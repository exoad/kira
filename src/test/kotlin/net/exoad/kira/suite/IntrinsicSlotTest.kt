package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A declaration intrinsic sits in the modifier slot: after the keyword modifiers, on the declaration's line. */
class IntrinsicSlotTest {

    private class Parsed(val context: SourceContext, val decls: List<ASTNode>)

    private fun parse(body: String): Parsed {
        val result = TestCompileSupport.compileSnippet("module \"test:slot\"\n" + body.trimIndent(), "tests/slot.kira")
        val decls = result.sourceContext.ast.statements.map { (it as Statement).expr }.filter { it !is ModuleDecl }
        return Parsed(result.sourceContext, decls)
    }

    private fun Parsed.marks(node: ASTNode): List<String> =
        context.intrinsicInvocationsOf(node).map { it.intrinsicKey.name }

    private fun refused(body: String): DiagnosticsException = assertThrows<DiagnosticsException> { parse(body) }

    @Test
    fun topLevelDeclarationsTakeTheSlot() {
        val p = parse(
            """
            pub @_opaque class Tag {
            }
            @_extern fx monotonic: () Float64
            pub @_extern(raises = "OSError ValueError") fx readText: (path: Str) Str
            pub @_const fx square: (x: Int32) Int32 { return x * x }
            pub @_extern(cpp = "bibo::Scan", header = "car.hxx") struct Scan { }
            pub @_extern(c = "LIMIT", header = "limits.h") LIMIT: Int32
            pub @_magic @_global true: Bool = Bool { }
            pub final @_magic class Fixed { }
            """
        )
        val d = p.decls
        assertEquals(listOf("_opaque"), p.marks(d[0]))
        assertEquals(listOf(Modifier.PUBLIC), assertIs<ClassDecl>(d[0]).modifiers)
        assertEquals(listOf("_extern"), p.marks(d[1]))
        assertTrue(assertIs<FunctionDecl>(d[1]).modifiers.isEmpty())
        val raises = p.context.intrinsicInvocationsOf(assertIs<FunctionDecl>(d[2])).single()
        assertEquals("OSError ValueError", assertIs<StringLiteral>(raises.namedParameters.getValue("raises")).value)
        assertEquals(listOf("_const"), p.marks(assertIs<FunctionDecl>(d[3])))
        assertEquals(listOf("_extern"), p.marks(assertIs<StructDecl>(d[4])))
        assertEquals(listOf("_extern"), p.marks(assertIs<VariableDecl>(d[5])))
        assertEquals(listOf("_magic", "_global"), p.marks(assertIs<VariableDecl>(d[6])))
        assertEquals(listOf(Modifier.PUBLIC, Modifier.FINAL), assertIs<ClassDecl>(d[7]).modifiers)
        assertEquals(listOf("_magic"), p.marks(d[7]))
    }

    @Test
    fun membersTakeTheSlotAfterEveryModifier() {
        val p = parse(
            """
            pub @_magic class Pair<A, B> {
                require pub @_magic first: A
                pub mut @_extern(cpp = "AddLine") fx addLine: (color: UInt32) Void;
                override pub @_const fx size: () Int32 { return 2 }
            }
            pub struct Hall {
                pub mut @_const fx feed: (next: UInt8) Void { }
            }
            pub trait Sized {
                pub @_magic fx size: () Int32;
            }
            """
        )
        val pair = assertIs<ClassDecl>(p.decls[0])
        val (first, addLine, size) = pair.members
        assertEquals(listOf("_magic"), p.marks(assertIs<VariableDecl>(first)))
        assertEquals(listOf(Modifier.REQUIRE, Modifier.PUBLIC), (first as VariableDecl).modifiers)
        assertEquals(listOf("_extern"), p.marks(assertIs<FunctionDecl>(addLine)))
        assertEquals(listOf(Modifier.PUBLIC, Modifier.MUTABLE), (addLine as FunctionDecl).modifiers)
        assertEquals(listOf("_const"), p.marks(assertIs<FunctionDecl>(size)))
        assertEquals(listOf(Modifier.OVERRIDE, Modifier.PUBLIC), (size as FunctionDecl).modifiers)
        assertEquals(listOf("_const"), p.marks(assertIs<StructDecl>(p.decls[1]).members.single()))
        assertEquals(listOf("_magic"), p.marks(assertIs<TraitDecl>(p.decls[2]).members.single()))
    }

    @Test
    fun severalIntrinsicsKeepTheirOrderAndArguments() {
        val p = parse(
            """
            pub @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h") class DrawList { }
            pub @_magic @_infer fx sqrt<T: FloatNum>: (value: T) T;
            pub @_extern("c_cos", c = "cos") fx cosine: (x: Float64) Float64;
            """
        )
        assertEquals(listOf("_opaque", "_extern"), p.marks(p.decls[0]))
        assertEquals(listOf("_magic", "_infer"), p.marks(p.decls[1]))
        val cos = p.context.intrinsicInvocationsOf(p.decls[2]).single()
        assertEquals("c_cos", assertIs<StringLiteral>(cos.parameters!!.single()).value)
        assertEquals("cos", assertIs<StringLiteral>(cos.namedParameters.getValue("c")).value)
    }

    @Test
    fun anIntrinsicOnALineOfItsOwnIsRefused() {
        val e = refused("@_opaque\npub class Tag {\n}")
        assertEquals("parse.intrinsic.line", e.tag)
        assertEquals(SourcePosition(2, 1), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_opaque class Tag {'."), e.message)
        val two = refused("@_opaque @_extern(cpp = \"X\", header = \"x.hxx\")\npub class X { }")
        assertEquals("parse.intrinsic.line", two.tag)
        assertTrue(two.message.endsWith("Help: write it as 'pub @_opaque @_extern(cpp = \"X\", header = \"x.hxx\") class X { }'."), two.message)
        val bare = refused("@_extern\nfx f: () Int32")
        assertEquals("parse.intrinsic.line", bare.tag)
        assertTrue(bare.message.endsWith("Help: write it as '@_extern fx f: () Int32'."), bare.message)
        val member = refused("pub class Car {\n    @_extern(cpp = \"Drive\")\n    pub mut fx drive: () Void;\n}")
        assertEquals("parse.intrinsic.line", member.tag)
        assertEquals(SourcePosition(3, 5), member.location)
        assertTrue(member.message.endsWith("Help: write it as 'pub mut @_extern(cpp = \"Drive\") fx drive: () Void;'."), member.message)
    }

    @Test
    fun anIntrinsicBeforeAModifierIsRefused() {
        val e = refused("@_extern(raises = \"OSError\") pub fx readText: (path: Str) Str")
        assertEquals("parse.intrinsic.order", e.tag)
        assertEquals(SourcePosition(2, 1), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_extern(raises = \"OSError\") fx readText: (path: Str) Str'."), e.message)
        val between = refused("pub struct Hall {\n    pub @_const mut fx feed: () Void { }\n}")
        assertEquals("parse.intrinsic.order", between.tag)
        assertEquals(SourcePosition(3, 9), between.location)
        assertTrue(between.message.endsWith("Help: write it as 'pub mut @_const fx feed: () Void { }'."), between.message)
        val field = refused("pub class Pair {\n    @_magic require pub first: Int32\n}")
        assertEquals("parse.intrinsic.order", field.tag)
        assertTrue(field.message.endsWith("Help: write it as 'require pub @_magic first: Int32'."), field.message)
    }

    @Test
    fun aNewlineBeforeTheDeclarationIsRefused() {
        val e = refused("pub @_opaque\nclass Tag { }")
        assertEquals("parse.intrinsic.newline", e.tag)
        assertEquals(SourcePosition(2, 5), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_opaque class Tag { }'."), e.message)
        val field = refused("pub @_extern(cpp = \"bibo::LIMIT\", header = \"car.hxx\")\nLIMIT: Int32")
        assertEquals("parse.intrinsic.newline", field.tag)
        assertTrue(field.message.endsWith("Help: write it as 'pub @_extern(cpp = \"bibo::LIMIT\", header = \"car.hxx\") LIMIT: Int32'."), field.message)
        val args = refused("pub @_extern(\n    raises = \"OSError\") fx readText: (path: Str) Str")
        assertEquals("parse.intrinsic.newline", args.tag)
        assertTrue(args.message.endsWith("Help: write it as 'pub @_extern(raises = \"OSError\") fx readText: (path: Str) Str'."), args.message)
    }

    @Test
    fun statementValueAndNameIntrinsicsAreUnchanged() {
        val p = parse(
            """
            @_static_assert(1 == 1, "ok")
            fx f: () Void {
                @_static_assert(2 == 2, "in a body")
                n: Int32 = @_infer
            }
            pub class V2 {
                pub x: Float32 = 0.0
                pub fx @_op_add_: (other: V2) V2 { return V2 { x + other.x } }
            }
            """
        )
        assertEquals("_static_assert", assertIs<IntrinsicExpr>(p.decls[0]).intrinsicKey.name)
        assertIs<FunctionDecl>(p.decls[1])
        assertIs<ClassDecl>(p.decls[2])
        val stray = refused("fx f: () Int32 {\n    @_extern(\"fopen\")\n    return 0\n}")
        assertEquals("KiraParser::parseStatement", stray.tag)
    }
}
