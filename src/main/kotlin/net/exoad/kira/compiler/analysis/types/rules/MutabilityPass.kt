package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr

/**
 * Writes need a mutable place (design 3.4, D29, D44).
 *
 * - `rules.mutability.local`, `.param`, `.global`: assigning a local, parameter or global
 *   declared without `mut`.
 * - `rules.mutability.field`: assigning a non-`mut` field of a class (a reference: `mut` on
 *   the field decides), or any field of a struct reached through an immutable place.
 * - `rules.mutability.this`: a plain `fx` writing its receiver's own state: a struct's, or a
 *   class's own field (`count = count + 1`; the spec's "methods that modify instance state
 *   must be marked `mut`", and a `const` member function in C++, design 5.5). It needs `mut
 *   fx`. A field of another object reached through a class-typed field (`child.n = 1`) is
 *   that object's, which any reference may write, and so is `this` captured by a lambda of a
 *   class method (`self = shared_from_this()`, design 5.6): the method that wrote the lambda
 *   down modifies nothing itself.
 * - `rules.mutability.element`: writing an element of a container held in an immutable place.
 * - `rules.mutability.method`: a `mut fx` (a struct's own, or a stdlib mutator such as
 *   `List.add`) called on an immutable place or on a lent result (`ys.get(0).add(1)`: R-A makes
 *   it a read-only place, never a write), or a class's own `mut fx` called on `this`
 *   in the body of a plain `fx` of the class (its instance state changes). A class's `mut
 *   fx` is callable through any other reference (D29), and so is a stdlib handle's (a
 *   Suite, a Mutex).
 *
 * Phase C already reports a write to a lambda's capture (types.lambda.assign-capture), a
 * `mut` argument that is not writable (types.call.mut-immutable), and writes into a View or a
 * Str (types.index.view-write, .str-write): those are not repeated here.
 */
internal class MutabilityPass : RulePass {
    override val name: String = "mutability"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            AstScan.walk(b.roots) { n, lambdas ->
                when (n) {
                    is AssignmentExpr -> write(r, b, n.target, lambdas)
                    is CompoundAssignmentExpr -> write(r, b, n.left, lambdas)
                    is PlaceAssignmentExpr -> write(r, b, n.target, lambdas)
                    is FunctionCallExpr -> mutMethod(r, b, n, lambdas)
                    else -> {}
                }
            }
        }
    }

    private fun write(r: Rules, b: Body, target: Expr, lambdas: List<LambdaExpr>) {
        val place = r.model.places[target] ?: return
        if (place is Place.Index && (place.kind == IndexKind.VIEW || place.kind == IndexKind.STR)) {
            return
        }
        if (DialectGates.mutability) {
            stage4Write(r, b, target, place, lambdas)
            return
        }
        if (r.isMutablePlace(place, thisMutable(b, lambdas)) || isCaptureWrite(r, place, b, lambdas)) {
            return
        }
        val text = KiraUnparser.text(target)
        val (code, message) = explain(r, b, place, text)
        r.report("rules.mutability.$code", message, target)
    }

    /**
     * Stage 4: a Mutable field or an element of a shared container changes through any binding (the value is
     * shared); a local or global is reassigned only when Mutable; a parameter never; a value type's field never.
     */
    private fun stage4Write(r: Rules, b: Body, target: Expr, place: Place, lambdas: List<LambdaExpr>) {
        val ok = when (place) {
            // A `with`'s copy: its block may assign every field of it.
            // `initially` still builds the value: it writes its own fields.
            is Place.Field -> (place.receiver as? Place.Local)?.sym?.builds == true || place.sym.isMut ||
                (b.kind == BodyKind.INITIALLY && place.receiver is Place.This)
            is Place.Index -> r.facts.shares(typeOf(r, place.container)) || r.isMutablePlace(place, thisMutable(b, lambdas))
            else -> r.isMutablePlace(place, thisMutable(b, lambdas))
        }
        if (ok && place is Place.Index && !oldRulesWrite(r, b, place, lambdas)) {
            shared(r, b, target, "a change to an element of ${typeOf(r, place.container)?.display()} that is not held in a Mutable binding (a shared value the py target lowers)")
        }
        if (ok || isCaptureWrite(r, place, b, lambdas)) {
            return
        }
        val text = KiraUnparser.text(target)
        val (code, message) = when (place) {
            is Place.Local -> "local" to "'${place.sym.name}' is not Mutable, so $text cannot be assigned; declare it '${place.sym.name}: Mutable<${place.sym.type.display()}>'."
            is Place.Param -> "param" to "Parameter '${place.sym.name}' cannot be assigned: a parameter is never Mutable. Copy it into a Mutable local."
            is Place.Global -> "global" to "'${place.sym.name}' is a module constant; a module-level binding that changes is '${place.sym.name}: Mutable<${place.sym.type.display()}>'."
            is Place.Field -> "field" to "Field '${place.sym.name}' of ${place.sym.owner.name} is not Mutable, so $text cannot be written; " +
                "declare it '${place.sym.name}: Mutable<${place.sym.type.display()}>' in a Mutable type, or build a new value with `with`."
            is Place.Index -> "element" to "$text is an element of ${typeOf(r, place.container)?.display() ?: "a value"}, which does not change; hold a Mutable one."
            is Place.This -> "this" to "${b.what} cannot write its receiver: ${b.owner?.name} is not Mutable."
        }
        r.report("rules.mutability.$code", message, target)
    }

    /** Whether the rules before stage 4 let this write through: a mutable place, not a lambda's capture. */
    private fun oldRulesWrite(r: Rules, b: Body, place: Place, lambdas: List<LambdaExpr>): Boolean =
        r.isMutablePlace(place, thisMutable(b, lambdas)) && !capturedRoot(r, place, lambdas)

    /** [place] starts at a variable the innermost lambda captured by value (C++ copies it in). */
    private fun capturedRoot(r: Rules, place: Place, lambdas: List<LambdaExpr>): Boolean {
        val lambda = lambdas.lastOrNull() ?: return false
        val captures = r.model.captures[lambda] ?: return false
        val root = place.root()
        val sym = (root as? Place.Local)?.sym ?: (root as? Place.Param)?.sym ?: return false
        return captures.any { it is Capture.Value && it.symbol === sym }
    }

    /** Records [node] as a change only stage 4's sharing allows, which the cpp target refuses. */
    private fun shared(r: Rules, b: Body, node: ASTNode, what: String) {
        r.model.sharedWrites.putIfAbsent(node, b.module to what)
    }

    /** The declared type of the value at [p], as far as a write needs it. */
    private fun typeOf(r: Rules, p: Place): KType? = when (p) {
        is Place.Local -> p.sym.type
        is Place.Param -> p.sym.type
        is Place.Global -> p.sym.type
        is Place.Field -> p.sym.type
        is Place.This -> (p.owner as? ClassSymbol)?.selfType
        is Place.Index -> typeOf(r, p.container)?.let { t ->
            val args = (t as? KType.Nominal)?.typeArgs().orEmpty()
            if (r.facts.isMap(t)) args.getOrNull(1) else args.firstOrNull()
        }
    }

    /** The reason a place is immutable: the first immutable step from the root. */
    private fun explain(r: Rules, b: Body, place: Place, text: String): Pair<String, String> = when (place) {
        is Place.Local -> "local" to "'${place.sym.name}' is not mut, so $text cannot be written; declare it `mut ${place.sym.name}: ${place.sym.type.display()}`."
        is Place.Param -> "param" to "Parameter '${place.sym.name}' is passed by value and cannot be written; take it as `mut ${place.sym.name}: ${place.sym.type.display()}` (D4) to write the caller's variable, or copy it into a mut local."
        is Place.Global -> "global" to "'${place.sym.name}' is a module constant; a module-level binding without `mut` is fixed at compile time. Declare it `pub mut ${place.sym.name}` for state."
        is Place.This -> "this" to "${b.what} is a plain `fx` of the ${ownerKind(b)} ${b.owner?.name}, so its receiver is read-only; declare it `mut fx` to write $text."
        is Place.Field -> {
            val owner = place.sym.owner
            val receiver = place.receiver
            when {
                owner is ClassSymbol && owner.kind == ClassKind.STRUCT && receiver != null && !r.isMutablePlace(receiver, b.thisMutable) ->
                    if (receiver is Place.This) {
                        "this" to "${b.what} is a plain `fx` of the ${ownerKind(b)} ${b.owner?.name}, so its receiver is read-only; declare it `mut fx` to write $text."
                    } else {
                        val (inner, why) = explain(r, b, receiver, text)
                        (if (inner == "this") "this" else "field") to why
                    }
                receiver is Place.This && !b.thisMutable && place.sym.isMut ->
                    "this" to "${b.what} is a plain `fx` of the ${ownerKind(b)} ${b.owner?.name}, so its own state is read-only (a `const` method in C++); declare it `mut fx` to write $text."
                else -> "field" to "Field '${place.sym.name}' of ${owner.name} is not mut, so $text cannot be written; declare it `mut ${place.sym.name}: ${place.sym.type.display()}`."
            }
        }
        is Place.Index -> {
            val (inner, why) = explain(r, b, place.container, text)
            (if (inner == "this") "this" else "element") to why
        }
    }

    private fun ownerKind(b: Body): String = if (b.isStructOwner) "struct" else "class"

    /**
     * Whether the receiver's own state may be written here: in the body, [Body.thisMutable];
     * inside a lambda of a class method, always. The lambda captures `this` as a reference
     * (`self = shared_from_this()`, design 5.6; a struct's lambda copies `*this`, and phase C
     * reports its writes), and a class's `mut fx` and `mut` fields are reachable through any
     * reference (D29): the method that wrote the lambda down modifies nothing itself.
     */
    private fun thisMutable(b: Body, lambdas: List<LambdaExpr>): Boolean = b.thisMutable || (!b.isStructOwner && lambdas.isNotEmpty())

    /** A write phase C reported as writing a lambda's capture, so nothing is said twice. */
    private fun isCaptureWrite(r: Rules, place: Place, b: Body, lambdas: List<LambdaExpr>): Boolean {
        val lambda = lambdas.lastOrNull() ?: return false
        val captures = r.model.captures[lambda] ?: return false
        return when (val root = place.root()) {
            is Place.Local -> captures.any { it is Capture.Value && it.symbol === root.sym }
            is Place.Param -> captures.any { it is Capture.Value && it.symbol === root.sym }
            is Place.This -> b.isStructOwner
            is Place.Field -> false
            else -> false
        }
    }

    private fun mutMethod(r: Rules, b: Body, e: FunctionCallExpr, lambdas: List<LambdaExpr>) {
        val rc = r.model.calls[e] ?: return
        val fn = rc.fn ?: return
        if (!fn.isMutMethod) {
            return
        }
        val receiverType = when {
            rc.implicitThis -> b.owner?.let { owner -> (owner as? ClassSymbol)?.selfType } ?: return
            else -> r.model.types[rc.receiver ?: return] ?: return
        }
        val place = if (rc.implicitThis) b.owner?.let { Place.This(it) } else r.model.places[rc.receiver!!]
        // Stage 4: a Mutable type's value is shared, so its functions change it through any binding.
        if (r.facts.shares(receiverType)) {
            val lent = place == null && rc.receiver?.let { r.model.lentPlaces[it] } != null
            if (!r.isReference(receiverType) && (lent || (place != null && !oldRulesWrite(r, b, place, lambdas)))) {
                shared(r, b, rc.receiver ?: e, "'${fn.name}' changing ${receiverType.display()} through a binding that is not Mutable (a shared value the py target lowers)")
            }
            return
        }
        val thisMutable = thisMutable(b, lambdas)
        if (!r.needsMutablePlace(receiverType)) {
            // A class's `mut fx` is callable through any reference (D29), but a plain `fx` of the class calling
            // one on its own `this` modifies its instance state (the spec's rule for `mut fx`).
            if (place !is Place.This || thisMutable) {
                return
            }
        }
        if (place == null) {
            // A lent result (R-A: `ys.get(0)` is `ys[0]`, and C++'s `kira::at` hands back a reference into ys) is no
            // temporary: a `mut fx` on it would write ys through an accessor, a write capability R-A never grants.
            val lent = rc.receiver?.let { r.model.lentPlaces[it] }
            if (lent != null && DialectGates.mutability) {
                r.report(
                    "rules.mutability.method",
                    "'${fn.name}' changes its receiver '${KiraUnparser.text(rc.receiver!!)}', which is '${r.describe(lent)}' lent by an accessor, not a place " +
                        "to change (R-A). Change the place itself (`${r.describe(lent)}.${fn.name}(...)` on a Mutable binding).",
                    rc.receiver,
                )
            } else if (lent != null) {
                r.report(
                    "rules.mutability.method",
                    "'${fn.name}' is a `mut fx` and writes its receiver '${KiraUnparser.text(rc.receiver!!)}', which is '${r.describe(lent)}' lent by an " +
                        "accessor, not a place to write (R-A). Write the place itself (`${r.describe(lent)}.${fn.name}(...)` on a `mut` binding).",
                    rc.receiver,
                )
            }
            // A temporary: nothing observable is written.
            return
        }
        if (r.isMutablePlace(place, thisMutable) || isCaptureWrite(r, place, b, lambdas)) {
            return
        }
        val at: ASTNode = rc.receiver ?: (e.name as? MemberAccessExpr)?.member ?: e.name
        val receiverText = rc.receiver?.let { KiraUnparser.text(it) } ?: "the receiver"
        if (DialectGates.mutability) {
            // Stage 4: only a value's own changing function (an Arr's `set`) reaches here; it needs a Mutable binding.
            r.report("rules.mutability.method", "'${fn.name}' changes its ${receiverType.display()}, a value, so it runs on a Mutable binding only, which $receiverText is not.", at)
            return
        }
        val (code, why) = explain(r, b, place, receiverText)
        val message = if (code == "this") {
            "'${fn.name}' is a `mut fx` and writes its receiver, but ${b.what} is a plain `fx` of the ${ownerKind(b)} ${b.owner?.name}; declare it `mut fx`."
        } else {
            "'${fn.name}' is a `mut fx` of ${receiverType.display()} and writes its receiver, which must be a mutable place (D44). $why"
        }
        r.report("rules.mutability.method", message, at)
    }
}
