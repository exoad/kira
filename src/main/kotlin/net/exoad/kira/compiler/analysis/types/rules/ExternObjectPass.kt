package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.isConstructibleExtern
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import java.util.IdentityHashMap

/**
 * `rules.extern.copy`: a value of an `@_constructible` extern struct is one C++ object, which
 * Kira never copies or replaces: a copy of a class like arduino-pico's `Servo` would duplicate
 * the pin it drives, and the assignment would detach nothing. So such a value appears only
 * as the receiver of a member access (`s.attach(...)`), and `T { }` only as the initializer of
 * a declaration (a local, a global or a field's default), where C++17's guaranteed elision
 * builds it in place, and as an argument at an extern's `Unsafe<T>` parameter, which takes its
 * address for the call. A lambda's capture is a copy, and so is a field of a struct: both are
 * refused, and a class (held by reference) is where such an object is kept.
 */
internal class ExternObjectPass : RulePass {
    override val name: String = "extern-object"

    private val lent = IdentityHashMap<Expr, Boolean>()

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        lent.clear()
        for (m in program.modules) {
            for (cls in m.declarations.filterIsInstance<ClassSymbol>().filter { it.kind != ClassKind.CLASS }) {
                for (f in cls.fields) {
                    val held = constructible(f.type) ?: continue
                    r.report(
                        "rules.extern.copy",
                        "'${cls.name}' is a value Kira copies, so it cannot hold the ${held.name} '${f.name}', a C++ object Kira does not copy: " +
                            "hold it in a class ('${cls.name}: Mutable<Any>').",
                        f.decl ?: cls.decl,
                    )
                }
            }
        }
        for (b in Bodies.of(program)) {
            val rootInitOk = b.kind == BodyKind.FIELD_DEFAULT || b.kind == BodyKind.GLOBAL_INIT
            for (root in b.roots) {
                visit(r, root, null, rootInitOk)
            }
        }
    }

    private fun constructible(t: KType?): ClassSymbol? = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.takeIf { it.isConstructibleExtern }

    private fun visit(r: Rules, n: ASTNode, parent: ASTNode?, rootInitOk: Boolean) {
        if (n is FunctionCallExpr) {
            byPointer(r, n).forEach { lent[it] = true }
        }
        if (n is LambdaExpr) {
            r.model.captures[n].orEmpty().forEach { c ->
                val sym = (c as? Capture.Value)?.symbol
                val held = constructible((sym as? LocalSymbol)?.type ?: (sym as? ParamSymbol)?.type) ?: return@forEach
                r.report(
                    "rules.extern.copy",
                    "This lambda captures the ${held.name} '${sym!!.name}' by copy, and it is a C++ object Kira does not copy: " +
                        "hold it in a class ('X: Mutable<Any>') and capture that.",
                    n,
                )
            }
        }
        if (n is Expr && n !is Type) {
            val cls = constructible(r.model.types[n])
            if (cls != null && !allowed(n, parent, rootInitOk) && !lent.containsKey(n)) {
                r.report(
                    "rules.extern.copy",
                    if (n is ObjectInitExpr) {
                        "'${cls.name} { }' builds a C++ object in place, so it is only the initializer of a declaration " +
                            "(a local, a global or a field's default), never assigned, passed or returned."
                    } else {
                        "This ${cls.name} is a C++ object that Kira does not copy: call its methods where it is " +
                            "(x.method(...)), and build each one with its own '${cls.name} { }' declaration."
                    },
                    n,
                )
            }
        }
        for (k in AstTree.children(n)) {
            if (n is MemberAccessExpr && k === n.member) {
                continue
            }
            visit(r, k, n, false)
        }
    }

    private fun allowed(e: Expr, parent: ASTNode?, rootInitOk: Boolean): Boolean = when {
        // The member name is never visited, so a value under a member access is its receiver.
        parent is MemberAccessExpr -> true
        e is ObjectInitExpr -> if (parent == null) rootInitOk else parent is VariableDecl && parent.value === e
        else -> false
    }

    /** The arguments of the extern call [call] at an `Unsafe<T>` parameter: their address is passed, not a copy (ExternPointerPass). */
    private fun byPointer(r: Rules, call: FunctionCallExpr): List<Expr> {
        val rc = r.model.calls[call] ?: return emptyList()
        val fn = rc.fn?.takeIf { it.suppliedByCpp } ?: return emptyList()
        return rc.args.withIndex().mapNotNull { (i, a) ->
            (a as? ArgBinding.Given)?.expr?.takeIf { fn.params.getOrNull(i)?.type?.let { t -> r.facts.isMagic(t, "Unsafe") } == true }
        }
    }
}
