package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.source.SourceContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The compiler's own version, stamped into `VERSION`, the banner and
 * `kira.gen.manifest`: the git SHA the distribution was built from, or
 * `dev` when nothing says.
 *
 * Lookup order: the `kira.version` system property, the `KIRA_VERSION`
 * environment variable, a `net/exoad/kira/VERSION` classpath resource
 * (which a release build can add), then `dev`.
 */
object CppCompilerVersion {
    const val DEV = "dev"

    fun current(): String {
        System.getProperty("kira.version")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv("KIRA_VERSION")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val resource = CppCompilerVersion::class.java.getResourceAsStream("/net/exoad/kira/VERSION")
        if (resource != null) {
            resource.use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    return text
                }
            }
        }
        return DEV
    }
}

/** What one run produced, for callers that want more than an exit code. */
data class CppBackendResult(
    val exitCode: Int,
    val diagnostics: List<CppDiagnostic>,
    /** Every file the run planned, whether or not it was written. */
    val planned: List<CppPlannedFile>,
    /** Files written (write mode) or found drifted (check mode). */
    val changed: List<Path>,
) {
    val hasErrors: Boolean
        get() = diagnostics.any { it.isError }
}

/**
 * `kira --target cpp`: one header (and at most one source) per workspace
 * module, the Kira-written stdlib modules header-only under
 * `<runtimeDir>/kira/std/`, the runtime copied beside them with `VERSION`,
 * and `kira.gen.manifest` at the project root.
 *
 * Everything is planned in memory first. If any emitter or layout error
 * exists, nothing is written. Otherwise, in write mode, only files whose
 * bytes differ from disk are written; in check mode (`--check`) every
 * differing or missing file is named and the exit code is 1.
 */
object KiraCppBackend {
    const val DRIFT_PREFIX = "drift: "

    /** The design's entry: the project root is the process working directory. */
    fun run(unit: CompilationUnit, manifest: ProjectManifest?, check: Boolean): Int {
        return run(unit, manifest, check, Paths.get(".")).exitCode
    }

    fun run(
        unit: CompilationUnit,
        manifest: ProjectManifest?,
        check: Boolean,
        projectRoot: Path,
        outDirOverride: String? = null,
        emitterFactory: (CompilationUnit, CppOptions) -> CppModuleEmitter = CppModuleEmitterFactory::create,
        version: String = CppCompilerVersion.current(),
        stdlibCppDir: Path? = StdlibLayout.cppDir(),
        log: (String) -> Unit = { Diagnostics.Logging.info("Kira", it) },
        report: (String) -> Unit = { Diagnostics.Logging.warn("Kira", it) },
        out: (String) -> Unit = { println(it) },
    ): CppBackendResult {
        val root = projectRoot.toAbsolutePath().normalize()
        val options = buildOptions(manifest, outDirOverride)
        val diagnostics = mutableListOf<CppDiagnostic>()

        // 1. Which modules exist, and where their files go.
        val sources = mutableListOf<Pair<CppModuleRef, SourceContext>>()
        unit.allSources().forEach { source ->
            val uri = runCatching { source.getModuleUri() }.getOrNull()
            if (uri == null || uri.startsWith("(unknown)")) {
                diagnostics += CppDiagnostic(
                    "cpp.no-module",
                    "${source.file} declares no module; every C++ module needs `module \"pkg:path\"`",
                    file = source.file,
                )
                return@forEach
            }
            sources += CppModuleRef(uri, Path.of(source.file)) to source
        }
        sources.sortWith(compareBy({ it.first.uri }, { it.first.sourcePath.toString() }))
        val layout = CppModuleLayout(options, root, sources.map { it.first })
        diagnostics += layout.checkCollisions()

        // 2. Emit every module in memory.
        val planned = mutableListOf<CppPlannedFile>()
        if (diagnostics.none { it.isError }) {
            val emitter = emitterFactory(unit, options)
            diagnostics += emitter.diagnostics
            sources.forEach { (ref, source) ->
                val emitted = emitter.emit(source)
                diagnostics += emitted.diagnostics
                if (emitted.hasErrors) {
                    return@forEach
                }
                val files = layout.filesFor(ref)
                planned += CppPlannedFile.ofText(files.header, CppWriter.normalize(emitted.header))
                if (files.source != null && emitted.source != null) {
                    planned += CppPlannedFile.ofText(files.source, CppWriter.normalize(emitted.source))
                } else if (files.source == null && emitted.source != null) {
                    diagnostics += CppDiagnostic(
                        "cpp.header-only",
                        "module '${ref.uri}' is header-only but the emitter produced a source file",
                        file = source.file,
                    )
                }
            }
        }

        // 3. The runtime and the manifest.
        val install = CppRuntimeInstaller(stdlibCppDir, layout.runtimeDir, version).plan()
        diagnostics += install.diagnostics
        planned += install.files
        val relative = { file: CppPlannedFile -> layout.relativeToRoot(file.path) }
        val stdlibHash = CppGenManifest.stdlibHash(install.files.map { relative(it) to it.bytes })
        val manifestText = CppGenManifest.render(version, stdlibHash, planned.map { relative(it) to it.bytes })
        val manifestPath = root.resolve(CppGenManifest.FILE_NAME)
        planned += CppPlannedFile.ofText(manifestPath, manifestText)
        planned.sortBy { it.path.toString() }

        // 4. Report, then write or check.
        diagnostics.forEach { report(it.render()) }
        if (diagnostics.any { it.isError }) {
            val errors = diagnostics.count { it.isError }
            report("C++ backend: $errors error${if (errors == 1) "" else "s"}; no file was written")
            return CppBackendResult(1, diagnostics, planned, emptyList())
        }
        reportStale(manifestPath, planned, root, report)

        return if (check) {
            val drifted = planned.filter { !matchesDisk(it) }
            drifted.forEach { file ->
                val reason = if (Files.exists(file.path)) "differs" else "missing"
                out("$DRIFT_PREFIX${layout.relativeToRoot(file.path)} ($reason)")
            }
            if (drifted.isEmpty()) {
                log("C++ check: ${planned.size} generated files are current")
                CppBackendResult(0, diagnostics, planned, emptyList())
            } else {
                report(
                    "C++ check: ${drifted.size} of ${planned.size} generated files drifted; " +
                        "run `kira --target cpp` and commit the result"
                )
                CppBackendResult(1, diagnostics, planned, drifted.map { it.path })
            }
        } else {
            val written = planned.filter { !matchesDisk(it) }
            written.forEach { file ->
                Files.createDirectories(file.path.parent)
                Files.write(file.path, file.bytes)
            }
            log("Emitting C++ -> ${layout.relativeToRoot(root)}: wrote ${written.size}, unchanged ${planned.size - written.size}")
            CppBackendResult(0, diagnostics, planned, written.map { it.path })
        }
    }

    fun buildOptions(manifest: ProjectManifest?, outDirOverride: String?): CppOptions {
        val base = manifest?.build?.cpp ?: CppOptions()
        return if (outDirOverride.isNullOrBlank()) base else base.copy(outDir = outDirOverride)
    }

    private fun matchesDisk(file: CppPlannedFile): Boolean {
        if (!Files.isRegularFile(file.path)) {
            return false
        }
        return Files.readAllBytes(file.path).contentEquals(file.bytes)
    }

    /** Files the previous manifest listed that this run no longer generates: named, never deleted. */
    private fun reportStale(manifestPath: Path, planned: List<CppPlannedFile>, root: Path, report: (String) -> Unit) {
        if (!Files.isRegularFile(manifestPath)) {
            return
        }
        val previous = CppGenManifest.parse(Files.readString(manifestPath)) ?: return
        val current = planned.map { root.relativize(it.path).toString().replace('\\', '/') }.toSet()
        previous.entries.map { it.path }
            .filter { it !in current && Files.exists(root.resolve(it)) }
            .sorted()
            .forEach { report("warning: cpp.stale-file: $it was generated by an earlier run and is not any more; delete it") }
    }
}
