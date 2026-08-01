package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
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
 * malformed-program diagnostics. Unsupported surface (nullable types, `this`,
 * lambdas, bare `return`, initially/finally blocks) is pinned as rejected so
 * the boundary is explicit.
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
        module "test:parser"
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
    fun parsesModuleDeclaration() {
        val ast = parse("""module "test:parser" """)
        val first = exprsOf(ast).first()
        assertIs<ModuleDecl>(first)
        assertEquals("test:parser", first.uri.value)
        assertEquals("parser", first.getModuleName())
    }

    @Test
    fun parsesUseImport() {
        parseModule("""use "kira:core" """)
    }

    @Test
    fun requiresModuleFirst() {
        assertThrows<Throwable>("a program must start with a module declaration") {
            parse("fx main: () Void { }")
        }
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
            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            fx stub: (x: Str) Void;
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
            pub class Counter {
                require pub value: Int32
                mut pub label: Str

                pub fx increment: () Int32 {
                    value = value + 1
                    return value
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
            pub trait Speaker {
                pub fx speak: () Str
            }

            pub trait Loud: Speaker {
                pub fx volume: () Int32 {
                    return 8
                }
            }
            """
        )
        assertEquals(2, declsOf(ast).filterIsInstance<TraitDecl>().size)
    }

    @Test
    fun parsesEnumWithExplicitValues() {
        val ast = parseModule(
            """
            pub enum Status {
                READY = 1,
                RUNNING = 2,
                DONE = 4
            }
            """
        )
        assertEquals(1, declsOf(ast).filterIsInstance<EnumDecl>().size)
    }

    @Test
    fun parsesVariantWithoutModifier() {
        parseModule(
            """
            variant Shape {
                Circle: Radius
                Square: Side
            }
            """
        )
    }

    @Test
    fun parsesTypeAlias() {
        val ast = parseModule(
            """
            pub alias MyInt as Int32
            """
        )
        assertEquals(1, declsOf(ast).filterIsInstance<TypeAliasDecl>().size)
    }

    @Test
    fun parsesGenericsWithBounds() {
        val ast = parseModule(
            """
            pub class Box<T> {
                require pub value: T
            }

            pub class Pair<K, V: Any> {
                require pub first: K
                require pub second: V
            }

            fx id<T: Any>: (value: T) T {
                return value
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
            fx main: () Void {
                a: Arr<Arr<Arr<Int32>>> = [[[1]]]
                b: Arr<Arr<Arr<Arr<Int32>>>> = [[[[1]]]]
            }
            """
        )
        assertTrue(exprsOf(ast).any { it is FunctionDecl }, "deep generic program should parse")
    }

    @Test
    fun genericsAndComparisonsOnTheSameLine() {
        parseModule(
            """
            fx main: () Void {
                a: Arr<Int32> = [1]
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
            fx id<T>: (v: T) T {
                return v
            }

            fx main: () Void {
                x: Int32 = id<Int32>(1)
                s: Str = id<Str>("a")
            }
            """
        )
    }

    // --- statements ---------------------------------------------------------

    @Test
    fun parsesIfElseIfElse() {
        parseModule(
            """
            fx main: () Void {
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
    fun parsesWhileAndDoWhile() {
        parseModule(
            """
            fx main: () Void {
                mut i: Int32 = 0
                while i < 3 {
                    i = i + 1
                }
                do {
                    i = i - 1
                } while i > 0
            }
            """
        )
    }

    @Test
    fun parsesForRange() {
        parseModule(
            """
            fx main: () Void {
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
            fx pick: (x: Int32) Int32 {
                for mut i: 0..10 {
                    if i == 2 {
                        continue
                    }
                    if i == 8 {
                        break
                    }
                    if i == 4 {
                        return i
                    }
                }
                return 0
            }
            """
        )
    }

    @Test
    fun parsesThrowAndTryOn() {
        parseModule(
            """
            fx fail: () Void {
                throw Exception { "boom" }
            }

            fx main: () Void {
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
            fx main: () Void {
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
            fx main: () Void {
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
    fun parsesPostfixMemberIndexCallCastIs() {
        parseModule(
            """
            fx main: () Void {
                values: Arr<Int32> = [1, 2, 3]
                first: Int32 = values[0]
                length: Int32 = values.size()
                typed: Int32 = first as Int32
                isArr: Bool = values is Arr<Int32>
                obj: Any = values
                cast: Arr<Int32> = obj as Arr<Int32>
            }
            """
        )
    }

    @Test
    fun parsesObjectCreationPositionalAndNamed() {
        parseModule(
            """
            pub class Point {
                require pub x: Int32
                require pub y: Int32
            }

            fx main: () Void {
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
            fx main: () Void {
                empty: Arr<Int32> = []
                nums: Arr<Int32> = [1, 2, 3]
                nested: Arr<Arr<Int32>> = [[1], [2, 3]]
            }
            """
        )
    }

    @Test
    fun parsesIntrinsicCalls() {
        parseModule(
            """
            fx main: () Void {
                @__dummy__("hello")
                trace("plain")
            }
            """
        )
    }

    // --- unsupported surface (pinned boundaries) ------------------------------

    @Test
    fun nullableTypesAreRejected() {
        assertThrows<Throwable> {
            parseModule("pub class Node { require pub next: Node? }")
        }
    }

    @Test
    fun thisKeywordIsRejected() {
        assertThrows<Throwable> {
            parseModule(
                """
                pub class Node {
                    require pub label: Str
                    pub fx get: () Str {
                        return this.label
                    }
                }
                """
            )
        }
    }

    @Test
    fun lambdaExpressionsAreRejected() {
        assertThrows<Throwable> {
            parseModule(
                """
                fx main: () Void {
                    f: fx (x: Int32) Int32 = fx (x: Int32) Int32 { return x }
                }
                """
            )
        }
    }

    @Test
    fun bareReturnIsRejected() {
        assertThrows<Throwable> {
            parseModule("fx main: () Void { return }")
        }
    }

    @Test
    fun initiallyFinallyBlocksAreRejected() {
        assertThrows<Throwable> {
            parseModule(
                """
                pub class C {
                    initially { x = 1 }
                }
                """
            )
        }
    }

    // --- malformed programs --------------------------------------------------

    @Test
    fun garbageFailsToParse() {
        assertThrows<Throwable> {
            parseModule("fx fx fx { { {")
        }
    }

    @Test
    fun unterminatedBlockFails() {
        assertThrows<Throwable> {
            parseModule("fx main: () Void {")
        }
    }

    @Test
    fun badStatementFails() {
        assertThrows<Throwable> {
            parseModule("fx main: () Void { 1 + }")
        }
    }

    @Test
    fun unknownTokenFails() {
        assertThrows<Throwable> {
            parseModule("fx main: () Void { \$ }")
        }
    }
}
