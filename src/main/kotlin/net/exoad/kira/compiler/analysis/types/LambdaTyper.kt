package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr

/**
 * `fx(x: Int32) Int32 { ... }` in expression position (design 3.3, 5.6). A lambda's parameter
 * and return types are written out; against an expected `Fx` they must match it exactly,
 * `mut` parameters included (D24, `Fx<Tuple1<mut T>, Void>`).
 *
 * Its body is typed in its own scope, which sees the enclosing locals. Every enclosing local or
 * parameter it reads is a capture ([Capture.Value]); a struct field read through the receiver
 * is captured as a copy of the field ([Capture.Field]); a struct method call, `this`, or any
 * member of a class or trait captures the receiver ([Capture.This]). Captures are by value and
 * immutable (spec): assigning one, or passing it as `mut`, is `types.lambda.assign-capture`.
 * The list lands in `TypedModel.captures`, in first-use order.
 */
internal class LambdaTyper(private val c: PhaseC) {
    fun lambda(e: LambdaExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val def = e.def
        val params = def.parameters.mapIndexed { i, p ->
            ParamSymbol(
                p.name.value, ctx.module, p,
                type = c.typeOf(p.typeSpecifier),
                byRef = Modifier.MUTABLE in p.modifiers,
                default = p.defaultValue,
                index = i,
            )
        }
        val ret = c.typeOf(def.returnTypeSpecifier)
        val own = KType.Fn(params.map { FnParam(it.type, it.byRef) }, ret)
        val frame = LambdaFrame(e, ctx.lambda, ctx.owner)
        val inner = scope.lambdaBody(frame)
        for (p in params) {
            val decl = p.decl as net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
            c.model.declSyms[decl] = p
            c.model.refs[decl.name] = p
            c.declaredIn[p] = frame
            if (inner.declare(p.name, p) != null) {
                c.report("types.decl.duplicate", "'${p.name}' is already a parameter of this lambda.", decl)
            }
            if (p.default != null) {
                c.report("types.lambda.default", "A lambda's parameters have no defaults; the caller passes every argument.", p.default)
            }
        }
        c.stmts.lambdaBody(def.body ?: emptyList(), ctx.inLambda(frame, ret), inner)
        c.model.captures[e] = frame.captures.toList()
        val expected = hint as? KType.Fn
        if (expected != null && !expected.containsError() && !own.containsError() && expected != own) {
            c.report(
                "types.lambda.mismatch",
                "This lambda is ${own.display()}, but its context expects ${expected.display()}: " +
                    "a lambda's parameter types, their `mut`, and its return type must match exactly.",
                e,
            )
            return KType.Error
        }
        return own
    }
}
