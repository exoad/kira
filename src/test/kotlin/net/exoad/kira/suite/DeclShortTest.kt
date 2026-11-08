package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.fix.DeclEqualsFix
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

/** A declaration initializes with braces (`x: T { v }`, `x: T { a = 1 }`), and the old `x: T = v` is refused with the braces form. */
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

    private fun same(short: String, nested: String) {
        assertEquals(tree(nested), tree(short))
        assertEquals(PyTestSupport.emit(full(nested)).python(), PyTestSupport.emit(full(short)).python())
    }

    private fun refusal(src: String): String = assertThrows<DiagnosticsException> { parsed(src) }.message!!

    // ---- the parse rule: a brace on the type's line is the value or the construction ----------------------

    @Test
    fun severalValuesConstructLikeTheTypeNamedAgain() = same(
        "main: () Void {\n    p: P { 1, 2 }\n}",
        "main: () Void {\n    p: P { P { 1, 2 } }\n}",
    )

    @Test
    fun namedArgumentsAndAnEmptyBlockToo() {
        same("main: () Void {\n    p: P { y = 2, x = 1 }\n}", "main: () Void {\n    p: P { P { y = 2, x = 1 } }\n}")
        assertEquals(
            tree("main: () Void {\n    xs: List<Str> { List<Str> { } }\n}"),
            tree("main: () Void {\n    xs: List<Str> { }\n}"),
        )
    }

    @Test
    fun aModuleLevelValueIsAlike() = same("origin: P { 1, 2 }", "origin: P { P { 1, 2 } }")

    @Test
    fun aLoneValueOfAnyTypeIsTheValue() {
        val p = TyperTestSupport.snippet(
            full(
                """
                make: () P {
                    P { 1, 2 }
                }

                twice: (n: Int32) Int32 {
                    n * 2
                }

                main: () Void {
                    n: Int32 { 0 }
                    s: Str { "hi" }
                    xs: List<Int32> { @listOf(1, 2) }
                    m: Maybe<Int32> { null }
                    a: P { make() }
                    b: P { a }
                    f: Fx<T1<Int32>, Int32> { twice }
                    g: Fx<T0, Int32> { () Int32 { 7 } }
                    ok: Bool { n == 0 }
                    q: Mutable<P> { make() }
                }
                """,
            ),
        )
        expectNoErrors(p)
    }

    @Test
    fun aLoneValueOfAClassIsNotItsFirstField() {
        // `p: P { 1 }` is a P from the value 1: a type error, not a construction from one field (that is `P { x = 1, .. }`).
        val codes = TyperTestSupport.snippet(full("main: () Void {\n    p: P { 1 }\n}")).diagnostics.map { it.code }
        assertTrue(codes.any { it.startsWith("types.") }, codes.toString())
    }

    @Test
    fun aMutableBlockConstructsItsInnerType() {
        same("main: () Void {\n    m: Mutable<P> { 1, 2 }\n}", "main: () Void {\n    m: Mutable<P> { P { 1, 2 } }\n}")
        assertEquals(
            tree("main: () Void {\n    m: Mutable<List<Str>> { List<Str> { } }\n}"),
            tree("main: () Void {\n    m: Mutable<List<Str>> { }\n}"),
        )
    }

    @Test
    fun aFieldDefaultMayUseIt() {
        val short = "Car: Mutable<Any> {\n    pub pose: P { 1, 2 }\n    pub count: Mutable<Int32> { 0 }\n}"
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
        "main: () Void {\n    p: P { P { 1, 2 } with {\n        x = 5\n    } }\n}",
    )

    @Test
    fun aCommentAfterTheBraceIsStillTheSameLine() = same(
        "main: () Void {\n    p: P { // the pair\n        1, 2\n    }\n}",
        "main: () Void {\n    p: P { P {\n        1, 2\n    } }\n}",
    )

    @Test
    fun aBraceThatStartsALineIsNotAConstruction() {
        for (src in listOf("x: P\n{ 1, 2 }", "x: P // a comment between\n{ 1, 2 }", "x: P\n\n{ 1, 2 }")) {
            assertTrue(refusal(src).contains("declares nothing"), src)
        }
    }

    @Test
    fun aBodyOfMembersIsStillRefusedAsADeclarationOfNothing() {
        assertTrue(refusal("main: () Void {\n    c: P {\n        pub x: Int32 { 1 }\n    }\n}").contains("declares nothing"))
    }

    @Test
    fun aTypeNameStillDeclaresATypeButAValueInBracesIsAConstant() {
        // `Name: Parent { }` is a class; a PascalCase or one-capital name with a value on the brace's line is a constant's.
        expectNoErrors(TyperTestSupport.snippet(full("Pair: Struct {\n    pub a: Int32\n}\nN: Size { 4 }\nmain: () Void {\n    q: Pair { a = 1 }\n}")))
        expectNoErrors(TyperTestSupport.snippet(full("Pair: Struct { pub a: Int32 }\nmain: () Void {\n}")))
    }

    // ---- the old form -------------------------------------------------------------------------------

    @Test
    fun theOldFormIsRefusedWithTheBracesForm() {
        val cases = mapOf(
            "main: () Void {\n    n: Int32 = 0\n}" to "n: Int32 { 0 }",
            "main: () Void {\n    p: P = P { 1, 2 }\n}" to "p: P { 1, 2 }",
            "main: () Void {\n    m: Mutable<Int32> = Int32 { 0 }\n}" to "m: Mutable<Int32> { 0 }",
            "main: () Void {\n    xs: List<Str> = @listOf(\"a\")\n}" to "xs: List<Str> { @listOf(\"a\") }",
            "LIMIT: Int32 = 5\nmain: () Void {\n}" to "LIMIT: Int32 { 5 }",
            "main: () Void {\n    f: (n: Int32) Int32 = twice\n}" to "f: Fx<T1<Int32>, Int32> { twice }",
            "main: () Void {\n    p: P = Q { 1, 2 }\n}" to "p: P { Q { 1, 2 } }",
        )
        for ((src, fix) in cases) {
            val m = refusal(src)
            assertTrue(m.contains("initializes with braces, not '='") && m.contains("write '$fix'"), "$src: $m")
        }
    }

    @Test
    fun aFieldDefaultInATypeBodyIsRefusedToo() {
        val m = refusal("Car: Mutable<Any> {\n    pub speed: Mutable<Int32> = 0\n}")
        assertTrue(m.contains("speed: Mutable<Int32> { 0 }"), m)
    }

    @Test
    fun theEqualsSignStaysForNamesAndMutation() {
        expectNoErrors(
            TyperTestSupport.snippet(
                full(
                    """
                    scaled: (v: Int32, by: Int32 = 2) Int32 {
                        v * by
                    }

                    main: () Void {
                        p: P { x = 1, y = 2 } with {
                            x = 5
                        }
                        m: Mutable<Int32> { scaled(by = 3, v = 1) }
                        m = 4
                        m += 1
                    }
                    """,
                ),
            ),
        )
    }

    // ---- the rewrite ----------------------------------------------------------------------------------

    private val old = full(
        """
        twice: (n: Int32) Int32 {
            n * 2
        }

        origin: P = P { 1, 2 }
        LIMIT: Int32 = 4

        main: () Void {
            a: P = P { 1, 2 } // keep this
            m: Mutable<Int32> = Int32 { 0 }
            xs: List<List<Str>> =   List<List<Str>> { }
            b: P = Q { 1, 2 }
            n: Int32 = 0
            p: P = P { 1, 2 } with {
                x = 5
            }
            f: (n: Int32) Int32 = twice
            q: P = a
        }
        """,
    )

    @Test
    fun theRewriteGivesTheBracesFormAndNothingElse() {
        val (out, n) = DeclEqualsFix.rewrite(old)
        assertEquals(10, n)
        assertEquals(
            old.replace("origin: P = P {", "origin: P {").replace("LIMIT: Int32 = 4", "LIMIT: Int32 { 4 }")
                .replace("a: P = P { 1, 2 } // keep this", "a: P { 1, 2 } // keep this")
                .replace("m: Mutable<Int32> = Int32 { 0 }", "m: Mutable<Int32> { 0 }")
                .replace("xs: List<List<Str>> =   List<List<Str>> { }", "xs: List<List<Str>> { }")
                .replace("b: P = Q { 1, 2 }", "b: P { Q { 1, 2 } }")
                .replace("n: Int32 = 0", "n: Int32 { 0 }")
                .replace("p: P = P { 1, 2 } with {", "p: P { 1, 2 } with {")
                .replace("f: (n: Int32) Int32 = twice", "f: Fx<T1<Int32>, Int32> { twice }")
                .replace("q: P = a", "q: P { a }"),
            out,
        )
    }

    @Test
    fun theRewriteIsIdempotentAndItsTextParsesWhereTheOldIsRefused() {
        val (out, _) = DeclEqualsFix.rewrite(old)
        assertEquals(out to 0, DeclEqualsFix.rewrite(out))
        parsed(out.removePrefix(types))
        assertThrows<DiagnosticsException> { parsed(old.removePrefix(types)) }
    }

    @Test
    fun aConstructionFromOneValueKeepsItsOwnBraces() {
        val (out, n) = DeclEqualsFix.rewrite("main: () Void {\n    b: Box = Box { 5 }\n    i: Int32 = Int32 { 5 }\n}\n")
        assertEquals(2, n)
        assertEquals("main: () Void {\n    b: Box { Box { 5 } }\n    i: Int32 { 5 }\n}\n", out)
    }

    @Test
    fun aValueWithABraceOfItsOwnAndAComment() {
        val (out, _) = DeclEqualsFix.rewrite("main: () Void {\n    g: Fx<T0, Int32> = () Int32 { 7 } // seven\n    s: Str = \"a // b\" // c\n}\n")
        assertEquals("main: () Void {\n    g: Fx<T0, Int32> { () Int32 { 7 } } // seven\n    s: Str { \"a // b\" } // c\n}\n", out)
    }

    @Test
    fun aValueSpanningLinesIsWrappedWhole() {
        val (out, n) = DeclEqualsFix.rewrite("main: () Void {\n    g: Fx<T0, Int32> = () Int32 {\n        h()\n        7\n    }\n    y: Int32 = 3\n}\n")
        assertEquals(2, n)
        assertEquals("main: () Void {\n    g: Fx<T0, Int32> { () Int32 {\n        h()\n        7\n    } }\n    y: Int32 { 3 }\n}\n", out)
    }

    @Test
    fun theRewriteKeepsLineEndings() {
        val crlf = "main: () Void {\r\n    a: P = P { 1, 2 }\r\n    n: Int32 = 1\r\n}\r\n"
        val (out, n) = DeclEqualsFix.rewrite("$types\r\n$crlf")
        assertEquals(2, n)
        assertTrue(out.endsWith("main: () Void {\r\n    a: P { 1, 2 }\r\n    n: Int32 { 1 }\r\n}\r\n"), out)
    }

    @Test
    fun aFileWithAForLoopStillHasItsDeclarationsFixedAndTheLoopLeftAlone() {
        fun text(vararg lines: String) = lines.joinToString("\n", postfix = "\n")
        val src = text("main: () Void {", "    t: Mutable<Int32> = 0", "    for i: Int32 in 0..3 {", "        k: Int32 = i * 2", "        t += k", "    }", "    s: Str = \"x\"", "}")
        val (out, n) = DeclEqualsFix.rewrite(src)
        assertEquals(3, n)
        assertEquals(text("main: () Void {", "    t: Mutable<Int32> { 0 }", "    for i: Int32 in 0..3 {", "        k: Int32 { i * 2 }", "        t += k", "    }", "    s: Str { \"x\" }", "}"), out)
        assertEquals(out to 0, DeclEqualsFix.rewrite(out))
    }

    @Test
    fun aFileThatNestsTypesIsRewrittenOnce() {
        val src = "Shape: Mutable<Any> {\n    pub Corner: Any {\n        pub x: Int32 = 0\n    }\n    pub n: Int32 = 1\n}\n"
        val (out, n) = DeclEqualsFix.rewrite(src)
        assertEquals(2, n)
        assertEquals("Shape: Mutable<Any> {\n    pub Corner: Any {\n        pub x: Int32 { 0 }\n    }\n    pub n: Int32 { 1 }\n}\n", out)
    }
}
