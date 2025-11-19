package net.exoad.kira.kim

import java.nio.file.Path
import kotlin.io.path.exists

data class ValidationIssue(val field: String, val message: String)

object ManifestValidator {
    fun validate(manifest: ProjectManifest, projectRoot: Path): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (manifest.version != "1") {
            issues += ValidationIssue("manifest_version", "unsupported manifest_version: ${manifest.version}")
        }
        val pkg = manifest.pkg
        if (pkg == null) {
            issues += ValidationIssue("package", "missing [package] section")
        } else {
            if (pkg.name.isBlank()) {
                issues += ValidationIssue("package.name", "package.name cannot be blank")
            }
        }
        val entry = manifest.workspace.entry
        if (entry != null) {
            val entryPath = projectRoot.resolve(entry)
            if (!entryPath.exists()) {
                issues += ValidationIssue("workspace.entry", "entry file does not exist: $entry")
            }
        }
        return issues
    }
}