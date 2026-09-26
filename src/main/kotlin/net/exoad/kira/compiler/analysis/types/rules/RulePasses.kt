package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.RulePass

/**
 * The rule passes (design 3.4), in the order KiraTyper runs them after phase C. The two that
 * write the model come first: EffectsPass fills `effects`/`fnEffects`, EscapePass fills
 * `fxEscapes`/`viewEscapes`/`thisEscapes`, and ProfilePass and ConstEligibilityPass read the
 * escape facts. The rest are independent, read-only walks.
 */
object RulePasses {
    fun default(): MutableList<RulePass> = mutableListOf(
        EffectsPass(),
        EscapePass(),
        MutabilityPass(),
        ExclusivityPass(),
        ReturnPathPass(),
        VisibilityPass(),
        ProfilePass(),
        ConstEligibilityPass(),
        GenericBoundsPass(),
        NamingPass(),
    )
}
