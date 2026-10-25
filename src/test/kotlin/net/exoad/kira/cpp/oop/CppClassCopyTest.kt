package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classes under copy by default (50-round4, W2.4's half): a class body never guards. No
 * parameter is copied at entry (`vRef_`), no method holds itself (`keepAlive_`), and what stays
 * refused stays refused by the rules.
 */
class CppClassCopyTest {
    private val uri = "test:main"

    private fun typerErrors(body: String): List<String> = OopTestSupport.typerErrors(OopTestSupport.module(uri, body))

    private fun assertRefusedBy(code: String, body: String, what: String = code) {
        val errors = typerErrors(body)
        assertTrue(errors.any { it.contains("[$code]") }, "$what: expected $code, got:\n${errors.joinToString("\n")}")
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    // ---- constructions, and no object holding a view -----------------------------------------------------

    @Test
    fun aConstructionThatLeavesAFieldWithoutAValueIsRefused() {
        // A null kira::Rc or an empty kira::Fn never reaches C++: the typer wants every field with no value.
        assertRefusedBy(
            "types.init.missing-required",
            """
            pub Other: Any {
                pub k: Int32 = 3
            }

            pub X: Any {
                pub c: Other
                pub f: Fx<Tuple0, Int32>
                pub n: Int32
            }

            pub make: () X {
                X { n = 1 }
            }
            """,
        )
    }

    @Test
    fun anObjectHoldingAViewIsAnInternalErrorNeverAUserOne() {
        // refview's `Ref<View<Int32>> { value = makeList().view() }` is W2.5's ViewPass's (rules.view.type). Should one reach
        // the classes part, it is the checker's bug, reported as cpp.internal, and never lowered as it is.
        val program = """
            pub makeList: () List<Int32> {
                List<Int32> {}
            }

            pub boxed: () Int32 {
                Ref<View<Int32>> { value = makeList().view() }.value.get(0)
            }
        """
        val diagnostics = try {
            OopTestSupport.emit(OopTestSupport.module(uri, program)).module(uri).diagnostics.filter { it.isError }
        } catch (e: AssertionError) {
            if (e.message?.contains("the typer refused") == true && e.message!!.contains("rules.view.type")) {
                return
            }
            throw e
        }
        assertTrue(diagnostics.any { it.code == CppModuleEmitterFactory.INTERNAL_CODE && it.message.contains("the value of Ref<View<Int32>> holds a view or a pointer") }, OopTestSupport.render(diagnostics))
        assertTrue(diagnostics.none { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }, OopTestSupport.render(diagnostics))
    }

    // ---- 50-round4 7.3: no emitted file holds itself or copies at entry -------------------------------------

    @Test
    fun noGoldenHoldsItselfOrCopiesAtEntry() {
        // Every expected/ tree (what CppGoldenEmitTest compares byte for byte), and the oop goldens emitted here with
        // the standard parts.
        val patterns = listOf("Ref_ =", "keepAlive_", "weak_from_this().lock()")
        val files = File("src/test/resources").walkTopDown().filter { it.isFile && (it.name.endsWith(".kira.cxx") || it.name.endsWith(".kira.hxx")) }.toList()
        assertTrue(files.size > 30, "found only ${files.size} generated files")
        assertEquals(emptyList(), files.filter { f -> patterns.any { f.readText().contains(it) } }.map { it.path })
        for (name in listOf("chain", "sender", "classes")) {
            val emitted = OopTestSupport.emitCase(OopTestSupport.case(name), CppEmitParts.standard())
            assertTrue(emitted.any { it.relative.endsWith(".kira.cxx") } && emitted.any { it.relative.endsWith(".kira.hxx") }, "$name emitted ${emitted.map { it.relative }}")
            emitted.forEach { f -> assertLacks(f.text, *patterns.toTypedArray()) }
        }
    }
}
