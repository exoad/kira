package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import java.util.IdentityHashMap

/**
 * An `@_const` function lowers to `constexpr` (D7), and clang and MSVC reject a constexpr
 * function that can never be constant [A-M7]. So its body, on the gcc 11.4 floor, uses only
 * literal types (TypeFacts.isLiteralType: the scalars, `Char`, `Bool`, enums, `Arr<T, N>`,
 * `View`/`MutView`, tuples and `Maybe` of those, and structs of those; never `Str`,
 * `Arr<T>`/`List<T>` (std::vector), a `Map`, a class or a trait reference), calls only
 * `@_const` functions and bindings marked `constexpr: true`, and has no `throw`, `try`,
 * extern call, print, or global mutation. Each refusal names the construct.
 *
 * - `rules.const.type`: a parameter, local, return or intermediate value of a non-literal
 *   type. An `Fx` parameter that does not escape is a template parameter and is allowed; a
 *   generic `T` is allowed (a constexpr template is checked at instantiation).
 * - `rules.const.call`: a call to a function that is not `@_const`, or to a magic binding
 *   that is not `constexpr`, a virtual or trait call, or a call through an `Fx` that is not
 *   a parameter.
 * - `rules.const.extern`: an extern call.
 * - `rules.const.trace`: `trace`, `print`, `println`, `eprint` or `@_trace_`.
 * - `rules.const.throw`, `rules.const.try`.
 * - `rules.const.global`: writing a global, or reading a `mut` one (never usable in a
 *   constant expression).
 * - `rules.const.lambda`: a lambda anywhere but as the direct argument of a non-escaping
 *   `Fx` parameter.
 */
internal class ConstEligibilityPass : RulePass {
    override val name: String = "const"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            val fn = b.fn ?: continue
            if (fn.isConst) {
                Check(r, b, fn).run()
            }
        }
    }

    private class Check(private val r: Rules, private val b: Body, private val fn: FnSymbol) {
        private val model = r.model
        private val facts = r.facts
        private val what = "@_const ${b.what}"

        /** Lambdas that are the direct argument of a non-escaping Fx parameter: template arguments, allowed. */
        private val templateLambdas = IdentityHashMap<LambdaExpr, Boolean>()

        /** A thrown value: the throw is the refusal, not the Str it carries. */
        private val thrown = IdentityHashMap<Expr, Boolean>()

        fun run() {
            for (p in fn.params) {
                if (p.type is KType.Fn) {
                    if (model.fxEscapes(p)) {
                        r.report("rules.const.type", "$what: parameter '${p.name}' is an Fx that escapes, so it is a std::function, which is no literal type. Keep it a parameter that is only called.", p.decl)
                    }
                } else {
                    literal(p.type, p.decl, "parameter '${p.name}'")
                }
            }
            if (fn.ret != KType.Void) {
                literal(fn.ret, fn.decl?.def?.returnTypeSpecifier ?: fn.decl, "the return type")
            }
            AstScan.walk(b.roots) { n, _ -> node(n) }
        }

        private fun literal(t: KType, at: ASTNode?, whatIs: String): Boolean {
            if (t.containsError() || t is KType.Param || t == KType.Never || facts.isLiteralType(t)) {
                return true
            }
            r.report("rules.const.type", "$what: $whatIs is ${t.display()}, which is no literal type in C++ (${nonLiteral(t)}), so the function could never be evaluated at compile time.", at)
            return false
        }

        private fun nonLiteral(t: KType): String = when {
            t == KType.Str -> "std::string allocates"
            facts.isList(t) || (facts.isArr(t) && !facts.isFixedArr(t)) -> "std::vector is constexpr only from GCC 12; use Arr<T, N>"
            facts.isClass(t) || facts.isTrait(t) -> "a class reference is an Rc on the heap; use a struct"
            facts.isStruct(t) -> "one of its fields is not"
            t is KType.Fn -> "a std::function"
            else -> "it lives on the heap"
        }

        private fun node(n: ASTNode) {
            when (n) {
                is VariableDecl -> {
                    val t = model.typeRefs[n.type] ?: return
                    literal(t, n.type, "local '${n.name.value}'")
                }
                is ThrowExpr -> {
                    thrown[n.value] = true
                    r.report("rules.const.throw", "$what: a throw is no constant expression; return a Maybe or a Result instead.", n)
                }
                is TryExpr -> r.report("rules.const.try", "$what: try is no constant expression.", n)
                is IntrinsicExpr -> if (n.intrinsicKey.name == "_trace_") r.report("rules.const.trace", "$what: @_trace_ prints, which no constant expression can.", n)
                is LambdaExpr -> if (!templateLambdas.containsKey(n)) {
                    r.report("rules.const.lambda", "$what: a lambda here is a std::function; a lambda is allowed only as the argument of an Fx parameter that is only called.", n)
                }
                is FunctionCallExpr -> call(n)
                is AssignmentExpr -> globalWrite(model.places[n.target], n.target)
                is CompoundAssignmentExpr -> globalWrite(model.places[n.left], n.left)
                is PlaceAssignmentExpr -> globalWrite(model.places[n.target], n.target)
                is Identifier -> globalRead(n)
                is MemberAccessExpr -> (model.members[n] as? MemberRef.ModuleMember)?.symbol?.let { s -> if (s is GlobalSymbol && s.isMut) mutGlobal(s, n) }
                is InterpolatedStringLiteral -> r.report("rules.const.type", "$what: \"\${...}\" builds a Str, which is no literal type in C++ (std::string allocates).", n)
                else -> {}
            }
            if (n is Expr && n !is Type && n !is Identifier && n !is LambdaExpr && !thrown.containsKey(n)) {
                value(n)
            }
        }

        /** An intermediate value of a non-literal type, reported at the expression that produces it. */
        private fun value(e: Expr) {
            val t = model.types[e] ?: return
            if (t == KType.Void || t == KType.Never || t is KType.Param || t.containsError() || facts.isLiteralType(t)) {
                return
            }
            if (e is FunctionCallExpr && (model.calls[e]?.kind == CallKind.FN_VALUE)) {
                return
            }
            if (e is BinaryExpr || e is UnaryExpr) {
                // Its operands were reported.
                return
            }
            r.report("rules.const.type", "$what: ${KiraUnparser.text(e)} is ${t.display()}, which is no literal type in C++ (${nonLiteral(t)}).", e)
        }

        private fun call(e: FunctionCallExpr) {
            val rc = model.calls[e] ?: return
            val callee = rc.fn
            val at: ASTNode = (e.name as? MemberAccessExpr)?.member ?: e.name
            when (rc.kind) {
                CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> if (callee != null && !callee.isConst) {
                    r.report("rules.const.call", "$what calls '${callee.name}', which is not @_const; mark it @_const, or take its result as a parameter.", at)
                }
                CallKind.MAGIC -> if (callee != null && !r.bindings.isConstexpr(callee)) {
                    r.report("rules.const.call", "$what calls '${callee.name}', whose C++ binding is not constexpr; compute it at run time and pass the result in.", at)
                }
                CallKind.VIRTUAL, CallKind.TRAIT -> r.report("rules.const.call", "$what calls '${callee?.name}' through a vtable, which is no constant expression on a heap reference.", at)
                CallKind.EXTERN -> r.report("rules.const.extern", "$what calls the extern '${callee?.name}', which C++ cannot evaluate at compile time.", at)
                CallKind.PRINT -> r.report("rules.const.trace", "$what calls '${callee?.name ?: "trace"}', which prints; no constant expression can.", at)
                CallKind.FN_VALUE -> {
                    val target = e.name
                    val sym = (target as? Identifier)?.let { model.refs[it] }
                    if (sym !is ParamSymbol) {
                        r.report("rules.const.call", "$what calls ${KiraUnparser.text(target)}, a std::function; only an Fx parameter (a template parameter) can be called here.", at)
                    }
                }
            }
            if (callee != null) {
                rc.args.forEachIndexed { i, a ->
                    val given = a as? ArgBinding.Given ?: return@forEachIndexed
                    val p = callee.params.getOrNull(i) ?: return@forEachIndexed
                    if (given.expr is LambdaExpr && p.type is KType.Fn && !model.fxEscapes(p)) {
                        templateLambdas[given.expr] = true
                    }
                }
            }
        }

        private fun globalWrite(p: Place?, at: ASTNode) {
            val g = (p?.root() as? Place.Global)?.sym ?: return
            r.report("rules.const.global", "$what writes the global '${g.name}'; a constant expression has no side effects.", at)
        }

        private fun globalRead(id: Identifier) {
            val g = model.refs[id] as? GlobalSymbol ?: return
            if (g.isMut) {
                mutGlobal(g, id)
            }
        }

        private fun mutGlobal(g: GlobalSymbol, at: ASTNode) {
            r.report("rules.const.global", "$what reads the mut global '${g.name}', whose value is never usable in a constant expression; take it as a parameter.", at)
        }
    }
}
