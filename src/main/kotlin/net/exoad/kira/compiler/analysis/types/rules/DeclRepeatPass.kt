package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypedProgram

/**
 * `rules.decl.repeat`: `x: T = T { .. }` (or `x: Mutable<U> = U { .. }`) says its type twice;
 * `x: T { .. }` is the same construction. The parser notes a declaration only when the declared
 * type and the constructed type are the same text and only ` = T ` sits between the type and
 * the `{`, so a different constructor, an expression, or a comment in between is never told.
 */
internal class DeclRepeatPass : RulePass {
    override val name: String = "decl-repeat"

    override fun run(program: TypedProgram) {
        if (!program.options.hintDeclRepeat) return
        val r = Rules(program)
        for (m in program.workspaceModules) {
            for (d in m.source.astDeclRepeats) {
                val shown = if (d.mutable) "Mutable<${d.type}>" else d.type
                r.report(
                    "rules.decl.repeat",
                    "`${d.local}` repeats its type: write `${d.local}: $shown { .. }`, the same construction without `= ${d.type}`.",
                    d.decl,
                    Severity.WARNING,
                )
            }
        }
    }
}
