package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.source.SourceContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
    /** Files written (write mode) or found drifted, missing or stale (check mode). */
    val changed: List<Path>,
    /** Stale generated files a write run removed. */
    val removed: List<Path> = emptyList(),
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
 * bytes differ from disk are written and stale generated files are removed;
 * in check mode (`--check`) every differing, missing or stale file is named
 * and the exit code is 1.
 *
 * A file on disk is read with CRLF as LF, so a Windows checkout under
 * `core.autocrlf=true` compares equal to what the repository keeps.
 *
 * A generated file is stale when this run does not plan it and either the
 * previous `kira.gen.manifest` listed it, it sits under a directory the
 * backend owns outright (`<runtimeDir>/kira/`, or `outDir` in the tree
 * layout), or it lies in the workspace (`srcDir` minus `srcExclude`) with a
 * generated extension containing `.kira.` (`x.kira.hxx`, which nothing else
 * produces; a custom `.hpp` is not distinctive, so only the first two apply). A write
 * run removes a stale file only when its content is still exactly what the
 * previous manifest recorded; an edited or unrecorded one is an error that
 * names it, and nothing is written until it is gone.
 *
 * `--out <dir>` puts every generated file under `<dir>`: the tree layout
 * rooted there, the runtime in `<dir>/kira/` and the manifest at
 * `<dir>/kira.gen.manifest`, whatever the manifest's `build.cpp` says.
 */
object KiraCppBackend {
    const val DRIFT_PREFIX = "drift: "
    const val STALE_CODE = "cpp.stale-file"
    const val OUTPUT_COLLISION_CODE = "cpp.output-collision"

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
        if (!outDirOverride.isNullOrBlank()) {
            log("--out ${options.outDir}: tree layout, runtime and ${CppGenManifest.FILE_NAME} under it")
        }

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
                val origin = "module '${ref.uri}' (${layout.relativeToRoot(ref.sourcePath)})"
                planned += CppPlannedFile.ofText(files.header, CppWriter.normalize(emitted.header), origin)
                if (files.source != null && emitted.source != null) {
                    planned += CppPlannedFile.ofText(files.source, CppWriter.normalize(emitted.source), origin)
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
        val stdlibHash = CppGenManifest.stdlibHash(
            install.files
                .filter { it.path.fileName.toString() != CppRuntimeInstaller.VERSION_FILE }
                .map { layout.runtimeKiraDir.relativize(it.path).toString().replace('\\', '/') to it.bytes }
        )
        val manifestText = CppGenManifest.render(version, stdlibHash, planned.map { relative(it) to it.bytes })
        val manifestPath = manifestPath(root, options, outDirOverride)
        planned += CppPlannedFile.ofText(manifestPath, manifestText, CppGenManifest.FILE_NAME)
        planned.sortBy { it.path.toString() }
        diagnostics += checkPlannedCollisions(planned, layout)

        // 4. Stale generated files: recorded, owned or generated-shaped, and not planned.
        val stale = findStale(manifestPath, planned, layout, manifest)
        val removable = mutableListOf<Path>()
        if (!check) {
            stale.forEach { (path, recorded) ->
                val onDisk = CppGenManifest.sha256(CppWriter.lfBytes(Files.readAllBytes(path)))
                if (recorded != null && recorded == onDisk) {
                    removable.add(path)
                } else {
                    val why = if (recorded == null) "was not recorded by ${CppGenManifest.FILE_NAME}" else "was edited since"
                    diagnostics += CppDiagnostic(
                        STALE_CODE,
                        "${layout.relativeToRoot(path)} was generated by an earlier run and is not any more; " +
                            "it $why, so delete it yourself",
                    )
                }
            }
        }

        // 5. Report, then write or check.
        diagnostics.forEach { report(it.render()) }
        if (diagnostics.any { it.isError }) {
            val errors = diagnostics.count { it.isError }
            report("C++ backend: $errors error${if (errors == 1) "" else "s"}; no file was written")
            return CppBackendResult(1, diagnostics, planned, emptyList())
        }

        return if (check) {
            val drifted = planned.filter { !matchesDisk(it) }
            val lines = drifted.map { file ->
                val reason = if (Files.exists(file.path)) "differs" else "missing"
                file.path to "$DRIFT_PREFIX${layout.relativeToRoot(file.path)} ($reason)"
            } + stale.map { (path, _) -> path to "$DRIFT_PREFIX${layout.relativeToRoot(path)} (stale)" }
            lines.sortedBy { it.second.removePrefix(DRIFT_PREFIX) }.forEach { out(it.second) }
            if (lines.isEmpty()) {
                log("C++ check: ${planned.size} generated files are current")
                CppBackendResult(0, diagnostics, planned, emptyList())
            } else {
                val staleNote = if (stale.isEmpty()) "" else " and ${stale.size} stale"
                report(
                    "C++ check: ${drifted.size} of ${planned.size} generated files drifted$staleNote; " +
                        "run `kira --target cpp` and commit the result"
                )
                CppBackendResult(1, diagnostics, planned, lines.map { it.first })
            }
        } else {
            val written = planned.filter { !matchesDisk(it) }
            written.forEach { file ->
                Files.createDirectories(file.path.parent)
                Files.write(file.path, file.bytes)
            }
            removable.forEach { path ->
                Files.deleteIfExists(path)
                pruneEmptyDirectories(path.parent, root)
                log("removed stale ${layout.relativeToRoot(path)}")
            }
            log(
                "Emitting C++ -> ${layout.relativeToRoot(root)}: wrote ${written.size}, " +
                    "unchanged ${planned.size - written.size}, removed ${removable.size}"
            )
            CppBackendResult(0, diagnostics, planned, written.map { it.path }, removable)
        }
    }

    /**
     * The manifest's `build.cpp`, or with `--out <dir>` the tree layout with
     * everything under `<dir>`: the beside layout has no other place to put
     * a module, and a runtime left in `build.cpp.runtimeDir` would make
     * `--out` a half-truth.
     */
    fun buildOptions(manifest: ProjectManifest?, outDirOverride: String?): CppOptions {
        val base = manifest?.build?.cpp ?: CppOptions()
        if (outDirOverride.isNullOrBlank()) {
            return base
        }
        return base.copy(layout = CppLayout.TREE, outDir = outDirOverride, runtimeDir = outDirOverride)
    }

    /** `<root>/kira.gen.manifest`, or `<dir>/kira.gen.manifest` under `--out <dir>`. */
    fun manifestPath(root: Path, options: CppOptions, outDirOverride: String?): Path {
        val dir = if (outDirOverride.isNullOrBlank()) root else root.resolve(options.outDir)
        return dir.resolve(CppGenManifest.FILE_NAME).toAbsolutePath().normalize()
    }

    private fun matchesDisk(file: CppPlannedFile): Boolean {
        if (!Files.isRegularFile(file.path)) {
            return false
        }
        return CppWriter.lfBytes(Files.readAllBytes(file.path)).contentEquals(file.bytes)
    }

    /** Two plans for one path (a runtime file and a generated stdlib header, say) are an error, never a silent last-writer-wins. */
    private fun checkPlannedCollisions(planned: List<CppPlannedFile>, layout: CppModuleLayout): List<CppDiagnostic> {
        return planned.groupBy { it.path.toString().lowercase() }
            .values
            .filter { it.size > 1 }
            .map { group ->
                CppDiagnostic(
                    OUTPUT_COLLISION_CODE,
                    "${layout.relativeToRoot(group.first().path)} is planned ${group.size} times: " +
                        group.joinToString { it.origin.ifEmpty { "(unnamed)" } },
                )
            }
    }

    /**
     * Stale generated files, each with the sha256 the previous manifest
     * recorded for it (null when it never did): files the previous manifest
     * listed, any file under the owned directories, and generated-shaped
     * files anywhere in the workspace (`srcDir` minus `srcExclude`, the
     * directories the sources come from), minus everything planned now.
     */
    private fun findStale(
        manifestPath: Path,
        planned: List<CppPlannedFile>,
        layout: CppModuleLayout,
        manifest: ProjectManifest?,
    ): List<Pair<Path, String?>> {
        val root = layout.projectRoot
        val plannedPaths = planned.map { it.path.toString().lowercase() }.toSet()
        val recorded = LinkedHashMap<Path, String>()
        if (Files.isRegularFile(manifestPath)) {
            CppGenManifest.parse(Files.readString(manifestPath))?.entries?.forEach { entry ->
                recorded[root.resolve(entry.path).toAbsolutePath().normalize()] = entry.sha256
            }
        }

        val candidates = LinkedHashSet<Path>()
        candidates.addAll(recorded.keys)
        val owned = mutableListOf(layout.runtimeKiraDir)
        if (layout.options.layout == CppLayout.TREE) {
            owned.add(layout.outDir)
        }
        owned.filter { Files.isDirectory(it) }.forEach { dir ->
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { candidates.add(it.toAbsolutePath().normalize()) }
            }
        }
        val generatedExts = listOf(layout.options.headerExt, layout.options.sourceExt).filter { it.contains(".kira.") }
        val workspace = root.resolve(manifest?.srcDir ?: "src").normalize()
        if (generatedExts.isNotEmpty() && layout.options.layout == CppLayout.BESIDE && Files.isDirectory(workspace)) {
            val excludes = manifest?.srcExclude.orEmpty()
            Files.walkFileTree(workspace, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val skip = dir.fileName?.toString() == ".git" ||
                        (dir != workspace && DependencyResolver.isExcluded(dir.toString(), root, excludes))
                    return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && generatedExts.any { file.fileName.toString().endsWith(it) }) {
                        candidates.add(file.toAbsolutePath().normalize())
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }

        return candidates
            .filter { it.toString().lowercase() !in plannedPaths && it != manifestPath && Files.isRegularFile(it) }
            .sortedBy { it.toString() }
            .map { it to recorded[it] }
    }

    /** After a removal, drops directories left empty, up to but never including [root]. */
    private fun pruneEmptyDirectories(start: Path?, root: Path) {
        var dir = start
        while (dir != null && dir != root && dir.startsWith(root) && Files.isDirectory(dir)) {
            val empty = Files.list(dir).use { it.findAny().isEmpty }
            if (!empty) {
                return
            }
            Files.delete(dir)
            dir = dir.parent
        }
    }
}
