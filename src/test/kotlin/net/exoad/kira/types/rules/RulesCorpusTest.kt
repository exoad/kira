package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.body.BodyTestSupport
import org.yaml.snakeyaml.Yaml
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule passes over the C++ golden corpus: every case types STRICT with 0 errors once the
 * passes run, and the facts the emitter reads (purity, Fx and view escapes, `this` escapes)
 * come out as the goldens' expected C++ assumes.
 */
class RulesCorpusTest {
    private val corpus = File("src/test/resources/cpp-golden")

    @Test
    fun everyRulePassIsRegistered() {
        assertEquals(
            listOf("lent", "effects", "escape", "view", "mutability", "exclusivity", "return", "visibility", "profile", "const", "generics", "naming"),
            KiraTyper.rulePasses.map { it.name },
        )
    }

    @TestFactory
    fun everyGoldenCaseTypesStrictWithZeroErrorsAfterTheRulePasses(): List<DynamicTest> {
        val cases = corpus.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }?.sortedBy { it.name }.orEmpty()
        assertTrue(cases.isNotEmpty(), "no golden cases under $corpus")
        return cases.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val program = TyperTestSupport.project(dir, TyperMode.STRICT, options(dir))
                assertEquals(0, program.errors.size, "${dir.name}:\n${TyperTestSupport.render(program)}")
                // Warnings are allowed: NamingPass warns on names a golden may use on purpose (decls renames a parameter named LIMIT).
                assertTrue(program.diagnostics.none { it.code.startsWith("rules.") && it.isError }, "${dir.name}:\n${TyperTestSupport.render(program)}")
            }
        }
    }

    /** The typer options the case's `kira.yaml` asks for: its `build.cpp.freestanding` globs (hall, text), so ProfilePass runs as the CLI would run it. */
    private fun options(dir: File): TyperOptions {
        val manifest = File(dir, "kira.yaml").takeIf { it.isFile } ?: return TyperOptions()
        val yaml = Yaml().load<Any>(manifest.readText()) as? Map<*, *> ?: return TyperOptions()
        val cpp = (yaml["build"] as? Map<*, *>)?.get("cpp") as? Map<*, *> ?: return TyperOptions()
        fun globs(key: String): List<String> = (cpp[key] as? List<*>)?.map { it.toString() } ?: emptyList()
        return TyperOptions(freestanding = globs("freestanding"), headerOnly = globs("headerOnly"))
    }

    @Test
    fun hallAndTextAreTypedFreestanding() {
        // The corpus test reads each case's kira.yaml; these two declare freestanding modules, so ProfilePass runs on them.
        assertEquals(listOf("pico:hall"), options(File(corpus, "hall")).freestanding)
        assertEquals(listOf("lib:text"), options(File(corpus, "text")).freestanding)
    }

    @Test
    fun closuresCounterSharesThisAndApplyToIsATemplate() {
        val p = TyperTestSupport.project(File(corpus, "closures"), TyperMode.STRICT)
        assertTrue(RulesTestSupport.cls(p, "Counter", "lang:closures").thisEscapes, "Counter.multiplier returns a lambda over this (kira::Shared<Counter>)")
        assertFalse(RulesTestSupport.cls(p, "Gain", "lang:closures").thisEscapes)
        assertFalse(p.model.fxEscapes(RulesTestSupport.fn(p, "applyTo", "lang:closures").params[0]))
        assertFalse(p.model.fxEscapes(RulesTestSupport.method(p, "Counter", "apply", "lang:closures").params[0]))
    }

    @Test
    fun unilidarPurityAndEachPacketIsATemplate() {
        // D33 over the golden: crc32, readU32, tailClosed and packetAt only read the storage their View borrows,
        // so they are READS, and `(p[0] as UInt32) | ((p[1] as UInt32) << 8)` (two READS) and
        // `(out.size as Size) == buf.size()` (two READS) are emitted bare, as the expected C++ has them.
        // writeU32 and readHeader write through a MutView or to a mut parameter, command and the frames built
        // on it call writeU32, and eachPacket calls through an Fx value: IMPURE.
        val p = TyperTestSupport.project(File(corpus, "unilidar"), TyperMode.STRICT)
        val m = p.module("pilot:unilidar")!!
        val fns = m.declarations.filterIsInstance<FnSymbol>().associateBy { it.name }
        assertTrue(fns.size >= 12, fns.keys.toString())
        val reads = setOf("crc32", "readU32", "tailClosed", "packetAt")
        for ((name, fn) in fns) {
            assertEquals(if (name in reads) Effect.READS else Effect.IMPURE, p.model.effect(fn), name)
        }
        val model = p.model
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "p[0] as UInt32 | (p[1] as UInt32) << 8", "pilot:unilidar")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "out.size as Size == buf.size()", "pilot:unilidar")))
        assertFalse(p.model.fxEscapes(RulesTestSupport.fn(p, "eachPacket", "pilot:unilidar").params[1]))
    }

    @TestFactory
    fun noGoldenOperandGroupNeedsASpill(): List<DynamicTest> {
        // The expected C++ of every case has no R19 spill, so no call, operator, compound assignment, assignment
        // index, class construction or interpolation may have an IMPURE operand beside a non-PURE sibling. The two
        // known exceptions are classes' `"${'$'}{name} says ${'$'}{sound()}"` (a class field READS beside a virtual
        // call) and chain's `b.id() == wanted` (a trait call beside a `const kira::Str&` parameter, READS since
        // round 3's issue 9), which W2.4's and W1.3's goldens emit bare; they are listed here so a change to either
        // side is noticed. The integrator picks the emitter's spill rule (round 3, issue 8). A golden whose case.yaml
        // says `pins: spills` exists to pin the spills (W2.3's evalorder, 40-round3 4.1) and is exempt.
        val cases = corpus.listFiles { f -> f.isDirectory && File(f, "src").isDirectory && !pinsSpills(f) }?.sortedBy { it.name }.orEmpty()
        val known = mapOf(
            "classes" to listOf("\"${'$'}{name} says ${'$'}{sound()}\""),
            "chain" to listOf("b.id() == wanted"),
        )
        return cases.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val p = TyperTestSupport.project(dir, TyperMode.STRICT, options(dir))
                assertEquals(known[dir.name].orEmpty(), spillSites(p), dir.name)
            }
        }
    }

    /** The case's `case.yaml` says `pins: spills` (a scalar or a list holding `spills`). */
    private fun pinsSpills(dir: File): Boolean {
        val manifest = File(dir, "case.yaml").takeIf { it.isFile } ?: return false
        val pins = (Yaml().load<Any>(manifest.readText()) as? Map<*, *>)?.get("pins") ?: return false
        return pins == "spills" || (pins as? List<*>)?.contains("spills") == true
    }

    @Test
    fun aGoldenThatPinsItsSpillsIsExemptFromTheNoSpillCheck() {
        val dir = kotlin.io.path.createTempDirectory("pins").toFile()
        try {
            File(dir, "case.yaml").writeText("toolchains: [gcc]\npins: spills\n")
            assertTrue(pinsSpills(dir))
            File(dir, "case.yaml").writeText("toolchains: [gcc]\npins: [spills, order]\n")
            assertTrue(pinsSpills(dir))
            File(dir, "case.yaml").writeText("toolchains: [gcc]\n")
            assertFalse(pinsSpills(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The source text of every operand group that R19 would spill under the three-valued rule. */
    private fun spillSites(p: TypedProgram): List<String> {
        val model = p.model
        val out = mutableListOf<String>()
        fun group(e: Expr, ops: List<Expr>) {
            val effs = ops.map { model.effect(it) }
            if (effs.any { it == Effect.IMPURE } && effs.count { it != Effect.PURE } >= 2) {
                out.add(KiraUnparser.text(e))
            }
        }
        fun walk(n: ASTNode) {
            when (n) {
                is FunctionCallExpr -> model.calls[n]?.let { rc -> group(n, listOfNotNull(rc.receiver) + rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }) }
                is BinaryExpr -> if (n.operator != BinaryOp.AND && n.operator != BinaryOp.OR) group(n, listOf(n.leftExpr, n.rightExpr))
                is CompoundAssignmentExpr -> group(n, listOf(n.left, n.right))
                is PlaceAssignmentExpr -> group(n, AstTree.children(n.target).filterIsInstance<Expr>().filter { it !is Type } + n.value)
                is ObjectInitExpr -> model.inits[n]?.let { ri -> if (ri.cls?.kind == ClassKind.CLASS) group(n, n.positionalArgs + n.namedArgs.map { it.value }) }
                is InterpolatedStringLiteral -> group(n, n.parts.filterIsInstance<InterpolationPart.Hole>().map { it.expr })
                else -> {}
            }
            AstTree.children(n).forEach { walk(it) }
        }
        for (m in p.modules) {
            if (!m.isStdlib) {
                walk(m.source.ast)
            }
        }
        return out
    }

    @Test
    fun chainAndSenderKeepThisInside() {
        val chain = TyperTestSupport.project(File(corpus, "chain"), TyperMode.STRICT)
        assertFalse(RulesTestSupport.cls(chain, "Chain", "pilot:chain").thisEscapes)
        val sender = TyperTestSupport.project(File(corpus, "sender"), TyperMode.STRICT)
        assertFalse(RulesTestSupport.cls(sender, "Sender", "pilot:carrules").thisEscapes)
        assertEquals(Effect.IMPURE, sender.model.effect(RulesTestSupport.method(sender, "Sender", "send", "pilot:carrules")))
        // A class method reads its fields through a reference a sibling operand could write (D33): READS.
        assertEquals(Effect.READS, sender.model.effect(RulesTestSupport.method(sender, "Sender", "sentMs", "pilot:carrules")))
    }
}
