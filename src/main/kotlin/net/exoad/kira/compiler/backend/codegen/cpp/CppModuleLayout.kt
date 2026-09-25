package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.kim.ManifestValidator
import net.exoad.kira.kim.SourceGlob
import java.nio.file.Path

/** A module the layout knows about: its URI and the `.kira` file it came from. */
data class CppModuleRef(val uri: String, val sourcePath: Path)

/** Where a module's generated header and (unless header-only) source go. */
data class CppModuleFiles(val header: Path, val source: Path?)

/**
 * File paths, namespaces and include paths for the generated tree.
 *
 * - `beside`: `proto.kira` becomes `proto.kira.hxx` (+ `.cxx`) next to it.
 * - `tree`: `firmware:pilot.src.proto` becomes `<outDir>/firmware/pilot/src/proto.kira.hxx`.
 * - `kira:x` stdlib modules always land header-only in `<runtimeDir>/kira/std/x.kira.hxx`,
 *   in namespace `kira::x`; a nested `kira:a.b` keeps its path, `kira::a::b`,
 *   so two stdlib modules with one last segment never merge.
 *
 * A module's namespace is the last URI segment unless `build.cpp.namespaces`
 * names it, exactly or by glob (`firmware:pilot.src.bibowire.*`); an exact key
 * wins over a glob, a longer glob over a shorter one. A derived segment that
 * is a C++ keyword is escaped the way names are (`new` gives `new_`).
 * [checkCollisions] rejects a namespace that is not valid C++ and the reserved
 * ones: `std`, and `kira` for anything but a stdlib module (the runtime's).
 */
class CppModuleLayout(
    val options: CppOptions,
    projectRoot: Path,
    modules: List<CppModuleRef> = emptyList(),
) {
    val projectRoot: Path = projectRoot.toAbsolutePath().normalize()
    val modules: List<CppModuleRef> = modules.sortedWith(compareBy({ it.uri }, { it.sourcePath.toString() }))

    /** `<projectRoot>/<outDir>`. */
    val outDir: Path = this.projectRoot.resolve(options.outDir).normalize()

    /** `<projectRoot>/<runtimeDir>`: the runtime lands in its `kira/`. */
    val runtimeDir: Path = this.projectRoot.resolve(options.effectiveRuntimeDir).normalize()

    /** `<runtimeDir>/kira`, where `rt.hxx`, `VERSION` and `std/` live. */
    val runtimeKiraDir: Path = runtimeDir.resolve("kira")

    fun filesFor(module: CppModuleRef): CppModuleFiles = filesFor(module.uri, module.sourcePath)

    fun filesFor(uri: String, sourcePath: Path): CppModuleFiles {
        val absoluteSource = sourcePath.toAbsolutePath().normalize()
        val stem = stemOf(absoluteSource.fileName.toString())
        val headerOnly = options.isHeaderOnly(uri)
        val dir: Path = when {
            uri.startsWith(CppOptions.STDLIB_URI_PREFIX) -> {
                val segments = uriSegments(uri)
                segments.dropLast(1).fold(runtimeKiraDir.resolve("std")) { acc, s -> acc.resolve(s) }
            }
            options.layout == CppLayout.BESIDE -> absoluteSource.parent ?: projectRoot
            else -> {
                val (pkg, path) = splitUri(uri)
                val segments = listOf(pkg) + path.dropLast(1)
                segments.fold(outDir) { acc, s -> acc.resolve(s) }
            }
        }
        val fileStem = when {
            uri.startsWith(CppOptions.STDLIB_URI_PREFIX) -> uriSegments(uri).last()
            options.layout == CppLayout.BESIDE -> stem
            else -> uriSegments(uri).last()
        }
        val header = dir.resolve(fileStem + options.headerExt).normalize()
        val source = if (headerOnly) null else dir.resolve(fileStem + options.sourceExt).normalize()
        return CppModuleFiles(header, source)
    }

    fun namespaceFor(uri: String): String {
        options.namespaces[uri]?.let { return it }
        val globHit = options.namespaces.entries
            .filter { (pattern, _) -> isGlob(pattern) && SourceGlob.matchesUri(pattern, uri) }
            .sortedWith(compareByDescending<Map.Entry<String, String>> { literalLength(it.key) }
                .thenByDescending { it.key.length })
            .firstOrNull()
        if (globHit != null) {
            return globHit.value
        }
        if (uri.startsWith(CppOptions.STDLIB_URI_PREFIX)) {
            return uriSegments(uri).joinToString("::", prefix = "$STDLIB_NAMESPACE::") { CppNames.escapeKeyword(it) }
        }
        return CppNames.escapeKeyword(uriSegments(uri).last())
    }

    /**
     * Why [namespace] cannot head a module's declarations, or null: a segment
     * that is not a C++ identifier (or is a keyword the manifest spelled out),
     * `std`, or `kira` under a module that is not the stdlib's.
     */
    fun namespaceProblem(uri: String, namespace: String): String? {
        val segments = namespace.split("::")
        if (segments.isEmpty() || segments.any { !IDENTIFIER.matches(it) }) {
            return "'$namespace' is not a C++ namespace path (identifiers joined by ::)"
        }
        segments.firstOrNull { CppNames.isKeyword(it) }?.let {
            return "'$namespace' uses the C++ keyword '$it'"
        }
        segments.firstOrNull { it.startsWith("__") || (it.length > 1 && it[0] == '_' && it[1].isUpperCase()) }?.let {
            return "'$namespace' uses '$it', a name C++ reserves for its implementation"
        }
        if (segments.first() == "std") {
            return "'$namespace' would add to namespace std, which C++ forbids"
        }
        if (segments.first() == STDLIB_NAMESPACE && !uri.startsWith(CppOptions.STDLIB_URI_PREFIX)) {
            return "'$namespace' is the runtime's namespace; only kira: modules live in $STDLIB_NAMESPACE::"
        }
        return null
    }

    /**
     * The `#include` text from the generated file [from] to the generated
     * file [to]: relative, with forward slashes, so no include path is
     * needed. Same directory gives the bare file name.
     */
    fun includePath(from: Path, to: Path): String {
        val fromDir = from.toAbsolutePath().normalize().parent ?: projectRoot
        val target = to.toAbsolutePath().normalize()
        val relative = runCatching { fromDir.relativize(target) }.getOrNull()
            ?: return target.toString().replace('\\', '/')
        return relative.toString().replace('\\', '/')
    }

    /** Every problem with the configured layout: colliding outputs, bad or unused namespace overrides. */
    fun checkCollisions(): List<CppDiagnostic> {
        val diagnostics = mutableListOf<CppDiagnostic>()

        val byUri = modules.groupBy { it.uri }
        byUri.filterValues { it.size > 1 }.forEach { (uri, refs) ->
            diagnostics += CppDiagnostic(
                "cpp.module-collision",
                "module '$uri' is declared by ${refs.size} files: ${refs.joinToString { it.sourcePath.toString() }}",
            )
        }

        val byOutput = LinkedHashMap<String, MutableList<CppModuleRef>>()
        modules.distinctBy { it.uri }.forEach { ref ->
            val files = filesFor(ref)
            listOfNotNull(files.header, files.source).forEach { path ->
                byOutput.getOrPut(path.toString().lowercase()) { mutableListOf() }.add(ref)
            }
        }
        byOutput.filterValues { it.size > 1 }.forEach { (path, refs) ->
            diagnostics += CppDiagnostic(
                "cpp.output-collision",
                "modules ${refs.joinToString { "'${it.uri}'" }} would both write $path",
            )
        }

        modules.distinctBy { it.uri }.forEach { ref ->
            val ns = namespaceFor(ref.uri)
            namespaceProblem(ref.uri, ns)?.let { problem ->
                diagnostics += CppDiagnostic(
                    "cpp.namespace-invalid",
                    "module '${ref.uri}': namespace $problem",
                    file = ref.sourcePath.toString(),
                )
            }
        }

        options.namespaces.forEach { (pattern, ns) ->
            if (!ManifestValidator.isNamespacePath(ns)) {
                diagnostics += CppDiagnostic(
                    "cpp.namespace-invalid",
                    "build.cpp.namespaces['$pattern'] = '$ns' is not a C++ namespace path",
                )
            } else if (modules.none { SourceGlob.matchesUri(pattern, it.uri) }) {
                diagnostics += CppDiagnostic(
                    "cpp.namespace-unused",
                    "build.cpp.namespaces['$pattern'] matches no module",
                    CppSeverity.WARNING,
                )
            }
        }
        return diagnostics
    }

    /** `outDir`, `runtimeDir` and every generated file, relative to the project root with forward slashes. */
    fun relativeToRoot(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        return if (absolute.startsWith(projectRoot)) {
            projectRoot.relativize(absolute).toString().replace('\\', '/')
        } else {
            absolute.toString().replace('\\', '/')
        }
    }

    companion object {
        /** The runtime's namespace; the Kira-written stdlib nests under it. */
        const val STDLIB_NAMESPACE = "kira"

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun stemOf(fileName: String): String {
            return if (fileName.endsWith(".kira")) fileName.dropLast(".kira".length) else fileName
        }

        /** `firmware:pilot.src.proto` gives `firmware` and `[pilot, src, proto]`. */
        fun splitUri(uri: String): Pair<String, List<String>> {
            val colon = uri.indexOf(':')
            require(colon > 0) { "module URI '$uri' has no package" }
            val pkg = uri.substring(0, colon)
            val path = uri.substring(colon + 1).split('.').filter { it.isNotEmpty() }
            require(path.isNotEmpty()) { "module URI '$uri' has no module path" }
            return pkg to path
        }

        fun uriSegments(uri: String): List<String> = splitUri(uri).second

        private fun isGlob(pattern: String): Boolean {
            return pattern.any { it == '*' || it == '?' || it == '{' }
        }

        private fun literalLength(pattern: String): Int {
            return pattern.count { it != '*' && it != '?' && it != '{' && it != '}' && it != ',' }
        }
    }
}
