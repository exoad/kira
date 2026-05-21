package net.exoad.kira.kim

import java.nio.file.Path
import kotlin.io.path.exists

data class ValidationIssue(val field: String, val message: String)

object ManifestValidator {
    private val supportedTargets = setOf("c", "native", "neko", "none")

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
}