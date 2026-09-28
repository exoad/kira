package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.RulePass

/**
 * The rule passes (design 3.4), in the order KiraTyper runs them after phase C. The three that
 * write the model come first: EffectsPass fills `effects`/`fnEffects`, EscapePass fills
 * `fxEscapes`/`thisEscapes`, and ViewPass (views are second-class, decision 4b) reads both and
 * fills `viewOrigins`, which ExclusivityPass reads. The rest are independent,
 * read-only walks.
 */
object RulePasses {
    fun default(): MutableList<RulePass> = mutableListOf(
        EffectsPass(),
        EscapePass(),
        ViewPass(),
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
