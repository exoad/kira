package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
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
