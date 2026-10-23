package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full parser coverage for the Kotlin-native frontend (KiraLexer + KiraParser):
 * every declaration, statement, and expression form the grammar accepts, plus
 * malformed-program diagnostics. Unsupported surface (nullable types, the
 * `fx (...) R` function-type spelling) is pinned as rejected so the boundary
 * is explicit. `this`, lambdas in expression position and initially/finally
 * blocks were once pinned as rejected; design 2.4 (the C++ backend's AST
 * contract) makes them syntax, so they are pinned as accepted here and
 * covered in full by ParserGrowthTest.
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
            immutable: Int32 = 1
            mut mutable: Str = "a"
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

            stub: (x: Str) Void;
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
            pub Counter: Any {
                pub value: Int32
                mut pub label: Str = ""

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
                a: Arr<Arr<Arr<Int32>>> = @arrOf(@arrOf(@arrOf(1)))
                b: Arr<Arr<Arr<Arr<Int32>>>> = @arrOf(@arrOf(@arrOf(@arrOf(1))))
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
                a: Arr<Int32> = @arrOf(1)
                b: Bool = a.size() > 0
                c: Bool = a.size() >= 1
                d: Bool = a.size() < 2
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
                x: Int32 = id<Int32>(1)
                s: Str = id<Str>("a")
            }
            """
        )
    }

    @Test
    fun aComparisonWithANegatedOperandIsNotATypeArgumentList() {
        val ast = parseModule(
            """
            f: (x: Int64) Bool {
                low: Bool = x < -LIMIT
                high: Bool = x > (LIMIT)
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
                x: Int32 = 2
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
                mut i: Int32 = 0
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
                for mut i: 0..5 {
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
                mut result: Int32 = 0
                for mut i: 0..10 {
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
                mut a: Int32 = 1
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
                a: Int32 = 1 + 2 * 3 - 4 / 2
                b: Bool = a > 2 && a <= 9 || a != 5
                c: Int32 = 1 .. 3 .size()
                d: Int32 = -a
                e: Bool = !b
                f: Int32 = ~a
            }
            """
        )
    }

    @Test
    fun parsesPostfixMemberIndexCallCastAndTypeTest() {
        parseModule(
            """
            main: () Void {
                values: Arr<Int32> = @arrOf(1, 2, 3)
                first: Int32 = values[0]
                length: Int32 = values.size()
                typed: Int32 = first as Int32
                isArr: Bool = values == Arr<Int32>
                obj: Any = values
                cast: Arr<Int32> = obj as Arr<Int32>
            }
            """
        )
    }

    @Test
    fun asBindsTighterThanTheBinaryOperatorsAndLooserThanAPrefixOne() {
        val ast = parseModule(
            """
            f: (x: Int32, n: Int64) Int32 {
                a: Float64 = x as Float64 / 1000.0
                b: Int64 = n + x as Int64
                c: Bool = x as Int64 > n
                d: Int64 = -x as Int64
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
                    "    c: Bool = x as Int64 < n\n" +
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
                c: Bool = (x as Int64) < n
                d: List<Int32> = xs as List<Int32>
                c
            }
            """
        )
    }

    private fun valueOf(expr: String): net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr =
        assertNotNull(declsOf(parseModule("x: Int32 = $expr")).filterIsInstance<VariableDecl>().single().value)

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
                mut s: Int64 = 0
                for i: Int64 in 0..n as Int64 {
                    s += i
                }
                for j: Int64 in 0 as Int64..n as Int64 {
                    s += j
                }
                s
            }
            """
        )
        val loops = declsOf(ast).filterIsInstance<FunctionDecl>().single().def.body!!.filterIsInstance<ForIterationStatement>()
        assertEquals(2, loops.size)
        val first = assertIs<RangeExpr>(loops[0].forIterationExpr.target)
        assertIs<TypeCastExpr>(first.end)
        val second = assertIs<RangeExpr>(loops[1].forIterationExpr.target)
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
                p1: Point = Point { 1, 2 }
                p2: Point = Point { x = 3, y = 4 }
            }
            """
        )
    }

    @Test
    fun parsesArrayLiterals() {
        parseModule(
            """
            main: () Void {
                empty: Arr<Int32> = @arrOf()
                nums: Arr<Int32> = @arrOf(1, 2, 3)
                nested: Arr<Arr<Int32>> = @arrOf(@arrOf(1), @arrOf(2, 3))
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
        // Was pinned as rejected; design 2.4 adds ThisExpr (see ParserGrowthTest).
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
        // `(x: Int32) Int32` is the spelling of Fx<Tuple1<Int32>, Int32>, `() Void` of Fx<Tuple0, Void>.
        val ast = parseModule(
            """
            f: (x: Int32) Int32 = (x: Int32) Int32 { x }
            g: Fx<Tuple1<Int32>, Int32> = (x: Int32) Int32 { x }
            h: () Void = () Void { }
            k: Fx<Tuple0, Void> = () Void { }
            """
        )
        fun text(t: net.exoad.kira.compiler.frontend.parser.ast.elements.Type): String {
            val name = (t.identifier as Identifier).value
            return if (t.children.isEmpty()) name else name + "<" + t.children.joinToString(", ") { text(it) } + ">"
        }
        val types = declsOf(ast).filterIsInstance<VariableDecl>().map { text(it.type) }
        assertEquals(listOf("Fx<Tuple1<Int32>, Int32>", "Fx<Tuple1<Int32>, Int32>", "Fx<Tuple0, Void>", "Fx<Tuple0, Void>"), types)
    }

    @Test
    fun namingConventionsReserveUnderscoresForConstants() {
        // Accepted: UPPER_SNAKE_CASE globals and fields.
        val ast = parseModule(
            """
            MAX_SIZE: Int32 = 10

            Limits: Any {
                pub MAX_X: Int32
            }
            """
        )
        assertEquals(2, declsOf(ast).size, ast.toString())
        // Rejected: types, functions, parameters and type parameters.
        for ((bad, tag, words) in listOf(
            Triple("A_T: Any {}", "parse.type.name", "'A_T' is a constant's name"),
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
    fun parsesBareReturn() {
        val ast = parseModule(
            """
            stop: (n: Int32) Void {
                if n < 0 {
                    return
                }
                trace(n)
                return;
            }
            """
        )
        val fn = declsOf(ast).filterIsInstance<FunctionDecl>().single()
        val returns = mutableListOf<ReturnStatement>()
        fun walk(statements: List<Statement>?) {
            statements?.forEach { stmt ->
                if (stmt is ReturnStatement) returns.add(stmt)
                if (stmt is IfSelectionStatement) walk(stmt.thenStatements)
            }
        }
        walk(fn.def.body)
        assertEquals(2, returns.size, ast.toString())
        assertTrue(returns.all { it.expr is NoExpr }, returns.toString())
    }

    @Test
    fun initiallyBlockParses() {
        // Design 2.4 adds ClassDecl.initially (see ParserGrowthTest).
        val ast = parseModule(
            """
            pub C: Any {
                mut x: Int32 = 0
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
                m: Map<Str, Map<Str, Map<Str, Int32>>> = Map<Str, Map<Str, Map<Str, Int32>>> { }
            }
            """
        )
        val body = declsOf(ast).filterIsInstance<FunctionDecl>().single().def.body!!
        assertIs<ObjectInitExpr>(assertIs<VariableDecl>(body.single().expr).value)
    }

    @Test
    fun nestingTooDeepIsALocatedDiagnostic() {
        val deep = "(".repeat(50_000) + "1" + ")".repeat(50_000)
        val e = assertThrows<DiagnosticsException> { parseModule("x: Int32 = $deep") }
        assertTrue(e.message.contains("nests too deeply"), e.message)
        assertEquals(1, e.location?.lineNumber)
    }
}
