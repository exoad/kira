package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.dialect.AstWalk
import net.exoad.kira.dialect.Stage3
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `value == Identity` (stage 3 of the clean dialect): conformance tests, the narrowing they give, `is` removed. */
class ConformanceTest {
    private val types = """
        Shape: Any {
            pub area: () Int32;
        }

        Circle: Shape {
            pub radius: Int32

            override pub area: () Int32 {
                return radius
            }
        }

        Pt: Struct {
            pub x: Int32 = 0
        }
    """.trimIndent()

    private fun text(body: String) = "$types\n\n${body.trimIndent()}"

    private fun snippet(body: String) = TyperTestSupport.snippet(text(body))

    private fun parseError(body: String): DiagnosticsException =
        assertThrows { TyperTestSupport.unitOf(module("test:main", text(body))) }

    /** The initializer of the local [name] in [body], as parsed. */
    private fun initOf(body: String, name: String = "a"): Expr {
        val unit = TyperTestSupport.unitOf(module("test:main", text(body)))
        val ast = unit.allSources().first { it.file == "test/main.kira" }.ast
        return AstWalk.all(ast).filterIsInstance<VariableDecl>().first { it.name.value == name }.value!!
    }

    /** `a: Bool = <rhs side>` inside a function that has a Shape, a Maybe, a Pt and a function at hand. */
    private fun compare(test: String): Expr =
        initOf("ZERO: Int32 = 0\n\nf: (s: Shape, m: Maybe<Int32>, p: Pt, g: (x: Int32) Int32, k: Int32) Void {\n    a: Bool = $test\n}")

    // ---- spelling decides --------------------------------------------------------------------

    @Test
    fun anIdentityOnTheRightIsATestByItsSpelling() {
        for (rhs in listOf("Circle", "Maybe<Int32>", "shapes.Circle", "(Int32) Int32", "(x: Int32) Int32", "() Void", "Pt")) {
            val e = assertIs<TypeCheckExpr>(compare("s == $rhs"), rhs)
            assertFalse(e.negated, rhs)
        }
        assertTrue(assertIs<TypeCheckExpr>(compare("s != Circle")).negated)
    }

    @Test
    fun aValueOnTheRightStaysEquality() {
        // camelCase, UPPER_SNAKE and one capital are values; a member or a call of a type is a value too.
        for (rhs in listOf("k", "ZERO", "null", "Pt.x", "g(1)", "(k)", "(Pt { 1 })", "-k", "1")) {
            val e = assertIs<BinaryExpr>(compare("k == $rhs"), rhs)
            assertEquals(BinaryOp.EQUALS, e.operator, rhs)
        }
        assertEquals(BinaryOp.NOT_EQUAL, assertIs<BinaryExpr>(compare("m != null")).operator)
    }

    @Test
    fun aTestBindsLikeEqualityAndTheRestOfTheConditionFollows() {
        val and = assertIs<BinaryExpr>(compare("s == Circle && k > 1"))
        assertEquals(BinaryOp.AND, and.operator)
        assertIs<TypeCheckExpr>(and.leftExpr)
        val sum = assertIs<TypeCheckExpr>(compare("k + 1 == Int32"))
        assertIs<BinaryExpr>(sum.value)
        assertEquals("s == Circle && k > 1", KiraUnparser.text(and))
        assertEquals("!(s != Circle)", KiraUnparser.text(compare("!(s != Circle)")))
    }

    @Test
    fun aTestBlockIsStage3bAndAnIfBodyIsNoTestBlock() {
        val e = parseError("f: (p: Pt) Bool {\n    return p == Pt { x == 1 }\n}")
        assertEquals("parse.conform.block", e.tag)
        assertTrue(e.message.contains("not part of Kira yet") && e.message.contains("x == (T { ... })"), e.message)
        // A construction to compare with goes in parentheses.
        assertIs<BinaryExpr>(compare("p == (Pt { 1 })"))
        // In a condition the brace opens the body.
        expectNoErrors(snippet("f: (s: Shape) Int32 {\n    if s == Circle {\n        return s.radius\n    }\n    return 0\n}"))
    }

    // ---- `is` --------------------------------------------------------------------------------

    @Test
    fun isIsRemovedAndNamesTheReplacement() {
        val e = parseError("f: (s: Shape) Bool {\n    return s is Circle\n}")
        assertEquals("parse.removed", e.tag)
        assertTrue(e.message.contains("'x == T'") && e.message.contains("'x != T'"), e.message)
        // It is a plain name now.
        expectNoErrors(snippet("f: () Int32 {\n    is: Int32 = 2\n    return is + 1\n}"))
    }

    @Test
    fun theConverterRewritesIsToAConformanceTest() {
        val dir = Files.createTempDirectory("stage3-is").toFile()
        try {
            File(dir, "kira.yaml").writeText("project:\n  name: probe\nsrcDir: src\ndependencies:\n  kira_stdlib:\n    path: ${File("kira").absolutePath.replace('\\', '/')}\n")
            val file = File(dir, "src/app/main.kira").apply { parentFile.mkdirs() }
            val body = "f: (s: Shape) Bool {\n    // s is Circle, said a comment\n    t: Str = \"it is so\"\n    return s is Circle && !(s is Pt)\n}\n"
            file.writeText("$types\n\n$body")
            val untouched = File(dir, "src/app/other.kira").apply { writeText("g: () Int32 {\n    1\n}\n") }
            assertEquals(1, Stage3.convertAll(listOf(dir.path), check = true), "--check sees the change")
            assertEquals(0, Stage3.convertAll(listOf(dir.path), check = false))
            val text = file.readText()
            // And, stage 3's returns going with it, the last return is the body's value.
            assertTrue(text.contains("    s == Circle && !(s == Pt)\n") && !text.contains("return"), text)
            assertTrue(text.contains("// s is Circle, said a comment") && text.contains("\"it is so\""), text)
            assertEquals("g: () Int32 {\n    1\n}\n", untouched.readText())
            assertEquals(0, Stage3.convertAll(listOf(dir.path), check = true), "a converted file converts to itself")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- the typer ---------------------------------------------------------------------------

    @Test
    fun aTypeOnTheRightIsNeverAnOverloadedEquality() {
        val p = snippet(
            """
            @op_eq: (a: Circle, b: Circle) Bool {
                return a.radius == b.radius
            }

            f: (c: Circle, d: Circle, s: Shape) Bool {
                a: Bool = c == d
                b: Bool = s == Circle
                return a && b
            }
            """,
        )
        expectNoErrors(p)
        val ast = p.unit.allSources().first { it.file == "test/main.kira" }.ast
        val decls = AstWalk.all(ast).filterIsInstance<VariableDecl>().associateBy { it.name.value }
        assertTrue(p.model.opCalls[decls.getValue("a").value!!] != null, "c == d calls @op_eq")
        val test = assertIs<TypeCheckExpr>(decls.getValue("b").value)
        assertNull(p.model.opCalls[test])
        assertTrue(p.model.conformances[test]!!.subtype != null, "s == Circle is a run-time test")
    }

    @Test
    fun aPassingTestNarrowsAndAWritableNameDoesNot() {
        val p = snippet(
            """
            f: (s: Shape, m: Maybe<Int32>) Int32 {
                mut t: Shape = s
                if t == Circle {
                    return 0
                }
                if s == Circle && m == Int32 {
                    return s.radius + m
                }
                return -1
            }
            """,
        )
        expectNoErrors(p)
        val narrowed = p.model.narrowings.entries.map { (id, n) -> "${id.value}:${n.from.display()}->${n.to.display()}" }.sorted()
        assertEquals(listOf("m:Maybe<Int32>->Int32", "s:Shape->Circle"), narrowed)
        expectDiagnostic(snippet("f: (s: Shape) Int32 {\n    mut t: Shape = s\n    if t == Circle {\n        return t.radius\n    }\n    return 0\n}"), "types.member.unknown")
    }

    @Test
    fun aTestTheTypesDecideIsAWarningOrAnError() {
        val always = expectDiagnostic(snippet("f: (c: Circle) Bool {\n    return c == Shape\n}"), "types.conform.always")
        assertTrue(always.message.contains("always true"), always.message)
        val never = expectDiagnostic(snippet("f: (k: Int32) Bool {\n    return k != Str\n}"), "types.conform.never")
        assertTrue(never.message.contains("always true"), never.message)
        expectDiagnostic(snippet("f: (m: Maybe<Int32>) Bool {\n    return m == Null\n}"), "types.conform.unsupported")
    }

    // ---- lowering ----------------------------------------------------------------------------

    @Test
    fun onPyAMaybeTestIsANoneTestAndADecidedOneStillEvaluates() {
        // No trait here: the py target has none yet.
        val py = PyTestSupport.emit(
            """
            Pt: Struct {
                pub x: Int32 = 0
            }

            probe: () Maybe<Int32> {
                return 1
            }

            f: (m: Maybe<Int32>, p: Pt, n: Maybe<Int32>, k: Int32) Int32 {
                a: Bool = m == Int32
                b: Bool = m != Int32
                c: Bool = p == Pt
                d: Bool = probe() == Maybe<Int32>
                e: Bool = (if k > 0 { m } else { n }) != Int32
                if m == Int32 && a {
                    return m + 1
                }
                return 0
            }
            """,
        ).python()
        assertTrue(py.contains("    a = m is not None\n"), py)
        assertTrue(py.contains("    b = m is None\n"), py)
        assertTrue(py.contains("    c = True\n"), py)
        assertTrue(py.contains("    d = (_probe(), True)[1]\n"), py)
        // A value that binds looser than `is` is tested whole.
        assertTrue(py.contains("    e = (m if k > 0 else n) is None\n"), py)
        assertTrue(py.contains("    if m is not None and a:\n        return _k_i32(m + 1)\n"), py)
    }

    @Test
    fun onCppAClassTestIsADynamicCastAndANarrowedReadCastsToo() {
        val src = DeclTestSupport.module(
            "app:main",
            text("pub f: (s: Shape, m: Maybe<Int32>) Int32 {\n    if s == Circle && m == Int32 {\n        return s.radius + m\n    }\n    return 0\n}"),
        )
        val out = DeclTestSupport.emit(src).source("app:main") ?: ""
        assertTrue(out.contains("(dynamic_cast<const Circle*>(s.get()) != nullptr) && kira::isSome(m)"), out)
        assertTrue(out.contains("return std::dynamic_pointer_cast<Circle>(s)->radius + kira::unwrap(m);"), out)
    }

    @Test
    fun onCppAClassWithoutVirtualMethodsIsRefusedAndCAndJsRefuseEveryTest() {
        val src = DeclTestSupport.module(
            "app:main",
            "Animal: Any {\n    pub name: Str\n}\n\nDog: Animal {\n    pub tricks: Int32 = 2\n}\n\npub f: (a: Animal) Bool {\n    return a == Dog\n}",
        )
        val emitted = DeclTestSupport.emit(src)
        assertTrue(
            emitted.diagnostics("app:main").any { it.code == "cpp.unsupported" && it.message.contains("without virtual methods") },
            emitted.render(emitted.diagnostics("app:main")),
        )
        val source = "f: (k: Int32) Bool {\n    return k == Int32\n}\n"
        val refused = listOf(
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToC(source, "test/main.kira") },
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToJS(source, "test/main.kira") },
        )
        refused.forEach { assertTrue(it.message.orEmpty().contains("a conformance test"), it.message) }
    }
}
