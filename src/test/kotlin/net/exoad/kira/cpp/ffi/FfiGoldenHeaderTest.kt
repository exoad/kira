package net.exoad.kira.cpp.ffi

import net.exoad.kira.cpp.CppGoldenEmitTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The extern modules of the golden corpus, byte for byte: forward's `pilot:car` (design
 * 7.2's example, checked against `driver/car.hxx` by goldens.sh and CppGoldenCompileTest)
 * and imgui-shape's `ui:imgui`. CppGoldenEmitTest covers every module of an `emit:
 * required` case; forward stays `pending` until the program body's part (W2.3) lands, so
 * its extern header is pinned here on its own.
 */
class FfiGoldenHeaderTest {
    private fun assertSame(case: String, uri: String) {
        val emission = FfiGoldenSupport.emit(case)
        val want = emission.expectedHeader(uri)
        val got = emission.header(uri)
        if (want != got) {
            fail("$case: $uri differs from expected/\n" + CppGoldenEmitTest.unifiedDiff(want.lines(), got.lines(), "expected", "emitted"))
        }
    }

    @Test
    fun forwardsCarModuleIsSection72Literally() {
        assertSame("forward", "pilot:car")
        val header = FfiGoldenSupport.emit("forward").header("pilot:car")
        // The shape 7.2 fixes: real headers, then kira/ffi.hxx, then one check per member.
        val lines = header.lines()
        val includes = lines.filter { it.startsWith("#include") }
        assertEquals(
            listOf("#include \"kira/rt.hxx\"", "#include \"car.hxx\"", "#include \"car.seam.hxx\"", "#include \"kira/ffi.hxx\"", "#include \"kira/macro_push.hxx\"", "#include \"kira/macro_pop.hxx\""),
            includes,
        )
        assertTrue("KIRA_EXTERN_CHECK(std::declval<const bibo::Car&>().ok(), bool, \"Car.ok\");" in lines, header)
        assertTrue("KIRA_EXTERN_CHECK((std::declval<bibo::Car&>().drive(std::declval<float>(), std::declval<float>()), 0), int, \"Car.drive\");" in lines, header)
        assertTrue("KIRA_EXTERN_CHECK(bibo::openCar(), kira::Rc<bibo::Car>, \"openCar\");" in lines, header)
        assertTrue(lines.none { it.contains("namespace bibo") }, "an extern module declares nothing of its own:\n$header")
    }

    @Test
    fun imguiShapesModuleMatchesExpected() {
        assertSame("imgui-shape", "ui:imgui")
    }
}
