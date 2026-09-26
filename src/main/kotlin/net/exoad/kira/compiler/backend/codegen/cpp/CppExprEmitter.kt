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
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.prim
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
 * otherwise meet `int` against `long`); the digits alone as a shift count ([SHIFT_COUNT]).
 */
enum class CppLitRole { PLAIN, OPERAND, TYPED, DEDUCED, SHIFT_COUNT }

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
            is Coercion.ToView -> {
                if (c.from == KType.Str && e is StringLiteral) {
                    return CppEx("kira::lit(${CppDeclEmitter.cppString(e.value)})", CppPrec.POSTFIX)
                }
                return raw(e, role)
            }
            else -> return raw(e, role)
        }
    }

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
            is AssignmentExpr -> assignment(e)
            is CompoundAssignmentExpr -> compound(e.left, e.operator, e.right, e)
            is PlaceAssignmentExpr -> if (e.operator == null) {
                CppEx("${place(e.target)} = ${emit(e.value, CppPrec.ASSIGN)}", CppPrec.ASSIGN, "=")
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
            else -> false
        }
        if (typed) {
            return CppEx("${ctx.speller.scalarName(prim)}{$sign$digits}", CppPrec.POSTFIX)
        }
        val suffix = when (prim) {
            Prim.UINT32 -> "u"
            Prim.UINT64 -> if (lit.value < 0) "u" else ""
            Prim.UINT8, Prim.UINT16 -> if (role == CppLitRole.OPERAND) "u" else ""
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
        } else {
            ctx.qualified(sym)
        }
        is FnSymbol -> if (sym.foreign is Foreign.Extern) externName(sym) else ctx.qualified(sym)
        else -> null
    }

    /** A field of the implicit receiver: bare in a method, its copy (`c_k`) or `self->k` in a lambda. */
    fun implicitField(f: FieldSymbol): String {
        val frame = state.frame
        frame?.fieldName(f)?.let { return it }
        val name = ctx.names.escape(f.name)
        return if (frame?.receiverAccess == CppBodyState.ThisCapture.SELF) "self->$name" else name
    }

    /** `this` as a value: `*this` in a struct, `shared_from_this()` in a class (`self` inside an escaping lambda). */
    private fun thisValue(e: ThisExpr): CppEx {
        val owner = state.frame?.owner ?: ctx.scope
        val cls = owner as? ClassSymbol
        if (cls != null && cls.isStruct) {
            return CppEx("*this", CppPrec.UNARY)
        }
        if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) {
            return CppEx("self", CppPrec.PRIMARY)
        }
        if (owner == null) {
            return internal(e, "`this` outside a method")
        }
        return CppEx("shared_from_this()", CppPrec.POSTFIX)
    }

    /** The receiver [e] as the object of `.` or `->` (a `this` receiver is the pointer `this`, or `self`). */
    private fun receiverText(e: Expr): String {
        if (e is ThisExpr) {
            return if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) "self" else "this"
        }
        return emit(e, CppPrec.POSTFIX)
    }

    /**
     * How members of a value of type [t] are reached (R4): `->` through a class or trait
     * reference, an `Unsafe`, a `Ref` or an opaque handle; `.` on a struct and the builtins;
     * `kira::deref(x).` on a type parameter (design 5.5).
     */
    private fun access(receiver: Expr, t: KType): String {
        if (receiver is ThisExpr) {
            return "${receiverText(receiver)}->"
        }
        if (t is KType.Param) {
            return "kira::deref(${emit(receiver, CppPrec.ASSIGN)})."
        }
        return receiverText(receiver) + if (isPointerLike(t)) "->" else "."
    }

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
    fun field(origin: Expr, f: FieldSymbol): CppEx {
        val t = typeOf(origin)
        val name = ctx.names.escape(f.name)
        when (CppBindingTable.magicName(t)) {
            "Maybe" -> if (f.name == "value") {
                // D40: `m.value` is `m.unwrap()`.
                return CppEx("kira::unwrap(${emit(origin, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
            }
            "Result" -> return CppEx("${receiverText(origin)}.${if (f.name == "error") "unwrapErr" else "unwrap"}()", CppPrec.POSTFIX)
        }
        return CppEx(access(origin, t) + name, CppPrec.POSTFIX)
    }

    // ---- places (5.4) -----------------------------------------------------------------------

    /** An assignment target: a variable, a field or an element, spelled as the lvalue C++ writes (`kira::at(p, 30)`, `m[k]`). */
    fun place(target: Expr): String = when (target) {
        is ArrayIndexExpr -> {
            val ct = typeOf(target.originExpr)
            if (CppBindingTable.magicName(ct) == "Map") {
                "${emit(target.originExpr, CppPrec.POSTFIX)}[${emit(target.indexExpr, CppPrec.NONE)}]"
            } else {
                "kira::at(${emit(target.originExpr, CppPrec.ASSIGN)}, ${emit(target.indexExpr, CppPrec.ASSIGN)})"
            }
        }
        else -> emit(target, CppPrec.UNARY)
    }

    private fun assignment(e: AssignmentExpr): CppEx =
        CppEx("${emit(e.target, CppPrec.UNARY)} = ${emit(e.value, CppPrec.ASSIGN)}", CppPrec.ASSIGN, "=")

    /**
     * `x op= y`, bare (R1: clean on every compiler), except where R11 or R12 checks the
     * operation: a division by what is not a nonzero constant is `x = kira::div(x, y)`, a shift
     * by what is not a constant below the width `x = kira::shl(x, n)`.
     */
    private fun compound(target: Expr, op: BinaryOp, value: Expr, node: Expr): CppEx {
        val t = typeOf(target)
        val prim = t.prim
        val lhs = place(target)
        if (prim != null && prim.isInteger) {
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && !isConstDivisor(value, prim)) {
                val f = if (op == BinaryOp.DIV) "kira::div" else "kira::mod"
                return CppEx("$lhs = $f($lhs, ${emit(value, CppPrec.ASSIGN, CppLitRole.DEDUCED)})", CppPrec.ASSIGN, "=")
            }
            if (op in SHIFT_OPS) {
                val constant = isConstCount(value, prim)
                if (op == BinaryOp.USHR || !constant) {
                    val n = if (constant) raw(value, CppLitRole.SHIFT_COUNT) else coerced(value)
                    val shifted = shiftText(CppEx(lhs, CppPrec.UNARY), n, constant, op, prim)
                    return CppEx("$lhs = ${narrowed(shifted, prim).text}", CppPrec.ASSIGN, "=")
                }
            }
        }
        val sym = COMPOUND[op] ?: return unsupported(node, "the compound assignment ${op.name}")
        val role = if (op in SHIFT_OPS) CppLitRole.SHIFT_COUNT else CppLitRole.PLAIN
        return CppEx("$lhs $sym ${emit(value, CppPrec.ASSIGN, role)}", CppPrec.ASSIGN, sym)
    }

    // ---- operators (R1, R11, R12, R16, R17) --------------------------------------------------

    private fun binary(e: BinaryExpr): CppEx {
        val op = e.operator
        if (model.opCall(e) != null) {
            // An @op_* overload: C++ resolves the same operator to the same function.
            val sym = CPP_OPS[op] ?: return unsupported(e, "the operator overload ${op.name}")
            return hoister.lower(
                listOf(operand(e.leftExpr), operand(e.rightExpr)), typeOf(e),
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

    /** An operand of an operator: its literal role follows the other side (R2). */
    private fun operand(e: Expr, other: Expr? = null, role: CppLitRole? = null): CppHoister.Operand {
        val r = role ?: if (other != null && bareLiteral(e) != null && isLiteralOnly(other)) CppLitRole.TYPED else CppLitRole.OPERAND
        return CppHoister.Operand(e, spillable = true) { coerced(e, r) }
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
        return hoister.lower(listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr)), KType.BOOL) { (l, r) ->
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
            // R11: the spec's run-time error on /0 and INT_MIN / -1 (D10).
            val f = if (op == BinaryOp.DIV) "kira::div" else "kira::mod"
            return hoister.lower(
                listOf(operand(e.leftExpr, role = CppLitRole.DEDUCED), operand(e.rightExpr, role = CppLitRole.DEDUCED)), t,
            ) { (l, r) -> CppEx("$f(${wrap(l, CppPrec.ASSIGN)}, ${wrap(r, CppPrec.ASSIGN)})", CppPrec.POSTFIX) }
        }
        val sym = CPP_OPS[op]!!
        return hoister.lower(listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr)), t) { (l, r) ->
            narrowed(infix(l, sym, r, precOf(op)), prim)
        }
    }

    /** R14: `a + b` on Str; a `const char*` on the left becomes a `kira::Str` first. */
    private fun concat(e: BinaryExpr): CppEx {
        val charPtr = isCharPtr(e.leftExpr)
        return hoister.lower(listOf(operand(e.leftExpr), operand(e.rightExpr)), KType.Str) { (l, r) ->
            val left = if (charPtr) CppEx("kira::Str(${l.text})", CppPrec.POSTFIX) else l
            infix(left, "+", r, CppPrec.ADD)
        }
    }

    private fun bitwise(e: BinaryExpr): CppEx {
        val t = typeOf(e)
        val sym = CPP_OPS[e.operator]!!
        return hoister.lower(listOf(operand(e.leftExpr, e.rightExpr), operand(e.rightExpr, e.leftExpr)), t) { (l, r) ->
            narrowed(infix(l, sym, r, precOf(e.operator)), t.prim)
        }
    }

    private fun shift(e: BinaryExpr): CppEx {
        val t = typeOf(e)
        val prim = t.prim ?: return internal(e, "a shift of a non-integer")
        val constant = isConstCount(e.rightExpr, prim)
        if (constant) {
            // C++17 sequences the left operand of a shift before its right: nothing to spill.
            return narrowed(shiftText(coerced(e.leftExpr, CppLitRole.OPERAND), raw(e.rightExpr, CppLitRole.SHIFT_COUNT), true, e.operator, prim), prim)
        }
        val count = CppHoister.Operand(e.rightExpr, true) { coerced(e.rightExpr) }
        return hoister.lower(listOf(operand(e.leftExpr, role = CppLitRole.DEDUCED), count), t) { (l, n) ->
            narrowed(shiftText(l, n, false, e.operator, prim), prim)
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

    private fun unary(e: UnaryExpr): CppEx {
        val t = typeOf(e)
        val prim = t.prim
        val operand = coerced(e.operand)
        return when (e.operator) {
            UnaryOp.NOT -> CppEx("!${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY)
            UnaryOp.POS -> operand
            UnaryOp.NEG -> when {
                prim != null && prim.promotesInCpp -> narrowed(CppEx("-${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY), prim)
                // MSVC C4146 refuses a unary minus on an unsigned operand; 0u - x is the same wrap (D8).
                prim != null && prim.isInteger && !prim.signed -> CppEx("0u - ${wrap(operand, CppPrec.ADD + 1)}", CppPrec.ADD, "-")
                else -> CppEx("-${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY)
            }
            UnaryOp.BIT_NOT -> narrowed(CppEx("~${wrap(operand, CppPrec.UNARY)}", CppPrec.UNARY), prim)
        }
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
                is InterpolationPart.Text -> CppHoister.Operand(null, false) { CppEx(CppDeclEmitter.cppString(part.text), CppPrec.PRIMARY) }
                is InterpolationPart.Hole -> CppHoister.Operand(part.expr, true) { coerced(part.expr) }
            }
        }
        return hoister.lower(operands, KType.Str) { texts ->
            CppEx("kira::cat(${texts.joinToString(", ") { wrap(it, CppPrec.ASSIGN) }})", CppPrec.POSTFIX)
        }
    }

    /** R15: `s[i]` is `kira::str::at(s, i)` on a Str and `kira::at(a, i)` on an Arr, List or view: checked. */
    private fun index(e: ArrayIndexExpr): CppEx {
        val ct = typeOf(e.originExpr)
        val f = when {
            ct == KType.Str -> "kira::str::at"
            CppBindingTable.magicName(ct) == "Map" -> return unsupported(e, "reading a Map with m[k] (read it with m.get(k))")
            else -> "kira::at"
        }
        val container = CppHoister.Operand(e.originExpr, !isPlaceExpr(e.originExpr)) { coerced(e.originExpr) }
        return hoister.lower(listOf(container, operand(e.indexExpr, role = CppLitRole.PLAIN)), typeOf(e)) { (c, i) ->
            CppEx("$f(${wrap(c, CppPrec.ASSIGN)}, ${wrap(i, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
        }
    }

    fun isPlaceExpr(e: Expr): Boolean = model.place(e) != null

    // ---- literals of containers, construction (R9) ---------------------------------------------

    private fun arrayLiteral(e: ArrayLiteral): CppEx {
        val t = typeOf(e)
        val elements = e.value.map { CppHoister.Operand(it, true) { coerced(it) } }
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
        val given = ri.sourceOrder.map { ri.fields[it] as FieldInit.Given }
        val ops = given.map { g -> CppHoister.Operand(g.expr, true) { coerced(g.expr) } }
        val byField = IdentityHashMap<FieldSymbol, Int>()
        given.forEachIndexed { i, g -> byField[g.field] = i }
        val typeText = if (model.typeOf(e.typeName) != null) ctx.spell(e.typeName, Pos.VALUE) else ctx.spell(t, Pos.VALUE, e)
        return when {
            cls.kind == ClassKind.MAGIC && cls.name == "Ref" -> hoister.lower(ops, t) { texts ->
                val inner = (t as KType.Nominal).typeArgs().firstOrNull() ?: KType.Error
                val arg = ri.fields.firstOrNull()?.let { f -> byField[f.field]?.let { wrap(texts[it], CppPrec.ASSIGN) } } ?: ""
                CppEx("std::make_shared<kira::Box<${ctx.spell(inner, Pos.TEMPLATE_ARG, e)}>>($arg)", CppPrec.POSTFIX)
            }
            cls.kind == ClassKind.CLASS || ctx.speller.isSystemClass(cls) -> hoister.lower(ops, t) { texts ->
                val last = ri.fields.indexOfLast { it is FieldInit.Given || it.field.default == null && it.field.isRequired }
                val args = ri.fields.take(last + 1).map { f ->
                    when (f) {
                        is FieldInit.Given -> wrap(texts[byField[f.field]!!], CppPrec.ASSIGN)
                        is FieldInit.Default -> f.field.default?.let { emit(it, CppPrec.ASSIGN) } ?: "${ctx.spell(f.field.type, Pos.VALUE, e)}{}"
                    }
                }
                CppEx("std::make_shared<${ctx.speller.bareClass(t)}>(${args.joinToString(", ")})", CppPrec.POSTFIX)
            }
            cls.kind == ClassKind.MAGIC -> magicConstruction(e, cls, t, typeText, ri.fields, byField, ops)
            else -> hoister.lower(ops, t) { texts ->
                val inits = ri.fields.filterIsInstance<FieldInit.Given>().map { f ->
                    ".${ctx.names.escape(f.field.name)} = ${wrap(texts[byField[f.field]!!], CppPrec.ASSIGN)}"
                }
                CppEx("$typeText{${inits.joinToString(", ")}}", CppPrec.POSTFIX)
            }
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
                val values = givenFields[0].expr
                if (values is ArrayLiteral && cls.name == "List") {
                    CppEx("$typeText${bracedElements(values)}", CppPrec.POSTFIX)
                } else {
                    CppEx("$typeText(${emit(values, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
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
     * parameter order. A `mut` argument is a place and is never spilled (R19); a trailing
     * default is left to C++'s default argument (R6).
     */
    private fun arguments(rc: ResolvedCall, role: (Int) -> CppLitRole = { CppLitRole.PLAIN }, wrap: (Int, Expr, CppEx) -> CppEx = { _, _, ex -> ex }): Pair<List<CppHoister.Operand>, List<Slot>> {
        val operands = mutableListOf<CppHoister.Operand>()
        val operandOf = HashMap<Int, Int>()
        rc.sourceOrder.forEach { i ->
            val a = rc.args[i] as? ArgBinding.Given ?: return@forEach
            operandOf[i] = operands.size
            val e = a.expr
            operands += CppHoister.Operand(e, spillable = !a.byRef) { wrap(i, e, coerced(e, role(i))) }
        }
        val slots = mutableListOf<Slot>()
        rc.args.forEachIndexed { i, a ->
            when (a) {
                is ArgBinding.Given -> {
                    val op = operandOf[i] ?: run {
                        // A written argument missing from sourceOrder: keep it, in parameter order.
                        operandOf[i] = operands.size
                        operands += CppHoister.Operand(a.expr, spillable = !a.byRef) { wrap(i, a.expr, coerced(a.expr, role(i))) }
                        operandOf[i]!!
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

    private fun argList(slots: List<Slot>, texts: List<CppEx>): String = slots.joinToString(", ") { s ->
        when (s) {
            is Slot.Given -> wrap(texts[s.operand], CppPrec.ASSIGN)
            is Slot.Filled -> s.text
        }
    }

    private fun typeArgText(types: List<KType>, at: Expr): String =
        if (types.isEmpty()) "" else types.joinToString(", ", "<", ">") { ctx.spell(it, Pos.TEMPLATE_ARG, at) }

    private fun freeCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "a free call without its function")
        val (ops, slots) = arguments(rc)
        val name = ctx.qualified(fn) + if (fn.typeParams.isNotEmpty()) typeArgText(rc.typeArgs, e) else ""
        return hoister.lower(ops, rc.returnType) { texts -> CppEx("$name(${argList(slots, texts)})", CppPrec.POSTFIX) }
    }

    private fun methodCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "a method call without its method")
        val (ops, slots) = arguments(rc)
        val name = ctx.names.escape(fn.name) + if (fn.typeParams.isNotEmpty()) typeArgText(rc.typeArgs, e) else ""
        val receiver = rc.receiver
        // The receiver is evaluated before the arguments in C++17 as in Kira: only the arguments are operands.
        return hoister.lower(ops, rc.returnType) { texts ->
            val callee = when {
                receiver != null -> access(receiver, typeOf(receiver)) + name
                state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF -> "self->$name"
                else -> name
            }
            CppEx("$callee(${argList(slots, texts)})", CppPrec.POSTFIX)
        }
    }

    /** A call through an `Fx` value: a local, a parameter (a template's `F_p&&` too), or a field. */
    private fun fnValueCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val (ops, slots) = arguments(rc)
        val callee = emit(e.name, CppPrec.POSTFIX)
        return hoister.lower(ops, rc.returnType) { texts -> CppEx("$callee(${argList(slots, texts)})", CppPrec.POSTFIX) }
    }

    /**
     * An `@_extern` function or method (design 7.2): the C++ name the declaration gives, a
     * `Str` argument through `kira::ffi::in`, a `mut` argument through `kira::ffi::out`.
     */
    private fun externCall(e: FunctionCallExpr, rc: ResolvedCall): CppEx {
        val fn = rc.fn ?: return internal(e, "an extern call without its function")
        val (ops, slots) = arguments(rc, wrap = { i, arg, ex ->
            when {
                (rc.args[i] as? ArgBinding.Given)?.byRef == true -> CppEx("kira::ffi::out(${ex.text})", CppPrec.POSTFIX)
                model.typeOrNull(arg) == KType.Str -> CppEx("kira::ffi::in(${wrap(ex, CppPrec.ASSIGN)})", CppPrec.POSTFIX)
                else -> ex
            }
        })
        val receiver = rc.receiver
        return hoister.lower(ops, rc.returnType) { texts ->
            val callee = when {
                receiver != null -> access(receiver, typeOf(receiver)) + externMemberName(fn)
                fn.owner != null -> (if (state.frame?.receiverAccess == CppBodyState.ThisCapture.SELF) "self->" else "") + externMemberName(fn)
                else -> externName(fn)
            }
            CppEx("$callee(${argList(slots, texts)})", CppPrec.POSTFIX)
        }
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
        return hoister.lower(ops, KType.Void) { texts -> CppEx("kira::$name(${argList(slots, texts)})", CppPrec.POSTFIX) }
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

    /** Hands a binding's includes to the header or the source, where the code using it is placed. */
    fun use(binding: CppBinding) {
        binding.includes.forEach { inc ->
            val name = CppBindingTable.includeName(inc)
            if (state.headerPlaced || ctx.isHeaderOnly) ctx.includeInHeader(name) else ctx.includeInSource(name)
        }
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
                return hoister.lower(ops, rc.returnType) { texts -> CppEx("$result::${fn.name}(${argList(slots, texts)})", CppPrec.POSTFIX) }
            }
            return unsupported(e, "the magic call '${fn.qualifiedName}' (no cpp binding under ${CppBindingTable.keysFor(fn, recvType, program)})")
        }
        val (_, binding) = found
        val strBufText = isStrBufText(fn, recvType) && rc.args.firstOrNull().let { it is ArgBinding.Given && it.expr is InterpolatedStringLiteral }
        if (strBufText && receiver != null) {
            val pieces = strBufPieces(receiver, fn, (rc.args.first() as ArgBinding.Given).expr as InterpolatedStringLiteral)
            return CppEx("(${pieces.joinToString(", ")})", CppPrec.PRIMARY)
        }
        use(binding)
        val memberStyle = CppBindingTable.isMemberStyle(binding)
        val typeArgs = buildList {
            if (fn.owner != null && recvType is KType.Nominal) {
                addAll(recvType.typeArgs())
            }
            addAll(rc.typeArgs)
        }.map { ctx.spell(it, Pos.TEMPLATE_ARG, e) }
        val (argOps, slots) = arguments(rc)
        val ops = mutableListOf<CppHoister.Operand>()
        val receiverIndex = if (receiver != null) {
            val spillable = !memberStyle && !isPlaceExpr(receiver)
            ops += CppHoister.Operand(receiver, spillable) { receiverEx(receiver, memberStyle) }
            0
        } else {
            -1
        }
        val shift = ops.size
        ops += argOps
        val lending = recvType != null && fn.name in LENDERS && (CppBindingTable.isArr(recvType) || CppBindingTable.magicName(recvType) == "List") &&
            CppBindingTable.magicName(rc.returnType) == "MutView"
        return hoister.lower(ops, rc.returnType) { texts ->
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
            bindingEx(text)
        }
    }

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
     * Char through `addChar`. Each call is spelled by its own binding.
     */
    fun strBufPieces(receiver: Expr, fn: FnSymbol, text: InterpolatedStringLiteral): List<String> {
        val recv = receiverText(receiver)
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

        /** The methods that lend a MutView from a mutable Arr or List (the typer's MutView lending). */
        private val LENDERS = setOf("from", "slice", "view")

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
