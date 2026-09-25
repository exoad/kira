package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/** How a call reaches its callee; the C++ emitter picks the call form from this alone. */
enum class CallKind {
    /** A module function, called directly. */
    FREE,

    /** A non-virtual method on a class or struct receiver. */
    METHOD,

    /** A method dispatched through a vtable (overridden somewhere in the program). */
    VIRTUAL,

    /** A trait method on a trait-typed receiver. */
    TRAIT,

    /** A `@_magic` function or method; the binding table (`*.bind.yaml`) spells it. */
    MAGIC,

    /** An `@_extern` function or method; FFI proxies apply (design 7.2). */
    EXTERN,

    /** A call through an `Fx` value (a local, parameter or field of function type). */
    FN_VALUE,

    /** `trace`, `print`, `println` and `eprint`: formatted from the argument's type. */
    PRINT,

    /** An operator whose operand type declares `@op_*` (TypedModel.opCalls). */
    OP_OVERLOAD,

    /** Construction through call syntax, where the language allows it. */
    CTOR,
}

/**
 * A call, fully resolved (TypedModel.calls, keyed by the FunctionCallExpr, and opCalls).
 *
 * @property fn the callee, or null for [CallKind.FN_VALUE] and [CallKind.PRINT].
 * @property receiver the receiver expression of a method call (`xs` in `xs.add(v)`), else null.
 * @property implicitThis a method of the enclosing class called without a receiver.
 * @property typeArgs the explicit (or `@_infer`red) type arguments, in the callee's type-parameter order.
 * @property args one binding per callee parameter, in **parameter** order.
 * @property sourceOrder indices into [args] in the order the arguments were **written** (D33:
 *   left-to-right evaluation); defaults are absent from it.
 * @property returnType the return type after [substitution].
 * @property substitution the callee's type parameters (and a generic receiver's) to their arguments.
 */
data class ResolvedCall(
    val kind: CallKind,
    val fn: FnSymbol?,
    val receiver: Expr?,
    val implicitThis: Boolean,
    val typeArgs: List<KType>,
    val args: List<ArgBinding>,
    val sourceOrder: List<Int>,
    val returnType: KType,
    val substitution: Map<TypeParamSymbol, KType> = emptyMap(),
)

/** How one parameter of a call gets its value. */
sealed interface ArgBinding {
    /** An argument the call wrote. [byRef] is the call-site `mut` on a `mut` parameter (D4). */
    data class Given(val expr: Expr, val byRef: Boolean) : ArgBinding

    /**
     * The parameter's default. [trailing] when every later parameter is defaulted too, so the
     * C++ default argument covers it; a skipped middle default is filled in at the call (R6).
     */
    data class Default(val param: ParamSymbol, val trailing: Boolean) : ArgBinding
}

/**
 * A construction `T { ... }` (TypedModel.inits, keyed by the ObjectInitExpr).
 *
 * @property type the constructed type (a Nominal, with its type arguments).
 * @property cls the class or struct; for a magic container it is the magic class.
 * @property fields one entry per field of [cls] (inherited ones first), in declaration order.
 * @property sourceOrder indices into [fields] in the order the arguments were written.
 * @property substitution the class's type parameters to [type]'s arguments.
 */
data class ResolvedInit(
    val type: KType,
    val cls: ClassSymbol?,
    val fields: List<FieldInit>,
    val sourceOrder: List<Int>,
    val substitution: Map<TypeParamSymbol, KType> = emptyMap(),
)

/** How one field of a construction gets its value. */
sealed interface FieldInit {
    val field: FieldSymbol

    /** Written at the construction, positionally or [named]. */
    data class Given(override val field: FieldSymbol, val expr: Expr, val named: Boolean) : FieldInit

    /** Left out: the field's default applies (or value-initialization for a defaultless field, D38). */
    data class Default(override val field: FieldSymbol) : FieldInit
}

/**
 * What a member access `a.b` names (TypedModel.members, keyed by the MemberAccessExpr). The
 * access form (R4: `->`, `.`, `E::F`, `::ns::f`) follows from the receiver's type and this.
 */
sealed interface MemberRef {
    /** A field; [type] is the field's type after the receiver's substitution. */
    data class Field(val field: FieldSymbol, val type: KType) : MemberRef

    /** A method, called or used as a value; [type] is its function type after substitution. */
    data class Method(val fn: FnSymbol, val type: KType) : MemberRef

    /** `E.F`. */
    data class EnumEntry(val entry: EnumEntrySymbol) : MemberRef

    /** A member of another module reached through the module's name. */
    data class ModuleMember(val module: ModuleSymbol, val symbol: Symbol) : MemberRef
}
