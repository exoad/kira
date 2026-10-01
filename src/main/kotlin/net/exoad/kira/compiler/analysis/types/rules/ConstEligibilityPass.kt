package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
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
 * Three calls hide in other shapes and are held to the call rule, as phase C holds a
 * global's initializer to it: an operator with an `@op_*` overload (`a + b` over a struct,
 * TypedModel.opCalls) is a call of that function; a construction that leaves a field to its
 * default runs the default (`W { }` with `n: Int32 = seed()` is `W{}` over a default member
 * initializer that calls `seed()`); and a call that leaves a parameter to its default passes
 * the default. A default is checked where it is used and reported at the construction or
 * call, naming the field or parameter and what inside the default is refused.
 *
 * - `rules.const.type`: a parameter, local, return or intermediate value of a non-literal
 *   type. An `Fx` parameter that does not escape is a template parameter and is allowed; a
 *   generic `T` is allowed (a constexpr template is checked at instantiation).
 * - `rules.const.call`: a call to a function that is not `@_const` (an `@op_*` overload
 *   among them), or to a magic binding that is not `constexpr`, a virtual or trait call, or a
 *   call through an `Fx` that is not a parameter.
 * - `rules.const.extern`: an extern call.
 * - `rules.const.trace`: `trace`, `print`, `println`, `eprint` or `@_trace_`.
 * - `rules.const.throw`, `rules.const.try`.
 * - `rules.const.global`: writing a global, or reading one whose value is never usable in a
 *   constant expression: a `mut` global, or a global of a non-literal type (an `Arr<Int32>`,
 *   a `List`, a struct holding one), which C++ builds at run time as `inline const`. A `Str`
 *   constant is usable only through its folded literal (a `const char*`).
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

    /** One refusal: its code, and the clause that names the construct (no subject). */
    private class Refusal(val code: String, val clause: String)

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
            // A constexpr member's class is a literal type: a value class with `initially` is none (W2.9 1.2.3).
            (fn.owner as? ClassSymbol)?.takeIf { it.isValueClass }?.let { owner -> literal(owner.selfType, fn.decl, "its receiver") }
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
            facts.isValueClass(t) && ((t as KType.Nominal).sym as ClassSymbol).initially != null -> "its initially block makes its constructor no constexpr"
            facts.isRefClass(t) || facts.isTrait(t) -> "a class reference is an Rc on the heap; use a struct"
            facts.isStruct(t) || facts.isValueClass(t) -> "one of its fields is not"
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
                is FunctionCallExpr -> model.calls[n]?.let { rc -> call(rc, (n.name as? MemberAccessExpr)?.member ?: n.name, n.name, n) }
                is BinaryExpr, is UnaryExpr -> model.opCalls[n as Expr]?.let { rc -> call(rc, n, null, n) }
                is ObjectInitExpr -> defaults(n)
                is AssignmentExpr -> globalWrite(model.places[n.target], n.target)
                is CompoundAssignmentExpr -> globalWrite(model.places[n.left], n.left)
                is PlaceAssignmentExpr -> globalWrite(model.places[n.target], n.target)
                is Identifier -> globalRead(model.refs[n] as? GlobalSymbol, n)
                is MemberAccessExpr -> globalRead((model.members[n] as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol, n)
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
            if ((e is BinaryExpr || e is UnaryExpr) && model.opCalls[e] == null) {
                // A builtin operator: its operands were reported.
                return
            }
            r.report("rules.const.type", "$what: ${KiraUnparser.text(e)} is ${t.display()}, which is no literal type in C++ (${nonLiteral(t)}).", e)
        }

        /**
         * A call ([TypedModel.calls]) or an operator with an `@op_*` overload
         * ([TypedModel.opCalls], where [target] is null). [at] is where a refusal is reported.
         */
        private fun call(rc: ResolvedCall, at: ASTNode, target: Expr?, e: Expr) {
            val callee = rc.fn
            when (rc.kind) {
                CallKind.FREE, CallKind.METHOD, CallKind.CTOR -> if (callee != null && !callee.isConst) {
                    r.report("rules.const.call", "$what calls '${callee.name}', which is not @_const; mark it @_const, or take its result as a parameter.", at)
                }
                CallKind.OP_OVERLOAD -> if (callee != null && !callee.isConst) {
                    r.report("rules.const.call", "$what: ${KiraUnparser.text(e)} calls '@${callee.name}', which is not @_const; mark the operator @_const, or take its result as a parameter.", at)
                }
                CallKind.MAGIC -> if (callee != null && !r.bindings.isConstexpr(callee)) {
                    r.report("rules.const.call", "$what calls '${callee.name}', whose C++ binding is not constexpr; compute it at run time and pass the result in.", at)
                }
                CallKind.VIRTUAL, CallKind.TRAIT -> r.report("rules.const.call", "$what calls '${callee?.name}' through a vtable, which is no constant expression on a heap reference.", at)
                CallKind.EXTERN -> r.report("rules.const.extern", "$what calls the extern '${callee?.name}', which C++ cannot evaluate at compile time.", at)
                CallKind.PRINT -> r.report("rules.const.trace", "$what calls '${callee?.name ?: "trace"}', which prints; no constant expression can.", at)
                // A value's copy is an aggregate initialization (W2.9 1.2.3); the result's type says whether it is a literal one.
                CallKind.COPY -> {}
                CallKind.FN_VALUE -> {
                    val sym = (target as? Identifier)?.let { model.refs[it] }
                    if (sym !is ParamSymbol) {
                        r.report("rules.const.call", "$what calls ${target?.let { KiraUnparser.text(it) } ?: "an Fx value"}, a std::function; only an Fx parameter (a template parameter) can be called here.", at)
                    }
                }
            }
            if (callee != null) {
                rc.args.forEachIndexed { i, a ->
                    when (a) {
                        is ArgBinding.Given -> {
                            val p = callee.params.getOrNull(i) ?: return@forEachIndexed
                            if (a.expr is LambdaExpr && p.type is KType.Fn && !model.fxEscapes(p)) {
                                templateLambdas[a.expr] = true
                            }
                        }
                        is ArgBinding.Default -> {
                            val d = a.param.default ?: return@forEachIndexed
                            val refusal = refusalIn(d) ?: return@forEachIndexed
                            r.report(
                                refusal.code,
                                "$what calls '${callee.name}' leaving '${a.param.name}' to its default ${KiraUnparser.text(d)}, which ${refusal.clause}.",
                                at,
                            )
                        }
                    }
                }
            }
        }

        /** A construction that leaves a field to its default runs the default here. */
        private fun defaults(e: ObjectInitExpr) {
            val ri = model.inits[e] ?: return
            val cls = ri.cls ?: return
            for (f in ri.fields) {
                if (f !is FieldInit.Default) {
                    continue
                }
                val d = f.field.default ?: continue
                val refusal = refusalIn(d) ?: continue
                r.report(
                    refusal.code,
                    "$what constructs ${cls.name} leaving field '${f.field.name}' to its default ${KiraUnparser.text(d)}, which ${refusal.clause}.",
                    e,
                )
            }
        }

        /**
         * The first construct in a default expression that the `@_const` rules refuse, as a
         * clause: the same tests as [node], without the locals a default cannot have.
         */
        private fun refusalIn(d: Expr): Refusal? {
            var found: Refusal? = null
            AstScan.walk(listOf(d)) { n, _ ->
                if (found == null) {
                    found = refusalOf(n)
                }
            }
            return found
        }

        private fun refusalOf(n: ASTNode): Refusal? {
            val e = n as? Expr ?: return null
            model.opCalls[e]?.let { rc -> callRefusal(rc, null)?.let { return it } }
            when (n) {
                is ThrowExpr -> return Refusal("rules.const.throw", "throws")
                is TryExpr -> return Refusal("rules.const.try", "has a try")
                is IntrinsicExpr -> if (n.intrinsicKey.name == "_trace_") return Refusal("rules.const.trace", "prints")
                is LambdaExpr -> return Refusal("rules.const.lambda", "builds a std::function")
                is FunctionCallExpr -> model.calls[n]?.let { rc -> callRefusal(rc, n.name)?.let { return it } }
                is ObjectInitExpr -> {
                    val ri = model.inits[n]
                    ri?.fields?.forEach { f ->
                        if (f is FieldInit.Default) {
                            f.field.default?.let { inner -> refusalIn(inner)?.let { return Refusal(it.code, "leaves '${f.field.name}' to its default ${KiraUnparser.text(inner)}, which ${it.clause}") } }
                        }
                    }
                }
                is Identifier -> (model.refs[n] as? GlobalSymbol)?.let { g -> globalRefusal(g, n)?.let { return it } }
                is MemberAccessExpr -> ((model.members[n] as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol)?.let { g -> globalRefusal(g, n)?.let { return it } }
                is InterpolatedStringLiteral -> return Refusal("rules.const.type", "builds a Str, which is no literal type in C++ (std::string allocates)")
                else -> {}
            }
            if (n !is Type && n !is Identifier && n !is LambdaExpr) {
                val t = model.types[e] ?: return null
                if (t == KType.Void || t == KType.Never || t is KType.Param || t.containsError() || facts.isLiteralType(t)) {
                    return null
                }
                if ((e is BinaryExpr || e is UnaryExpr) && model.opCalls[e] == null) {
                    return null
                }
                return Refusal("rules.const.type", "is ${t.display()}, no literal type in C++ (${nonLiteral(t)})")
            }
            return null
        }

        private fun callRefusal(rc: ResolvedCall, target: Expr?): Refusal? {
            val callee = rc.fn
            return when (rc.kind) {
                CallKind.FREE, CallKind.METHOD, CallKind.CTOR -> if (callee != null && !callee.isConst) Refusal("rules.const.call", "calls '${callee.name}', which is not @_const") else null
                CallKind.OP_OVERLOAD -> if (callee != null && !callee.isConst) Refusal("rules.const.call", "calls '@${callee.name}', which is not @_const") else null
                CallKind.MAGIC -> if (callee != null && !r.bindings.isConstexpr(callee)) Refusal("rules.const.call", "calls '${callee.name}', whose C++ binding is not constexpr") else null
                CallKind.VIRTUAL, CallKind.TRAIT -> Refusal("rules.const.call", "calls '${callee?.name}' through a vtable")
                CallKind.EXTERN -> Refusal("rules.const.extern", "calls the extern '${callee?.name}'")
                CallKind.PRINT -> Refusal("rules.const.trace", "prints")
                CallKind.COPY -> null
                CallKind.FN_VALUE -> Refusal("rules.const.call", "calls ${target?.let { KiraUnparser.text(it) } ?: "an Fx value"}, a std::function")
            }
        }

        private fun globalWrite(p: Place?, at: ASTNode) {
            val g = (p?.root() as? Place.Global)?.sym ?: return
            r.report("rules.const.global", "$what writes the global '${g.name}'; a constant expression has no side effects.", at)
        }

        private fun globalRead(g: GlobalSymbol?, at: Expr) {
            val refusal = globalRefusal(g ?: return, at) ?: return
            r.report(refusal.code, "$what ${refusal.clause}; take it as a parameter.", at)
        }

        /** Why reading [g] here is no constant expression, or null: `mut`, or a value C++ builds at run time. */
        private fun globalRefusal(g: GlobalSymbol, at: Expr): Refusal? {
            model.consts[at]?.let { c ->
                // Folded here (`null`, a Str literal, a literal-typed constant): phase C's rule, a constant expression.
                if (c is ConstValue.NullConst || c is ConstValue.StrConst || facts.isLiteralType(c.type)) {
                    return null
                }
            }
            if (g.isMut) {
                return Refusal("rules.const.global", "reads the mut global '${g.name}', whose value is never usable in a constant expression")
            }
            if (g.type == KType.Str) {
                return if (model.consts[at] is ConstValue.StrConst) null else Refusal("rules.const.global", "reads the Str global '${g.name}', which did not fold to a literal, so it is a std::string built at run time")
            }
            if (facts.isLiteralType(g.type) || g.type.containsError()) {
                return null
            }
            return Refusal(
                "rules.const.global",
                "reads the global '${g.name}', which is ${g.type.display()}, no literal type in C++ (${nonLiteral(g.type)}): it is built at run time (inline const) and never usable in a constant expression",
            )
        }
    }
}
