package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every syntax form design section 2 adds (W1.1, step 3), parsed into the
 * AST contract of section 2.4: one positive case per form, the negative
 * cases the brief names, the intrinsic-marker fix, rule D36 for condition
 * heads, and the loud failure of the C and JS backends on each new node.
 */
class ParserGrowthTest {

    // --- helpers ---------------------------------------------------------

    private class Parsed(val ast: RootASTNode, val context: SourceContext) {
        /** Top-level nodes arrive wrapped in [Statement] from the parser. */
        val exprs: List<ASTNode>
            get() = ast.statements.map { if (it is Statement) it.expr else it }
        val decls: List<ASTNode>
            get() = exprs.filter { it !is ModuleDecl }
    }

    private fun parse(body: String): Parsed {
        val source = "module \"test:growth\"\n" + body.trimIndent()
        val result = TestCompileSupport.compileSnippet(source, "tests/growth.kira", runSemantic = false)
        val ast = assertNotNull(result.sourceContext.ast, "expected an AST for: $source") as RootASTNode
        return Parsed(ast, result.sourceContext)
    }

    private inline fun <reified T> Parsed.single(): T = decls.filterIsInstance<T>().single()

    private fun body(fn: FunctionDecl): List<Statement> = assertNotNull(fn.def.body, "function body")

    private fun typeName(type: Type?): String? = (type?.identifier as? Identifier)?.value

    private fun emitC(body: String): String = TestCompileSupport.transpileSnippetToC(
        source = TestCompileSupport.wrapModule("test:growth.c", body),
        logicalPath = TestCompileSupport.logicalPathForModule("test:growth.c"),
        runSemantic = false,
    )

    private fun emitJS(body: String): String = TestCompileSupport.transpileSnippetToJS(
        source = TestCompileSupport.wrapModule("test:growth.js", body),
        logicalPath = TestCompileSupport.logicalPathForModule("test:growth.js"),
        runSemantic = false,
    )

    // --- struct -------------------------------------------------------------

    @Test
    fun structDeclarationParsesWithTraitsMembersAndInitially() {
        val s = parse(
            """
            pub struct Reply: Show, Eq {
                pub kind: Int32 = 0
                pub topic: Str = ""
                pub fx isEmpty: () Bool { return kind == 0 }
                initially { kind = 4 }
            }
            """
        ).single<StructDecl>()
        assertEquals("Reply", typeName(s.name))
        assertEquals(listOf(Modifier.PUBLIC), s.modifiers)
        assertEquals(listOf("Show", "Eq"), s.traits.map { typeName(it) })
        assertEquals(2, s.members.filterIsInstance<VariableDecl>().size)
        assertEquals(1, s.members.filterIsInstance<FunctionDecl>().size)
        val initially = assertNotNull(s.initially)
        assertIs<AssignmentExpr>(initially.single().expr)
        // A new node, never a ClassDecl: an old backend must reach the throwing default.
        val node: ASTNode = s
        assertFalse(node is ClassDecl)
    }

    @Test
    fun structWithoutBodyOrTraitsParses() {
        val bare = parse("struct Unit").single<StructDecl>()
        assertTrue(bare.members.isEmpty())
        assertTrue(bare.traits.isEmpty())
        assertNull(bare.initially)
        assertNull(parse("struct Empty { }").single<StructDecl>().initially)
    }

    @Test
    fun structRefusesFinallyAndDuplicateInitially() {
        assertThrows<Throwable> { parse("struct S { finally { } }") }
        assertThrows<Throwable> { parse("struct S { initially { } initially { } }") }
    }

    // --- for ... in ---------------------------------------------------------

    @Test
    fun forInParsesAsTheSpecFormBesideTheLegacyForm() {
        val fn = parse(
            """
            fx f: (names: Arr<Str>) Void {
                for i: Int32 in 0..8 { trace(i) }
                for mut j: 0..8 { trace(j) }
                for b: Str in names { trace(b) }
                for (k: Size in 0..n) { trace(k) }
            }
            """
        ).single<FunctionDecl>()
        val loops = body(fn).map { assertIs<ForIterationStatement>(it).forIterationExpr }
        assertEquals(4, loops.size)
        assertFalse(loops[0].isLegacy)
        assertEquals("Int32", typeName(loops[0].declaredType))
        assertEquals("i", loops[0].initializer.value)
        assertIs<RangeExpr>(loops[0].target)
        assertTrue(loops[1].isLegacy)
        assertNull(loops[1].declaredType)
        assertIs<RangeExpr>(loops[1].target)
        assertFalse(loops[2].isLegacy)
        assertEquals("Str", typeName(loops[2].declaredType))
        assertEquals("names", (loops[2].target as Identifier).value)
        assertFalse(loops[3].isLegacy)
        assertEquals("Size", typeName(loops[3].declaredType))
    }

    @Test
    fun forInWithoutTheInKeywordIsRejected() {
        assertThrows<Throwable> { parse("fx f: () Void { for i: Int32 0..8 { } }") }
    }

    // --- if-expressions -----------------------------------------------------

    @Test
    fun ifExpressionParsesInExpressionPosition() {
        val fn = parse(
            """
            fx f: (neg: Bool, x: Int32) Int32 {
                v: Int32 = if neg { -x } else { x }
                sign: Str = if neg { "-" } else { "" }
                return if v > LIMIT { LIMIT } else { v }
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        val v = assertIs<VariableDecl>(stmts[0].expr)
        val ifExpr = assertIs<IfExpr>(v.value)
        assertIs<Identifier>(ifExpr.condition)
        assertIs<UnaryExpr>(ifExpr.thenBranch.single().expr)
        assertIs<Identifier>(ifExpr.elseBranch.single().expr)
        assertIs<IfExpr>(assertIs<VariableDecl>(stmts[1].expr).value)
        val ret = assertIs<ReturnStatement>(stmts[2])
        val retIf = assertIs<IfExpr>(ret.expr)
        val cond = assertIs<BinaryExpr>(retIf.condition)
        // Rule D36 applies to the head of an if-expression as well.
        assertEquals("LIMIT", (cond.rightExpr as Identifier).value)
        assertEquals("LIMIT", (retIf.thenBranch.single().expr as Identifier).value)
    }

    @Test
    fun elseIfNestsAnIfExprAsTheSingleElseStatement() {
        val fn = parse(
            """
            fx f: (a: Bool, b: Bool) Str {
                return if a { "a" } else if b { "b" } else { "c" }
            }
            """
        ).single<FunctionDecl>()
        val outer = assertIs<IfExpr>(assertIs<ReturnStatement>(body(fn).single()).expr)
        assertEquals("a", (outer.thenBranch.single().expr as StringLiteral).value)
        val nested = assertIs<IfExpr>(outer.elseBranch.single().expr)
        assertEquals("b", (nested.thenBranch.single().expr as StringLiteral).value)
        assertEquals("c", (nested.elseBranch.single().expr as StringLiteral).value)
    }

    @Test
    fun ifExpressionWithoutElseIsRejected() {
        assertThrows<Throwable> {
            parse(
                """
                fx f: (c: Bool) Int32 {
                    v: Int32 = if c { 1 }
                    return v
                }
                """
            )
        }
    }

    @Test
    fun statementLevelIfStaysAStatement() {
        val fn = parse("fx f: (c: Bool) Void { if c { trace(1) } else { trace(2) } }").single<FunctionDecl>()
        assertIs<IfSelectionStatement>(body(fn).single())
    }

    // --- lambdas ------------------------------------------------------------

    @Test
    fun lambdaParsesInExpressionPosition() {
        val fn = parse(
            """
            fx f: (buf: View<UInt8>) Void {
                twice: Fx<Tuple1<Int32>, Int32> = fx (x: Int32) Int32 { return x * 2 }
                each(buf, fx (p: View<UInt8>) Void { n += 1 })
            }
            """
        ).single<FunctionDecl>()
        val decl = assertIs<VariableDecl>(body(fn)[0].expr)
        val lambda = assertIs<LambdaExpr>(decl.value)
        assertEquals(listOf("x"), lambda.def.parameters.map { it.name.value })
        assertEquals("Int32", typeName(lambda.def.returnTypeSpecifier))
        assertEquals(1, assertNotNull(lambda.def.body).size)
        val call = assertIs<FunctionCallExpr>(body(fn)[1].expr)
        val arg = assertIs<LambdaExpr>(call.positionalParameters[1].value)
        assertEquals("Void", typeName(arg.def.returnTypeSpecifier))
    }

    @Test
    fun namedFxStaysAFunctionDecl() {
        val p = parse("fx named: (x: Int32) Int32 { return x }")
        assertIs<FunctionDecl>(p.decls.single())
    }

    // --- default parameters ---------------------------------------------------

    @Test
    fun parameterDefaultsParse() {
        val fn = parse("""fx command: (verb: Str, args: Str = "", n: Int32 = 3 + 1) Str { return verb }""")
            .single<FunctionDecl>()
        val params = fn.def.parameters
        assertNull(params[0].defaultValue)
        assertEquals("", assertIs<StringLiteral>(params[1].defaultValue).value)
        assertIs<BinaryExpr>(params[2].defaultValue)
    }

    // --- named construction -----------------------------------------------------

    @Test
    fun namedConstructionParsesPositionalFirstThenNamed() {
        val fn = parse(
            """
            fx f: (line: Str) Void {
                out: Reply = Reply { line = trimEnd(line), kind = 2 }
                p: Point = Point { 1, y = 2 }
                e: Esc { pulse = 0 }
                q: Point = Point { 1, 2 }
                empty: Walked = Walked {}
            }
            """.replace("e: Esc { pulse = 0 }", "e: Esc = Esc { pulse = 0 }")
        ).single<FunctionDecl>()
        val inits = body(fn).map { assertIs<ObjectInitExpr>(assertIs<VariableDecl>(it.expr).value) }
        assertTrue(inits[0].positionalArgs.isEmpty())
        assertEquals(listOf("line", "kind"), inits[0].namedArgs.map { it.name.value })
        assertIs<FunctionCallExpr>(inits[0].namedArgs[0].value)
        assertEquals(1, inits[1].positionalArgs.size)
        assertEquals(listOf("y"), inits[1].namedArgs.map { it.name.value })
        // Measured before: `Esc { pulse = 0 }` parsed as a positional AssignmentExpr.
        assertTrue(inits[2].positionalArgs.isEmpty())
        assertEquals("pulse", inits[2].namedArgs.single().name.value)
        assertEquals(2, inits[3].positionalArgs.size)
        assertTrue(inits[3].namedArgs.isEmpty())
        assertTrue(inits[4].positionalArgs.isEmpty() && inits[4].namedArgs.isEmpty())
    }

    @Test
    fun namedArgumentBeforePositionalIsRejected() {
        assertThrows<Throwable> { parse("fx f: () Void { p: Point = Point { y = 2, 1 } }") }
        assertThrows<Throwable> { parse("fx f: () Void { g(y = 2, 1) }") }
    }

    // --- place assignment -------------------------------------------------------

    @Test
    fun placeAssignmentParsesForMembersIndexesAndCompounds() {
        val fn = parse(
            """
            fx f: () Void {
                out.kind = 1
                p[0] = 5
                w.packets += 1
                s.calib.aAxisM = 2.0
                out.points[kept] = Point { 1, 2 }
                w.bits >>= 1
                x = 1
                x += 1
            }
            """
        ).single<FunctionDecl>()
        val e = body(fn).map { it.expr }
        val a = assertIs<PlaceAssignmentExpr>(e[0])
        assertIs<MemberAccessExpr>(a.target)
        assertNull(a.operator)
        assertIs<IntegerLiteral>(a.value)
        assertIs<ArrayIndexExpr>(assertIs<PlaceAssignmentExpr>(e[1]).target)
        assertEquals(BinaryOp.ADD, assertIs<PlaceAssignmentExpr>(e[2]).operator)
        val deep = assertIs<MemberAccessExpr>(assertIs<PlaceAssignmentExpr>(e[3]).target)
        assertIs<MemberAccessExpr>(deep.origin)
        val indexed = assertIs<PlaceAssignmentExpr>(e[4])
        assertIs<ArrayIndexExpr>(indexed.target)
        assertIs<ObjectInitExpr>(indexed.value)
        assertEquals(BinaryOp.SHR, assertIs<PlaceAssignmentExpr>(e[5]).operator)
        // Identifier targets keep their own nodes.
        assertIs<AssignmentExpr>(e[6])
        assertIs<CompoundAssignmentExpr>(e[7])
    }

    @Test
    fun thisIsAPrimaryAndAFreshNodePerUse() {
        val cls = parse(
            """
            pub class Node {
                require pub label: Str
                pub fx get: () Str { return this.label }
                pub mut fx set: (v: Str) Void { this.label = v }
            }
            """
        ).single<ClassDecl>()
        val methods = cls.members.filterIsInstance<FunctionDecl>()
        val get = assertIs<MemberAccessExpr>(assertIs<ReturnStatement>(body(methods[0]).single()).expr)
        val first = assertIs<ThisExpr>(get.origin)
        val set = assertIs<PlaceAssignmentExpr>(body(methods[1]).single().expr)
        val second = assertIs<ThisExpr>(assertIs<MemberAccessExpr>(set.target).origin)
        assertNotSame(first, second)
    }

    // --- call-site mut ----------------------------------------------------------

    @Test
    fun callSiteMutMarksArguments() {
        val fn = parse(
            """
            fx f: (line: Str) Void {
                wordAt(line, 0, mut b, mut e)
                field(text, key, out = mut raw)
                field(text, key, mut out = raw)
                if !field(text, key, mut raw) { return }
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        val c1 = assertIs<FunctionCallExpr>(stmts[0].expr)
        assertEquals(listOf(false, false, true, true), c1.positionalParameters.map { it.isMut })
        val c2 = assertIs<FunctionCallExpr>(stmts[1].expr)
        assertEquals("out", c2.namedParameters.single().name.value)
        assertTrue(c2.namedParameters.single().isMut)
        assertTrue(assertIs<FunctionCallExpr>(stmts[2].expr).namedParameters.single().isMut)
        val cond = assertIs<UnaryExpr>(assertIs<IfSelectionStatement>(stmts[3]).expr)
        assertTrue(assertIs<FunctionCallExpr>(cond.operand).positionalParameters[2].isMut)
    }

    // --- initially, finally, override -----------------------------------------

    @Test
    fun classInitiallyFinallyAndOverrideParse() {
        val cls = parse(
            """
            class Stop: Behaviour {
                mut count: Int32 = 0
                initially { count = 1 }
                override pub fx id: () Str { return "stop" }
                finally { count = 0 }
            }
            """
        ).single<ClassDecl>()
        assertEquals(1, assertNotNull(cls.initially).size)
        assertEquals(1, assertNotNull(cls.finally).size)
        assertEquals(listOf("Behaviour"), cls.parents.map { typeName(it) })
        val id = cls.members.filterIsInstance<FunctionDecl>().single()
        assertTrue(Modifier.OVERRIDE in id.modifiers)
        assertTrue(Modifier.PUBLIC in id.modifiers)
        assertEquals(1, cls.members.filterIsInstance<VariableDecl>().size)
        // A class without the blocks reads exactly as before.
        val plain = parse("class Plain { x: Int32 = 0 }").single<ClassDecl>()
        assertNull(plain.initially)
        assertNull(plain.finally)
    }

    @Test
    fun duplicateBlocksAndMisplacedOverrideAreRejected() {
        assertThrows<Throwable> { parse("class C { initially { } initially { } }") }
        assertThrows<Throwable> { parse("class C { finally { } finally { } }") }
        assertThrows<Throwable> { parse("override x: Int32 = 1") }
    }

    // --- const type arguments, mut in tuples -------------------------------------

    @Test
    fun integerTypeArgumentsBecomeConstTypeArgs() {
        val p = parse(
            """
            pub MAGIC: Arr<UInt8, 4> = [1, 2, 3, 4]
            pub alias Frame as Arr<UInt8, USER_CMD_BYTES>
            """
        )
        val magic = p.single<VariableDecl>()
        val arg = assertIs<ConstTypeArg>(magic.type.children[1])
        assertEquals(4L, arg.value.value)
        assertEquals(ConstTypeArg.NAME, typeName(arg))
        assertEquals("UInt8", typeName(magic.type.children[0]))
        // A constant name stays a Type; the typer resolves it.
        val alias = p.single<TypeAliasDecl>()
        val named: Type = alias.target.children[1]
        assertFalse(named is ConstTypeArg)
        assertEquals("USER_CMD_BYTES", typeName(named))
    }

    @Test
    fun mutInsideTupleTypeArgumentsSetsIsMutParam() {
        val fn = parse("fx f: (each: Fx<Tuple2<mut Str, Int32>, Void>) Void { }").single<FunctionDecl>()
        val fx = fn.def.parameters.single().typeSpecifier
        val tuple = fx.children[0]
        assertEquals("Tuple2", typeName(tuple))
        assertTrue(tuple.children[0].isMutParam)
        assertFalse(tuple.children[1].isMutParam)
        assertFalse(fx.isMutParam)
        assertThrows<Throwable> { parse("fx f: (xs: Arr<mut Str>) Void { }") }
    }

    // --- intrinsic markers and calls ---------------------------------------------

    @Test
    fun externMarkerCarriesItsParameterAndLeavesNoStrayStatement() {
        val p = parse(
            """
            @_extern("sym") fx f: () Void;
            @_extern(cpp = "ns::g", header = "g.hxx") fx g: (x: Int32) Int32;
            pub @_const fx crc: () UInt32 { return 1 }
            """
        )
        assertEquals(3, p.decls.size, p.decls.toString())
        assertTrue(p.decls.all { it is FunctionDecl }, p.decls.toString())
        val f = p.decls[0] as FunctionDecl
        val mark = p.context.intrinsicInvocationsOf(f).single()
        assertEquals("_extern", mark.intrinsicKey.name)
        assertEquals("sym", assertIs<StringLiteral>(assertNotNull(mark.parameters).single()).value)
        assertEquals(listOf("_extern"), p.context.intrinsicsOf(f).map { it.name })
        val g = p.decls[1] as FunctionDecl
        val gMark = p.context.intrinsicInvocationsOf(g).single()
        assertTrue(assertNotNull(gMark.parameters).isEmpty())
        assertEquals(
            mapOf("cpp" to "ns::g", "header" to "g.hxx"),
            gMark.namedParameters.mapValues { (_, v) -> assertIs<StringLiteral>(v).value }
        )
        val crc = p.decls[2] as FunctionDecl
        assertEquals(listOf("_const"), p.context.intrinsicsOf(crc).map { it.name })
        assertTrue(Modifier.PUBLIC in crc.modifiers)
        assertTrue(p.context.intrinsicInvocationsOf(crc).single().parameters == null)
    }

    @Test
    fun markerArgumentsMustBeLiteralsAndAMarkerMustMarkADeclaration() {
        assertThrows<Throwable> { parse("@_extern(name) fx f: () Void;") }
        // Measured before: this compiled to a stray `"fopen";` statement.
        assertThrows<Throwable> {
            parse(
                """
                fx openFile: (path: Str) Int32 {
                    @_extern("fopen")
                    return 0
                }
                """
            )
        }
    }

    @Test
    fun intrinsicCallArgumentsAreFullExpressions() {
        val p = parse(
            """
            @_static_assert(A == B, "sizes agree")
            fx f: () Void { @_trace_(LIMIT == 10) }
            """
        )
        val assertion = assertIs<IntrinsicExpr>(p.decls[0])
        assertEquals("_static_assert", assertion.intrinsicKey.name)
        val params = assertNotNull(assertion.parameters)
        assertEquals(2, params.size)
        assertIs<BinaryExpr>(params[0])
        assertIs<StringLiteral>(params[1])
        // Measured before: `@_trace_(LIMIT == 10)` failed with "Expected ','".
        val trace = assertIs<IntrinsicExpr>(body(p.decls[1] as FunctionDecl).single().expr)
        assertIs<BinaryExpr>(assertNotNull(trace.parameters).single())
    }

    // --- condition heads (rule D36) ------------------------------------------------

    @Test
    fun conditionHeadsNeverStartAConstruction() {
        // Measured before: `if x < LIMIT {` failed and `if x < (LIMIT) {` parsed.
        val fn = parse(
            """
            fx f: (x: Int32) Void {
                if x < LIMIT { trace(1) } else if x > TOP { trace(2) }
                while x < MAX { x += 1 }
                do { x -= 1 } while x > MIN
                for b: Str in NAMES { trace(b) }
                if (x < LIMIT) { trace(3) }
                if x == LIMIT_M { trace(4) }
                if isOk(Foo { 1 }) { trace(5) }
                y: Int32 = if x < LIMIT { 1 } else { 2 }
                crc: Int32 = if (x & 1) != 0 { (x >> 1) ^ 7 } else { x >> 1 }
                dir: Int32 = if (x == 1) == FORWARD_ASCENDING { 1 } else { -1 }
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        fun rightName(e: ASTNode): String = ((e as BinaryExpr).rightExpr as Identifier).value
        val first = assertIs<IfSelectionStatement>(stmts[0])
        assertEquals("LIMIT", rightName(first.expr))
        assertEquals("TOP", rightName(assertIs<ElseIfBranchStatement>(first.elseBranches.single()).condition))
        assertEquals("MAX", rightName(assertIs<WhileIterationStatement>(stmts[1]).condition))
        assertEquals("MIN", rightName(assertIs<DoWhileIterationStatement>(stmts[2]).condition))
        assertEquals("NAMES", (assertIs<ForIterationStatement>(stmts[3]).forIterationExpr.target as Identifier).value)
        assertEquals("LIMIT", rightName(assertIs<IfSelectionStatement>(stmts[4]).expr))
        assertEquals("LIMIT_M", rightName(assertIs<IfSelectionStatement>(stmts[5]).expr))
        // Parentheses reset the rule: inside a call's parentheses `Foo { 1 }` is a construction.
        val call = assertIs<FunctionCallExpr>(assertIs<IfSelectionStatement>(stmts[6]).expr)
        assertIs<ObjectInitExpr>(call.positionalParameters.single().value)
        assertEquals("LIMIT", rightName(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[7].expr).value).condition))
        // A leading `(` is an operand, not the whole condition (probes unilidar and hall).
        val crc = assertIs<BinaryExpr>(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[8].expr).value).condition)
        assertEquals(BinaryOp.NOT_EQUAL, crc.operator)
        assertIs<BinaryExpr>(crc.leftExpr)
        val dir = assertIs<BinaryExpr>(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[9].expr).value).condition)
        assertEquals(BinaryOp.EQUALS, dir.operator)
        assertEquals("FORWARD_ASCENDING", (dir.rightExpr as Identifier).value)
    }

    @Test
    fun traitMethodsMayBeMut() {
        // design 5.9 chain: `pub mut fx onLoad: () Void { }` inside a trait.
        val p = parse(
            """
            pub trait Behaviour {
                pub fx id: () Str;
                pub mut fx onLoad: () Void { }
                pub mut fx step: (p: Pass) Reply;
            }
            """
        )
        assertEquals(1, p.decls.size)
    }

    @Test
    fun constructionInAnUnparenthesizedConditionHeadIsNotAConstruction() {
        // `Foo { }` in a bare head is the condition `Foo` followed by the body `{ }`,
        // so what comes next is an error rather than a method call on a new Foo.
        assertThrows<Throwable> { parse("fx f: () Void { if Foo { }.ok() { trace(1) } } ") }
        // A construction the body follows directly cannot be told from a
        // condition and a body either, so it is refused the same way.
        assertThrows<Throwable> { parse("fx f: () Void { while Foo { 1 } { trace(1) } }") }
    }

    // --- char literals and interpolated strings ---------------------------------

    @Test
    fun charLiteralsDecodeTheirEscapes() {
        val p = parse(
            listOf(
                "a: Char = 'a'",
                "sp: Char = ' '",
                "n: Char = '\\n'",
                "t: Char = '\\t'",
                "r: Char = '\\r'",
                "b: Char = '\\\\'",
                "q: Char = '\\''",
                "z: Char = '\\0'",
            ).joinToString("\n")
        )
        val values = p.decls.map { assertIs<CharLiteral>(assertIs<VariableDecl>(it).value).value }
        assertEquals(listOf(97, 32, 10, 9, 13, 92, 39, 0), values)
    }

    @Test
    fun charLiteralsOutsideOneByteOrWithUnknownEscapesAreRejected() {
        assertThrows<Throwable> { parse("c: Char = '€'") }
        assertThrows<Throwable> { parse("c: Char = '\\q'") }
    }

    @Test
    fun interpolatedStringsParseIntoTextAndHoles() {
        val p = parse(
            listOf(
                "fx fixed3: (sign: Str, whole: Int32, frac: Int32, f: Float32) Str {",
                "    a: Str = \"\${sign}\${whole}.\${(frac as Str).padStart(3, '0')}\"",
                "    b: Str = \"STEER \${fixed3(f)}\"",
                "    c: Str = \"plain\"",
                "    d: Str = \"cost \\\$5 and \\\${not a hole}\"",
                "    e: Str = \"\${if f > 0.0 { \"pos\" } else { \"neg\" }}!\"",
                "    return a",
                "}",
            ).joinToString("\n")
        )
        val values = body(p.single<FunctionDecl>()).take(5).map { assertIs<VariableDecl>(it.expr).value }
        val a = assertIs<InterpolatedStringLiteral>(values[0]).parts
        assertEquals(4, a.size, a.toString())
        assertEquals("sign", (assertIs<InterpolationPart.Hole>(a[0]).expr as Identifier).value)
        assertEquals("whole", (assertIs<InterpolationPart.Hole>(a[1]).expr as Identifier).value)
        assertEquals(".", assertIs<InterpolationPart.Text>(a[2]).text)
        assertIs<FunctionCallExpr>(assertIs<InterpolationPart.Hole>(a[3]).expr)
        val b = assertIs<InterpolatedStringLiteral>(values[1]).parts
        assertEquals("STEER ", assertIs<InterpolationPart.Text>(b[0]).text)
        assertIs<FunctionCallExpr>(assertIs<InterpolationPart.Hole>(b[1]).expr)
        assertEquals(2, b.size)
        // No `${`: a plain StringLiteral, and `\$` is a literal dollar.
        assertEquals("plain", assertIs<StringLiteral>(values[2]).value)
        assertEquals("cost \$5 and \${not a hole}", assertIs<StringLiteral>(values[3]).value)
        val e = assertIs<InterpolatedStringLiteral>(values[4]).parts
        assertIs<IfExpr>(assertIs<InterpolationPart.Hole>(e[0]).expr)
        assertEquals("!", assertIs<InterpolationPart.Text>(e[1]).text)
    }

    // --- legacy shapes are unchanged ----------------------------------------------

    @Test
    fun legacyShapesParseExactlyAsBefore() {
        val fn = parse(
            """
            fx f: () Void {
                for mut i: 0..3 { trace(i) }
                p: Point = Point { 1, 2 }
                x = 1
                x += 1
                @_trace_("plain")
                n = n + p.get()
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        assertTrue(assertIs<ForIterationStatement>(stmts[0]).forIterationExpr.isLegacy)
        val init = assertIs<ObjectInitExpr>(assertIs<VariableDecl>(stmts[1].expr).value)
        assertEquals(2, init.positionalArgs.size)
        assertTrue(init.namedArgs.isEmpty())
        assertIs<AssignmentExpr>(stmts[2].expr)
        assertIs<CompoundAssignmentExpr>(stmts[3].expr)
        assertIs<IntrinsicExpr>(stmts[4].expr)
        assertIs<BinaryExpr>(assertIs<AssignmentExpr>(stmts[5].expr).value)
    }

    // --- C and JS fail loudly on every new construct ---------------------------------

    /** (a fragment of the construct's diagnostic name) to (a program using it). */
    private val newConstructs = listOf(
        "struct declaration" to "struct S { x: Int32 = 1 }",
        "if-expression" to "fx f: (c: Bool) Int32 { return if c { 1 } else { 2 } }",
        "lambda expression" to "fx f: () Void { g: Fx<Tuple1<Int32>, Int32> = fx (x: Int32) Int32 { return x } }",
        "member or index place" to "class P { mut x: Int32 = 0 }\nfx f: (p: P) Void { p.x = 1 }",
        "'this' expression" to "class P { mut x: Int32 = 0\n pub fx get: () Int32 { return this.x } }",
        "char literal" to "fx f: () Void { c: Char = 'a' }",
        "interpolated string literal" to "fx f: (n: Int32) Str { return \"n=\${n}\" }",
        "'initially' or 'finally'" to "class P { mut x: Int32 = 0\n initially { x = 1 } }",
        "named construction" to "class P { mut x: Int32 = 0 }\nfx f: () Void { p: P = P { x = 1 } }",
        "call-site 'mut'" to "fx g: (mut x: Int32) Void { x = 1 }\nfx f: () Void { mut y: Int32 = 0\n g(mut y) }",
        "omits a default-valued parameter" to "fx g: (a: Int32, b: Int32 = 2) Int32 { return a + b }\nfx f: () Int32 { return g(1) }",
        "over a container" to "fx f: (xs: Arr<Int32>) Void { for x: Int32 in xs { trace(x) } }",
        "@_static_assert" to "fx f: () Void { @_static_assert(1 == 1, \"ok\") }",
    )

    @Test
    fun eachNewConstructFailsLoudlyOnTheCTarget() {
        for ((construct, program) in newConstructs) {
            val e = assertThrows<UnsupportedConstruct>("the C backend should refuse:\n$program") { emitC(program) }
            val message = assertNotNull(e.message)
            assertTrue(message.contains(construct), "'$message' should name $construct")
            assertTrue(message.contains("C backend"), "'$message' should name the C target")
        }
    }

    @Test
    fun eachNewConstructFailsLoudlyOnTheJSTarget() {
        for ((construct, program) in newConstructs) {
            val e = assertThrows<UnsupportedConstruct>("the JS backend should refuse:\n$program") { emitJS(program) }
            val message = assertNotNull(e.message)
            assertTrue(message.contains(construct), "'$message' should name $construct")
            assertTrue(message.contains("JS backend"), "'$message' should name the JS target")
        }
    }

    @Test
    fun theOldFormsStillCompileOnTheCTarget() {
        // A default parameter that every call supplies, an exclusive spec range
        // and the legacy inclusive range all lower; only the omitted default,
        // the container for-in and the new nodes are refused.
        val c = emitC(
            """
            fx g: (a: Int32, b: Int32 = 2) Int32 { return a + b }
            fx f: () Int32 {
                mut n: Int32 = 0
                for i: Int32 in 0..3 { n += i }
                for mut j: 0..3 { n += j }
                return g(n, 1)
            }
            """.trimIndent()
        )
        assertTrue(c.contains("i < 3"), c)
        assertTrue(c.contains("j <= 3"), c)
        assertTrue(c.contains("g(n, 1)"), c)
    }

    @Test
    fun unsupportedConstructIsADiagnosticNotAStackTraceAtTheCli() {
        val repoRoot = File(System.getProperty("user.dir"))
        val dir = File(repoRoot, "build/tmp/parser-growth/cli-unsupported").apply { deleteRecursively(); mkdirs() }
        File(dir, "kira.yaml").writeText(
            listOf(
                "project:",
                "  name: growth",
                "",
                "srcDir: src",
                "",
                "build:",
                "  target: c",
                "",
                "dependencies:",
                "  kira_stdlib:",
                "    path: ${File(repoRoot, "kira").absolutePath}",
                "",
            ).joinToString("\n")
        )
        File(dir, "src/app/main.kira").apply { parentFile.mkdirs() }.writeText(
            listOf(
                "module \"app:main\"",
                "",
                "fx main: () Int32 {",
                "    x: Int32 = 1",
                "    return if x > 0 { 1 } else { 2 }",
                "}",
                "",
            ).joinToString("\n")
        )
        val java = System.getProperty("java.home") + "/bin/java"
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "net.exoad.kira.cli.MainKt")
            .directory(dir)
            .start()
        val stderrHolder = StringBuilder()
        val reader = Thread { stderrHolder.append(process.errorStream.bufferedReader().readText()) }
        reader.start()
        val stdout = process.inputStream.bufferedReader().readText()
        reader.join()
        val code = process.waitFor()
        val all = stdout + stderrHolder
        assertEquals(1, code, all)
        assertTrue(all.contains("if-expression"), all)
        assertTrue(all.contains("C backend"), all)
        assertFalse(all.contains("Exception in thread"), all)
        assertFalse(all.contains("\tat net.exoad"), all)
    }
}
