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
 * (which a release build can add), the Kira checkout the running compiler
 * sits in (an `installDist` under `build/install/`, as `kira_gen.py` builds
 * at the SHA `tools/kira.lock` pins, or the classes directory of a test
 * run), then `dev`. The checkout's HEAD is read from `.git` without running
 * git, worktrees and packed refs included; it names the commit the tree was
 * at, not whether the tree was clean.
 *
 * Only Kira's own checkout counts. The nearest `.git` above an install
 * copied into some other repository (`tools/kira/` in a project, say) is
 * that project's, and its HEAD would change the version on every commit
 * there; so the checkout is accepted when the compiler sits at its
 * `build/install/kira/lib` or `build/classes`, or its `settings.gradle.kts`
 * names the `kira` project, and anything else is `dev`.
 */
object CppCompilerVersion {
    const val DEV = "dev"

    private val KIRA_SETTINGS = Regex("""rootProject\.name\s*=\s*"kira"""")

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
        fromCheckout(codeLocation())?.let { return it }
        return DEV
    }

    /** Where this class was loaded from: the installed jar, or a classes directory. */
    fun codeLocation(): Path? {
        return runCatching {
            val url = CppCompilerVersion::class.java.protectionDomain?.codeSource?.location ?: return null
            Paths.get(url.toURI())
        }.getOrNull()
    }

    /**
     * The commit the Kira checkout holding [start] is at: the nearest `.git`
     * above it (a directory, or a worktree's `gitdir:` file), its `HEAD`,
     * and the ref that names, looked up loose in the git dir, then in the
     * common dir a worktree points at, then in `packed-refs`. Null when
     * there is no checkout, the checkout is not Kira's ([isKiraCheckout]),
     * or the ref cannot be read.
     */
    fun fromCheckout(start: Path?): String? {
        val origin = start?.toAbsolutePath()?.normalize() ?: return null
        var dir: Path? = if (Files.isDirectory(origin)) origin else origin.parent
        while (dir != null) {
            val dotGit = dir.resolve(".git")
            if (Files.exists(dotGit)) {
                if (!isKiraCheckout(dir, origin)) {
                    return null
                }
                return runCatching { headOf(dotGit) }.getOrNull()
            }
            dir = dir.parent
        }
        return null
    }

    /**
     * Whether [checkout] is the Kira repository that built the code at
     * [code]: the code lies in its `build/install/kira/lib` (an
     * `installDist`) or `build/classes` (a test run), or its
     * `settings.gradle.kts` names the `kira` project.
     */
    fun isKiraCheckout(checkout: Path, code: Path): Boolean {
        val root = checkout.toAbsolutePath().normalize()
        val absolute = code.toAbsolutePath().normalize()
        if (absolute.startsWith(root)) {
            val relative = root.relativize(absolute).toString().replace('\\', '/') + "/"
            if (relative.startsWith("build/install/kira/lib/") || relative.startsWith("build/classes/")) {
                return true
            }
        }
        return readText(root.resolve("settings.gradle.kts"))?.let { KIRA_SETTINGS.containsMatchIn(it) } == true
    }

    private fun headOf(dotGit: Path): String? {
        val gitDir = if (Files.isDirectory(dotGit)) {
            dotGit
        } else {
            val line = readText(dotGit)?.lines()?.firstOrNull { it.startsWith("gitdir:") } ?: return null
            dotGit.parent.resolve(line.removePrefix("gitdir:").trim()).normalize()
        }
        val head = readText(gitDir.resolve("HEAD"))?.trim() ?: return null
        if (!head.startsWith("ref:")) {
            return head.takeIf { isSha(it) }
        }
        val ref = head.removePrefix("ref:").trim()
        val common = readText(gitDir.resolve("commondir"))?.trim()?.let { gitDir.resolve(it).normalize() }
        val bases = listOfNotNull(gitDir, common)
        bases.forEach { base ->
            readText(base.resolve(ref))?.trim()?.takeIf { isSha(it) }?.let { return it }
        }
        bases.forEach { base ->
            readText(base.resolve("packed-refs"))?.lines()?.forEach { line ->
                val parts = line.trim().split(' ')
                if (parts.size == 2 && parts[1] == ref && isSha(parts[0])) {
                    return parts[0]
                }
            }
        }
        return null
    }

    private fun readText(path: Path): String? {
        return if (Files.isRegularFile(path)) runCatching { Files.readString(path) }.getOrNull() else null
    }

    private fun isSha(text: String): Boolean {
        return text.length == 40 && text.all { it in '0'..'9' || it in 'a'..'f' }
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
 * **The tree.** Every generated file lies under one directory, the *base*:
 * the project root, or `<dir>` under `--out <dir>`. `kira.gen.manifest`
 * sits in the base and records each file relative to it, never absolute
 * and never through `..`; a `build.cpp.outDir` or `runtimeDir` that leaves
 * the root is an error, and a manifest entry that would leave the base is
 * ignored with a warning. A write removes only files under the base.
 *
 * **Stale files.** A generated file is stale when this run does not plan it
 * and either the previous `kira.gen.manifest` listed it, or it is shaped
 * like the backend's output in a place the backend writes: under
 * `<runtimeDir>/kira/` a file with one of the runtime's extensions
 * (`.hxx`, `.cxx`), a generated extension or the name `VERSION`; under
 * `outDir` in the tree layout, or in the workspace (`srcDir` minus
 * `srcExclude`) in the beside layout, a file with a generated extension
 * containing `.kira.` (`x.kira.hxx`, which nothing else produces; a custom
 * `.hpp` is not distinctive, so only the manifest finds those). Anything
 * else in those directories, the user's own sources included, is left
 * alone, so `--out .` and a shared `runtimeDir` work. A write run removes a
 * stale file only when its content is still exactly what the previous
 * manifest recorded; an edited or unrecorded one is an error that names
 * it, and nothing is written until it is gone. `srcExclude` prunes these
 * scans below the directory scanned, never the directory itself: `build`
 * over `outDir: build/gen` hides nothing inside `build/gen`.
 *
 * **Files that are not the backend's.** A write replaces a file at a
 * planned path only when the file is the backend's: the previous manifest
 * recorded it, or it is generated-shaped as above. A hand-written
 * `proto.hxx` beside `proto.kira` under `headerExt: .hxx`, or any file at a
 * planned path with a custom extension that no manifest records, is an
 * error naming it, and nothing is written. An output planned at a Kira
 * source's own path (`headerExt: .kira`) is an error before anything is
 * emitted.
 *
 * `--out <dir>` puts every generated file under `<dir>`: the tree layout
 * rooted there, the runtime in `<dir>/kira/` and the manifest at
 * `<dir>/kira.gen.manifest`, whatever the manifest's `build.cpp` says.
 */
object KiraCppBackend {
    const val DRIFT_PREFIX = "drift: "
    const val STALE_CODE = "cpp.stale-file"
    const val OUTPUT_COLLISION_CODE = "cpp.output-collision"
    const val OUTSIDE_TREE_CODE = "cpp.outside-tree"
    const val MANIFEST_ENTRY_CODE = "cpp.manifest-entry"

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
        val manifestPath = manifestPath(root, options, outDirOverride)
        val base: Path = manifestPath.parent
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
        val show = { path: Path -> describe(path, layout, base, options) }
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
        val stdlibHash = CppGenManifest.stdlibHash(
            install.files
                .filter { it.path.fileName.toString() != CppRuntimeInstaller.VERSION_FILE }
                .map { layout.runtimeKiraDir.relativize(it.path).toString().replace('\\', '/') to it.bytes }
        )
        diagnostics += checkInsideBase(planned, base, layout, options, outDirOverride)
        val manifestText = CppGenManifest.render(
            version, stdlibHash,
            planned.map { (CppModuleLayout.relativeTo(base, it.path) ?: layout.relativeToRoot(it.path)) to it.bytes },
        )
        planned += CppPlannedFile.ofText(manifestPath, manifestText, CppGenManifest.FILE_NAME)
        planned.sortBy { it.path.toString() }
        diagnostics += checkPlannedCollisions(planned, layout)

        // 4. Stale generated files: recorded, or generated-shaped where the backend writes, and not planned.
        val shapes = GeneratedShapes(layout, manifest, install.files, show)
        val found = findStale(base, manifestPath, planned, layout, manifest, stdlibCppDir, shapes, show)
        diagnostics += found.diagnostics
        val stale = found.stale

        // 4b. A planned path holding a file that is not the backend's is never overwritten.
        planned.filter { file ->
            file.path != manifestPath && Files.isRegularFile(file.path) && file.path !in found.recorded &&
                shapes.of(file.path) == null && !matchesDisk(file)
        }.forEach { file ->
            diagnostics += CppDiagnostic(
                OUTPUT_COLLISION_CODE,
                "${show(file.path)} exists and is not a file this backend wrote: ${CppGenManifest.FILE_NAME} does not " +
                    "record it and its name is not generated-shaped, so ${file.origin.ifEmpty { "the run" }} would replace " +
                    "a hand-written file; move it, or delete it if it is stale",
            )
        }
        val removable = mutableListOf<Path>()
        if (!check) {
            stale.forEach { candidate ->
                val onDisk = CppGenManifest.sha256(CppWriter.lfBytes(Files.readAllBytes(candidate.path)))
                if (candidate.recorded != null && candidate.recorded == onDisk) {
                    removable.add(candidate.path)
                } else if (candidate.recorded != null) {
                    diagnostics += CppDiagnostic(
                        STALE_CODE,
                        "${show(candidate.path)} was generated by an earlier run (${CppGenManifest.FILE_NAME} records it) " +
                            "and is not any more, but it was edited since, so delete it yourself",
                    )
                } else {
                    diagnostics += CppDiagnostic(
                        STALE_CODE,
                        "${show(candidate.path)} is ${candidate.shape} that this run does not produce and " +
                            "${CppGenManifest.FILE_NAME} has not recorded, so it is either stale or hand-written where " +
                            "generated files go; delete it, or move it out of the generated tree",
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
                file.path to "$DRIFT_PREFIX${show(file.path)} ($reason)"
            } + stale.map { it.path to "$DRIFT_PREFIX${show(it.path)} (stale)" }
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
                require(path.startsWith(base)) { "refusing to remove $path: it is not under $base" }
                Files.deleteIfExists(path)
                pruneEmptyDirectories(path.parent, base)
                log("removed stale ${show(path)}")
            }
            log(
                "Emitting C++ -> ${layout.relativeToRoot(base).ifEmpty { "." }}: wrote ${written.size}, " +
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

    /** A path for a message: relative to the project root, or under `--out` as `<dir>/...` the way it was given. */
    private fun describe(path: Path, layout: CppModuleLayout, base: Path, options: CppOptions): String {
        CppModuleLayout.relativeTo(layout.projectRoot, path)?.let { return it }
        CppModuleLayout.relativeTo(base, path)?.let { inside ->
            return options.outDir.replace('\\', '/').trimEnd('/') + "/" + inside
        }
        return layout.relativeToRoot(path)
    }

    private fun matchesDisk(file: CppPlannedFile): Boolean {
        if (!Files.isRegularFile(file.path)) {
            return false
        }
        return CppWriter.lfBytes(Files.readAllBytes(file.path)).contentEquals(file.bytes)
    }

    /**
     * Every planned file must lie under the base; one error per setting
     * that sends files elsewhere (`build.cpp.runtimeDir: ../lib`, say),
     * naming the first such file, since `kira.gen.manifest` could not
     * record it and a later write could not safely remove it.
     */
    private fun checkInsideBase(
        planned: List<CppPlannedFile>,
        base: Path,
        layout: CppModuleLayout,
        options: CppOptions,
        outDirOverride: String?,
    ): List<CppDiagnostic> {
        val outside = planned.filter { CppModuleLayout.relativeTo(base, it.path) == null }
        if (outside.isEmpty()) {
            return emptyList()
        }
        val where = if (outDirOverride.isNullOrBlank()) "the project root" else "--out ${options.outDir}"
        return outside.groupBy { file ->
            when {
                file.path.startsWith(layout.runtimeKiraDir) -> "build.cpp.runtimeDir '${options.effectiveRuntimeDir}'"
                options.layout == CppLayout.TREE -> "build.cpp.outDir '${options.outDir}'"
                else -> "the beside layout (its source is outside the project)"
            }
        }.map { (blame, files) ->
            CppDiagnostic(
                OUTSIDE_TREE_CODE,
                "$blame puts ${layout.relativeToRoot(files.first().path)} outside $where; generated files stay under it " +
                    "(use --out <dir> to generate elsewhere)",
            )
        }
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

    /** A stale candidate: the sha256 the previous manifest recorded (null when it never did) and why it looks generated. */
    private class StaleFile(val path: Path, val recorded: String?, val shape: String)

    /** What a scan found: the stale candidates, the previous manifest's entries under the base, and warnings. */
    private class StaleScan(val stale: List<StaleFile>, val recorded: Map<Path, String>, val diagnostics: List<CppDiagnostic>)

    /**
     * The shapes the class comment lists, by place: why a path looks like
     * the backend's output, or null. Used to find stale files the manifest
     * never recorded, and to refuse to overwrite a file that is not ours.
     */
    private class GeneratedShapes(
        layout: CppModuleLayout,
        manifest: ProjectManifest?,
        runtimeFiles: List<CppPlannedFile>,
        private val show: (Path) -> String,
    ) {
        private val options = layout.options
        val runtimeKiraDir: Path = layout.runtimeKiraDir
        val outDir: Path = layout.outDir
        val workspace: Path = layout.projectRoot.resolve(manifest?.srcDir ?: "src").normalize()
        private val distinctiveExts = listOf(options.headerExt, options.sourceExt).filter { it.contains(".kira.") }
        private val generatedExts = listOf(options.headerExt, options.sourceExt)
        private val runtimeExts = runtimeFiles.map { it.path.fileName.toString() }
            .filter { it != CppRuntimeInstaller.VERSION_FILE && it.contains('.') }
            .map { it.substring(it.lastIndexOf('.')) }
            .toSet()
        private val runtimeNames = runtimeFiles.map { it.path.fileName.toString() }.filter { !it.contains('.') }.toSet() +
            CppRuntimeInstaller.VERSION_FILE

        fun of(path: Path): String? {
            val absolute = path.toAbsolutePath().normalize()
            val name = absolute.fileName?.toString() ?: return null
            if (absolute.startsWith(runtimeKiraDir)) {
                val where = "under the runtime's directory ${show(runtimeKiraDir)}"
                return when {
                    name in runtimeNames -> "a runtime file $where"
                    runtimeExts.any { name.endsWith(it) } -> "a $where file"
                    generatedExts.any { name.endsWith(it) } -> "a generated header $where"
                    else -> null
                }
            }
            val ext = distinctiveExts.firstOrNull { name.endsWith(it) } ?: return null
            return when {
                options.layout == CppLayout.TREE && absolute.startsWith(outDir) ->
                    "a $ext file under the generated tree ${show(outDir).ifEmpty { "." }}"
                options.layout == CppLayout.BESIDE && absolute.startsWith(workspace) -> "a $ext file in the workspace"
                else -> null
            }
        }
    }

    /**
     * Stale generated files under [base]: what the previous manifest listed
     * (entries that would leave the base are ignored with a warning), plus
     * generated-shaped files where the backend writes, minus everything
     * planned now. The stdlib's own directory and `.git` are never scanned,
     * and `srcExclude` prunes below each scanned directory (never the
     * directory itself, which the manifest chose as a place to write).
     */
    private fun findStale(
        base: Path,
        manifestPath: Path,
        planned: List<CppPlannedFile>,
        layout: CppModuleLayout,
        manifest: ProjectManifest?,
        stdlibCppDir: Path?,
        shapes: GeneratedShapes,
        show: (Path) -> String,
    ): StaleScan {
        val root = layout.projectRoot
        val options = layout.options
        val diagnostics = mutableListOf<CppDiagnostic>()
        val plannedPaths = planned.map { it.path.toString().lowercase() }.toSet()
        val recorded = LinkedHashMap<Path, String>()
        if (Files.isRegularFile(manifestPath)) {
            CppGenManifest.parse(Files.readString(manifestPath))?.entries?.forEach { entry ->
                val resolved = if (CppGenManifest.isRelativeInside(entry.path)) base.resolve(entry.path).normalize() else null
                if (resolved == null || !resolved.startsWith(base)) {
                    diagnostics += CppDiagnostic(
                        MANIFEST_ENTRY_CODE,
                        "${show(manifestPath)} records '${entry.path}', which is not a path under ${show(base).ifEmpty { "the project root" }}; ignored",
                        CppSeverity.WARNING,
                    )
                } else {
                    recorded[resolved] = entry.sha256
                }
            }
        }

        val candidates = LinkedHashMap<Path, String>()
        recorded.keys.forEach { candidates[it] = "recorded" }
        val excludes = manifest?.srcExclude.orEmpty()
        val stdlibRoot = stdlibCppDir?.toAbsolutePath()?.normalize()?.parent

        fun scan(dir: Path) {
            if (!Files.isDirectory(dir)) {
                return
            }
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(sub: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val absolute = sub.toAbsolutePath().normalize()
                    val skip = absolute.fileName?.toString() == ".git" ||
                        (stdlibRoot != null && absolute.startsWith(stdlibRoot)) ||
                        DependencyResolver.isExcluded(absolute.toString(), root, excludes, below = dir)
                    return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val absolute = file.toAbsolutePath().normalize()
                        val shape = shapes.of(absolute)
                        if (shape != null && absolute !in candidates) {
                            candidates[absolute] = shape
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }

        scan(shapes.runtimeKiraDir)
        scan(if (options.layout == CppLayout.TREE) shapes.outDir else shapes.workspace)

        val stale = candidates
            .filter { (path, _) ->
                path.startsWith(base) && path.toString().lowercase() !in plannedPaths &&
                    path != manifestPath && Files.isRegularFile(path)
            }
            .toSortedMap()
            .map { (path, shape) -> StaleFile(path, recorded[path], shape) }
        return StaleScan(stale, recorded, diagnostics)
    }

    /** After a removal, drops directories left empty, up to but never including [base]. */
    private fun pruneEmptyDirectories(start: Path?, base: Path) {
        var dir = start
        while (dir != null && dir != base && dir.startsWith(base) && Files.isDirectory(dir)) {
            val empty = Files.list(dir).use { it.findAny().isEmpty }
            if (!empty) {
                return
            }
            Files.delete(dir)
            dir = dir.parent
        }
    }
}
