package net.exoad.kira.compiler.analysis.types

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one fact phase C reads from the stdlib's `*.bind.yaml` manifests: whether a `@_magic`
 * callable's C++ binding is declared `constexpr: true`, so a call to it on constant arguments
 * is a constant expression (a module constant, a `mut` global's initializer under D49, a
 * static assert's condition). `kira::abs` is; `std::sqrt` is not on MSVC or clang in C++20,
 * so `ROOT2: Float64 = sqrt(2.0)` is refused here, not by the C++ compiler. The flag holds
 * only when the receiver and arguments are literal types, as the manifests say (`Arr.size` is
 * constexpr on a std::array, not on the std::vector an `Arr<T>` is): that condition is
 * StmtChecker's ([TypeFacts.isLiteralType]), not this table's.
 *
 * The manifest sits beside the module that declares the symbol (`kira/core.kira` and
 * `kira/core.bind.yaml`), keyed by the symbol's [Foreign.Magic.key] (`sqrt`, `Num.abs`,
 * `StrBuf.add`). A missing manifest, key or flag means "not constexpr": the conservative
 * answer, which the C++ compiler cannot make wrong.
 */
internal class MagicBindings {
    private val manifests = HashMap<Path, Map<String, Boolean>>()

    /** True when [fn]'s cpp binding carries `constexpr: true`. */
    fun isConstexpr(fn: FnSymbol): Boolean {
        val key = (fn.foreign as? Foreign.Magic)?.key?.takeIf { it.isNotEmpty() } ?: return false
        val manifest = manifestOf(fn.module) ?: return false
        return manifests.getOrPut(manifest) { parse(manifest) }[key] == true
    }

    private fun manifestOf(module: ModuleSymbol): Path? {
        val source = runCatching { Path.of(module.source.file) }.getOrNull() ?: return null
        val name = source.fileName?.toString()?.takeIf { it.endsWith(".kira") } ?: return null
        val manifest = source.resolveSibling(name.removeSuffix(".kira") + ".bind.yaml")
        return manifest.takeIf { Files.isRegularFile(it) }
    }

    private fun parse(path: Path): Map<String, Boolean> {
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return emptyMap()
        val yaml = runCatching { Yaml().load<Any>(text) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val out = HashMap<String, Boolean>()
        yaml.forEach { (key, value) ->
            val cpp = (value as? Map<*, *>)?.get("cpp") as? Map<*, *> ?: return@forEach
            out[key.toString()] = cpp["constexpr"] == true
        }
        return out
    }
}
