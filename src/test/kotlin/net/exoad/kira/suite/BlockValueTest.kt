package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TernaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.dialect.DialectConverter
import net.exoad.kira.dialect.TreeProof
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Stage 3 of the clean dialect, lane A: block values, `if`/`try` as expressions, the ternary, and their lowering. */
class BlockValueTest {
    private fun ast(body: String): RootASTNode {
        val unit = TyperTestSupport.unitOf(module("test:main", body))
        return unit.allSources().first { runCatching { it.getModuleUri() }.getOrNull() == "test:main" }.ast as RootASTNode
    }

    private fun fn(root: RootASTNode, name: String): FunctionDecl =
        root.statements.mapNotNull { (it as? Statement)?.expr }.filterIsInstance<FunctionDecl>().first { KiraUnparser.text(it.name) == name }

    private fun body(body: String, name: String = "f"): List<Statement> = fn(ast(body), name).def.body!!

    /** The value of `x: T = ...` in [body]'s function f. */
    private fun value(body: String): net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr =
        body(body).firstNotNullOf { (it.expr as? VariableDecl)?.value }

    private fun parseError(body: String): DiagnosticsException = assertThrows { ast(body) }

    private fun tail(s: Statement): ReturnStatement = assertIs<ReturnStatement>(s).also { assertTrue(it.implicit, "an implicit return") }

    // ---- a body's last expression ------------------------------------------------------------

    @Test
    fun aValueBodysLastExpressionIsItsResult() {
        val b = body("f: (x: Int32) Int32 {\n    y: Int32 = x * 2\n    y + 1\n}")
        assertEquals("y + 1", KiraUnparser.text(tail(b.last()).expr))
        expectNoErrors(TyperTestSupport.snippet("f: (x: Int32) Int32 {\n    y: Int32 = x * 2\n    y + 1\n}"))
    }

    @Test
    fun aVoidBodyDropsItsLastValue() {
        val src = "g: () Int32 {\n    1\n}\n\nf: () Void {\n    g()\n}"
        assertFalse(body(src).last() is ReturnStatement)
        expectNoErrors(TyperTestSupport.snippet(src))
    }

    @Test
    fun theResultReachesThroughAFinalElseChainAndATry() {
        val chain = body("f: (x: Int32) Str {\n    if x < 0 {\n        \"neg\"\n    } else if x == 0 {\n        z: Str = \"zero\"\n        z\n    } else {\n        \"pos\"\n    }\n}")
        val s = assertIs<IfSelectionStatement>(chain.single())
        tail(s.thenStatements.single())
        tail(s.elseBranches.first().let { (it as net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement).statements.last() })
        val tried = body("h: (s: Str) Int32 {\n    1\n}\n\nf: (s: Str) Int32 {\n    try {\n        h(s)\n    } on e: Str {\n        -1\n    }\n}")
        val t = assertIs<TryExpr>(tried.single().expr)
        assertFalse(t.isValue, "a body's last try stays a statement whose blocks return")
        tail(t.tryBlock.single())
        tail(t.handlerBlock.single())
        // An if with no else is a statement: the body has no value on its other path.
        expectDiagnostic(TyperTestSupport.snippet("f: (x: Int32) Int32 {\n    if x > 0 {\n        1\n    }\n}"), "rules.return.missing")
    }

    @Test
    fun aLambdasBodyEndsInItsValue() {
        val p = TyperTestSupport.snippet("f: () Int32 {\n    g: (n: Int32) Int32 = (n: Int32) Int32 {\n        m: Int32 = n * 3\n        m - 1\n    }\n    g(2)\n}")
        expectNoErrors(p)
    }

    @Test
    fun aTailOfTheWrongTypeIsNamedAsTheResult() {
        val d = expectDiagnostic(TyperTestSupport.snippet("f: () Int32 {\n    \"text\"\n}"), "types.assign.mismatch")
        assertTrue(d.message.contains("its body's last expression"), d.message)
    }

    // ---- return alongside ---------------------------------------------------------------------

    @Test
    fun anEarlyReturnAndATailMixButAValueAfterAReturnIsRefused() {
        val ok = "f: (x: Int32) Int32 {\n    if x < 0 {\n        return 0\n    }\n    x * 2\n}"
        expectNoErrors(TyperTestSupport.snippet(ok))
        // One arm returns and the other ends in its value: both are the function's result.
        expectNoErrors(TyperTestSupport.snippet("f: (x: Int32) Int32 {\n    if x < 0 {\n        return 0\n    } else {\n        x\n    }\n}"))
        assertEquals("parse.return.tail", parseError("f: (x: Int32) Int32 {\n    return x\n    x + 1\n}").tag)
        assertEquals("parse.return.tail", parseError("f: (x: Int32) Int32 {\n    if x < 0 {\n        return 0\n        1\n    } else {\n        x\n    }\n}").tag)
        // `return x` as the last statement is the result too, unchanged.
        assertIs<ReturnStatement>(body("f: (x: Int32) Int32 {\n    return x\n}").single()).also { assertFalse(it.implicit) }
        // A lambda's body is read the same way.
        assertEquals("parse.return.tail", parseError("f: () Int32 {\n    g: () Int32 = () Int32 {\n        return 1\n        2\n    }\n    g()\n}").tag)
        // A try's block that returns, then a value after the try, is a guard and a tail.
        expectNoErrors(TyperTestSupport.snippet("h: () Int32 {\n    return 1\n}\n\nf: () Int32 {\n    try {\n        return h()\n    } on e: Str {\n        trace(e)\n    }\n    0\n}"))
    }

    @Test
    fun theStageOneProofReadsANeverTailAsTheStatementItWas() {
        val old = "module \"p:a\"\n\nfx fail: (m: Str) Never {\n    throw m\n}\n\nfx f: (x: Int32) Int32 {\n    if x > 0 {\n        return x\n    }\n    fail(\"no\")\n}\n"
        val r = DialectConverter.convert(old, "a.kira")
        assertEquals(emptyList(), r.problems.map { it.toString() })
        val (nc, err) = TreeProof.parseNew(r.text, "a.kira", "p.a")
        assertEquals(null, err)
        val f = (nc!!.ast as RootASTNode).let { fn(it, "f") }
        tail(f.def.body!!.last())
        assertEquals(null, TreeProof.compare(r.old!!, nc, DialectConverter.Renames()))
    }

    // ---- if, try and with as expressions -----------------------------------------------------

    @Test
    fun anIfValuesArmsEndInValuesThroughANestedIfAndTry() {
        val src = "h: () Int32 {\n    1\n}\n\n" +
            "f: (c: Bool, d: Bool) Int32 {\n    x: Int32 = if c {\n        h()\n        if d {\n            h()\n            1\n        } else {\n            2\n        }\n" +
            "    } else {\n        try {\n            h()\n        } on e: Str {\n            0\n        }\n    }\n    x\n}"
        val e = assertIs<IfExpr>(value(src))
        assertIs<IfExpr>(e.thenBranch.last().expr)
        assertTrue(assertIs<TryExpr>(e.elseBranch.last().expr).isValue)
        expectNoErrors(TyperTestSupport.snippet(src))
    }

    @Test
    fun aTryValueNeedsItsOnAndEndsInAValue() {
        assertEquals("parse.try.value", parseError("f: () Int32 {\n    x: Int32 = try {\n        1\n    }\n    x\n}").tag)
        val bad = TyperTestSupport.snippet("f: () Int32 {\n    mut y: Int32 = 0\n    x: Int32 = try {\n        y = 1\n    } on e: Str {\n        0\n    }\n    x\n}")
        expectDiagnostic(bad, "types.try.value")
        expectDiagnostic(TyperTestSupport.snippet("f: () Int32 {\n    x: Int32 = try {\n        \"one\"\n    } on e: Str {\n        0\n    }\n    x\n}"), "types.assign.mismatch")
        expectNoErrors(TyperTestSupport.snippet("f: () Str {\n    x: Str = try {\n        throw \"no\"\n    } on e: Str {\n        e\n    }\n    x\n}"))
    }

    // ---- the ternary -------------------------------------------------------------------------

    @Test
    fun theTernaryBindsBelowEveryBinaryOperatorAndNestsToTheRight() {
        val head = "f: (a: Bool, b: Bool, n: Int32) Int32 {\n    x: Int32 = "
        val or = assertIs<TernaryExpr>(value("$head a || b ? n + 1 : n * 2\n    x\n}"))
        assertIs<BinaryExpr>(or.condition)
        assertEquals("a || b ? n + 1 : n * 2", KiraUnparser.text(or))
        val right = assertIs<TernaryExpr>(value("$head a ? 1 : b ? 2 : 3\n    x\n}"))
        assertIs<TernaryExpr>(right.no)
        val grouped = assertIs<BinaryExpr>(value("$head (a ? 1 : 2) + n\n    x\n}"))
        assertIs<TernaryExpr>(grouped.leftExpr)
        assertIs<TernaryExpr>(value("$head a ? (b ? 1 : 2) : 3\n    x\n}"))
    }

    @Test
    fun aNameBeforeTheColonIsTheArmNotADeclaration() {
        val p = "P: Struct {\n    pub x: Int32 = 0\n    pub y: Int32 = 0\n}\n\n"
        val e = assertIs<TernaryExpr>(value("${p}f: (c: Bool, q: P, m: Int32, n: Int32) Int32 {\n    x: Int32 = c ? m : n\n    x\n}"))
        assertEquals("m", KiraUnparser.text(e.yes))
        expectNoErrors(TyperTestSupport.snippet("${p}f: (c: Bool, q: P) Int32 {\n    c ? q.x : q.y\n}"))
        // A block in the first arm declares again: a lambda's local.
        expectNoErrors(
            TyperTestSupport.snippet(
                "f: (c: Bool) Int32 {\n    g: (n: Int32) Int32 = c ? (n: Int32) Int32 {\n        k: Int32 = n\n        k\n    } : (n: Int32) Int32 {\n        n\n    }\n    g(1)\n}",
            ),
        )
        // Named arguments and declarations keep their `=` and `:` around a ternary.
        expectNoErrors(TyperTestSupport.snippet("g: (a: Int32, b: Int32) Int32 {\n    a + b\n}\n\nf: (c: Bool) Int32 {\n    y: Int32 = c ? 1 : 2\n    g(a = c ? y : 0, b = 1)\n}"))
    }

    @Test
    fun aTernaryWithoutItsSecondArmOrWithAnAssignmentIsRefused() {
        assertEquals("parse.ternary.colon", parseError("f: (c: Bool) Int32 {\n    x: Int32 = c ? 1\n    x\n}").tag)
        assertEquals("parse.ternary.nested", parseError("f: (a: Bool, b: Bool) Int32 {\n    x: Int32 = a ? b ? 1 : 2 : 3\n    x\n}").tag)
        assertEquals("parse.ternary.arm", parseError("f: (c: Bool) Void {\n    mut y: Int32 = 0\n    c ? y = 1 : 2\n}").tag)
        expectDiagnostic(TyperTestSupport.snippet("f: (c: Bool) Int32 {\n    x: Int32 = c ? 1 : \"two\"\n    x\n}"), "types.assign.mismatch")
        val cond = expectDiagnostic(TyperTestSupport.snippet("f: (n: Int32) Int32 {\n    x: Int32 = n ? 1 : 2\n    x\n}"), "types.assign.mismatch")
        assertTrue(cond.message.contains("the condition of `?:`"), cond.message)
        // A ternary is a value: Void arms are refused as an if-expression's are.
        val void = expectDiagnostic(TyperTestSupport.snippet("g: () Void {\n}\n\nf: (c: Bool) Void {\n    c ? g() : g()\n}"), "types.if.branch-value")
        assertTrue(void.message.startsWith("An arm of `?:`"), void.message)
    }

    @Test
    fun aComparisonBeforeTheColonIsNoGenericCall() {
        // `a < b : b > (a)` holds a `:`, which no call's type arguments do: two comparisons.
        val e = assertIs<TernaryExpr>(value("f: (c: Bool, a: Int32, b: Int32) Bool {\n    x: Bool = c ? a < b : b > (a)\n    x\n}"))
        assertIs<BinaryExpr>(e.yes)
        assertIs<BinaryExpr>(e.no)
        expectNoErrors(TyperTestSupport.snippet("f: (c: Bool, a: Int32, b: Int32) Bool {\n    c ? a < b : b > (a)\n}"))
    }

    @Test
    fun aLoopIsAStatementNotAValue() {
        val w = parseError("f: (c: Bool) Int32 {\n    x: Int32 = while c {\n        break\n    }\n    x\n}")
        assertEquals("parse.loop.value", w.tag)
        assertTrue(w.message.contains("`mut` local") && w.message.contains("`break`"), w.message)
        assertEquals("parse.loop.value", parseError("f: () Int32 {\n    x: Int32 = 1 + for i: Int32 in 0..3 {\n    }\n    x\n}").tag)
        // A value body that ends in a loop has no value on that path.
        expectDiagnostic(TyperTestSupport.snippet("f: () Int32 {\n    mut n: Int32 = 0\n    while n < 3 {\n        n += 1\n    }\n}"), "rules.return.missing")
    }

    @Test
    fun theTernarySitsBesideMaybeACastAGenericABuilderAndAnInterpolation() {
        val src = "f: (c: Bool, x: Int32, xs: List<Int32>) Int64 {\n" +
            "    m: Maybe<Int32> = c ? x : null\n" +
            "    w: Int64 = c ? x as Int64 : 0\n" +
            "    ys: List<Int32> = c ? List<Int32> { } : xs\n" +
            "    k: Map<Str, Int32> = @mapOf(\"a\", c ? 1 : 2)\n" +
            "    s: Str = \"<\${c ? x : 0}>\"\n" +
            "    trace(s)\n" +
            "    trace(ys.size())\n" +
            "    trace(k.size())\n" +
            "    return m.isNone() ? w : w + 1\n" +
            "}"
        expectNoErrors(TyperTestSupport.snippet(src))
        val py = PyTestSupport.emit(src).python()
        assertTrue(py.contains("    m = x if c else None\n"), py)
        assertTrue(py.contains("    w = x if c else 0\n"), py)
        assertTrue(py.contains("    return w if ") && py.contains(" else _k_i64(w + 1)\n"), py)
    }

    @Test
    fun anIfWhoseValueIsOneExpressionPerArmIsRefusedOnceTheGateIsOn() {
        val value = "f: (c: Bool) Int32 {\n    x: Int32 = if c {\n        1\n    } else {\n        2\n    }\n    x\n}"
        val bodyTail = "f: (c: Bool, d: Bool) Int32 {\n    if c {\n        1\n    } else if d {\n        2\n    } else {\n        3\n    }\n}"
        val statements = "f: (c: Bool) Int32 {\n    x: Int32 = if c {\n        y: Int32 = 1\n        y\n    } else {\n        2\n    }\n    x\n}"
        val returns = "f: (c: Bool) Int32 {\n    if c {\n        return 1\n    } else {\n        return 2\n    }\n}"
        val armTail = "f: (c: Bool, d: Bool) Int32 {\n    x: Int32 = if c {\n        y: Int32 = 1\n        if d {\n            y\n        } else {\n            2\n        }\n    } else {\n        3\n    }\n    x\n}"
        DialectGates.ternaryOnly(true) {
            assertEquals("parse.if.ternary", parseError(value).tag)
            assertTrue(parseError(value).message.contains("c ? a : b"))
            assertEquals("parse.if.ternary", parseError(bodyTail).tag)
            assertEquals("parse.if.ternary", parseError(armTail).tag)
            ast(statements)
            ast(returns)
            ast("f: (c: Bool) Int32 {\n    x: Int32 = c ? 1 : 2\n    x\n}")
            // An arm with a block of its own (a lambda, a `with`) is no inline expression: `if` stays.
            ast("f: (c: Bool) Int32 {\n    g: (n: Int32) Int32 = if c {\n        (n: Int32) Int32 {\n            n\n        }\n    } else {\n        (n: Int32) Int32 {\n            n + 1\n        }\n    }\n    g(1)\n}")
            ast("P: Struct {\n    pub x: Int32 = 0\n}\n\nf: (c: Bool, q: P) P {\n    if c {\n        q with {\n            x = 1\n        }\n    } else {\n        q\n    }\n}")
            // A throw is no expression either.
            ast("f: (c: Bool) Int32 {\n    x: Int32 = if c {\n        1\n    } else {\n        throw \"no\"\n    }\n    x\n}")
        }
        // Off until the corpus and bibo are converted: the old spelling still parses.
        ast(value)
        ast(bodyTail)
        ast(armTail)
    }

    // ---- py ----------------------------------------------------------------------------------

    @Test
    fun aReturnAtTheEndAndTheBareLastExpressionGiveTheSamePython() {
        val explicit = """
            h: (s: Str) Int32 {
                if s == "x" {
                    throw "bad"
                }
                return s.size() as Int32
            }

            pick: (x: Int32) Str {
                if x < 0 {
                    return "neg"
                } else if x == 0 {
                    z: Str = "zero"
                    return z
                } else {
                    return "pos"
                }
            }

            safe: (s: Str) Int32 {
                try {
                    return h(s)
                } on e: Str {
                    return -1
                }
            }

            twice: (x: Int32) Int32 {
                if x > 100 {
                    return 100
                }
                f: (n: Int32) Int32 = (n: Int32) Int32 {
                    return n * 2
                }
                return f(x)
            }
        """.trimIndent()
        // Every `return` that ends a block dropped; the guard's early return stays.
        val bare = explicit.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("return ") && !line.contains("return 100")) line.replaceFirst("return ", "") else line
        }
        assertEquals(8, Regex("return ").findAll(explicit).count() - Regex("return ").findAll(bare).count(), bare)
        assertEquals(PyTestSupport.emit(explicit).python(), PyTestSupport.emit(bare).python())
        // And the same C++.
        val cpp = { text: String -> DeclTestSupport.emit(DeclTestSupport.module("app:main", text)).source("app:main") }
        assertEquals(cpp(explicit), cpp(bare))
    }

    @Test
    fun onPyAnIfWithStatementArmsIsADefCalledInPlace() {
        val py = PyTestSupport.emit(
            "note: () Int32 {\n    1\n}\n\n" +
                "f: (c: Bool) Int32 {\n    mut total: Int32 = 0\n    v: Int32 = if c {\n        total = note()\n        total * 10\n    } else {\n        -1\n    }\n    v + total\n}",
        ).python()
        assertTrue(py.contains("    def _k_t0():\n        nonlocal total\n        if c:\n            total = _note()\n"), py)
        assertTrue(py.contains("        else:\n            return -1\n    v = _k_t0()\n"), py)
    }

    @Test
    fun onPyATryValueIsADefWhoseHandlerReturnsItsValue() {
        val py = PyTestSupport.emit(
            "h: (s: Str) Int32 {\n    1\n}\n\nf: (s: Str) Int32 {\n    n: Int32 = try {\n        h(s)\n    } on e: Str {\n        0\n    }\n    n\n}",
        ).python()
        assertTrue(py.contains("    def _k_t0():\n        try:\n            return _h(s)\n        except _k_Error as _k_t1:\n            e = _k_t1.args[0]\n            return 0\n    n = _k_t0()\n"), py)
    }

    @Test
    fun aParenthesizedTryValueEndsABodyAsItsReturnDoes() {
        val h = "h: (s: Str) Int32 {\n    if s == \"\" {\n        throw \"empty\"\n    }\n    s.size() as Int32\n}\n\n"
        val tried = "try {\n        h(s)\n    } on e: Str {\n        -1\n    }"
        val bare = "${h}f: (s: Str) Int32 {\n    ($tried)\n}"
        val explicit = "${h}f: (s: Str) Int32 {\n    ($tried)\n}"
        assertTrue(assertIs<TryExpr>(tail(body(bare).last()).expr).isValue, "the try value is the result whole")
        expectNoErrors(TyperTestSupport.snippet(bare))
        val py = PyTestSupport.emit(bare).python()
        assertTrue(py.contains("            return -1\n    return _k_t0()\n"), py)
        assertEquals(PyTestSupport.emit(explicit).python(), py)
    }

    @Test
    fun aTryValueInAMaybeSlotTakesNullFromItsHandler() {
        val src = "h: (s: Str) Int32 {\n    1\n}\n\n" +
            "f: (s: Str) Bool {\n    m: Maybe<Int32> = try {\n        h(s)\n    } on e: Str {\n        null\n    }\n    m.isSome()\n}"
        expectNoErrors(TyperTestSupport.snippet(src))
        val py = PyTestSupport.emit(src).python()
        assertTrue(py.contains("            return _h(s)\n") && py.contains("            return None\n    m = _k_t0()\n"), py)
    }

    @Test
    fun onPyALoopVariableInAnArmIsTheDefsOwn() {
        val py = PyTestSupport.emit(
            "f: (c: Bool) Int32 {\n    v: Int32 = if c {\n        mut s: Int32 = 0\n        for i: Int32 in 0..3 {\n            s += i\n        }\n        s\n    } else {\n        0\n    }\n    v\n}",
        ).python()
        assertFalse(py.contains("nonlocal"), py)
    }

    @Test
    fun onPyAnElseIfWhoseConditionNeedsLinesNestsUnderElse() {
        val py = PyTestSupport.emit(
            "note: () Int32 {\n    1\n}\n\n" +
                "f: (a: Bool, b: Bool) Int32 {\n    v: Int32 = if a {\n        note()\n        1\n    } else if (if b {\n        note()\n        true\n    } else {\n        false\n    }) {\n        note()\n        2\n    } else {\n        3\n    }\n    v\n}",
        ).python()
        assertTrue(py.contains("        else:\n            def _k_t1():\n"), py)
        assertFalse(py.contains("elif"), py)
    }

    @Test
    fun onPyAJumpOutOfAValueArmIsRefused() {
        val returns = PyTestSupport.emit("f: (c: Bool, d: Bool) Int32 {\n    v: Int32 = if c {\n        if d {\n            return 1\n        }\n        2\n    } else {\n        3\n    }\n    v\n}")
        assertTrue(returns.errors.any { it.contains("a return inside an arm of an if or try used as a value") }, returns.errors.joinToString("\n"))
        val breaks = PyTestSupport.emit(
            "f: (d: Bool) Int32 {\n    mut s: Int32 = 0\n    for i: Int32 in 0..3 {\n        s += try {\n            if d {\n                break\n            }\n            i\n        } on e: Str {\n            0\n        }\n    }\n    s\n}",
        )
        assertTrue(breaks.errors.any { it.contains("a break inside an arm of an if or try used as a value") }, breaks.errors.joinToString("\n"))
        // A loop inside the arm keeps its own break.
        val inner = PyTestSupport.emit("f: (c: Bool) Int32 {\n    v: Int32 = if c {\n        mut n: Int32 = 0\n        while true {\n            n += 1\n            if n > 2 {\n                break\n            }\n        }\n        n\n    } else {\n        0\n    }\n    v\n}")
        assertTrue(inner.errors.isEmpty(), inner.errors.joinToString("\n"))
        // As the arm's last statement a return is no value: the typer says so first.
        expectDiagnostic(TyperTestSupport.snippet("f: (c: Bool) Int32 {\n    v: Int32 = if c {\n        return 1\n    } else {\n        2\n    }\n    v\n}"), "types.if.branch-value")
    }

    @Test
    fun aWithInAStatementArmRunsInItsTurnAndOneInATernaryArmIsRefused() {
        val p = "P: Struct {\n    pub x: Int32 = 0\n}\n\n"
        val ok = "${p}f: (c: Bool, q: P) Int32 {\n    r: P = if c {\n        t: P = q with {\n            x = 5\n        }\n        t\n    } else {\n        q\n    }\n    r.x\n}"
        expectNoErrors(TyperTestSupport.snippet(ok))
        val okPy = PyTestSupport.emit(ok).python()
        assertTrue(okPy.contains("        if c:\n            _k_w0 = q._k_clone()\n            _k_w0.x = 5\n"), okPy)
        // Each arm returns a copy, so the def's value is stored as it is.
        assertTrue(okPy.contains("            return t._k_clone()\n") && okPy.contains("    r = _k_t0()\n"), okPy)
        val bad = TyperTestSupport.snippet("${p}f: (c: Bool, q: P) Int32 {\n    r: P = c ? q with {\n        x = 5\n    } : q\n    r.x\n}")
        assertTrue(expectDiagnostic(bad, "types.with.position").message.contains("a branch of an if-expression"))
    }

    // ---- C++ ---------------------------------------------------------------------------------

    @Test
    fun onCppTheTernaryIsCsAndATryValueIsRefused() {
        val ok = DeclTestSupport.emit(DeclTestSupport.module("app:main", "f: (c: Bool, n: Int32) Int32 {\n    c ? n + 1 : n\n}"))
        assertTrue((ok.source("app:main") ?: "").contains("return c ? n + 1 : n;"), ok.source("app:main"))
        val tried = DeclTestSupport.emit(DeclTestSupport.module("app:main", "f: (s: Str) Int32 {\n    n: Int32 = try {\n        1\n    } on e: Str {\n        0\n    }\n    n\n}"))
        assertTrue(tried.diagnostics("app:main").any { it.code == "cpp.unsupported" && it.message.contains("a try used as a value") }, tried.render(tried.diagnostics("app:main")))
    }

    @Test
    fun aNeverTailIsTheStatementItWasWritten() {
        val src = "kira.os as os\n\nf: (x: Int32) Int32 {\n    if x > 0 {\n        return x\n    }\n    os.exit(2)\n}"
        val cpp = DeclTestSupport.emit(DeclTestSupport.module("app:main", src)).source("app:main") ?: ""
        assertTrue(cpp.contains("kira::os::exit(2);") && !cpp.contains("return kira::os::exit"), cpp)
        val py = PyTestSupport.emit(src).python()
        assertTrue(py.contains("    _k_exit(2)\n") && !py.contains("return _k_exit"), py)
    }
}
