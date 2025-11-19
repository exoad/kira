package net.exoad.kira.kim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PackageInfo(
    val name: String,
    val version: String = "1.0.0",
    val authors: List<String> = emptyList(),
    val description: String? = null
)

@Serializable
data class Workspace(
    val src: List<String> = listOf("*.kira"),
    val entry: String? = null
)

@Serializable
data class BuildOptions(
    val outDir: String = "build",
    val target: String = "native",
    val debug: Boolean = false,
    val emitIR: Boolean = false
)

@Serializable
data class DependencySpec(
    val path: String? = null,
    val version: String? = null,
    val registry: String? = null
)

@Serializable
data class ProjectManifest(
    val version: String = "1",
    @SerialName("package")
    val pkg: PackageInfo? = null,
    val workspace: Workspace = Workspace(),
    val build: BuildOptions = BuildOptions(),
    val dependencies: Map<String, DependencySpec> = emptyMap()
)