package net.exoad.kira.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Builder
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.types.TyperTestSupport.astModule
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.fn
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.param
import net.exoad.kira.types.TyperTestSupport.snippet
import net.exoad.kira.types.TyperTestSupport.ty
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
        val p = snippet("x: Nope = 1\nf: (a: Missing) Void { }")
        assertEquals(2, count(p, "types.type.unknown"), TyperTestSupport.render(p))
        val d = expectDiagnostic(p, "types.type.unknown")
        assertEquals(Severity.ERROR, d.severity)
        assertTrue(d.message.contains("Nope"), d.message)
        assertEquals(1, d.position?.lineNumber, "located at the Type node")
        val x = p.workspaceModules.single().members["x"] as GlobalSymbol
        assertEquals(KType.Error, x.type)
    }

    @Test
    fun unknownTypeInsideABodyIsReportedInPhaseB() {
        val p = snippet(
            """
            f: () Void {
                local: Missing = 1
            }
            """
        )
        assertEquals(1, count(p, "types.type.unknown"), TyperTestSupport.render(p))
    }

    @Test
    fun aliasCycle() {
        val p = snippet("B as A\nA as B\nA as C")
        assertEquals(1, count(p, "types.alias.cycle"), TyperTestSupport.render(p))
        val members = p.workspaceModules.single().members
        for (name in listOf("A", "B", "C")) {
            assertEquals(KType.Error, (members[name] as AliasSymbol).target, name)
        }
        assertTrue(expectDiagnostic(p, "types.alias.cycle").message.contains("A -> B -> A"))
    }

    @Test
    fun selfRecursiveAlias() {
        val p = snippet("List<Chain> as Chain")
        assertEquals(1, count(p, "types.alias.cycle"), TyperTestSupport.render(p))
    }

    @Test
    fun duplicateDeclaration() {
        val p = snippet(
            """
            f: () Void { }
            f: () Void { }
            LIMIT: Int32 = 1
            LIMIT: Int32 = 2
            Point: Any {
                pub x: Int32
                pub x: Int32
            }
            g: (a: Int32, a: Int32) Void { }
            """
        )
        assertEquals(4, count(p, "types.decl.duplicate"), TyperTestSupport.render(p))
        val m = p.workspaceModules.single()
        assertEquals(2, m.declarations.count { it.name == "f" }, "both are collected; the first keeps the name")
        assertTrue(m.members["f"] === m.declarations.first { it.name == "f" })
    }

    @Test
    fun classInheritingTwoClasses() {
        val p = snippet(
            """
            A: Any { a: Int32 = 0 }
            B: Any { b: Int32 = 0 }
            C: A, B { }
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
            T: Any { t: () Void }
            A: Any { a: Int32 = 0 }
            C: T, A { }
            """
        )
        expectDiagnostic(p, "types.class.superclass-not-first")
    }

    @Test
    fun inheritanceCyclesAreErrors() {
        val p = snippet(
            """
            A: B { a: Int32 = 0 }
            B: A { b: Int32 = 0 }
            P: Q { }
            Q: P { }
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
            D: Int32 { }
            """
        )
        assertEquals(1, count(p, "types.class.bad-parent"), TyperTestSupport.render(p))
    }

    @Test
    fun anOverrideKeepsTheMutOfWhatItOverrides() {
        val p = snippet(
            """
            pub Stepper: Any {
                pub mut step: () Void { }
                pub peek: () Int32 {
                    0
                }
            }
            pub Good: Stepper {
                pub mut step: () Void { }
                pub peek: () Int32 {
                    1
                }
            }
            pub Bad: Stepper {
                pub step: () Void { }
                pub mut peek: () Int32 {
                    1
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
            module("app:lib", "Hidden: Any { pub n: Int32 = 0 }\npub Shown: Any { pub n: Int32 = 0 }"),
            module("app:main", "app.lib as lib\napp.lib.Shown as Shown\na: Shown = Shown { }\nb: lib.Hidden = lib.Hidden { }"),
        )
        assertEquals(2, count(p, "types.type.not-visible"), TyperTestSupport.render(p))
        val main = p.module("app:main")!!
        assertEquals("Hidden", (main.members["b"] as GlobalSymbol).type.toString(), "still resolved, so later phases see the type")
        assertEquals(0, count(p, "types.type.unknown"))
        // A member alias of a name that is not pub is refused where it is written.
        val alias = TyperTestSupport.type(
            module("app:lib", "Hidden: Any { pub n: Int32 = 0 }"),
            module("app:main", "app.lib.Hidden as Hidden\nb: Hidden = Hidden { }"),
        )
        assertTrue(expectDiagnostic(alias, "types.use.not-visible").message.contains("'Hidden' is not pub in module 'app.lib'"), TyperTestSupport.render(alias))
    }

    @Test
    fun aTypeFromAModuleThatIsNotUsedIsUnknown() {
        val p = TyperTestSupport.type(
            module("app:lib", "pub Shown: Any { pub n: Int32 = 0 }"),
            module("app:main", "a: Shown = Shown { }"),
        )
        assertEquals(2, count(p, "types.type.unknown"), TyperTestSupport.render(p))
    }

    @Test
    fun useOfAnUnknownModule() {
        val p = snippet("app.nowhere as nowhere")
        expectDiagnostic(p, "types.use.unknown-module")
    }

    // Stage 5: two imports with one name leave it out of scope bare; a member alias chooses.
    @Test
    fun ambiguousExport() {
        val one = module("app:one", "pub Thing: Any { pub n: Int32 = 0 }")
        val two = module("app:two", "pub Thing: Any { pub n: Int32 = 0 }")
        val p = TyperTestSupport.type(one, two, module("app:main", "app.one as one\napp.two as two\nt: Thing = Thing { }"))
        assertTrue(expectDiagnostic(p, "types.use.bare").message.contains("add `app.one.Thing as Thing` or write `one.Thing`"), TyperTestSupport.render(p))
        val chosen = TyperTestSupport.type(one, two, module("app:main", "app.one as one\napp.two as two\napp.two.Thing as Thing\nt: Thing = Thing { }"))
        TyperTestSupport.expectNoErrors(chosen)
        assertEquals("app:two", (chosen.graph.lookup(chosen.module("app:main")!!, "Thing") as ModuleGraph.Lookup.Found).from.uri)
    }

    // bibo's tools/npu uses kira:math and its own pylib, whose hypot, atan2 and exp K5's kira:math gained.
    @Test
    fun aUsedProjectModuleBeatsAUsedStdlibModule() {
        val lib = module("app:lib", "pub exp: (x: Float64) Float64 {\n    x\n}\npub Regex: Any { pub n: Int32 = 0 }\npub IGNORE_CASE: Int32 = 7")
        val body = "f: () Float64 {\n    r: Regex = Regex { }\n    exp(2.0) + sqrt(4.0) + IGNORE_CASE as Float64\n}\n"
        // Each name the project means is its member alias, which beats the ambient stdlib.
        val p = TyperTestSupport.type(
            lib,
            module("app:main", "kira.math as math\nkira.regex as regex\napp.lib as lib\napp.lib.exp as exp\napp.lib.Regex as Regex\napp.lib.IGNORE_CASE as IGNORE_CASE\n$body"),
        )
        TyperTestSupport.expectNoErrors(p)
        val main = p.module("app:main")!!
        for (name in listOf("exp", "Regex", "IGNORE_CASE")) {
            val hit = p.graph.lookup(main, name) as ModuleGraph.Lookup.Found
            assertEquals("app:lib", hit.from.uri, name)
        }
        assertEquals("kira:math", (p.graph.lookup(main, "sqrt") as ModuleGraph.Lookup.Found).from.uri)
        // Without them a name both have is neither bare (old text meant the project's); one only the stdlib has is the stdlib's.
        val bare = TyperTestSupport.type(lib, module("app:main", "kira.math as math\napp.lib as lib\nf: () Float64 {\n    exp(2.0) + sqrt(4.0)\n}\n"))
        assertTrue(
            expectDiagnostic(bare, "types.use.bare").message ==
                "'exp' is not in scope, as app.lib and kira.math both have it: add `app.lib.exp as exp` or write `lib.exp`, or for kira.math's add `kira.math.exp as exp` or write `math.exp`.",
            TyperTestSupport.render(bare),
        )
        val main2 = bare.module("app:main")!!
        assertEquals(ModuleGraph.Lookup.Missing, bare.graph.lookup(main2, "exp"))
        assertEquals("kira:math", (bare.graph.lookup(main2, "sqrt") as ModuleGraph.Lookup.Found).from.uri)
        // Not imported, the project's exp is no one's here: the stdlib's it is.
        TyperTestSupport.expectNoErrors(TyperTestSupport.type(lib, module("app:main", "kira.math as math\nf: () Float64 {\n    exp(2.0)\n}\n")))
    }

    @Test
    fun twoUsedProjectModulesBesideAStdlibOneAreStillAmbiguous() {
        val one = module("app:one", "pub exp: (x: Float64) Float64 {\n    x\n}")
        val two = module("app:two", "pub exp: (x: Float64) Float64 {\n    x\n}")
        // One alias names one thing: two member aliases spelled exp are refused.
        val p = TyperTestSupport.type(one, two, module("app:main", "app.one.exp as exp\napp.two.exp as exp\nf: () Float64 {\n    exp(2.0)\n}\n"))
        assertTrue(expectDiagnostic(p, "types.use.alias-taken").message.contains("'exp' already names app.one.exp"), TyperTestSupport.render(p))
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
        val p = snippet("helper: () Void { }\nx: helper = 1")
        expectDiagnostic(p, "types.type.not-a-type")
    }

    @Test
    fun aMutableGlobalCannotSizeAnArr() {
        val p = snippet("mut COUNT: Size = 4\nbuf: Arr<UInt8, COUNT> = @arrOf(1, 2, 3, 4)")
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
    fun anArrLiteralMustHaveExactlyTheDeclaredCount() {
        // Sizes are spelled through Size constants: this branch's parser reads `Arr<UInt8, FOUR>`
        // but not `Arr<UInt8, 4>` (a ConstTypeArg comes with the frontend package's parser).
        val p = snippet(
            """
            pub ONE: Size = 1
            pub TWO: Size = 2
            pub THREE: Size = 3
            pub FOUR: Size = 4
            pub MAGIC: Arr<UInt8, FOUR> = @arrOf(1, 2, 3)
            pub OVER: Arr<UInt8, TWO> = @arrOf(1, 2, 3)
            pub EXACT: Arr<UInt8, THREE> = @arrOf(1, 2, 3)
            pub OPEN: Arr<UInt8> = @arrOf(1, 2, 3)
            pub NESTED: Arr<Arr<UInt8, TWO>, ONE> = @arrOf(@arrOf(1, 2, 3))
            pub Frame: Any {
                pub head: Arr<UInt8, TWO> = @arrOf(1)
            }
            """
        )
        val m = p.workspaceModules.single()
        assertEquals(4, count(p, "types.const.arr-size"), TyperTestSupport.render(p))
        assertNull((m.members["MAGIC"] as GlobalSymbol).constValue, "3 elements for Arr<UInt8, 4> is not a value")
        assertNull((m.members["OVER"] as GlobalSymbol).constValue, "3 elements for Arr<UInt8, 2> is not a value")
        assertNull((m.members["NESTED"] as GlobalSymbol).constValue, "the inner literal is checked too")
        assertEquals("[1:UInt8, 2:UInt8, 3:UInt8]", (m.members["EXACT"] as GlobalSymbol).constValue.toString())
        assertEquals("[1:UInt8, 2:UInt8, 3:UInt8]", (m.members["OPEN"] as GlobalSymbol).constValue.toString())
        val magic = expectDiagnostic(p, "types.const.arr-size")
        assertTrue(magic.message.contains("This @arrOf has 3 elements, but Arr<UInt8, 4> holds exactly 4"), magic.message)
        assertEquals(5, magic.position?.lineNumber, "located at the literal")
        assertTrue(
            p.diagnostics.any { it.code == "types.const.arr-size" && it.message.contains("has 1 element, but Arr<UInt8, 2>") },
            "the field default is checked too:\n" + TyperTestSupport.render(p),
        )

        // A parameter default (syntax this branch's parser does not produce) is checked the same way.
        val unit = CompilationUnit()
        val send = fn(
            "send",
            listOf(param("tail", ty("Arr", ty("UInt8"), ConstTypeArg(IntegerLiteral(1))), BuilderExpr(Builder.ARR, emptyList(), listOf(IntegerLiteral(1), IntegerLiteral(2))))),
            ty("Void"),
        )
        astModule(unit, "test:arrdef", send)
        val q = KiraTyper.run(unit, TyperMode.STRICT)
        assertEquals(1, count(q, "types.const.arr-size"), TyperTestSupport.render(q))
        assertTrue(expectDiagnostic(q, "types.const.arr-size").message.contains("has 2 elements, but Arr<UInt8, 1> holds exactly 1"))
    }

    @Test
    fun anOverrideOfAGenericMethodMapsItsTypeParameters() {
        val p = snippet(
            """
            pub Mapper: Any {
                pub map<U>: (u: U) U;
                pub bounded<U: Mapper>: (u: U) Void;
            }
            pub Base: Any {
                pub map<U>: (u: U) U {
                    u
                }
                pub pair<A, B>: (a: A, b: B) A {
                    a
                }
            }
            pub Child: Base {
                pub map<V>: (v: V) V {
                    v
                }
                pub pair<B, A>: (a: B, b: A) B {
                    a
                }
            }
            pub Impl: Mapper {
                pub map<T>: (t: T) T {
                    t
                }
                pub bounded<T: Mapper>: (t: T) Void { }
            }
            pub Wrong: Base {
                pub map<U>: (u: U) Int32 {
                    1
                }
                pub pair<A>: (a: A, b: A) A {
                    a
                }
            }
            pub Loose: Mapper {
                pub map<T>: (t: T) T {
                    t
                }
                pub bounded<T>: (t: T) Void { }
            }
            """
        )
        val errors = p.diagnostics.filter { it.code == "types.override.signature" }
        assertEquals(3, errors.size, TyperTestSupport.render(p))
        assertTrue(errors[0].message.startsWith("Wrong.map must have the signature of Base.map, (U) U; it is (U) Int32."), errors[0].message)
        assertTrue(errors[1].message.startsWith("Wrong.pair must have the type parameters of Base.pair, <A, B>; it has <A>."), errors[1].message)
        assertTrue(errors[2].message.startsWith("Loose.bounded must bound its type parameters as Mapper.bounded does, <T: Mapper>; it has <T>."), errors[2].message)
    }

    @Test
    fun aLiteralOutOfRangeDoesNotFoldAndIsLeftToPhaseC() {
        // Phases A and B alone: phase C's literal check reports it (TyperBodyNegativeTest).
        val p = TyperTestSupport.phasesAAndB { snippet("X: UInt8 = 256") }
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
