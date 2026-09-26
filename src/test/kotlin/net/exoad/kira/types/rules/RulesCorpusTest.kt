package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.types.TyperTestSupport
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
            listOf("effects", "escape", "mutability", "exclusivity", "return", "visibility", "profile", "const", "generics", "naming"),
            KiraTyper.rulePasses.map { it.name },
        )
    }

    @TestFactory
    fun everyGoldenCaseTypesStrictWithZeroErrorsAfterTheRulePasses(): List<DynamicTest> {
        val cases = corpus.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }?.sortedBy { it.name }.orEmpty()
        assertTrue(cases.isNotEmpty(), "no golden cases under $corpus")
        return cases.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val program = TyperTestSupport.project(dir, TyperMode.STRICT)
                assertEquals(0, program.errors.size, "${dir.name}:\n${TyperTestSupport.render(program)}")
                assertTrue(program.diagnostics.none { it.code.startsWith("rules.") }, "${dir.name}:\n${TyperTestSupport.render(program)}")
            }
        }
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
        // Pure: reads only. Impure: a write through a MutView or to a mut parameter writes the caller's
        // storage (writeU32, readHeader), and whoever calls such a function is impure too (command and
        // the frames built on it); eachPacket calls through an Fx value.
        val p = TyperTestSupport.project(File(corpus, "unilidar"), TyperMode.STRICT)
        val m = p.module("pilot:unilidar")!!
        val pure = setOf("crc32", "readU32", "tailClosed", "packetAt")
        for (fn in m.declarations.filterIsInstance<FnSymbol>()) {
            assertEquals(if (fn.name in pure) Effect.PURE else Effect.IMPURE, p.model.effect(fn), fn.name)
        }
        assertFalse(p.model.fxEscapes(RulesTestSupport.fn(p, "eachPacket", "pilot:unilidar").params[1]))
    }

    @Test
    fun chainAndSenderKeepThisInside() {
        val chain = TyperTestSupport.project(File(corpus, "chain"), TyperMode.STRICT)
        assertFalse(RulesTestSupport.cls(chain, "Chain", "pilot:chain").thisEscapes)
        val sender = TyperTestSupport.project(File(corpus, "sender"), TyperMode.STRICT)
        assertFalse(RulesTestSupport.cls(sender, "Sender", "pilot:carrules").thisEscapes)
        assertEquals(Effect.IMPURE, sender.model.effect(RulesTestSupport.method(sender, "Sender", "send", "pilot:carrules")))
        assertEquals(Effect.PURE, sender.model.effect(RulesTestSupport.method(sender, "Sender", "sentMs", "pilot:carrules")))
    }
}
