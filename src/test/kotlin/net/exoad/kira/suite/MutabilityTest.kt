package net.exoad.kira.suite

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Stage 4, Mutability: `Mutable<T>` bindings and types, contagion, sharing, `with` in place, `== Mutable<..>`, `mut` gone. */
class MutabilityTest {
    /** [text] as module test:main, parsed: its context, or the parse error's first line and where. */
    private fun read(text: String): Pair<net.exoad.kira.source.SourceContext?, String?> = try {
        val unit = CompilationUnit(bootstrap = false)
        val processed = KiraPreprocessor(text).process().processedContent
        val bare = unit.addSource("probe.kira", processed, emptyList(), "test:main")
        val ctx = unit.addSource("probe.kira", processed, KiraLexer(bare).tokenize())
        KiraParser(ctx).parse()
        ctx to null
    } catch (e: DiagnosticsException) {
        null to ((e.message.lines().firstOrNull { it.isNotBlank() } ?: e.toString()) + " at ${e.location}")
    }

    private fun parse(text: String): net.exoad.kira.source.SourceContext {
        val (ctx, err) = read(text)
        return ctx ?: fail("does not parse: $err")
    }

    private fun refusal(text: String): String {
        val (ctx, err) = read(text)
        if (ctx != null) fail("parsed, but should be refused:\n$text")
        return err ?: ""
    }

    // The [i]th declaration of [ctx], past the module the reader names it as.
    private fun decl(ctx: net.exoad.kira.source.SourceContext, i: Int = 0) =
        ctx.ast.statements.map { (it as Statement).expr }.filter { it !is net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl }[i]

    /** [kira]'s stdout on py. */
    private fun runPy(kira: String): String {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val file = Files.createTempFile("mutability-run", ".py").toFile()
        try {
            file.writeText(PyTestSupport.emit(kira).python())
            val r = PyTestSupport.run(listOf(PyTestSupport.python!!, file.absolutePath), file.parentFile)
            assertEquals(0, r.exitCode, r.all)
            return r.stdout.replace("\r", "")
        } finally {
            file.delete()
        }
    }

    // ---- the parser ------------------------------------------------------------------------------

    @Test
    fun aMutableBindingIsTheTreeMutWas() {
        val new = decl(parse("x: Mutable<List<Int32>> = List<Int32> { }\n")) as VariableDecl
        assertEquals(listOf(Modifier.MUTABLE), new.modifiers)
        assertEquals("List", (new.type.identifier as Identifier).value)
        // A field the same way, with its modifiers.
        val field = (decl(parse("C: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n}\n")) as ClassDecl).members.single() as VariableDecl
        assertEquals(setOf(Modifier.PUBLIC, Modifier.MUTABLE), field.modifiers.toSet())
    }

    @Test
    fun aMutableTypeIsMarkedAndItsParentUnwrapped() {
        val ctx = parse("Base: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n}\n\nTop: Mutable<Base> {\n    pub m: Int32 = 1\n}\n")
        val base = decl(ctx, 0) as ClassDecl
        val top = decl(ctx, 1) as ClassDecl
        assertTrue(Modifier.MUTABLE in base.modifiers && base.parents.isEmpty(), "Mutable<Any> names no parent")
        assertTrue(Modifier.MUTABLE in top.modifiers)
        assertEquals("Base", (top.parents.single().identifier as Identifier).value)
    }

    @Test
    fun mutIsRefusedWithWhatReplacesIt() {
        assertContains(refusal("f: () Void {\n    mut x: Int32 = 0\n}\n"), "'name: Mutable<T>'")
        assertContains(refusal("C: Any {\n    pub n: Int32 = 0\n    pub mut bump: () Void {\n    }\n}\n"), "declare the type 'Name: Mutable<Parent>'")
        assertContains(refusal("f: (mut xs: List<Int32>) Void {\n}\n"), "a parameter is never Mutable")
        assertContains(refusal("f: (xs: List<Int32>) Void {\n}\n\ng: () Void {\n    f(mut ys)\n}\n"), "pass the value itself")
        assertContains(refusal("f: (each: Fx<Tuple1<mut Int32>, Void>) Void {\n}\n"), "a parameter is never Mutable")
    }

    @Test
    fun mutableIsRefusedOnAParameterAValueTypeAndTwice() {
        assertContains(refusal("f: (xs: Mutable<List<Int32>>) Void {\n}\n"), "A parameter is never Mutable")
        assertContains(refusal("f: (each: (n: Mutable<Int32>) Void) Void {\n}\n"), "A parameter is never Mutable")
        assertContains(refusal("S: Struct, Mutable<Any> {\n    pub n: Int32 = 0\n}\n"), "drop 'Struct'")
        assertContains(refusal("x: Mutable<Mutable<Int32>> = 0\n"), "says it twice")
        assertContains(refusal("Mutable: Any {\n    pub n: Int32 = 0\n}\n"), "the language's own")
    }

    // ---- the typer -------------------------------------------------------------------------------

    @Test
    fun mutableInAnyOtherTypePositionIsRefused() {
        expectDiagnostic(TyperTestSupport.snippet("f: () Mutable<Int32> {\n    1\n}\n"), "types.mutable.position")
        expectDiagnostic(TyperTestSupport.snippet("xs: List<Mutable<Int32>> = List<Mutable<Int32>> { }\n"), "types.mutable.position")
    }

    @Test
    fun aTypeWithMutableStateSaysSo() {
        expectDiagnostic(TyperTestSupport.snippet("C: Any {\n    pub n: Mutable<Int32> = 0\n}\n"), "types.mutable.field")
        expectDiagnostic(TyperTestSupport.snippet("C: Any {\n    pub xs: List<Int32> = List<Int32> { }\n}\n"), "types.mutable.contagion")
        // Through a Maybe too, and a value type is told to drop Struct.
        val s = TyperTestSupport.snippet("S: Struct {\n    pub m: Maybe<List<Int32>> = null\n}\n")
        assertContains(expectDiagnostic(s, "types.mutable.contagion").message, "Struct")
        expectNoErrors(TyperTestSupport.snippet("C: Mutable<Any> {\n    pub xs: List<Int32> = List<Int32> { }\n    pub n: Mutable<Int32> = 0\n}\n"))
    }

    @Test
    fun aSubtypeOfAMutableTypeRestatesIt() {
        val p = TyperTestSupport.snippet(
            "Base: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n}\n\nTop: Base {\n    pub m: Int32 = 1\n}\n\nmake: () Top {\n    Top { }\n}\n",
        )
        assertContains(expectDiagnostic(p, "types.mutable.restate").message, "'Top: Mutable<Base>'")
        expectNoErrors(TyperTestSupport.snippet("Base: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n}\n\nTop: Mutable<Base> {\n    pub m: Int32 = 1\n}\n\nmake: () Top {\n    Top { }\n}\n"))
    }

    @Test
    fun aSharedValueChangesThroughAnyBindingAndOnlyAMutableOneIsReassigned() {
        // A List is Mutable: added to through a plain local and a parameter.
        expectNoErrors(TyperTestSupport.snippet("fill: (xs: List<Int32>) Void {\n    xs.add(1)\n    xs[0] = 2\n}\n\nf: () Void {\n    xs: List<Int32> = List<Int32> { }\n    xs.add(1)\n    fill(xs)\n}\n"))
        expectDiagnostic(TyperTestSupport.snippet("f: () Void {\n    xs: List<Int32> = List<Int32> { }\n    xs = List<Int32> { }\n}\n"), "rules.mutability.local")
        expectDiagnostic(TyperTestSupport.snippet("f: (xs: List<Int32>) Void {\n    xs = List<Int32> { }\n}\n"), "rules.mutability.param")
        // A field changes only when it is Mutable; a value type's never, whatever binds it.
        expectDiagnostic(TyperTestSupport.snippet("C: Mutable<Any> {\n    pub n: Int32 = 0\n    pub m: Mutable<Int32> = 0\n}\n\nf: (c: C) Void {\n    c.m = 1\n    c.n = 2\n}\n"), "rules.mutability.field")
        expectDiagnostic(TyperTestSupport.snippet("P: Struct {\n    pub x: Int32 = 0\n}\n\nf: () Void {\n    p: Mutable<P> = P { }\n    p.x = 1\n}\n"), "rules.mutability.field")
        expectNoErrors(TyperTestSupport.snippet("P: Struct {\n    pub x: Int32 = 0\n}\n\nf: () Void {\n    p: Mutable<P> = P { }\n    p = p with {\n        x = 1\n    }\n}\n"))
    }

    @Test
    fun aLambdaChangesASharedValueItCapturedButAssignsNoCapture() {
        expectNoErrors(TyperTestSupport.snippet("f: () Void {\n    xs: List<Int32> = List<Int32> { }\n    g: () Void = () Void {\n        xs.add(1)\n    }\n    g()\n}\n"))
        expectDiagnostic(TyperTestSupport.snippet("f: () Void {\n    n: Mutable<Int32> = 0\n    g: () Void = () Void {\n        n = 1\n    }\n    g()\n}\n"), "types.lambda.assign-capture")
    }

    @Test
    fun aFunctionOfAMutableTypeChangesItWhenItsBodyDoes() {
        val p = TyperTestSupport.snippet(
            "Counter: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n\n    pub bump: () Void {\n        n += 1\n    }\n\n    pub read: () Int32 {\n        n\n    }\n\n    pub bumpTwice: () Void {\n        bump()\n        bump()\n    }\n}\n",
        )
        expectNoErrors(p)
        val fns = p.modules.flatMap { it.declarations }.filterIsInstance<net.exoad.kira.compiler.analysis.types.ClassSymbol>().single { it.name == "Counter" }.methods.associateBy { it.name }
        assertTrue(fns.getValue("bump").isMutMethod && fns.getValue("bumpTwice").isMutMethod, "a writer, and one calling it")
        assertFalse(fns.getValue("read").isMutMethod, "a reader stays one (C++ const)")
    }

    @Test
    fun everyFunctionOfAMutableTraitMayChangeIt() {
        val p = TyperTestSupport.snippet("Grows: Mutable<Any> {\n    pub grow: () Void;\n    pub size: () Int32 {\n        1\n    }\n}\n\nNamed: Any {\n    pub id: () Int32;\n}\n")
        expectNoErrors(p)
        val traits = p.modules.flatMap { it.declarations }.filterIsInstance<net.exoad.kira.compiler.analysis.types.TraitSymbol>().associateBy { it.name }
        assertTrue(traits.getValue("Grows").methods.all { it.isMutMethod }, "an implementer may change itself in any of them (C++ non-const)")
        assertFalse(traits.getValue("Named").methods.single().isMutMethod, "a value trait's never does")
    }

    @Test
    fun aTypeParameterBoundedByAMutableTypeIsShared() {
        // Only Mutable types satisfy the bound, so a function changing one through any binding changes the one value.
        expectNoErrors(
            TyperTestSupport.snippet(
                "Named: Mutable<Any> {\n    pub hits: Mutable<Int32> = 0\n\n    pub hit: () Void {\n        hits += 1\n    }\n}\n\n" +
                    "poke<T: Named>: (n: T) Void {\n    n.hit()\n    n.hits = 2\n}\n",
            ),
        )
    }

    @Test
    fun aListParameterTheFunctionChangesIsPassedByReference() {
        val p = TyperTestSupport.snippet("fill: (xs: List<Int32>, n: Int32) Void {\n    xs.add(n)\n}\n\nsize: (xs: List<Int32>) Size {\n    xs.size()\n}\n\nf: () Void {\n    xs: List<Int32> = List<Int32> { }\n    fill(xs, 1)\n    trace(size(xs))\n}\n")
        expectNoErrors(p)
        val fns = p.modules.flatMap { it.declarations }.filterIsInstance<FnSymbol>().associateBy { it.name }
        assertTrue(fns.getValue("fill").params[0].byRef, "a List the callee changes")
        assertFalse(fns.getValue("size").params[0].byRef, "a List the callee reads")
        val calls = p.model.calls.values.filter { it.fn?.name == "fill" }
        assertTrue(calls.single().args.first().let { it is net.exoad.kira.compiler.analysis.types.ArgBinding.Given && it.byRef }, "its call says so")
        val param: ParamSymbol = fns.getValue("fill").params[0]
        assertEquals("xs", param.name)
    }

    @Test
    fun conformanceToMutableIsDecidedOrTestedAtRunTime() {
        val always = TyperTestSupport.snippet("f: (xs: List<Int32>) Bool {\n    xs == Mutable<Any>\n}\n")
        expectDiagnostic(always, "types.conform.always")
        val never = TyperTestSupport.snippet("P: Struct {\n    pub x: Int32 = 0\n}\n\nf: (p: P) Bool {\n    p == Mutable<Any>\n}\n")
        expectDiagnostic(never, "types.conform.never")
    }

    // ---- the backends --------------------------------------------------------------------------

    @Test
    fun aListIsSharedOnPy() {
        val out = runPy(
            """
            grow: (xs: List<Int32>) Void {
                xs.add(9)
            }

            main: () Void {
                a: List<Int32> = @listOf(1)
                b: List<Int32> = a
                b.add(2)
                grow(a)
                trace(a.size())
                trace(b.size())
                c: List<Int32> = a.view().toList()
                c.add(3)
                trace(a.size())
            }
            """,
        )
        assertEquals("3\n3\n3\n", out)
    }

    @Test
    fun anArrStaysAValueOnPy() {
        val out = runPy(
            """
            main: () Void {
                a: Arr<Int32, 2> = @arrOf(1, 2)
                b: Mutable<Arr<Int32, 2>> = a
                b[0] = 9
                trace(a[0])
                trace(b[0])
            }
            """,
        )
        assertEquals("1\n9\n", out)
    }

    @Test
    fun withEditsAMutableValueInPlaceAndCopiesAValue() {
        val out = runPy(
            """
            Box: Mutable<Any> {
                pub n: Mutable<Int32> = 0
            }

            Pt: Struct {
                pub x: Int32 = 0
            }

            main: () Void {
                b: Box = Box { }
                c: Box = b with {
                    n = 5
                }
                trace(b.n)
                p: Pt = Pt { 1 }
                q: Pt = p with {
                    x = 2
                }
                trace(p.x)
                trace(q.x)
            }
            """,
        )
        assertEquals("5\n1\n2\n", out)
    }

    @Test
    fun aRunTimeTestOfMutableAsksTheObjectsType() {
        // A trait with a Mutable implementer is tested at run time, against that implementer (py lowers it isinstance).
        val p = TyperTestSupport.snippet(
            "Shape: Any {\n    pub area: () Int32;\n}\n\nFixed: Shape {\n    pub side: Int32 = 2\n\n    override pub area: () Int32 {\n        side * side\n    }\n}\n\n" +
                "Grow: Mutable<Shape> {\n    pub side: Mutable<Int32> = 1\n\n    override pub area: () Int32 {\n        side\n    }\n}\n\nchanges: (s: Shape) Bool {\n    s == Mutable<Shape>\n}\n",
        )
        expectNoErrors(p)
        val v = p.model.conformances.values.single()
        assertEquals(listOf("Grow"), v.mutables?.map { it.name })
        assertFalse(v.always)
    }

    @Test
    fun cppRefusesAChangeOnlySharingAllows() {
        val e = DeclTestSupport.emit(
            DeclTestSupport.module(
                "test:share",
                "pub f: () Int32 {\n    xs: List<Int32> = List<Int32> { }\n    xs.add(1)\n    xs.size() as Int32\n}\n",
            ),
        )
        assertTrue(e.diagnostics("test:share").any { it.code == "cpp.unsupported" && it.message.contains("not Mutable") }, e.render(e.diagnostics("test:share")))
        val ok = DeclTestSupport.emit(
            DeclTestSupport.module(
                "test:share",
                "pub f: () Int32 {\n    xs: Mutable<List<Int32>> = List<Int32> { }\n    xs.add(1)\n    xs.size() as Int32\n}\n",
            ),
        )
        assertFalse(ok.diagnostics("test:share").any { it.code == "cpp.unsupported" }, ok.render(ok.diagnostics("test:share")))
    }

    @Test
    fun aWithCopyOfAValueTypeKeepsItsOwnArrOnPy() {
        // `initially` re-run on the copy, and the block itself, change the copy's Arr, never the original's.
        val out = runPy(
            """
            P: Struct {
                pub n: Int32
                pub a: Arr<Int32> = @arrOf(0, 0)

                initially {
                    a.set(0, n)
                }
            }

            main: () Void {
                p: P = P { 1 }
                q: P = p with {
                    n = 2
                }
                r: P = p with {
                    a.set(1, 9)
                }
                trace(p.a.get(0))
                trace(q.a.get(0))
                trace(p.a.get(1))
                trace(r.a.get(1))
            }
            """,
        )
        assertEquals("1\n2\n0\n9\n", out)
    }

    /** The cpp.unsupported messages of [body], emitted as the module test:copies. */
    private fun cppRefusals(body: String): List<String> {
        val e = DeclTestSupport.emit(DeclTestSupport.module("test:copies", body))
        return e.diagnostics("test:copies").filter { it.code == "cpp.unsupported" }.map { it.message }
    }

    @Test
    fun cppRefusesACopyThatSharingMakesOneValue() {
        val refused = cppRefusals(
            """
            Bag: Mutable<Any> {
                pub items: Mutable<List<Int32>> = List<Int32> { }
            }

            pub f: () Int32 {
                a: Mutable<List<Int32>> = @listOf(1, 2)
                b: Mutable<List<Int32>> = a
                b.add(3)
                bag: Bag = Bag { }
                bag.items = a
                bag.items.add(4)
                rows: Mutable<List<List<Int32>>> = List<List<Int32>> { }
                rows.add(a)
                rows[0].add(5)
                g: () Size = () Size {
                    a.size()
                }
                a.add(6)
                total: Size = a.size() + b.size() + g()
                total as Int32
            }
            """,
        )
        assertTrue(refused.any { it.contains("copied from 'a' into 'b'") }, refused.toString())
        assertTrue(refused.any { it.contains("copied from 'a' into bag.items") }, refused.toString())
        assertTrue(refused.any { it.contains("copied from 'a' into rows") }, refused.toString())
        assertTrue(refused.any { it.contains("a lambda captures 'a'") }, refused.toString())
        // An explicit copy is a new value, and a local built up and stored, never used again, is moved: both lower.
        val ok = cppRefusals(
            """
            Bag: Mutable<Any> {
                pub items: Mutable<List<Int32>> = List<Int32> { }
            }

            pub f: () Int32 {
                a: Mutable<List<Int32>> = @listOf(1, 2)
                b: Mutable<List<Int32>> = a.view().toList()
                b.add(3)
                built: Mutable<List<Int32>> = List<Int32> { }
                built.add(4)
                bag: Bag = Bag { }
                bag.items = built
                bag.items.add(5)
                total: Size = a.size() + b.size() + bag.items.size()
                total as Int32
            }
            """,
        )
        assertEquals(emptyList(), ok)
    }

    @Test
    fun cppRefusesAListPassedByReferenceFromAPlainBindingOrATemporary() {
        val grow = "grow: (xs: List<Int32>) Void {\n    xs.add(9)\n}\n\n"
        val plain = cppRefusals(grow + "pub f: () Int32 {\n    a: List<Int32> = @listOf(1, 2)\n    grow(a)\n    a.size() as Int32\n}\n")
        assertTrue(plain.any { it.contains("'a' passed to 'grow', which changes it, from a binding that is not Mutable") }, plain.toString())
        val temp = cppRefusals(grow + "pub f: () Void {\n    grow(@listOf(1, 2))\n}\n")
        assertTrue(temp.any { it.contains("no place of its own, passed to 'grow'") }, temp.toString())
        assertEquals(emptyList(), cppRefusals(grow + "pub f: () Int32 {\n    a: Mutable<List<Int32>> = @listOf(1, 2)\n    grow(a)\n    a.size() as Int32\n}\n"))
    }

    @Test
    fun cppTakesAChangedListParameterByReferenceAndAReaderConst() {
        val e = DeclTestSupport.emit(
            DeclTestSupport.module(
                "test:refs",
                "Counter: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n\n    pub bump: () Void {\n        n += 1\n    }\n\n    pub read: () Int32 {\n        n\n    }\n}\n\n" +
                    "pub fill: (xs: List<Int32>) Void {\n    xs.add(1)\n}\n",
            ),
        )
        val text = e.header("test:refs") + (e.source("test:refs") ?: "")
        assertContains(text, "fill(kira::List<std::int32_t>& xs)")
        assertTrue(Regex("""read\(\) const""").containsMatchIn(text), text)
        assertFalse(Regex("""bump\(\) const""").containsMatchIn(text), text)
    }
}
