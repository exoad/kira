package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

/**
 * What a type is, asked the same way everywhere in phase C. A magic container is recognised
 * by its magic [ClassSymbol]'s name (`Maybe`, `Arr`, `View`, ...), never by a user class that
 * happens to share the name: that one is an ordinary nominal and shadows the builtin.
 */
internal class TypeFacts(private val builtins: Builtins) {
    /** The builtin name of a magic nominal (`Maybe`, `Arr`, `Str`'s class is not one: Str is its own KType). */
    fun magicName(t: KType): String? {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return null
        return if (sym.kind == ClassKind.MAGIC) sym.name else null
    }

    fun isMagic(t: KType, name: String): Boolean = magicName(t) == name

    fun isMaybe(t: KType): Boolean = isMagic(t, "Maybe")

    /** `T` of `Maybe<T>`, else null. */
    fun maybeInner(t: KType): KType? = if (isMaybe(t)) (t as KType.Nominal).typeArgs().firstOrNull() else null

    fun maybeOf(inner: KType): KType = nominal("Maybe", inner)

    fun viewOf(element: KType): KType = nominal("View", element)

    fun mutViewOf(element: KType): KType = nominal("MutView", element)

    fun arrOf(element: KType): KType = nominal("Arr", element)

    fun tuple2Of(a: KType, b: KType): KType = nominal("Tuple2", a, b)

    private fun nominal(name: String, vararg args: KType): KType {
        val cls = builtins.classFor(name) ?: return KType.Error
        return KType.Nominal(cls, args.map { TypeArg.Ty(it) })
    }

    /** `Arr<T>` or `Arr<T, N>`. */
    fun isArr(t: KType): Boolean = isMagic(t, "Arr")

    fun isFixedArr(t: KType): Boolean = isArr(t) && (t as KType.Nominal).constArgs().isNotEmpty()

    fun isList(t: KType): Boolean = isMagic(t, "List")

    fun isView(t: KType): Boolean = isMagic(t, "View")

    fun isMutView(t: KType): Boolean = isMagic(t, "MutView")

    fun isMap(t: KType): Boolean = isMagic(t, "Map")

    fun isStrBuf(t: KType): Boolean = isMagic(t, "StrBuf")

    /** The first type argument of a magic container (its element), else null. */
    fun elementOf(t: KType): KType? = (t as? KType.Nominal)?.typeArgs()?.firstOrNull()

    /** A user class (or an extern class): a reference type held by Rc. */
    fun isClass(t: KType): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.CLASS || sym.kind == ClassKind.OPAQUE
    }

    fun isTrait(t: KType): Boolean = (t as? KType.Nominal)?.sym is TraitSymbol

    fun isStruct(t: KType): Boolean = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.kind == ClassKind.STRUCT

    fun isEnum(t: KType): Boolean = (t as? KType.Nominal)?.sym is EnumSymbol

    /**
     * A value whose copies share one object: a class or trait reference, a `Ref<T>` (D46), a
     * `Weak<T>`, an `Unsafe<T>`. Writing through one does not write a copy. A type parameter
     * is one when a bound of it is a class: only that class and its subclasses satisfy the
     * bound, and every one is an `Rc`. A trait bound is not enough (a struct may implement
     * the trait, and a struct is copied).
     */
    fun isReference(t: KType): Boolean = when (t) {
        is KType.Param -> boundNominals(t).any { isClass(it) }
        else -> isClass(t) || isTrait(t) || isMagic(t, "Ref") || isMagic(t, "Weak") || isMagic(t, "Unsafe")
    }

    /**
     * The nominal bounds of a type parameter, in declaration order, a bound that is itself a
     * type parameter replaced by its own bounds. A cycle (`<T: T>`, `<T: U, U: T>`, which
     * phase B does not refuse) contributes nothing past its first visit, so the walk ends.
     */
    fun boundNominals(p: KType.Param): List<KType.Nominal> {
        val out = mutableListOf<KType.Nominal>()
        val seen = HashSet<TypeParamSymbol>()
        fun walk(q: KType.Param) {
            if (!seen.add(q.sym)) {
                return
            }
            for (b in q.sym.bounds) {
                when (b) {
                    is KType.Nominal -> out.add(b)
                    is KType.Param -> walk(b)
                    else -> {}
                }
            }
        }
        walk(p)
        return out
    }

    /**
     * A literal type (C++ [basic.types]) on the gcc 11.4 floor, so a constant of it is
     * `inline constexpr` and a value of it can take part in a constant expression: the
     * scalars, `Bool`, `Char`, an enum, `Arr<T, N>` (std::array), a tuple or a `Maybe` of
     * literal types, `View`, `MutView`, `Unsafe`, and a struct whose fields are all literal.
     * Not `Str` (std::string), not `Arr<T>` or `List<T>` (std::vector: constexpr only from GCC
     * 12), not a Map, Set, Deque, StrBuf, class, trait or Fx: a global of one of those is
     * `inline const`, built at run time, and reading it is no constant. This is the same rule
     * the C++ emitter's CppDeclEmitter spells a global by; the two must agree.
     */
    fun isLiteralType(t: KType): Boolean = when (t) {
        is KType.Scalar -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> true
            is ClassSymbol -> when {
                sym.kind == ClassKind.MAGIC -> when {
                    sym.name == Builtins.ARR && t.args.size >= 2 -> t.typeArgs().all { isLiteralType(it) }
                    sym.name == "Maybe" || Builtins.tupleArity(sym.name) != null -> t.typeArgs().all { isLiteralType(it) }
                    sym.name == "View" || sym.name == "MutView" || sym.name == "Unsafe" -> true
                    else -> false
                }
                sym.kind == ClassKind.STRUCT -> sym.fields.all { isLiteralType(it.type) }
                else -> false
            }
            else -> false
        }
        else -> false
    }

    fun isInteger(t: KType): Boolean = t.prim?.isInteger == true

    fun isFloat(t: KType): Boolean = t.prim?.isFloat == true

    fun isNumeric(t: KType): Boolean = isInteger(t) || isFloat(t)

    /**
     * A type that `"${e}"`, `e as Str` and the print family can format (D42): every scalar,
     * `Char`, `Bool`, `Str` and an enum.
     */
    fun isShowable(t: KType): Boolean = t is KType.Scalar || t == KType.Str || isEnum(t)

    /**
     * Every direct parent of [t] with [t]'s type arguments substituted: a class's superclass and
     * traits, a trait's parent traits. A magic class's parents count too (`Int32: Num`).
     */
    fun parents(t: KType.Nominal): List<KType.Nominal> {
        return when (val sym = t.sym) {
            is ClassSymbol -> {
                val sub = sym.typeParams.zip(t.typeArgs()).toMap()
                (listOfNotNull(sym.superclass) + sym.traits).map { it.substitute(sub) as KType.Nominal }
            }
            is TraitSymbol -> {
                val sub = sym.typeParams.zip(t.typeArgs()).toMap()
                sym.parents.map { it.substitute(sub) as KType.Nominal }
            }
            else -> emptyList()
        }
    }

    /** True when [from] is [to] or reaches it through [parents]. */
    fun isSubtype(from: KType.Nominal, to: KType.Nominal): Boolean {
        val seen = HashSet<KType>()
        val queue = ArrayDeque<KType.Nominal>()
        queue.add(from)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n == to) {
                return true
            }
            if (!seen.add(n)) {
                continue
            }
            queue.addAll(parents(n))
        }
        return false
    }

    /** The [TraitSymbol] or [ClassSymbol] a type-parameter bound names, satisfied by [t]? */
    fun satisfies(t: KType, bound: KType): Boolean {
        if (t == bound || t.containsError() || bound.containsError()) {
            return true
        }
        val boundNominal = bound as? KType.Nominal ?: return false
        val self: KType.Nominal = when (t) {
            is KType.Nominal -> t
            else -> builtins.classOf(t)?.let { cls ->
                KType.Nominal(cls, emptyList())
            } ?: return false
        }
        return isSubtype(self, boundNominal)
    }
}

/**
 * Whether a value of one type may stand where another is expected, and the implicit
 * conversion that makes it so (design 3.3): `null` into `Maybe<T>` ([Coercion.NoneOf]), a `T`
 * into `Maybe<T>` ([Coercion.WrapSome], at every use site), a class to its superclass or a
 * trait it implements ([Coercion.Upcast]), and an `Arr`/`List` place or a `MutView` (or a
 * string literal, for `View<Char>`) into a `View` ([Coercion.ToView]). Nothing else converts
 * implicitly: no numeric widening, no int to float.
 *
 * A `T` whose class is a subclass of `Maybe`'s inner type is one [Coercion.WrapSome] whose
 * `inner` differs from the expression's own type: the upcast is implied (a nullable Rc of the
 * base, in C++).
 */
internal class CoercionRules(private val c: PhaseC) {
    private val facts get() = c.facts

    sealed interface Fit {
        /** Already the expected type. */
        data object Same : Fit

        data class Coerce(val coercion: Coercion) : Fit

        /** Does not fit; [reason] explains why when there is more to say than the two types. */
        data class No(val reason: String? = null, val code: String = "types.assign.mismatch") : Fit
    }

    /** Whether [actual] fits [expected]. [e] is the expression (its shape and place matter for views). */
    fun fit(e: Expr, actual: KType, expected: KType): Fit {
        if (actual == expected || actual.containsError() || expected.containsError() || actual == KType.Never) {
            return Fit.Same
        }
        if (facts.isMaybe(expected)) {
            val inner = facts.maybeInner(expected) ?: return Fit.Same
            if (actual == KType.NullT) {
                return Fit.Coerce(Coercion.NoneOf(inner))
            }
            val actualInner = facts.maybeInner(actual)
            if (actualInner != null) {
                val a = actualInner as? KType.Nominal
                val b = inner as? KType.Nominal
                if (a != null && b != null && (facts.isClass(a) || facts.isTrait(a)) && facts.isSubtype(a, b)) {
                    return Fit.Coerce(Coercion.Upcast(actual, expected))
                }
                return Fit.No()
            }
            return when (val f = fit(e, actual, inner)) {
                Fit.Same -> Fit.Coerce(Coercion.WrapSome(inner))
                is Fit.Coerce -> if (f.coercion is Coercion.Upcast) Fit.Coerce(Coercion.WrapSome(inner)) else Fit.No(
                    "a value becomes a Maybe only as itself or through an upcast"
                )
                is Fit.No -> f
            }
        }
        if (actual == KType.NullT) {
            return Fit.No(
                "null is a value of Maybe<T> only; declare the slot as Maybe<${expected.display()}>",
                "types.null.not-maybe",
            )
        }
        if (actual is KType.Nominal && expected is KType.Nominal) {
            if ((facts.isClass(actual) || facts.isTrait(actual)) && (facts.isClass(expected) || facts.isTrait(expected))) {
                if (facts.isSubtype(actual, expected)) {
                    return Fit.Coerce(Coercion.Upcast(actual, expected))
                }
                return Fit.No()
            }
            if (facts.isStruct(actual) && facts.isTrait(expected) && facts.isSubtype(actual, expected)) {
                return Fit.No(
                    "struct ${actual.display()} implements ${expected.display()}, but a struct is not boxed into a trait " +
                        "value (D43); take it through a generic parameter bounded by the trait",
                    "types.assign.struct-to-trait",
                )
            }
        }
        if (facts.isView(expected)) {
            val element = facts.elementOf(expected) ?: return Fit.No()
            if ((facts.isArr(actual) || facts.isList(actual) || facts.isMutView(actual)) && facts.elementOf(actual) == element) {
                if (facts.isMutView(actual) || c.model.places[e] != null) {
                    return Fit.Coerce(Coercion.ToView(actual))
                }
                return Fit.No(
                    "a View of a temporary ${actual.display()} would dangle; store it in a local first",
                    "types.view.temporary",
                )
            }
            if (actual == KType.Str && element == KType.CHAR && e is StringLiteral) {
                return Fit.Coerce(Coercion.ToView(KType.Str))
            }
            if (actual == KType.Str && element == KType.CHAR) {
                return Fit.No("a Str becomes a View<Char> explicitly, with s.view()")
            }
        }
        return Fit.No()
    }

    /**
     * Fits [e] (of type [actual]) into [expected]: records the coercion, or reports a mismatch at
     * [e] ([what] says where: "the return value", "argument 'x'"). Returns true when it fits.
     */
    fun assign(e: Expr, actual: KType, expected: KType, what: String): Boolean {
        return when (val f = fit(e, actual, expected)) {
            Fit.Same -> true
            is Fit.Coerce -> {
                c.model.coercions[e] = f.coercion
                true
            }
            is Fit.No -> {
                val base = "$what expects ${expected.display()}, but this is ${actual.display()}"
                c.report(f.code, if (f.reason != null) "$base: ${f.reason}." else "$base.", e)
                false
            }
        }
    }
}
