package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.decls.DeclTestSupport
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a lowered body must say about an extern call and an extern constant, once bodies
 * lower.
 *
 * The expression part (W2.3, on its own branch) lowers a function's body and makes the text
 * of every call in it; CppExternEmitter (this package) spells an extern call and an extern
 * constant read. At the merge the expression part must hand those two to
 * [net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter.call] and
 * [net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter.constant]; its own
 * stand-ins, written before this package existed, spell `::` before a constant's name (a C
 * macro constant then reads `::42`) and keep a result's C++ type. Without the delegation
 * the CStr rules, the Str-result conversion and the macro read are dead code, and nothing
 * under `./gradlew test` or CI says so: examples/cpp/13-ffi-cpp/run.sh, which would, is not
 * run by either.
 *
 * So this test states what the lowered body contains. On a branch without the expression
 * part every body is `cpp.unsupported: the body ... is not lowered yet`, and the body checks
 * are skipped (an assumption, so they count as skipped, never as passed); the golden flag
 * check runs on both sides, since forward is `emit: required` exactly when bodies lower.
 */
class ExternDelegationTest {
    private val uri = "test:probe"

    private val probe = """
        @_extern(c = "PROBE_LIMIT", header = "probe.h")
        pub LIMIT: Int32;

        @_extern(cpp = "probe::name", header = "probe.hxx")
        pub fx name: () Str;

        @_extern(cpp = "probe::take", header = "probe.hxx")
        pub fx take: (s: CStr) Void;

        pub fx run: (label: Str) Int32 {
            take(label)
            if name() == "abc" {
                return LIMIT
            }
            return 0
        }
    """

    private fun emitted() = DeclTestSupport.emit(DeclTestSupport.module(uri, probe), options = CppOptions(lineDirectives = false))

    /** Whether this build lowers a function's body: the expression part is present. */
    private fun bodiesLower(): Boolean {
        val m = emitted().module(uri)
        return m.diagnostics.none { it.message.contains("is not lowered yet") }
    }

    @Test
    fun anExternCallAndConstantInABodyGoThroughCppExternEmitter() {
        Assumptions.assumeTrue(bodiesLower(), "no expression part on this branch: bodies are not lowered, so there is no body to check")
        val source = emitted().source(uri) ?: error("$uri emitted no source")
        assertTrue(source.contains("static_cast<kira::Str>(::probe::name())"), "a Str result is converted at the call:\n$source")
        assertTrue(source.contains("::probe::take(label.c_str())"), "a Str reaches a CStr parameter as .c_str():\n$source")
        assertTrue(source.contains("PROBE_LIMIT") && !source.contains("::PROBE_LIMIT"), "a C macro constant is read as the marker spells it:\n$source")
    }

    @Test
    fun theForwardGoldenIsRequiredExactlyWhenBodiesLower() {
        val yaml = File("src/test/resources/cpp-golden/forward/case.yaml").readText()
        val required = Regex("(?m)^emit:\\s*required\\s*$").containsMatchIn(yaml)
        assertEquals(
            bodiesLower(), required,
            if (required) "forward is emit: required, but bodies are not lowered on this build; flip it back to pending"
            else "bodies lower on this build, so forward must be emit: required (src/test/resources/cpp-golden/forward/case.yaml): its expected/ is byte for byte what W2.3 and W2.6 emit together",
        )
    }
}
