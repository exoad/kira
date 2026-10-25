package net.exoad.kira.compiler

import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * A project: project.kira's `PROJECT`, field for field as the stdlib's `kira:config` declares
 * Project ([ProjectFile] reads one). Its modules are srcDir's files less srcExclude's, and its
 * dependencies' are theirs ([ProjectSources]); [ModuleNames] names each.
 */
data class Project(
    /** The first part of every module's name: srcDir's `a/b.kira` is `name.a.b`. */
    val name: String,
    /** Who wrote it and its version: read and kept, used by nothing yet. */
    val author: String = "",
    val version: String = "",
    val srcDir: String = "src",
    /**
     * Path globs relative to the project root; a source file under a matching path is left out
     * of the workspace. `build` excludes the root's build directory, `** /build` (without the
     * space) every build directory. Dependency sources are never excluded by this list.
     */
    val srcExclude: List<String> = emptyList(),
    /** c, cpp, js, py, neko or none; `--target` overrides it. */
    val target: String = "c",
    /** Extra .c/.o files linked with out.kira.c (paths relative to the project root). */
    val cSources: List<String> = emptyList(),
    /** Extra flags passed to cc after the sources (e.g. -framework Cocoa). */
    val linkFlags: List<String> = emptyList(),
    /** When true (the default), generated C/JS user code is minified and obfuscated. */
    val minify: Boolean = true,
    /** How `--target cpp` lays out and names its files. */
    val cpp: CppOptions = CppOptions(),
    val types: TypeCheckMode = TypeCheckMode.OFF,
    /** A file for the compiler's symbol dump; null for none. */
    val emitIr: String? = null,
    /** Each dependency's name and path, relative to the root (or its parent), the stdlib's among them. */
    val dependencies: Map<String, String> = emptyMap(),
) {
    /** One thing wrong with a project: the field as project.kira spells it, and why. */
    data class Issue(val field: String, val message: String)

    /** What is wrong with this project at [root]; nothing, an empty list. */
    fun issues(root: Path): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (name.isBlank()) {
            issues += Issue("name", "name cannot be blank")
        }
        if (srcDir.isBlank()) {
            issues += Issue("srcDir", "srcDir cannot be blank")
        } else if (!Files.exists(root.resolve(srcDir).normalize())) {
            issues += Issue("srcDir", "srcDir does not exist: $srcDir")
        }
        if (target.lowercase() !in TARGETS) {
            issues += Issue("target", "unsupported target '$target', expected one of ${TARGETS.joinToString(", ")}")
        }
        if (cpp.outDir.isBlank()) {
            issues += Issue("cpp.outDir", "cpp.outDir cannot be blank")
        }
        if (cpp.runtimeDir != null && cpp.runtimeDir.isBlank()) {
            issues += Issue("cpp.runtimeDir", "cpp.runtimeDir cannot be blank")
        }
        listOf("headerExt" to cpp.headerExt, "sourceExt" to cpp.sourceExt).forEach { (key, ext) ->
            if (!ext.startsWith(".") || ext.length < 2) {
                issues += Issue("cpp.$key", "cpp.$key must start with '.': $ext")
            } else if (ext.endsWith(KIRA_SOURCE_EXT)) {
                issues += Issue(
                    "cpp.$key",
                    "cpp.$key '$ext' ends with '$KIRA_SOURCE_EXT', so every generated file would be a Kira source: " +
                        "written over the module it came from, or read as a module on the next run",
                )
            }
        }
        if (cpp.headerExt == cpp.sourceExt) {
            issues += Issue("cpp.sourceExt", "cpp.headerExt and sourceExt must differ")
        }
        cpp.namespaces.forEach { (uri, ns) ->
            if (!CppOptions.isNamespacePath(ns)) {
                issues += Issue("cpp.namespaces['$uri']", "'$ns' is not a C++ namespace path (expected identifiers joined by ::)")
            }
        }
        dependencies.forEach { (dep, path) ->
            if (path.isBlank()) {
                issues += Issue("dependencies['$dep']", "dependency path must be provided")
            } else if (ProjectSources.resolvePath(root, path) == null) {
                issues += Issue("dependencies['$dep']", "dependency path does not exist: $path")
            }
        }
        return issues
    }

    companion object {
        /** The targets a project may name. */
        val TARGETS = setOf("c", "native", "cpp", "c++", "js", "javascript", "py", "python", "neko", "none")

        /** The extension the source scan reads as a module; no generated file may end with it. */
        const val KIRA_SOURCE_EXT = ".kira"
    }
}

/**
 * How much the typed frontend does for the C and JS backends. The C++ backend
 * always runs the typer STRICT regardless of this setting; `off` is the
 * default so C and JS stay byte-identical while the typer is young.
 */
enum class TypeCheckMode {
    OFF,
    LENIENT,
    STRICT;

    companion object {
        fun parse(raw: String): TypeCheckMode {
            return when (raw.trim().lowercase()) {
                "off" -> OFF
                "lenient" -> LENIENT
                "strict" -> STRICT
                else -> throw IllegalArgumentException("Field 'types' must be one of off, lenient, strict (got '$raw')")
            }
        }
    }
}
