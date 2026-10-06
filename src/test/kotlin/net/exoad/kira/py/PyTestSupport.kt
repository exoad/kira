package net.exoad.kira.py

import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.py.KiraPyBackend
import net.exoad.kira.compiler.backend.codegen.py.PyPlannedFile
import net.exoad.kira.types.TyperTestSupport
import java.io.File
import java.nio.file.Path

/** The py target's test helpers: an in-memory emit of a snippet, the CLI as a subprocess, and Python. */
object PyTestSupport {
    val repoRoot = File(System.getProperty("user.dir"))

    /** What [emit] produced: the module's Python (null when refused) and every diagnostic. */
    data class Emitted(val text: String?, val diagnostics: List<CppDiagnostic>, val files: List<PyPlannedFile>) {
        val errors: List<String> get() = diagnostics.filter { it.isError }.map { it.render() }

        fun python(): String = text ?: error("the py target refused the module:\n${errors.joinToString("\n")}")
    }

    /** [body] as module [uri], typed with the repository's stdlib and emitted in memory. */
    fun emit(body: String, uri: String = "test:main"): Emitted {
        val unit = TyperTestSupport.unitOf(TyperTestSupport.module(uri, body))
        val (diagnostics, planned) = KiraPyBackend.plan(unit, repoRoot.toPath(), version = "test")
        return Emitted(planned.firstOrNull()?.text, diagnostics, planned)
    }

    /** The modules [modules] (URI to body) as one program, emitted in memory: each module's Python by URI, and every error. */
    fun emitProgram(vararg modules: Pair<String, String>): Pair<Map<String, String>, List<String>> {
        val unit = TyperTestSupport.unitOf(*modules.map { (uri, body) -> TyperTestSupport.module(uri, body) }.toTypedArray())
        val (diagnostics, planned) = KiraPyBackend.plan(unit, repoRoot.toPath(), version = "test")
        return planned.associate { it.module to it.text } to diagnostics.filter { it.isError }.map { it.render() }
    }

    data class Run(val exitCode: Int, val stdout: String, val stderr: String) {
        val all: String get() = "$stdout\n$stderr"
    }

    fun run(command: List<String>, dir: File): Run {
        val proc = ProcessBuilder(command).directory(dir).start()
        val err = StringBuilder()
        val reader = Thread { err.append(proc.errorStream.bufferedReader(Charsets.UTF_8).readText()) }
        reader.start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        reader.join()
        return Run(proc.waitFor(), out.replace("\r", ""), err.toString())
    }

    /** The real CLI (`net.exoad.kira.cli.MainKt`) in [dir], as CppCliTest runs it. */
    fun cli(dir: File, vararg args: String): Run {
        val java = System.getProperty("java.home") + "/bin/java"
        return run(listOf(java, "-cp", System.getProperty("java.class.path"), "net.exoad.kira.cli.MainKt") + args, dir)
    }

    /**
     * The Python the run tests use: `$KIRA_PYTHON` when set, else `python3` or `python` on PATH.
     * A Windows Store alias (under WindowsApps) is never run: with no Store Python installed it
     * opens the Store.
     */
    val python: String? by lazy {
        System.getenv("KIRA_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@lazy it }
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() && !it.contains("WindowsApps") }
        for (name in listOf("python3", "python")) {
            for (dir in dirs) {
                val candidate = File(dir, if (windows) "$name.exe" else name)
                if (candidate.isFile) {
                    return@lazy candidate.absolutePath
                }
            }
        }
        null
    }

    /** The version line of [python] (`3.10.11`), for the test output. */
    val pythonVersion: String by lazy {
        val p = python ?: return@lazy "none"
        run(listOf(p, "-c", "import sys; print('%d.%d.%d' % sys.version_info[:3])"), repoRoot).stdout.trim()
    }

    /** The builtins [files] read by a bare name that is neither in [reserved] nor bound in the file: a Kira name could shadow each. */
    fun unreservedBuiltins(files: List<Path>, reserved: Set<String>): List<String> {
        val p = python ?: return emptyList()
        val script = """
            import ast, builtins, sys
            reserved = set(sys.argv[1].split(','))
            out = set()
            for path in sys.argv[2:]:
                tree = ast.parse(open(path, encoding='utf-8').read(), path)
                bound = set()
                for n in ast.walk(tree):
                    if isinstance(n, ast.Name) and not isinstance(n.ctx, ast.Load):
                        bound.add(n.id)
                    elif isinstance(n, (ast.FunctionDef, ast.ClassDef)):
                        bound.add(n.name)
                    elif isinstance(n, ast.arg):
                        bound.add(n.arg)
                for n in ast.walk(tree):
                    if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load) and hasattr(builtins, n.id):
                        if not n.id.startswith('__') and n.id not in reserved and n.id not in bound:
                            out.add(n.id)
            print(' '.join(sorted(out)))
        """.trimIndent()
        val r = run(listOf(p, "-c", script, reserved.joinToString(",")) + files.map { it.toString() }, repoRoot)
        check(r.exitCode == 0) { r.all }
        return r.stdout.trim().split(" ").filter { it.isNotEmpty() }
    }

    /** Whether [file] parses as Python 3.10 source: null when it does, else the error. */
    fun parsesAs310(file: Path): String? {
        val p = python ?: return "no python"
        val r = run(
            listOf(p, "-c", "import ast, sys; ast.parse(open(sys.argv[1], encoding='utf-8').read(), sys.argv[1], feature_version=(3, 10))", file.toString()),
            repoRoot,
        )
        return if (r.exitCode == 0) null else r.all
    }
}
