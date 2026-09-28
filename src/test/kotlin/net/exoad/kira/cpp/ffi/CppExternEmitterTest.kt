package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.compiler.backend.codegen.cpp.Pos
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CppExternEmitter over small modules (design 7.2, 7.3): the checks an extern declaration
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
            "KIRA_EXTERN_CHECK(ImGui::SliderFloat(kira::ffi::in(std::declval<const kira::Str&>()), kira::ffi::out(std::declval<float&>()), kira::ffi::arg<float>(), kira::ffi::arg<float>()), bool, \"sliderFloat\");",
            "KIRA_EXTERN_CHECK((ImGui::Text(std::declval<const char*>()), 0), int, \"text\");",
            "KIRA_EXTERN_CHECK(bare_c_name(std::declval<const std::uint8_t*>(), kira::ffi::arg<kira::Size>()), std::int64_t, \"bare\");",
        )
        val includes = h.lines().filter { it.startsWith("#include") }
        assertEquals(listOf("#include \"kira/rt.hxx\"", "#include \"imgui.h\"", "#include \"kira/ffi.hxx\"", "#include \"kira/macro_push.hxx\"", "#include \"kira/macro_pop.hxx\""), includes)
    }

    @Test
    fun aMutUnsafeParameterIsTheWritablePointerItselfNotAnOutProxy() {
        // Table 5.1: Unsafe<T> is `const T*` unless `mut`, by value. A C `void fill(uint8_t*, size_t)`
        // or ImGui's InputText(char* buf, ...) binds only a T*, which kira::ffi::out over a
        // `const T*` never gives; the mut form is the T* and takes no proxy.
        val h = header(
            """
            @_extern(cpp = "fill_buf", header = "probe.h")
            pub fx fill: (mut p: Unsafe<UInt8>, n: Size) Void;

            @_extern(cpp = "ImGui::InputText", header = "imgui.h")
            pub fx inputText: (label: Str, mut buf: Unsafe<Char>, size: Size) Bool;

            @_extern(cpp = "read_buf", header = "probe.h")
            pub fx read: (p: Unsafe<UInt8>, n: Size) Void;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK((fill_buf(std::declval<std::uint8_t*>(), kira::ffi::arg<kira::Size>()), 0), int, \"fill\");",
            "KIRA_EXTERN_CHECK(ImGui::InputText(kira::ffi::in(std::declval<const kira::Str&>()), std::declval<char*>(), kira::ffi::arg<kira::Size>()), bool, \"inputText\");",
            "KIRA_EXTERN_CHECK((read_buf(std::declval<const std::uint8_t*>(), kira::ffi::arg<kira::Size>()), 0), int, \"read\");",
        )
    }

    @Test
    fun aCSymbolIsReachedByItsCNameThroughAnExternCInclude() {
        // Design 7.3: `c =` alone names a C function, callable from C++ under that name; its
        // header is included with C linkage, which a header without a __cplusplus guard needs
        // and one with a guard tolerates. A `cpp =` beside it wins for this backend.
        val h = header(
            """
            @_extern(cpp = "ImGui::Text", header = "imgui.h")
            pub fx text: (s: CStr) Void;

            @_extern(c = "c_only_fn", header = "probe.h")
            pub fx cOnly: (a: Int32) Int32;

            @_extern(c = "c_len", header = "probe.h")
            pub fx cLen: (s: Str) Size;

            @_extern(c = "both_c", cpp = "both::cpp", header = "both.hxx")
            pub fx both: () Int32;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK(c_only_fn(kira::ffi::arg<std::int32_t>()), std::int32_t, \"cOnly\");",
            "KIRA_EXTERN_CHECK(c_len(kira::ffi::in(std::declval<const kira::Str&>())), kira::Size, \"cLen\");",
            "KIRA_EXTERN_CHECK(both::cpp(), std::int32_t, \"both\");",
        )
        val wanted = listOf(
            "#include \"kira/rt.hxx\"",
            "#include \"imgui.h\"",
            "#include \"both.hxx\"",
            "extern \"C\" {",
            "#include \"probe.h\"",
            "}",
            "#include \"kira/ffi.hxx\"",
            "#include \"kira/macro_push.hxx\"",
        )
        val got = h.lines().filter { it.startsWith("#include") || it == "extern \"C\" {" || it == "}" }.take(wanted.size)
        assertEquals(wanted, got, h)
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
            "KIRA_EXTERN_CHECK((std::declval<ImDrawList&>().AddLine(kira::ffi::arg<std::uint32_t>()), 0), int, \"DrawList.addLine\");",
            "KIRA_EXTERN_CHECK(ImGui::GetWindowDrawList(), ImDrawList*, \"drawList\");",
            "KIRA_EXTERN_CHECK(bibo::openCar(), kira::Rc<bibo::Car>, \"openCar\");",
        )
        // A method's own header is included too, each header once, in declaration order.
        val includes = h.lines().filter { it.startsWith("#include") }
        assertEquals(listOf("#include \"kira/rt.hxx\"", "#include \"car.hxx\"", "#include \"car.extra.hxx\"", "#include \"imgui.h\"", "#include \"kira/ffi.hxx\"", "#include \"kira/macro_push.hxx\"", "#include \"kira/macro_pop.hxx\""), includes)
    }

    @Test
    fun aStructWithFieldsGetsALayoutTwinASizeofCheckAndAnExactCheckPerField() {
        // is_same, not is_convertible: a C++ `int x` against Kira's Float32 converts, and
        // has the same size, so only the exact type catches it; offsetof against the twin
        // catches two same-typed fields declared in the other order.
        val h = header(
            """
            @_extern(cpp = "ImVec2", header = "imgui.h")
            pub struct Vec2 {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }

            @_opaque @_extern(cpp = "bibo::Buf", header = "car.hxx")
            pub class Buf {
            }

            @_extern(cpp = "bibo::Frame", header = "car.hxx")
            pub struct Frame {
                require pub mut data: Buf
                pub len: Size
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
            "KIRA_EXTERN_FIELD(ImVec2, ui::ffi_::Vec2, x, float, \"Vec2.x\");",
            "KIRA_EXTERN_FIELD(ImVec2, ui::ffi_::Vec2, y, float, \"Vec2.y\");",
            // A pointer field is an @_opaque handle: an Unsafe<T> field is rules.view.unsafe (4b, 1.4).
            "namespace ui::ffi_ { struct Frame { bibo::Buf* data; kira::Size len; }; }",
            "KIRA_EXTERN_FIELD(bibo::Frame, ui::ffi_::Frame, data, bibo::Buf*, \"Frame.data\");",
            "KIRA_EXTERN_FIELD(bibo::Frame, ui::ffi_::Frame, len, kira::Size, \"Frame.len\");",
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

            @_extern(cpp = "bibo::Pt", header = "car.hxx")
            pub struct Pt {
                pub new: Int32 = 0
            }
            """
        )
        assertTrue(errors.any { it.contains("the generic extern class 'Box'") }, errors.toString())
        assertTrue(errors.any { it.contains("the system header <cmath> of 'sqrt'") }, errors.toString())
        assertTrue(errors.any { it.contains("the fields of the extern class 'Car'") }, errors.toString())
        assertTrue(errors.any { it.contains("the extern mut variable 'COUNTER'") }, errors.toString())
        assertTrue(errors.any { it.contains("the extern constant 'LIMIT' takes its value from C++") }, errors.toString())
        assertTrue(errors.any { it.contains("the field 'new' of the extern struct 'Pt'") }, errors.toString())
    }

    @Test
    fun aKiraDefaultOnAnExternParameterIsRefused() {
        // The typer accepts `width: Float32 = 10.0` on an extern declaration (measured), but the
        // C++ side would never see it: KIRA_EXTERN_CHECK cannot check a default, and a call
        // that leaves it out would take whatever the C++ header's own default is. Refused at
        // the declaration, so a call's bindings are always given arguments.
        val errors = errorsOf(
            """
            @_extern(cpp = "ImGui::Button", header = "imgui.h")
            pub fx button: (label: Str, width: Float32 = 10.0) Bool;

            fx main: () Void {
                pressed: Bool = button("go")
            }
            """
        )
        assertTrue(errors.any { it.contains("the default of parameter 'width' of the extern function 'button'") }, errors.toString())
        assertTrue(errors.any { it.contains("leave it out") }, errors.toString())
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

            @_extern(c = "c_only_fn", header = "probe.h")
            pub fx cOnly: (a: Int32) Int32;

            @_extern(cpp = "fill_buf", header = "probe.h")
            pub fx fill: (mut p: Unsafe<UInt8>, n: Size) Void;

            fx main: () Int32 {
                car: Car = openCar()
                armed: Bool = car.arm()
                car.drive(0.1, 0.0)
                ahead: Float32 = scanOf(car).ahead()
                mut v: Float32 = 0.0
                moved: Bool = sliderFloat("throttle", mut v, 0.0, 1.0)
                drawList().addLine(7)
                mut bytes: List<UInt8> = List<UInt8> { values = [1, 2, 3, 4] }
                fill(bytes.view(), 4)
                return hypot(3, 4) + cOnly(1)
            }
            """
        )
        // A class handle, a struct and a scalar reach Kira as the declared type (a unique_ptr, a
        // Scan& or a C `int` would pass the check); a Void and a pointer are what C++ gave.
        assertEquals("kira::ffi::declared<kira::Rc<::bibo::Car>>(::bibo::openCar())", c.text("openCar", null))
        // R-B: `car` is a class handle C++ takes by const&, and a handle may hold an Fx (R-C), so
        // the handle is copied at the call (a refcount, never the object).
        assertEquals("kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(kira::Rc<::bibo::Car>(car)))", c.text("scanOf", null, "car"))
        assertEquals("kira::ffi::declared<bool>(car->arm())", c.text("arm", "car"))
        assertEquals("car->Drive(0.1f, 0.0f)", c.text("drive", "car", "0.1f", "0.0f"))
        assertEquals("kira::ffi::declared<float>(kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(car)).ahead())", c.text("ahead", "kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(car))"))
        assertEquals("kira::ffi::declared<bool>(::ImGui::SliderFloat(kira::ffi::in(\"throttle\"), kira::ffi::out(v), 0.0f, 1.0f))", c.text("sliderFloat", null, "\"throttle\"", "v", "0.0f", "1.0f"))
        assertEquals("::ImGui::GetWindowDrawList()->AddLine(7u)", c.text("addLine", "::ImGui::GetWindowDrawList()", "7u"))
        assertEquals("kira::ffi::declared<std::int32_t>(::c_hypot(3, 4))", c.text("hypot", null, "3", "4"))
        // A C name (7.3) and a mut Unsafe<T>, which is the T* itself: no out(...) around it; a
        // MutView of a local gives it its pointer (an Unsafe<T> result or local is refused, 4b).
        assertEquals("kira::ffi::declared<std::int32_t>(::c_only_fn(1))", c.text("cOnly", null, "1"))
        assertEquals("::fill_buf((kira::mutView(bytes)).data(), 4u)", c.text("fill", null, "kira::mutView(bytes)", "4u"))
        assertTrue(c.ctx.model.calls.values.filter { it.fn?.name == "arm" }.all { CppExternEmitter.isExternCall(it) })
    }

    /**
     * The check lets a `const char* name()` pass as `name: () Str` (7.2: a const char*
     * converts to a Str), and the call kept C++'s type: `name() == ABC`, with ABC a Kira Str
     * constant (D12: `inline constexpr const char*`), compared two pointers and printed 0 for
     * Kira's 1, on g++ 13, zig clang 20 and MSVC /W4 /WX with no warning (measured). A scalar
     * kept C++'s type too: the check proves a scalar of the same size, signedness and kind,
     * and a `long wide()` declared Int32 then failed to compile in `kira::div(long,
     * int32_t&)` (measured, g++ 13.2 and zig clang 20; a C `int` on arm-none-eabi the same).
     * So a result that is not Void and not a pointer (only a qualification could differ) is
     * `kira::ffi::declared<T>` at the call, and an extern constant's read the same; a field
     * read of an extern struct is `kira::ffi::field<T>`, which keeps the lvalue when the
     * member has the declared type.
     */
    @Test
    fun aResultAndAConstantReachKiraAsTheDeclaredType() {
        val c = callsOf(
            """
            @_extern(cpp = "probe::name", header = "probe.hxx")
            pub fx name: () Str;

            @_extern(cpp = "probe::find", header = "probe.hxx")
            pub fx find: () Maybe<Int32>;

            @_extern(cpp = "probe::count", header = "probe.hxx")
            pub fx count: () Int32;

            @_opaque @_extern(cpp = "probe::Buffer", header = "probe.hxx")
            pub class Buffer {
            }

            @_extern(cpp = "probe::buffer", header = "probe.hxx")
            pub fx buffer: () Buffer;

            @_extern(cpp = "probe::VERSION", header = "probe.hxx")
            pub VERSION: Str;

            @_extern(cpp = "probe::LIMIT", header = "probe.hxx")
            pub LIMIT: Int32;

            fx main: () Int32 {
                s: Str = name()
                m: Maybe<Int32> = find()
                p: Buffer = buffer()
                return count()
            }
            """
        )
        assertEquals("kira::ffi::declared<kira::Str>(::probe::name())", c.text("name", null))
        assertEquals("kira::ffi::declared<kira::Maybe<std::int32_t>>(::probe::find())", c.text("find", null))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::count())", c.text("count", null))
        // A pointer result is an @_opaque handle, kept as C++ gave it (a CStr or Unsafe<T>
        // result is rules.view.extern: an extern never hands back a pointer, 5.2).
        assertEquals("::probe::buffer()", c.text("buffer", null))
        val version = c.ctx.symbol.members["VERSION"] as GlobalSymbol
        val limit = c.ctx.symbol.members["LIMIT"] as GlobalSymbol
        assertEquals("kira::ffi::declared<kira::Str>(probe::VERSION)", CppExternEmitter.constant(c.ctx, version))
        assertEquals("kira::ffi::declared<std::int32_t>(probe::LIMIT)", CppExternEmitter.constant(c.ctx, limit))
    }

    /**
     * A field read of an extern struct is a boundary crossing like a result: the field check
     * accepts a same-size twin of the declared type (a C `int` on arm-none-eabi, an unscoped
     * enum; and, before char became only Char, a `char c` declared Int8 printed C where an
     * Int8 prints 67, measured). The expression part hands the read's text to [CppExternEmitter.field],
     * which wraps a scalar or class-typed field of an extern struct and leaves a pointer field
     * and every field of a Kira type alone.
     */
    @Test
    fun aFieldReadOfAnExternStructReachesKiraAsTheDeclaredType() {
        val (_, ctx) = emit(
            """
            @_opaque @_extern(cpp = "probe::Buf", header = "probe.hxx")
            pub class Buf {
            }

            @_extern(cpp = "probe::Rec", header = "probe.hxx")
            pub struct Rec {
                pub c: Int8 = 0
                pub mode: Int32 = 0
                pub x: Float32 = 0.0
                require pub mut buf: Buf
            }

            pub struct Own {
                pub n: Int32 = 0
            }
            """
        )
        val rec = ctx.symbol.members["Rec"] as ClassSymbol
        val own = ctx.symbol.members["Own"] as ClassSymbol
        fun fieldOf(cls: ClassSymbol, name: String) = cls.fields.first { it.name == name }
        assertTrue(CppExternEmitter.isExternField(fieldOf(rec, "c")))
        assertTrue(!CppExternEmitter.isExternField(fieldOf(own, "n")))
        assertEquals("kira::ffi::field<std::int8_t>(r.c)", CppExternEmitter.field(ctx, fieldOf(rec, "c"), "r.c"))
        assertEquals("kira::ffi::field<std::int32_t>(r.mode)", CppExternEmitter.field(ctx, fieldOf(rec, "mode"), "r.mode"))
        assertEquals("kira::ffi::field<float>(p->x)", CppExternEmitter.field(ctx, fieldOf(rec, "x"), "p->x"))
        assertEquals("r.buf", CppExternEmitter.field(ctx, fieldOf(rec, "buf"), "r.buf"))
        assertEquals("o.n", CppExternEmitter.field(ctx, fieldOf(own, "n"), "o.n"))
    }

    /**
     * std::optional and std::function convert from any optional or callable whose parts
     * convert, which is the is_convertible hole one level down: a Kira `Maybe<Int32>` reached
     * a C++ `std::optional<std::uint8_t>` through `std::declval<const kira::Maybe<std::int32_t>&>()`
     * and 300 arrived as 44 (measured). A Maybe or a Fn parameter is stated as
     * `kira::ffi::arg<T>()`, whose conversion kira/ffi.hxx enables part by part; a struct
     * stays the lvalue `std::declval<const T&>()`, so an overload taking it outranks one
     * taking a type it converts to, as at the call.
     */
    @Test
    fun aMaybeOrFnParameterIsStatedAsTheProxyAndAStructAsTheLvalue() {
        val h = header(
            """
            @_extern(cpp = "probe::put", header = "probe.hxx")
            pub fx put: (m: Maybe<Int32>) Void;

            @_extern(cpp = "probe::onTick", header = "probe.hxx")
            pub fx onTick: (f: Fx<Tuple1<Int32>, Void>) Void;

            @_extern(cpp = "probe::ticker", header = "probe.hxx")
            pub fx ticker: () Fx<Tuple1<Int32>, Int32>;

            @_extern(cpp = "probe::find", header = "probe.hxx")
            pub fx find: () Maybe<Int32>;

            @_extern(cpp = "ImVec2", header = "imgui.h")
            pub struct Vec2 {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }

            @_extern(cpp = "ImGui::Dummy", header = "imgui.h")
            pub fx dummy: (size: Vec2) Void;
            """
        )
        assertLines(
            h,
            "KIRA_EXTERN_CHECK((probe::put(kira::ffi::arg<kira::Maybe<std::int32_t>>()), 0), int, \"put\");",
            "KIRA_EXTERN_CHECK((probe::onTick(kira::ffi::arg<kira::Fn<void(std::int32_t)>>()), 0), int, \"onTick\");",
            "KIRA_EXTERN_CHECK(probe::ticker(), kira::Fn<std::int32_t(std::int32_t)>, \"ticker\");",
            "KIRA_EXTERN_CHECK(probe::find(), kira::Maybe<std::int32_t>, \"find\");",
            "KIRA_EXTERN_CHECK((ImGui::Dummy(std::declval<const ImVec2&>()), 0), int, \"dummy\");",
        )
    }

    @Test
    fun aCStrParameterTakesAStrAsSection72Says() {
        // Section 7.2: a literal passes through, a named Str becomes .c_str(), anything else
        // kira::ffi::CStrBuf(expr).c_str(). A Kira Str constant is `inline constexpr const
        // char*` (D12), already a CStr, so it passes through like the literal; a mut Str global
        // is a kira::Str; an extern Str constant is whatever C++ declared, so it takes the
        // buffer. A CStr value (a CStr parameter; an extern CStr result is rules.view.extern,
        // 5.2) passes as itself.
        //
        // The typer takes a Str for a CStr parameter of an extern function and records the
        // argument as the Str it is (CallResolver.typeGiven); it refused every one of these
        // calls with types.assign.mismatch before (measured under --target cpp), so CStr was
        // unreachable from Kira. Every call here goes through the typer.
        val c = callsOf(
            """
            @_extern(cpp = "ImGui::Text", header = "imgui.h")
            pub fx text: (s: CStr) Void;

            @_extern(cpp = "bibo::nameOf", header = "car.hxx")
            pub fx nameOf: (i: Int32) Str;

            @_extern(cpp = "bibo::BANNER", header = "car.hxx")
            pub BANNER: Str;

            pub GREETING: Str = "hi"
            pub mut TITLE: Str = "t"

            fx show: (label: Str, version: CStr) Void {
                text(version)
                text("literal")
                text(label)
                text(GREETING)
                text(TITLE)
                text(BANNER)
                text(nameOf(1))
            }
            """
        )
        val textCalls = c.ctx.model.calls.values.filter { it.fn?.name == "text" }
        assertEquals(7, textCalls.size, "every text(...) call typed")
        fun given(text: String, pick: (Expr) -> Boolean): String {
            val call = textCalls.firstOrNull { pick((it.args.single() as ArgBinding.Given).expr) } ?: fail("no text(...) call whose argument is $text")
            return CppExternEmitter.call(c.ctx, call, null, listOf(text))
        }
        fun named(name: String): (Expr) -> Boolean = { it is Identifier && it.value == name }
        fun calling(name: String): (Expr) -> Boolean = { it is FunctionCallExpr && (it.name as? Identifier)?.value == name }
        assertEquals("::ImGui::Text(version)", given("version", named("version")))
        assertEquals("::ImGui::Text(\"literal\")", given("\"literal\"") { it is StringLiteral })
        assertEquals("::ImGui::Text(label.c_str())", given("label", named("label")))
        assertEquals("::ImGui::Text(::ext::GREETING)", given("::ext::GREETING", named("GREETING")))
        assertEquals("::ImGui::Text(::ext::TITLE.c_str())", given("::ext::TITLE", named("TITLE")))
        assertEquals("::ImGui::Text(kira::ffi::CStrBuf(::bibo::BANNER).c_str())", given("::bibo::BANNER", named("BANNER")))
        assertEquals("::ImGui::Text(kira::ffi::CStrBuf(::bibo::nameOf(1)).c_str())", given("::bibo::nameOf(1)", calling("nameOf")))
        // The argument is recorded as the Str it is: no coercion, the emitter reads its type.
        textCalls.forEach { call ->
            val arg = (call.args.single() as ArgBinding.Given).expr
            assertTrue(c.ctx.model.coercion(arg) == null, "no coercion at a CStr argument")
            assertTrue(c.ctx.model.types[arg] == KType.Str || named("version")(arg), "the argument keeps its Str type")
        }
    }

    /**
     * Rounds 1-4 refused a computed `Str` argument feeding a call whose result, `mut` argument
     * or `mut` receiver could point into it ("policy 1"), tracked here through `carriesPointer`.
     * Decision 4b (30-second-class.md, section 7.4) deletes that check from this emitter: an
     * extern's result, `mut` argument and receiver can never be second-class any more (5.2, 5.3
     * - refused at the declaration by ViewPass's `rules.view.extern`/`rules.view.type`, in
     * `w2-5-rules`, which also carries the refused shapes these rounds probed as
     * `ViewPassTest` negatives), so a temporary `Str` buffer feeding any argument here is always
     * safe for the call's own full-expression. What stays worth pinning in this file is exactly
     * what rounds 1-4 called "not the regression": these shapes were never refused, and still
     * are not, now with no check standing between them and a silent one - there being none any
     * more is the point.
     */
    private fun diagsOf(body: String, fnName: String, receiver: String? = null, vararg args: String): List<String> {
        val c = callsOf(body)
        val call = c.ctx.model.calls.values.firstOrNull { it.fn?.name == fnName } ?: fail("no call of '$fnName' in the model")
        CppExternEmitter.call(c.ctx, call, receiver, args.toList())
        // Bodies are not lowered on this branch (pending on W2.3, ledgered), so emit(body)
        // already reported "the body of '<fn>' is not lowered yet" for every function with a
        // body; that noise is unrelated to what this test checks and is filtered out here.
        return errorsIgnoringUnloweredBodies(c.ctx)
    }

    /** [errorsOf]/[Calls.ctx]'s errors, minus the "body not lowered yet" noise every function with a body reports on this branch ([diagsOf]'s doc). */
    private fun errorsIgnoringUnloweredBodies(ctx: CppEmitContextImpl): List<String> =
        ctx.diagnostics.filter { it.isError && "the body of" !in it.message }.map { it.render() }

    @Test
    fun aLiteralANamedStrOrAConstantIntoACStrParameterIsNeverRefused() {
        // A literal, a plain named Str, and a Kira Str constant build no temporary at all
        // (CppExternEmitter.argument's doc), so none of them was ever refused even against a
        // call whose result is a pointer-carrying shape - and nothing here checks that any more.
        // The results are first-class (Int32): a Maybe<CStr> result or local is rules.view.type
        // (decision 4b), which ViewPassTest pins.
        val literal = diagsOf(
            """
            @_extern(cpp = "probe::after", header = "probe.hxx")
            pub fx after: (s: CStr, c: Int32) Int32;
            fx cat: () Void {
                r: Int32 = after("literal", 1)
            }
            """,
            "after", null, "\"literal\"", "1",
        )
        assertTrue(literal.isEmpty(), literal.toString())

        val namedCStr = diagsOf(
            """
            @_extern(cpp = "probe::after", header = "probe.hxx")
            pub fx after: (s: CStr, c: Int32) Int32;
            fx cat: (a: Str) Void {
                r: Int32 = after(a, 1)
            }
            """,
            "after", null, "a", "1",
        )
        assertTrue(namedCStr.isEmpty(), namedCStr.toString())

        val constantCStr = diagsOf(
            """
            @_extern(cpp = "probe::after", header = "probe.hxx")
            pub fx after: (s: CStr, c: Int32) Int32;
            pub GREETING: Str = "hi"
            fx cat: () Void {
                r: Int32 = after(GREETING, 1)
            }
            """,
            "after", null, "::ext::GREETING", "1",
        )
        assertTrue(constantCStr.isEmpty(), constantCStr.toString())

        val namedStr = diagsOf(
            """
            @_extern(cpp = "probe::afterS", header = "probe.hxx")
            pub fx afterS: (s: Str, c: Int32) Int32;
            fx cat: (a: Str) Void {
                r: Int32 = afterS(a, 1)
            }
            """,
            "afterS", null, "a", "1",
        )
        assertTrue(namedStr.isEmpty(), namedStr.toString())

        // Against a call whose result carries no pointer at all, a literal was always fine too.
        val literalIntoScalarResult = diagsOf(
            """
            @_extern(cpp = "probe::lengthOfS", header = "probe.hxx")
            pub fx lengthOfS: (s: Str) Int32;
            fx cat: () Void {
                n: Int32 = lengthOfS("harmless")
            }
            """,
            "lengthOfS", null, "\"harmless\"",
        )
        assertTrue(literalIntoScalarResult.isEmpty(), literalIntoScalarResult.toString())
    }

    /**
     * A bare `mut p: Unsafe<T>` out-buffer is the caller's own memory, not a value the callee
     * produces (table 5.1), so round 4's `carriesPointer` never refused a computed `Str`
     * argument beside one - the one shape among rounds 1-4's probes that was never refused even
     * before this round deleted the check. It still is not.
     */
    @Test
    fun aMutUnsafeOutBufferBesideAComputedStrArgumentIsNeverRefused() {
        val mutUnsafeAlone = diagsOf(
            """
            @_extern(cpp = "probe::nameOf", header = "probe.hxx")
            pub fx nameOf: (i: Int32) Str;
            @_extern(cpp = "probe::fillFrom", header = "probe.hxx")
            pub fx fillFrom: (s: Str, mut buf: Unsafe<UInt8>) Void;
            fx cat: () Void {
                mut b: List<UInt8> = List<UInt8> { values = [0, 0, 0, 0] }
                fillFrom(nameOf(1), b.view())
            }
            """,
            "fillFrom", null, "::probe::nameOf(1)", "kira::mutView(b)",
        )
        assertTrue(mutUnsafeAlone.isEmpty(), mutUnsafeAlone.toString())
    }

    /**
     * Design 1.4, 5.5: an `Unsafe<T>` parameter of an extern function - the pointer type's only
     * legal position - takes a `View<T>` argument as itself, and a `mut Unsafe<T>` (`T*` by
     * value, not an out-parameter, table 5.1) a `MutView<T>`, with no call-site `mut` and no
     * place needed for the `mut` one: it hands over the view's own pointer, not a reference the
     * callee writes back through. `CallResolver.typeGiven` accepts either at an extern call
     * only, recording nothing beyond the argument's own `View`/`MutView` type (exactly as the
     * `CStr`-for-`Str` case needs no coercion either); the emitter reads that type back and
     * lowers `.data()`. The exact-type case (an `Unsafe<T>` argument that already is one, table
     * 5.1's other way of reaching a C buffer) is untouched: it still passes through as itself.
     */
    @Test
    fun anUnsafeParameterTakesAViewOrAMutViewLoweredDotData() {
        val c = callsOf(
            """
            @_extern(cpp = "probe::readBuf", header = "probe.hxx")
            pub fx readBuf: (p: Unsafe<UInt8>, n: Size) Int64;

            @_extern(cpp = "probe::fillBuf", header = "probe.hxx")
            pub fx fillBuf: (mut p: Unsafe<UInt8>, n: Size) Void;

            fx read: (v: View<UInt8>) Int64 {
                return readBuf(v, 4)
            }

            fx fill: (v: MutView<UInt8>) Void {
                fillBuf(v, 4)
            }
            """
        )
        // The typer accepted View<T> and MutView<T> arguments against Unsafe<T> and mut
        // Unsafe<T> parameters at all (measured under --target cpp, this branch would have
        // refused both with types.assign.mismatch/types.call.mut-missing before); the emitter
        // wraps `.data()` around whatever text the expression part already produced for them.
        assertEquals(
            "kira::ffi::declared<std::int64_t>(::probe::readBuf((v).data(), 4u))",
            c.text("readBuf", null, "v", "4u"),
        )
        assertEquals(
            "::probe::fillBuf((v).data(), 4u)",
            c.text("fillBuf", null, "v", "4u"),
        )
        val readArg = (c.of("readBuf").args[0] as ArgBinding.Given).expr
        val fillArg = (c.of("fillBuf").args[0] as ArgBinding.Given).expr
        assertTrue(c.ctx.model.coercion(readArg) == null, "no coercion recorded at a View argument to Unsafe<T>")
        assertTrue(c.ctx.model.coercion(fillArg) == null, "no coercion recorded at a MutView argument to a mut Unsafe<T>")
    }

    /**
     * Round-1 significant finding #1 and round 2's #4-#6 (this package's), closed by R-B
     * (40-round3 2): a by-value argument C++ receives by reference - a `Str` through
     * `kira::ffi::in`, a `Str` into a `CStr` as `.c_str()`, a container, a value struct, a
     * handle - is bound to the argument's own storage, and an extern may run Kira code through
     * what it is given (contract 5.4.3) or write a `mut` argument that is the same storage. Such
     * an argument that is a place is copied at the call exactly then:
     * [CppExternEmitter.call] asks R-C's `CallReach.mayRunAnything` (condition 1) and R-A's
     * `readPlace` (a lent `gstrs.get(0)` is the place `gstrs[0]`), and `Rules.mayHold` for a `mut`
     * argument (condition 2). A literal, a computed value and a Kira constant never are, nor is
     * anything when neither condition holds. ExternCallRunTest runs the same shapes on three
     * compilers.
     *
     * Round 2's fixture handed the extern a direct lambda that writes `GS`; with W2.5's rules
     * merged that is `rules.exclusivity.alias` (D37), refused before any emission, so the
     * shapes that reach the copy are an `Fx` held in a List or in a struct field.
     */
    @Test
    fun aByReferenceArgumentThatIsAPlaceIsCopiedWhenTheCallMayWriteIt() {
        val c = callsOf(
            """
            @_extern(cpp = "probe::lenAfterL", header = "probe.hxx")
            pub fx lenAfterL: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;
            @_extern(cpp = "probe::lenAfterCb", header = "probe.hxx")
            pub fx lenAfterCb: (s: Str, c: Cb) Int32;
            @_extern(cpp = "probe::sumListL", header = "probe.hxx")
            pub fx sumListL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;
            @_extern(cpp = "probe::appendLen", header = "probe.hxx")
            pub fx appendLen: (s: Str, mut out: Str) Int32;
            @_extern(cpp = "probe::appendLenI", header = "probe.hxx")
            pub fx appendLenI: (s: Str, mut n: Int32) Int32;
            @_extern(cpp = "probe::lengthOfS", header = "probe.hxx")
            pub fx lengthOfS: (s: Str) Int32;
            pub struct Cb {
                require pub f: Fx<Tuple0, Void>
            }
            pub mut GS: Str = "hi"
            pub mut GL: List<Int32> = List<Int32> { values = [1, 2, 3] }
            pub mut GSTRS: List<Str> = List<Str> { values = ["hi"] }
            pub GREETING: Str = "hi"
            fx inList: (fs: List<Fx<Tuple0, Void>>) Int32 {
                return lenAfterL(GS, fs) + lenAfterL(GSTRS.get(0), fs) + sumListL(GL, fs) + lenAfterL("hi", fs) + lenAfterL(GREETING, fs)
            }
            fx inField: (c: Cb) Int32 {
                return lenAfterCb(GS, c)
            }
            fx viaMut: (mut o: Str, mut n: Int32) Int32 {
                return appendLen(GS, mut o) + appendLenI(GS, mut n) + lengthOfS(GS)
            }
            """
        )
        fun text(callee: String, first: String, vararg args: String): String {
            val call = c.ctx.model.calls.values.firstOrNull { rc ->
                rc.fn?.name == callee && (rc.args.first() as ArgBinding.Given).expr.let { net.exoad.kira.compiler.analysis.types.KiraUnparser.text(it) } == first
            } ?: fail("no call $callee($first, ...)")
            return CppExternEmitter.call(c.ctx, call, null, args.toList())
        }
        // Condition 1, an Fx held in a List: the global, the lent element and the List are
        // copied; the literal and the constant are not. The List of closures is itself a
        // by-reference place (the parameter `fs`), so it is copied too.
        val fsCopy = "kira::List<kira::Fn<void()>>(fs)"
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lenAfterL(kira::ffi::in(kira::Str(::ext::GS)), $fsCopy))", text("lenAfterL", "GS", "::ext::GS", "fs"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lenAfterL(kira::ffi::in(kira::Str(kira::at(::ext::GSTRS, 0))), $fsCopy))", text("lenAfterL", "GSTRS.get(0)", "kira::at(::ext::GSTRS, 0)", "fs"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::sumListL(kira::List<std::int32_t>(::ext::GL), $fsCopy))", text("sumListL", "GL", "::ext::GL", "fs"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lenAfterL(kira::ffi::in(\"hi\"), $fsCopy))", text("lenAfterL", "\"hi\"", "\"hi\"", "fs"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lenAfterL(kira::ffi::in(::ext::GREETING), $fsCopy))", text("lenAfterL", "GREETING", "::ext::GREETING", "fs"))
        // Condition 1, an Fx in a struct field (round 1's "anything holding one").
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lenAfterCb(kira::ffi::in(kira::Str(::ext::GS)), Cb(c)))", text("lenAfterCb", "GS", "::ext::GS", "c"))
        // Condition 2: a mut Str may be GS itself; a mut Int32 cannot hold a Str; and a call
        // given nothing that may run Kira code or write lends the place as before.
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::appendLen(kira::ffi::in(kira::Str(::ext::GS)), kira::ffi::out(o)))", text("appendLen", "GS", "::ext::GS", "o"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::appendLenI(kira::ffi::in(::ext::GS), kira::ffi::out(n)))", text("appendLenI", "GS", "::ext::GS", "n"))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::lengthOfS(kira::ffi::in(::ext::GS)))", text("lengthOfS", "GS", "::ext::GS"))

        // The direct lambda that writes the place it is handed with is refused, not copied.
        val direct = TyperTestSupport.snippet(
            """
            @_extern(cpp = "probe::lenAfter", header = "probe.hxx")
            pub fx lenAfter: (s: Str, whenDone: Fx<Tuple0, Void>) Int32;
            pub mut GS: Str = "hi"
            fx cat: () Int32 {
                return lenAfter(GS, fx() Void { GS = "" })
            }
            """
        ).diagnostics.map { it.code }
        assertTrue("rules.exclusivity.alias" in direct, direct.toString())
    }

    /**
     * Round-1 significant finding #2, part (a). Design 1.4: a non-`mut` `p: Unsafe<T>` takes
     * "a `View<T>` argument (or anything the typer converts to one, `Coercion.ToView`)" - the
     * same conversion an ordinary `View<T>` parameter accepts (`CoercionRules.fit`'s
     * `facts.isView(expected)` branch): an `Arr`/`List` place, or a `Str` literal when `T` is
     * `Char`. Before this fix, `CallResolver.typeGiven`'s `Unsafe<T>` branch checked only for
     * an argument whose static type already *was* `View<T>`/`MutView<T>`, so a bare place or a
     * literal fell through to the ordinary mismatch (measured, round-1 verdict probes u2/u10:
     * `sumP(ys, ys.size())` with `ys: List<Int32>` refused `types.assign.mismatch "expects
     * Unsafe<Int32>, but this is List<Int32>"`; `lenBuf("abc")` the same against
     * `Unsafe<Char>`). Fixed: `typeGiven` now tries the same `View<T>` fit before falling back
     * to the plain `Unsafe<T>` mismatch, and records the `Coercion.ToView` exactly as a
     * `View<T>` parameter would - the emitter's `.data()` wrap (`isUnsafe(p.type)`, extended to
     * read that coercion too) is all that is new at the boundary. The `mut Unsafe<T>` case is
     * untouched (still `MutView<T>`-only, table 5.1): design 1.4 states the "anything the typer
     * converts" clause for the non-`mut` case alone.
     */
    @Test
    fun aListOrArrPlaceOrAStrLiteralCoercesToUnsafeViaToView() {
        val list = callsOf(
            """
            @_extern(cpp = "probe::sumP", header = "probe.hxx")
            pub fx sumP: (p: Unsafe<Int32>, n: Size) Int32;
            fx cat: () Int32 {
                ys: List<Int32> = List<Int32> { values = [1, 2] }
                return sumP(ys, ys.size())
            }
            """
        )
        // `ys` a local: a List parameter is shared storage, and beside the IMPURE extern the
        // view of it is rules.view.write (decision 4b, literally), which ViewPassTest pins.
        assertTrue(errorsIgnoringUnloweredBodies(list.ctx).isEmpty(), errorsIgnoringUnloweredBodies(list.ctx).toString())
        assertEquals(
            "kira::ffi::declared<std::int32_t>(::probe::sumP((kira::view(ys)).data(), n))",
            list.text("sumP", null, "kira::view(ys)", "n"),
        )
        val listArg = (list.of("sumP").args[0] as ArgBinding.Given).expr
        assertTrue(list.ctx.model.coercion(listArg) is Coercion.ToView, "a List place records Coercion.ToView into Unsafe<T>")

        val arr = callsOf(
            """
            @_extern(cpp = "probe::sumP", header = "probe.hxx")
            pub fx sumP: (p: Unsafe<Int32>, n: Size) Int32;
            fx cat: () Int32 {
                xs: Arr<Int32, 4> = [1, 2, 3, 4]
                return sumP(xs, 4)
            }
            """
        )
        assertTrue(errorsIgnoringUnloweredBodies(arr.ctx).isEmpty(), errorsIgnoringUnloweredBodies(arr.ctx).toString())

        val literal = callsOf(
            """
            @_extern(cpp = "probe::lenBuf", header = "probe.hxx")
            pub fx lenBuf: (p: Unsafe<Char>) Int32;
            fx cat: () Int32 {
                return lenBuf("abc")
            }
            """
        )
        assertTrue(errorsIgnoringUnloweredBodies(literal.ctx).isEmpty(), errorsIgnoringUnloweredBodies(literal.ctx).toString())
        assertEquals(
            "kira::ffi::declared<std::int32_t>(::probe::lenBuf((kira::lit(\"abc\")).data()))",
            literal.text("lenBuf", null, "kira::lit(\"abc\")"),
        )

        // A List of the wrong element type: the View<T> fit does not apply, so this still
        // refuses against Unsafe<T>'s own message, not silently against View<T>'s. Checked
        // through the typer alone (TyperTestSupport.snippet), since callsOf/emit hard-fails
        // the moment the typer has any error (DeclTestSupport.emitWith).
        val wrong = TyperTestSupport.snippet(
            """
            @_extern(cpp = "probe::sumP", header = "probe.hxx")
            pub fx sumP: (p: Unsafe<Int32>, n: Size) Int32;
            fx cat: (ys: List<Int64>) Int32 {
                return sumP(ys, ys.size())
            }
            """
        ).diagnostics.map { it.message }
        assertTrue(wrong.any { it.contains("expects Unsafe<Int32>") && it.contains("List<Int64>") }, wrong.toString())

        // The mut Unsafe<T> case is untouched: a plain mut List place is still refused, since
        // design 1.4 gives the "anything the typer converts" clause to the non-mut case alone.
        val mutList = TyperTestSupport.snippet(
            """
            @_extern(cpp = "probe::fillP", header = "probe.hxx")
            pub fx fillP: (mut p: Unsafe<Int32>, n: Size) Void;
            fx cat: (mut ys: List<Int32>) Void {
                fillP(mut ys, ys.size())
            }
            """
        ).diagnostics
        assertTrue(mutList.isNotEmpty(), mutList.toString())
    }

    /**
     * Round-1 significant finding #2, part (b). Design 30 section 5: "'Extern' here means every
     * function whose body C++ supplies: an `@_extern` function or method, and a bodyless `pub`
     * prototype a C++ file defines ... as W2.4 already groups them" - the decls golden's own
     * `peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;` has no `@_extern` marker at all.
     * Before this fix, `externCallee` was `fn.foreign is Foreign.Extern` alone, so calling such
     * a prototype with `View`/`MutView` arguments was refused three ways (measured, round-1
     * verdict probe u10): `types.assign.mismatch` against `Unsafe<Int32>` for the `View`
     * argument, plus `types.call.mut-missing`/`mut-not-place`/`mut-type` for the `mut`
     * `MutView` one, since neither the `Unsafe` coercion nor the by-ref `MutView` exemption
     * (design 1.4's "no call-site `mut` and no place needed") ever ran. Fixed:
     * `CallResolver.isExternLike` also counts a bodyless `pub` function or method, and both
     * `externCallee` call sites (`method`, `free`) read it instead of the marker alone. A
     * private bodyless function is a different, and already-refused (`CppDeclEmitter`,
     * `NO_BODY_CODE`), shape.
     *
     * Round 3 (R-G, round-2 w2-6 #7): the prototype was typed as an extern but its call was
     * lowered as a Kira call (`::w::peek(w, v)`, 'cannot convert kira::View<int> to const
     * int32_t*' in g++). `isExternLike` is now R-G's one predicate, `suppliedByCpp`, the typer
     * gives the call `CallKind.EXTERN`, and [CppExternEmitter.call] lowers it: `.data()` for the
     * views, the module's own spelling of the name, no `declared<T>` (Kira declared the C++
     * prototype itself). ExternCallRunTest compiles and runs it. The views are of locals: of
     * parameters (shared storage) beside the IMPURE call they are rules.view.write (4b).
     */
    @Test
    fun aBodylessPubPrototypeIsAnExternForTheUnsafeConventionToo() {
        val body = """
            pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;
            fx cat: () Int32 {
                xs: List<Int32> = List<Int32> { values = [1, 2] }
                mut ys: List<Int32> = List<Int32> { values = [3, 4] }
                return peek(xs.view(), ys.view())
            }
        """
        val pub = TyperTestSupport.snippet(body).diagnostics.map { it.message }
        assertTrue(pub.isEmpty(), pub.toString())
        val c = callsOf(body)
        val call = c.of("peek")
        assertEquals(net.exoad.kira.compiler.analysis.types.CallKind.EXTERN, call.kind)
        assertTrue(CppExternEmitter.isExternCall(call))
        assertEquals("peek((kira::view(xs)).data(), (kira::mutView(ys)).data())", c.text("peek", null, "kira::view(xs)", "kira::mutView(ys)"))

        val shared = TyperTestSupport.snippet(
            """
            pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;
            fx cat: (xs: List<Int32>, mut ys: List<Int32>) Int32 {
                return peek(xs.view(), ys.view())
            }
            """
        ).diagnostics.map { it.code }
        assertTrue("rules.view.write" in shared, shared.toString())

        val private = TyperTestSupport.snippet(
            """
            fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;
            fx cat: () Int32 {
                xs: List<Int32> = List<Int32> { values = [1, 2] }
                mut ys: List<Int32> = List<Int32> { values = [3, 4] }
                return peek(xs.view(), ys.view())
            }
            """
        ).diagnostics.map { it.message }
        assertTrue(private.any { it.contains("expects Unsafe<Int32>") }, private.toString())
    }

    @Test
    fun aStrIsNoCStrAnywhereElse() {
        // Only an extern function has a CStr parameter to fill; the typer's rule is scoped to
        // the extern callee, so a Kira function declared with CStr still refuses a Str.
        val program = TyperTestSupport.snippet(
            """
            @_extern(cpp = "bibo::version", header = "car.hxx")
            pub fx version: () CStr;

            fx own: (s: CStr) Void { }

            fx show: () Void {
                own("literal")
                own(version())
            }
            """
        )
        // Two diagnostics, each a refusal: the Str given to a Kira function's CStr, and the
        // extern's CStr result itself (rules.view.extern: an extern never hands back a
        // pointer, 5.2). `own(version())` is a CStr given to a CStr and adds nothing.
        val messages = program.diagnostics.map { it.message }
        assertEquals(1, messages.count { it.contains("expects CStr, but this is Str") }, messages.toString())
        assertEquals(1, program.diagnostics.count { it.code == "rules.view.extern" && it.message.contains("'version' returns a CStr") }, messages.toString())
        assertEquals(2, program.diagnostics.size, messages.toString())
    }

    /**
     * The read is the marker's spelling, never `::` + it: a C constant through `c =` is
     * usually a macro (`#define C_LIMIT 42`; limits.h's are), `::C_LIMIT` is `::42`, and the
     * check the header states is spelled without `::` too, so the read uses what the check
     * proved. A marker that wants the global scope writes it and keeps it.
     */
    @Test
    fun anExternConstantReadsByItsCppNameAsTheMarkerSpellsIt() {
        val (emitted, ctx) = emit(
            """
            @_extern(cpp = "ImGuiWindowFlags_NoTitleBar", header = "imgui.h")
            pub NO_TITLE_BAR: Int32;

            @_extern(cpp = "::bibo::LIMIT", header = "car.hxx")
            pub LIMIT: Int32;

            @_extern(c = "C_LIMIT", header = "limits.h")
            pub C_LIMIT: Int32;

            @_extern(c = "C_VERSION", header = "limits.h")
            pub C_VERSION: Str;
            """
        )
        val noTitle = ctx.symbol.members["NO_TITLE_BAR"] as GlobalSymbol
        val limit = ctx.symbol.members["LIMIT"] as GlobalSymbol
        val cLimit = ctx.symbol.members["C_LIMIT"] as GlobalSymbol
        val cVersion = ctx.symbol.members["C_VERSION"] as GlobalSymbol
        assertEquals("kira::ffi::declared<std::int32_t>(ImGuiWindowFlags_NoTitleBar)", CppExternEmitter.constant(ctx, noTitle))
        assertEquals("kira::ffi::declared<std::int32_t>(::bibo::LIMIT)", CppExternEmitter.constant(ctx, limit))
        assertEquals("kira::ffi::declared<std::int32_t>(C_LIMIT)", CppExternEmitter.constant(ctx, cLimit))
        // A Str constant is a kira::Str made from whatever the macro or object is (a `#define C_VERSION "1.2"`).
        assertEquals("kira::ffi::declared<kira::Str>(C_VERSION)", CppExternEmitter.constant(ctx, cVersion))
        val header = CppWriter.normalize(emitted.header)
        assertLines(header, "KIRA_EXTERN_CHECK(C_LIMIT, std::int32_t, \"C_LIMIT\");")
        assertTrue("::C_LIMIT" !in header, "the check is spelled as the read is:\n$header")
    }

    /**
     * A struct or an opaque class marked `c =` is spelled by its C name wherever the type
     * appears (a check's result or parameter, a signature, a body), exactly as its sizeof and
     * field checks name it: the type speller reads the marker through [CppExternEmitter.cppName].
     * Read through `cpp =` alone, `@_extern(c = "cnt_state") pub struct CntState` was spelled
     * `CntState` in the check of `cnt_get()` and `File*` for `fopen`'s result, and the header
     * failed with 'CntState was not declared' (measured, g++ 13.2): the normal case for C,
     * where the C name is snake_case and the Kira name is not. A C struct its header never
     * typedefs is named with its keyword, and the global `::` goes after it.
     */
    @Test
    fun aCTypeIsSpelledByItsCNameWhereverTheTypeAppears() {
        val (emitted, ctx) = emit(
            """
            @_extern(c = "cnt_state", header = "cnt.h")
            pub struct CntState {
                pub n: Int32 = 0
            }

            @_extern(c = "cnt_get", header = "cnt.h")
            pub fx cntGet: () CntState;

            @_extern(c = "cnt_put", header = "cnt.h")
            pub fx cntPut: (mut s: CntState) Void;

            @_extern(c = "struct raw_pt", header = "cnt.h")
            pub struct RawPt {
                pub x: Int32 = 0
            }

            @_extern(c = "raw_origin", header = "cnt.h")
            pub fx rawOrigin: () RawPt;

            @_opaque @_extern(c = "FILE", header = "stdio.h")
            pub class File {
            }

            @_extern(c = "fopen", header = "stdio.h")
            pub fx fopen: (path: CStr, mode: CStr) File;
            """
        )
        val errors = emitted.diagnostics.filter { it.isError }
        assertTrue(errors.isEmpty(), "errors:\n" + errors.joinToString("\n") { it.render() })
        val header = CppWriter.normalize(emitted.header)
        assertLines(
            header,
            "static_assert(sizeof(cnt_state) == sizeof(ext::ffi_::CntState), \"Kira's CntState no longer matches its C++ header\");",
            "KIRA_EXTERN_FIELD(cnt_state, ext::ffi_::CntState, n, std::int32_t, \"CntState.n\");",
            "KIRA_EXTERN_CHECK(cnt_get(), cnt_state, \"cntGet\");",
            "KIRA_EXTERN_CHECK((cnt_put(kira::ffi::out(std::declval<cnt_state&>())), 0), int, \"cntPut\");",
            "static_assert(sizeof(struct raw_pt) == sizeof(ext::ffi_::RawPt), \"Kira's RawPt no longer matches its C++ header\");",
            "KIRA_EXTERN_CHECK(raw_origin(), struct raw_pt, \"rawOrigin\");",
            "KIRA_EXTERN_CHECK(fopen(std::declval<const char*>(), std::declval<const char*>()), FILE*, \"fopen\");",
        )
        // A plain "CntState," or "RawPt," substring check also matches the twin's own
        // sizeof/field-check message text ("Kira's CntState no longer matches..."), which
        // names the Kira type on purpose; the regression this guards is the check or field
        // macro's *type argument* reading the Kira name instead of the C one.
        assertTrue(
            "KIRA_EXTERN_CHECK(cnt_get(), CntState, \"cntGet\");" !in header &&
                "KIRA_EXTERN_CHECK(raw_origin(), RawPt, \"rawOrigin\");" !in header &&
                "File*" !in header,
            "the Kira name never stands for the C type:\n$header",
        )
        fun fn(name: String): FnSymbol = ctx.symbol.members[name] as FnSymbol
        assertEquals("::cnt_state", ctx.spell(fn("cntGet").ret, Pos.RETURN))
        assertEquals("::cnt_state&", ctx.spell(fn("cntPut").params.single().type, Pos.MUT_PARAM))
        assertEquals("struct ::raw_pt", ctx.spell(fn("rawOrigin").ret, Pos.VALUE))
        assertEquals("::FILE*", ctx.spell(fn("fopen").ret, Pos.RETURN))
        assertEquals("::cnt_get", CppExternEmitter.globalName(fn("cntGet")))
        assertEquals("struct ::raw_pt", CppExternEmitter.globalName(ctx.symbol.members["RawPt"] as ClassSymbol))
    }

    /**
     * R-G (40-round3 2, round-2 w2-6 #7): one predicate says C++ supplies a body,
     * `FnSymbol.suppliedByCpp`, and every reader agrees with it: the typer's call kind
     * (`CallKind.EXTERN`, what the expression part dispatches on), [CppExternEmitter.isExternCall],
     * and EffectsPass (IMPURE). One callee of each kind: an `@_extern` function, an `@_extern`
     * class's method, a bodiless `pub` prototype, a bodiless method of an `@_opaque` class; and
     * three that are not: a Kira function with a body, a class's bodiless method (a slot a
     * construction fills), a trait method.
     */
    @Test
    fun everyCalleeWhoseBodyCppSuppliesIsCalledAsAnExternAndNoOther() {
        val c = callsOf(
            """
            @_extern(cpp = "probe::ext", header = "probe.hxx")
            pub fx ext: () Int32;

            @_extern(cpp = "probe::Car", header = "probe.hxx")
            pub class Car {
                pub fx speed: () Int32;
            }

            @_extern(cpp = "probe::openCar", header = "probe.hxx")
            pub fx openCar: () Car;

            pub fx proto: () Int32;

            @_opaque
            pub class Handle {
                pub fx size: () Int32;
            }

            @_extern(cpp = "probe::openHandle", header = "probe.hxx")
            pub fx openHandle: () Handle;

            fx kira: () Int32 {
                return 1
            }

            fx all: () Int32 {
                car: Car = openCar()
                h: Handle = openHandle()
                return ext() + car.speed() + proto() + h.size() + kira()
            }
            """
        )
        val supplied = mapOf("ext" to true, "speed" to true, "openCar" to true, "proto" to true, "size" to true, "openHandle" to true, "kira" to false)
        supplied.forEach { (name, want) ->
            val call = c.of(name)
            val fn = call.fn ?: fail("no callee for $name")
            assertEquals(want, fn.suppliedByCpp, "suppliedByCpp($name)")
            assertEquals(want, call.kind == net.exoad.kira.compiler.analysis.types.CallKind.EXTERN, "the call kind of $name is ${call.kind}")
            assertEquals(want, CppExternEmitter.isExternCall(call), "isExternCall($name)")
            if (want) {
                val e = c.ctx.model.calls.entries.first { it.value === call }.key
                assertEquals(net.exoad.kira.compiler.analysis.types.Effect.IMPURE, c.ctx.model.effect(e), "EffectsPass ranks $name")
            }
        }
    }

    /**
     * F2 (40-round3 R-F; round-2 w2-6 #0, r1 minor #3): a `mut p: Unsafe<T>` takes a `MutView` of
     * a local and moves nothing, so the canonical calls of design 1.4 are accepted (round 2
     * refused each as rules.view.write "'mut xs'"); with a shared `xs` the same calls are refused
     * by literal 4b; and a `MutView` formed of a variable a lambda captured is
     * `types.lambda.assign-capture` (the typer half, CallResolver), where round 1 reached g++
     * and failed there ('no matching function for call to mutView').
     */
    @Test
    fun aMutUnsafeTakesAMutViewOfALocalAndAMutViewOfACaptureIsRefused() {
        val decls = """
            @_extern(cpp = "probe::fillP", header = "probe.hxx")
            pub fx fillP: (mut p: Unsafe<Int32>, n: Size, v: Int32) Void;
            @_extern(cpp = "probe::setFirst", header = "probe.hxx")
            pub fx setFirst: (mut p: Unsafe<Int32>, v: Int32) Int32;
            pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;
            fx zero: (v: MutView<Int32>) Void {
                v[0] = 0
            }
            fx run: (f: Fx<Tuple0, Void>) Void {
                f()
            }
        """.trimIndent()
        fun codes(body: String): List<String> = TyperTestSupport.snippet(decls + "\n" + body.trimIndent()).diagnostics.map { it.code }
        val locals = codes(
            """
            fx cat: () Int32 {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                mut arr: Arr<Int32, 3> = [1, 2, 3]
                ys: List<Int32> = List<Int32> { values = [4, 5, 6] }
                fillP(xs.view(), xs.size(), 7)
                fillP(arr.view(), 3, 1)
                n: Int32 = setFirst(xs.view(), 9)
                return n + peek(ys.view(), xs.view())
            }
            """
        )
        assertTrue(locals.isEmpty(), locals.toString())
        val c = callsOf(
            decls + "\n" + """
            fx cat: () Void {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                fillP(xs.view(), xs.size(), 7)
            }
            """.trimIndent()
        )
        assertEquals("::probe::fillP((kira::mutView(xs)).data(), n, 7)", c.text("fillP", null, "kira::mutView(xs)", "n", "7"))

        val shared = codes(
            """
            mut gl: List<Int32> = List<Int32> { values = [1, 2, 3] }
            fx cat: () Void {
                fillP(gl.view(), 3, 7)
            }
            """
        )
        assertTrue("rules.view.write" in shared, shared.toString())

        val capturedIntoUnsafe = codes(
            """
            fx cat: () Void {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                run(fx() Void { fillP(xs.view(), 3, 0) })
            }
            """
        )
        assertEquals(listOf("types.lambda.assign-capture"), capturedIntoUnsafe)
        val capturedIntoMutView = codes(
            """
            fx cat: () Void {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                run(fx() Void { zero(xs.view().from(1)) })
            }
            """
        )
        assertEquals(listOf("types.lambda.assign-capture"), capturedIntoMutView)
        // A View of a capture reads it, and a lambda's own local is no capture.
        val reads = codes(
            """
            fx cat: () Void {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                run(fx() Void {
                    mut own: List<Int32> = List<Int32> { values = [1] }
                    fillP(own.view(), 1, 0)
                    trace(peek(xs.view(), own.view()))
                })
            }
            """
        )
        assertTrue("types.lambda.assign-capture" !in reads, reads.toString())
    }
}
