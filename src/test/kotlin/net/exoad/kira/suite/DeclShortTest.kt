package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.fix.DeclRepeatFix
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.source.SourceContext
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `x: T { .. }` is `x: T = T { .. }`, and rules.decl.repeat with its rewrite says the short form for the long one. */
class DeclShortTest {
    private val types = """
        P: Struct {
            pub x: Int32
            pub y: Int32
        }

        Q: Struct {
            pub x: Int32
            pub y: Int32
        }
    """.trimIndent()

    private fun full(body: String) = "$types\n\n${body.trimIndent()}\n"

    private fun parsed(body: String): SourceContext =
        TyperTestSupport.unitOf(module("test:main", full(body))).allSources().single { it.file.endsWith("test/main.kira") }

    /** The tree as the parser built it, node by node. */
    private fun tree(body: String): String = parsed(body).ast.statements.joinToString("\n")

    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(full(body), options = TyperOptions(hintDeclRepeat = true)).also { expectNoErrors(it) }

    private fun told(p: TypedProgram) = p.diagnostics.filter { it.code == "rules.decl.repeat" }

    private fun repeats(body: String) = parsed(body).astDeclRepeats.map { it.local }

    private fun same(short: String, long: String) {
        assertEquals(tree(long), tree(short))
        assertEquals(PyTestSupport.emit(full(long)).python(), PyTestSupport.emit(full(short)).python())
    }

    // ---- the parse rule: a brace on the type's line is a construction ----------------------------

    @Test
    fun aLocalIsItsLongForm() = same(
        "main: () Void {\n    p: P { 1, 2 }\n}",
        "main: () Void {\n    p: P = P { 1, 2 }\n}",
    )

    @Test
    fun namedArgumentsAndAnEmptyBlockToo() {
        same("main: () Void {\n    p: P { y = 2, x = 1 }\n}", "main: () Void {\n    p: P = P { y = 2, x = 1 }\n}")
        assertEquals(
            tree("main: () Void {\n    xs: List<Str> = List<Str> { }\n}"),
            tree("main: () Void {\n    xs: List<Str> { }\n}"),
        )
    }

    @Test
    fun aModuleLevelValueIsItsLongForm() = same("origin: P { 1, 2 }", "origin: P = P { 1, 2 }")

    @Test
    fun aPrimitiveIsBuiltTheSameWay() = assertEquals(
        tree("main: () Void {\n    n: Int32 = Int32 { 0 }\n}"),
        tree("main: () Void {\n    n: Int32 { 0 }\n}"),
    )

    @Test
    fun genericsTwoDeepAndTwoParameters() {
        assertEquals(
            tree("main: () Void {\n    xs: List<List<Str>> = List<List<Str>> { }\n}"),
            tree("main: () Void {\n    xs: List<List<Str>> { }\n}"),
        )
        assertEquals(
            tree("main: () Void {\n    m: Map<Str, List<Int32>> = Map<Str, List<Int32>> { }\n}"),
            tree("main: () Void {\n    m: Map<Str, List<Int32>> { }\n}"),
        )
    }

    @Test
    fun aMutableBlockConstructsItsInnerType() {
        assertEquals(
            tree("main: () Void {\n    m: Mutable<Int32> = Int32 { 0 }\n}"),
            tree("main: () Void {\n    m: Mutable<Int32> { 0 }\n}"),
        )
        same("main: () Void {\n    m: Mutable<P> { 1, 2 }\n}", "main: () Void {\n    m: Mutable<P> = P { 1, 2 }\n}")
        assertEquals(
            tree("main: () Void {\n    m: Mutable<List<Str>> = List<Str> { }\n}"),
            tree("main: () Void {\n    m: Mutable<List<Str>> { }\n}"),
        )
    }

    @Test
    fun aFieldDefaultMayUseIt() {
        val short = "Car: Mutable<Any> {\n    pub pose: P { 1, 2 }\n    pub count: Mutable<P> { 1, 2 }\n}"
        val long = "Car: Mutable<Any> {\n    pub pose: P = P { 1, 2 }\n    pub count: Mutable<P> = P { 1, 2 }\n}"
        assertEquals(tree(long), tree(short))
        expectNoErrors(TyperTestSupport.snippet(full(short)))
    }

    @Test
    fun theConstructionOwnsATypeNodeOfItsOwn() {
        // The typed model keys nodes by identity: the declared type and the constructed one must not be one node.
        var decl: VariableDecl? = null
        AstTree.walk(parsed("main: () Void {\n    p: P { 1, 2 }\n}").ast) { n -> if (n is VariableDecl && n.name.value == "p") decl = n }
        val init = decl!!.value as ObjectInitExpr
        assertTrue(decl!!.type !== init.typeName)
    }

    @Test
    fun aWithMayFollowTheBlock() = same(
        "main: () Void {\n    p: P { 1, 2 } with {\n        x = 5\n    }\n}",
        "main: () Void {\n    p: P = P { 1, 2 } with {\n        x = 5\n    }\n}",
    )

    @Test
    fun aCommentAfterTheBraceIsStillTheSameLine() = same(
        "main: () Void {\n    p: P { // the pair\n        1, 2\n    }\n}",
        "main: () Void {\n    p: P = P {\n        1, 2\n    }\n}",
    )

    @Test
    fun aBraceThatStartsALineIsNotAConstruction() {
        for (src in listOf("x: P\n{ 1, 2 }", "x: P // a comment between\n{ 1, 2 }", "x: P\n\n{ 1, 2 }")) {
            val e = assertThrows<DiagnosticsException> { parsed(src) }
            assertTrue(e.message!!.contains("declares nothing"), "$src: ${e.message}")
        }
    }

    @Test
    fun aBodyOfMembersIsStillRefusedAsADeclarationOfNothing() {
        val e = assertThrows<DiagnosticsException> { parsed("main: () Void {\n    c: P {\n        pub x: Int32 = 1\n    }\n}") }
        assertTrue(e.message!!.contains("declares nothing"), e.message)
    }

    @Test
    fun theHintIsOffUnlessAskedFor() {
        val p = TyperTestSupport.snippet(full("main: () Void {\n    a: P = P { 1, 2 }\n}"))
        expectNoErrors(p)
        assertEquals(emptyList(), told(p))
    }

    @Test
    fun aTypeNameStillDeclaresAType() {
        // `Name: Parent { }` is a class; the short form is for a value's name.
        expectNoErrors(TyperTestSupport.snippet(full("Pair: Struct {\n    pub a: Int32\n}\nmain: () Void {\n    q: Pair { 1 }\n}")))
    }

    @Test
    fun anOldFormStaysValid() {
        expectNoErrors(TyperTestSupport.snippet(full("main: () Void {\n    p: P = P { 1, 2 }\n    n: Int32 = 0\n}")))
    }

    // ---- the hint -----------------------------------------------------------------------------------

    @Test
    fun theHintFiresOnTheRepeatedTypeInEveryPlace() {
        val p = typed(
            """
            origin: P = P { 1, 2 }

            Car: Mutable<Any> {
                pub pose: P = P { 1, 2 }
            }

            main: () Void {
                a: P = P { 1, 2 }
                m: Mutable<P> = P { 1, 2 }
                xs: List<Str> = List<Str> { }
            }
            """,
        )
        val d = told(p)
        assertEquals(listOf("origin", "pose", "a", "m", "xs"), d.map { it.message.substringAfter('`').substringBefore('`') })
        assertTrue(d.all { it.severity == Severity.WARNING })
        assertTrue(d.first { it.message.startsWith("`m`") }.message.contains("`m: Mutable<P> { .. }`"), d.toString())
        assertTrue(d.first { it.message.startsWith("`a`") }.message.contains("`a: P { .. }`, the same construction without `= P`"), d.toString())
    }

    @Test
    fun theHintFiresOnAnOldFormWithAWith() {
        assertEquals(listOf("p"), repeats("main: () Void {\n    p: P = P { 1, 2 } with {\n        x = 5\n    }\n}"))
    }

    @Test
    fun theHintIsSilentOnTheShortForm() {
        val p = typed("origin: P { 1, 2 }\nmain: () Void {\n    a: P { 1, 2 }\n    m: Mutable<P> { 1, 2 }\n}")
        assertEquals(emptyList(), told(p))
    }

    @Test
    fun theHintIsSilentWhereMeaningWouldChange() {
        val src = """
            make: () P {
                P { 1, 2 }
            }

            main: () Void {
                a: P = Q { 1, 2 }
                m: Mutable<Int64> = Int32 { 0 }
                k: Int32 = 0
                f: Float64 = 1.5 * 2.0
                e: P = make()
                g: P = (P { 1, 2 })
                h: P =
                    P { 1, 2 }
                i: Int32 = P { 1, 2 }.x
                j: Mutable<P> = Q { 1, 2 }
            }
        """
        assertEquals(emptyList(), repeats(src))
    }

    @Test
    fun theHintNamesTheTypeAsWritten() {
        assertEquals(listOf("a"), repeats("main: () Void {\n    a: List<List<Str>> = List<List<Str>> { }\n}"))
        assertEquals(emptyList(), repeats("main: () Void {\n    a: List<List<Str>> = List<List<Int32>> { }\n}"))
    }

    // ---- the rewrite ----------------------------------------------------------------------------------

    private val old = full(
        """
        origin: P = P { 1, 2 }

        main: () Void {
            a: P = P { 1, 2 } // keep this
            m: Mutable<Int32> = Int32 { 0 }
            xs: List<List<Str>> =   List<List<Str>> { }
            b: P = Q { 1, 2 }
            n: Int32 = 0
            p: P = P { 1, 2 } with {
                x = 5
            }
        }
        """,
    )

    @Test
    fun theRewriteDropsTheRepeatAndNothingElse() {
        val (out, n) = DeclRepeatFix.rewrite(old)
        assertEquals(5, n)
        assertEquals(
            old.replace("origin: P = P {", "origin: P {").replace("a: P = P {", "a: P {").replace("m: Mutable<Int32> = Int32 {", "m: Mutable<Int32> {")
                .replace("xs: List<List<Str>> =   List<List<Str>> {", "xs: List<List<Str>> {").replace("p: P = P {", "p: P {"),
            out,
        )
    }

    @Test
    fun theRewriteKeepsTheTreeAndIsIdempotent() {
        val (out, _) = DeclRepeatFix.rewrite(old)
        fun ctx(text: String) = TyperTestSupport.unitOf(module("test:main", text.trimEnd())).allSources().single { it.file.endsWith("test/main.kira") }
        val before = ctx(old)
        val after = ctx(out)
        assertEquals(before.ast.statements.joinToString("\n"), after.ast.statements.joinToString("\n"))
        assertEquals(emptyList(), after.astDeclRepeats.map { it.local })
        assertEquals(out to 0, DeclRepeatFix.rewrite(out))
    }

    @Test
    fun theRewriteKeepsLineEndings() {
        val crlf = "main: () Void {\r\n    a: P = P { 1, 2 }\r\n}\r\n"
        val (out, n) = DeclRepeatFix.rewrite("$types\r\n$crlf")
        assertEquals(1, n)
        assertTrue(out.endsWith("main: () Void {\r\n    a: P { 1, 2 }\r\n}\r\n"), out)
    }
}
