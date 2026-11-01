package net.exoad.kira.py

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The py runtime harness (src/test/resources/py-rt/rt_equiv.py). It loads two runtimes into separate namespaces, calls
 * every `_k_` helper over a boundary corpus and compares values, exceptions, reasons, output and files. Here the two
 * are kira/py/runtime.py against itself, then against planted mutants it must tell apart. Skipped without a Python.
 */
class PyRuntimeEquivTest {
    private val dir = File(PyTestSupport.repoRoot, "src/test/resources/py-rt")
    private val runtime = File(PyTestSupport.repoRoot, "kira/py/runtime.py")
    private val scratch = File(PyTestSupport.repoRoot, "build/tmp/py-rt").also { it.mkdirs() }

    /** The harness in [mode] under [python]; its key=value lines by key, and the exit code. */
    private class Result(val code: Int, val lines: Map<String, List<String>>, val raw: String) {
        fun one(key: String): String = lines[key]?.singleOrNull() ?: error("no single $key in:\n$raw")
        fun int(key: String): Int = one(key).toInt()
    }

    /** An interpreter command, and how a path of this machine reads to it (a WSL distro sees C:\\x as /mnt/c/x). */
    private class Py(val cmd: List<String>, val label: String, val path: (String) -> String = { it })

    /** PY310: a Python 3.10 path, or `wsl:<distro>` for that distro's python3.10 (the Linux run); null when unset. */
    private fun py310(): Py? {
        val v = System.getenv("PY310")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!v.startsWith("wsl:")) return Py(listOf(v), v)
        val distro = v.removePrefix("wsl:")
        return Py(listOf("wsl", "-d", distro, "--", "python3.10"), v) { p ->
            if (p.length > 2 && p[1] == ':') "/mnt/" + p[0].lowercaseChar() + p.substring(2).replace('\\', '/') else p
        }
    }

    private fun harness(mode: String, vararg args: String, py: Py? = null): Result {
        val use = py ?: PyTestSupport.python?.let { Py(listOf(it), it) }
        assumeTrue(use != null, "no Python on PATH (set KIRA_PYTHON)")
        val argv = args.map { if (it.startsWith("--") || !(File(it).isAbsolute)) it else use!!.path(it) }
        val cmd = use!!.cmd + listOf(use.path(File(dir, "rt_equiv.py").absolutePath), mode) + argv + listOf("--repo", use.path(PyTestSupport.repoRoot.absolutePath))
        val run = PyTestSupport.run(cmd, PyTestSupport.repoRoot)
        println("rt_equiv $mode ${args.joinToString(" ")} [${use.label}]: exit ${run.exitCode}\n${run.stdout.lines().filter { !it.startsWith("kind.") && !it.startsWith("outcome.") }.joinToString("\n")}")
        if (run.stderr.isNotBlank()) println("stderr:\n${run.stderr.take(3000)}")
        // The harness never writes bytecode: a __pycache__ in py-rt would mean a mutant or a corpus module hit the disk.
        assertTrue(dir.walkTopDown().none { it.name == "__pycache__" }, "a __pycache__ appeared under $dir")
        val lines = run.stdout.lines().filter { '=' in it }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
        return Result(run.exitCode, lines, run.all)
    }

    private fun rt() = runtime.absolutePath

    /** A copy of the runtime with [old] (which must occur once) replaced by [new]. */
    private fun variant(name: String, old: String, new: String): File {
        val text = runtime.readText()
        assertEquals(1, Regex(Regex.escape(old)).findAll(text).count(), "$old must occur once in the runtime")
        return File(scratch, name).also { it.writeText(text.replace(old, new), Charsets.UTF_8) }
    }

    @Test
    fun `the runtime equals itself over the whole corpus`() {
        val r = harness("equiv", "--a", rt(), "--b", rt())
        assertEquals(0, r.code, r.raw)
        assertEquals(0, r.int("mismatches"))
        assertEquals(0, r.int("shape_diffs"))
        assertEquals(0, r.int("skipped"), "a case was skipped on the unchanged runtime")
        assertTrue(r.int("cases") >= 30000, "only ${r.int("cases")} cases")
        for (kind in listOf("int", "float", "text", "slice", "bytes", "radix", "json", "collections", "os", "host", "ffi", "misc")) {
            assertTrue(r.int("kind.$kind") >= 100, "kind $kind has ${r.lines["kind.$kind"]} cases")
        }
        // A corpus whose calls all fail the same way would pass while measuring nothing.
        val ok = r.int("outcome.ok")
        val raised = r.lines.filterKeys { it.startsWith("outcome.exc") }.values.sumOf { it.single().toInt() }
        assertTrue(ok >= 28000, "only $ok cases returned a value")
        assertTrue(raised in 1000..(ok / 5), "$raised cases raised against $ok that returned")
        // Misuse of an API by the corpus shows as these, never as the runtime's own errors.
        for (bad in listOf("outcome.exc_NameError", "outcome.exc_UnboundLocalError", "outcome.exc_SyntaxError", "outcome.exc_ModuleNotFoundError")) {
            assertTrue(bad !in r.lines, "$bad: ${r.lines[bad]}")
        }
    }

    @Test
    fun `the corpus runs the runtime's lines, and says which host-only branches it did not`() {
        val r = harness("coverage", "--rt", rt())
        assertEquals(0, r.code, r.raw)
        assertEquals("", r.one("never_entered"), "a function or method no case entered")
        val lines = r.int("lines")
        assertTrue(lines >= 1400, "only $lines executable lines found")
        val pct = r.one("line_coverage").toDouble()
        // A ratchet: the lines left are error paths of sockets, pipes and imports; a lane that adds code adds cases.
        assertTrue(pct >= 93.0, "line coverage fell to $pct% (${r.int("lines_uncovered")} of $lines lines)")
        val unrun = r.one("branches_uncovered")
        println("host ${r.one("platform")}: line coverage $pct%; host-only branches not run here: ${unrun.ifEmpty { "none" }}")
        // The host-only branches are run through fakes on any host, so none may be left; a list here is never silent.
        assertEquals("", unrun, "host-only branches the corpus did not run on ${r.one("platform")}")
        val (compared, constants) = r.one("constants_compared").split("/").map { it.toInt() }
        assertEquals(constants, compared)
    }

    @Test
    fun `every planted mutant is caught and the controls are not`() {
        val r = harness("mutants", "--rt", rt())
        assertEquals(0, r.code, r.raw)
        assertEquals(0, r.int("missed"))
        assertEquals(0, r.int("pycache_dirs"))
        println("mutants: ${r.one("mutants")}, flaky differences refused as catches: ${r.one("flakes")}")
        val mutants = r.lines.filterKeys { it.startsWith("mutant.") }
        assertTrue(mutants.size >= 55, "only ${mutants.size} mutants")
        for ((id, verdict) in mutants) {
            if (id.startsWith("mutant.control-")) {
                assertEquals(listOf("MISSED"), verdict, "$id is a no-op, so nothing may catch it")
            } else {
                assertTrue(verdict.single().startsWith("caught by "), "$id: $verdict")
            }
        }
    }

    @Test
    fun `a changed value, signature or method set fails the comparison`() {
        val value = variant("i8-wrong.py", "    return ((v + 0x80) & 0xFF) - 0x80", "    return ((v + 0x80) & 0x1FF) - 0x80")
        val v = harness("equiv", "--a", rt(), "--b", value.absolutePath)
        assertEquals(1, v.code)
        assertTrue(v.int("mismatches") > 0, v.raw)
        val arity = variant("min-arity.py", "def _k_min(a, b):", "def _k_min(a, b, c=0):")
        val a = harness("equiv", "--a", rt(), "--b", arity.absolutePath)
        assertEquals(1, a.code)
        assertEquals(0, a.int("mismatches"), "the values are unchanged; only the signature differs")
        assertTrue(a.lines["shape_diff"].orEmpty().any { "_k_min" in it }, a.raw)
        val methods = variant("method-renamed.py", "    def waitUntil(self, pred, ms):", "    def waitFor(self, pred, ms):")
        val m = harness("equiv", "--a", rt(), "--b", methods.absolutePath)
        assertEquals(1, m.code)
        assertTrue(m.lines["shape_diff"].orEmpty().any { "_k_Mutex" in it }, m.raw)
        // The sentinel, the per-thread reasons and the shared decoder are compared too, not passed as opaque objects.
        val decoder = variant("decoder-lax.py", "_k_jdecoder = _k_json.JSONDecoder()", "_k_jdecoder = _k_json.JSONDecoder(strict=False)")
        val d = harness("equiv", "--a", rt(), "--b", decoder.absolutePath)
        assertEquals(1, d.code)
        assertTrue(d.lines["shape_diff"].orEmpty().any { "_k_jdecoder" in it }, d.raw)
        val unset = variant("unset-none.py", "_k_unset = object()", "_k_unset = None")
        val u = harness("equiv", "--a", rt(), "--b", unset.absolutePath)
        assertEquals(1, u.code)
        assertTrue(u.lines["shape_diff"].orEmpty().any { "_k_unset" in it }, u.raw)
        val local = variant("reasons-plain.py", "_k_reasons = _k_errors().__dict__.setdefault(\"reasons\", _k_threading.local())", "_k_reasons = _k_errors().__dict__.setdefault(\"reasons\", _k_types.SimpleNamespace())")
        val l = harness("equiv", "--a", rt(), "--b", local.absolutePath)
        assertEquals(1, l.code)
        val gone = variant("function-gone.py", "def _k_hex(v):\n    return \"%x\" % v\n", "")
        val g = harness("equiv", "--a", rt(), "--b", gone.absolutePath)
        assertEquals(1, g.code)
        assertTrue(g.lines["shape_diff"].orEmpty().any { it.startsWith("only in A: _k_hex") }, g.raw)
    }

    @Test
    fun `the names the dead-code lane deletes may go without the harness failing`() {
        val stripped = File(scratch, "stripped.py")
        val s = harness("strip-dead", "--rt", rt(), "--out", stripped.absolutePath)
        println("dead names: functions ${s.one("dead.functions")}, classes ${s.one("dead.classes")}, methods ${s.one("dead.methods")} (${s.one("dead.state")}); stripped from the copy: ${s.one("stripped")}")
        // Every listed name is in the runtime or none is; a part means the lists in rt_cases.py no longer match it.
        assertTrue(s.one("dead.state") != "DRIFTED", "DEAD_FUNCTIONS, DEAD_CLASSES and DEAD_METHODS match only part of the runtime: ${s.raw}")
        // The corpus must have stood on the copy too: its other cases all still equal, the dead ones skipped.
        val r = harness("equiv", "--a", rt(), "--b", stripped.absolutePath)
        assertEquals(0, r.code, r.raw)
        assertEquals(0, r.int("mismatches"))
        if (s.one("dead.state") == "present") {
            assertEquals(2 + 6, s.int("stripped"), "the stripped count is the two functions and six methods")
            assertTrue(r.int("skipped") > 0, "the dead cases ran against a runtime without them")
        }
        // The check can fail: a runtime that renames one dead function leaves the lists matching only part of it.
        val renamed = variant("dead-renamed.py", "def _k_mapset(m, v):", "def _k_mapput(m, v):")
        val d = harness("strip-dead", "--rt", renamed.absolutePath, "--out", File(scratch, "stripped2.py").absolutePath)
        if (s.one("dead.state") == "present") assertEquals("DRIFTED", d.one("dead.state"))
    }

    @Test
    fun `every _k_ token the runtime uses is defined, comments aside`() {
        val r = harness("closure", "--rt", rt())
        assertEquals(0, r.code, r.raw)
        assertEquals(0, r.int("tokens_undefined"))
        assertEquals("", r.one("defined_twice"))
        // The check can fail: a use of a name nothing defines is found; the same name in a comment or a string is not.
        val planted = File(scratch, "planted-closure.py")
        planted.writeText("def _k_planted():\n    return _k_nosuch()\n\n\n# _k_commented is only a comment\n_k_text = \"_k_instring\"\n", Charsets.UTF_8)
        val p = harness("closure", "--rt", rt(), "--append", planted.absolutePath)
        assertEquals(1, p.code)
        assertEquals(1, p.int("tokens_undefined"), p.raw)
        assertTrue(p.one("undefined").startsWith("_k_nosuch:"), p.raw)
        // A name that exists only as a method, a parameter or a local is not a definition a call elsewhere can reach.
        val methods = File(scratch, "planted-methods.py")
        methods.writeText("def _k_planted():\n    return _k_clone(1) + _k_set(2)\n\n\ndef _k_local():\n    _k_inner = 1\n    return _k_inner + _k_inner2\n", Charsets.UTF_8)
        val m = harness("closure", "--rt", rt(), "--append", methods.absolutePath)
        assertEquals(1, m.code)
        val undefined = m.lines["undefined"].orEmpty().map { it.substringBefore(":") }.toSet()
        assertTrue(setOf("_k_clone", "_k_set", "_k_inner", "_k_inner2") == undefined, m.raw)
    }

    @Test
    fun `the runtime parses as Python 3_10 as far as a newer interpreter can say`() {
        val r = harness("parse", "--rt", rt())
        assertEquals(0, r.code, r.raw)
        assertEquals(0, r.int("parse310_problems"))
        val later = mapOf(
            "try:\n    pass\nexcept* ValueError:\n    pass\n" to "syntax",
            "import math\nx = math.cbrt(8)\n" to "later than 3.10",
            "x = f\"{\"a\"}\"\n" to "f-string",
            "def f[T](x):\n    return x\n" to "syntax",
        )
        for ((i, e) in later.entries.withIndex()) {
            val planted = File(scratch, "planted-310-$i.py")
            planted.writeText(e.key, Charsets.UTF_8)
            val p = harness("parse", "--rt", planted.absolutePath)
            assertEquals(1, p.code, "${e.key} must fail")
            assertTrue(p.lines["problem"].orEmpty().any { e.value in it }, p.raw)
        }
    }

    @Test
    fun `the runtime runs on a real Python 3_10 and a later feature is refused there`() {
        val py = py310()
        // An assumption, so the report counts a skip: set PY310 to a Python 3.10 path, or wsl:<distro> for a distro's python3.10.
        assumeTrue(py != null, "PY310 is not set: the runtime was not run on Python 3.10")
        val real = harness("parse", "--rt", rt(), py = py)
        assertEquals(0, real.code, real.raw)
        assertTrue(real.one("interpreter").startsWith("3.10"), "PY310 is ${real.one("interpreter")}")
        val eq = harness("equiv", "--a", rt(), "--b", rt(), py = py)
        assertEquals(0, eq.code, eq.raw)
        assertEquals(0, eq.int("mismatches"))
        // On a POSIX 3.10 the host-only branches run for real, so the coverage report may not list any.
        if (eq.one("python").startsWith("3.10")) {
            val cov = harness("coverage", "--rt", rt(), py = py)
            assertEquals(0, cov.code, cov.raw)
            println("python 3.10 host ${cov.one("platform")}: line coverage ${cov.one("line_coverage")}%")
            assertEquals("", cov.one("branches_uncovered"))
        }
        // What the name list and the feature_version parse let through, each in the body of a helper the corpus calls.
        val plants = mapOf(
            "math-sumprod" to "__import__(\"math\").sumprod([1], [1])",
            "from-bytes-no-order" to "int.from_bytes(b\"\\x01\")",
            "atomic-group" to "__import__(\"re\").compile(\"(?>ab)+\")",
            "popen-process-group" to "__import__(\"subprocess\").Popen([\"x\"], process_group=0)",
        )
        for ((id, expr) in plants) {
            val planted = variant("later-$id.py", "def _k_hex(v):\n    return \"%x\" % v\n", "def _k_hex(v):\n    _k_unused = $expr\n    return \"%x\" % v\n")
            val p = harness("equiv", "--a", rt(), "--b", planted.absolutePath, py = py)
            assertEquals(1, p.code, "$id must fail on Python 3.10")
            assertTrue(p.int("mismatches") > 0, p.raw)
        }
    }

    @Test
    fun `the microbench holds the runtime to itself and catches a slowdown`() {
        // The 1.2x bound is the gate of the lanes that replace a block; here both sides are the same text, so the
        // ratios sit near 1 and only noise could cross a looser bound.
        val r = harness("bench", "--a", rt(), "--b", rt(), "--bound", "1.5")
        assertEquals(0, r.code, r.raw)
        for (item in listOf("sort", "sum", "rdle", "jparse", "ftext", "import")) {
            assertTrue(r.one("bench.$item.a_us").toDouble() > 0.0, "bench $item measured nothing")
        }
        val slow = variant("rdle-slow.py", "def _k_rdle(b, at, n):\n    _k_span(b, at, n)\n", "def _k_rdle(b, at, n):\n    for _ in range(60):\n        _k_span(b, at, n)\n")
        val s = harness("bench", "--a", rt(), "--b", slow.absolutePath, "--bound", "1.2")
        assertEquals(1, s.code, s.raw)
        assertTrue(s.one("bench.rdle.ratio").toDouble() > 1.2, s.raw)
        assertTrue(s.int("over_bound") >= 1)
    }
}
