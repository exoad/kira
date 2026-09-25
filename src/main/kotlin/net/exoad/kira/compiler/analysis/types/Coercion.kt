package net.exoad.kira.compiler.analysis.types

/**
 * An implicit conversion the typer inserted at a use site (TypedModel.coercions, keyed by the
 * expression being converted). The C++ emitter spells each one; it never infers one itself.
 */
sealed interface Coercion {
    /** A `T` where a `Maybe<T>` is expected (return, argument, assignment): auto-boxing. [inner] is `T`. */
    data class WrapSome(val inner: KType) : Coercion

    /** `null` where a `Maybe<T>` is expected. [inner] is `T`. */
    data class NoneOf(val inner: KType) : Coercion

    /** A class to its superclass or a trait it implements (a class or trait reference). */
    data class Upcast(val from: KType, val to: KType) : Coercion

    /** A named function used as a value (`adder: Fx<...> = add`). */
    data class FnRef(val fn: FnSymbol) : Coercion

    /** An `Arr<T, N>`, `Arr<T>` or `List<T>` place, or a `MutView<T>`, becoming a `View<T>`. [from] is the source type. */
    data class ToView(val from: KType) : Coercion

    /** A literal `Str` constant (a `const char*` in C++) as the receiver of a member-style binding (R5). */
    data object StrConstReceiver : Coercion
}

/** What an explicit `x as T` does (TypedModel.conversions, keyed by the TypeCastExpr); design R13. */
enum class ConversionKind {
    /** Integer to integer: wraps (`static_cast`). */
    INT_WRAP,

    /** Integer to float. */
    INT_TO_FLOAT,

    /** Float to integer: saturating, NaN to 0 (D9, `kira::as<T>`). */
    FLOAT_TO_INT_SAT,

    /** Float to float. */
    FLOAT_RESIZE,

    /** `Char` to an integer: the code unit, unsigned. */
    CHAR_TO_INT,

    /** An integer to `Char`. */
    INT_TO_CHAR,

    /** An enum to its base type. */
    ENUM_TO_BASE,

    /** Anything showable to `Str` (`kira::text`). */
    TO_STR,
}
