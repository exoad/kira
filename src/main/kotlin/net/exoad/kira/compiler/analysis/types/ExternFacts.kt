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
