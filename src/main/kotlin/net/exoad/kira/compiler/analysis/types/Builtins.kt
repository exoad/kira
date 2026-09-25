package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.source.SourceContext
import java.util.IdentityHashMap

/**
 * The names the typer knows without looking at a declaration's shape (design 3.1):
 *
 * - `Int8`..`UInt64`, `Size`, `Float32`/`Float64`, `Bool` and `Char` are [KType.Scalar];
 * - `Str`, `Void`, `Never` and `Null` are their own KTypes;
 * - `List Map Set Deque Stack Queue Arr View MutView Maybe Result Tuple0..9 Fx Weak Ref Unsafe`
 *   are [KType.Nominal] of their magic [ClassSymbol]. `Arr` resolves by arity: `Arr<T>` or
 *   `Arr<T, N>`. A fully applied `Fx<TupleN<...>, R>` becomes a [KType.Fn].
 *
 * A name maps here when the stdlib declares it `@_magic` (kira:core's `pub @_magic class
 * Int32: Num`) and also when the stdlib does not declare it at all: UInt8..UInt64, Size, Char,
 * View, MutView, Fx, Ref, Weak and Unsafe arrive with the stdlib surface package, and the
 * typer must recognise them without requiring them. For a missing nominal name the typer
 * synthesizes a magic ClassSymbol in the [module] `kira:builtin`, which has no source file.
 *
 * A user declaration of the same name (a non-magic `class View` in the user's module) is an
 * ordinary type and shadows the builtin, by the normal scope order.
 */
class Builtins(declared: Collection<ClassSymbol>) {
    /** The synthetic module that owns synthesized builtins. It is never in TypedProgram.modules. */
    val module: ModuleSymbol = syntheticModule()

    private val classes: MutableMap<String, ClassSymbol> = linkedMapOf()
    private val synthesized = IdentityHashMap<ClassSymbol, Boolean>()

    init {
        // The stdlib's own magic declarations win; the first module (by URI) that declares a name keeps it.
        declared.filter { it.kind == ClassKind.MAGIC }.forEach { classes.putIfAbsent(it.name, it) }
    }

    /**
     * The magic class behind a builtin name: the stdlib's declaration when there is one, else
     * a synthesized one (for a nominal builtin) or null (for a name that is not a builtin
     * nominal and is not declared).
     */
    fun classFor(name: String): ClassSymbol? {
        classes[name]?.let { return it }
        val params = NOMINAL_PARAMS[name] ?: return null
        val typeParams = params.mapIndexed { i, p -> TypeParamSymbol(p, module, null, i) }
        val sym = ClassSymbol(name, module, null, ClassKind.MAGIC, typeParams)
        typeParams.forEach { it.owner = sym }
        sym.isPub = true
        sym.foreign = Foreign.Magic(name)
        classes[name] = sym
        synthesized[sym] = true
        module.members[name] = sym
        return sym
    }

    /** True when [sym] was synthesized because the stdlib does not declare it. */
    fun isSynthesized(sym: ClassSymbol): Boolean = synthesized.containsKey(sym)

    /** The class a builtin type's members come from: Str's methods, Int32's (through Num), a container's. */
    fun classOf(type: KType): ClassSymbol? = when (type) {
        is KType.Scalar -> classes[type.prim.kiraName]
        KType.Str -> classes["Str"]
        KType.Void -> classes["Void"]
        KType.Never -> classes["Never"]
        KType.NullT -> classes["Null"]
        is KType.Nominal -> type.sym as? ClassSymbol
        is KType.Fn -> classes["Fx"]
        else -> null
    }

    /** Every builtin class known so far, declared or synthesized, by name. */
    fun all(): Map<String, ClassSymbol> = classes.toMap()

    companion object {
        val SPECIAL: Map<String, KType> = linkedMapOf(
            "Str" to KType.Str,
            "Void" to KType.Void,
            "Never" to KType.Never,
            "Null" to KType.NullT,
        )

        /** Nominal builtins and the type-parameter names a synthesized declaration gets. */
        val NOMINAL_PARAMS: Map<String, List<String>> = buildMap {
            put("List", listOf("T"))
            put("Map", listOf("K", "V"))
            put("Set", listOf("T"))
            put("Deque", listOf("T"))
            put("Stack", listOf("T"))
            put("Queue", listOf("T"))
            put("Arr", listOf("T"))
            put("View", listOf("T"))
            put("MutView", listOf("T"))
            put("Maybe", listOf("T"))
            put("Result", listOf("T", "E"))
            val letters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I")
            for (n in 0..9) {
                put("Tuple$n", letters.take(n))
            }
            put("Fx", listOf("P", "R"))
            put("Weak", listOf("T"))
            put("Ref", listOf("T"))
            put("Unsafe", listOf("T"))
            // Named by the design outside its 3.1 list, and declared by no package yet:
            // `StrBuf<N>`, the freestanding text buffer (design 6 and 10), takes one constant
            // argument the way Arr's size does; `CStr` is the FFI `const char*` (design 7.2).
            put("StrBuf", emptyList())
            put("CStr", emptyList())
        }

        /** The names of the nominal builtins, in design order. */
        val NOMINAL: Set<String> get() = NOMINAL_PARAMS.keys

        /** `Arr` takes its element type and, optionally, a constant size. */
        const val ARR = "Arr"

        /** `Fx<TupleN<...>, R>` resolves to a [KType.Fn]. */
        const val FX = "Fx"

        /** `StrBuf<N>`: exactly one constant argument, its capacity. */
        const val STRBUF = "StrBuf"

        fun prim(name: String): Prim? = Prim.byKiraName(name)

        /** True for every name this object maps: prims, the special types and the nominal builtins. */
        fun isBuiltinName(name: String): Boolean = prim(name) != null || name in SPECIAL || name in NOMINAL_PARAMS

        /** `Tuple0`..`Tuple9`: the arity, else null. */
        fun tupleArity(name: String): Int? {
            if (!name.startsWith("Tuple")) {
                return null
            }
            val n = name.removePrefix("Tuple").toIntOrNull() ?: return null
            return if (n in 0..9 && name == "Tuple$n") n else null
        }

        private fun syntheticModule(): ModuleSymbol {
            val source = SourceContext("", "<kira:builtin>", emptyList())
            source.ast = RootASTNode(emptyList())
            source.astOrigins = IdentityHashMap()
            source.astIntrinsicMarked = IdentityHashMap()
            return ModuleSymbol("kira:builtin", source)
        }
    }
}
