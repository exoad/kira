package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.cpp.decls.DeclTestSupport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CppExternEmitter over small modules (design 7.2): the checks an extern declaration
 * states, the includes it needs, what it refuses, and the text of a call or a constant
 * read that the expression part asks it for.
 */
class CppExternEmitterTest {
    private fun emit(body: String, uri: String = "test:ext", options: CppOptions = CppOptions(lineDirectives = false)): Pair<EmittedModule, CppEmitContextImpl> =
        DeclTestSupport.emitWith(DeclTestSupport.module(uri, body), uri = uri, options = options)

    private fun header(body: String, uri: String = "test:ext", options: CppOptions = CppOptions(lineDirectives = false)): String {
        val (emitted, _) = emit(body, uri, options)
        val errors = emitted.diagnostics.filter { it.isError }
        if (errors.isNotEmpty()) {
            fail("errors:\n" + errors.joinToString("\n") { it.render() } + "\n--- header ---\n" + emitted.header)
        }
        return CppWriter.normalize(emitted.header)
    }

    private fun errorsOf(body: String): List<String> = emit(body).first.diagnostics.filter { it.isError }.map { it.render() }

    private fun assertLines(header: String, vararg wanted: String) {
        val lines = header.lines()
        wanted.forEach { assertTrue(it in lines, "expected the line:\n$it\nin:\n$header") }
    }

    // ---- checks ------------------------------------------------------------------------------

    @Test
    fun aFreeFunctionIsCheckedWithTheProxiesItsCallWouldPass() {
        val h = header(
            """
            @_extern(cpp = "ImGui::SliderFloat", header = "imgui.h")
            pub fx sliderFloat: (label: Str, mut v: Float32, lo: Float32, hi: Float32) Bool;

            @_extern(cpp = "ImGui::Text", header = "imgui.h")
            pub fx text: (s: CStr) Void;

            @_extern("bare_c_name")
            pub fx bare: (p: Unsafe<UInt8>, n: Size) Int64;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK(ImGui::SliderFloat(kira::ffi::in(std::declval<const kira::Str&>()), kira::ffi::out(std::declval<float&>()), std::declval<float>(), std::declval<float>()), bool, \"sliderFloat\");",
            "KIRA_EXTERN_CHECK((ImGui::Text(std::declval<const char*>()), 0), int, \"text\");",
            "KIRA_EXTERN_CHECK(bare_c_name(std::declval<const std::uint8_t*>(), std::declval<kira::Size>()), std::int64_t, \"bare\");",
        )
        val includes = h.lines().filter { it.startsWith("#include") }
        assertEquals(listOf("#include \"kira/rt.hxx\"", "#include \"imgui.h\"", "#include \"kira/ffi.hxx\"", "#include \"kira/macro_push.hxx\"", "#include \"kira/macro_pop.hxx\""), includes)
    }

    @Test
    fun aClassIsCheckedThroughItsReceiverAndAMethodMayNameItself() {
        val h = header(
            """
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx arm: () Bool;
                pub fx ok: () Bool;
                @_extern(cpp = "Finish", header = "car.extra.hxx") pub mut fx finish: () Int32;
            }

            @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h")
            pub class DrawList {
                @_extern(cpp = "AddLine") pub mut fx addLine: (color: UInt32) Void;
            }

            @_extern(cpp = "ImGui::GetWindowDrawList", header = "imgui.h")
            pub fx drawList: () DrawList;

            @_extern(cpp = "bibo::openCar", header = "car.hxx")
            pub fx openCar: () Car;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK(std::declval<bibo::Car&>().arm(), bool, \"Car.arm\");",
            "KIRA_EXTERN_CHECK(std::declval<const bibo::Car&>().ok(), bool, \"Car.ok\");",
            "KIRA_EXTERN_CHECK(std::declval<bibo::Car&>().Finish(), std::int32_t, \"Car.finish\");",
            "KIRA_EXTERN_CHECK((std::declval<ImDrawList&>().AddLine(std::declval<std::uint32_t>()), 0), int, \"DrawList.addLine\");",
            "KIRA_EXTERN_CHECK(ImGui::GetWindowDrawList(), ImDrawList*, \"drawList\");",
            "KIRA_EXTERN_CHECK(bibo::openCar(), kira::Rc<bibo::Car>, \"openCar\");",
        )
        // A method's own header is included too, each header once, in declaration order.
        val includes = h.lines().filter { it.startsWith("#include") }
        assertEquals(listOf("#include \"kira/rt.hxx\"", "#include \"car.hxx\"", "#include \"car.extra.hxx\"", "#include \"imgui.h\"", "#include \"kira/ffi.hxx\"", "#include \"kira/macro_push.hxx\"", "#include \"kira/macro_pop.hxx\""), includes)
    }

    @Test
    fun aStructWithFieldsGetsALayoutTwinAndASizeofCheck() {
        val h = header(
            """
            @_extern(cpp = "ImVec2", header = "imgui.h")
            pub struct Vec2 {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }

            @_extern(cpp = "bibo::Scan", header = "car.hxx")
            pub struct Scan {
                pub fx ahead: () Float32;
            }
            """,
            uri = "pilot:ui",
        )
        assertLines(
            h,
            "namespace ui::ffi_ { struct Vec2 { float x; float y; }; }",
            "static_assert(sizeof(ImVec2) == sizeof(ui::ffi_::Vec2), \"Kira's Vec2 no longer matches its C++ header\");",
            "KIRA_EXTERN_CHECK(std::declval<ImVec2&>().x, float, \"Vec2.x\");",
            "KIRA_EXTERN_CHECK(std::declval<ImVec2&>().y, float, \"Vec2.y\");",
            "KIRA_EXTERN_CHECK(std::declval<const bibo::Scan&>().ahead(), float, \"Scan.ahead\");",
        )
        assertTrue(h.lines().none { it.startsWith("static_assert(sizeof(bibo::Scan)") }, "a struct without fields has no sizeof check:\n$h")
        assertTrue(h.lines().none { it.trim().startsWith("struct Vec2") && !it.contains("ffi_") }, "an extern struct is never declared:\n$h")
    }

    @Test
    fun anExternConstantIsCheckedAgainstItsType() {
        val h = header(
            """
            @_extern(cpp = "ImGuiWindowFlags_NoTitleBar", header = "imgui.h")
            pub NO_TITLE_BAR: Int32;

            @_extern(cpp = "bibo::VERSION", header = "car.hxx")
            pub VERSION: Str;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK(ImGuiWindowFlags_NoTitleBar, std::int32_t, \"NO_TITLE_BAR\");",
            "KIRA_EXTERN_CHECK(bibo::VERSION, kira::Str, \"VERSION\");",
        )
        assertTrue(h.lines().none { it.contains("NO_TITLE_BAR =") }, "an extern constant is never defined:\n$h")
    }

    @Test
    fun namesAreSpelledAsTheMarkerWroteThemNeverFromTheGlobalScope() {
        val h = header(
            """
            @_extern(cpp = "::bibo::Car", header = "car.hxx")
            pub class Car {
                pub fx ok: () Bool;
            }
            @_extern(cpp = "bibo::openCar", header = "car.hxx")
            pub fx openCar: () Car;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK(std::declval<const ::bibo::Car&>().ok(), bool, \"Car.ok\");",
            "KIRA_EXTERN_CHECK(bibo::openCar(), kira::Rc<bibo::Car>, \"openCar\");",
        )
    }

    // ---- refusals ---------------------------------------------------------------------------

    @Test
    fun whatSection72DoesNotCoverIsRefusedAtTheDeclaration() {
        val errors = errorsOf(
            """
            @_extern(cpp = "bibo::Box", header = "box.hxx")
            pub class Box<T> {
                pub fx get: () T;
            }

            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub speed: Float32 = 0.0
            }

            @_extern(cpp = "bibo::counter", header = "car.hxx")
            pub mut COUNTER: Int32 = 0

            @_extern(cpp = "bibo::LIMIT", header = "car.hxx")
            pub LIMIT: Int32 = 5

            @_extern(cpp = "std::sqrt", header = "<cmath>")
            pub fx sqrt: (x: Float64) Float64;
            """
        )
        assertTrue(errors.any { it.contains("the generic extern class 'Box'") }, errors.toString())
        assertTrue(errors.any { it.contains("the system header <cmath> of 'sqrt'") }, errors.toString())
        assertTrue(errors.any { it.contains("the fields of the extern class 'Car'") }, errors.toString())
        assertTrue(errors.any { it.contains("the extern mut variable 'COUNTER'") }, errors.toString())
        assertTrue(errors.any { it.contains("the extern constant 'LIMIT' takes its value from C++") }, errors.toString())
    }

    // ---- calls and constants: what the expression part asks for ---------------------------------

    private class Calls(val ctx: CppEmitContextImpl) {
        fun of(name: String): ResolvedCall = ctx.model.calls.values.firstOrNull { it.fn?.name == name } ?: fail("no call of '$name' in the model")
        fun text(name: String, receiver: String?, vararg args: String): String = CppExternEmitter.call(ctx, of(name), receiver, args.toList())
    }

    private fun callsOf(body: String): Calls {
        val (_, ctx) = emit(body)
        return Calls(ctx)
    }

    @Test
    fun aCallSpellsTheCppNameAndTheProxies() {
        val c = callsOf(
            """
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx arm: () Bool;
                @_extern(cpp = "Drive") pub mut fx drive: (throttle: Float32, steer: Float32) Void;
            }

            @_extern(cpp = "bibo::Scan", header = "car.hxx")
            pub struct Scan {
                pub fx ahead: () Float32;
            }

            @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h")
            pub class DrawList {
                @_extern(cpp = "AddLine") pub mut fx addLine: (color: UInt32) Void;
            }

            @_extern(cpp = "bibo::openCar", header = "car.hxx")
            pub fx openCar: () Car;

            @_extern(cpp = "bibo::scanOf", header = "car.hxx")
            pub fx scanOf: (car: Car) Scan;

            @_extern(cpp = "ImGui::SliderFloat", header = "imgui.h")
            pub fx sliderFloat: (label: Str, mut v: Float32, lo: Float32, hi: Float32) Bool;

            @_extern(cpp = "ImGui::GetWindowDrawList", header = "imgui.h")
            pub fx drawList: () DrawList;

            @_extern("c_hypot")
            pub fx hypot: (a: Int32, b: Int32) Int32;

            fx main: () Int32 {
                car: Car = openCar()
                armed: Bool = car.arm()
                car.drive(0.1, 0.0)
                ahead: Float32 = scanOf(car).ahead()
                mut v: Float32 = 0.0
                moved: Bool = sliderFloat("throttle", mut v, 0.0, 1.0)
                drawList().addLine(7)
                return hypot(3, 4)
            }
            """
        )
        assertEquals("::bibo::openCar()", c.text("openCar", null))
        assertEquals("car->arm()", c.text("arm", "car"))
        assertEquals("car->Drive(0.1f, 0.0f)", c.text("drive", "car", "0.1f", "0.0f"))
        assertEquals("::bibo::scanOf(car).ahead()", c.text("ahead", "::bibo::scanOf(car)"))
        assertEquals("::ImGui::SliderFloat(kira::ffi::in(\"throttle\"), kira::ffi::out(v), 0.0f, 1.0f)", c.text("sliderFloat", null, "\"throttle\"", "v", "0.0f", "1.0f"))
        assertEquals("::ImGui::GetWindowDrawList()->AddLine(7u)", c.text("addLine", "::ImGui::GetWindowDrawList()", "7u"))
        assertEquals("::c_hypot(3, 4)", c.text("hypot", null, "3", "4"))
        assertTrue(c.ctx.model.calls.values.filter { it.fn?.name == "arm" }.all { CppExternEmitter.isExternCall(it) })
    }

    @Test
    fun aCStrParameterTakesAStrAsSection72Says() {
        // The typer refuses a Str where a CStr is expected today (types.assign.mismatch), so the
        // CStr rule is exercised on the emitter alone: a CStr-typed argument passes through.
        val c = callsOf(
            """
            @_extern(cpp = "ImGui::Text", header = "imgui.h")
            pub fx text: (s: CStr) Void;

            @_extern(cpp = "bibo::version", header = "car.hxx")
            pub fx version: () CStr;

            fx main: () Void {
                text(version())
            }
            """
        )
        assertEquals("::ImGui::Text(::bibo::version())", c.text("text", null, "::bibo::version()"))
    }

    @Test
    fun anExternConstantReadsByItsCppName() {
        val (_, ctx) = emit(
            """
            @_extern(cpp = "ImGuiWindowFlags_NoTitleBar", header = "imgui.h")
            pub NO_TITLE_BAR: Int32;

            @_extern(cpp = "::bibo::LIMIT", header = "car.hxx")
            pub LIMIT: Int32;
            """
        )
        val noTitle = ctx.symbol.members["NO_TITLE_BAR"] as GlobalSymbol
        val limit = ctx.symbol.members["LIMIT"] as GlobalSymbol
        assertEquals("::ImGuiWindowFlags_NoTitleBar", CppExternEmitter.constant(ctx, noTitle))
        assertEquals("::bibo::LIMIT", CppExternEmitter.constant(ctx, limit))
    }
}
