package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram

/**
 * A test block's checks (stage 3b) are effect-free: none is IMPURE by EffectsPass (it writes,
 * prints, calls through a vtable, an Fx or the host, or may throw), so testing changes nothing.
 */
internal class TestBlockPass : RulePass {
    override val name: String = "testblocks"

    override fun run(program: TypedProgram) {
        val model = program.model
        for (test in model.tests.keys) {
            for (check in test.checks.orEmpty()) {
                if ((model.effects[check] ?: Effect.IMPURE) == Effect.IMPURE) {
                    program.report(
                        "types.test.effect",
                        "A check in a test block only reads: this one may change state, print, throw, or call what Kira cannot see into " +
                            "(a virtual or trait method, an Fx, the host); compute it into a local before the test.",
                        check,
                    )
                }
            }
        }
    }
}
