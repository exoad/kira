package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr

/**
 * Writes need a place that changes (stage 4: Mutable bindings and types).
 *
 * - `rules.mutability.local`, `.global`: assigning a local or a global that is not Mutable.
 * - `rules.mutability.param`: assigning a parameter, which is never Mutable.
 * - `rules.mutability.field`: writing a field that is not Mutable (a value type's never is),
 *   but in a `with`'s copy and in `initially`, which still build the value.
 * - `rules.mutability.element`: writing an element of a value that does not change; a shared
 *   container's element changes through any binding.
 * - `rules.mutability.this`: a function writing the receiver of a type that is not Mutable.
 * - `rules.mutability.method`: a value's own changing function (an Arr's `set`) called on a
 *   binding that is not Mutable, or a changing function called on a lent result
 *   (`ys.get(0).add(1)`: R-A makes it a read-only place, never a write). A Mutable type's
 *   functions change it through any binding: its value is shared.
 *
 * A change only sharing allows goes to the model's sharedWrites, which the cpp target refuses.
 * Phase C already reports a write to a lambda's capture (types.lambda.assign-capture) and
 * writes into a View or a Str (types.index.view-write, .str-write): those are not repeated here.
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
                    is FunctionCallExpr -> {
                        mutMethod(r, b, n, lambdas)
                        byRef(r, b, n, lambdas)
                    }
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
        stage4Write(r, b, target, place, lambdas)
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

    /**
     * Stage 4: a List the callee changes is passed by reference (WrittenParams, C++ `T&`); from a binding that is not
     * Mutable, or as a temporary, only sharing lets the change through.
     */
    private fun byRef(r: Rules, b: Body, e: FunctionCallExpr, lambdas: List<LambdaExpr>) {
        val rc = r.model.calls[e] ?: return
        for (a in rc.args) {
            if (a !is ArgBinding.Given || !a.byRef) continue
            val place = r.model.places[a.expr]
            when {
                place == null -> shared(r, b, a.expr, "'${KiraUnparser.text(a.expr)}', no place of its own, passed to '${rc.fn?.name}', which changes it in place (C++ takes it by reference)")
                !oldRulesWrite(r, b, place, lambdas) -> shared(r, b, a.expr, "'${KiraUnparser.text(a.expr)}' passed to '${rc.fn?.name}', which changes it, from a binding that is not Mutable (a shared value the py target lowers)")
            }
        }
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
            if (lent != null) {
                r.report(
                    "rules.mutability.method",
                    "'${fn.name}' changes its receiver '${KiraUnparser.text(rc.receiver!!)}', which is '${r.describe(lent)}' lent by an accessor, not a place " +
                        "to change (R-A). Change the place itself (`${r.describe(lent)}.${fn.name}(...)` on a Mutable binding).",
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
        // Stage 4: only a value's own changing function (an Arr's `set`) reaches here; it needs a Mutable binding.
        r.report("rules.mutability.method", "'${fn.name}' changes its ${receiverType.display()}, a value, so it runs on a Mutable binding only, which $receiverText is not.", at)
    }
}
