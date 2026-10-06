package net.exoad.kira.compiler.backend.codegen.py

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppCompilerVersion
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import java.nio.file.Files
import java.nio.file.Path

/** One generated Python module: where it goes, its text, and the Kira module it is. */
data class PyPlannedFile(val path: Path, val text: String, val module: String)

/** Where [module]'s generated file finds the generated files of the workspace modules it uses. */
class PyImports(program: TypedProgram, private val module: ModuleSymbol, private val targets: Map<ModuleSymbol, Path>) {
    /** The workspace modules that [module] uses and that use it back, directly or not. */
    val cycle: Set<ModuleSymbol> = program.graph.transitiveUses(module)
        .filter { !it.isStdlib && it !== module && module in program.graph.transitiveUses(it) }
        .toSet()

    /** Null across Windows drives. */
    fun path(used: ModuleSymbol): String? {
        val from = targets[module]?.parent ?: return null
        val to = targets[used] ?: return null
        return runCatching { from.relativize(to).toString().replace('\\', '/') }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/** What one `--target py` run produced. */
data class PyBackendResult(
    val exitCode: Int,
    val diagnostics: List<CppDiagnostic>,
    val planned: List<PyPlannedFile>,
    /** Files written (write mode) or found drifted or missing (check mode). */
    val changed: List<Path>,
)

/**
 * `kira --target py`: each workspace module `x.kira` becomes `x.kira.py` beside it, or under
 * `--out <dir>` at its path relative to the project root. The program is typed STRICT, as for
 * C++; a typer error or a construct the py target refuses ([PyModuleEmitter]) writes nothing.
 * Each file starts with a comment naming the compiler's commit and the source, and carries the
 * runtime helpers and Kira-written stdlib functions it uses, so it loads nothing of Kira's but the
 * generated files of the modules it uses ([PyImports]). `--check` regenerates in memory and
 * names each file that differs from disk (CRLF read as LF) or is missing, exiting 1.
 */
object KiraPyBackend {
    const val EXTENSION = ".py"
    const val INTERNAL_CODE = "py.internal"

    fun run(
        unit: CompilationUnit,
        check: Boolean,
        projectRoot: Path,
        outDirOverride: String? = null,
        version: String = CppCompilerVersion.current(),
        log: (String) -> Unit = { Diagnostics.Logging.info("Kira", it) },
        report: (String) -> Unit = { Diagnostics.Logging.warn("Kira", it) },
        out: (String) -> Unit = { println(it) },
    ): PyBackendResult {
        val root = projectRoot.toAbsolutePath().normalize()
        val (diagnostics, planned) = plan(unit, root, outDirOverride, version)
        diagnostics.forEach { report(it.render()) }
        if (diagnostics.any { it.isError }) {
            val errors = diagnostics.count { it.isError }
            report("py backend: $errors error${if (errors == 1) "" else "s"}; no file was written")
            return PyBackendResult(1, diagnostics, planned, emptyList())
        }
        val differing = planned.filter { !matchesDisk(it) }
        if (check) {
            differing.forEach { out("drift: ${shown(root, it.path)} (${if (Files.exists(it.path)) "differs" else "missing"})") }
            if (differing.isEmpty()) {
                log("py check: ${planned.size} generated files are current")
                return PyBackendResult(0, diagnostics, planned, emptyList())
            }
            report("py check: ${differing.size} of ${planned.size} generated files drifted; run `kira --target py` and commit the result")
            return PyBackendResult(1, diagnostics, planned, differing.map { it.path })
        }
        differing.forEach { file ->
            Files.createDirectories(file.path.parent)
            Files.write(file.path, file.text.toByteArray(Charsets.UTF_8))
        }
        log("Emitting py: wrote ${differing.size}, unchanged ${planned.size - differing.size}")
        return PyBackendResult(0, diagnostics, planned, differing.map { it.path })
    }

    /** Every workspace module typed and emitted in memory: the diagnostics, and the files a write would hold. */
    fun plan(
        unit: CompilationUnit,
        projectRoot: Path,
        outDirOverride: String? = null,
        version: String = CppCompilerVersion.current(),
    ): Pair<List<CppDiagnostic>, List<PyPlannedFile>> {
        val root = projectRoot.toAbsolutePath().normalize()
        val diagnostics = mutableListOf<CppDiagnostic>()
        val planned = mutableListOf<PyPlannedFile>()
        val program = KiraTyper.run(unit, TyperMode.STRICT, TyperOptions())
        diagnostics += program.diagnostics.map { CppModuleEmitterFactory.convert(it) }
        val runtime = PyRuntime.load(program)
        if (runtime == null) {
            diagnostics += CppDiagnostic(INTERNAL_CODE, "the stdlib has no py/${PyRuntime.FILE} beside its modules")
        }
        if (diagnostics.none { it.isError } && runtime != null) {
            val bindings = PyBindingTable().apply { load(program) }
            val usage = CppUsage.scan(program)
            val targets = LinkedHashMap<ModuleSymbol, Path>()
            program.workspaceModules.forEach { m ->
                val source = Path.of(m.source.file).toAbsolutePath().normalize()
                targets[m] = if (outDirOverride.isNullOrBlank()) {
                    source.resolveSibling(source.fileName.toString() + EXTENSION)
                } else {
                    root.resolve(outDirOverride).resolve(shown(root, source) + EXTENSION).normalize()
                }
            }
            program.workspaceModules.forEach { m ->
                val source = Path.of(m.source.file).toAbsolutePath().normalize()
                val relative = shown(root, source)
                val target = targets.getValue(m)
                val header = listOf(
                    "# Generated by Kira $version (--target py) from $relative, module ${m.uri}.",
                    "# Do not edit: change the .kira file and run the compiler again.",
                )
                val emitter = PyModuleEmitter(program, m, bindings, runtime, PyImports(program, m, targets), usage = usage)
                val text = try {
                    emitter.emit(header)
                } catch (e: RuntimeException) {
                    diagnostics += CppDiagnostic(INTERNAL_CODE, "internal compiler error: ${e.javaClass.simpleName}: ${e.message}", file = m.source.file)
                    null
                }
                diagnostics += emitter.diagnostics
                if (text != null) {
                    planned += PyPlannedFile(target, text, m.uri)
                }
            }
        }
        return diagnostics to planned
    }

    /** [path] relative to [root] with `/`, or the absolute path when it lies outside. */
    private fun shown(root: Path, path: Path): String =
        if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/') else path.toString().replace('\\', '/')

    private fun matchesDisk(file: PyPlannedFile): Boolean {
        if (!Files.isRegularFile(file.path)) {
            return false
        }
        return String(Files.readAllBytes(file.path), Charsets.UTF_8).replace("\r\n", "\n") == file.text
    }
}
