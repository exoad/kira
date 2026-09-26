package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
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

            @_extern(cpp = "bibo::Frame", header = "car.hxx")
            pub struct Frame {
                pub mut data: Unsafe<UInt8>
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
            "namespace ui::ffi_ { struct Frame { std::uint8_t* data; kira::Size len; }; }",
            "KIRA_EXTERN_FIELD(bibo::Frame, ui::ffi_::Frame, data, std::uint8_t*, \"Frame.data\");",
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

            @_extern(cpp = "alloc_buf", header = "probe.h")
            pub fx allocBuf: (n: Size) Unsafe<UInt8>;

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
                mut buf: Unsafe<UInt8> = allocBuf(4)
                fill(mut buf, 4)
                return hypot(3, 4) + cOnly(1)
            }
            """
        )
        // A class handle, a struct and a scalar reach Kira as the declared type (a unique_ptr, a
        // Scan& or a C `int` would pass the check); a Void and a pointer are what C++ gave.
        assertEquals("kira::ffi::declared<kira::Rc<::bibo::Car>>(::bibo::openCar())", c.text("openCar", null))
        assertEquals("kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(car))", c.text("scanOf", null, "car"))
        assertEquals("kira::ffi::declared<bool>(car->arm())", c.text("arm", "car"))
        assertEquals("car->Drive(0.1f, 0.0f)", c.text("drive", "car", "0.1f", "0.0f"))
        assertEquals("kira::ffi::declared<float>(kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(car)).ahead())", c.text("ahead", "kira::ffi::declared<::bibo::Scan>(::bibo::scanOf(car))"))
        assertEquals("kira::ffi::declared<bool>(::ImGui::SliderFloat(kira::ffi::in(\"throttle\"), kira::ffi::out(v), 0.0f, 1.0f))", c.text("sliderFloat", null, "\"throttle\"", "v", "0.0f", "1.0f"))
        assertEquals("::ImGui::GetWindowDrawList()->AddLine(7u)", c.text("addLine", "::ImGui::GetWindowDrawList()", "7u"))
        assertEquals("kira::ffi::declared<std::int32_t>(::c_hypot(3, 4))", c.text("hypot", null, "3", "4"))
        // A C name (7.3) and a mut Unsafe<T>, which is the T* itself: no out(...) around it.
        assertEquals("kira::ffi::declared<std::int32_t>(::c_only_fn(1))", c.text("cOnly", null, "1"))
        assertEquals("::fill_buf(buf, 4u)", c.text("fill", null, "buf", "4u"))
        assertEquals("::alloc_buf(4u)", c.text("allocBuf", null, "4u"))
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

            @_extern(cpp = "probe::version", header = "probe.hxx")
            pub fx version: () CStr;

            @_extern(cpp = "probe::count", header = "probe.hxx")
            pub fx count: () Int32;

            @_extern(cpp = "probe::buffer", header = "probe.hxx")
            pub fx buffer: () Unsafe<UInt8>;

            @_extern(cpp = "probe::VERSION", header = "probe.hxx")
            pub VERSION: Str;

            @_extern(cpp = "probe::LIMIT", header = "probe.hxx")
            pub LIMIT: Int32;

            fx main: () Int32 {
                s: Str = name()
                m: Maybe<Int32> = find()
                v: CStr = version()
                p: Unsafe<UInt8> = buffer()
                return count()
            }
            """
        )
        assertEquals("kira::ffi::declared<kira::Str>(::probe::name())", c.text("name", null))
        assertEquals("kira::ffi::declared<kira::Maybe<std::int32_t>>(::probe::find())", c.text("find", null))
        assertEquals("::probe::version()", c.text("version", null))
        assertEquals("kira::ffi::declared<std::int32_t>(::probe::count())", c.text("count", null))
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
            @_extern(cpp = "probe::Rec", header = "probe.hxx")
            pub struct Rec {
                pub c: Int8 = 0
                pub mode: Int32 = 0
                pub x: Float32 = 0.0
                require pub mut buf: Unsafe<UInt8>
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
        // buffer. A CStr value (an extern CStr return) passes as itself.
        //
        // The typer takes a Str for a CStr parameter of an extern function and records the
        // argument as the Str it is (CallResolver.typeGiven); it refused every one of these
        // calls with types.assign.mismatch before (measured under --target cpp), so CStr was
        // unreachable from Kira. Every call here goes through the typer.
        val c = callsOf(
            """
            @_extern(cpp = "ImGui::Text", header = "imgui.h")
            pub fx text: (s: CStr) Void;

            @_extern(cpp = "bibo::version", header = "car.hxx")
            pub fx version: () CStr;

            @_extern(cpp = "bibo::nameOf", header = "car.hxx")
            pub fx nameOf: (i: Int32) Str;

            @_extern(cpp = "bibo::BANNER", header = "car.hxx")
            pub BANNER: Str;

            pub GREETING: Str = "hi"
            pub mut TITLE: Str = "t"

            fx show: (label: Str) Void {
                text(version())
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
        assertEquals("::ImGui::Text(::bibo::version())", given("::bibo::version()", calling("version")))
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
            assertTrue(c.ctx.model.types[arg] == KType.Str || calling("version")(arg), "the argument keeps its Str type")
        }
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
        val messages = program.diagnostics.map { it.message }
        assertEquals(1, messages.count { it.contains("expects CStr, but this is Str") }, messages.toString())
        assertEquals(1, program.diagnostics.size, messages.toString())
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
}
