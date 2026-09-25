package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Declarations phases A and B must reject, each with its diagnostic code. */
class TyperDeclDiagnosticsTest {
    private fun count(program: net.exoad.kira.compiler.analysis.types.TypedProgram, code: String) =
        program.diagnostics.count { it.code == code }

    // ---- the brief's five ----------------------------------------------------------------

    @Test
    fun unknownType() {
        val p = snippet("x: Nope = 1\nfx f: (a: Missing) Void { }")
        assertEquals(2, count(p, "types.type.unknown"), TyperTestSupport.render(p))
        val d = expectDiagnostic(p, "types.type.unknown")
        assertEquals(Severity.ERROR, d.severity)
        assertTrue(d.message.contains("Nope"), d.message)
        assertEquals(3, d.position?.lineNumber, "located at the Type node")
        val x = p.workspaceModules.single().members["x"] as GlobalSymbol
        assertEquals(KType.Error, x.type)
    }

    @Test
    fun unknownTypeInsideABodyIsReportedInPhaseB() {
        val p = snippet(
            """
            fx f: () Void {
                local: Missing = 1
            }
            """
        )
        assertEquals(1, count(p, "types.type.unknown"), TyperTestSupport.render(p))
    }

    @Test
    fun aliasCycle() {
        val p = snippet("alias A as B\nalias B as A\nalias C as A")
        assertEquals(1, count(p, "types.alias.cycle"), TyperTestSupport.render(p))
        val members = p.workspaceModules.single().members
        for (name in listOf("A", "B", "C")) {
            assertEquals(KType.Error, (members[name] as AliasSymbol).target, name)
        }
        assertTrue(expectDiagnostic(p, "types.alias.cycle").message.contains("A -> B -> A"))
    }

    @Test
    fun selfRecursiveAlias() {
        val p = snippet("alias Chain as List<Chain>")
        assertEquals(1, count(p, "types.alias.cycle"), TyperTestSupport.render(p))
    }

    @Test
    fun duplicateDeclaration() {
        val p = snippet(
            """
            fx f: () Void { }
            fx f: () Void { }
            LIMIT: Int32 = 1
            LIMIT: Int32 = 2
            class Point {
                require pub x: Int32
                require pub x: Int32
            }
            enum E { A, A }
            fx g: (a: Int32, a: Int32) Void { }
            """
        )
        assertEquals(5, count(p, "types.decl.duplicate"), TyperTestSupport.render(p))
        val m = p.workspaceModules.single()
        assertEquals(2, m.declarations.count { it.name == "f" }, "both are collected; the first keeps the name")
        assertTrue(m.members["f"] === m.declarations.first { it.name == "f" })
    }

    @Test
    fun enumValueOfTheWrongType() {
        val p = snippet(
            """
            enum Small: Int8 { OK = 1, BIG = 300 }
            enum Short: Int16 { LOW = -32768, OVER = 40000 }
            """
        )
        assertEquals(2, count(p, "types.enum.value"), TyperTestSupport.render(p))
        val small = p.workspaceModules.single().members["Small"] as EnumSymbol
        assertEquals("1:Int8", small.entries[0].value.toString())
        assertNull(small.entries[1].value)
        val short = p.workspaceModules.single().members["Short"] as EnumSymbol
        assertEquals("-32768:Int16", short.entries[0].value.toString())
    }

    @Test
    fun classInheritingTwoClasses() {
        val p = snippet(
            """
            class A { }
            class B { }
            class C: A, B { }
            """
        )
        assertEquals(1, count(p, "types.class.multiple-superclasses"), TyperTestSupport.render(p))
        val c = p.workspaceModules.single().members["C"] as ClassSymbol
        assertEquals("A", c.superclass?.sym?.name)
        assertTrue(c.traits.isEmpty())
    }

    // ---- parents -------------------------------------------------------------------------

    @Test
    fun superclassMustComeFirst() {
        val p = snippet(
            """
            trait T { fx t: () Void }
            class A { }
            class C: T, A { }
            """
        )
        expectDiagnostic(p, "types.class.superclass-not-first")
    }

    @Test
    fun inheritanceCyclesAreErrors() {
        val p = snippet(
            """
            class A: B { }
            class B: A { }
            trait P: Q { }
            trait Q: P { }
            """
        )
        // One report per cycle: the first class found on it drops its parent, which breaks the cycle.
        assertEquals(1, count(p, "types.class.inheritance-cycle"), TyperTestSupport.render(p))
        assertEquals(1, count(p, "types.trait.inheritance-cycle"), TyperTestSupport.render(p))
    }

    @Test
    fun badParents() {
        val p = snippet(
            """
            enum E { X }
            class A { }
            class C: E { }
            trait T: A { }
            class D: Int32 { }
            """
        )
        assertEquals(2, count(p, "types.class.bad-parent"), TyperTestSupport.render(p))
        assertEquals(1, count(p, "types.trait.bad-parent"), TyperTestSupport.render(p))
    }

    @Test
    fun anOverrideKeepsTheMutOfWhatItOverrides() {
        val p = snippet(
            """
            pub class Stepper {
                pub mut fx step: () Void { }
                pub fx peek: () Int32 {
                    return 0
                }
            }
            pub class Good: Stepper {
                pub mut fx step: () Void { }
                pub fx peek: () Int32 {
                    return 1
                }
            }
            pub class Bad: Stepper {
                pub fx step: () Void { }
                pub mut fx peek: () Int32 {
                    return 1
                }
            }
            """
        )
        assertEquals(2, count(p, "types.override.signature"), TyperTestSupport.render(p))
        assertTrue(p.diagnostics.filter { it.code == "types.override.signature" }.all { it.message.startsWith("Bad.") })
    }

    // ---- visibility and modules ----------------------------------------------------------

    @Test
    fun nonPubTypeOfAUsedModuleIsNotVisible() {
        val p = TyperTestSupport.type(
            module("app:lib", "class Hidden { }\npub class Shown { }"),
            module("app:main", "use \"app:lib\"\na: Shown = Shown { }\nb: Hidden = Hidden { }"),
        )
        assertEquals(2, count(p, "types.type.not-visible"), TyperTestSupport.render(p))
        val main = p.module("app:main")!!
        assertEquals("Hidden", (main.members["b"] as GlobalSymbol).type.toString(), "still resolved, so later phases see the type")
        assertEquals(0, count(p, "types.type.unknown"))
    }

    @Test
    fun aTypeFromAModuleThatIsNotUsedIsUnknown() {
        val p = TyperTestSupport.type(
            module("app:lib", "pub class Shown { }"),
            module("app:main", "a: Shown = Shown { }"),
        )
        assertEquals(2, count(p, "types.type.unknown"), TyperTestSupport.render(p))
    }

    @Test
    fun useOfAnUnknownModule() {
        val p = snippet("use \"app:nowhere\"")
        expectDiagnostic(p, "types.use.unknown-module")
    }

    @Test
    fun ambiguousExport() {
        val p = TyperTestSupport.type(
            module("app:one", "pub class Thing { }"),
            module("app:two", "pub class Thing { }"),
            module("app:main", "use \"app:one\"\nuse \"app:two\"\nt: Thing = Thing { }"),
        )
        assertTrue(count(p, "types.type.ambiguous") >= 1, TyperTestSupport.render(p))
    }

    @Test
    fun twoFilesDeclaringOneModule() {
        val p = TyperTestSupport.type(
            TyperTestSupport.Src("a/one.kira", "module \"app:same\"\nx: Int32 = 1\n"),
            TyperTestSupport.Src("b/two.kira", "module \"app:same\"\ny: Int32 = 2\n"),
        )
        expectDiagnostic(p, "types.module.duplicate")
        assertEquals(1, p.modules.count { it.uri == "app:same" })
    }

    // ---- type shapes ---------------------------------------------------------------------

    @Test
    fun arityAndShapeErrors() {
        val p = snippet(
            """
            a: List<Int32, Int32> = 1
            b: Map<Str> = 1
            c: Int32<Str> = 1
            d: Arr<Int32, Str> = 1
            e: Arr<Int32, Int32, Int32> = 1
            """
        )
        assertEquals(4, count(p, "types.type.arity"), TyperTestSupport.render(p))
        assertEquals(1, count(p, "types.type.const-arg"), TyperTestSupport.render(p))
    }

    @Test
    fun fxNeedsATupleOfParameters() {
        val p = snippet("f: Fx<Int32, Void> = 1\ng: Fx<Tuple1<Int32>, Int32> = 1")
        assertEquals(1, count(p, "types.fx.shape"), TyperTestSupport.render(p))
        val g = p.workspaceModules.single().members["g"] as GlobalSymbol
        assertTrue(g.type is KType.Fn, g.type.toString())
    }

    @Test
    fun aValueNamedInTypePosition() {
        val p = snippet("fx helper: () Void { }\nx: helper = 1")
        expectDiagnostic(p, "types.type.not-a-type")
    }

    @Test
    fun aMutableGlobalCannotSizeAnArr() {
        val p = snippet("mut COUNT: Size = 4\nbuf: Arr<UInt8, COUNT> = [1, 2, 3, 4]")
        expectDiagnostic(p, "types.type.const-arg")
    }

    // ---- constants -----------------------------------------------------------------------

    @Test
    fun constantFoldingReportsOverflowAndDivisionByZero() {
        val p = snippet(
            """
            BIG: Int32 = 2147483647 + 1
            ZERO: Int32 = 1 / 0
            WRAP: UInt8 = 255 + 1
            HUGE: Size = 4294967295 + 1
            """
        )
        assertEquals(2, count(p, "types.const.overflow"), TyperTestSupport.render(p))
        assertEquals(1, count(p, "types.const.div-by-zero"), TyperTestSupport.render(p))
        val wrap = p.workspaceModules.single().members["WRAP"] as GlobalSymbol
        assertEquals("0:UInt8", wrap.constValue.toString(), "unsigned arithmetic wraps (D8)")
    }

    @Test
    fun aConstantThatDependsOnItself() {
        val p = snippet("A: Int32 = B + 1\nB: Int32 = A + 1")
        expectDiagnostic(p, "types.const.cycle")
        assertNull((p.workspaceModules.single().members["A"] as GlobalSymbol).constValue)
    }

    @Test
    fun strBufTakesOnePositiveConstant() {
        val p = snippet("CAP: Size = 64\nZERO: Size = 0\na: StrBuf<CAP> = 1\nb: StrBuf<Int32> = 1\nc: StrBuf<ZERO> = 1\nd: CStr = 1")
        assertEquals(2, count(p, "types.type.const-arg"), TyperTestSupport.render(p))
        val m = p.workspaceModules.single()
        assertEquals("StrBuf<64>", (m.members["a"] as GlobalSymbol).type.toString())
        assertEquals("CStr", (m.members["d"] as GlobalSymbol).type.toString())
    }

    @Test
    fun aLiteralOutOfRangeDoesNotFoldAndIsLeftToPhaseC() {
        val p = snippet("X: UInt8 = 256")
        val x = p.workspaceModules.single().members["X"] as GlobalSymbol
        assertNull(x.constValue)
        assertTrue(p.diagnostics.isEmpty(), "W2.1's literal checks report it: ${TyperTestSupport.render(p)}")
    }

    // ---- modes and failures --------------------------------------------------------------

    @Test
    fun lenientDowngradesErrorsToWarnings() {
        val p = snippet("x: Nope = 1", mode = TyperMode.LENIENT)
        val d = expectDiagnostic(p, "types.type.unknown")
        assertEquals(Severity.WARNING, d.severity)
        assertTrue(!p.hasErrors)
    }
}
