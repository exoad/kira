package net.exoad.kira.kim

import java.nio.file.Path
import kotlin.io.path.exists

data class ValidationIssue(val field: String, val message: String)

object ManifestValidator {
    private val supportedTargets = setOf("c", "native", "cpp", "c++", "js", "javascript", "neko", "none")

    fun validate(manifest: ProjectManifest, projectRoot: Path): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (manifest.project.name.isBlank()) {
            issues += ValidationIssue("project.name", "project.name cannot be blank")
        }

        if (manifest.srcDir.isBlank()) {
            issues += ValidationIssue("srcDir", "srcDir cannot be blank")
        } else {
            val srcPath = projectRoot.resolve(manifest.srcDir).normalize()
            if (!srcPath.exists()) {
                issues += ValidationIssue("srcDir", "srcDir does not exist: ${manifest.srcDir}")
            }
        }

        if (!supportedTargets.contains(manifest.build.target.lowercase())) {
            issues += ValidationIssue(
                "build.target",
                "unsupported build target '${manifest.build.target}', expected one of ${supportedTargets.joinToString(", ")}"
            )
        }

        val cpp = manifest.build.cpp
        if (cpp.outDir.isBlank()) {
            issues += ValidationIssue("build.cpp.outDir", "build.cpp.outDir cannot be blank")
        }
        if (cpp.runtimeDir != null && cpp.runtimeDir.isBlank()) {
            issues += ValidationIssue("build.cpp.runtimeDir", "build.cpp.runtimeDir cannot be blank")
        }
        if (!cpp.headerExt.startsWith(".")) {
            issues += ValidationIssue("build.cpp.headerExt", "build.cpp.headerExt must start with '.': ${cpp.headerExt}")
        }
        if (!cpp.sourceExt.startsWith(".")) {
            issues += ValidationIssue("build.cpp.sourceExt", "build.cpp.sourceExt must start with '.': ${cpp.sourceExt}")
        }
        if (cpp.headerExt == cpp.sourceExt) {
            issues += ValidationIssue("build.cpp.sourceExt", "build.cpp.headerExt and sourceExt must differ")
        }
        cpp.namespaces.forEach { (uri, ns) ->
            if (!isNamespacePath(ns)) {
                issues += ValidationIssue(
                    "build.cpp.namespaces.$uri",
                    "'$ns' is not a C++ namespace path (expected identifiers joined by ::)"
                )
            }
        }

        manifest.dependencies.forEach { (name, spec) ->
            val path = spec.path
            if (path.isNullOrBlank()) {
                issues += ValidationIssue("dependencies.$name.path", "dependency path must be provided")
                return@forEach
            }
            val resolved = DependencyResolver.resolvePath(projectRoot, path)
            if (resolved == null) {
                issues += ValidationIssue("dependencies.$name.path", "dependency path does not exist: $path")
            }
        }

        return issues
    }

    private val namespaceSegment = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isNamespacePath(ns: String): Boolean {
        if (ns.isBlank()) {
            return false
        }
        return ns.split("::").all { namespaceSegment.matches(it) }
    }
}
