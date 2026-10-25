package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
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
    fun aCSymbolIsReachedByItsCNameThroughAnExternCInclude() {
        // Design 7.3: `c =` alone names a C function, callable from C++ under that name; its
        // header is included with C linkage, which a header without a __cplusplus guard needs
        // and one with a guard tolerates. A `cpp =` beside it wins for this backend.
        val h = header(
            """
            pub @_extern(cpp = "ImGui::Text", header = "imgui.h") text: (s: CStr) Void;

            pub @_extern(c = "c_only_fn", header = "probe.h") cOnly: (a: Int32) Int32;

            pub @_extern(c = "c_len", header = "probe.h") cLen: (s: Str) Size;

            pub @_extern(c = "both_c", cpp = "both::cpp", header = "both.hxx") both: () Int32;
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
    fun anExternConstantIsCheckedAgainstItsType() {
        val h = header(
            """
            pub @_extern(cpp = "ImGuiWindowFlags_NoTitleBar", header = "imgui.h") NO_TITLE_BAR: Int32;

            pub @_extern(cpp = "bibo::VERSION", header = "car.hxx") VERSION: Str;
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
            pub @_extern(cpp = "::bibo::Car", header = "car.hxx") Car: Any {
                pub ok: () Bool;
            }
            pub @_extern(cpp = "bibo::openCar", header = "car.hxx") openCar: () Car;
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
    fun aKiraDefaultOnAnExternParameterIsRefused() {
        // The typer accepts `width: Float32 = 10.0` on an extern declaration (measured), but the
        // C++ side would never see it: KIRA_EXTERN_CHECK cannot check a default, and a call
        // that leaves it out would take whatever the C++ header's own default is. Refused at
        // the declaration, so a call's bindings are always given arguments.
        val errors = errorsOf(
            """
            pub @_extern(cpp = "ImGui::Button", header = "imgui.h") button: (label: Str, width: Float32 = 10.0) Bool;

            main: () Void {
                pressed: Bool = button("go")
            }
            """
        )
        assertTrue(errors.any { it.contains("the default of parameter 'width' of the extern function 'button'") }, errors.toString())
        assertTrue(errors.any { it.contains("leave it out") }, errors.toString())
    }

    // ---- calls and constants: what the expression part asks for ---------------------------------

    /**
     * [CppExternEmitter.call] over a module's typed calls, with argument texts written by the
     * test. Which argument is copied is the copy policy's (W2.3), handed over as `copied`:
     * [text] passes none, [copiedText] the indices given; the policy's own decision is what the
     * lowered body says ([source]).
     */
    private class Calls(val ctx: CppEmitContextImpl, val source: String) {
        fun of(name: String): ResolvedCall = ctx.model.calls.values.firstOrNull { it.fn?.name == name } ?: fail("no call of '$name' in the model")
        fun text(name: String, receiver: String?, vararg args: String): String = CppExternEmitter.call(ctx, of(name), receiver, args.toList(), emptySet())
        fun copiedText(name: String, copied: Set<Int>, receiver: String?, vararg args: String): String = CppExternEmitter.call(ctx, of(name), receiver, args.toList(), copied)
    }

    private fun callsOf(body: String): Calls {
        val (emitted, ctx) = emit(body)
        return Calls(ctx, CppWriter.normalize(emitted.source.orEmpty()))
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
            pub @_extern(cpp = "probe::name", header = "probe.hxx") name: () Str;

            pub @_extern(cpp = "probe::find", header = "probe.hxx") find: () Maybe<Int32>;

            pub @_extern(cpp = "probe::count", header = "probe.hxx") count: () Int32;

            pub @_opaque @_extern(cpp = "probe::Buffer", header = "probe.hxx") Buffer: Any {
            }

            pub @_extern(cpp = "probe::buffer", header = "probe.hxx") buffer: () Buffer;

            pub @_extern(cpp = "probe::VERSION", header = "probe.hxx") VERSION: Str;

            pub @_extern(cpp = "probe::LIMIT", header = "probe.hxx") LIMIT: Int32;

            main: () Int32 {
                s: Str = name()
                m: Maybe<Int32> = find()
                p: Buffer = buffer()
                count()
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
            pub @_extern(cpp = "probe::put", header = "probe.hxx") put: (m: Maybe<Int32>) Void;

            pub @_extern(cpp = "probe::onTick", header = "probe.hxx") onTick: (f: Fx<Tuple1<Int32>, Void>) Void;

            pub @_extern(cpp = "probe::ticker", header = "probe.hxx") ticker: () Fx<Tuple1<Int32>, Int32>;

            pub @_extern(cpp = "probe::find", header = "probe.hxx") find: () Maybe<Int32>;

            pub @_extern(cpp = "ImVec2", header = "imgui.h") Vec2: Struct {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }

            pub @_extern(cpp = "ImGui::Dummy", header = "imgui.h") dummy: (size: Vec2) Void;
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
        CppExternEmitter.call(c.ctx, call, receiver, args.toList(), emptySet())
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
            pub @_extern(cpp = "probe::after", header = "probe.hxx") after: (s: CStr, c: Int32) Int32;
            cat: () Void {
                r: Int32 = after("literal", 1)
            }
            """,
            "after", null, "\"literal\"", "1",
        )
        assertTrue(literal.isEmpty(), literal.toString())

        val namedCStr = diagsOf(
            """
            pub @_extern(cpp = "probe::after", header = "probe.hxx") after: (s: CStr, c: Int32) Int32;
            cat: (a: Str) Void {
                r: Int32 = after(a, 1)
            }
            """,
            "after", null, "a", "1",
        )
        assertTrue(namedCStr.isEmpty(), namedCStr.toString())

        val constantCStr = diagsOf(
            """
            pub @_extern(cpp = "probe::after", header = "probe.hxx") after: (s: CStr, c: Int32) Int32;
            pub GREETING: Str = "hi"
            cat: () Void {
                r: Int32 = after(GREETING, 1)
            }
            """,
            "after", null, "::ext::GREETING", "1",
        )
        assertTrue(constantCStr.isEmpty(), constantCStr.toString())

        val namedStr = diagsOf(
            """
            pub @_extern(cpp = "probe::afterS", header = "probe.hxx") afterS: (s: Str, c: Int32) Int32;
            cat: (a: Str) Void {
                r: Int32 = afterS(a, 1)
            }
            """,
            "afterS", null, "a", "1",
        )
        assertTrue(namedStr.isEmpty(), namedStr.toString())

        // Against a call whose result carries no pointer at all, a literal was always fine too.
        val literalIntoScalarResult = diagsOf(
            """
            pub @_extern(cpp = "probe::lengthOfS", header = "probe.hxx") lengthOfS: (s: Str) Int32;
            cat: () Void {
                n: Int32 = lengthOfS("harmless")
            }
            """,
            "lengthOfS", null, "\"harmless\"",
        )
        assertTrue(literalIntoScalarResult.isEmpty(), literalIntoScalarResult.toString())
    }

    @Test
    fun aStrIsNoCStrAnywhereElse() {
        // Only an extern function has a CStr parameter to fill; the typer's rule is scoped to
        // the extern callee, so a Kira function declared with CStr still refuses a Str.
        val program = TyperTestSupport.snippet(
            """
            pub @_extern(cpp = "bibo::version", header = "car.hxx") version: () CStr;

            own: (s: CStr) Void { }

            show: () Void {
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
            pub @_extern(cpp = "ImGuiWindowFlags_NoTitleBar", header = "imgui.h") NO_TITLE_BAR: Int32;

            pub @_extern(cpp = "::bibo::LIMIT", header = "car.hxx") LIMIT: Int32;

            pub @_extern(c = "C_LIMIT", header = "limits.h") C_LIMIT: Int32;

            pub @_extern(c = "C_VERSION", header = "limits.h") C_VERSION: Str;
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
            pub @_extern(cpp = "probe::ext", header = "probe.hxx") ext: () Int32;

            pub @_extern(cpp = "probe::Car", header = "probe.hxx") Car: Any {
                pub speed: () Int32;
            }

            pub @_extern(cpp = "probe::openCar", header = "probe.hxx") openCar: () Car;

            pub proto: () Int32;

            pub @_opaque Handle: Any {
                pub size: () Int32;
            }

            pub @_extern(cpp = "probe::openHandle", header = "probe.hxx") openHandle: () Handle;

            kira: () Int32 {
                1
            }

            all: () Int32 {
                car: Car = openCar()
                h: Handle = openHandle()
                ext() + car.speed() + proto() + h.size() + kira()
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
}
