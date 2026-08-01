package net.exoad.kira.kim

data class ProjectSpec(
    val name: String
)

data class BuildOptions(
    val target: String = "c",
    /** Extra .c/.o files linked with out.kira.c (paths relative to project root). */
    val cSources: List<String> = emptyList(),
    /** Extra flags passed to cc after sources (e.g. -framework Cocoa). */
    val linkFlags: List<String> = emptyList(),
    /** When true (default), generated C/JS user code is minified + obfuscated. */
    val minify: Boolean = true,
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
