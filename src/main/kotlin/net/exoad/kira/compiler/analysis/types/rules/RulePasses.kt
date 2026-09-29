package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.RulePass

/**
 * The rule passes (design 3.4), in the order KiraTyper runs them after phase C. The four that
 * write the model come first: LentPlaces fills `lentPlaces` (40-round3 R-A: a lent result is a
 * place, read through `readPlace` by every pass after it), EffectsPass fills
 * `effects`/`fnEffects` and the CONFINED tables `CallReach.confined` reads (50-round4 2.3), EscapePass fills `fxEscapes`/`thisEscapes`, and ViewPass (views are
 * second-class, decision 4b) reads them and fills `viewOrigins`, which ExclusivityPass reads.
 * The rest are independent, read-only walks.
 */
object RulePasses {
    fun default(): MutableList<RulePass> = mutableListOf(
        LentPlaces(),
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
