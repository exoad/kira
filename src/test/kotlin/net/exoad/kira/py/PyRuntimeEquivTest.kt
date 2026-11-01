package net.exoad.kira.py

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The py runtime harness (src/test/resources/py-rt/rt_equiv.py) run on kira/py/runtime.py against itself and against
 * planted mutants it must tell apart. Skipped without a Python.
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
        // The dead blocks are deleted: the only skips are the cases of the dead names, all counted as such.
        assertEquals("gone", harness("strip-dead", "--rt", rt(), "--out", File(scratch, "stripped0.py").absolutePath).one("dead.state"), "a dead block is back in the runtime")
        assertEquals(0, r.int("skipped") - r.int("skipped_dead"), "a case was skipped that no dead name explains")
        assertTrue(r.int("skipped_dead") > 0, "the runtime lost the dead names but no case was skipped for them")
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
        // A ratchet: the lines left are error paths of sockets, pipes and imports; new code needs new cases.
        assertTrue(pct >= 93.0, "line coverage fell to $pct% (${r.int("lines_uncovered")} of $lines lines)")
        val unrun = r.one("branches_uncovered")
        println("host ${r.one("platform")}: line coverage $pct%; host-only branches not run here: ${unrun.ifEmpty { "none" }}")
        // The host-only branches are run through fakes on any host, so none may be left; a list here is never silent.
        assertEquals("", unrun, "host-only branches the corpus did not run on ${r.one("platform")}")
        assertEquals("", r.one("branches_absent"), "a host-only branch whose line text is no longer in the runtime")
        // The check can fail: the same behaviour in other words leaves the POSIX kill line unfound, and that is a failure.
        val reworded = variant("kill-reworded.py", "            self.p.kill()", "            self.p.kill( )")
        val w = harness("coverage", "--rt", reworded.absolutePath)
        assertEquals("process-kill", w.one("branches_absent"), w.raw)
        assertTrue("process-kill" in w.one("branches_uncovered").split(","), w.raw)
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
        assertEquals(0, r.int("flakes"), "a difference that did not repeat was refused as a catch")
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
        // The shared Error's base: a throw must not be a RuntimeError, which is what a panic raises.
        val base = variant("error-base.py", "(_k_builtins.Exception,), {\"__module__\": \"kira:errors\"}", "(_k_builtins.RuntimeError,), {\"__module__\": \"kira:errors\"}")
        val b = harness("equiv", "--a", rt(), "--b", base.absolutePath)
        assertEquals(1, b.code, b.raw)
        assertTrue(b.lines["shape_diff"].orEmpty().any { "_k_Error" in it }, b.raw)
        val gone = variant("function-gone.py", "def _k_hex(v):\n    return \"%x\" % v\n", "")
        val g = harness("equiv", "--a", rt(), "--b", gone.absolutePath)
        assertEquals(1, g.code)
        assertTrue(g.lines["shape_diff"].orEmpty().any { it.startsWith("only in A: _k_hex") }, g.raw)
        // _k_hex is no dead name: its cases are skipped, and only the runtime's own dead-name skips may count as dead.
        val dead = harness("equiv", "--a", rt(), "--b", rt()).int("skipped_dead")
        assertTrue(g.int("skipped") > dead, "the cases of the removed function were not skipped: ${g.raw}")
        assertEquals(dead, g.int("skipped_dead"), "a skip was counted as dead for a live function: ${g.raw}")
    }

    /** The runtime as it stood before the dead blocks went: [functions] at its end and, if [methods], each dead class's `_k_set`. */
    private fun withDeadBlocks(name: String, functions: Boolean = true, methods: Boolean = true): File {
        var text = runtime.readText()
        val fields = mapOf(
            "_k_Datagram" to listOf("size", "host", "port"), "_k_Ready" to listOf("handle", "events"), "_k_Ran" to listOf("code", "out", "err"),
            "_k_HttpReply" to listOf("status", "body"), "_k_HttpRequest" to listOf("method", "path", "host", "body"),
            "_k_HttpAnswer" to listOf("status", "contentType", "body", "headers"),
        )
        if (methods) for ((cls, names) in fields) {
            val at = text.indexOf("\nclass $cls:\n")
            val eq = text.indexOf("    def __eq__(self, o):", at)
            assertTrue(at >= 0 && eq > at, "$cls has no __eq__ to put _k_set before")
            val method = "    def _k_set(self, o):\n" + names.joinToString("") { "        self.$it = o.$it\n" } + "\n"
            text = text.substring(0, eq) + method + text.substring(eq)
        }
        if (functions) text += "\n\ndef _k_dqset(q, v):\n    if q is not v:\n        q.clear()\n        q.extend(v)\n\n\ndef _k_mapset(m, v):\n    if m is not v:\n        m.clear()\n        m.update(v)\n"
        return File(scratch, name).also { it.writeText(text, Charsets.UTF_8) }
    }

    @Test
    fun `the names the corpus lists as dead may go without the harness failing`() {
        // The runtime before the deletion, rebuilt: it strips to the runtime as it stands, and the corpus stands on both.
        val before = withDeadBlocks("before-deletion.py")
        val stripped = File(scratch, "stripped.py")
        val s = harness("strip-dead", "--rt", before.absolutePath, "--out", stripped.absolutePath)
        println("dead names: functions ${s.one("dead.functions")}, classes ${s.one("dead.classes")}, methods ${s.one("dead.methods")} (${s.one("dead.state")}); stripped from the copy: ${s.one("stripped")}")
        assertEquals("present", s.one("dead.state"), s.raw)
        assertEquals(2 + 6, s.int("stripped"), "the stripped count is the two functions and six methods")
        // Its other cases all still equal, the dead ones (3 dqset, 3 mapset and 6 method cases) skipped and counted as dead.
        for (b in listOf(stripped.absolutePath, rt())) {
            val r = harness("equiv", "--a", before.absolutePath, "--b", b)
            assertEquals(0, r.code, r.raw)
            assertEquals(0, r.int("mismatches"))
            assertEquals(12, r.int("skipped"), "the dead cases were not the ones skipped")
            assertEquals(r.int("skipped"), r.int("skipped_dead"), "a skipped case is not a dead name's")
        }
        // The runtime as it stands against itself: the same 12 skips; and what is left reads as gone, no function and no method.
        val both = harness("equiv", "--a", rt(), "--b", rt())
        assertEquals(0, both.code, both.raw)
        assertEquals(12, both.int("skipped"), "the runtime against itself skips the dead cases")
        assertEquals(both.int("skipped"), both.int("skipped_dead"), both.raw)
        val again = harness("strip-dead", "--rt", rt(), "--out", File(scratch, "stripped2.py").absolutePath)
        assertEquals("gone", again.one("dead.state"), again.raw)
        assertEquals("0/2", again.one("dead.functions"))
        assertEquals("0", again.one("stripped"))
        // The check can fail: one dead function back in a runtime that has none (or a third copy of one) is a part, not all.
        val drifted = File(scratch, "dead-drifted.py")
        drifted.writeText(runtime.readText() + "\n\ndef _k_mapset(m, v):\n    return m\n", Charsets.UTF_8)
        val d = harness("strip-dead", "--rt", drifted.absolutePath, "--out", File(scratch, "stripped3.py").absolutePath)
        assertEquals("DRIFTED", d.one("dead.state"), d.raw)
        // Half a deletion is a part too: only the functions back, only the methods back.
        val halves = mapOf(
            "functions-only" to withDeadBlocks("dead-half-functions-only.py", methods = false),
            "methods-only" to withDeadBlocks("dead-half-methods-only.py", functions = false),
        )
        for ((id, f) in halves) {
            val h = harness("strip-dead", "--rt", f.absolutePath, "--out", File(scratch, "stripped-$id.py").absolutePath)
            assertEquals("DRIFTED", h.one("dead.state"), "$id: ${h.raw}")
        }
    }

    @Test
    fun `a missing _k_set is dead on a listed class only`() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val code = """
            import sys
            sys.dont_write_bytecode = True
            sys.path.insert(0, ${"\"" + dir.absolutePath.replace('\\', '/') + "\""})
            import rt_equiv
            class Rt:
                ns = {"_k_Datagram": 1, "_k_Mutex": 1}
            class Case:
                def __init__(self, *needs): self.needs = needs
            rts = [Rt(), Rt()]
            for need in ("_k_Datagram._k_set", "_k_Mutex._k_set", "_k_mapset", "_k_hex", "_k_Datagram._k_other"):
                print(need, rt_equiv.dead_skip(rts, Case(need)))
        """.trimIndent()
        val run = PyTestSupport.run(listOf(PyTestSupport.python!!, "-c", code), PyTestSupport.repoRoot)
        assertEquals(0, run.exitCode, run.all)
        val verdict = run.stdout.lines().filter { ' ' in it }.associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("True", verdict["_k_Datagram._k_set"], "the method of a dead class is dead: ${run.all}")
        assertEquals("True", verdict["_k_mapset"], run.all)
        assertEquals("False", verdict["_k_Mutex._k_set"], "_k_set of a live class is not dead: ${run.all}")
        assertEquals("False", verdict["_k_hex"], run.all)
        assertEquals("False", verdict["_k_Datagram._k_other"], run.all)
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
        // Nor is a name that only looks bound: a bare annotation, a global never assigned, a del, an except-as, a loop over nothing.
        val use = "\n\ndef _k_planted():\n    return _k_clone(1)\n"
        val looks = mapOf(
            "annotation" to "_k_clone: object\n",
            "global" to "def _k_g():\n    global _k_clone\n    return _k_clone\n",
            "del" to "del _k_clone\n",
            "except-as" to "try:\n    pass\nexcept ValueError as _k_clone:\n    pass\n",
            "empty-for" to "for _k_clone in ():\n    pass\n",
            "augassign" to "_k_clone += 1\n",
            "global-aug" to "def _k_g():\n    global _k_clone\n    _k_clone += 1\n",
            "global-nested-local" to "def _k_g():\n    global _k_clone\n\n    def inner():\n        _k_clone = 1\n    return inner\n",
            "global-empty-for" to "def _k_g():\n    global _k_clone\n    for _k_clone in ():\n        pass\n",
        )
        for ((id, text) in looks) {
            val f = File(scratch, "planted-looks-$id.py")
            f.writeText(text + use, Charsets.UTF_8)
            val r2 = harness("closure", "--rt", rt(), "--append", f.absolutePath)
            assertEquals(1, r2.code, "$id must leave _k_clone undefined: ${r2.raw}")
            assertTrue(r2.lines["undefined"].orEmpty().any { it.startsWith("_k_clone:") }, "$id: ${r2.raw}")
        }
        // And the real bindings still count: an assignment, a valued annotation, a global assigned in a function, a loop over items.
        val real = mapOf(
            "assign" to "_k_clone = print\n",
            "annotated" to "_k_clone: object = print\n",
            "global" to "def _k_g():\n    global _k_clone\n    _k_clone = print\n",
            "for" to "for _k_clone in (print,):\n    pass\n",
            "global-nested-global" to "def _k_g():\n    def inner():\n        global _k_clone\n        _k_clone = print\n    return inner\n",
        )
        for ((id, text) in real) {
            val f = File(scratch, "planted-real-$id.py")
            f.writeText(text + use, Charsets.UTF_8)
            val r3 = harness("closure", "--rt", rt(), "--append", f.absolutePath)
            assertEquals(0, r3.code, "$id binds _k_clone: ${r3.raw}")
        }
        // An except-as name is bound inside its handler: a use there is defined, a use after it is not.
        val inside = File(scratch, "planted-handler-inside.py")
        inside.writeText("try:\n    pass\nexcept ValueError as _k_x:\n    print(_k_x)\n", Charsets.UTF_8)
        assertEquals(0, harness("closure", "--rt", rt(), "--append", inside.absolutePath).code)
        val after = File(scratch, "planted-handler-after.py")
        after.writeText("try:\n    pass\nexcept ValueError as _k_x:\n    print(_k_x)\nprint(_k_x)\n", Charsets.UTF_8)
        val a = harness("closure", "--rt", rt(), "--append", after.absolutePath)
        assertEquals(1, a.code, a.raw)
        assertTrue(a.one("undefined").startsWith("_k_x:"), a.raw)
        // The name is gone when the handler ends, so a def, lambda or generator inside it that reads it fails when called later.
        val deferred = mapOf(
            "def" to "try:\n    pass\nexcept ValueError as _k_x:\n    def f():\n        return _k_x\nf()\n",
            "lambda" to "try:\n    pass\nexcept ValueError as _k_x:\n    f = lambda: _k_x\nf()\n",
            "generator" to "try:\n    pass\nexcept ValueError as _k_x:\n    g = (_k_x for _ in (1,))\nlist(g)\n",
        )
        for ((id, text) in deferred) {
            val f = File(scratch, "planted-handler-$id.py")
            f.writeText(text, Charsets.UTF_8)
            val r4 = harness("closure", "--rt", rt(), "--append", f.absolutePath)
            assertEquals(1, r4.code, "a $id in the handler reads a name that is gone by then: ${r4.raw}")
            assertTrue(r4.one("undefined").startsWith("_k_x:"), "$id: ${r4.raw}")
        }
        // What runs at once in the handler still counts: a comprehension, and a use on the line of an unrelated lambda.
        val now = File(scratch, "planted-handler-now.py")
        now.writeText("try:\n    pass\nexcept ValueError as _k_x:\n    print([_k_x for _ in (1,)]); f = lambda: 1\n", Charsets.UTF_8)
        assertEquals(0, harness("closure", "--rt", rt(), "--append", now.absolutePath).code)
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
        // Each plant raises only for its feature, in a helper the corpus calls: it passes where the feature exists, and
        // on 3.10 the mismatch names it.
        class Plant(val expr: String, val since: Int, val names: String)
        val plants = mapOf(
            "math-sumprod" to Plant("__import__(\"math\").sumprod([1], [1])", 12, "sumprod"),
            "from-bytes-no-order" to Plant("int.from_bytes(b\"\\x01\")", 11, "byteorder"),
            "atomic-group" to Plant("__import__(\"re\").compile(\"(?>ab)+\")", 11, "unknown extension"),
            "popen-process-group" to Plant("__import__(\"inspect\").signature(__import__(\"subprocess\").Popen).parameters[\"process_group\"]", 11, "process_group"),
        )
        val here = harness("parse", "--rt", rt()).one("interpreter").split(".")[1].toInt()
        for ((id, plant) in plants) {
            val planted = variant("later-$id.py", "def _k_hex(v):\n    return \"%x\" % v\n", "def _k_hex(v):\n    _k_unused = ${plant.expr}\n    return \"%x\" % v\n")
            val p = harness("equiv", "--a", rt(), "--b", planted.absolutePath, py = py)
            assertEquals(1, p.code, "$id must fail on Python 3.10")
            assertTrue(p.int("mismatches") > 0, p.raw)
            assertTrue(p.lines["mismatch"].orEmpty().any { plant.names in it }, "$id: the mismatch must name ${plant.names}: ${p.raw.take(2000)}")
            if (here >= plant.since) {
                val ok = harness("equiv", "--a", rt(), "--b", planted.absolutePath)
                assertEquals(0, ok.code, "$id must pass on the default Python 3.$here: ${ok.raw.take(2000)}")
                assertEquals(0, ok.int("mismatches"), "$id on 3.$here")
            }
        }
    }

    @Test
    fun `the microbench holds the runtime to itself and catches a slowdown`() {
        // The 1.2x bound is what a replacement block is held to; here both sides are the same text, so the
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
