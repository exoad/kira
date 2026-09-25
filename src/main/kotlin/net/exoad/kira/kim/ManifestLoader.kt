package net.exoad.kira.kim

import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object ManifestLoader {
    fun loadFromPath(manifestPath: Path): ProjectManifest {
        require(manifestPath.exists()) { "Manifest file not found: $manifestPath" }
        return parse(Files.readString(manifestPath))
    }

    fun parse(content: String): ProjectManifest {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(content).toStringKeyMap("kira.yaml")

        val project = root["project"].toStringKeyMap("project")
        val projectName = project.requiredString("name", "project.name")

        val srcDir = root.optionalString("srcDir") ?: "src"
        val srcExclude = root.optionalStringList("srcExclude")
            ?: root.optionalStringList("src_exclude")
            ?: emptyList()

        val buildMap = root.optionalMap("build")
        val target = buildMap?.optionalString("target") ?: "c"
        val cSources = buildMap?.optionalStringList("cSources")
            ?: buildMap?.optionalStringList("c_sources")
            ?: emptyList()
        val linkFlags = buildMap?.optionalStringList("linkFlags")
            ?: buildMap?.optionalStringList("link_flags")
            ?: emptyList()
        val minify = buildMap?.optionalBoolean("minify") ?: true
        val cpp = buildMap?.optionalMap("cpp")?.let { parseCpp(it) } ?: CppOptions()

        val compilerMap = root.optionalMap("compiler")
        val emitIr = compilerMap?.optionalString("emitIr") ?: compilerMap?.optionalString("emit_ir")
        val types = compilerMap?.optionalString("types")?.let { TypeCheckMode.parse(it) } ?: TypeCheckMode.OFF

        val dependencies = root.optionalMap("dependencies")?.entries?.associate { (name, rawSpec) ->
            val spec = rawSpec.toStringKeyMap("dependencies.$name")
            name to DependencySpec(path = spec.optionalString("path"))
        } ?: emptyMap()

        return ProjectManifest(
            project = ProjectSpec(name = projectName),
            srcDir = srcDir,
            srcExclude = srcExclude,
            build = BuildOptions(
                target = target,
                cSources = cSources,
                linkFlags = linkFlags,
                minify = minify,
                cpp = cpp,
            ),
            compiler = CompilerOptions(emitIr = emitIr, types = types),
            dependencies = dependencies
        )
    }

    private fun parseCpp(cpp: Map<String, Any?>): CppOptions {
        val defaults = CppOptions()
        return CppOptions(
            layout = cpp.optionalString("layout")?.let { CppLayout.parse(it) } ?: defaults.layout,
            outDir = cpp.optionalString("outDir") ?: cpp.optionalString("out_dir") ?: defaults.outDir,
            runtimeDir = cpp.optionalString("runtimeDir") ?: cpp.optionalString("runtime_dir"),
            lineDirectives = cpp.optionalBoolean("lineDirectives")
                ?: cpp.optionalBoolean("line_directives")
                ?: defaults.lineDirectives,
            namespaces = cpp.optionalStringMap("namespaces") ?: emptyMap(),
            headerOnly = cpp.optionalStringList("headerOnly")
                ?: cpp.optionalStringList("header_only")
                ?: emptyList(),
            freestanding = cpp.optionalStringList("freestanding") ?: emptyList(),
            headerExt = cpp.optionalString("headerExt") ?: cpp.optionalString("header_ext") ?: defaults.headerExt,
            sourceExt = cpp.optionalString("sourceExt") ?: cpp.optionalString("source_ext") ?: defaults.sourceExt,
        )
    }

    private fun Any?.toStringKeyMap(context: String): Map<String, Any?> {
        if (this !is Map<*, *>) {
            throw IllegalArgumentException("$context must be a YAML object")
        }
        return this.entries.associate { (k, v) ->
            val key = k as? String ?: throw IllegalArgumentException("$context contains a non-string key")
            key to v
        }
    }

    private fun Map<String, Any?>.requiredString(key: String, fieldName: String): String {
        val value = optionalString(key)
        if (value == null) {
            throw IllegalArgumentException("Missing required field '$fieldName'")
        }
        return value
    }

    private fun Map<String, Any?>.optionalString(key: String): String? {
        val value = this[key] ?: return null
        if (value !is String) {
            throw IllegalArgumentException("Field '$key' must be a string")
        }
        return value.trim()
    }

    private fun Map<String, Any?>.optionalMap(key: String): Map<String, Any?>? {
        val value = this[key] ?: return null
        return value.toStringKeyMap(key)
    }

    /** A YAML object whose values are all strings, in the manifest's order. */
    private fun Map<String, Any?>.optionalStringMap(key: String): Map<String, String>? {
        val value = optionalMap(key) ?: return null
        val result = LinkedHashMap<String, String>()
        value.forEach { (k, v) ->
            if (v !is String) {
                throw IllegalArgumentException("Field '$key.$k' must be a string")
            }
            result[k.trim()] = v.trim()
        }
        return result
    }

    private fun Map<String, Any?>.optionalBoolean(key: String): Boolean? {
        val value = this[key] ?: return null
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> throw IllegalArgumentException("Field '$key' must be a boolean")
            }
            else -> throw IllegalArgumentException("Field '$key' must be a boolean")
        }
    }

    private fun Map<String, Any?>.optionalStringList(key: String): List<String>? {
        val value = this[key] ?: return null
        if (value !is List<*>) {
            throw IllegalArgumentException("Field '$key' must be a list of strings")
        }
        return value.mapIndexed { index, item ->
            item as? String
                ?: throw IllegalArgumentException("Field '$key[$index]' must be a string")
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
