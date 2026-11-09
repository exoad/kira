package net.exoad.kira.suite.parser

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.InIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
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
 * Full parser coverage for the Kotlin-native frontend (KiraLexer + KiraParser):
 * every declaration, statement, and expression form the grammar accepts, plus
 * malformed-program diagnostics. Unsupported surface (nullable types, the
 * `fx (...) R` function-type spelling) is pinned as rejected so the boundary
 * is explicit. `this`, lambdas in expression position and initially/finally
 * blocks were once pinned as rejected; design 2.4 (the C++ backend's AST
 * contract) makes them syntax, so they are pinned as accepted here and
 * covered in full by the tests below.
 */
class ParserSuiteTest {

    // --- helpers ---------------------------------------------------------

    private fun parse(
        source: String,
        logicalPath: String = "tests/parser.kira",
    ): RootASTNode {
        val result = TestCompileSupport.compileSnippet(
            source = source,
            logicalPath = logicalPath,
            runSemantic = false,
        )
        return assertNotNull(result.sourceContext.ast, "expected an AST for: $source") as RootASTNode
    }

    private fun module(body: String): String = """
        $body
    """.trimIndent()

    private fun parseModule(body: String): RootASTNode =
        parse(module(body))

    /** Top-level nodes arrive wrapped in [Statement] from the parser. */
    private fun exprsOf(ast: RootASTNode): List<net.exoad.kira.compiler.frontend.parser.ast.ASTNode> =
        ast.statements.map { if (it is Statement) it.expr else it }

    private fun declsOf(ast: RootASTNode): List<net.exoad.kira.compiler.frontend.parser.ast.ASTNode> =
        exprsOf(ast).filter { it !is ModuleDecl }

    // --- declarations -------------------------------------------------------

    @Test
    fun theFileIsTheModule() {
        // No header: the parser names the module after the file's path.
        val ast = parse("")
        val first = exprsOf(ast).single()
        assertIs<ModuleDecl>(first)
        assertEquals("tests:parser", first.uri.value)
        assertEquals("parser", first.getModuleName())
    }

    @Test
    fun parsesUseImport() {
        parseModule("""kira.core as core """)
    }

    @Test
    fun aModuleHeaderIsRefused() {
        val e = assertThrows<DiagnosticsException> { parse("module \"test:parser\"" + "\n" + "main: () Void { }") }
        assertEquals("parse.removed", e.tag)
        assertTrue(e.message.contains("the file is the module"), e.message)
    }

    @Test
    fun parsesTopLevelVariableDeclarations() {
        val ast = parseModule(
            """
            immutable: Int32 { 1 }
            mutable: Mutable<Str> { "a" }
            typed: Int32
            """
        )
        assertEquals(3, declsOf(ast).filterIsInstance<VariableDecl>().size)
    }

    @Test
    fun parsesFunctionDeclarationWithParametersAndReturn() {
        val ast = parseModule(
            """
            add: (a: Int32, b: Int32) Int32 {
                a + b
            }

            stub: (x: Str) Void
            """
        )
        val fns = declsOf(ast).filterIsInstance<FunctionDecl>()
        assertEquals(2, fns.size)
        assertEquals("add", (fns[0].name as Identifier).value)
        assertTrue(fns[1].isStub(), "a stub function has no body")
    }

    @Test
    fun parsesClassWithFieldsAndMethods() {
        val ast = parseModule(
            """
            pub Counter: Mutable<Any> {
                pub value: Int32
                pub label: Mutable<Str> { "" }

                pub increment: () Int32 {
                    value = value + 1
                    value
                }
            }
            """
        )
        assertEquals(1, declsOf(ast).filterIsInstance<ClassDecl>().size)
    }

    @Test
    fun parsesTraitWithSignatureAndImplementation() {
        val ast = parseModule(
            """
            pub Speaker: Any {
                pub speak: () Str
            }

            pub Loud: Speaker {
                pub volume: () Int32 {
                    8
                }
            }
            """
        )
        // Speaker holds no state and is a parent: a trait. Loud is a class.
        assertEquals(1, declsOf(ast).filterIsInstance<TraitDecl>().size)
        assertEquals(1, declsOf(ast).filterIsInstance<ClassDecl>().size)
    }

    @Test
    fun parsesTypeAlias() {
        val ast = parseModule(
            """
            pub Int32 as MyInt
            """
        )
        assertEquals(1, declsOf(ast).filterIsInstance<TypeAliasDecl>().size)
    }

    @Test
    fun parsesGenericsWithBounds() {
        val ast = parseModule(
            """
            pub Box<T>: Any {
                pub value: T
            }

            pub Pair<K, V: Any>: Any {
                pub first: K
                pub second: V
            }

            id<T: Any>: (value: T) T {
                value
            }
            """
        )
        assertEquals(3, declsOf(ast).size)
    }

    // --- generics and the closing-angle-bracket parity ---------------------------

    @Test
    fun nestedGenericsParseDeep() {
        // The C++ `>>` problem, solved lexically: conservative single-'>' lexing
        // means arbitrarily deep generic nesting parses (three/four adjacent
        // closers at once, no `> >` required).
        val ast = parseModule(
            """
            main: () Void {
                a: Arr<Arr<Arr<Int32>>> { @arrOf(@arrOf(@arrOf(1))) }
                b: Arr<Arr<Arr<Arr<Int32>>>> { @arrOf(@arrOf(@arrOf(@arrOf(1)))) }
            }
            """
        )
        assertTrue(exprsOf(ast).any { it is FunctionDecl }, "deep generic program should parse")
    }

    @Test
    fun genericsAndComparisonsOnTheSameLine() {
        parseModule(
            """
            main: () Void {
                a: Arr<Int32> { @arrOf(1) }
                b: Bool { a.size() > 0 }
                c: Bool { a.size() >= 1 }
                d: Bool { a.size() < 2 }
            }
            """
        )
    }

    @Test
    fun genericCallsWithExplicitTypeArguments() {
        parseModule(
            """
            id<T>: (v: T) T {
                v
            }

            main: () Void {
                x: Int32 { id<Int32>(1) }
                s: Str { id<Str>("a") }
            }
            """
        )
    }

    @Test
    fun aComparisonWithANegatedOperandIsNotATypeArgumentList() {
        val ast = parseModule(
            """
            f: (x: Int64) Bool {
                low: Bool { x < -LIMIT }
                high: Bool { x > (LIMIT) }
                x < -LIMIT || x > (LIMIT)
            }
            """
        )
        val fn = declsOf(ast).filterIsInstance<FunctionDecl>().single()
        val ret = assertIs<ReturnStatement>(fn.def.body!!.last())
        val or = assertIs<BinaryExpr>(ret.expr)
        assertEquals(BinaryOp.OR, or.operator)
        val less = assertIs<BinaryExpr>(or.leftExpr)
        assertEquals(BinaryOp.LESS_THAN, less.operator)
        assertIs<UnaryExpr>(less.rightExpr)
    }

    // --- statements ---------------------------------------------------------

    @Test
    fun parsesIfElseIfElse() {
        parseModule(
            """
            main: () Void {
                x: Int32 { 2 }
                if x == 1 {
                    trace("one")
                } else if x == 2 {
                    trace("two")
                } else {
                    trace("many")
                }
            }
            """
        )
    }

    @Test
    fun parsesWhile() {
        parseModule(
            """
            main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 3 {
                    i = i + 1
                }
            }
            """
        )
    }

    @Test
    fun parsesForRange() {
        parseModule(
            """
            main: () Void {
                while i: Int32 in 0..6 {
                    trace(i)
                }
            }
            """
        )
    }

    @Test
    fun parsesReturnBreakContinue() {
        parseModule(
            """
            pick: (x: Int32) Int32 {
                result: Mutable<Int32> { 0 }
                while i: Int32 in 0..11 {
                    if i == 2 {
                        continue
                    }
                    if i == 8 {
                        break
                    }
                    if i == 4 {
                        result = i
                        break
                    }
                }
                result
            }
            """
        )
    }

    @Test
    fun parsesThrowAndTryOn() {
        parseModule(
            """
            fail: () Void {
                throw Exception { "boom" }
            }

            main: () Void {
                try {
                    fail()
                } on e: Exception {
                    trace(e.message)
                }
            }
            """
        )
    }

    @Test
    fun parsesCompoundAssignments() {
        parseModule(
            """
            main: () Void {
                a: Mutable<Int32> { 1 }
                a += 2
                a -= 1
                a *= 3
                a /= 2
                a %= 2
                a <<= 1
                a |= 1
                a &= 3
                a ^= 1
            }
            """
        )
    }

    // --- expressions ---------------------------------------------------------

    @Test
    fun parsesOperatorPrecedence() {
        parseModule(
            """
            main: () Void {
                a: Int32 { 1 + 2 * 3 - 4 / 2 }
                b: Bool { a > 2 && a <= 9 || a != 5 }
                c: Int32 { 1 .. 3 .size() }
                d: Int32 { -a }
                e: Bool { !b }
                f: Int32 { ~a }
            }
            """
        )
    }

    @Test
    fun parsesPostfixMemberIndexCallCastAndTypeTest() {
        parseModule(
            """
            main: () Void {
                values: Arr<Int32> { @arrOf(1, 2, 3) }
                first: Int32 { values[0] }
                length: Int32 { values.size() }
                typed: Int32 { first as Int32 }
                isArr: Bool { values == Arr<Int32> }
                obj: Any { values }
                cast: Arr<Int32> { obj as Arr<Int32> }
            }
            """
        )
    }

    @Test
    fun asBindsTighterThanTheBinaryOperatorsAndLooserThanAPrefixOne() {
        val ast = parseModule(
            """
            f: (x: Int32, n: Int64) Int32 {
                a: Float64 { x as Float64 / 1000.0 }
                b: Int64 { n + x as Int64 }
                c: Bool { x as Int64 > n }
                d: Int64 { -x as Int64 }
                x as Int32 + 1
            }
            """
        )
        val body = declsOf(ast).filterIsInstance<FunctionDecl>().single().def.body!!
        assertEquals(5, body.size, "nothing after a cast is left to parse as a statement of its own")
        val values = body.map { it.expr }.filterIsInstance<VariableDecl>().associate { it.name.value to it.value }

        val a = assertIs<BinaryExpr>(values["a"])
        assertEquals(BinaryOp.DIV, a.operator)
        assertIs<TypeCastExpr>(a.leftExpr)

        val b = assertIs<BinaryExpr>(values["b"])
        assertEquals(BinaryOp.ADD, b.operator)
        assertIs<TypeCastExpr>(b.rightExpr)

        val c = assertIs<BinaryExpr>(values["c"])
        assertEquals(BinaryOp.GREATER_THAN, c.operator)
        assertIs<TypeCastExpr>(c.leftExpr)

        assertIs<UnaryExpr>(assertIs<TypeCastExpr>(values["d"]).value)

        val ret = assertIs<BinaryExpr>(assertIs<ReturnStatement>(body.last()).expr)
        assertEquals(BinaryOp.ADD, ret.operator)
        assertIs<TypeCastExpr>(ret.leftExpr)
    }

    @Test
    fun aLessThanAfterACastIsADiagnosticAtTheCast() {
        val e = assertThrows<DiagnosticsException> {
            parse(
                "" +
                    "f: (x: Int32, n: Int64) Bool {\n" +
                    "    c: Bool { x as Int64 < n }\n" +
                    "    return c\n" +
                    "}\n"
            )
        }
        assertTrue(e.message.contains("put the cast in parentheses: (... as Int64) < ..."), e.message)
        assertEquals(2, e.location?.lineNumber)
        assertEquals(17, e.location?.column)
        parseModule(
            """
            g: (x: Int32, n: Int64, xs: Arr<Int32>) Bool {
                c: Bool { (x as Int64) < n }
                d: List<Int32> { xs as List<Int32> }
                c
            }
            """
        )
    }

    private fun valueOf(expr: String): net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr =
        assertNotNull(declsOf(parseModule("x: Int32 { $expr }")).filterIsInstance<VariableDecl>().single().value)

    @Test
    fun theUnparserParenthesizesACastAsTheParserReadsIt() {
        val cases = linkedMapOf(
            "(x as Int64) < n" to "(x as Int64) < n",
            "(a + x as Int64) < n" to "(a + x as Int64) < n",
            "x as Int64 > n" to "x as Int64 > n",
            "(a + b) as Int64" to "(a + b) as Int64",
            "a * b as Int64" to "a * b as Int64",
            "-(x as Int64)" to "-(x as Int64)",
            "-x as Int64" to "-x as Int64",
            "v >> (n as UInt8)" to "v >> n as UInt8",
            "(v >> n) as UInt8" to "(v >> n) as UInt8",
        )
        for ((source, text) in cases) {
            assertEquals(text, KiraUnparser.text(valueOf(source)), source)
            assertEquals(text, KiraUnparser.text(valueOf(text)), "$text read back")
        }
    }

    @Test
    fun aRangeEndTakesTheCast() {
        val ast = parseModule(
            """
            f: (n: Int32) Int64 {
                s: Mutable<Int64> { 0 }
                while i: Int64 in 0..n as Int64 {
                    s += i
                }
                while j: Int64 in 0 as Int64..n as Int64 {
                    s += j
                }
                s
            }
            """
        )
        val loops = declsOf(ast).filterIsInstance<FunctionDecl>().single().def.body!!.filterIsInstance<InIterationStatement>()
        assertEquals(2, loops.size)
        val first = assertIs<RangeExpr>(loops[0].inIterationExpr.target)
        assertIs<TypeCastExpr>(first.end)
        val second = assertIs<RangeExpr>(loops[1].inIterationExpr.target)
        assertIs<TypeCastExpr>(second.begin)
        assertIs<TypeCastExpr>(second.end)
    }

    @Test
    fun parsesObjectCreationPositionalAndNamed() {
        parseModule(
            """
            pub Point: Any {
                pub x: Int32
                pub y: Int32
            }

            main: () Void {
                p1: Point { 1, 2 }
                p2: Point { x = 3, y = 4 }
            }
            """
        )
    }

    @Test
    fun parsesArrayLiterals() {
        parseModule(
            """
            main: () Void {
                empty: Arr<Int32> { @arrOf() }
                nums: Arr<Int32> { @arrOf(1, 2, 3) }
                nested: Arr<Arr<Int32>> { @arrOf(@arrOf(1), @arrOf(2, 3)) }
            }
            """
        )
    }

    @Test
    fun parsesIntrinsicCalls() {
        parseModule(
            """
            main: () Void {
                @op_add(1, 2)
                @_trace_("plain")
            }
            """
        )
    }

    // --- unsupported surface (pinned boundaries) ------------------------------

    @Test
    fun nullableTypesAreRejected() {
        // No `T?`: the `?` is where the body stops reading a member.
        val src = "pub Node: Any { pub next: Node? }"
        val e = assertThrows<DiagnosticsException> { parseModule(src) }
        assertEquals("KiraParser::parseTypeBody", e.tag, e.message)
        assertEquals(src.indexOf('?') + 1, e.location?.column, e.message)
    }

    @Test
    fun thisKeywordParsesAsAPrimary() {
        // Was pinned as rejected; design 2.4 adds ThisExpr (see the tests below).
        parseModule(
            """
            pub Node: Any {
                pub label: Str
                pub get: () Str {
                    this.label
                }
            }
            """
        )
    }

    @Test
    fun aFunctionTypeIsFxOfItsParameterTuple() {
        // `(x: Int32) Int32` is the spelling of Fx<T1<Int32>, Int32>, `() Void` of Fx<T0, Void>.
        val ast = parseModule(
            """
            f: Fx<T1<Int32>, Int32> { (x: Int32) Int32 { x } }
            g: Fx<T1<Int32>, Int32> { (x: Int32) Int32 { x } }
            h: Fx<T0, Void> { () Void { } }
            k: Fx<T0, Void> { () Void { } }
            """
        )
        fun text(t: net.exoad.kira.compiler.frontend.parser.ast.elements.Type): String {
            val name = (t.identifier as Identifier).value
            return if (t.children.isEmpty()) name else name + "<" + t.children.joinToString(", ") { text(it) } + ">"
        }
        val types = declsOf(ast).filterIsInstance<VariableDecl>().map { text(it.type) }
        assertEquals(listOf("Fx<T1<Int32>, Int32>", "Fx<T1<Int32>, Int32>", "Fx<T0, Void>", "Fx<T0, Void>"), types)
    }

    @Test
    fun namingConventionsReserveUnderscoresForConstants() {
        // Accepted: UPPER_SNAKE_CASE globals and fields.
        val ast = parseModule(
            """
            MAX_SIZE: Int32 { 10 }

            Limits: Any {
                pub MAX_X: Int32
            }
            """
        )
        assertEquals(2, declsOf(ast).size, ast.toString())
        // Rejected: types, functions, parameters and type parameters.
        for ((bad, tag, words) in listOf(
            Triple("A_T: Any {\n    pub x: Int32\n}", "parse.type.name", "'A_T' is a constant's name"),
            Triple("Int32 as A_B", "KiraParser::expectConventionalName", "Type alias 'A_B' does not conform to PascalCase"),
            Triple("MY_FN: () Void {}", "KiraParser::expectConventionalName", "Function 'MY_FN' does not conform to camelCase"),
            Triple("f: (X_Y: Int32) Void {}", "KiraParser::expectConventionalName", "Parameter 'X_Y' does not conform to camelCase"),
            Triple("id<T_X>: (v: T_X) T_X { return v }", "KiraParser::expectConventionalName", "Type parameter 'T_X' does not conform to PascalCase"),
        )) {
            val e = assertThrows<DiagnosticsException>(bad) { parseModule(bad) }
            assertEquals(tag, e.tag, e.message)
            assertTrue(e.message.contains(words), e.message)
        }
    }

    @Test
    fun aBareReturnIsRefused() {
        val src = """
            stop: (n: Int32) Void {
                if n < 0 {
                    return
                }
                trace(n)
                return
            }
            """
        val refused = assertThrows<DiagnosticsException> { parseModule(src) }
        assertEquals("parse.removed", refused.tag)
    }

    @Test
    fun initiallyBlockParses() {
        // Design 2.4 adds ClassDecl.initially (see the tests below).
        val ast = parseModule(
            """
            pub C: Mutable<Any> {
                x: Mutable<Int32> { 0 }
                initially { x = 1 }
            }
            """
        )
        val cls = declsOf(ast).filterIsInstance<ClassDecl>().single()
        assertNotNull(cls.initially)
    }

    // --- malformed programs --------------------------------------------------

    @Test
    fun garbageFailsToParse() {
        val e = assertThrows<DiagnosticsException> { parseModule("fx fx fx { { {") }
        assertEquals("parse.removed", e.tag, e.message)
    }

    @Test
    fun unterminatedBlockFails() {
        val e = assertThrows<DiagnosticsException> { parseModule("main: () Void {") }
        assertTrue(e.tag == "KiraParser::expect" && e.message.contains("Expected a '}' (closing brace) but got an 'EOF'"), e.message)
    }

    @Test
    fun badStatementFails() {
        val e = assertThrows<DiagnosticsException> { parseModule("main: () Void { 1 + }") }
        assertTrue(e.tag == "KiraParser::parsePrimaryExpr" && e.message.contains("a '}' (Closing Brace) is not allowed here"), e.message)
    }

    @Test
    fun unknownTokenFails() {
        val e = assertThrows<DiagnosticsException> { parseModule("main: () Void { \$ }") }
        assertTrue(e.tag == "KiraLexer::nextToken" && e.message.contains("Symbol '$' is not known"), e.message)
    }

    @Test
    fun anEmptySourceIsAnEmptyModule() {
        assertEquals(emptyList(), declsOf(parse("")))
        assertEquals(emptyList(), declsOf(parse("// only a comment" + "\n")))
    }

    @Test
    fun aThreeLevelGenericInitialiserParses() {
        val ast = parseModule(
            """
            main: () Void {
                m: Map<Str, Map<Str, Map<Str, Int32>>> { }
            }
            """
        )
        val body = declsOf(ast).filterIsInstance<FunctionDecl>().single().def.body!!
        assertIs<ObjectInitExpr>(assertIs<VariableDecl>(body.single().expr).value)
    }

    @Test
    fun nestingTooDeepIsALocatedDiagnostic() {
        val deep = "(".repeat(50_000) + "1" + ")".repeat(50_000)
        val e = assertThrows<DiagnosticsException> { parseModule("x: Int32 { $deep }") }
        assertTrue(e.message.contains("nests too deeply"), e.message)
        assertEquals(1, e.location?.lineNumber)
    }

    // --- parser forms ---------------------------------

    // --- parsed-module helpers ---------------------------------------------------------

    private class Parsed(val ast: RootASTNode, val context: SourceContext) {
        /** Top-level nodes arrive wrapped in [Statement] from the parser. */
        val exprs: List<ASTNode>
            get() = ast.statements.map { if (it is Statement) it.expr else it }
        val decls: List<ASTNode>
            get() = exprs.filter { it !is ModuleDecl }
    }

    private fun growthParse(body: String): Parsed {
        val source = "" + body.trimIndent()
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

    /** [body] refused by the parser with [tag], its message holding [words]: the reason the test means. */
    private fun refusedAs(body: String, tag: String, words: String) {
        val e = assertThrows<DiagnosticsException>(body) { growthParse(body) }
        assertEquals(tag, e.tag, e.message)
        assertTrue(e.message.contains(words), e.message)
    }

    /** The analyzer's diagnostics for [body], every one at a real position. */
    private fun analyze(body: String): List<DiagnosticsException> {
        val result = TestCompileSupport.compileSnippet(
            "" + body.trimIndent(),
            "tests/growth.kira",
            runSemantic = true,
        )
        val diagnostics = assertNotNull(result.semanticResults).diagnostics
        for (diagnostic in diagnostics) {
            val location = diagnostic.location
            assertTrue(
                location == null || location.lineNumber >= 1,
                "a diagnostic at an unknown position cannot be rendered: ${diagnostic.message}"
            )
            // Rendering is what the CLI does with it; it must not throw.
            Diagnostics.recordDiagnostics(diagnostic)
        }
        return diagnostics
    }

    // --- struct -------------------------------------------------------------

    @Test
    fun structDeclarationParsesWithTraitsMembersAndInitially() {
        val s = growthParse(
            """
            pub Reply: Struct, Show, Eq {
                pub kind: Int32 { 0 }
                pub topic: Str { "" }
                pub isEmpty: () Bool { kind == 0 }
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
    fun aStructWithoutBodyOrTraitsIsTheHosts() {
        // A type adds at least one member; one with none parses only as the host's (marked).
        val bare = growthParse("@_extern(cpp = \"Unit\", header = \"unit.hxx\") Unit: Struct").single<StructDecl>()
        assertTrue(bare.members.isEmpty())
        assertTrue(bare.traits.isEmpty())
        assertNull(bare.initially)
        for (empty in listOf("Unit: Struct", "Empty: Struct { }")) {
            assertEquals("parse.type.empty", assertThrows<DiagnosticsException>(empty) { growthParse(empty) }.tag)
        }
    }

    @Test
    fun structRefusesFinallyAndDuplicateInitially() {
        refusedAs("S: Struct { finally { } }", "parse.removed", "'finally' is not part of Kira any more")
        refusedAs("S: Struct { initially { } initially { } }", "KiraParser::parseTypeBody", "only one 'initially' block")
    }

    // --- for ... in ---------------------------------------------------------

    @Test
    fun forInParsesAsTheSpecForm() {
        val fn = growthParse(
            """
            f: (names: Arr<Str>) Void {
                while i: Int32 in 0..8 { trace(i) }
                while b: Str in names { trace(b) }
                while k: Size in 0..n { trace(k) }
            }
            """
        ).single<FunctionDecl>()
        val loops = body(fn).map { assertIs<InIterationStatement>(it).inIterationExpr }
        assertEquals(3, loops.size)
        assertEquals("Int32", typeName(loops[0].declaredType))
        assertEquals("i", loops[0].initializer.value)
        assertIs<RangeExpr>(loops[0].target)
        assertEquals("Str", typeName(loops[1].declaredType))
        assertEquals("names", (loops[1].target as Identifier).value)
        assertEquals("Size", typeName(loops[2].declaredType))
    }

    @Test
    fun whileTakesATypedHeadAsIterationAndAnyOtherHeadAsACondition() {
        val fn = growthParse(
            """
            f: (r: Range<Int32>, j: Map<Str, Int32>, ok: Bool) Void {
                while k: Str in j.keys() { trace(k) }
                while ok && n < 5 { n += 1 }
                while x in r { trace(x) }
                while i: Size in 0..n { trace(i) }
            }
            """
        ).single<FunctionDecl>()
        val loops = body(fn)
        assertIs<InIterationStatement>(loops[0])
        assertIs<WhileIterationStatement>(loops[1])
        // `x in r` is a membership test with no `NAME: TYPE`, so it stays a condition.
        assertIs<BinaryExpr>(assertIs<WhileIterationStatement>(loops[2]).condition)
        assertIs<InIterationStatement>(loops[3])
    }

    @Test
    fun aRangeThatLeavesItsEndOutBindsAsTheInclusiveOneDoes() {
        val fn = growthParse(
            """
            f: (n: Int32, k: Int32) Void {
                0..<n as Int64
                -5..<5
                0..n
                k in 1..<n
                while i: Int32 in 0..<n { trace(i) }
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        fun initOf(i: Int) = assertIs<RangeExpr>(stmts[i].expr)
        val a = initOf(0)
        assertTrue(a.exclusive && a.end is TypeCastExpr, "0..<n as Int64 is 0..<(n as Int64)")
        val b = initOf(1)
        assertTrue(b.exclusive && b.begin is UnaryExpr, "-5..<5 is (-5)..<5")
        assertTrue(!initOf(2).exclusive, "a..b holds its end")
        val member = assertIs<BinaryExpr>(stmts[3].expr)
        assertEquals(BinaryOp.IN, member.operator)
        assertTrue((member.rightExpr as RangeExpr).exclusive, "x in a..<b is a membership test of the ..< range")
        assertTrue(assertIs<InIterationStatement>(stmts[4]).inIterationExpr.target.let { it is RangeExpr && it.exclusive })
    }

    @Test
    fun forInWithoutTheInKeywordIsRejected() {
        refusedAs("f: () Void { while i: Int32 0..8 { } }", "KiraParser::expect", "Expected an 'in' but got an Integer Literal")
    }

    // --- if-expressions -----------------------------------------------------

    @Test
    fun ifExpressionParsesInExpressionPosition() {
        val fn = growthParse(
            """
            f: (neg: Bool, x: Int32) Int32 {
                v: Int32 { neg ? -x : x }
                sign: Str { neg ? "-" : "" }
                v > LIMIT ? LIMIT : v
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
        val fn = growthParse(
            """
            f: (a: Bool, b: Bool) Str {
                a ? "a" : b ? "b" : "c"
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
        refusedAs(
            """
            f: (c: Bool) Int32 {
                v: Int32 { if c { 1 } }
                return v
            }
            """,
            "KiraParser::parseIfExpr",
            "An if-expression needs an 'else' branch",
        )
    }

    @Test
    fun statementLevelIfStaysAStatement() {
        val fn = growthParse("f: (c: Bool) Void { if c { trace(1) } else { trace(2) } }").single<FunctionDecl>()
        assertIs<IfSelectionStatement>(body(fn).single())
    }

    // --- lambdas ------------------------------------------------------------

    @Test
    fun lambdaParsesInExpressionPosition() {
        val fn = growthParse(
            """
            f: (buf: View<UInt8>) Void {
                twice: Fx<T1<Int32>, Int32> { (x: Int32) Int32 { x * 2 } }
                each(buf, (p: View<UInt8>) Void { n += 1 })
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
        val p = growthParse("named: (x: Int32) Int32 { x }")
        assertIs<FunctionDecl>(p.decls.single())
    }

    // --- default parameters ---------------------------------------------------

    @Test
    fun parameterDefaultsParse() {
        val fn = growthParse("""command: (verb: Str, args: Str = "", n: Int32 = 3 + 1) Str { verb }""")
            .single<FunctionDecl>()
        val params = fn.def.parameters
        assertNull(params[0].defaultValue)
        assertEquals("", assertIs<StringLiteral>(params[1].defaultValue).value)
        assertIs<BinaryExpr>(params[2].defaultValue)
    }

    @Test
    fun aRequiredParameterAfterADefaultIsRejected() {
        // Spec Default Parameters: `fx invalid: (port: Int32 = 8080, host: Str)` is an Error.
        for (signature in listOf("(port: Int32 = 8080, host: Str)", "(a: Int32, b: Int32 = 2, c: Int32)")) {
            val e = assertThrows<DiagnosticsException>(signature) { growthParse("f: $signature Void { }") }
            assertTrue(e.message.contains("required parameter cannot follow"), e.message)
            assertTrue(assertNotNull(e.location).lineNumber >= 1)
        }
        // A lambda's parameter list follows the same rule.
        refusedAs("f: () Void { g: Fx<T2<Int32, Int32>, Void> { (a: Int32 = 1, b: Int32) Void { } } }", "KiraParser::parseFunctionDeclParameters", "A required parameter cannot follow")
        // Defaults after every required parameter stay fine.
        growthParse("f: (host: Str, port: Int32 = 8080, ssl: Bool = true) Void { }")
    }

    // --- named construction -----------------------------------------------------

    @Test
    fun namedConstructionParsesPositionalFirstThenNamed() {
        val fn = growthParse(
            """
            f: (line: Str) Void {
                out: Reply { line = trimEnd(line), kind = 2 }
                p: Point { 1, y = 2 }
                e: Esc { pulse = 0 }
                q: Point { 1, 2 }
                empty: Walked {}
            }
            """.replace("e: Esc { pulse = 0 }", "e: Esc { pulse = 0 }")
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
        refusedAs("f: () Void { p: Point { y = 2, 1 } }", "KiraParser::parseObjectInit", "Positional arguments must come before named arguments in a construction")
        refusedAs("f: () Void { g(y = 2, 1) }", "KiraParser::parseFunctionCallParameter", "Positional arguments must come before named arguments")
    }

    // --- place assignment -------------------------------------------------------

    @Test
    fun placeAssignmentParsesForMembersIndexesAndCompounds() {
        val fn = growthParse(
            """
            f: () Void {
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
        val cls = growthParse(
            """
            pub Node: Mutable<Any> {
                pub label: Str
                pub get: () Str { this.label }
                pub set: (v: Str) Void { this.label = v }
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

    // --- initially, finally, override -----------------------------------------

    @Test
    fun classInitiallyAndOverrideParse() {
        val cls = growthParse(
            """
            Stop: Mutable<Behaviour> {
                count: Mutable<Int32> { 0 }
                initially { count = 1 }
                override pub id: () Str { "stop" }
            }
            """
        ).single<ClassDecl>()
        assertEquals(1, assertNotNull(cls.initially).size)
        assertEquals(listOf("Behaviour"), cls.parents.map { typeName(it) })
        val id = cls.members.filterIsInstance<FunctionDecl>().single()
        assertTrue(Modifier.OVERRIDE in id.modifiers)
        assertTrue(Modifier.PUBLIC in id.modifiers)
        assertEquals(1, cls.members.filterIsInstance<VariableDecl>().size)
        // A class without the blocks reads exactly as before.
        val plain = growthParse("Plain: Any { x: Int32 { 0 } }").single<ClassDecl>()
        assertNull(plain.initially)
    }

    @Test
    fun duplicateBlocksAndMisplacedOverrideAreRejected() {
        refusedAs("C: Any { initially { } initially { } }", "KiraParser::parseTypeBody", "only one 'initially' block")
        refusedAs("C: Any { finally { } }", "parse.removed", "'finally' is not part of Kira any more")
        refusedAs("override x: Int32 { 1 }", "KiraParser::expectModifiers", "The modifier 'override' cannot be applied to a variable")
    }

    // --- const type arguments, mut in tuples -------------------------------------

    @Test
    fun integerTypeArgumentsBecomeConstTypeArgs() {
        val p = growthParse(
            """
            pub MAGIC: Arr<UInt8, 4> { @arrOf(1, 2, 3, 4) }
            pub Arr<UInt8, USER_CMD_BYTES> as Frame
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
    fun declaredGenericsKeepThePascalCaseRule() {
        // The UPPER_SNAKE constant and the integer are argument-list forms
        // (`Arr<UInt8, N>`, `Arr<UInt8, 32>`); a declaration's own generics
        // are PascalCase names, as before this package. Measured before:
        // `class Box<T_X> { }` parsed.
        for (decl in listOf(
            "Box<T_X>: Any { }",
            "Pair<T_X>: Struct { }",
            "Arr<T_X> as Names<T_X>",
            "f<T_X>: () Void { }",
        )) {
            val e = assertThrows<DiagnosticsException>(decl) { growthParse(decl) }
            assertTrue(e.message.contains("Type parameter 'T_X' does not conform to PascalCase"), e.message)
        }
        refusedAs("Box<4>: Any { }", "KiraParser::expect", "Expected an identifier but got an Integer Literal")
        growthParse("Box<T>: Any { pub v: T }\nid<T>: (x: T) T { x }")
        // ...and the argument-list forms still parse where they belong.
        growthParse("f: (xs: Arr<UInt8, USER_CMD_BYTES>, each: Fx<T1<Str>, Void>) Void { }")
    }

    // --- intrinsic markers and calls ---------------------------------------------

    @Test
    fun externMarkerCarriesItsParameterAndLeavesNoStrayStatement() {
        val p = growthParse(
            """
            @_extern("sym") f: () Void
            @_extern(cpp = "ns::g", header = "g.hxx") g: (x: Int32) Int32
            pub @_const crc: () UInt32 { 1 }
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
        refusedAs("@_extern(name) f: () Void", "KiraParser::parseModifiers", "'@_extern' takes literal arguments")
        // Measured before: this compiled to a stray `"fopen";` statement.
        refusedAs(
            """
            openFile: (path: Str) Int32 {
                @_extern("fopen")
                return 0
            }
            """,
            "KiraParser::parseStatement",
            "'@_extern' marks the declaration that follows it, but a 'return' follows instead",
        )
    }

    @Test
    fun intrinsicCallArgumentsAreFullExpressions() {
        val p = growthParse(
            """
            @_static_assert(A == B, "sizes agree")
            f: () Void { @_trace_(LIMIT == 10) }
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

    @Test
    fun staticAssertArityIsADiagnosticAtTheCall() {
        // Measured before: StaticAssertIntrinsic.validate threw out of the
        // walk, the catch-all recorded it at SourcePosition.UNKNOWN and the
        // CLI died rendering it (IndexOutOfBoundsException: Index -2).
        for (program in listOf(
            "@_static_assert(1 == 1, \"a\", \"b\")\nf: () Void { }",
            "@_static_assert()\nf: () Void { }",
            "f: () Void { @_static_assert(1 == 1, \"a\", \"b\") }",
        )) {
            val arity = analyze(program).filter { it.message.contains("@_static_assert") }
            assertEquals(1, arity.size, "one arity diagnostic for:\n$program")
            assertTrue(arity.single().message.contains("condition and an optional message"), arity.single().message)
        }
        assertTrue(analyze("@_static_assert(1 == 1, \"ok\")\nf: () Void { }").none { it.message.contains("@_static_assert") })
    }

    @Test
    fun markerArgumentsReachTheMarkersArityCheck() {
        // Measured before: the analyzer validated a fresh argument-less
        // IntrinsicExpr, so `@_const(1)`, `@_opaque(1)` and a three-argument
        // `@_extern` all passed.
        val rejected = mapOf(
            "@_const(1) k: () Int32 { 1 }" to "@_const takes no arguments",
            "@_const(x = 1) k: () Int32 { 1 }" to "@_const takes no arguments",
            "@_extern(\"a\", \"b\", \"c\") f: () Void" to "@_extern accepts at most one",
            "@_opaque(1) X: Any { }" to "@_opaque does not take parameters",
        )
        for ((program, expected) in rejected) {
            val about = analyze(program).filter { it.message.contains(expected) }
            assertEquals(1, about.size, "one arity diagnostic for: $program")
            // The diagnostic points at the marker's own line.
            assertEquals(1, assertNotNull(about.single().location).lineNumber, program)
        }
        for (program in listOf(
            "@_const k: () Int32 { 1 }",
            "@_extern(\"a\") f: () Void",
            "@_opaque X: Any { }",
        )) {
            assertTrue(analyze(program).none { it.message.contains("@_") }, program)
        }
    }

    @Test
    fun aDiagnosticWithoutAPositionRendersItsMessage() {
        val p = growthParse("f: () Void { }")
        val diagnostic = Diagnostics.recordPanic("Test", "no position", location = SourcePosition.UNKNOWN, context = p.context)
        assertTrue(Diagnostics.recordDiagnostics(diagnostic).contains("no position"))
        assertTrue(diagnostic.formattedPanicMessage().contains("no position"))
    }

    // --- condition heads (rule D36) ------------------------------------------------

    @Test
    fun conditionHeadsNeverStartAConstruction() {
        // Measured before: `if x < LIMIT {` failed and `if x < (LIMIT) {` parsed.
        val fn = growthParse(
            """
            f: (x: Int32) Void {
                if x < LIMIT { trace(1) } else if x > TOP { trace(2) }
                while x < MAX { x += 1 }
                while b: Str in NAMES { trace(b) }
                if (x < LIMIT) { trace(3) }
                if x == LIMIT_M { trace(4) }
                if isOk(Foo { 1 }) { trace(5) }
                y: Int32 { x < LIMIT ? 1 : 2 }
                crc: Int32 { (x & 1) != 0 ? (x >> 1) ^ 7 : x >> 1 }
                dir: Int32 { (x == 1) == FORWARD_ASCENDING ? 1 : -1 }
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        fun rightName(e: ASTNode): String = ((e as BinaryExpr).rightExpr as Identifier).value
        val first = assertIs<IfSelectionStatement>(stmts[0])
        assertEquals("LIMIT", rightName(first.expr))
        assertEquals("TOP", rightName(assertIs<ElseIfBranchStatement>(first.elseBranches.single()).condition))
        assertEquals("MAX", rightName(assertIs<WhileIterationStatement>(stmts[1]).condition))
        assertEquals("NAMES", (assertIs<InIterationStatement>(stmts[2]).inIterationExpr.target as Identifier).value)
        assertEquals("LIMIT", rightName(assertIs<IfSelectionStatement>(stmts[3]).expr))
        assertEquals("LIMIT_M", rightName(assertIs<IfSelectionStatement>(stmts[4]).expr))
        // Parentheses reset the rule: inside a call's parentheses `Foo { 1 }` is a construction.
        val call = assertIs<FunctionCallExpr>(assertIs<IfSelectionStatement>(stmts[5]).expr)
        assertIs<ObjectInitExpr>(call.positionalParameters.single().value)
        assertEquals("LIMIT", rightName(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[6].expr).value).condition))
        // A leading `(` is an operand, not the whole condition (probes unilidar and hall).
        val crc = assertIs<BinaryExpr>(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[7].expr).value).condition)
        assertEquals(BinaryOp.NOT_EQUAL, crc.operator)
        assertIs<BinaryExpr>(crc.leftExpr)
        val dir = assertIs<BinaryExpr>(assertIs<IfExpr>(assertIs<VariableDecl>(stmts[8].expr).value).condition)
        assertEquals(BinaryOp.EQUALS, dir.operator)
        assertEquals("FORWARD_ASCENDING", (dir.rightExpr as Identifier).value)
    }

    @Test
    fun traitMethodsMayBeMut() {
        // design 5.9 chain: `pub mut fx onLoad: () Void { }` inside a trait.
        val p = growthParse(
            """
            pub Behaviour: Mutable<Any> {
                pub id: () Str
                pub onLoad: () Void { }
                pub step: (p: Pass) Reply
            }
            """
        )
        assertEquals(1, p.decls.size)
    }

    @Test
    fun constructionInAnUnparenthesizedConditionHeadIsNotAConstruction() {
        // `Foo { }` in a bare head is the condition `Foo` followed by the body `{ }`,
        // so what comes next is an error rather than a method call on a new Foo.
        refusedAs("f: () Void { if Foo { }.ok() { trace(1) } } ", "KiraParser::parsePrimaryExpr", "a '.' (Dot) is not allowed here")
        // A construction the body follows directly cannot be told from a
        // condition and a body either, so it is refused the same way.
        refusedAs("f: () Void { while Foo { 1 } { trace(1) } }", "parse.brace.type", "never `{ }` alone")
    }

    // --- char literals and interpolated strings ---------------------------------

    @Test
    fun charLiteralsDecodeTheirEscapes() {
        val p = growthParse(
            listOf(
                "a: Char { 'a' }",
                "sp: Char { ' ' }",
                "n: Char { '\\n' }",
                "t: Char { '\\t' }",
                "r: Char { '\\r' }",
                "b: Char { '\\\\' }",
                "q: Char { '\\'' }",
                "z: Char { '\\0' }",
            ).joinToString("\n")
        )
        val values = p.decls.map { assertIs<CharLiteral>(assertIs<VariableDecl>(it).value).value }
        assertEquals(listOf(97, 32, 10, 9, 13, 92, 39, 0), values)
    }

    @Test
    fun charLiteralsOutsideOneByteOrWithUnknownEscapesAreRejected() {
        refusedAs("c: Char { '€' }", "KiraParser::parseCharLiteral", "A Char is one byte (0..255)")
        refusedAs("c: Char { '\\q' }", "KiraParser::parseCharLiteral", "Unknown escape sequence '\\q'")
    }

    @Test
    fun interpolatedStringsParseIntoTextAndHoles() {
        val p = growthParse(
            listOf(
                "fixed3: (sign: Str, whole: Int32, frac: Int32, f: Float32) Str {",
                "    a: Str { \"\${sign}\${whole}.\${(frac as Str).padStart(3, '0')}\" }",
                "    b: Str { \"STEER \${fixed3(f)}\" }",
                "    c: Str { \"plain\" }",
                "    d: Str { \"cost \\\$5 and \\\${not a hole}\" }",
                "    e: Str { \"\${(f > 0.0 ? \"pos\" : \"neg\")}!\" }",
                "    a",
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
        val fn = growthParse(
            """
            f: () Void {
                while i: Int32 in 0..4 { trace(i) }
                p: Point { 1, 2 }
                x = 1
                x += 1
                @_trace_("plain")
                n = n + p.get()
            }
            """
        ).single<FunctionDecl>()
        val stmts = body(fn)
        assertIs<InIterationStatement>(stmts[0])
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
        "struct declaration" to "S: Struct { x: Int32 { 1 } }",
        // A returned ternary is the if/else of returns on C and JS; one held in a local is not lowered.
        "if-expression" to "f: (c: Bool) Int32 { x: Int32 { c ? 1 : 2 }\n x }",
        "lambda expression" to "f: () Void { g: Fx<T1<Int32>, Int32> { (x: Int32) Int32 { x } } }",
        "member or index place" to "P: Mutable<Any> { x: Mutable<Int32> { 0 } }\nf: (p: P) Void { p.x = 1 }",
        "'this' expression" to "P: Mutable<Any> { x: Mutable<Int32> { 0 }\n pub get: () Int32 { this.x } }",
        "char literal" to "f: () Void { c: Char { 'a' } }",
        "interpolated string literal" to "f: (n: Int32) Str { \"n=\${n}\" }",
        "an 'initially' block" to "P: Mutable<Any> { x: Mutable<Int32> { 0 }\n initially { x = 1 } }",
        "named construction" to "P: Mutable<Any> { x: Mutable<Int32> { 0 } }\nf: () Void { p: P { x = 1 } }",
        "omits a default-valued parameter ('b')" to "g: (a: Int32, b: Int32 = 2) Int32 { a + b }\nf: () Int32 { g(1) }",
        // Measured before: only the trailing positions were inspected, and this
        // one ended in an IllegalStateException from the argument binder.
        "omits a default-valued parameter ('b')" to "g: (a: Int32, b: Int32 = 2, c: Int32 = 3) Int32 { a + b + c }\nf: () Int32 { g(1, c = 3) }",
        "@_static_assert" to "f: () Void { @_static_assert(1 == 1, \"ok\") }",
        "const type argument" to "f: () Void { xs: Arr<Int32, 4> { @arrOf(1, 2, 3, 4) } }",
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
    fun aReturnedTernaryIsTheIfElseOfReturnsOnCAndJs() {
        // A body's last `c ? a : d ? b : e`, and nothing else of an if value.
        val src = "f: (a: Int32) Int32 { a < 0 ? -1 : a > 0 ? 1 : 0 }"
        val c = emitC(src)
        assertTrue(c.contains("if((a < 0))") && c.contains("else if((a > 0))") && c.contains("return 0;"), c)
        val js = emitJS(src)
        assertTrue(js.contains("a < 0") && js.contains("a > 0") && js.contains("return -1;") && js.contains("return 0;"), js)
        assertThrows<UnsupportedConstruct> { emitC("f: (c: Bool) Int32 { x: Int32 { c ? 1 : 2 }\n x }") }
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
        // A default parameter that every call supplies and a typed
        // range loop lower; only the omitted default and the new nodes are refused.
        val c = emitC(
            """
            g: (a: Int32, b: Int32 = 2) Int32 { a + b }
            f: () Int32 {
                n: Mutable<Int32> { 0 }
                while i: Int32 in 0..2 { n += i }
                n = g(n, b = 5)
                g(n, 1)
            }
            """.trimIndent()
        )
        assertTrue(c.contains("i_last = 2; i_more && i <= i_last"), c)
        assertTrue(c.contains("g(n, 5)"), c)
        assertTrue(c.contains("g(n, 1)"), c)
    }

    // --- the real CLI --------------------------------------------------------------

    private class CliRun(val exitCode: Int, val output: String)

    /**
     * Compiles a one-file project holding [program] (the body after its
     * module line) with the real CLI, semantic analysis included, the way a
     * user runs it: `kira --target [target]` against the repo's stdlib.
     */
    private fun runCli(name: String, target: String, program: String): CliRun {
        val repoRoot = File(System.getProperty("user.dir"))
        val dir = File(repoRoot, "build/tmp/parser-growth/cli/$name-$target").apply { deleteRecursively(); mkdirs() }
        File(dir, "project.kira").writeText(net.exoad.kira.ProjectText.of("growth", target = "c", stdlib = File(repoRoot, "kira").absolutePath.replace('\\', '/')))
        File(dir, "src/app/main.kira").apply { parentFile.mkdirs() }
            .writeText("" + program.trimIndent() + "\n")
        val java = System.getProperty("java.home") + "/bin/java"
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "net.exoad.kira.cli.MainKt", "--target", target
        ).directory(dir).start()
        val stderrHolder = StringBuilder()
        val reader = Thread { stderrHolder.append(process.errorStream.bufferedReader().readText()) }
        reader.start()
        val stdout = process.inputStream.bufferedReader().readText()
        reader.join()
        return CliRun(process.waitFor(), stdout + stderrHolder)
    }

    /**
     * (a fragment of the diagnostic) to (a whole program). Each program
     * passes the analyzer, so the backend is what refuses it: a lambda and a
     * char literal are passed as arguments because the stdlib declares no
     * `Fx` or `Char` type yet, and a variable of either type stops earlier
     * with "type not found".
     */
    private val cliConstructs = listOf(
        "struct declaration" to "S: Struct { x: Int32 { 1 } }\nmain: () Int32 { 0 }",
        "if-expression" to "main: () Int32 { x: Int32 { 1 }\n y: Int32 { if x > 0 {\n trace(x)\n 1\n } else {\n 2\n } }\n y }",
        "if-expression" to "main: () Int32 { x: Int32 { 1 }\n y: Int32 { x > 0 ? 1 : 2 }\n y }",
        "a try used as a value" to "main: () Int32 { x: Int32 { try { 1 } on e: Str { 2 } }\n x }",
        "lambda expression" to "main: () Int32 { trace((x: Int32) Int32 { x })\n 0 }",
        "member or index place" to "P: Mutable<Any> { x: Mutable<Int32> { 0 } }\nmain: () Int32 { p: P { P { 0 } }\n p.x = 1\n p.x }",
        "'this' expression" to "P: Mutable<Any> {\n x: Mutable<Int32> { 0 }\n pub get: () Int32 { this.x }\n}\nmain: () Int32 { p: P { P { 0 } }\n p.get() }",
        "char literal" to "main: () Int32 { trace('a')\n 0 }",
        "interpolated string literal" to "main: () Int32 { n: Int32 { 3 }\n s: Str { \"n=\${n}\" }\n trace(s)\n 0 }",
        "an 'initially' block" to "P: Mutable<Any> {\n x: Mutable<Int32> { 0 }\n initially { x = 1 }\n}\nmain: () Int32 { p: P { P { 0 } }\n p.x }",
        "named construction" to "P: Mutable<Any> { x: Mutable<Int32> { 0 } }\nmain: () Int32 { p: P { x = 1 }\n p.x }",
        "omits a default-valued parameter ('b')" to "g: (a: Int32, b: Int32 = 2) Int32 { a + b }\nmain: () Int32 { g(1) }",
        "omits a default-valued parameter ('b')" to "g: (a: Int32, b: Int32 = 2, c: Int32 = 3) Int32 { a + b + c }\nmain: () Int32 { g(1, c = 3) }",
        "@_static_assert" to "@_static_assert(1 == 1, \"ok\")\nmain: () Int32 { 0 }",
        "@_static_assert" to "main: () Int32 { @_static_assert(1 == 1, \"ok\")\n 0 }",
        "const type argument" to "main: () Int32 { xs: Arr<Int32, 4> { @arrOf(1, 2, 3, 4) }\n 0 }",
    )

    private fun assertRefusedByTheBackend(target: String, backend: String) {
        for ((index, entry) in cliConstructs.withIndex()) {
            val (construct, program) = entry
            val run = runCli("construct-$index", target, program)
            val all = run.output
            assertEquals(1, run.exitCode, "exit 1 for:\n$program\n$all")
            assertTrue(all.contains("-- Diagnostic Report: "), "a diagnostic for:\n$program\n$all")
            assertTrue(all.contains(construct), "'$construct' named for:\n$program\n$all")
            assertTrue(all.contains("$backend backend"), "the $backend target named for:\n$program\n$all")
            assertFalse(all.contains("Exception in thread"), "no stack trace for:\n$program\n$all")
            assertFalse(all.contains("\tat net.exoad"), "no stack trace for:\n$program\n$all")
        }
    }

    @Test
    fun eachNewConstructIsADiagnosticNotAStackTraceAtTheCliOnTheCTarget() {
        assertRefusedByTheBackend("c", "C")
    }

    @Test
    fun eachNewConstructIsADiagnosticNotAStackTraceAtTheCliOnTheJSTarget() {
        assertRefusedByTheBackend("js", "JS")
    }

    @Test
    fun anArityErrorIsADiagnosticNotAStackTraceAtTheCli() {
        // Measured before: exit 1 with 'Exception in thread "main"
        // java.lang.IndexOutOfBoundsException: Index -2 out of bounds'.
        val cases = listOf(
            "sa-top" to ("@_static_assert(1 == 1, \"a\", \"b\")\nf: () Void { }" to "@_static_assert takes a condition"),
            "sa-fn" to ("f: () Void { @_static_assert() }" to "@_static_assert takes a condition"),
            "const" to ("@_const(1) k: () Int32 { 1 }" to "@_const takes no arguments"),
            "extern" to ("@_extern(\"a\", \"b\", \"c\") f: () Void" to "@_extern accepts at most one"),
            "opaque" to ("@_opaque(1) X: Any { }" to "@_opaque does not take parameters"),
        )
        for ((name, case) in cases) {
            val (program, expected) = case
            val run = runCli("arity-$name", "none", program)
            val all = run.output
            assertEquals(1, run.exitCode, "exit 1 for:\n$program\n$all")
            assertTrue(all.contains(expected), "'$expected' for:\n$program\n$all")
            assertFalse(all.contains("Exception in thread"), "no stack trace for:\n$program\n$all")
            assertFalse(all.contains("\tat net.exoad"), "no stack trace for:\n$program\n$all")
        }
    }
}
