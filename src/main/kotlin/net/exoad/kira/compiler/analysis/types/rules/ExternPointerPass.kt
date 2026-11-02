package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.isAggregateExtern
import net.exoad.kira.compiler.analysis.types.isExternStruct
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr

/**
 * An `@_extern` struct reaches C by pointer (cpp only):
 *
 * - at an `Unsafe<T>` parameter of an extern, `f(x)` passes `&x` as a `const T*`;
 * - as the receiver of a method that names a C function (`@_extern(c = "f")`), `x.m(a)` is
 *   `f(&x, a)`, a `T*` when the method is `@_mutates` (so `x` is a Mutable binding, and the
 *   change is Kira's to see) and a `const T*` otherwise;
 * - `T { field = v }` builds one from the fields Kira declares, a C aggregate.
 *
 * Each pointer lives for its one call. The one pointer Kira keeps is a struct's `Unsafe<T>`
 * field (a C `const T*` member), so `rules.extern.pointer` admits only a module-level constant
 * `Arr` there, whose storage is static: a local or a Mutable array would leave the struct
 * pointing at storage that ends or changes. `rules.extern.target` refuses the construction and
 * the pointer argument on py, whose `@_extern` struct is no C struct (C and JS refuse an
 * `@_extern` struct or method already, and py a `c =` method).
 */
internal class ExternPointerPass : RulePass {
    override val name: String = "extern-pointer"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val mode = GeneratedProvider.outputMode
        val offCpp = mode != GeneratedProvider.OutputTarget.CPP && mode != GeneratedProvider.OutputTarget.NONE
        for (b in Bodies.of(program)) {
            for (root in b.roots) {
                AstTree.walk(root) { n -> visit(r, n, offCpp, mode) }
            }
        }
    }

    private fun offTarget(mode: GeneratedProvider.OutputTarget): String = "the ${mode.name} backend has no C struct"

    private fun visit(r: Rules, n: ASTNode, offCpp: Boolean, mode: GeneratedProvider.OutputTarget) {
        if (n is ObjectInitExpr) {
            val init = r.model.inits[n] ?: return
            val cls = init.cls ?: return
            if (!cls.isAggregateExtern) {
                return
            }
            if (offCpp) {
                r.report(TARGET, "'${cls.name} { ... }' builds a C struct from its fields, which reaches --target cpp only: ${offTarget(mode)}.", n)
                return
            }
            for (f in init.fields.filterIsInstance<FieldInit.Given>()) {
                if (!r.facts.isMagic(f.field.type, "Unsafe") || staticArr(r, f.expr)) {
                    continue
                }
                r.report(
                    POINTER,
                    "Field '${f.field.name}' of ${cls.name} keeps a pointer for as long as the struct lives, so it takes a module-level constant Arr " +
                        "(static storage): declare the array at module level, without Mutable, and name it here.",
                    f.expr,
                )
            }
        }
        if (offCpp && n is FunctionCallExpr) {
            val rc = r.model.calls[n] ?: return
            val fn = rc.fn ?: return
            if (!fn.suppliedByCpp) {
                return
            }
            rc.args.forEachIndexed { i, a ->
                val e = (a as? ArgBinding.Given)?.expr ?: return@forEachIndexed
                val p = fn.params.getOrNull(i) ?: return@forEachIndexed
                if (byPointer(r, p.type, r.model.types[e])) {
                    r.report(TARGET, "Passing a ${r.model.types[e]?.let { (it as KType.Nominal).sym.name }} to '${fn.name}' by pointer reaches --target cpp only: ${offTarget(mode)}.", e)
                }
            }
        }
    }

    private fun byPointer(r: Rules, param: KType, given: KType?): Boolean {
        val n = given as? KType.Nominal ?: return false
        return r.facts.isMagic(param, "Unsafe") && (param as KType.Nominal).typeArgs().firstOrNull() == n && (n.sym as? ClassSymbol)?.isExternStruct == true
    }

    /**
     * Whether [e] names a Kira module-level constant (an `Arr`, as the typer took it): static
     * storage that never changes. An extern constant is read as a copy (`kira::ffi::declared`).
     */
    private fun staticArr(r: Rules, e: Expr): Boolean {
        val g = when (e) {
            is Identifier -> r.model.symbolOf(e) as? GlobalSymbol
            is MemberAccessExpr -> (r.model.member(e) as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol
            else -> null
        } ?: return false
        return !g.isMut && g.foreign == null
    }

    private companion object {
        const val POINTER = "rules.extern.pointer"
        const val TARGET = "rules.extern.target"
    }
}
