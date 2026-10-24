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
        val result = TestCompileSupport.compileSnippet("" + body.trimIndent(), "tests/slot.kira")
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
            pub @_opaque Tag: Any {
            }
            @_extern monotonic: () Float64
            pub @_extern(raises = "OSError ValueError") readText: (path: Str) Str
            pub @_const square: (x: Int32) Int32 { x * x }
            pub @_extern(cpp = "bibo::Scan", header = "car.hxx") Scan: Struct { }
            pub @_extern(c = "LIMIT", header = "limits.h") LIMIT: Int32
            pub @_magic @_global true: Bool = Bool { }
            pub final @_magic Fixed: Any { }
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
            pub @_magic Pair<A, B>: Any {
                pub @_magic first: A
                pub mut @_extern(cpp = "AddLine") addLine: (color: UInt32) Void;
                override pub @_const size: () Int32 { 2 }
            }
            pub Hall: Struct {
                pub mut @_const feed: (next: UInt8) Void { }
            }
            pub Sized: Any {
                pub @_magic size: () Int32;
            }
            """
        )
        val pair = assertIs<ClassDecl>(p.decls[0])
        val (first, addLine, size) = pair.members
        assertEquals(listOf("_magic"), p.marks(assertIs<VariableDecl>(first)))
        assertEquals(listOf(Modifier.PUBLIC), (first as VariableDecl).modifiers)
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
            pub @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h") DrawList: Any { }
            pub @_magic @_infer sqrt<T: FloatNum>: (value: T) T;
            pub @_extern("c_cos", c = "cos") cosine: (x: Float64) Float64;
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
        val e = refused("@_opaque\npub Tag: Any {\n}")
        assertEquals("parse.intrinsic.line", e.tag)
        assertEquals(SourcePosition(1, 1), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_opaque Tag: Any {'."), e.message)
        val two = refused("@_opaque @_extern(cpp = \"X\", header = \"x.hxx\")\npub X: Any { }")
        assertEquals("parse.intrinsic.line", two.tag)
        assertTrue(two.message.endsWith("Help: write it as 'pub @_opaque @_extern(cpp = \"X\", header = \"x.hxx\") X: Any { }'."), two.message)
        val bare = refused("@_extern\nf: () Int32")
        assertEquals("parse.intrinsic.line", bare.tag)
        assertTrue(bare.message.endsWith("Help: write it as '@_extern f: () Int32'."), bare.message)
        val member = refused("pub Car: Any {\n    @_extern(cpp = \"Drive\")\n    pub mut drive: () Void;\n}")
        assertEquals("parse.intrinsic.line", member.tag)
        assertEquals(SourcePosition(2, 5), member.location)
        assertTrue(member.message.endsWith("Help: write it as 'pub mut @_extern(cpp = \"Drive\") drive: () Void;'."), member.message)
    }

    @Test
    fun anIntrinsicBeforeAModifierIsRefused() {
        val e = refused("@_extern(raises = \"OSError\") pub readText: (path: Str) Str")
        assertEquals("parse.intrinsic.order", e.tag)
        assertEquals(SourcePosition(1, 1), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_extern(raises = \"OSError\") readText: (path: Str) Str'."), e.message)
        val between = refused("pub Hall: Struct {\n    pub @_const mut feed: () Void { }\n}")
        assertEquals("parse.intrinsic.order", between.tag)
        assertEquals(SourcePosition(2, 9), between.location)
        assertTrue(between.message.endsWith("Help: write it as 'pub mut @_const feed: () Void { }'."), between.message)
        val field = refused("pub Pair: Any {\n    @_magic pub first: Int32\n}")
        assertEquals("parse.intrinsic.order", field.tag)
        assertTrue(field.message.endsWith("Help: write it as 'pub @_magic first: Int32'."), field.message)
    }

    @Test
    fun aNewlineBeforeTheDeclarationIsRefused() {
        val e = refused("pub @_opaque\nTag: Any { }")
        assertEquals("parse.intrinsic.newline", e.tag)
        assertEquals(SourcePosition(1, 5), e.location)
        assertTrue(e.message.endsWith("Help: write it as 'pub @_opaque Tag: Any { }'."), e.message)
        val field = refused("pub @_extern(cpp = \"bibo::LIMIT\", header = \"car.hxx\")\nLIMIT: Int32")
        assertEquals("parse.intrinsic.newline", field.tag)
        assertTrue(field.message.endsWith("Help: write it as 'pub @_extern(cpp = \"bibo::LIMIT\", header = \"car.hxx\") LIMIT: Int32'."), field.message)
        val args = refused("pub @_extern(\n    raises = \"OSError\") readText: (path: Str) Str")
        assertEquals("parse.intrinsic.newline", args.tag)
        assertTrue(args.message.endsWith("Help: write it as 'pub @_extern(raises = \"OSError\") readText: (path: Str) Str'."), args.message)
    }

    @Test
    fun statementValueAndNameIntrinsicsAreUnchanged() {
        val p = parse(
            """
            @_static_assert(1 == 1, "ok")
            f: () Void {
                @_static_assert(2 == 2, "in a body")
                n: Int32 = @_infer
            }
            pub V2: Any {
                pub x: Float32 = 0.0
                pub @_op_add_: (other: V2) V2 { V2 { x + other.x } }
            }
            """
        )
        assertEquals("_static_assert", assertIs<IntrinsicExpr>(p.decls[0]).intrinsicKey.name)
        assertIs<FunctionDecl>(p.decls[1])
        assertIs<ClassDecl>(p.decls[2])
        val stray = refused("f: () Int32 {\n    @_extern(\"fopen\")\n    return 0\n}")
        assertEquals("KiraParser::parseStatement", stray.tag)
    }
}
