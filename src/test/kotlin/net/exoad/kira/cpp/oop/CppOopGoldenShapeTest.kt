package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.cpp.CppGoldenEmitTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The goldens W2.4 answers for (chain, sender, classes) before W2.3's bodies exist: every
 * file the case's modules emit, with the real classes and generics parts and fake bodies,
 * equals `expected/` byte for byte once each function body is emptied on both sides
 * ([OopTestSupport.stripBodies]). So the forward declarations, class and trait bodies,
 * constructors and mem-initializers, prototypes, definition heads, their order and their
 * placement (header, `.kira.cxx`, its anonymous namespace) are checked now; the bodies are
 * CppGoldenEmitTest's once the cases are `emit: required`.
 *
 * `closures` is W2.3's case, but its `Counter` is a class: its part of the header (the
 * `kira::Shared` base an escaping lambda asks for, a template member declared in the class
 * and defined in the header) is checked the same way, with EscapePass's verdict on `apply`'s
 * `f` (W2.5, not merged here) written into the model the way that pass writes it.
 */
class CppOopGoldenShapeTest {
    private fun checkCase(name: String, tweak: (TypedProgram) -> Unit = {}) {
        val case = OopTestSupport.case(name)
        val files = OopTestSupport.emitCase(case, tweak = tweak)
        val expectedFiles = case.expectedDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(case.expectedDir).path.replace('\\', '/') }.sorted().toList()
        assertEquals(expectedFiles, files.map { it.relative }.sorted(), "$name: the emitted files are expected/'s")
        val problems = mutableListOf<String>()
        files.forEach { f ->
            val want = OopTestSupport.stripBodies(OopTestSupport.expected(case, f.relative))
            val got = OopTestSupport.stripBodies(f.text)
            if (want != got) {
                problems += "${f.relative} differs (bodies emptied)\n" +
                    CppGoldenEmitTest.unifiedDiff(want.lines(), got.lines(), "expected/${f.relative}", "emitted")
            }
        }
        if (problems.isNotEmpty()) {
            fail("$name: ${problems.size} file(s) differ\n\n" + problems.joinToString("\n\n"))
        }
    }

    @Test
    fun chainMatchesItsGoldenOutsideTheBodies() = checkCase("chain")

    @Test
    fun senderMatchesItsGoldenOutsideTheBodies() = checkCase("sender")

    @Test
    fun classesMatchesItsGoldenOutsideTheBodies() = checkCase("classes")

    @Test
    fun theClosuresCounterClassMatchesItsGolden() {
        val case = OopTestSupport.case("closures")
        val files = OopTestSupport.emitCase(case, tweak = { program -> nonEscapingApply(program) })
        val header = files.single { it.relative.endsWith(".kira.hxx") }
        val want = OopTestSupport.stripBodies(OopTestSupport.expected(case, header.relative))
        val got = OopTestSupport.stripBodies(header.text)
        val counterOf = { text: String -> text.substringAfter("  class Counter final").substringBefore("  };") }
        assertEquals(counterOf(want), counterOf(got), "the Counter class as closures.kira.hxx has it")
        val applyOf = { text: String -> text.substringAfter("  template<typename F_f>\n    requires kira::Callable<F_f, std::int32_t, std::int32_t>\n  std::int32_t Counter::apply") }
        assertEquals(applyOf(want), applyOf(got), "Counter::apply is defined in the header, after the free templates")
        val source = files.single { it.relative.endsWith(".kira.cxx") }
        val cxx = OopTestSupport.stripBodies(source.text)
        val wantCxx = OopTestSupport.stripBodies(OopTestSupport.expected(case, source.relative))
        val members = { text: String -> text.substringAfter("  Counter::Counter").substringBefore("  kira::Fn<std::int32_t()> tally()") }
        assertEquals(members(wantCxx), members(cxx), "Counter's out-of-line members in closures.kira.cxx, apply not among them")
    }

    /** EscapePass's verdict on `Counter.apply`'s `f` and `applyTo`'s `f`: only called, so neither escapes. */
    private fun nonEscapingApply(program: TypedProgram) {
        val m = program.modules.single { it.uri == "lang:closures" }
        m.declarations.forEach { sym ->
            val fns = when (sym) {
                is net.exoad.kira.compiler.analysis.types.FnSymbol -> listOf(sym)
                is net.exoad.kira.compiler.analysis.types.ClassSymbol -> sym.methods
                else -> emptyList()
            }
            fns.filter { it.name == "apply" || it.name == "applyTo" }.forEach { fn ->
                fn.params.filter { it.name == "f" }.forEach { program.model.fxEscapes[it] = false }
            }
        }
    }

    @Test
    fun stripBodiesKeepsHeadsAndClassBodies() {
        val text = listOf(
            "  class A final",
            "  {",
            "  public:",
            "      A() = default;",
            "  };",
            "",
            "  A::A(std::int32_t k_)",
            "      : k(k_)",
            "  {",
            "      x();",
            "  }",
        ).joinToString("\n")
        assertEquals(
            listOf("  class A final", "  {", "  public:", "      A() = default;", "  };", "", "  A::A(std::int32_t k_)", "      : k(k_)", "  {", "  }").joinToString("\n"),
            OopTestSupport.stripBodies(text),
        )
        // the cases this test reads exist where it looks
        listOf("chain", "sender", "classes", "closures").forEach { assert(File(OopTestSupport.goldenRoot, it).isDirectory) { it } }
    }
}
