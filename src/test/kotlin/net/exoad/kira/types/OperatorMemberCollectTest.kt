package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.phasesAAndB
import net.exoad.kira.types.TyperTestSupport.render
import net.exoad.kira.types.TyperTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `w2-9-1-parse`: the member operator form takes part in phase A's collection and phase B's
 * member checks (1.3.2) exactly as any method does, and `final` (1.8) is enforced by
 * `SignatureResolver.classParents`. `OperatorMemberParseTest` covers the parser itself; the
 * typer's own resolution of an operator call (`a.@_op_add_(b)`'s type) is `w2-9-7-ops-typer`'s
 * -- these tests only ask that phases A and B see no error in the acceptance snippet.
 */
class OperatorMemberCollectTest {

    private fun classOf(program: net.exoad.kira.compiler.analysis.types.TypedProgram, uri: String, name: String): ClassSymbol {
        val module = assertNotNull(program.module(uri), "module $uri")
        return module.members[name] as? ClassSymbol ?: error("no class $name in $uri: ${module.members.keys}")
    }

    private fun methodOf(cls: ClassSymbol, name: String): FnSymbol =
        cls.methods.firstOrNull { it.name == name } ?: error("no method $name on ${cls.name}: ${cls.methods.map { it.name }}")

    // The acceptance snippet, verbatim: a member `@_op_add_` and an explicit call of it.
    @Test
    fun theAcceptanceSnippetParsesAndCollectsWithNoError() {
        val program = phasesAAndB {
            snippet(
                """
                pub class V2 {
                    pub x: Float32 = 0.0
                    pub fx @_op_add_: (other: V2) V2 { return V2 { x + other.x } }
                }
                fx apply: (a: V2, b: V2) Void {
                    c: V2 = a.@_op_add_(b)
                }
                """
            )
        }
        expectNoErrors(program)
    }

    @Test
    fun memberOperatorIsCollectedAsAMethodNotAFreeOperator() {
        val program = phasesAAndB {
            snippet(
                """
                pub class V2 {
                    pub x: Float32 = 0.0
                    pub fx @_op_add_: (other: V2) V2 { return V2 { x } }
                }
                """
            )
        }
        expectNoErrors(program)
        val v2 = classOf(program, "test:main", "V2")
        val add = methodOf(v2, "_op_add_")
        assertTrue(add.isOperator, "@_op_add_ must be isOperator")
        assertFalse(add.isFreeOperator, "a class member is never the free form")
        // Never in module.operators (that list is the free form's, 1.3.5), and not skipped out
        // of the class's own method list either.
        assertTrue(v2.methods.contains(add))
    }

    @Test
    fun aFreeOperatorAtModuleLevelIsFreeOperator() {
        val program = phasesAAndB {
            snippet(
                """
                pub class Point {
                    require pub x: Int32
                }
                pub fx @op_add: (a: Point, b: Point) Point {
                    return Point { a.x + b.x }
                }
                """
            )
        }
        val module = assertNotNull(program.module("test:main"))
        val op = module.operators.single()
        assertTrue(op.isFreeOperator)
        // The free form never lands in `members` (1.3.5's own name, `op_add`, is not `Point`'s).
        assertTrue("op_add" !in module.members.keys)
    }

    @Test
    fun duplicateMemberOperatorIsADuplicateDeclaration() {
        val program = phasesAAndB {
            snippet(
                """
                pub class V2 {
                    pub x: Float32 = 0.0
                    pub fx @_op_add_: (other: V2) V2 { return V2 { x } }
                    pub fx @_op_add_: (other: V2) V2 { return V2 { x } }
                }
                """
            )
        }
        expectDiagnostic(program, "types.decl.duplicate")
    }

    @Test
    fun duplicateMemberOperatorInATraitIsADuplicateDeclaration() {
        val program = phasesAAndB {
            snippet(
                """
                pub trait Ordered<T> {
                    pub fx @_op_lt_: (other: T) Bool;
                    pub fx @_op_lt_: (other: T) Bool;
                }
                """
            )
        }
        expectDiagnostic(program, "types.decl.duplicate")
    }

    @Test
    fun overrideMemberOperatorLinksToItsBase() {
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub fx @_op_eq_: (other: Base) Bool { return true }
                }
                pub class Leaf: Base {
                    override pub fx @_op_eq_: (other: Base) Bool { return false }
                }
                """
            )
        }
        expectNoErrors(program)
        val leaf = classOf(program, "test:main", "Leaf")
        val base = classOf(program, "test:main", "Base")
        val eq = methodOf(leaf, "_op_eq_")
        assertTrue(eq.overrides === methodOf(base, "_op_eq_"), "override pub fx @_op_eq_ must link to its base")
    }

    @Test
    fun missingOverrideOnAMemberOperatorIsRefused() {
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub fx @_op_eq_: (other: Base) Bool { return true }
                }
                pub class Leaf: Base {
                    pub fx @_op_eq_: (other: Base) Bool { return false }
                }
                """
            )
        }
        expectDiagnostic(program, "types.override.missing")
    }

    @Test
    fun overrideWithNothingToOverrideIsRefused() {
        val program = phasesAAndB {
            snippet(
                """
                pub class Lone {
                    override pub fx @_op_eq_: (other: Lone) Bool { return true }
                }
                """
            )
        }
        expectDiagnostic(program, "types.override.nothing")
    }

    @Test
    fun aModuleLevelMemberFormNameIsRefused() {
        // 1.3.1/1.3.5: `@_op_add_` is the *member* spelling. Declared at module level (owner ==
        // null) it would otherwise become a "free operator" under a name neither backend's
        // free-operator lowering ever emits (`OperatorIntrinsics.binaryName` always spells
        // `op_add`), so `a + a` would call a function that does not exist. This must be refused,
        // not silently accepted.
        val program = phasesAAndB {
            snippet(
                """
                pub class V2 {
                    pub x: Float32 = 0.0
                }
                pub fx @_op_add_: (a: V2, b: V2) V2 {
                    return V2 { a.x + b.x }
                }
                """
            )
        }
        expectDiagnostic(program, "ops.member-scope")
        assertTrue(program.hasErrors, render(program))
    }

    @Test
    fun aModuleLevelFreeFormNameIsStillJustFreeOperator() {
        // The pre-W2.9 free form (`@op_add`, no leading/trailing underscore) is not the member
        // spelling, so it must not trip the new module-level refusal above.
        val program = phasesAAndB {
            snippet(
                """
                pub class Point {
                    require pub x: Int32
                }
                pub fx @op_add: (a: Point, b: Point) Point {
                    return Point { a.x + b.x }
                }
                """
            )
        }
        assertFalse(program.diagnostics.any { it.code == "ops.member-scope" }, render(program))
    }

    @Test
    fun twoInheritedOperatorsOfOneNameConflictEvenWhenLeafDeclaresNeither() {
        // The spec's own example (1.3.2, brief step 4): a superclass's `@_op_eq_(other: Base)`
        // beside a trait's `@_op_eq_(other: Leaf)`, with Leaf declaring nothing at all. The
        // conflict must still be found, never first-found, even though no `Leaf.methods` entry
        // exists to anchor the per-method check.
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub fx @_op_eq_: (other: Base) Bool { return true }
                }
                pub trait Eq<T> {
                    pub fx @_op_eq_: (other: T) Bool
                }
                pub class Leaf: Base, Eq<Leaf> {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun twoInheritedOrdinaryMethodsOfOneNameConflictEvenWhenLeafDeclaresNeither() {
        // Same shape as above, with an ordinary method rather than an operator: the check must
        // not be operator-specific.
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub fx name: () Str { return "base" }
                }
                pub trait Named {
                    pub fx name: (n: Int32) Str
                }
                pub class Leaf: Base, Named {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun twoInheritedMethodsOfOneNameAgreeingIsNoConflict() {
        // Same shape, but the superclass and the trait declare the *same* signature: no
        // diagnostic, so the new whole-class pass does not over-trigger.
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub fx @_op_eq_: (other: Base) Bool { return true }
                }
                pub trait Eq<T> {
                    pub fx @_op_eq_: (other: T) Bool
                }
                pub class Leaf: Base, Eq<Base> {
                }
                """
            )
        }
        assertFalse(program.diagnostics.any { it.code == "types.member.conflict" }, render(program))
    }

    @Test
    fun twoTraitsOfOneNameWithDifferentSignaturesConflict() {
        // w2-9-1-parse round 2, significant issue #2: `reportConflictIfAny` used to compare only
        // `fromChain` (a superclass) against the *first* trait found, so two traits that disagree
        // and no superclass at all (`base == null`) returned before ever comparing them. Brief
        // step 4 requires this regardless of a superclass: "two inherited methods of one name
        // with different signatures are types.member.conflict... never first-found."
        val program = phasesAAndB {
            snippet(
                """
                pub trait DefA {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait DefB {
                    pub fx m: (x: Int32) Int32 { return x }
                }
                pub class Both: DefA, DefB {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun twoTraitsOfOneOperatorNameWithDifferentSignaturesConflict() {
        // Same shape, operators included, and with the class itself overriding the name (the
        // measured case: DefA/DefB's `@_op_eq_` disagree, and `Leaf3`'s own override matches only
        // one of them). The conflict must still fire; it is not silenced just because the class
        // resolves which base it overrides.
        val program = phasesAAndB {
            snippet(
                """
                pub trait EqA {
                    pub fx @_op_eq_: (other: Int32) Bool;
                }
                pub trait EqB {
                    pub fx @_op_eq_: (other: Str) Bool;
                }
                pub class Leaf3: EqA, EqB {
                    override pub fx @_op_eq_: (other: Int32) Bool { return true }
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aSingleTraitOperatorMismatchIsStillTypesOverrideSignature() {
        // Contrast for the test above: with only EqB implemented (no second trait to disagree
        // with), the class's override is checked against EqB alone and its signature disagrees --
        // `types.override.signature`, not `types.member.conflict`. Pins that the fix above did
        // not turn every override mismatch into a conflict.
        val program = phasesAAndB {
            snippet(
                """
                pub trait EqB {
                    pub fx @_op_eq_: (other: Str) Bool;
                }
                pub class Only: EqB {
                    override pub fx @_op_eq_: (other: Int32) Bool { return true }
                }
                """
            )
        }
        expectDiagnostic(program, "types.override.signature")
        assertFalse(program.diagnostics.any { it.code == "types.member.conflict" }, render(program))
    }

    @Test
    fun aTraitOverridingItsOwnParentTraitIsNotATraitVsTraitConflict() {
        // The fix must exclude a trait method that overrides a parent trait's method (the
        // flatten/traitOverrides case, brief significant issue #2's last sentence): `Mid`
        // overrides `Base0`'s `m`, and `Leaf` implements only `Mid` (whose own closure includes
        // `Base0`). That must never register as two independent trait roots disagreeing.
        val program = phasesAAndB {
            snippet(
                """
                pub trait Base0 {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait Mid: Base0 {
                    pub fx m: (x: Int32) Int32 { return x }
                }
                pub class Leaf: Mid {
                }
                """
            )
        }
        assertFalse(program.diagnostics.any { it.code == "types.member.conflict" }, render(program))
    }

    @Test
    fun aTraitWrappingTwoDisagreeingTraitsStillConflictsThroughTheWrapper() {
        // w2-9-1-parse round 3, significant issue #1: `fromTraits` used to take `traitClosure`'s
        // *first* match per root (`firstNotNullOfOrNull`), so a trait root that itself forks into
        // two disagreeing parents (`T: A, B` here) contributed only `A`'s declaration. A class
        // naming `A` and `B` directly already conflicted; wrapping them in a third trait must not
        // remove the diagnostic (brief step 4, 20-revision 1.3.2: "never first-found").
        //
        // Round 4, significant issue #2: `T`'s own trait-level check (below) independently
        // reports this same disagreement at `T`, regardless of `frontierDeclarers`/`traitAncestors`
        // -- each of `T`'s two *direct* parents is trivial (neither forks on its own), so that
        // check alone can never distinguish this fix from a mutant that reverts
        // `frontierDeclarers` to one candidate per root. Only `C`'s own comparison, which treats
        // `T` as a single root and must resolve its internal fork, needs the fix; assert the
        // conflict this test is named for is the one anchored at `C`, not merely present anywhere.
        val program = phasesAAndB {
            snippet(
                """
                pub trait A {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait B {
                    pub fx m: (x: Int32) Int32 { return x }
                }
                pub trait T: A, B {
                }
                pub class C: T {
                }
                """
            )
        }
        val conflicts = program.diagnostics.filter { it.code == "types.member.conflict" }
        assertTrue(conflicts.any { it.message.startsWith("C inherits") }, render(program))
    }

    @Test
    fun aTraitNamingTwoDisagreeingParentsConflictsOnItsOwnDeclaration() {
        // Same shape as above, but asks about `T` itself, never implemented by any class: `T`
        // inherits `A` and `B`'s disagreement the moment it names both parents, so it must not
        // wait for a class like the test above to surface the conflict.
        val program = phasesAAndB {
            snippet(
                """
                pub trait A {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait B {
                    pub fx m: (x: Int32) Int32 { return x }
                }
                pub trait T: A, B {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun anOperatorWrappedInAThirdTraitStillConflictsThroughTheWrapper() {
        // The measured operator case from the brief: `EqA` and `EqB` disagree on `@_op_eq_`,
        // `EqBoth` wraps both with no declaration of its own, and `Leaf`'s own override matches
        // only `EqA`. Before the fix, wrapping removed the diagnostic `twoTraitsOfOneOperatorName-
        // WithDifferentSignaturesConflict` (above) pins for the unwrapped shape.
        //
        // Round 4, significant issue #2: as above, `EqBoth`'s own trait-level check independently
        // reports this at `EqBoth` regardless of the fix (neither `EqA` nor `EqB` forks on its
        // own), so only `Leaf`'s own comparison -- which must resolve `EqBoth`'s internal fork as
        // a single root -- actually needs `frontierDeclarers` to keep both branches. Assert the
        // conflict is the one anchored at `Leaf`.
        val program = phasesAAndB {
            snippet(
                """
                pub trait EqA {
                    pub fx @_op_eq_: (other: Int32) Bool;
                }
                pub trait EqB {
                    pub fx @_op_eq_: (other: Str) Bool;
                }
                pub trait EqBoth: EqA, EqB {
                }
                pub class Leaf: EqBoth {
                    override pub fx @_op_eq_: (other: Int32) Bool { return true }
                }
                """
            )
        }
        val conflicts = program.diagnostics.filter { it.code == "types.member.conflict" }
        assertTrue(conflicts.any { it.message.startsWith("Leaf inherits") }, render(program))
    }

    @Test
    fun aSuperclassDisagreeingWithOnlyOneForkedTraitBranchConflictsOnlyAtTheClass() {
        // A case the trait-level check (`traitOverrides`) cannot cover at all: it only ever
        // compares a trait's parents against each other, never against a class's superclass,
        // since a trait has none. `Base` agrees with `A`'s branch of `T`'s fork and disagrees only
        // with `B`'s, so the only diagnostic this specific disagreement can ever produce is `C`'s
        // own `reportConflictIfAny` call comparing `fromChain` (`Base`) against `fromTraits` (`T`,
        // as one root). `T` itself still independently reports its own `A` vs `B` disagreement (it
        // names both directly), but that call never mentions `Base`. If `frontierDeclarers`
        // regressed to one candidate per root (dropping `B`, since `A` matches `Base` and would be
        // found first), `C`'s comparison would never see a mismatch, even though `T`'s unrelated
        // report still exists -- so this asserts the specific diagnostic naming `C`.
        val program = phasesAAndB {
            snippet(
                """
                pub trait A {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait B {
                    pub fx m: (x: Int32) Int32 { return x }
                }
                pub trait T: A, B {
                }
                pub class Base {
                    pub fx m: () Int32 { return 1 }
                }
                pub class C: Base, T {
                }
                """
            )
        }
        val conflicts = program.diagnostics.filter { it.code == "types.member.conflict" }
        assertTrue(conflicts.any { it.message.startsWith("C inherits") }, render(program))
    }

    @Test
    fun aTraitOverridingTwoDisagreeingParentsConflictsRegardlessOfParentOrder() {
        // w2-9-1-parse round 4, significant issue #1: `traitOverrides` only ever checked a
        // trait's own declared method (`m` here) against the *first* parent `traitClosure` finds
        // (`checkOverride`), and never called `reportConflictIfAny` for `t.methods` at all --
        // `ownNames` excluded every name `t` redeclares from the `parentNames` loop, so `T: A, B`
        // declaring `m` itself dropped both `A` and `B` from `types.member.conflict` entirely.
        // Measured: this snippet gave 0 typer diagnostics before the fix.
        val program = phasesAAndB {
            snippet(
                """
                pub trait A {
                    pub fx m: () Int32
                }
                pub trait B {
                    pub fx m: (x: Int32) Int32
                }
                pub trait T: A, B {
                    override pub fx m: () Int32 { return 1 }
                }
                pub class C: T {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aTraitOverridingTwoDisagreeingParentsConflictsWithParentsSwapped() {
        // Same shape with the parents swapped (`T: B, A`): before the fix, swapping the parent
        // order changed the result (`types.override.signature` against `B` instead of a
        // conflict, since `checkOverride` compares only against whichever parent
        // `traitClosure` visits first) -- the conflict must fire either way.
        val program = phasesAAndB {
            snippet(
                """
                pub trait A {
                    pub fx m: () Int32
                }
                pub trait B {
                    pub fx m: (x: Int32) Int32
                }
                pub trait T: B, A {
                    override pub fx m: () Int32 { return 1 }
                }
                pub class C: T {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aTraitOverridingTwoDisagreeingOperatorParentsConflicts() {
        // The operator form of the two tests above (brief step 4 covers operators equally):
        // `EqBoth` redeclares `@_op_eq_` itself, matching only `EqA`'s signature. Measured: this
        // also gave 0 typer diagnostics before the fix.
        val program = phasesAAndB {
            snippet(
                """
                pub trait EqA {
                    pub fx @_op_eq_: (other: Int32) Bool;
                }
                pub trait EqB {
                    pub fx @_op_eq_: (other: Str) Bool;
                }
                pub trait EqBoth: EqA, EqB {
                    override pub fx @_op_eq_: (other: Int32) Bool { return true }
                }
                pub class C: EqBoth {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun mutMethodMismatchIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 5, significant issue #1: `sameSignature` ignored `isMutMethod`, so
        // two inherited declarations differing only in `mut fx` compared as "the same" and never
        // reached `types.member.conflict` -- the class's own diagnostic then depended entirely on
        // `checkOverride`'s first-found target (`base ?: viaTraits.firstOrNull()`), which flipped
        // with parent order. Measured: `class C: A, B {}` (bodies in the traits, no override at
        // all) gave 0 diagnostics in both orders even though one `m` is `mut` and the other is
        // not. Both orders must now conflict.
        val bodies = """
            pub trait A {
                pub fx m: () Int32 { return 1 }
            }
            pub trait B {
                pub mut fx m: () Int32 { return 2 }
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bodies\npub class C: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bodies\npub class C: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun mutMethodMismatchOnAMemberOperatorConflictsRegardlessOfParentOrder() {
        // Same shape as above, for the operator form the brief calls out specifically ("It
        // matters most for @_op_set_, whose mut-ness 1.3.2 says varies by class").
        val ops = """
            pub trait SA {
                pub fx @_op_set_: (i: Int32, v: Int32) Void { }
            }
            pub trait SB {
                pub mut fx @_op_set_: (i: Int32, v: Int32) Void { }
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$ops\npub class C: SA, SB {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$ops\npub class C: SB, SA {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun typeParameterBoundsMismatchIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 5, significant issue #1: `sameSignature` never compared a method's
        // own type-parameter bounds, so `A`'s `m<U: X>` and `B`'s `m<U>` (no bound) compared as
        // "the same" method. Measured: `class C: A, B {}` gave 0 diagnostics in both orders even
        // though the two `m`s disagree on their bound. Both orders must now conflict.
        val bounds = """
            pub trait X {
            }
            pub trait A {
                pub fx m<U: X>: (u: U) U;
            }
            pub trait B {
                pub fx m<U>: (u: U) U;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bounds\npub class C: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bounds\npub class C: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun returnTypeMismatchIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 6, significant issue #1: `sameSignature`'s return-type comparison
        // (SignatureResolver.kt:754) has no test that isolates it -- every existing conflict
        // test also differs in parameter type or arity. Mutant R8 replaces the whole comparison
        // with `return true`, and the suite still passes 932/0: `A`'s `m: () Int32` and `B`'s
        // `m: () Str` (same arity, same params, same mut-ness, same bounds; only the return type
        // disagrees) would then compare equal and the conflict would vanish. Both orders must
        // conflict.
        val bodies = """
            pub trait A {
                pub fx m: () Int32;
            }
            pub trait B {
                pub fx m: () Str;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bodies\npub class C: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bodies\npub class C: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun byRefMismatchIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 6, significant issue #1: `sameSignature`'s byRef comparison
        // (SignatureResolver.kt:750) has no test either. Mutant R6 drops
        // `|| a.params[i].byRef != b.params[i].byRef`, and the suite still passes 932/0: `A`'s
        // `m: (a: Int32)` and `B`'s `m: (mut a: Int32)` (same type, same arity; only the
        // parameter's byRef-ness disagrees) would then compare equal. Both orders must conflict.
        val bodies = """
            pub trait A {
                pub fx m: (a: Int32) Int32;
            }
            pub trait B {
                pub fx m: (mut a: Int32) Int32;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bodies\npub class C: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bodies\npub class C: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun twoBoundsOfEqualCountButDifferentTargetIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 6, significant issue #2: round 5's bounds check is tested only as
        // "has a bound" vs "has none". Mutant R5 (SignatureResolver.kt:743) compares only how
        // many bounds each side has, not what they are. `A`'s `m<U: X>` and `B`'s `m<U: Y>` both
        // have exactly one bound, so the mutant sees them as the same and the suite still passes
        // 932/0, half-reverting round 5's fix. Both orders must conflict.
        val bounds = """
            pub trait X {
            }
            pub trait Y {
            }
            pub trait A {
                pub fx m<U: X>: (u: U) U;
            }
            pub trait B {
                pub fx m<U: Y>: (u: U) U;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bounds\npub class C: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bounds\npub class C: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun aSubstitutedBoundAgreeingWithTheOtherSideIsNoConflict() {
        // w2-9-1-parse round 6, significant issue #2: the bounds line's `aSub` substitution
        // (SignatureResolver.kt:741) has no test. Mutant R3 drops it, so `A<T>`'s own type
        // parameter `T` is never replaced by the class's actual argument (`Int32` here) before
        // comparing bounds. `A<Int32>`'s `m<U: Marker<T>>` and `B`'s `m<U: Marker<Int32>>` agree
        // once `T` is substituted to `Int32`, so this must never conflict -- with `aSub` dropped
        // it falsely does, but (the verifier's measurement) only when `A` is the class's first
        // parent, so both orders are asserted.
        val bounds = """
            pub trait Marker<T> {
            }
            pub trait A<T> {
                pub fx m<U: Marker<T>>: (u: U) U;
            }
            pub trait B {
                pub fx m<U: Marker<Int32>>: (u: U) U;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bounds\npub class C: A<Int32>, B {\n}") }
        assertFalse(ab.diagnostics.any { it.code == "types.member.conflict" }, render(ab))
        val ba = phasesAAndB { snippet("$bounds\npub class C: B, A<Int32> {\n}") }
        assertFalse(ba.diagnostics.any { it.code == "types.member.conflict" }, render(ba))
    }

    @Test
    fun anFBoundAgreeingUnderItsOwnTypeParameterIsNoConflict() {
        // w2-9-1-parse round 6, significant issue #2: the bounds line's `ownMap` substitution
        // (SignatureResolver.kt:739/741) has no test either. Mutant R4 drops it, so a method's
        // own type parameter is never mapped onto the other side's before comparing bounds.
        // `F`'s `m<U: Marker<U>>` and `G`'s `m<V: Marker<V>>` are the same F-bounded shape under
        // different names, so this must never conflict -- with `ownMap` dropped it falsely does.
        val bounds = """
            pub trait Marker<T> {
            }
            pub trait F {
                pub fx m<U: Marker<U>>: (u: U) U;
            }
            pub trait G {
                pub fx m<V: Marker<V>>: (v: V) V;
            }
        """.trimIndent()
        val fg = phasesAAndB { snippet("$bounds\npub class C: F, G {\n}") }
        assertFalse(fg.diagnostics.any { it.code == "types.member.conflict" }, render(fg))
        val gf = phasesAAndB { snippet("$bounds\npub class C: G, F {\n}") }
        assertFalse(gf.diagnostics.any { it.code == "types.member.conflict" }, render(gf))
    }

    @Test
    fun aTraitOverridingAMemberOperatorReplacesItInFlatten() {
        // w2-9-1-parse round 5, significant issue #2: `SignatureResolver.flatten` must merge a
        // member operator by name (`m.isFreeOperator`), like any other method -- mutant M7
        // reverted that one line to `m.isOperator` (always false for a member operator, since
        // `isOperator` is set on both forms and only `isFreeOperator` tells them apart) and every
        // one of the suite's 930 tests still passed, because nothing pinned `flatten`'s own
        // output. With the mutant, `Q`'s closure keeps `P`'s un-overridden `@_op_eq_` *ahead of*
        // `Q`'s own (appended, never replacing it by name), so `Q.method("_op_eq_")` -- which
        // reads `flatMethods` first -- silently resolves back to `P`'s declaration.
        val program = phasesAAndB {
            snippet(
                """
                pub trait P {
                    pub fx @_op_eq_: (o: Int32) Bool { return false }
                    pub fx name: () Int32 { return 0 }
                }
                pub trait Q: P {
                    override pub fx @_op_eq_: (o: Int32) Bool { return true }
                }
                """
            )
        }
        expectNoErrors(program)
        val module = assertNotNull(program.module("test:main"))
        val q = module.members["Q"] as? TraitSymbol ?: error("no trait Q: ${module.members.keys}")
        val p = module.members["P"] as? TraitSymbol ?: error("no trait P: ${module.members.keys}")
        val qOwnEq = q.methods.single { it.name == "_op_eq_" }
        assertTrue(
            q.flatMethods.count { it.name == "_op_eq_" } == 1,
            "Q's own @_op_eq_ must replace P's by name in flatten, not sit beside it: ${q.flatMethods.map { it.qualifiedName }}",
        )
        assertTrue(
            q.method("_op_eq_") === qOwnEq,
            "Q.method(_op_eq_) must resolve to Q's own override, not P.method: ${q.method("_op_eq_")?.qualifiedName}",
        )
        assertTrue(q.flatMethods.none { it === p.method("_op_eq_") && it !== qOwnEq })
    }

    @Test
    fun typeParameterCountMismatchIsAConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 7, significant issue #1: `sameSignature`'s type-parameter *count*
        // comparison (SignatureResolver.kt:733) has no test at all -- every existing conflict
        // test compares two methods with the same arity of type parameters. Mutant S1 drops
        // `a.typeParams.size != b.typeParams.size`, and the suite still passes 937/0: `A`'s
        // plain `m` and `B`'s `m<U>` (same value parameters) would then fall through to the
        // bounds loop, which indexes `b.typeParams[i]` up to `a.typeParams.size` -- with `A`
        // first that reads past the end of `B`'s empty `typeParams` and throws, and with `B`
        // first it silently drops the conflict (0 bounds either way, so the loop never mismatches
        // on its own). Both orders must report the conflict, and neither may throw.
        val bodies = """
            pub trait A {
                pub fx m: (x: Int32) Int32;
            }
            pub trait B {
                pub fx m<U>: (x: Int32) Int32;
            }
        """.trimIndent()
        val ab = phasesAAndB { snippet("$bodies\npub class C1: A, B {\n}") }
        expectDiagnostic(ab, "types.member.conflict")
        val ba = phasesAAndB { snippet("$bodies\npub class C2: B, A {\n}") }
        expectDiagnostic(ba, "types.member.conflict")
    }

    @Test
    fun aSubstitutedParameterTypeAgreeingWithTheOtherSideIsNoConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 7, significant issue #2: `sameSignature`'s `aSub` substitution on an
        // ordinary parameter type (SignatureResolver.kt:748) has no test -- the only 'agree' test
        // (`twoInheritedMethodsOfOneNameAgreeingIsNoConflict`) puts the generic side second (as
        // `b`, never substituted through `aSub`). Mutant S9 drops `.substitute(aSub)` there, so
        // `G<T>.m(t: T)`'s parameter stays the unsubstituted type parameter `T` instead of
        // becoming `Int32`, and a false conflict appears -- but only when `G` is read first
        // (`aSub` applies to `a`), so both orders are asserted.
        val bodies = """
            pub trait G<T> {
                pub fx m: (t: T) Int32;
            }
            pub trait H {
                pub fx m: (t: Int32) Int32;
            }
        """.trimIndent()
        val gh = phasesAAndB { snippet("$bodies\npub class GH: G<Int32>, H {\n}") }
        assertFalse(gh.diagnostics.any { it.code == "types.member.conflict" }, render(gh))
        val hg = phasesAAndB { snippet("$bodies\npub class HG: H, G<Int32> {\n}") }
        assertFalse(hg.diagnostics.any { it.code == "types.member.conflict" }, render(hg))
    }

    @Test
    fun aSubstitutedGenericReturnTypeAgreeingWithTheOtherSideIsNoConflictRegardlessOfParentOrder() {
        // w2-9-1-parse round 7, significant issue #2: `sameSignature`'s return-type substitutions
        // (SignatureResolver.kt:754) have no test with a generic return type at all. Mutant S15
        // drops `.substitute(aSub)` on the return (a false conflict when the generic side is read
        // first, `a`); mutant S17 drops `.substitute(bSub)` (a false conflict when the generic
        // side is read second, `b`). `G<T>.m(): T` and `H.m(): Int32` must never conflict once
        // `G`'s type argument (`Int32`) is substituted in, in either order.
        val bodies = """
            pub trait G<T> {
                pub fx m: () T;
            }
            pub trait H {
                pub fx m: () Int32;
            }
        """.trimIndent()
        val gh = phasesAAndB { snippet("$bodies\npub class GH: G<Int32>, H {\n}") }
        assertFalse(gh.diagnostics.any { it.code == "types.member.conflict" }, render(gh))
        val hg = phasesAAndB { snippet("$bodies\npub class HG: H, G<Int32> {\n}") }
        assertFalse(hg.diagnostics.any { it.code == "types.member.conflict" }, render(hg))
    }

    @Test
    fun aGenericSuperclassStillDisagreesWithATraitOnceSubstituted() {
        // Companion to the two tests above, through a *superclass* rather than two traits (the
        // verifier's other measured shape): `Base<T>: X<T>` overrides `m` itself, so `C1`'s
        // `fromChain` finds `Base`'s declaration with its own substitution; it must still be
        // compared against `Y`'s under `sameSignature`'s substitutions, and must actually
        // disagree once `T` is substituted to `Str` against `Y`'s `Int32`.
        val program = phasesAAndB {
            snippet(
                """
                pub trait X<T> {
                    pub fx m: (t: T) Int32;
                }
                pub class Base<T>: X<T> {
                    override pub fx m: (t: T) Int32 { return 0 }
                }
                pub trait Y {
                    pub fx m: (t: Int32) Int32;
                }
                pub class C1: Base<Str>, Y {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aTraitOverrideMismatchAgainstItsSingleParentIsTypesOverrideSignature() {
        // w2-9-1-parse round 7, significant issue #3: `traitOverrides`' own `checkOverride` call
        // (SignatureResolver.kt:466) has no test that isolates it -- mutant T3 deletes the whole
        // line and the suite still passes 937/0, because `frontierDeclarers`'s ancestor filter
        // then drops `P` from `TBad`'s own `parentNames` loop too (`TBad` extends `P`, so `P` is
        // never an unrelated fork), leaving no diagnostic anywhere, not even on `UsesTBad`.
        val program = phasesAAndB {
            snippet(
                """
                pub trait P {
                    pub fx m: (x: Int32) Int32;
                }
                pub trait TBad: P {
                    override pub fx m: (x: Str) Int32 { return 1 }
                }
                pub class UsesTBad: TBad {
                }
                """
            )
        }
        expectDiagnostic(program, "types.override.signature")
    }

    @Test
    fun aGenericDiamondAcrossTwoRootsStillConflicts() {
        // w2-9-1-parse round 7, significant issue #4: `fromTraits` keeps one candidate per
        // (declaration, substitution) -- mutant F6 appends `.distinctBy { it.first }` to line 674
        // and the suite still passes 937/0, because `X.k` is a *single* trait method symbol
        // reached twice, through `A` (substituting `T` to `Int32`) and through `B` (substituting
        // it to `Str`): `it.first` is the same `FnSymbol` both times, so `distinctBy` collapses
        // them to one candidate and `reportConflictIfAny` never has a second one to compare
        // against. `A` and `B` disagreeing this way must still refuse `C`.
        val program = phasesAAndB {
            snippet(
                """
                pub trait X<T> {
                    pub fx k: (t: T) Int32;
                }
                pub trait A: X<Int32> {
                }
                pub trait B: X<Str> {
                }
                pub class C: A, B {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aSuperclassChainsOwnTraitStillConflictsWithADirectTrait() {
        // w2-9-1-parse round 7, significant issue #5 (O3): `overrides`' `traitRoots` includes the
        // superclass chain's own traits (SignatureResolver.kt:565-566), not just the class's
        // direct ones -- mutant O3 drops that half of the union and the suite still passes
        // 937/0. `Base` implements `X` (never overriding `m` itself) and `C` implements `Y`
        // directly; `C` inherits both and must refuse the disagreement even though `X` only
        // reaches `C` through `Base`'s chain, never as one of `C`'s own direct traits.
        val program = phasesAAndB {
            snippet(
                """
                pub trait X {
                    pub fx m: () Int32 { return 1 }
                }
                pub class Base: X {
                }
                pub trait Y {
                    pub fx m: () Str { return "y" }
                }
                pub class C: Base, Y {
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun aGenericSuperclassChainTraitAgreesWithADirectTraitOnceSubstituted() {
        // w2-9-1-parse round 7, significant issue #5 (O4): the substitution `overrides` carries
        // for a superclass chain's own traits (SignatureResolver.kt:566, the chain's `s`) must
        // actually be used -- mutant O4 gives those chain roots `emptyMap()` instead, and the
        // suite still passes 937/0. `Base<T>: X<T>` reaches `C1` with `T` substituted to `Int32`;
        // with the substitution dropped, `X`'s parameter stays the bare type parameter `T` and a
        // false conflict appears against `Y`'s already-concrete `Int32`. There must be none.
        val program = phasesAAndB {
            snippet(
                """
                pub trait X<T> {
                    pub fx m: (t: T) Int32 { return 0 }
                }
                pub class Base<T>: X<T> {
                }
                pub trait Y {
                    pub fx m: (t: Int32) Int32 { return 0 }
                }
                pub class C1: Base<Int32>, Y {
                }
                """
            )
        }
        assertFalse(program.diagnostics.any { it.code == "types.member.conflict" }, render(program))
    }

    @Test
    fun aClasssOwnOverrideStillConflictsWithATraitBesideItsSuperclass() {
        // w2-9-1-parse round 7, significant issue #5 (O8): the per-method loop's own
        // `reportConflictIfAny` call (SignatureResolver.kt:585) must compare against `base`, not
        // some name-only lookup -- mutant O8 passes `null` there instead of `base`, and the suite
        // still passes 937/0. `D` declares its own `m`, matching its superclass `Bs` exactly, but
        // `Bs` and the trait `Y` disagree; `D`'s own declaration does not resolve which of the two
        // it means, so the conflict must still fire at `D`, not just silently link to `Bs`.
        val program = phasesAAndB {
            snippet(
                """
                pub class Bs {
                    pub fx m: () Int32 { return 1 }
                }
                pub trait Y {
                    pub fx m: () Str { return "y" }
                }
                pub class D: Bs, Y {
                    override pub fx m: () Int32 { return 2 }
                }
                """
            )
        }
        expectDiagnostic(program, "types.member.conflict")
    }

    @Test
    fun finalClassExtendedIsTypesClassFinal() {
        val program = phasesAAndB {
            snippet(
                """
                pub final class Base {
                    pub x: Int32 = 0
                }
                pub class Leaf: Base {
                }
                """
            )
        }
        expectDiagnostic(program, "types.class.final")
    }

    @Test
    fun aNonFinalClassExtendsWithNoError() {
        val program = phasesAAndB {
            snippet(
                """
                pub class Base {
                    pub x: Int32 = 0
                }
                pub class Leaf: Base {
                }
                """
            )
        }
        assertFalse(program.diagnostics.any { it.code == "types.class.final" }, render(program))
    }
}
