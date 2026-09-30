package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.ConversionKind
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.prim
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import java.math.BigInteger
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Expressions (design 5.3, rules R1-R21). The part the context calls ([emit]) spells one
 * expression under a parent of a given C++ precedence and returns the text; the work is
 * [CppLowering]'s, one per module emission, which the statement and closure parts share.
 */
class CppExprEmitter : CppExprPart {
    override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = CppLowering.of(ctx).emit(e, parentPrec)
}

/**
 * One expression as C++: its [text], the C++ precedence of its top level ([CppPrec]), and the
 * binary operator at its top level when it is one ([op], `"&"`, `">>"`), which the
 * `-Wparentheses` rules read.
 */
data class CppEx(val text: String, val prec: Int, val op: String? = null)

/**
 * How a literal is spelled where it stands (R2): bare in an initializer, an assignment, a
 * return, an argument or an index ([PLAIN]); with `u` for the unsigned narrow types inside an
 * operation ([OPERAND]); `T{lit}` for the widths C++ has no literal of in a literal-only
 * operation, a ternary branch and a `Maybe` ([TYPED]); `T{lit}` for every integer width where a
 * template deduces its type from it ([DEDUCED]: `kira::div`, which on arm-none-eabi would
 * otherwise meet `int` against `long`); the digits alone as a shift count ([SHIFT_COUNT]);
 * `T{lit}` for the 64-bit widths where the literal's own type is the operation's ([WIDTH]:
 * the left operand of a shift, `std::int64_t{1} << 40`, and the operand of `~` or `-`,
 * `~std::uint64_t{0xFF}`, which as an `int` would shift past its width or sign-extend).
 */
enum class CppLitRole { PLAIN, OPERAND, TYPED, DEDUCED, SHIFT_COUNT, WIDTH }

/**
 * What the expression, statement and closure parts share while one module is emitted: the
 * function (and lambda) bodies being written, the C++ name each local was given, and the
 * names visible at each point, so a synthesized or renamed name never shadows another under
 * `-Wshadow` and a loop bound or a spilled temporary is `i_end`, `t0_` in every function that
 * needs one. One instance per [CppEmitContextImpl] ([of]); the parts are shared by every
 * module of a run, so they keep no state of their own.
 */
class CppBodyState private constructor() {
    /** How a lambda reaches the receiver of the method it is written in (design 5.6). */
    enum class ThisCapture {
        /** No capture of the receiver. */
        NONE,

        /** A struct: `[*this]`, a copy; members are read bare. */
        COPY,

        /** A class, the lambda does not escape: `[this]`; members are read bare. */
        POINTER,

        /** A class, the lambda escapes: `[self = shared_from_this()]`; members are `self->m`. */
        SELF,
    }

    /** One body being written: a function's, or a lambda's inside it ([lambda] non-null). */
    class Frame(
        val fn: FnSymbol?,
        val owner: TypeSymbol?,
        val parent: Frame?,
        val lambda: LambdaExpr? = null,
        val thisCapture: ThisCapture = ThisCapture.NONE,
    ) {
        /** Struct fields this lambda copied under a name of its own (`c_k`). */
        val fieldNames: IdentityHashMap<FieldSymbol, String> = IdentityHashMap()

        /** How the receiver is reached from here: through the nearest lambda's capture, or directly. */
        val receiverAccess: ThisCapture
            get() {
                var f: Frame? = this
                while (f != null) {
                    if (f.lambda != null) {
                        return f.thisCapture
                    }
                    f = f.parent
                }
                return ThisCapture.NONE
            }

        /** The copied name of struct field [f] in this lambda or an enclosing one, or null. */
        fun fieldName(f: FieldSymbol): String? {
            var fr: Frame? = this
            while (fr != null) {
                fr.fieldNames[f]?.let { return it }
                fr = fr.parent
            }
            return null
        }
    }

    private val names = IdentityHashMap<Symbol, String>()
    private val scopes = ArrayDeque<MutableSet<String>>()

    /** The body being written, or null at namespace or class scope (a default, an initializer). */
    var frame: Frame? = null
        private set

    /** Inside a function or lambda body: an IIFE there captures by reference (`[&]`). */
    val inBody: Boolean get() = frame != null

    /** Whether the code being written goes into the header (an include it needs goes there too). */
    var headerPlaced: Boolean = true
        private set

    /** The C++ name [sym] (a local, loop variable, lambda parameter) was declared as, or null. */
    fun nameOf(sym: Symbol): String? = names[sym]

    fun isVisible(name: String): Boolean = scopes.any { name in it }

    /** Marks [name] as declared in the innermost block. */
    fun reserve(name: String) {
        if (scopes.isEmpty()) {
            scopes.addLast(HashSet())
        }
        scopes.last().add(name)
    }

    /**
     * Declares [sym] in the innermost block as [wanted] (the escaped Kira name), or, when that
     * would shadow a visible name or one [taken] says is in use (a member, a module
     * declaration), as a synthesized `name_l`: lowercase with an underscore, no Kira name.
     */
    fun declare(sym: Symbol, wanted: String, taken: (String) -> Boolean): String {
        val name = if (isVisible(wanted) || taken(wanted)) fresh(wanted.lowercase() + "_l") else wanted
        names[sym] = name
        reserve(name)
        return name
    }

    /** Records that [sym] is spelled [name] in the innermost block (a parameter whose name the declaration emitter chose). */
    fun bind(sym: Symbol, name: String) {
        names[sym] = name
        reserve(name)
    }

    /**
     * A synthesized name for [stem] that no visible name uses: [stem] itself when it has an
     * underscore (`i_end`), else `stem0_`, `stem1_`, ...; declared in the innermost block.
     */
    fun fresh(stem: String): String {
        val base = stem.lowercase().ifEmpty { "t" }
        var candidate: String? = if (base.contains('_') && !isVisible(base)) base else null
        var n = 0
        while (candidate == null || isVisible(candidate)) {
            candidate = "$base${n}_"
            n += 1
        }
        reserve(candidate)
        return candidate
    }

    /** Runs [block] inside a nested block scope. */
    fun <T> block(block: () -> T): T {
        scopes.addLast(HashSet())
        try {
            return block()
        } finally {
            scopes.removeLast()
        }
    }

    /** Runs [block] with [f] as the current frame, in a scope of its own. */
    fun <T> inFrame(f: Frame, block: () -> T): T {
        val before = frame
        frame = f
        scopes.addLast(HashSet())
        try {
            return block()
        } finally {
            scopes.removeLast()
            frame = before
        }
    }

    /** Runs [block] with [headerPlaced] set to [header], restoring it after. */
    fun <T> placed(header: Boolean, block: () -> T): T {
        val before = headerPlaced
        headerPlaced = header
        try {
            return block()
        } finally {
            headerPlaced = before
        }
    }

    /** The lowering of this module's emission (one per state). */
    internal var lowering: CppLowering? = null

    companion object {
        private val states = WeakHashMap<CppEmitContextImpl, CppBodyState>()

        /** The state of [ctx]'s module emission. */
        fun of(ctx: CppEmitContextImpl): CppBodyState = synchronized(states) { states.getOrPut(ctx) { CppBodyState() } }
    }
}

/**
 * The lowering of expressions for one module emission (design 5.3). Reads only the typed
 * model; a fact it needs and does not find is `cpp.internal` at the node, never a guess.
 */
class CppLowering private constructor(val ctx: CppEmitContextImpl) {
    val state: CppBodyState = CppBodyState.of(ctx)
    val model: TypedModel get() = ctx.model
    val hoister: CppHoister = CppHoister(this)

    /** Copy by default (50-round4): what every by-reference use of an operand is passed as, lent or copied ([CppCopyPolicy.pass]). */
    val policy: CppCopyPolicy = CppCopyPolicy(this)
    private val program get() = ctx.program

    /** The binding table, loaded with the manifests beside this program's stdlib. */
    fun binding(key: String): CppBinding? {
        val part = ctx.parts.bindings
        if (part is CppBindingTable) {
            part.load(program)
        }
        return part.lookup(key)
    }

    /** Whether the module is compiled freestanding (design 10): no exceptions, no heap, no hosted runtime. */
    val freestanding: Boolean get() = ctx.symbol.profile == Profile.FREESTANDING || ctx.options.isFreestanding(ctx.symbol.uri)

    // ---- entry ------------------------------------------------------------------------------

    /** [e] with its coercion, parenthesized for a parent of precedence [parentPrec]. */
    fun emit(e: Expr, parentPrec: Int, role: CppLitRole = CppLitRole.PLAIN): String = wrap(coerced(e, role), parentPrec)

    fun wrap(ex: CppEx, parentPrec: Int): String = if (ex.prec < parentPrec) "(${ex.text})" else ex.text

    /** Reports `cpp.internal` at [e] and returns a placeholder text. */
    fun internal(e: Expr, message: String): CppEx {
        ctx.diag(e, CppModuleEmitterFactory.INTERNAL_CODE, message)
        return CppEx("/* ${CppEmitContextImpl.describe(e)} */", CppPrec.PRIMARY)
    }

    /** Reports `cpp.unsupported` at [e] and returns a placeholder text. */
    fun unsupported(e: Expr, construct: String): CppEx {
        ctx.unsupported(e, construct)
        return CppEx("/* ${CppEmitContextImpl.describe(e)} */", CppPrec.PRIMARY)
    }

    /** The recorded type of [e]; a missing one is an internal error ([TypedModel.require]). */
    fun typeOf(e: Expr): KType = model.require(e)

    /** [e] as C++ with the implicit conversion the typer recorded at it applied. */
    fun coerced(e: Expr, role: CppLitRole = CppLitRole.PLAIN): CppEx {
        when (val c = model.coercion(e)) {
            is Coercion.WrapSome -> {
                // A literal reaches Maybe<T> through std::optional's converting constructor, a
                // template, where int-to-narrow is C4244 under MSVC /W4 /WX: spell it T{lit}.
                if (bareLiteral(e) != null) {
                    return literal(e, CppLitRole.TYPED)!!
                }
                return raw(e, role)
            }
            is Coercion.NoneOf -> return CppEx("kira::none", CppPrec.PRIMARY)
            is Coercion.Upcast -> return upcast(e, c, raw(e, role))
            is Coercion.ToView -> {
                if (c.from == KType.Str && e is StringLiteral) {
                    return CppEx("kira::lit(${CppDeclEmitter.cppString(e.value)})", CppPrec.POSTFIX)
                }
                return raw(e, role)
            }
            else -> return raw(e, role)
        }
    }

    /**
     * [ex], the C++ of [e], under the upcast [c] (a class to its superclass or a trait it
     * implements): a `kira::Rc` converts implicitly, a nullable one too. The typer refuses a
     * struct boxed into a trait value (D43). W2.4's `CppClassesPart.upcast` spells this on its
     * branch; at the merge this delegates to it.
     */
    fun upcast(@Suppress("UNUSED_PARAMETER") e: Expr, @Suppress("UNUSED_PARAMETER") c: Coercion.Upcast, ex: CppEx): CppEx = ex

    /** [e] as C++, without its coercion. */
    fun raw(e: Expr, role: CppLitRole = CppLitRole.PLAIN): CppEx {
        literal(e, role)?.let { return it }
        return when (e) {
            is CharLiteral -> CppEx(CppDeclEmitter.cppChar(e.value), CppPrec.PRIMARY)
            is StringLiteral -> CppEx(CppDeclEmitter.cppString(e.value), CppPrec.PRIMARY)
            is NullLiteral -> CppEx("kira::none", CppPrec.PRIMARY)
            is InterpolatedStringLiteral -> interpolation(e)
            is ArrayLiteral -> arrayLiteral(e)
            is IntrinsicExpr -> intrinsic(e)
            is Identifier -> identifier(e)
            is ThisExpr -> thisValue(e)
            is MemberAccessExpr -> memberAccess(e)
            is FunctionCallExpr -> call(e)
            is ObjectInitExpr -> construction(e)
            is ArrayIndexExpr -> index(e)
            is BinaryExpr -> binary(e)
            is UnaryExpr -> unary(e)
            is TypeCastExpr -> cast(e)
            is IfExpr -> ifExpr(e)
            is LambdaExpr -> CppEx(ctx.lambda(e, CppPrec.NONE), CppPrec.PRIMARY)
            is AssignmentExpr -> assignment(e.target, e.value, e)
            is CompoundAssignmentExpr -> compound(e.left, e.operator, e.right, e)
            is PlaceAssignmentExpr -> if (e.operator == null) {
                assignment(e.target, e.value, e)
            } else {
                compound(e.target, e.operator, e.value, e)
            }
            is ThrowExpr -> throwExpr(e)
            NoExpr -> CppEx("", CppPrec.PRIMARY)
            else -> unsupported(e, "the expression ${CppEmitContextImpl.describe(e)}")
        }
    }

    // ---- literals (R2, R3) ------------------------------------------------------------------

    /** A numeric literal, or a sign applied to one, and whether it is negated; else null. */
    fun bareLiteral(e: Expr): Pair<Expr, Boolean>? = when (e) {
        is IntegerLiteral, is FloatLiteral -> e to false
        is UnaryExpr -> if (e.operator == UnaryOp.NEG || e.operator == UnaryOp.POS) {
            bareLiteral(e.operand)?.let { (lit, neg) -> lit to (neg xor (e.operator == UnaryOp.NEG)) }
        } else {
            null
        }
        else -> null
    }

    /** Built of numeric literals only (`1000000 * 1000000`): such an operand took the other side's type. */
    fun isLiteralOnly(e: Expr): Boolean = when (e) {
        is IntegerLiteral, is FloatLiteral -> true
        is UnaryExpr -> e.operator != UnaryOp.NOT && isLiteralOnly(e.operand)
        is BinaryExpr -> e.operator in ARITH_OR_BITS && isLiteralOnly(e.leftExpr) && isLiteralOnly(e.rightExpr)
        else -> false
    }

    /** [e] spelled as a literal of its recorded type under [role], or null when [e] is not a numeric literal. */
    fun literal(e: Expr, role: CppLitRole): CppEx? {
        val (lit, negative) = bareLiteral(e) ?: return null
        val prim = model.typeOrNull(e)?.prim
        return when (lit) {
            is IntegerLiteral -> intLiteral(lit, negative, prim, role)
            is FloatLiteral -> floatLiteral(lit, negative, prim)
            else -> null
        }
    }

    private fun intLiteral(lit: IntegerLiteral, negative: Boolean, prim: Prim?, role: CppLitRole): CppEx {
        val sign = if (negative) "-" else ""
        val precOfSigned = if (negative) CppPrec.UNARY else CppPrec.PRIMARY
        if (prim != null && prim.isFloat) {
            // An integer literal in a float context (3.3): spelled as the float it is.
            val text = sign + CppDeclEmitter.floatText(lit.value.toDouble()) + if (prim == Prim.FLOAT32) "f" else ""
            return CppEx(text, precOfSigned)
        }
        val value = BigInteger.valueOf(lit.value).let { if (negative) it.negate() else it }
        if (prim != null && (prim == Prim.INT32 || prim == Prim.INT64) && value == prim.minValue) {
            return CppEx("std::numeric_limits<${ctx.speller.scalarName(prim)}>::min()", CppPrec.POSTFIX)
        }
        val digits = ctx.integerText(lit, prim)
        if (digits.startsWith("static_cast") || digits.startsWith("std::numeric_limits")) {
            return CppEx(sign + digits, precOfSigned)
        }
        if (role == CppLitRole.SHIFT_COUNT || prim == null || !prim.isInteger) {
            return CppEx(sign + digits, precOfSigned)
        }
        val typed = when (role) {
            CppLitRole.TYPED -> prim != Prim.INT32 && prim != Prim.UINT32
            CppLitRole.DEDUCED -> true
            CppLitRole.WIDTH -> prim.bits > 32
            else -> false
        }
        if (typed) {
            return CppEx("${ctx.speller.scalarName(prim)}{$sign$digits}", CppPrec.POSTFIX)
        }
        val suffix = when (prim) {
            Prim.UINT32 -> "u"
            Prim.UINT64 -> if (lit.value < 0) "u" else ""
            Prim.UINT8, Prim.UINT16 -> if (role == CppLitRole.OPERAND || role == CppLitRole.WIDTH) "u" else ""
            else -> ""
        }
        return CppEx(sign + digits + suffix, precOfSigned)
    }

    private fun floatLiteral(lit: FloatLiteral, negative: Boolean, prim: Prim?): CppEx {
        val raw = ctx.rawNumberText(lit) ?: CppDeclEmitter.floatText(lit.value)
        var digits = raw
        var neg = negative
        if (digits.startsWith("-")) {
            // The parser folded the sign into the literal.
            digits = digits.removePrefix("-")
            neg = !neg
        }
        if (!digits.contains('.') && !digits.contains('e') && !digits.contains('E')) {
            digits += ".0"
        }
        val text = (if (neg) "-" else "") + digits + if (prim == Prim.FLOAT32) "f" else ""
        return CppEx(text, if (neg) CppPrec.UNARY else CppPrec.PRIMARY)
    }

    // ---- names ------------------------------------------------------------------------------

    private fun identifier(id: Identifier): CppEx {
        val sym = model.symbolOf(id) ?: return internal(id, "no symbol was recorded for '${id.value}'")
        return CppEx(nameOfSymbol(sym, id) ?: return internal(id, "'${id.value}' names ${sym.javaClass.simpleName}, which is not a value"), CppPrec.PRIMARY)
    }

    /** The C++ text that names the value [sym] from the body being written. */
    fun nameOfSymbol(sym: Symbol, at: Expr): String? = when (sym) {
        is LocalSymbol -> state.nameOf(sym) ?: ctx.names.escape(sym.name)
        is ParamSymbol -> state.nameOf(sym) ?: ctx.paramName(sym)
        is FieldSymbol -> implicitField(sym)
        is GlobalSymbol -> if (sym.foreign is Foreign.Magic && sym.module.isStdlib) {
            when (sym.name) {
                "true" -> "true"
                "false" -> "false"
                "null" -> "kira::none"
                else -> {
                    ctx.unsupported(at, "the magic value '${sym.name}'")
                    sym.name
                }
            }
        } else if (sym.foreign is Foreign.Extern) {
            externConstant(sym)
        } else {
            ctx.qualified(sym)
        }
        is FnSymbol -> if (sym.foreign is Foreign.Extern) externName(sym) else functionValue(sym, at)
        else -> null
    }

    /**
     * A named function used as a value (`g: Fx<...> = applyTo`). A function with a template
     * parameter is no value C++ can hold; [CppEscapes.fxEscapes] makes every `Fx` parameter of
     * a function used as a value a `kira::Fn`, so this refuses only what another fact made a
     * template, by name, rather than write a conversion no compiler accepts.
     */
    private fun functionValue(fn: FnSymbol, at: Expr): String {
        val placement = ctx.placementOf(fn.module)
        fn.params.firstOrNull { placement.isNonEscapingFx(it) }?.let { p ->
            ctx.unsupported(at, "the function '${fn.name}' as a value: its Fx parameter '${p.name}' is a template parameter, which no kira::Fn holds (pass a lambda that calls ${fn.name})")
        }
        return ctx.qualified(fn)
    }

    /**
     * An `@_extern` constant read by its C++ name, from the global namespace (design 7.2).
     * W2.6's `CppExternsPart.constant` spells this on its branch; at the merge this delegates to it.
     */
    fun externConstant(sym: GlobalSymbol): String {
        val params = (sym.foreign as? Foreign.Extern)?.params.orEmpty()
        val name = params["cpp"] ?: params["symbol"] ?: sym.name
        return if (name.startsWith("::")) name else "::$name"
    }

    /** A field of the implicit receiver: bare in a method, its copy (`c_k`) or `self->k` in a lambda. */
    fun implicitField(f: FieldSymbol): String {
        val frame = state.frame
        frame?.fieldName(f)?.let { return it }
        val name = ctx.names.escape(f.name)
        return if (frame?.receiverAccess == CppBodyState.ThisCapture.SELF) "${selfPointer()}->$name" else name
    }

    /** `this` as a value (design 5.5): `*this` in a struct method, the class's own `kira::Rc` in a class method ([classThis]). */
    private fun thisValue(e: ThisExpr): CppEx {
        val owner = state.frame?.owner ?: ctx.scope ?: return internal(e, "`this` outside a method")
        if ((owner as? ClassSymbol)?.isStruct == true) {
            return CppEx("*this", CppPrec.UNARY)
        }
        return classThis(e, owner)
    }

    /**
     * `this` as a value in a class method: `shared_from_this()` (the class derives
     * `kira::Shared<C>`, D11), or the `self` an escaping lambda captured. The
     * `enable_shared_from_this` base is the chain's root, so a subclass casts down to itself,
     * and a non-`mut` method sees a `const` object whose `shared_from_this()` is a
     * `shared_ptr<const Root>` (a class is a reference, and D29 lets a `mut fx` run through
     * any reference). W2.4's `CppClassesPart.thisValue` spells this on its branch; at the merge
     * this delegates to it.
     */
    fun classThis(e: ThisExpr, owner: TypeSymbol): CppEx {
        val cls = owner as? ClassSymbol
        if (cls == null || cls.kind != ClassKind.CLASS) {
            return unsupported(e, "this as a value in ${owner.name} (only a class has a shared_from_this)")
        }
        val source = if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) "self" else "shared_from_this()"
        val constant = state.frame?.fn?.isMutMethod != true
        return CppEx(ownRc(cls, source, constant), CppPrec.POSTFIX)
    }

    /**
     * The receiver inside a lambda that captured `[self = shared_from_this()]`: `self`, a
     * `shared_ptr` to the chain's root (`const` in a non-`mut` method), cast down to the
     * method's own class when the root is another, so `self->k` reaches a subclass's field.
     */
    fun selfPointer(): String {
        val cls = state.frame?.owner as? ClassSymbol ?: return "self"
        if (rootOf(cls) === cls) {
            return "self"
        }
        val constant = state.frame?.fn?.isMutMethod != true
        return "std::static_pointer_cast<${if (constant) "const " else ""}${ctx.speller.bareClass(cls.selfType)}>(self)"
    }

    /** The root of [cls]'s superclass chain: the class whose `kira::Shared` base `shared_from_this()` returns. */
    fun rootOf(cls: ClassSymbol): ClassSymbol {
        var root: ClassSymbol = cls
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<ClassSymbol, Boolean>())
        while (seen.add(root)) {
            root = root.superclass?.sym as? ClassSymbol ?: break
        }
        return root
    }

    /** [source] (a `shared_ptr` to the chain's root, `const` in a non-`mut` method) as [cls]'s own `kira::Rc`. */
    fun ownRc(cls: ClassSymbol, source: String, constant: Boolean): String {
        val root = rootOf(cls)
        val self = ctx.speller.bareClass(cls.selfType)
        return when {
            root === cls && !constant -> source
            root === cls -> "std::const_pointer_cast<$self>($source)"
            !constant -> "std::static_pointer_cast<$self>($source)"
            else -> "std::static_pointer_cast<$self>(std::const_pointer_cast<${ctx.speller.bareClass(root.selfType)}>($source))"
        }
    }

    /** The receiver [e] as the object of `.` or `->` (a `this` receiver is the pointer `this`, or `self`). */
    private fun receiverText(e: Expr): String {
        if (e is ThisExpr) {
            return if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) selfPointer() else "this"
        }
        return emit(e, CppPrec.POSTFIX)
    }

    /**
     * How members of a value of type [t] are reached (R4): `->` through a class or trait
     * reference, an `Unsafe`, a `Ref` or an opaque handle; `.` on a struct and the builtins;
     * `kira::deref(x).` on a type parameter ([paramReceiver], design 5.5).
     */
    private fun access(receiver: Expr, t: KType): String = accessWith(receiver, null, t)

    /** [access] over [text], the receiver's C++ once it was spilled or bound (null: spelled here). */
    private fun accessWith(receiver: Expr, text: CppEx?, t: KType): String {
        if (receiver is ThisExpr) {
            // A struct's `this` the hoister copied (`const Acc t0_ = *this;`) is a value.
            return if (text == null || text === thisLeaf) "${receiverText(receiver)}->" else "${wrap(text, CppPrec.POSTFIX)}."
        }
        if (t is KType.Param) {
            return (if (text != null) paramReceiverText(wrap(text, CppPrec.ASSIGN)) else paramReceiver(receiver)) + "."
        }
        val object_ = if (text != null) wrap(text, CppPrec.POSTFIX) else receiverText(receiver)
        return object_ + if (isPointerLike(t)) "->" else "."
    }

    /**
     * A receiver whose type is a type parameter: `kira::deref(x)`, so `.m()` reaches a value
     * and a class alike (design 5.5, [S1]). W2.4's `CppGenericsPart.receiver` spells this on its
     * branch; at the merge this delegates to it.
     */
    fun paramReceiver(receiver: Expr): String = paramReceiverText(emit(receiver, CppPrec.ASSIGN))

    /** [paramReceiver] over the receiver's C++ text. */
    fun paramReceiverText(text: String): String = "kira::deref($text)"

    /**
     * A generic call's explicit type arguments, `<std::int32_t>` (`id<std::int32_t>(7)`), or ""
     * for none. W2.4's `CppGenericsPart.typeArguments` spells this on its branch; at the merge
     * this delegates to it.
     */
    fun explicitTypeArgs(at: Expr, types: List<KType>): String =
        if (types.isEmpty()) "" else types.joinToString(", ", "<", ">") { ctx.spell(it, Pos.TEMPLATE_ARG, at) }

    fun isPointerLike(t: KType): Boolean {
        val n = t as? KType.Nominal ?: return false
        return when (val sym = n.sym) {
            is TraitSymbol -> true
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> true
                ClassKind.STRUCT -> false
                ClassKind.MAGIC -> sym.name in POINTER_MAGIC || ctx.speller.isSystemClass(sym)
            }
            else -> false
        }
    }

    // ---- member access (R4) -----------------------------------------------------------------

    private fun memberAccess(e: MemberAccessExpr): CppEx {
        (e.member as? FunctionCallExpr)?.let { if (model.call(it) != null) return call(it) }
        return when (val m = model.member(e)) {
            is MemberRef.EnumEntry -> CppEx(ctx.entry(m.entry), CppPrec.POSTFIX)
            is MemberRef.ModuleMember -> CppEx(nameOfSymbol(m.symbol, e) ?: return internal(e, "a module member that is not a value"), CppPrec.PRIMARY)
            is MemberRef.Field -> field(e.origin, m.field)
            is MemberRef.Method -> unsupported(e, "a method used as a value")
            null -> {
                (model.const(e) as? ConstValue.EnumConst)?.let { return CppEx(ctx.entry(it.entry), CppPrec.POSTFIX) }
                internal(e, "no member was recorded for this access")
            }
        }
    }

    /** `origin.f` for field [f]. */
    fun field(origin: Expr, f: FieldSymbol): CppEx = fieldWith(origin, null, f)

    /** [field] over [text], the origin's C++ once it was ordered as a place's part (null: spelled here). */
    private fun fieldWith(origin: Expr, text: CppEx?, f: FieldSymbol): CppEx {
        val t = typeOf(origin)
        val name = ctx.names.escape(f.name)
        when (CppBindingTable.magicName(t)) {
            "Maybe" -> if (f.name == "value") {
                // D40: `m.value` is `m.unwrap()`.
                val m = if (text != null) wrap(text, CppPrec.ASSIGN) else emit(origin, CppPrec.ASSIGN)
                return CppEx("kira::unwrap($m)", CppPrec.POSTFIX)
            }
            "Result" -> {
                val r = if (text != null) wrap(text, CppPrec.POSTFIX) else receiverText(origin)
                return CppEx("$r.${if (f.name == "error") "unwrapErr" else "unwrap"}()", CppPrec.POSTFIX)
            }
        }
        return CppEx(accessWith(origin, text, t) + name, CppPrec.POSTFIX)
    }

    // ---- places (5.4) -----------------------------------------------------------------------

    /**
     * Whether C++ holds a value of type [t] by reference where Kira reads it (design 5.1: a
     * `const&` parameter): a `Str`, a struct, a container, a `Maybe`, a tuple, an `Fx` value,
     * a type parameter. A scalar, a `Char`, a `Bool`, an enum, a view, an `Unsafe` and a class
     * or trait handle are held by value, and a copy of one is the value itself.
     */
    fun heldByReference(t: KType): Boolean = when (t) {
        KType.Str, is KType.Param, is KType.Fn -> true
        is KType.Nominal -> t.sym !is EnumSymbol && !isPointerLike(t) && !isView(t)
        else -> false
    }

    fun isView(t: KType): Boolean = CppBindingTable.magicName(t).let { it == "View" || it == "MutView" }

    /**
     * Whether [e] names storage that lives somewhere, however it is spelled (40-round3 R-A and
     * R-E, `TypedModel.readPlace`): a variable, another module's global written `ctr.G`, a
     * field or an element of one (TypedModel.places), or a lent result, the element or payload
     * an accessor returns a reference to (`gl.get(0)` is `gl[0]`, `m.unwrap()` is `m.value`:
     * TypedModel.lentPlaces). Only an assignment and a `mut` argument need the assignable
     * table, and the typer and the rules have checked those before the emitter runs.
     */
    fun isPlaceExpr(e: Expr): Boolean = model.readPlace(e) != null

    /**
     * Whether [e] is a lent result (R-A): an accessor call whose result is a place of its
     * receiver's storage (`gl.get(i)`, `m.unwrap()`, `r.unwrapErr()`, `Rules.ACCESSORS`), which
     * is lowered as the other spelling (`gl[i]`, `m.value`) is: its receiver is a step of the
     * path, located and never copied, and the call's own operands are the path's parts.
     */
    fun isLentCall(e: Expr): Boolean = model.lentPlaces[e] != null && hoister.callNode(e)?.let { model.lentPlaces[it] != null } == true

    /**
     * [e] as an operand of a call, an operator or a construction that reads it: a place C++
     * holds by reference is a snapshot [CppHoister.Operand.Place] (D33 reads it before a
     * sibling's effect, so the hoister copies it first), unless a view is formed of it ([lent]:
     * the typer converts it to a `View`, or it is the receiver of a call whose result is
     * second-class), where it is never copied and its path is applied after every sibling
     * (design 30, E1); a call whose result is second-class is a [CppHoister.Operand.Chain]
     * ([valueOperand]); anything else is a [CppHoister.Operand.Value] that [emit] spells. A
     * `Str` constant a view is formed of is no place: C++ makes a `kira::Str` of it, a
     * temporary, which the hoister spills as the view's owner.
     */
    fun operandOf(e: Expr, lent: Boolean = false, emit: () -> CppEx): CppHoister.Operand =
        if (isPlaceExpr(e) && heldByReference(typeOf(e)) && !(lent && isCharPtr(e))) {
            placeOperand(e, emit, if (lent) CppHoister.PlaceMode.LENT else CppHoister.PlaceMode.SNAPSHOT)
        } else {
            valueOperand(e, owner = lent, emit = emit)
        }

    /**
     * [e] as the operand of a by-reference use (50-round4 2.0): [operandOf], carrying a
     * [CppHoister.Use] of [e]'s type when [byRef] says C++ binds a reference to it and no view is
     * formed of it ([lent]). The consumer's [CppHoister.lower] passes it through the copy policy.
     */
    fun usedOperand(e: Expr, byRef: Boolean, lent: Boolean = false, emit: () -> CppEx): CppHoister.Operand {
        val op = operandOf(e, lent, emit)
        if (byRef && !lent) {
            model.typeOrNull(e)?.let { op.use = CppHoister.Use(it) }
        }
        return op
    }

    /**
     * [e], which is no place, as an operand: a [CppHoister.Operand.Chain] when it is a call
     * whose result is second-class (its receiver and arguments are leaves of the enclosing
     * spill, so a view is never copied out of the lambda that holds its owner: design 30, E2
     * and E3), else a [CppHoister.Operand.Value] that [emit] spells. An [owner] is a
     * first-class value a view is formed of (O3: made a prvalue unless it is one).
     */
    private fun valueOperand(e: Expr, mutable: Boolean = false, owner: Boolean = false, emit: () -> CppEx): CppHoister.Operand {
        val second = CppHoister.isSecondClass(model.typeOrNull(e))
        val node = if (!isPlaceExpr(e) && second) hoister.callNode(e) else null
        return if (node != null) hoister.chain(e, node, emit) else CppHoister.Operand.Value(e, mutable, owner && !second, emit)
    }

    /**
     * [e] as a step of a place's path (the container of an element, the struct a field is of):
     * a place C++ holds by reference is the place itself, never a copy, so a write through the
     * path lands where Kira wrote it; anything else (a call's result, a handle) is a value.
     */
    private fun pathOperand(e: Expr, mode: CppHoister.PlaceMode = CppHoister.PlaceMode.PATH): CppHoister.Operand =
        if (isPlaceExpr(e) && heldByReference(typeOf(e))) placeOperand(e, mode = mode) else valueOperand(e) { coerced(e) }

    /**
     * [target] as a place operand: an element is `kira::at(c, i)` (`c[i]` on a Map, `kira::str::at`
     * on a Str) over its container and index as parts, a field of a value place is `.f` over
     * the place, a field through a handle is `->f` over the handle as a value; a variable, `this`
     * and a field of `this` have no parts and [leaf] spells them. The path is applied at the end,
     * over the parts as they were ordered (R19, D33). [mode] says how the hoister orders it: a
     * place that is written (a `mut` argument, an assignment's target, the receiver a `mut fx`
     * writes) is a path, a read the hoister may copy a snapshot, a read a call may lend from lent.
     */
    fun placeOperand(target: Expr, leaf: () -> CppEx = { coerced(target) }, mode: CppHoister.PlaceMode = CppHoister.PlaceMode.PATH): CppHoister.Operand = when {
        // R-A: `gl.get(i)` is the place `gl[i]`: its parts are the call's own operands (the
        // receiver as a step of the path, the index as a value), ordered as an element's are.
        isLentCall(target) -> hoister.lentPlace(target, hoister.callNode(target)!!, mode) { coerced(target) }
        target is ArrayIndexExpr -> {
            val origin = target.originExpr
            val ct = typeOf(origin)
            // P2 (50-round4 2.8, OQ-1): a Str is an immutable value, read before a sibling runs
            // (a by-value operand, copied in order); a container is a step of the path, read
            // when the access runs (Q4).
            val container = if (ct == KType.Str) operandOf(origin) { coerced(origin) } else pathOperand(origin)
            val index = CppHoister.Operand.Value(target.indexExpr) { coerced(target.indexExpr, CppLitRole.PLAIN) }
            val map = CppBindingTable.magicName(ct) == "Map"
            val at = if (ct == KType.Str) "kira::str::at" else "kira::at"
            CppHoister.Operand.Place(target, listOf(container, index), mode) { (c, i) ->
                if (map) {
                    CppEx("${wrap(c, CppPrec.POSTFIX)}[${wrap(i, CppPrec.NONE)}]", CppPrec.POSTFIX)
                } else {
                    CppEx("$at(${wrap(c, CppPrec.ASSIGN)}, ${wrap(i, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
                }
            }
        }
        target is MemberAccessExpr && target.origin !is ThisExpr && model.member(target) is MemberRef.Field -> {
            val f = (model.member(target) as MemberRef.Field).field
            val origin = target.origin
            val through = pathOperand(origin)
            CppHoister.Operand.Place(target, listOf(through), mode) { (o) -> fieldWith(origin, o, f) }
        }
        else -> CppHoister.Operand.Place(target, emptyList(), mode) { leaf() }
    }

    /**
     * `place = value`. C++ runs the right side first, Kira locates the target first (D33): when
     * the value has an effect and locating the target reads anything, or the other way round,
     * the target's parts and the value are ordered inside an IIFE, and the path is written at
     * the end (`kira::at(s, t0_) = t1_`).
     *
     * The value is a use (50-round4 2.0): `operator=(const T&)` binds a reference to it and
     * writes the target while it reads it, so a value inside the target (`t = t.kids[0]`,
     * `t.kids[0] = t`, `xs[0] = xs[1]`) is freed or overwritten as it is read. The assignment
     * is its consumer: never CONFINED, its target its own `mut` operand, so the value is lent
     * only as a prvalue (W1) or a PRIVATE place under another root (W2), and copied otherwise,
     * `T(e)`, before the target is written. A `WrapSome` is no prvalue here
     * ([CppHoister.Use.direct]: `m = m.unwrap().kids[0]` assigns the payload from the element).
     *
     * Kira stores the new value, then drops the old one. Where that drop may run an IMPURE
     * `finally` ([CppCopyPolicy.dropsOnWrite]), the write is `kira::replace(place) = value`: the
     * old value is moved out, the place takes the new one, and the old value dies after the
     * store is whole. A plain `operator=` would run the `finally` partway through the write,
     * which may free the place's storage (`gl[0] = v` while the `finally` replaces `gl`) or
     * rewrite it half-written.
     */
    private fun assignment(target: Expr, value: Expr, node: Expr): CppEx {
        val tt = typeOf(target)
        if (CppHoister.isSecondClass(tt) || CppHoister.holdsSecondClass(tt)) {
            // No variable, field or element holds a view (design 30, 1.2).
            hoister.internalView(node, "this assignment stores a view in a ${tt.display()}", "type")
        }
        val vt = typeOf(value)
        val valueOp = operandOf(value) { coerced(value) }
        if (heldByReference(vt)) {
            valueOp.use = CppHoister.Use(vt, direct = true)
        }
        val ops = listOf(placeOperand(target), valueOp)
        val consumer = CppHoister.Consumer(confined = false, mutOperands = listOf(CppHoister.MutOperand(model.readPlace(target), tt)))
        val replace = policy.dropsOnWrite(tt)
        return hoister.lower(ops, model.typeOrNull(node) ?: KType.Void, consumer = consumer) { (l, r) ->
            val place = if (replace) "kira::replace(${wrap(l, CppPrec.ASSIGN)})" else wrap(l, CppPrec.UNARY)
            CppEx("$place = ${wrap(r, CppPrec.ASSIGN)}", CppPrec.ASSIGN, "=")
        }
    }

    /**
     * `x op= y`, bare (R1: clean on every compiler), except where the operation is checked or
     * widened: a division by what is not a nonzero constant is `x = kira::div(x, y)` (R11), a
     * shift by what is not a constant below the width `x = kira::shl(x, n)` (R12), a UInt16
     * product `x = static_cast<std::uint16_t>(static_cast<unsigned>(x) * y)` (D8). A rewrite
     * names the target twice, so a target whose path holds a call has that part computed once
     * first (`const kira::Size t0_ = nextSize();` then `kira::at(p, t0_)` twice), and D33
     * orders the target's reads before an impure value: the old value is copied, then the
     * value, then the write.
     */
    private fun compound(target: Expr, op: BinaryOp, value: Expr, node: Expr): CppEx {
        val t = typeOf(target)
        val prim = t.prim
        val rewritten = prim != null && prim.isInteger && (
            ((op == BinaryOp.DIV || op == BinaryOp.MOD) && !isConstDivisor(value, prim)) ||
                (op in SHIFT_OPS && (op == BinaryOp.USHR || !isConstCount(value, prim))) ||
                (op == BinaryOp.MUL && prim == Prim.UINT16)
            )
        val sym = COMPOUND[op]
        if (!rewritten && sym == null) {
            return unsupported(node, "the compound assignment ${op.name}")
        }
        val role = when {
            op in SHIFT_OPS && isConstCount(value, prim ?: Prim.INT32) -> CppLitRole.SHIFT_COUNT
            op in SHIFT_OPS -> CppLitRole.PLAIN
            rewritten -> CppLitRole.DEDUCED
            else -> CppLitRole.PLAIN
        }
        val rhs = { if (role == CppLitRole.SHIFT_COUNT) raw(value, role) else coerced(value, role) }
        fun text(l: CppEx, r: CppEx): CppEx = if (rewritten) {
            CppEx("${l.text} = ${applyOp(op, t, l, r, value).text}", CppPrec.ASSIGN, "=")
        } else {
            CppEx("${l.text} $sym ${wrap(r, CppPrec.ASSIGN)}", CppPrec.ASSIGN, sym)
        }
        val targetOp = placeOperand(target)
        val valueOp = CppHoister.Operand.Value(value) { rhs() }
        // A value that writes the target's variable (`x += inc(mut x)`) makes reading it a read of shared state.
        val written = hoister.writtenBy(listOf(targetOp, valueOp))
        val located = hoister.rankOf(targetOp, written)
        val read = hoister.rank(target, written)
        val given = hoister.rank(value, written)
        // A rewrite names the target twice; a call in its path must run once.
        val force = rewritten && located == CppHoister.IMPURE
        // D33: the target is located and read before the value runs, and the value before the write.
        val ordered = (read == CppHoister.IMPURE || given == CppHoister.IMPURE) && read != CppHoister.PURE && given != CppHoister.PURE
        if (!force && !ordered && !hoister.needsSpill(listOf(targetOp, valueOp), written)) {
            return text(hoister.text(targetOp), rhs())
        }
        return state.block {
            val lines = mutableListOf<String>()
            val spelled = ctx.spell(t, Pos.VALUE, target)
            val l = hoister.ordered(targetOp, lines, written)
            // The value's effect may change what the target holds: Kira read it first.
            val old = if (read != CppHoister.PURE && given == CppHoister.IMPURE) {
                val name = state.fresh("t")
                lines += "const $spelled $name = ${l.text};"
                CppEx(name, CppPrec.PRIMARY)
            } else {
                l
            }
            val r = hoister.ordered(valueOp, lines, written)
            lines += if (old === l) "${text(l, r).text};" else "${l.text} = ${applyOp(op, t, old, r, value).text};"
            hoister.iife(KType.Void, lines)
        }
    }

    /**
     * `l op r` for an operator whose operands are already spelled: the R1 narrowing, R11's
     * `kira::div`, R12's checked shifts, the UInt16 product in `unsigned` (D8), Str's `+`.
     * [value] is the right operand's expression, whose constant decides the checked forms.
     */
    private fun applyOp(op: BinaryOp, t: KType, l: CppEx, r: CppEx, value: Expr): CppEx {
        if (t == KType.Str && op == BinaryOp.ADD) {
            return infix(l, "+", r, CppPrec.ADD)
        }
        val prim = t.prim
        if (prim != null && prim.isInteger) {
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && !isConstDivisor(value, prim)) {
                val f = if (op == BinaryOp.DIV) "kira::div" else "kira::mod"
                return CppEx("$f(${wrap(l, CppPrec.ASSIGN)}, ${wrap(r, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
            }
            if (op in SHIFT_OPS) {
                return narrowed(shiftText(l, r, isConstCount(value, prim), op, prim), prim)
            }
            if (op == BinaryOp.MUL && prim == Prim.UINT16) {
                // Both operands promote to int, whose product overflows at 65535 * 65535 (UB): multiply as unsigned.
                return narrowed(infix(CppEx("static_cast<unsigned>(${l.text})", CppPrec.POSTFIX), "*", r, CppPrec.MUL), prim)
            }
        }
        val sym = CPP_OPS[op] ?: return CppEx("/* ${op.name} */", CppPrec.PRIMARY)
        return narrowed(infix(l, sym, r, precOf(op)), prim)
    }

    // ---- operators (R1, R11, R12, R16, R17) --------------------------------------------------

    private fun binary(e: BinaryExpr): CppEx {
        val op = e.operator
        val rc = model.opCall(e)
        if (rc != null) {
            // An @op_* overload: C++ resolves the same operator to the same function. A member
            // overload takes the left operand as its receiver, a free one as its first argument.
            val sym = CPP_OPS[op] ?: return unsupported(e, "the operator overload ${op.name}")
            val member = rc.receiver != null
            // A view is formed of an operand the typer converts, or of a receiver whose overload returns one (E1).
            val left = if (member) CppHoister.isSecondClass(rc.returnType) else model.coercion(e.leftExpr) is Coercion.ToView
            val right = model.coercion(e.rightExpr) is Coercion.ToView
            val lt = typeOf(e.leftExpr)
            val leftOp = operand(e.leftExpr, lent = left)
            val rightOp = operand(e.rightExpr, lent = right)
            // Uses (50-round4 L3, L6): a member overload's left operand is its `const S&` this, a free one's its first argument.
            if (!left && (if (member) heldByReference(lt) else policy.argByRef(rc, 0, e.leftExpr))) leftOp.use = CppHoister.Use(lt)
            if (!right && policy.argByRef(rc, if (member) 0 else 1, e.rightExpr)) rightOp.use = CppHoister.Use(typeOf(e.rightExpr))
            return hoister.lower(
                listOf(leftOp, rightOp), typeOf(e), node = e, consumer = policy.callConsumer(rc, if (member) lt else null),
            ) { (l, r) -> CppEx("${wrap(l, precOf(op))} $sym ${wrap(r, precOf(op) + 1)}", precOf(op), sym) }
        }
        return when (op) {
            BinaryOp.AND, BinaryOp.OR -> logical(e)
            in COMPARISONS -> comparison(e)
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> arithmetic(e)
            BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR -> bitwise(e)
            in SHIFT_OPS -> shift(e)
            else -> unsupported(e, "the operator ${op.name}")
        }
    }

    /** An operand of an operator: its literal role follows the other side (R2); [lent] as [operandOf] says. */
    private fun operand(e: Expr, other: Expr? = null, role: CppLitRole? = null, lent: Boolean = false): CppHoister.Operand {
        val r = role ?: if (other != null && bareLiteral(e) != null && isLiteralOnly(other)) CppLitRole.TYPED else CppLitRole.OPERAND
        return operandOf(e, lent) { coerced(e, r) }
    }

    /**
     * The operands of a C++ runtime operator or function ([ops], of [exprs]) as uses where C++
     * binds a reference ([heldByReference]: `Str` `+` and `==`, a container's `==`,
     * `kira::cat`'s holes), and their consumer (50-round4 L8): CONFINED when no operand type
     * holds a user class, trait or type parameter.
     */
    private fun runtimeUses(ops: List<CppHoister.Operand>, exprs: List<Expr?>): CppHoister.Consumer {
        ops.forEachIndexed { i, op ->
            val t = exprs[i]?.let { model.typeOrNull(it) }
            if (t != null && heldByReference(t) && op.use == null) {
                op.use = CppHoister.Use(t)
            }
        }
        return policy.runtimeConsumer(exprs.filterNotNull().map { model.typeOrNull(it) })
    }

    /** [child] as an operand of the C++ operator [parentOp] at [need], with the `-Wparentheses` rules. */
    private fun side(child: CppEx, need: Int, parentOp: String): String {
        val parens = child.prec < need || conflicts(parentOp, child.op)
        return if (parens) "(${child.text})" else child.text
    }

    private fun conflicts(parent: String, child: String?): Boolean {
        child ?: return false
        return when {
            parent in BITWISE_SYMS && child != parent && child in BINARY_SYMS -> true
            parent in COMPARISON_SYMS && child in COMPARISON_SYMS -> true
            parent in SHIFT_SYMS && child in ARITH_SYMS -> true
            parent == "||" && child == "&&" -> true
            else -> false
        }
    }

    private fun infix(l: CppEx, sym: String, r: CppEx, prec: Int): CppEx =
        CppEx("${side(l, prec, sym)} $sym ${side(r, prec + 1, sym)}", prec, sym)

    private fun logical(e: BinaryExpr): CppEx {
        // Never spilled across: the right side of && and || runs only on the left's say.
        val sym = if (e.operator == BinaryOp.AND) "&&" else "||"
        val prec = if (e.operator == BinaryOp.AND) CppPrec.AND else CppPrec.OR
        return infix(coerced(e.leftExpr), sym, coerced(e.rightExpr), prec)
    }

    private fun comparison(e: BinaryExpr): CppEx {
        val op = e.operator
        val lt = typeOf(e.leftExpr)
        val sym = CPP_OPS[op]!!
        val prec = precOf(op)
        val ops = listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr))
        val consumer = runtimeUses(ops, listOf(e.leftExpr, e.rightExpr))
        return hoister.lower(ops, KType.BOOL, consumer = consumer) { (l, r) ->
            when {
                lt == KType.CHAR && op in ORDERING -> {
                    // R17: char is signed on x86 and unsigned on ARM; compare the code units.
                    infix(CppEx("kira::ord(${l.text})", CppPrec.POSTFIX), sym, CppEx("kira::ord(${r.text})", CppPrec.POSTFIX), prec)
                }
                lt == KType.Str && isCharPtr(e.leftExpr) && isCharPtr(e.rightExpr) -> {
                    // Two `const char*` (literals, Str constants) would compare pointers; a
                    // string_view compares their text, in a constant expression too.
                    infix(CppEx("std::string_view(${l.text})", CppPrec.POSTFIX), sym, r, prec)
                }
                else -> infix(l, sym, r, prec)
            }
        }
    }

    private fun arithmetic(e: BinaryExpr): CppEx {
        val op = e.operator
        val t = typeOf(e)
        if (t == KType.Str) {
            return concat(e)
        }
        val prim = t.prim
        if (prim != null && prim.isInteger && (op == BinaryOp.DIV || op == BinaryOp.MOD) && !isConstDivisor(e.rightExpr, prim)) {
            // R11: the spec's run-time error on /0 and INT_MIN / -1 (D10), through a deduced template.
            return hoister.lower(
                listOf(operand(e.leftExpr, role = CppLitRole.DEDUCED), operand(e.rightExpr, role = CppLitRole.DEDUCED)), t,
            ) { (l, r) -> applyOp(op, t, l, r, e.rightExpr) }
        }
        return hoister.lower(listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr)), t) { (l, r) ->
            applyOp(op, t, l, r, e.rightExpr)
        }
    }

    /** R14: `a + b` on Str; a `const char*` on the left becomes a `kira::Str` first. */
    private fun concat(e: BinaryExpr): CppEx {
        val charPtr = isCharPtr(e.leftExpr)
        val ops = listOf(operand(e.leftExpr), operand(e.rightExpr))
        val consumer = runtimeUses(ops, listOf(e.leftExpr, e.rightExpr))
        return hoister.lower(ops, KType.Str, consumer = consumer) { (l, r) ->
            val left = if (charPtr) CppEx("kira::Str(${l.text})", CppPrec.POSTFIX) else l
            infix(left, "+", r, CppPrec.ADD)
        }
    }

    private fun bitwise(e: BinaryExpr): CppEx {
        val t = typeOf(e)
        return hoister.lower(listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr)), t) { (l, r) ->
            applyOp(e.operator, t, l, r, e.rightExpr)
        }
    }

    private fun shift(e: BinaryExpr): CppEx {
        val t = typeOf(e)
        val prim = t.prim ?: return internal(e, "a shift of a non-integer")
        val constant = isConstCount(e.rightExpr, prim)
        if (constant) {
            // C++17 sequences the left operand of a shift before its right: nothing to spill. A
            // literal on the left carries the width (R2): `std::int64_t{1} << 40`, never `1 << 40`.
            return applyOp(e.operator, t, coerced(e.leftExpr, CppLitRole.WIDTH), raw(e.rightExpr, CppLitRole.SHIFT_COUNT), e.rightExpr)
        }
        val count = operandOf(e.rightExpr) { coerced(e.rightExpr) }
        return hoister.lower(listOf(operand(e.leftExpr, role = CppLitRole.DEDUCED), count), t) { (l, n) ->
            applyOp(e.operator, t, l, n, e.rightExpr)
        }
    }

    /**
     * `v << n` (R12): bare when the count is a constant below the width ([constant]), else
     * `kira::shl(v, n)`. `>>>` shifts the unsigned counterpart of a signed [prim] and converts back.
     */
    private fun shiftText(value: CppEx, n: CppEx, constant: Boolean, op: BinaryOp, prim: Prim): CppEx {
        val logical = op == BinaryOp.USHR && prim.signed
        val v = if (logical) CppEx("static_cast<${unsignedOf(prim)}>(${value.text})", CppPrec.POSTFIX) else value
        val shifted = if (constant) {
            infix(v, if (op == BinaryOp.SHL) "<<" else ">>", n, CppPrec.SHIFT)
        } else {
            CppEx("${if (op == BinaryOp.SHL) "kira::shl" else "kira::shr"}(${wrap(v, CppPrec.ASSIGN)}, ${wrap(n, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
        }
        return if (logical) CppEx("static_cast<${ctx.speller.scalarName(prim)}>(${shifted.text})", CppPrec.POSTFIX) else shifted
    }

    private fun unsignedOf(p: Prim): String = when (p) {
        Prim.INT8 -> "std::uint8_t"
        Prim.INT16 -> "std::uint16_t"
        Prim.INT32 -> "std::uint32_t"
        else -> "std::uint64_t"
    }

    /** R1: arithmetic on Int8, UInt8, Int16 and UInt16 promotes to int in C++; it is narrowed back. */
    private fun narrowed(ex: CppEx, prim: Prim?): CppEx {
        if (prim == null || !prim.promotesInCpp) {
            return ex
        }
        return CppEx("static_cast<${ctx.speller.scalarName(prim)}>(${ex.text})", CppPrec.POSTFIX)
    }

    /** R11: a divisor that is a nonzero constant (and not -1 for a signed type) needs no check. */
    fun isConstDivisor(e: Expr, prim: Prim): Boolean {
        val v = (model.const(e) as? ConstValue.IntConst)?.value ?: return false
        if (v.signum() == 0) {
            return false
        }
        return !(prim.signed && v == BigInteger.ONE.negate())
    }

    /** R12: a shift count that is a constant in `0 until width` (Size counts as 32 bits, the Pico's). */
    fun isConstCount(e: Expr, prim: Prim): Boolean {
        val v = (model.const(e) as? ConstValue.IntConst)?.value ?: return false
        return v.signum() >= 0 && v < BigInteger.valueOf(prim.portableBits.toLong())
    }

    /**
     * `!x`, `-x`, `~x`. A literal operand carries the operation's width ([CppLitRole.WIDTH]):
     * `~0` on a UInt64 is `~std::uint64_t{0}`, on a UInt32 `~0u`, never the `int` `~0`, which
     * is `-1` (a narrowing error in a brace-init, a sign conversion in `v & ~0xFF`). A minus
     * before an operand that starts with one is parenthesized: `--y` is a pre-decrement.
     */
    private fun unary(e: UnaryExpr): CppEx {
        val t = typeOf(e)
        val prim = t.prim
        val operand = coerced(e.operand, CppLitRole.WIDTH)
        return when (e.operator) {
            UnaryOp.NOT -> CppEx("!${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY)
            UnaryOp.POS -> operand
            UnaryOp.NEG -> when {
                prim != null && prim.promotesInCpp -> narrowed(CppEx("-${minusOperand(operand)}", CppPrec.UNARY), prim)
                // MSVC C4146 refuses a unary minus on an unsigned operand; 0u - x is the same wrap (D8).
                prim != null && prim.isInteger && !prim.signed -> CppEx("0u - ${wrap(operand, CppPrec.ADD + 1)}", CppPrec.ADD, "-")
                else -> CppEx("-${minusOperand(operand)}", CppPrec.UNARY)
            }
            UnaryOp.BIT_NOT -> narrowed(CppEx("~${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY), prim)
        }
    }

    /** [operand] after a unary minus: parenthesized when it starts with a minus itself (`-(-y)`, never `--y`). */
    private fun minusOperand(operand: CppEx): String {
        val text = wrap(operand, CppPrec.UNARY)
        return if (text.startsWith("-")) "($text)" else text
    }

    /** R13: `as`, by the conversion the typer recorded. */
    private fun cast(e: TypeCastExpr): CppEx {
        val kind = model.conversion(e) ?: return internal(e, "no conversion was recorded for this `as`")
        val to = typeOf(e)
        val target = ctx.spell(to, Pos.VALUE, e)
        val v = emit(e.value, CppPrec.ASSIGN)
        return when (kind) {
            ConversionKind.INT_WRAP, ConversionKind.INT_TO_FLOAT, ConversionKind.FLOAT_RESIZE, ConversionKind.ENUM_TO_BASE,
            ConversionKind.INT_TO_CHAR -> CppEx("static_cast<$target>($v)", CppPrec.POSTFIX)
            ConversionKind.FLOAT_TO_INT_SAT -> CppEx("kira::as<$target>($v)", CppPrec.POSTFIX)
            ConversionKind.CHAR_TO_INT -> CppEx("static_cast<$target>(static_cast<unsigned char>($v))", CppPrec.POSTFIX)
            ConversionKind.TO_STR -> CppEx("kira::text($v)", CppPrec.POSTFIX)
        }
    }

    // ---- Str (5.8, R14, R15) ------------------------------------------------------------------

    /**
     * Whether [e] is a `const char*` in C++: a string literal, or a Str constant (design 5.2
     * spells every one `inline constexpr const char*`).
     */
    fun isCharPtr(e: Expr): Boolean {
        if (model.typeOrNull(e) != KType.Str) {
            return false
        }
        if (e is StringLiteral) {
            return true
        }
        val sym = when (e) {
            is Identifier -> if (e is IntrinsicExpr) null else model.symbolOf(e)
            is MemberAccessExpr -> (model.member(e) as? MemberRef.ModuleMember)?.symbol
            else -> null
        } as? GlobalSymbol ?: return false
        return sym.isConstant && !sym.isMut && sym.foreign == null
    }

    /** `"a${x}b"` is `kira::cat("a", x, "b")`: one allocation, locale-free. */
    private fun interpolation(e: InterpolatedStringLiteral): CppEx {
        if (typeOf(e) != KType.Str) {
            return unsupported(e, "an interpolation into ${typeOf(e)} outside a StrBuf's set or add")
        }
        val operands = e.parts.map { part ->
            when (part) {
                is InterpolationPart.Text -> CppHoister.Operand.Value(null) { CppEx(CppDeclEmitter.cppString(part.text), CppPrec.PRIMARY) }
                is InterpolationPart.Hole -> operandOf(part.expr) { coerced(part.expr) }
            }
        }
        val consumer = runtimeUses(operands, e.parts.map { (it as? InterpolationPart.Hole)?.expr })
        return hoister.lower(operands, KType.Str, consumer = consumer) { texts ->
            CppEx("kira::cat(${texts.joinToString(", ") { wrap(it, CppPrec.ASSIGN) }})", CppPrec.POSTFIX)
        }
    }

    /**
     * R15: `s[i]` is `kira::str::at(s, i)` on a Str and `kira::at(a, i)` on an Arr, List or
     * view: checked. Read as a value: the element's path over its ordered parts ([placeOperand]),
     * so `grid[nextSize()][nextSize()]` orders its two calls and copies no row.
     */
    private fun index(e: ArrayIndexExpr): CppEx {
        if (CppBindingTable.magicName(typeOf(e.originExpr)) == "Map") {
            return unsupported(e, "reading a Map with m[k] (read it with m.get(k))")
        }
        return hoister.lower(listOf(placeOperand(e)), typeOf(e)) { (element) -> element }
    }

    // ---- literals of containers, construction (R9) ---------------------------------------------

    private fun arrayLiteral(e: ArrayLiteral): CppEx {
        val t = typeOf(e)
        if (CppHoister.holdsSecondClass(t)) {
            hoister.internalView(e, "this array literal holds views, a ${t.display()}", "type")
        }
        val elements = e.value.map { operandOf(it) { coerced(it) } }
        return hoister.lower(elements, t) { texts ->
            CppEx("${ctx.spell(t, Pos.VALUE, e)}{${texts.joinToString(", ") { wrap(it, CppPrec.ASSIGN) }}}", CppPrec.POSTFIX)
        }
    }

    /** The elements of an array literal as a braced list (a local's copy-list-initializer). */
    fun bracedElements(e: ArrayLiteral): String = "{${e.value.joinToString(", ") { emit(it, CppPrec.ASSIGN) }}}"

    /**
     * R9: a struct is an aggregate with designated initializers in declaration order; a class
     * (and a system module's class) is `std::make_shared<C>(...)` with every field in
     * constructor order, defaulted trailing ones left to the constructor's default arguments;
     * `Ref<T> { value = v }` is `std::make_shared<kira::Box<T>>(v)`; an empty container is
     * `T{}`.
     */
    private fun construction(e: ObjectInitExpr): CppEx {
        val ri = model.init(e) ?: return internal(e, "no construction was recorded")
        val cls = ri.cls ?: return internal(e, "a construction without a class")
        val t = ri.type
        if ((cls.kind == ClassKind.MAGIC && cls.name == "Ref") || cls.kind == ClassKind.CLASS || ctx.speller.isSystemClass(cls)) {
            return classConstruction(e, ri, cls)
        }
        refuseViewFields(e, ri)
        val given = ri.sourceOrder.map { ri.fields[it] as FieldInit.Given }
        val ops = given.map { g -> operandOf(g.expr) { coerced(g.expr) } }
        val byField = IdentityHashMap<FieldSymbol, Int>()
        given.forEachIndexed { i, g -> byField[g.field] = i }
        val typeText = if (model.typeOf(e.typeName) != null) ctx.spell(e.typeName, Pos.VALUE) else ctx.spell(t, Pos.VALUE, e)
        if (cls.kind == ClassKind.MAGIC) {
            return magicConstruction(e, cls, t, typeText, ri.fields, byField, ops)
        }
        return hoister.lower(ops, t) { texts ->
            val inits = ri.fields.filterIsInstance<FieldInit.Given>().map { f ->
                ".${ctx.names.escape(f.field.name)} = ${wrap(texts[byField[f.field]!!], CppPrec.ASSIGN)}"
            }
            CppEx("$typeText{${inits.joinToString(", ")}}", CppPrec.POSTFIX)
        }
    }

    /**
     * A class construction (R9): `std::make_shared<C>(...)`, one argument per field of the
     * chain in constructor order ([ResolvedInit.fields], inherited ones first). The trailing
     * fields left to their defaults are left to the constructor's C++ default arguments; a
     * skipped earlier one is filled in with its default, or `T{}` for a field without one
     * (D38). `make_shared` forwards through a deduced parameter, so a narrow literal is
     * `T{lit}` (R2). `Ref<T> { value = v }` is `std::make_shared<kira::Box<T>>(v)` (D46), and a
     * system module's class is its runtime's. Impure arguments are spilled as written (D33).
     * W2.4's `CppClassesPart.construct` spells this on its branch; at the merge this delegates to it.
     */
    fun classConstruction(e: ObjectInitExpr, ri: net.exoad.kira.compiler.analysis.types.ResolvedInit, cls: ClassSymbol): CppEx {
        refuseViewFields(e, ri)
        val t = ri.type
        val target = if (cls.kind == ClassKind.MAGIC && cls.name == "Ref") {
            "kira::Box<${ctx.spell((t as? KType.Nominal)?.typeArgs()?.firstOrNull() ?: KType.Error, Pos.TEMPLATE_ARG, e)}>"
        } else {
            ctx.speller.bareClass(t)
        }
        val fields = ri.fields
        var end = fields.size
        while (end > 0 && fields[end - 1] is FieldInit.Default && fields[end - 1].field.default != null) {
            end -= 1
        }
        val position = IdentityHashMap<FieldSymbol, Int>()
        val ops = mutableListOf<CppHoister.Operand>()
        ri.sourceOrder.forEach { i ->
            val g = fields[i] as? FieldInit.Given ?: return@forEach
            if (i < end) {
                position[g.field] = ops.size
                ops += operandOf(g.expr) { coerced(g.expr, CppLitRole.TYPED) }
            }
        }
        return hoister.lower(ops, t) { texts ->
            val args = fields.take(end).map { f ->
                when (f) {
                    is FieldInit.Given -> wrap(texts[position[f.field]!!], CppPrec.ASSIGN)
                    is FieldInit.Default -> f.field.default?.let { emit(it, CppPrec.ASSIGN, CppLitRole.TYPED) }
                        ?: "${ctx.spell(f.field.type.substitute(ri.substitution), Pos.VALUE, e)}{}"
                }
            }
            CppEx("std::make_shared<$target>(${args.joinToString(", ")})", CppPrec.POSTFIX)
        }
    }

    /** `cpp.internal` at the construction [e] of a type that holds a view (design 30, 1.2: no field, box or container holds one). */
    private fun refuseViewFields(e: ObjectInitExpr, ri: net.exoad.kira.compiler.analysis.types.ResolvedInit) {
        val held = ri.fields.firstOrNull { f -> f.field.type.substitute(ri.substitution).let { CppHoister.isSecondClass(it) || CppHoister.holdsSecondClass(it) } }
        if (held != null || CppHoister.holdsSecondClass(ri.type)) {
            hoister.internalView(e, "this construction of ${ri.type.display()} holds a view${held?.let { " in '${it.field.name}'" } ?: ""}", "type")
        }
    }

    private fun magicConstruction(
        e: ObjectInitExpr,
        cls: ClassSymbol,
        t: KType,
        typeText: String,
        fields: List<FieldInit>,
        byField: IdentityHashMap<FieldSymbol, Int>,
        ops: List<CppHoister.Operand>,
    ): CppEx {
        val givenFields = fields.filterIsInstance<FieldInit.Given>()
        if (givenFields.isEmpty()) {
            return CppEx("$typeText{}", CppPrec.POSTFIX)
        }
        val tuple = cls.name.startsWith("Tuple")
        return when {
            tuple -> hoister.lower(ops, t) { texts ->
                val inits = givenFields.map { f -> ".${ctx.names.escape(f.field.name)} = ${wrap(texts[byField[f.field]!!], CppPrec.ASSIGN)}" }
                CppEx("$typeText{${inits.joinToString(", ")}}", CppPrec.POSTFIX)
            }
            givenFields.size == 1 && givenFields[0].field.name == "values" && cls.name in setOf("List", "Map", "Set") -> {
                // A literal's elements are a braced list (`kira::Map<K, V>{...}` takes an
                // initializer_list of entries, `kira::Set<T>{...}` one of values, and C++
                // sequences a braced list left to right); a List also copies from a List
                // value. kira::Map and kira::Set have no constructor from a List, so a Map or
                // Set built from one is refused by name.
                val values = givenFields[0].expr
                when {
                    values is ArrayLiteral -> CppEx("$typeText${bracedElements(values)}", CppPrec.POSTFIX)
                    cls.name == "List" -> CppEx("$typeText(${emit(values, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
                    else -> unsupported(e, "constructing a ${cls.name} from a List value (write its elements as a literal)")
                }
            }
            else -> unsupported(e, "constructing ${cls.name} with fields")
        }
    }

    // ---- calls (R5, R6, R8, R19-R21) ------------------------------------------------------------

    fun call(e: FunctionCallExpr): CppEx {
        val rc = model.call(e) ?: return internal(e, "no call was resolved")
        return when (rc.kind) {
            CallKind.PRINT -> printCall(e, rc)
            CallKind.MAGIC -> magicCall(e, rc)
            CallKind.FREE -> freeCall(e, rc)
            CallKind.METHOD, CallKind.VIRTUAL, CallKind.TRAIT -> methodCall(e, rc)
            CallKind.EXTERN -> externCall(e, rc)
            CallKind.FN_VALUE -> fnValueCall(e, rc)
            CallKind.OP_OVERLOAD, CallKind.CTOR -> unsupported(e, "a ${rc.kind} call")
        }
    }

    /** One slot of a call's argument list, in parameter order. */
    private sealed interface Slot {
        /** A written argument: [operand] is its index among the call's operands. */
        data class Given(val operand: Int, val param: Int, val byRef: Boolean) : Slot

        /** A skipped middle default, filled in at the call (R6). */
        data class Filled(val text: String) : Slot
    }

    /**
     * The operands of [rc]'s written arguments in source order (D33), and the slots in
     * parameter order. A `mut` argument is a place: located in order, never copied (R19); a trailing
     * default is left to C++'s default argument (R6), a temporary of the call's full expression.
     */
    private fun arguments(rc: ResolvedCall): Pair<List<CppHoister.Operand>, List<Slot>> {
        val operands = mutableListOf<CppHoister.Operand>()
        val indexOf = HashMap<Int, Int>()
        rc.sourceOrder.forEach { i ->
            val a = rc.args[i] as? ArgBinding.Given ?: return@forEach
            indexOf[i] = operands.size
            operands += argumentOperand(rc, i, a)
        }
        val slots = mutableListOf<Slot>()
        rc.args.forEachIndexed { i, a ->
            when (a) {
                is ArgBinding.Given -> {
                    val op = indexOf[i] ?: run {
                        // A written argument missing from sourceOrder: keep it, in parameter order.
                        indexOf[i] = operands.size
                        operands += argumentOperand(rc, i, a)
                        indexOf[i]!!
                    }
                    slots += Slot.Given(op, i, a.byRef)
                }
                is ArgBinding.Default -> if (!a.trailing) {
                    val d = a.param.default
                    slots += Slot.Filled(if (d != null) emit(d, CppPrec.ASSIGN) else "{}")
                }
            }
        }
        return operands to slots
    }

    /**
     * A written argument for parameter [i] of [rc]: a `mut` one is a bound place (its path
     * applied at the call, never copied: R19, W7; C++ may bind the reference before a sibling
     * runs, so a sibling that may move its container is ordered first), any other a
     * [usedOperand], lent where the typer converts it to a `View` (design 30, E1: the view is of
     * the place, never of a copy), and else passed by the copy policy wherever the parameter
     * binds a reference ([CppCopyPolicy.argByRef]).
     */
    private fun argumentOperand(rc: ResolvedCall, i: Int, a: ArgBinding.Given): CppHoister.Operand {
        if (a.byRef) {
            return placeOperand(a.expr, mode = CppHoister.PlaceMode.BOUND)
        }
        val lent = model.coercion(a.expr) is Coercion.ToView
        return usedOperand(a.expr, policy.argByRef(rc, i, a.expr), lent) { coerced(a.expr) }
    }

    /** The text of a struct's `this` as a receiver the hoister may copy: [accessWith] knows this instance as the uncopied one. */
    private val thisLeaf = CppEx("*this", CppPrec.UNARY)

    /**
     * The receiver of a call as an ordered operand (D33: Kira evaluates it before the
     * arguments; an IIFE that spills the arguments would otherwise run it after them), or null
     * for a class's `this`, whose identity is fixed. A place C++ holds by reference (a struct,
     * a `Str`, a container, a type parameter: [heldByReference]; a struct's own `this` too) is
     * a place operand: the one a `mut fx` writes stays where it lives (the write lands there),
     * one a view is formed of (a `MutView` lent of it, or the receiver of any call whose result
     * is second-class: design 30, E1) is never copied and is read after every sibling, any
     * other is a snapshot the hoister copies before
     * a sibling's effect (D33), so `gm.get(k())` and `gl.get(k())` read the same way, whichever
     * binding spells them, and `this.plus(bump())` reads the same as `ga.plus(bumpA())`. A
     * handle (a class, a trait, a `Ref`), a view, a scalar, and any receiver that is no place
     * (a call's result) is a value: what the call holds is a copy of it, a plain `T t0_` when
     * a member-style binding calls a non-const method on it. With no [rc] (a StrBuf's pieces)
     * nothing is known of lending, and only a written receiver is safe from a copy.
     */
    private fun receiverOperand(receiver: Expr, rc: ResolvedCall?, fn: FnSymbol?, memberStyle: Boolean, lending: Boolean, emit: () -> CppEx): CppHoister.Operand? {
        val writes = fn?.isMutMethod == true || lending
        if (receiver is ThisExpr) {
            val t = typeOf(receiver)
            if (rc == null || rc.kind == CallKind.EXTERN || !heldByReference(t)) {
                return null
            }
            val mode = receiverMode(rc, fn, lending, t)
            return CppHoister.Operand.Place(receiver, emptyList(), mode) { thisLeaf }.also { if (mode == CppHoister.PlaceMode.SNAPSHOT) it.use = CppHoister.Use(t) }
        }
        val t = typeOf(receiver)
        val isPlace = isPlaceExpr(receiver)
        val lent = lending || (rc != null && fn?.isMutMethod != true && CppHoister.isSecondClass(rc.returnType))
        if (isPlace && heldByReference(t) && !(lent && isCharPtr(receiver))) {
            // A value receiver read as a `const S&` (a struct's `this`, a binding's `{self}`) is a use (50-round4 L6).
            val mode = receiverMode(rc, fn, lending, t)
            return placeOperand(receiver, emit, mode).also { if (mode == CppHoister.PlaceMode.SNAPSHOT) it.use = CppHoister.Use(t) }
        }
        // A fresh receiver a view is formed of (`makeList().from(k)`) is the view's owner, spilled into a typed temporary of the lambda that uses the view (E2).
        val op = valueOperand(receiver, mutable = writes || (memberStyle && !isPlace), owner = lent) { emit() }
        if (rc != null && !lent) {
            when {
                // `h->m(...)` runs with a raw `this`: the caller holds the object (W5), a copied handle when h is not kept still.
                policy.isHandle(t) && rc.kind in HANDLE_CALLS -> op.use = CppHoister.Use(t, handle = true)
                !writes && heldByReference(t) -> op.use = CppHoister.Use(t)
            }
        }
        return op
    }

    /** How a receiver place of type [t] is ordered ([receiverOperand]): lent, written through a bound reference, or a snapshot. */
    private fun receiverMode(rc: ResolvedCall?, fn: FnSymbol?, lending: Boolean, t: KType): CppHoister.PlaceMode = when {
        lending -> CppHoister.PlaceMode.LENT
        fn?.isMutMethod == true -> CppHoister.PlaceMode.BOUND
        rc == null -> CppHoister.PlaceMode.PATH
        CppHoister.isSecondClass(rc.returnType) -> CppHoister.PlaceMode.LENT
        else -> CppHoister.PlaceMode.SNAPSHOT
    }

    /**
     * The implicit `this` of a struct method called from a sibling (`plus(bump())`) as the
     * receiver operand [receiverOperand] makes of an explicit one: a place with no expression
     * of its own, read as shared state (a sibling `mut fx` changes it), copied before the
     * effect unless the callee writes or lends from it. Null in a class (a handle).
     */
    private fun implicitThisOperand(rc: ResolvedCall, fn: FnSymbol): CppHoister.Operand? {
        val owner = state.frame?.owner as? ClassSymbol ?: return null
        if (!owner.isStruct) {
            return null
        }
        val t = owner.selfType
        if (!heldByReference(t)) {
            return null
        }
        val place = Place.This(owner)
        // R-PURE: the receiver of a method that is no `mut fx` is PRIVATE (I); a `mut fx`'s is the caller's `S&`.
        val rank = if (policy.isPrivate(place)) CppHoister.PURE else CppHoister.READS
        val mode = receiverMode(rc, fn, false, t)
        return CppHoister.Operand.Place(null, emptyList(), mode, type = t, rank = rank) { thisLeaf }.also {
            if (mode == CppHoister.PlaceMode.SNAPSHOT) it.use = CppHoister.Use(t, place = place)
        }
    }

    private fun argList(slots: List<Slot>, texts: List<CppEx>): String = slots.joinToString(", ") { s ->
        when (s) {
            is Slot.Given -> wrap(texts[s.operand], CppPrec.ASSIGN)
            is Slot.Filled -> s.text
        }
    }

    private fun freeCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "a free call without its function")
        val (ops, slots) = arguments(rc)
        val name = ctx.qualified(fn) + if (fn.typeParams.isNotEmpty()) explicitTypeArgs(e, rc.typeArgs) else ""
        return hoister.lower(ops, rc.returnType, node = e, consumer = policy.callConsumer(rc, null)) { texts -> CppEx("$name(${argList(slots, texts)})", CppPrec.POSTFIX) }
    }

    private fun methodCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "a method call without its method")
        val (argOps, argSlots) = arguments(rc)
        val name = ctx.names.escape(fn.name) + if (fn.typeParams.isNotEmpty()) explicitTypeArgs(e, rc.typeArgs) else ""
        val receiver = rc.receiver
        // The receiver is an operand before the arguments (D33): spilled, it runs first.
        val recvOp = when {
            receiver != null -> receiverOperand(receiver, rc, fn, memberStyle = false, lending = false) { coerced(receiver) }
            rc.implicitThis -> implicitThisOperand(rc, fn)
            else -> null
        }
        val (ops, slots) = withReceiver(recvOp, argOps, argSlots)
        val receiverType = receiver?.let { typeOf(it) } ?: (state.frame?.owner as? ClassSymbol)?.takeIf { rc.implicitThis }?.selfType
        return hoister.lower(ops, rc.returnType, node = e, consumer = policy.callConsumer(rc, receiverType)) { texts ->
            val callee = when {
                receiver != null -> accessWith(receiver, if (recvOp != null) texts[0] else null, typeOf(receiver)) + name
                recvOp != null && texts[0] !== thisLeaf -> "${wrap(texts[0], CppPrec.POSTFIX)}.$name"
                state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF -> "${selfPointer()}->$name"
                else -> name
            }
            CppEx("$callee(${argList(slots, texts)})", CppPrec.POSTFIX)
        }
    }

    /** [receiver] first among the operands, when it is one, with the argument slots shifted past it. */
    private fun withReceiver(receiver: CppHoister.Operand?, args: List<CppHoister.Operand>, slots: List<Slot>): Pair<List<CppHoister.Operand>, List<Slot>> {
        receiver ?: return args to slots
        return (listOf(receiver) + args) to slots.map { if (it is Slot.Given) it.copy(operand = it.operand + 1) else it }
    }

    /**
     * A call through an `Fx` value: a local, a parameter (a template's `F_p&&` too), or a field.
     * C++ runs the closure where it is stored, so the callee is a use (50-round4 L6): an `Fx`
     * value call is never CONFINED, so a callee that is no PRIVATE place is copied,
     * `(kira::Fn<...>(f))(...)`. A callee spelled as a functional cast to its `kira::Fn` type
     * (that copy, or a conversion) is parenthesized wherever the call stands: as a statement,
     * `kira::Fn<void()>(f)();` is a declaration of a function `f` (and `kira::Fn<void(int)>(f)(3);`
     * one of a variable `f`), so the call would never run.
     */
    private fun fnValueCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val (argOps, argSlots) = arguments(rc)
        // The callee is evaluated first (D33); as an operand it is spilled with the arguments.
        val calleeOp = usedOperand(e.name, byRef = true) { coerced(e.name) }
        val (ops, slots) = withReceiver(calleeOp, argOps, argSlots)
        return hoister.lower(ops, rc.returnType, node = e, consumer = policy.callConsumer(rc, null)) { texts ->
            val c = texts[0]
            val callee = if (calleeOp.copied || c.text.startsWith("kira::Fn<")) "(${c.text})" else wrap(c, CppPrec.POSTFIX)
            CppEx("$callee(${argList(slots, texts)})", CppPrec.POSTFIX)
        }
    }

    /**
     * An `@_extern` function or method (design 7.2). The receiver and the arguments are
     * spelled here (spilled as D33 needs); [externCallText] makes the call of them.
     */
    private fun externCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "an extern call without its function")
        val (argOps, argSlots) = arguments(rc)
        // The policy decides which argument is copied; the extern's proxies spell the copy (externCallText, W2.6's).
        argOps.forEach { op -> op.use?.let { u -> op.use = CppHoister.Use(u.type, u.place, u.handle) { it } } }
        val receiver = rc.receiver
        val recvOp = receiver?.let { receiverOperand(it, rc, fn, memberStyle = false, lending = false) { coerced(it) } }
        val (ops, slots) = withReceiver(recvOp, argOps, argSlots)
        val receiverType = receiver?.let { typeOf(it) } ?: (state.frame?.owner as? ClassSymbol)?.takeIf { rc.implicitThis }?.selfType
        return hoister.lower(ops, rc.returnType, node = e, consumer = policy.callConsumer(rc, receiverType)) { texts ->
            val copied = argSlots.mapNotNull { s -> (s as? Slot.Given)?.takeIf { argOps[it.operand].copied }?.param }.toSet()
            val recv = when {
                receiver != null -> if (recvOp != null) wrap(texts[0], CppPrec.POSTFIX) else receiverText(receiver)
                rc.implicitThis -> if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) selfPointer() else "this"
                else -> null
            }
            val args = slots.map { s ->
                when (s) {
                    is Slot.Given -> wrap(texts[s.operand], CppPrec.ASSIGN)
                    is Slot.Filled -> s.text
                }
            }
            CppEx(externCallText(rc, recv, args, copied), CppPrec.POSTFIX)
        }
    }

    /**
     * The text of an extern call (design 7.2): the C++ name the declaration gives, `->` or `.`
     * after [receiver] (null for a free function) as the owner's kind says, a `Str` argument
     * through `kira::ffi::in`, a `mut` argument through `kira::ffi::out`. [args] holds one text
     * per entry of [ResolvedCall.args] the call fills, in parameter order, as written; [copied]
     * holds the indices of those the copy policy copies (50-round4 1.2, 6.1), spelled here:
     * `kira::ffi::in(kira::Str(e))` for a `Str`, `T(e)` for anything else. A trailing default
     * is the C++ header's own. W2.6's `CppExternsPart.call` takes exactly these on its branch
     * and makes this text there; at the merge this delegates to it.
     */
    fun externCallText(rc: ResolvedCall, receiver: String?, args: List<String>, copied: Set<Int> = emptySet()): String {
        val fn = rc.fn!!
        val proxied = args.mapIndexed { i, written ->
            val binding = rc.args.getOrNull(i) as? ArgBinding.Given
            val type = binding?.let { model.typeOrNull(it.expr) }
            val a = if (i in copied && type != null) policy.copy(CppEx(written, CppPrec.ASSIGN), type, binding.expr).text else written
            when {
                binding?.byRef == true -> "kira::ffi::out($a)"
                type == KType.Str -> "kira::ffi::in($a)"
                else -> a
            }
        }
        val callee = when {
            receiver == null -> externName(fn)
            rc.receiver == null || rc.receiver is ThisExpr || isPointerLike(typeOf(rc.receiver)) -> "$receiver->${externMemberName(fn)}"
            else -> "$receiver.${externMemberName(fn)}"
        }
        return "$callee(${proxied.joinToString(", ")})"
    }

    /** The C++ name of an extern free function: its `cpp =` or positional symbol, from the global namespace. */
    fun externName(fn: FnSymbol): String {
        val params = (fn.foreign as? Foreign.Extern)?.params.orEmpty()
        val name = params["cpp"] ?: params["symbol"] ?: fn.name
        return if (name.startsWith("::")) name else "::$name"
    }

    private fun externMemberName(fn: FnSymbol): String {
        val params = (fn.foreign as? Foreign.Extern)?.params.orEmpty()
        return params["cpp"] ?: params["symbol"] ?: fn.name
    }

    /** R21: `trace(x)` is `kira::trace(x)` (the C prelude's formats, D42); kira:io's print family likewise. */
    private fun printCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val name = rc.fn?.name ?: "trace"
        if (freestanding) {
            return unsupported(e, "$name in a freestanding module (kira/core.hxx has no output; the board's serial seam prints)")
        }
        val (ops, slots) = arguments(rc)
        return hoister.lower(ops, KType.Void, consumer = policy.callConsumer(rc, null)) { texts -> CppEx("kira::$name(${argList(slots, texts)})", CppPrec.POSTFIX) }
    }

    private fun intrinsic(e: IntrinsicExpr): CppEx {
        if (e.intrinsicKey.name != "_trace_") {
            return unsupported(e, "the intrinsic @${e.intrinsicKey.name}")
        }
        val arg = e.parameters?.singleOrNull() ?: return internal(e, "@_trace_ without its value")
        if (freestanding) {
            return unsupported(e, "@_trace_ in a freestanding module")
        }
        return CppEx("kira::trace(${emit(arg, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
    }

    // ---- magic calls: the binding table (R5, R20) ---------------------------------------------

    private fun receiverTypeOf(rc: ResolvedCall): KType? = rc.receiver?.let { typeOf(it) }

    /** The binding of the magic callee of [rc], found under the keys [CppBindingTable.keysFor] lists. */
    fun bindingFor(fn: FnSymbol, receiver: KType?): Pair<String, CppBinding>? {
        for (key in CppBindingTable.keysFor(fn, receiver, program)) {
            binding(key)?.let { return key to it }
        }
        return null
    }

    /**
     * Hands a binding's includes to the header or the source, where the code using it is
     * placed. One the header already includes (a system module's runtime header, which its
     * types brought in) is not repeated in the source, which includes the header first.
     */
    fun use(binding: CppBinding) {
        binding.includes.forEach { inc ->
            val name = CppBindingTable.includeName(inc)
            when {
                state.headerPlaced || ctx.isHeaderOnly -> ctx.includeInHeader(name)
                !headerIncludes(name) -> ctx.includeInSource(name)
            }
        }
    }

    /**
     * Whether the module's header already includes [name]: an include a part asked for, or the
     * runtime header of a system module the module uses or names (`kira/sync.hxx` for `use
     * "kira:sync"`), which the declaration emitter writes with the module includes.
     */
    private fun headerIncludes(name: String): Boolean {
        if (name in ctx.headerIncludes) {
            return true
        }
        return (ctx.symbol.imports + ctx.referencedModules).any { CppTypeSpeller.systemHeaderFor(it.uri) == name }
    }

    private fun magicCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "a magic call without its function")
        val receiver = rc.receiver
        val recvType = receiverTypeOf(rc)
        val found = bindingFor(fn, recvType)
        if (found == null) {
            val key = (fn.foreign as? Foreign.Magic)?.key
            if (key == "Result.success" || key == "Result.error") {
                // D39: compiler-known; kira::Result's static factories.
                val (ops, slots) = arguments(rc)
                val result = ctx.spell(rc.returnType, Pos.VALUE, e)
                return hoister.lower(ops, rc.returnType, node = e) { texts -> CppEx("$result::${fn.name}(${argList(slots, texts)})", CppPrec.POSTFIX) }
            }
            return unsupported(e, "the magic call '${fn.qualifiedName}' (no cpp binding under ${CppBindingTable.keysFor(fn, recvType, program)})")
        }
        val original = found.second
        // A write over an element whose drop may run an IMPURE `finally` stores first (CppCopyPolicy.dropsOnWrite).
        val binding = replacing(original, fn, rc)
        if (isLiteralView(rc)) {
            // `"abc".view()` views the literal's static storage, as the implicit conversion does
            // (kira::lit): kira::str::view("abc") would view a temporary kira::Str.
            return CppEx("kira::lit(${CppDeclEmitter.cppString((receiver as StringLiteral).value)})", CppPrec.POSTFIX)
        }
        val strBufText = isStrBufText(fn, recvType) && rc.args.firstOrNull().let { it is ArgBinding.Given && it.expr is InterpolatedStringLiteral }
        if (strBufText && receiver != null) {
            val pieces = strBufPieces(receiver, fn, (rc.args.first() as ArgBinding.Given).expr as InterpolatedStringLiteral)
            return CppEx("(${pieces.joinToString(", ")})", CppPrec.PRIMARY)
        }
        use(original)
        val memberStyle = CppBindingTable.isMemberStyle(original)
        val typeArgs = buildList {
            if (fn.owner != null && recvType is KType.Nominal) {
                addAll(recvType.typeArgs())
            }
            addAll(rc.typeArgs)
        }.map { ctx.spell(it, Pos.TEMPLATE_ARG, e) }
        if (recvType != null && CppHoister.holdsSecondClass(recvType)) {
            // A container, box or Maybe of views (design 30, 1.2).
            hoister.internalView(e, "the receiver of '${fn.name}' holds views, a ${recvType.display()}", "type")
        }
        val (argOps, slots) = arguments(rc)
        val lending = recvType != null && fn.name in LENDERS && (CppBindingTable.isArr(recvType) || CppBindingTable.magicName(recvType) == "List") &&
            CppBindingTable.magicName(rc.returnType) == "MutView"
        val ops = mutableListOf<CppHoister.Operand>()
        val receiverIndex = if (receiver != null) {
            // A lent result's receiver (R-A: `gl.get(i)` is `gl[i]`) is a step of the path, as an
            // element's container is: located where the accessor runs, never copied before it.
            // The accessor binds a reference to it before its index runs, so an element step of
            // its own is a leaf (P1: `gll.get(0).get(growGll())` as `gll[0][growGll()]`).
            val op = (if (model.lentPlaces[e] != null) pathOperand(receiver, CppHoister.PlaceMode.BOUND) else null)
                ?: receiverOperand(receiver, rc, fn, memberStyle, lending) { receiverEx(receiver, memberStyle) }
                ?: CppHoister.Operand.Value(null) { receiverEx(receiver, memberStyle) }
            ops += op
            0
        } else {
            -1
        }
        val shift = ops.size
        ops += argOps
        // A binding that names an operand twice (`({self}.clear(), {self}.add({0}))`) would run
        // an impure path or argument twice: such an operand is computed exactly once first.
        val force = CppBindingTable.repeated(binding).any { name ->
            val op = if (name == "self") ops.getOrNull(receiverIndex) else slots.getOrNull(name.toInt()).let { s -> (s as? Slot.Given)?.let { ops[it.operand + shift] } }
            op != null && hoister.rankOf(op) != CppHoister.PURE
        }
        // w2-3 #3: an operand the expansion never names (`Tuple2.size` is
        // `static_cast<std::int32_t>(2)`) is still evaluated, in Kira's order, unless evaluating it
        // does nothing a sibling or the program could see (a literal, a constant, a PRIVATE read).
        val named = CppBindingTable.placeholders(binding)
        val dropped = buildList {
            if (receiverIndex >= 0 && "self" !in named) add(receiverIndex)
            slots.forEachIndexed { i, s -> if (s is Slot.Given && "$i" !in named) add(s.operand + shift) }
        }.filter { hoister.rankOf(ops[it]) != CppHoister.PURE }
        return hoister.lower(ops, rc.returnType, force, node = e, consumer = policy.callConsumer(rc, recvType)) { texts ->
            val argTexts = slots.map { s ->
                when (s) {
                    is Slot.Given -> texts[s.operand + shift]
                    is Slot.Filled -> CppEx(s.text, CppPrec.ASSIGN)
                }
            }
            val self: ((Int) -> String)? = if (receiverIndex >= 0) { prec -> wrap(texts[receiverIndex], prec) } else null
            var text = CppBindingTable.expand(binding, self, { i, prec -> argTexts.getOrNull(i)?.let { wrap(it, prec) } ?: "/* missing */" }, typeArgs)
            if (lending) {
                // A mutable Arr or List lends a MutView (the typer's MutView lending): `kira::mutView(p).from(4)`.
                text = text.replaceFirst("kira::view(", "kira::mutView(")
            }
            if (dropped.isNotEmpty()) {
                val evaluated = dropped.joinToString(", ") { "static_cast<void>(${wrap(texts[it], CppPrec.ASSIGN)})" }
                return@lower CppEx("($evaluated, ${wrap(bindingEx(text), CppPrec.ASSIGN)})", CppPrec.PRIMARY)
            }
            bindingEx(text)
        }
    }

    /**
     * [binding], or, when its expression is a write `PLACE = {n}` (`List.set`, `Arr.set`,
     * `MutView.set`) over an element whose drop may run an IMPURE `finally`
     * ([CppCopyPolicy.dropsOnWrite]), the same write as `kira::replace(PLACE) = {n}`, which stores
     * first and drops the old element after, as [assignment] spells `xs[i] = v`.
     */
    private fun replacing(binding: CppBinding, fn: FnSymbol, rc: ResolvedCall): CppBinding {
        val m = WRITE_TEMPLATE.matchEntire(binding.expr) ?: return binding
        val element = fn.params.getOrNull(m.groupValues[2].toInt())?.type?.substitute(rc.substitution)
        if (!policy.dropsOnWrite(element)) {
            return binding
        }
        return binding.copy(expr = "kira::replace(${m.groupValues[1]}) = {${m.groupValues[2]}}")
    }

    /** Whether [rc] is `Str.view` on a string literal: a view of static storage (`kira::lit`), no temporary. */
    fun isLiteralView(rc: ResolvedCall): Boolean =
        rc.kind == CallKind.MAGIC && rc.fn?.name == "view" && rc.receiver is StringLiteral && rc.args.isEmpty() &&
            model.typeOrNull(rc.receiver) == KType.Str

    /**
     * A magic call's receiver: the value itself, except a literal Str constant (a `const char*`)
     * as the receiver of a member-style binding, which is `kira::Str(...)` first (R5,
     * StrConstReceiver).
     */
    private fun receiverEx(receiver: Expr, memberStyle: Boolean): CppEx {
        if (memberStyle && model.coercion(receiver) == Coercion.StrConstReceiver) {
            return CppEx("kira::Str(${emit(receiver, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
        }
        if (receiver is ThisExpr) {
            return thisValue(receiver)
        }
        return raw(receiver)
    }

    /** A binding's expansion as an expression: a fully parenthesized one loses its parentheses and keeps its inner precedence. */
    fun bindingEx(text: String): CppEx {
        val stripped = stripParens(text)
        val prec = CppBindingTable.precOf(stripped)
        if (prec == CppPrec.COMMA) {
            return CppEx("($stripped)", CppPrec.PRIMARY)
        }
        return CppEx(stripped, prec, topOperator(stripped, prec))
    }

    private fun topOperator(text: String, prec: Int): String? = when (prec) {
        CppPrec.EQ -> if (" != " in text) "!=" else "=="
        CppPrec.REL, CppPrec.ADD, CppPrec.MUL, CppPrec.SHIFT, CppPrec.BIT_AND, CppPrec.BIT_OR, CppPrec.BIT_XOR, CppPrec.AND, CppPrec.OR -> "?"
        else -> null
    }

    private fun stripParens(text: String): String {
        var t = text.trim()
        while (t.startsWith("(") && t.endsWith(")") && matchingClose(t, 0) == t.length - 1) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun matchingClose(t: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        var inChar = false
        while (i < t.length) {
            val c = t[i]
            when {
                inString -> if (c == '\\') i += 1 else if (c == '"') inString = false
                inChar -> if (c == '\\') i += 1 else if (c == '\'') inChar = false
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> depth += 1
                c == ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return i
                    }
                }
            }
            i += 1
        }
        return -1
    }

    // ---- StrBuf (design 10) -------------------------------------------------------------------

    /** `StrBuf.set` / `StrBuf.add`, whose interpolation argument is appended piece by piece. */
    fun isStrBufText(fn: FnSymbol, recvType: KType?): Boolean =
        CppBindingTable.magicName(recvType) == "StrBuf" && (fn.name == "set" || fn.name == "add")

    /**
     * `buf.set("OK servo=${s}")` as the calls it is (design 10): `buf.clear()` for `set`, then
     * one append per piece, text as `kira::lit`, an integer through `addInt` or `addUInt`, a
     * Char through `addChar`. Each call is spelled by its own binding. Every piece names the
     * receiver, so a receiver whose path is not pure (`bufs[nextSize()]`) is located once
     * first, and the pieces are then one IIFE (the single element returned).
     */
    fun strBufPieces(receiver: Expr, fn: FnSymbol, text: InterpolatedStringLiteral): List<String> {
        val op = receiverOperand(receiver, null, fn, memberStyle = true, lending = false) { receiverEx(receiver, true) }
        // Each piece is `recv.addInt(hole)`: C++ binds recv before the hole runs, so a hole
        // whose effect may move the container recv is an element of would be appended into
        // freed storage. Refused, as nothing orders a hole before its own piece's receiver.
        val holes = text.parts.filterIsInstance<InterpolationPart.Hole>()
        val written = hoister.writtenBy(holes.map { h -> CppHoister.Operand.Value(h.expr) { CppEx("", CppPrec.PRIMARY) } })
        if (op != null && hoister.stepRank(op, written) != CppHoister.PURE) {
            holes.firstOrNull { hoister.rank(it.expr, written) == CppHoister.IMPURE }?.let { hole ->
                hoister.refuseOrder(hole.expr, "this hole's effect may move the container the StrBuf is an element of, which C++ has already located for the append: store the hole's value in a local first")
            }
        }
        if (op == null || hoister.rankOf(op) == CppHoister.PURE) {
            return strBufPiecesOn(if (op == null) receiverText(receiver) else wrap(hoister.text(op), CppPrec.POSTFIX), fn, text)
        }
        val iife = hoister.lower(listOf(op), KType.Void, force = true) { (r) ->
            CppEx(strBufPiecesOn(wrap(r, CppPrec.POSTFIX), fn, text).joinToString(", "), CppPrec.COMMA)
        }
        return listOf(iife.text)
    }

    /** [strBufPieces] over the receiver's C++ text [recv]. */
    private fun strBufPiecesOn(recv: String, fn: FnSymbol, text: InterpolatedStringLiteral): List<String> {
        val out = mutableListOf<String>()
        fun piece(method: String, arg: String?) {
            val key = "StrBuf.$method"
            val b = binding(key)
            if (b == null) {
                ctx.unsupported(text, "a StrBuf append ($key has no cpp binding)")
                return
            }
            use(b)
            out += CppBindingTable.expand(b, { _ -> recv }, { _, _ -> arg ?: "" }, emptyList())
        }
        if (fn.name == "set") {
            piece("clear", null)
        }
        text.parts.forEach { part ->
            when (part) {
                is InterpolationPart.Text -> piece("add", "kira::lit(${CppDeclEmitter.cppString(part.text)})")
                is InterpolationPart.Hole -> {
                    val t = typeOf(part.expr)
                    val prim = t.prim
                    val v = emit(part.expr, CppPrec.ASSIGN)
                    when {
                        prim != null && prim.isInteger && prim.signed -> piece("addInt", v)
                        prim != null && prim.isInteger -> piece("addUInt", v)
                        prim == Prim.CHAR -> piece("addChar", v)
                        prim == Prim.BOOL -> piece("add", "${emit(part.expr, CppPrec.OR)} ? kira::lit(\"true\") : kira::lit(\"false\")")
                        t == KType.Str && !freestanding -> piece("add", "kira::str::view($v)")
                        else -> ctx.unsupported(part.expr, "a ${t} hole in a StrBuf interpolation (append it with addFixed, or convert it first)")
                    }
                }
            }
        }
        return out
    }

    // ---- if-expressions (R18) -------------------------------------------------------------------

    private fun ifExpr(e: IfExpr): CppEx {
        val t = typeOf(e)
        if (model.ifShape(e) == true) {
            return ternary(e, t)
        }
        if (CppHoister.isSecondClass(t)) {
            // Its branches would be a lambda's, and a view made in one would be returned past its statements (design 30, E4).
            hoister.internalView(e, "an if-expression with statement branches is a view (${t.display()})", "position")
        }
        return ctx.parts.stmts.let { stmts ->
            val part = stmts as? CppStmtEmitter ?: return unsupported(e, "an if-expression that is no ternary, without W2.3's statement part")
            CppEx(part.ifExprLambda(ctx, e, t), CppPrec.POSTFIX)
        }
    }

    /** The value a branch of an if-expression ends in (its single statement's expression, for a ternary). */
    fun branchValue(branch: List<Statement>): Expr? {
        val last = branch.lastOrNull() ?: return null
        if (last.javaClass != Statement::class.java) {
            return null
        }
        return last.expr
    }

    /**
     * `c ? a : b`. A branch the typer coerced is spelled `T(branch)`; in a Str ternary a branch
     * that is no Str prvalue is `kira::Str(branch)`; a numeric literal branch is `T{lit}` where
     * C++ has no literal of its type (R2, R18).
     */
    private fun ternary(e: IfExpr, t: KType): CppEx {
        val cond = emit(e.condition, CppPrec.OR)
        val a = branchValue(e.thenBranch) ?: return internal(e, "an if-expression branch without a value")
        val b = branchValue(e.elseBranch) ?: return internal(e, "an if-expression branch without a value")
        val then = branchText(a, t)
        val otherwise = branchText(b, t)
        return CppEx("$cond ? ${wrap(then, CppPrec.ASSIGN + 1)} : ${wrap(otherwise, CppPrec.ASSIGN)}", CppPrec.ASSIGN, "?")
    }

    private fun branchText(v: Expr, t: KType): CppEx {
        if (v is IfExpr && model.ifShape(v) == true) {
            return ternary(v, t)
        }
        val c = model.coercion(v)
        if (c != null && !(c is Coercion.ToView && v is StringLiteral)) {
            val inner = if (c is Coercion.WrapSome && bareLiteral(v) != null) literal(v, CppLitRole.TYPED)!! else raw(v, CppLitRole.TYPED)
            return CppEx("${ctx.spell(t, Pos.VALUE, v)}(${inner.text})", CppPrec.POSTFIX)
        }
        if (t == KType.Str && !isStrPrvalue(v)) {
            return CppEx("kira::Str(${emit(v, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
        }
        return coerced(v, CppLitRole.TYPED)
    }

    /** Whether [e] is a `kira::Str` prvalue in C++ (a call, an interpolation, a concatenation, a conversion). */
    private fun isStrPrvalue(e: Expr): Boolean = when (e) {
        is FunctionCallExpr, is InterpolatedStringLiteral, is BinaryExpr, is TypeCastExpr, is IfExpr -> true
        is MemberAccessExpr -> e.member is FunctionCallExpr
        else -> false
    }

    // ---- throw (5.4, D41) -----------------------------------------------------------------------

    private fun throwExpr(e: ThrowExpr): CppEx {
        val v = emit(e.value, CppPrec.ASSIGN)
        if (freestanding) {
            return CppEx("kira::panic($v)", CppPrec.POSTFIX)
        }
        return CppEx("throw kira::Error{$v}", CppPrec.ASSIGN)
    }

    companion object {
        /** The lowering of [ctx]'s module emission. */
        fun of(ctx: CppEmitContextImpl): CppLowering {
            val state = CppBodyState.of(ctx)
            return state.lowering ?: CppLowering(ctx).also { state.lowering = it }
        }

        private val POINTER_MAGIC = setOf("Ref", "Unsafe")

        /** The calls whose handle receiver runs with a raw `this` the caller must hold (50-round4 W5). */
        private val HANDLE_CALLS = setOf(CallKind.METHOD, CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN)

        /** The methods that lend a MutView from a mutable Arr or List (the typer's MutView lending). */
        private val LENDERS = setOf("from", "slice", "view")

        /** A binding whose expression writes its argument `{n}` over a place: `kira::at({self}, {0}) = {1}`. */
        private val WRITE_TEMPLATE = Regex("(.+) = \\{([0-9]+)}")

        val ARITH_OR_BITS = setOf(
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD,
            BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR, BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR,
        )
        val COMPARISONS = setOf(
            BinaryOp.EQUALS, BinaryOp.NOT_EQUAL, BinaryOp.LESS_THAN, BinaryOp.LESS_THAN_OR_EQUAL,
            BinaryOp.GREATER_THAN, BinaryOp.GREATER_THAN_OR_EQUAL,
        )
        val ORDERING = setOf(BinaryOp.LESS_THAN, BinaryOp.LESS_THAN_OR_EQUAL, BinaryOp.GREATER_THAN, BinaryOp.GREATER_THAN_OR_EQUAL)
        val SHIFT_OPS = setOf(BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR)

        val CPP_OPS: Map<BinaryOp, String> = mapOf(
            BinaryOp.ADD to "+", BinaryOp.SUB to "-", BinaryOp.MUL to "*", BinaryOp.DIV to "/", BinaryOp.MOD to "%",
            BinaryOp.EQUALS to "==", BinaryOp.NOT_EQUAL to "!=", BinaryOp.LESS_THAN to "<", BinaryOp.LESS_THAN_OR_EQUAL to "<=",
            BinaryOp.GREATER_THAN to ">", BinaryOp.GREATER_THAN_OR_EQUAL to ">=", BinaryOp.AND to "&&", BinaryOp.OR to "||",
            BinaryOp.CONJUNCTIVE_AND to "&", BinaryOp.CONJUNCTIVE_OR to "|", BinaryOp.XOR to "^", BinaryOp.SHL to "<<", BinaryOp.SHR to ">>",
        )

        val COMPOUND: Map<BinaryOp, String> = mapOf(
            BinaryOp.ADD to "+=", BinaryOp.SUB to "-=", BinaryOp.MUL to "*=", BinaryOp.DIV to "/=", BinaryOp.MOD to "%=",
            BinaryOp.CONJUNCTIVE_AND to "&=", BinaryOp.CONJUNCTIVE_OR to "|=", BinaryOp.XOR to "^=", BinaryOp.SHL to "<<=", BinaryOp.SHR to ">>=",
        )

        private val BITWISE_SYMS = setOf("&", "|", "^")
        private val COMPARISON_SYMS = setOf("==", "!=", "<", "<=", ">", ">=")
        private val SHIFT_SYMS = setOf("<<", ">>")
        private val ARITH_SYMS = setOf("+", "-", "*", "/", "%")

        /** Every C++ binary operator the emitter writes at a top level, and `?` for a binding's unknown one. */
        private val BINARY_SYMS = BITWISE_SYMS + COMPARISON_SYMS + SHIFT_SYMS + ARITH_SYMS + setOf("&&", "||", "?")

        fun precOf(op: BinaryOp): Int = when (op) {
            BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> CppPrec.MUL
            BinaryOp.ADD, BinaryOp.SUB -> CppPrec.ADD
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR -> CppPrec.SHIFT
            BinaryOp.LESS_THAN, BinaryOp.LESS_THAN_OR_EQUAL, BinaryOp.GREATER_THAN, BinaryOp.GREATER_THAN_OR_EQUAL -> CppPrec.REL
            BinaryOp.EQUALS, BinaryOp.NOT_EQUAL -> CppPrec.EQ
            BinaryOp.CONJUNCTIVE_AND -> CppPrec.BIT_AND
            BinaryOp.XOR -> CppPrec.BIT_XOR
            BinaryOp.CONJUNCTIVE_OR -> CppPrec.BIT_OR
            BinaryOp.AND -> CppPrec.AND
            BinaryOp.OR -> CppPrec.OR
            else -> CppPrec.PRIMARY
        }
    }
}
