package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnParam
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr

/** Where a type is spelled; the column of design table 5.1 that applies. */
enum class Pos {
    /** A local, a temporary, a template argument's inner value. */
    VALUE,

    /** A function parameter: `const kira::Str&`, `std::int32_t`, `kira::View<T>`. */
    PARAM,

    /** A `mut` parameter (D4): `kira::Str&`, `std::int32_t&`. */
    MUT_PARAM,

    /** A `mut` local, field or module state: as [VALUE], except that `Unsafe<T>` is `T*`, not `const T*`. */
    MUT_VALUE,
    RETURN,
    FIELD,
    TEMPLATE_ARG,

    /** A parameter inside a `kira::Fn<R(...)>` signature: the parameter column. */
    FN_SIG,
}

/**
 * Design table 5.1: every [KType] as C++ text, per [Pos]. Spelling from a `Type` node
 * ([spell] with a node) is alias-aware: a type written through an alias keeps the alias's
 * name (`Frame`, not `std::array<std::uint8_t, 32>`), and `Arr<UInt8, USER_CMD_BYTES>` keeps
 * the constant's name as the array size.
 */
class CppTypeSpeller(private val ctx: CppEmitContextImpl) {
    /** The node a diagnostic of the current spelling is placed at: the `Type` node being spelled, or what the caller named. */
    private var at: ASTNode? = null

    /** The type parameters [spellUnder] replaces by their arguments, at the leaves only; empty in an ordinary spelling. */
    private var leaves: Map<TypeParamSymbol, KType> = emptyMap()

    /** [t] as C++ text at [pos]; a diagnostic it raises is placed at [at] when one is given. */
    fun spell(t: KType, pos: Pos, at: ASTNode? = null): String = located(at) { wrap(base(t), t, pos) }

    /**
     * [t] as the template that wrote it spells it, with each type parameter in [under]
     * standing for its argument: what an instantiation of that template's member has, which
     * an override or a forwarder of it must match exactly. Every choice the columns make is
     * made on [t] as written, where a type parameter is never known to be by value, and only
     * then is the parameter's name replaced by its argument, spelled as a template argument.
     * So `v: T` is `const T&` and at `T = Int32` stays `const std::int32_t&`, not the
     * `std::int32_t` a declaration written at Int32 spells on its own; `Fx<Tuple1<T>, Int32>`
     * is `kira::Fn<std::int32_t(const T&)>` and stays `kira::Fn<std::int32_t(const
     * std::int32_t&)>`, where substituting first would have spelled the `kira::Fn<...(std::int32_t)>`
     * gcc calls "marked override, but does not override" (measured; MSVC C3668, and on a
     * return type "invalid covariant return type"). A parameter that stands for itself is
     * left alone.
     */
    fun spellUnder(t: KType, pos: Pos, under: Map<TypeParamSymbol, KType>, at: ASTNode? = null): String {
        val before = leaves
        leaves = under.filterNot { (param, arg) -> arg is KType.Param && arg.sym === param }
        try {
            return spell(t, pos, at)
        } finally {
            leaves = before
        }
    }

    /** The argument a type parameter stands for, spelled as a template argument in its own right (no leaf of it is replaced again). */
    private fun leaf(arg: KType): String {
        val before = leaves
        leaves = emptyMap()
        try {
            return spell(arg, Pos.TEMPLATE_ARG)
        } finally {
            leaves = before
        }
    }

    fun spell(node: Type, pos: Pos): String {
        val t = ctx.model.typeOf(node)
        if (t == null) {
            ctx.diag(node, INTERNAL_CODE, "no type was recorded for a type reference (${node.javaClass.simpleName})")
            return "/* untyped */"
        }
        return located(node) { wrap(text(node, t), t, pos) }
    }

    private inline fun located(node: ASTNode?, block: () -> String): String {
        val before = at
        if (node != null) {
            at = node
        }
        try {
            return block()
        } finally {
            at = before
        }
    }

    /** A diagnostic of the current spelling: at [at] when there is one, else on the module's file alone. */
    private fun report(code: String, message: String) {
        val node = at
        if (node != null) {
            ctx.diag(node, code, message)
        } else {
            ctx.report(CppDiagnostic(code, message, file = ctx.module.file))
        }
    }

    /**
     * `kira::Callable<F_p, R, A...>` for a non-escaping `Fx` parameter [p] of type [fn]
     * lowered to the template parameter [typeParam] (design 5.1, EscapePass).
     */
    fun callable(typeParam: String, fn: KType.Fn): String {
        val args = fn.params.joinToString("") { ", " + fnParam(it) }
        return "kira::Callable<$typeParam, ${spell(fn.ret, Pos.RETURN)}$args>"
    }

    /** The template parameter name for a non-escaping `Fx` parameter: `F_each`. */
    fun templateParamName(p: ParamSymbol): String = "F_${p.name}"

    // ---- alias-aware text from a Type node -----------------------------------------------------

    private fun text(node: Type, t: KType): String {
        ctx.model.aliasRefs[node]?.let { alias ->
            if (!isCoreAlias(alias)) {
                return aliasText(alias, node)
            }
        }
        if (node is ConstTypeArg) {
            return node.value.value.toString()
        }
        return when (t) {
            is KType.Fn -> fnText(node, t)
            is KType.Nominal -> nominalFromNode(node, t)
            else -> base(t)
        }
    }

    /**
     * `Int` and `Float` are aliases `kira:core` declares for `Int32` and `Float32`. They are
     * spelled as their targets (`std::int32_t`, `float`), never as `::kira::core::Int`: core
     * is the runtime's own module, whose traits no generated header can hold (W2.4) and
     * whose stdlib header is hosted, which a freestanding module must not include.
     */
    private fun isCoreAlias(alias: AliasSymbol): Boolean = alias.module.uri == CORE_URI

    private fun aliasText(alias: AliasSymbol, node: Type): String {
        val name = ctx.qualified(alias)
        if (alias.typeParams.isEmpty() || node.children.isEmpty()) {
            return name
        }
        val args = node.children.map { child ->
            val ct = ctx.model.typeOf(child)
            if (ct == null) "/* untyped */" else wrap(text(child, ct), ct, Pos.TEMPLATE_ARG)
        }
        return "$name<${args.joinToString(", ")}>"
    }

    private fun fnText(node: Type, t: KType.Fn): String {
        // Fx<TupleN<A..>, R>: children[0] is the tuple node, children[1] the return type.
        val tuple = node.children.getOrNull(0)
        val ret = node.children.getOrNull(1)
        if (tuple == null || ret == null || tuple.children.size != t.params.size) {
            return base(t)
        }
        val retT = ctx.model.typeOf(ret) ?: return base(t)
        val params = t.params.mapIndexed { i, p ->
            val child = tuple.children[i]
            val pt = ctx.model.typeOf(child) ?: return base(t)
            wrap(text(child, pt), pt, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM)
        }
        return "kira::Fn<${wrap(text(ret, retT), retT, Pos.RETURN)}(${params.joinToString(", ")})>"
    }

    private fun nominalFromNode(node: Type, t: KType.Nominal): String {
        if (node.children.size != t.args.size) {
            return base(t)
        }
        val args = t.args.mapIndexed { i, arg ->
            val child = node.children[i]
            when (arg) {
                is TypeArg.Ty -> {
                    val ct = ctx.model.typeOf(child) ?: return base(t)
                    ArgText(wrap(text(child, ct), ct, Pos.TEMPLATE_ARG), ct)
                }
                is TypeArg.Const -> ArgText(constArgText(child, arg), null)
            }
        }
        return nominal(t, args)
    }

    /** `Arr<UInt8, USER_CMD_BYTES>` keeps the name; `Arr<UInt8, 4>` keeps the digits (as C++ reads them, [CppEmitContextImpl.integerText]). */
    private fun constArgText(child: Type, arg: TypeArg.Const): String {
        if (child is ConstTypeArg) {
            return ctx.integerText(child.value, null)
        }
        val id = child.identifier as? Identifier
        if (id != null && id !is IntrinsicExpr) {
            (ctx.model.symbolOf(id) as? GlobalSymbol)?.let { return ctx.qualified(it) }
            // Phase B resolves the constant without recording the identifier; find it by name.
            val lookup = ctx.program.graph.lookup(ctx.symbol, id.value) { it is GlobalSymbol }
            if (lookup is net.exoad.kira.compiler.analysis.types.ModuleGraph.Lookup.Found) {
                return ctx.qualified(lookup.symbol)
            }
        }
        return arg.n.toString()
    }

    // ---- text from a KType ---------------------------------------------------------------------

    private class ArgText(val text: String, val type: KType?)

    private fun base(t: KType): String = when (t) {
        is KType.Scalar -> scalar(t.prim)
        KType.Str -> "kira::Str"
        KType.Void -> "void"
        KType.Never -> "void"
        KType.NullT -> {
            report(INTERNAL_CODE, "the type Null has no C++ spelling of its own")
            "/* Null */"
        }
        KType.Error -> {
            report(INTERNAL_CODE, "an unresolved type reached the C++ emitter")
            "/* error */"
        }
        is KType.Param -> leaves[t.sym]?.let { leaf(it) } ?: ctx.names.escape(t.sym.name)
        is KType.Fn -> "kira::Fn<${spell(t.ret, Pos.RETURN)}(${t.params.joinToString(", ") { fnParam(it) }})>"
        is KType.Nominal -> nominal(t, t.args.map { arg ->
            when (arg) {
                is TypeArg.Ty -> ArgText(spell(arg.t, Pos.TEMPLATE_ARG), arg.t)
                is TypeArg.Const -> ArgText(arg.n.toString(), null)
            }
        })
    }

    private fun fnParam(p: FnParam): String = spell(p.type, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM)

    /** `std::int32_t`, `kira::Size`, `float`: the value column of a scalar. */
    fun scalarName(p: Prim): String = scalar(p)

    private fun scalar(p: Prim): String = when (p) {
        Prim.INT8 -> "std::int8_t"
        Prim.INT16 -> "std::int16_t"
        Prim.INT32 -> "std::int32_t"
        Prim.INT64 -> "std::int64_t"
        Prim.UINT8 -> "std::uint8_t"
        Prim.UINT16 -> "std::uint16_t"
        Prim.UINT32 -> "std::uint32_t"
        Prim.UINT64 -> "std::uint64_t"
        Prim.SIZE -> "kira::Size"
        Prim.FLOAT32 -> "float"
        Prim.FLOAT64 -> "double"
        Prim.BOOL -> "bool"
        Prim.CHAR -> "char"
    }

    private fun nominal(t: KType.Nominal, args: List<ArgText>): String {
        val sym = t.sym
        val targs = if (args.isEmpty()) "" else "<${args.joinToString(", ") { it.text }}>"
        return when (sym) {
            is ClassSymbol -> when {
                sym.kind == ClassKind.MAGIC -> magic(sym, t, args)
                sym.kind == ClassKind.OPAQUE -> "${externName(sym) ?: ctx.qualified(sym)}$targs*"
                sym.isStruct -> "${externName(sym) ?: ctx.qualified(sym)}$targs"
                else -> "kira::Rc<${externName(sym) ?: ctx.qualified(sym)}$targs>"
            }
            is TraitSymbol -> "kira::Rc<${ctx.qualified(sym)}$targs>"
            is EnumSymbol -> ctx.qualified(sym)
            is AliasSymbol -> if (isCoreAlias(sym)) base(sym.target) else ctx.qualified(sym) + targs
            else -> ctx.qualified(sym as net.exoad.kira.compiler.analysis.types.Symbol) + targs
        }
    }

    /** A class or trait as the bare C++ class, for `kira::Weak<C>` and `kira::Shared<C>`. */
    fun bareClass(t: KType): String {
        val n = t as? KType.Nominal ?: return spell(t, Pos.VALUE)
        val sym = n.sym
        val targs = if (n.args.isEmpty()) "" else n.args.joinToString(", ", "<", ">") { spellArg(it) }
        return when (sym) {
            is ClassSymbol -> (externName(sym) ?: systemClass(sym) ?: ctx.qualified(sym)) + targs
            is TraitSymbol -> ctx.qualified(sym) + targs
            else -> spell(t, Pos.VALUE)
        }
    }

    /**
     * A `@_magic` class the runtime defines under its own module's namespace (`kira:sync`'s
     * `Thread` is `kira::sync::Thread` in `kira/sync.hxx`, [SYSTEM_MODULE_HEADERS]): the bare
     * class name, with the runtime header included. Null for any other class.
     */
    private fun systemClass(sym: ClassSymbol): String? {
        if (!isSystemClass(sym)) {
            return null
        }
        ctx.includeInHeader(systemHeaderFor(sym.module.uri)!!)
        return "${ctx.layout.namespaceFor(sym.module.uri)}::${ctx.names.escape(sym.name)}"
    }

    /**
     * Whether [sym] is a `@_magic` class of a system module ([SYSTEM_MODULE_HEADERS]): a Kira
     * class the runtime defines, held as `kira::Rc<C>` and constructed through `make_shared`
     * like any class (R9), never a value container like `List` or `Map`.
     */
    fun isSystemClass(sym: ClassSymbol): Boolean = sym.kind == ClassKind.MAGIC && systemHeaderFor(sym.module.uri) != null

    private fun spellArg(arg: TypeArg): String = when (arg) {
        is TypeArg.Ty -> spell(arg.t, Pos.TEMPLATE_ARG)
        is TypeArg.Const -> arg.n.toString()
    }

    /**
     * `@_extern(cpp = "bibo::Car")`, or `@_extern(c = "cnt_state")`: the name the marker gives
     * the type, fully qualified from the global namespace. [CppExternEmitter.cppName] is the
     * one reading of the marker (`cpp =`, the positional symbol, then `c =`), so a type
     * reached through a C header is spelled the same in a signature, a check and a body as
     * in its sizeof and field checks. Reading `cpp =` alone here spelled a `c = "cnt_state"`
     * struct as its Kira name wherever the type appeared, and the module's header failed
     * with 'CntState was not declared' instead of building (measured, g++ 13.2).
     */
    private fun externName(sym: TypeSymbol): String? {
        val s: Symbol = when (sym) {
            is ClassSymbol -> sym
            is TraitSymbol -> sym
            else -> return null
        }
        return if (CppExternEmitter.externOf(s) == null) null else CppExternEmitter.globalName(s)
    }

    private fun magic(sym: ClassSymbol, t: KType.Nominal, args: List<ArgText>): String {
        val a = args.map { it.text }
        fun arg(i: Int): String = a.getOrNull(i) ?: "/* missing */"
        return when (sym.name) {
            "List" -> "kira::List<${arg(0)}>"
            Builtins.ARR -> if (t.args.size >= 2) "std::array<${arg(0)}, ${arg(1)}>" else "kira::List<${arg(0)}>"
            "Map" -> "kira::Map<${arg(0)}, ${arg(1)}>"
            "Set" -> "kira::Set<${arg(0)}>"
            "Deque" -> "kira::Deque<${arg(0)}>"
            "Stack" -> "kira::Stack<${arg(0)}>"
            "Queue" -> "kira::Queue<${arg(0)}>"
            "View" -> "kira::View<${arg(0)}>"
            "MutView" -> "kira::MutView<${arg(0)}>"
            "Maybe" -> "kira::Maybe<${arg(0)}>"
            "Result" -> "kira::Result<${arg(0)}, ${arg(1)}>"
            "Weak" -> "kira::Weak<${args.getOrNull(0)?.type?.let { bareClass(it) } ?: arg(0)}>"
            "Ref" -> "kira::Rc<kira::Box<${arg(0)}>>"
            "Unsafe" -> "${arg(0)}*"
            Builtins.STRBUF -> "kira::StrBuf<${arg(0)}>"
            "CStr" -> "const char*"
            else -> {
                Builtins.tupleArity(sym.name)?.let { n ->
                    return if (n == 0) "kira::Tuple0" else "kira::Tuple$n<${a.joinToString(", ")}>"
                }
                // A class of a hosted system module is a Kira class the runtime defines (table 5.1's
                // class row): `kira::Rc<kira::sync::Mutex<std::int32_t>>`, `kira::Rc<kira::test::Suite>`.
                systemClass(sym)?.let { name ->
                    val targs = if (a.isEmpty()) "" else "<${a.joinToString(", ")}>"
                    return "kira::Rc<$name$targs>"
                }
                report(CppModuleEmitterFactory.UNSUPPORTED_CODE, "the type ${t.display()} is not lowered yet")
                "/* ${t.display()} */"
            }
        }
    }

    // ---- the position columns ------------------------------------------------------------------

    private fun wrap(text: String, t: KType, pos: Pos): String {
        // Unsafe<T> is `const T*` unless the binding is `mut` (table 5.1); [magic] spells the bare `T*`.
        // The const qualifies the pointee: `Unsafe<Unsafe<T>>` is `const T* const*`, never `const const T**`.
        if (isUnsafe(t)) {
            if (pos == Pos.MUT_PARAM || pos == Pos.MUT_VALUE) {
                return text
            }
            val pointee = text.removeSuffix("*")
            return if (pointee.endsWith("*")) "$pointee const*" else "const $text"
        }
        return when (pos) {
            Pos.VALUE, Pos.MUT_VALUE, Pos.RETURN, Pos.FIELD, Pos.TEMPLATE_ARG -> text
            // `const T&` with T a pointer (a type parameter standing for Unsafe<X>, an opaque
            // class or CStr under [spellUnder]) is `X* const&` in C++: the const binds to T.
            Pos.PARAM, Pos.FN_SIG -> when {
                byValue(t) -> text
                t is KType.Param && text.endsWith("*") -> "$text const&"
                else -> "const $text&"
            }
            Pos.MUT_PARAM -> "$text&"
        }
    }

    private fun isUnsafe(t: KType): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.MAGIC && sym.name == "Unsafe"
    }

    /** Types the parameter column passes by value: scalars, views, enums, raw pointers. */
    fun byValue(t: KType): Boolean = when (t) {
        is KType.Scalar, KType.Void, KType.Never, KType.NullT, KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> true
            is ClassSymbol -> when (sym.kind) {
                ClassKind.OPAQUE -> true
                ClassKind.MAGIC -> sym.name in BY_VALUE_MAGIC
                else -> false
            }
            else -> false
        }
        else -> false
    }

    companion object {
        const val INTERNAL_CODE = "cpp.internal"

        /** The stdlib module whose aliases (`Int`, `Float`) are spelled as their targets. */
        const val CORE_URI = "kira:core"
        private val BY_VALUE_MAGIC = setOf("View", "MutView", "Unsafe", "CStr")

        /**
         * The hosted system modules whose `@_magic` classes the runtime defines in a header
         * of its own, under the module's namespace (design 4.3, section 6's runtime list:
         * `kira/sync.hxx` holds `kira::sync::Thread`, `Mutex`, `Atomic`, `BlockingQueue`;
         * `kira/test.hxx` `kira::test::Suite`; `kira/os.hxx` `kira::os::UdpSocket` and the
         * rest; `kira/time.hxx` its functions). A magic class of any other module (`kira:result`'s
         * `Exception`, which no runtime type backs) is `cpp.unsupported`.
         */
        val SYSTEM_MODULE_HEADERS: Map<String, String> = mapOf(
            "kira:sync" to "kira/sync.hxx",
            "kira:test" to "kira/test.hxx",
            "kira:time" to "kira/time.hxx",
            "kira:os" to "kira/os.hxx",
        )

        /** The runtime header of the system module [uri], or null when it has none. */
        fun systemHeaderFor(uri: String): String? = SYSTEM_MODULE_HEADERS[uri]
    }
}
