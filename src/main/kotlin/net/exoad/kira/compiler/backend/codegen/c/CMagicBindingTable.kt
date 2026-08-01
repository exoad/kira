package net.exoad.kira.compiler.backend.codegen.c

import net.exoad.kira.Public
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Magic binding manifest for the C backend.
 *
 * `@_magic` declarations in the `kira/` stdlib modules carry only the
 * signature the typechecker needs. The C symbol a declaration lowers to --
 * and any include the call site must bring into the translation unit --
 * lives in a sibling `*.bind.yaml` manifest next to the module.
 *
 * Loading the table from disk instead of hardcoding it in Kotlin means adding
 * a stdlib entry is a data change, not a compiler change: declare it
 * `@_magic` in the module, add one binding to the module's manifest, and the
 * backend resolves it. The only magic names that cannot be bound this way are
 * type-directed intrinsics (print family): their format string is synthesized
 * from the Kira argument type at each call site, so they stay in codegen.
 *
 * Resolution is keyed by the canonical Kira name -- lowercased, with a leading
 * `@` and surrounding `_` stripped -- the same canonicalization
 * [KiraCCodeGenerator.mapIntrinsicName] applies to intrinsic spellings.
 */
object CMagicBindingTable {
    data class Binding(
        val symbol: String,
        val includes: Set<String> = emptySet()
    )

    private val bindings: Map<String, Binding> by lazy { load() }

    /** C symbol a magic name lowers to, or null when the name is unbound. */
    fun resolveFunctionOrNull(name: String): String? {
        return bindings[name]?.symbol
    }

    /**
     * Includes a magic call site must bring into the translation unit.
     *
     * Null when [name] is unbound -- callers fall back to their previous
     * resolution source. A bound name with no includes returns the empty set,
     * which is distinct from unbound.
     */
    fun includesOrNull(name: String): Set<String>? {
        return bindings[name]?.includes
    }

    private fun load(): Map<String, Binding> {
        val out = mutableMapOf<String, Binding>()
        val candidates = linkedSetOf<Path>()
        candidates.addAll(bindFilesInStdlibSources())
        candidates.addAll(bindFilesInCwdKiraDir())
        candidates.forEach { out.putAll(parse(it)) }
        return out
    }

    /** Sibling `*.bind.yaml` files next to every discovered stdlib `.kira` module. */
    private fun bindFilesInStdlibSources(): List<Path> {
        return Public.Builtin.intrinsicalStandardLibrarySources
            .mapNotNull { sourcePath ->
                val kira = Path.of(sourcePath)
                if (!kira.fileName.toString().endsWith(".kira")) return@mapNotNull null
                val moduleBase = kira.fileName.toString().removeSuffix(".kira")
                kira.resolveSibling("$moduleBase.bind.yaml")
            }
            .filter { Files.isRegularFile(it) }
    }

    /** Fallback for test / CLI runs that never populated the stdlib registry. */
    private fun bindFilesInCwdKiraDir(): List<Path> {
        val root = Path.of("kira").toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".bind.yaml") }
                .toList()
        }
    }

    private fun parse(path: Path): Map<String, Binding> {
        val out = mutableMapOf<String, Binding>()
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return out
        val yaml = runCatching { Yaml().load<Any>(text) }.getOrNull() ?: return out
        if (yaml !is Map<*, *>) return out
        yaml.forEach { (key, value) ->
            val name = key?.toString() ?: return@forEach
            val binding = when (value) {
                is String -> Binding(value)
                is Map<*, *> -> {
                    val symbol = value["symbol"]?.toString() ?: return@forEach
                    val includes = (value["includes"] as? List<*>)
                        ?.mapNotNull { it?.toString() }
                        ?.toSet()
                        ?: emptySet()
                    Binding(symbol, includes)
                }
                else -> return@forEach
            }
            out[name] = binding
        }
        return out
    }
}
