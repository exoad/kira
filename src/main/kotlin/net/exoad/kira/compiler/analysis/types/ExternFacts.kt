package net.exoad.kira.compiler.analysis.types

/**
 * Whether C++ supplies this function's body (40-round3 R-G, the one predicate every reader
 * asks): an `@_extern` function or method (a member of an `@_extern` class carries
 * `Foreign.Extern` too), a bodiless `pub` free prototype, and a bodiless method of an
 * `@_opaque` class. A bodiless private prototype is none of these (the typer refuses it), nor
 * is a bodiless method of a class or trait (a slot a construction or an override fills), nor
 * a `@_magic` binding (the manifests spell it).
 *
 * Readers: ViewPass (an extern's signature, 1.4 and 5.2), EffectsPass (IMPURE), W2.6's
 * CallResolver and C++ call lowering (every such call goes through `CppExternEmitter.call`),
 * and W2.4, whose wider question "may C++ supply the override" is this or a VIRTUAL/TRAIT
 * dispatch. R-C's `CallReach.bodyUnknown` reads it too.
 */
val FnSymbol.suppliedByCpp: Boolean
    get() {
        if (foreign is Foreign.Extern) {
            return true
        }
        if (foreign != null || body != null || hasBody) {
            return false
        }
        return (owner == null && isPub) || (owner as? ClassSymbol)?.kind == ClassKind.OPAQUE
    }

/**
 * Whether `T { }` builds this `@_extern` struct by its C++ default constructor (`@_constructible`).
 * Such a value is never copied by Kira (rules.extern.copy, ExternObjectPass).
 */
val ClassSymbol.isConstructibleExtern: Boolean
    get() = foreign is Foreign.Extern && markers.any { it.name == DeclarationCollector.CONSTRUCTIBLE }

/** An `@_extern` struct: a C++ value Kira holds, whose address an extern may take for its call. */
val ClassSymbol.isExternStruct: Boolean
    get() = isStruct && foreign is Foreign.Extern

/**
 * Whether `T { field = v }` builds this `@_extern` struct from the fields Kira declares (a C
 * aggregate, laid out as the layout check proves).
 */
val ClassSymbol.isAggregateExtern: Boolean
    get() = isExternStruct && fields.isNotEmpty()

/**
 * Whether this method of an `@_extern` struct names a C function (`@_extern(c = "f")` and no
 * C++ name): C has no members, so the call is `f(&receiver, args)`, the receiver a `T*` when
 * the method is `@_mutates` and a `const T*` otherwise.
 */
val FnSymbol.takesReceiverByPointer: Boolean
    get() {
        val params = (foreign as? Foreign.Extern)?.params ?: return false
        val owner = owner as? ClassSymbol ?: return false
        return owner.isExternStruct && "c" in params && "cpp" !in params && "symbol" !in params
    }

/**
 * The C member a field of an `@_extern` struct names when it differs from the Kira name
 * (`@_extern(c = "pio_version") pioVersion: UInt8`), or null when the two are the same.
 */
val FieldSymbol.externMember: String?
    get() = markers.firstOrNull { it.name == DeclarationCollector.EXTERN }?.let { m -> m.namedParameters["c"]?.let { m.text(it) } }

/**
 * A C `const T*` member of an aggregate `@_extern` struct (`x: Unsafe<T>`): it points only at
 * a module-level constant `Arr` (ExternPointerPass), static storage, so the struct holds no
 * view. A struct has no Mutable field (types.mutable.field).
 */
val FieldSymbol.isStaticPointer: Boolean
    get() = (owner as? ClassSymbol)?.isAggregateExtern == true &&
        ((type as? KType.Nominal)?.sym as? ClassSymbol)?.let { it.kind == ClassKind.MAGIC && it.name == "Unsafe" } == true
