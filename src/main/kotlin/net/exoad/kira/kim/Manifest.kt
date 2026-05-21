package net.exoad.kira.kim

data class ProjectSpec(
    val name: String
)

data class BuildOptions(
    val target: String = "c"
)

data class CompilerOptions(
    val emitIr: String? = null
)

data class DependencySpec(
    val path: String? = null
)

data class ProjectManifest(
    val project: ProjectSpec,
    val srcDir: String = "src",
    val build: BuildOptions = BuildOptions(),
    val compiler: CompilerOptions = CompilerOptions(),
    val dependencies: Map<String, DependencySpec> = emptyMap()
)