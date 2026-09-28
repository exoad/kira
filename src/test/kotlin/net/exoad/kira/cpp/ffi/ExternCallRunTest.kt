package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R-B (40-round3 2, group B): a by-value argument that C++ receives by reference is copied at
 * the call exactly when the call may write its storage while it runs, and R-G: a bodiless `pub`
 * prototype is called through [CppExternEmitter.call] like an `@_extern`.
 *
 * Every row is one call of the module below. Its text is what [CppExternEmitter.call] writes
 * (the expression part hands it the argument texts, spelled here as the driver's C++ names), and
 * the driver runs each text against a C++ callee that runs the callbacks it is given (or writes
 * its `mut` argument) and then reads the argument. The value each row prints is Kira's, written
 * from the semantics (Kira passes the argument by value, so the callee reads it as it was when
 * the call began). Before this rule (round-2 verdict, g++/clang/MSVC ASan): `lenAfter(gstrs.get(0),
 * ...)` 0 for 82 or a heap-use-after-free, `sumListL(gl, fs)` 21 for 6, `lenAfterCL(gs, fs)` and
 * `appendLenC(gs, mut o)` 6 for 82, `appendLen(gs, mut o)` inside `viaMut(mut gs)` 1 for 82, and
 * `peek(w, v)` / `plen(loc)` did not compile.
 */
class ExternCallRunTest {
    private val runtime = File("kira/cpp")
    private val uri = "app:w"

    private val module = """
        @_extern(cpp = "nat::lenAfterL", header = "nat.hxx")
        pub fx lenAfterL: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::lenAfterCL", header = "nat.hxx")
        pub fx lenAfterCL: (s: CStr, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::lenAfterCb", header = "nat.hxx")
        pub fx lenAfterCb: (s: Str, c: Cb) Int32;

        @_extern(cpp = "nat::appendLen", header = "nat.hxx")
        pub fx appendLen: (s: Str, mut out: Str) Int32;

        @_extern(cpp = "nat::appendLenC", header = "nat.hxx")
        pub fx appendLenC: (s: CStr, mut out: Str) Int32;

        @_extern(cpp = "nat::appendLenI", header = "nat.hxx")
        pub fx appendLenI: (s: Str, mut n: Int32) Int32;

        @_extern(cpp = "nat::sumListL", header = "nat.hxx")
        pub fx sumListL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::sumArrL", header = "nat.hxx")
        pub fx sumArrL: (a: Arr<Int32, 3>, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::lenMaybeL", header = "nat.hxx")
        pub fx lenMaybeL: (m: Maybe<Str>, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::ptLenL", header = "nat.hxx")
        pub fx ptLenL: (p: Pt, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::boxLenL", header = "nat.hxx")
        pub fx boxLenL: (b: Box, fs: List<Fx<Tuple0, Void>>) Int32;

        @_extern(cpp = "nat::lenS", header = "nat.hxx")
        pub fx lenS: (s: Str) Int32;

        @_extern(cpp = "nat::Box", header = "nat.hxx")
        pub class Box {
            pub fx size: () Int32;
        }

        @_extern(cpp = "nat::Pump", header = "nat.hxx")
        pub class Pump {
            pub mut fx lenAfter: (s: Str) Int32;
        }

        pub struct Pt {
            pub name: Str = ""
            pub n: Int32 = 0
        }

        pub struct Cb {
            require pub f: Fx<Tuple0, Void>
        }

        pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;

        pub fx plen: (s: CStr) Int32;

        mut GS: Str = "$LONG"
        mut GL: List<Int32> = List<Int32> { values = [1, 2, 3] }
        mut GA: Arr<Int32, 3> = [1, 2, 3]
        mut GM: Maybe<Str> = "$LONG"
        mut GP: Pt = Pt { name = "$LONG", n = 1 }
        mut GSTRS: List<Str> = List<Str> { values = ["$LONG"] }
        GREETING: Str = "hi"

        fx sGlobal: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(GS, fs) }
        fx sLent: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(GSTRS.get(0), fs) }
        fx sField: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(GP.name, fs) }
        fx sParam: (p: Str, fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(p, fs) }
        fx sLiteral: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL("abc", fs) }
        fx sConstant: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(GREETING, fs) }
        fx sComputed: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterL(GS + "!", fs) }
        fx sStructFx: (c: Cb) Int32 { return lenAfterCb(GS, c) }
        fx sReceiver: (pump: Pump) Int32 { return pump.lenAfter(GS) }
        fx cCStr: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenAfterCL(GS, fs) }
        fx cList: (fs: List<Fx<Tuple0, Void>>) Int32 { return sumListL(GL, fs) }
        fx cArr: (fs: List<Fx<Tuple0, Void>>) Int32 { return sumArrL(GA, fs) }
        fx cMaybe: (fs: List<Fx<Tuple0, Void>>) Int32 { return lenMaybeL(GM, fs) }
        fx cStruct: (fs: List<Fx<Tuple0, Void>>) Int32 { return ptLenL(GP, fs) }
        fx cClass: (b: Box, fs: List<Fx<Tuple0, Void>>) Int32 { return boxLenL(b, fs) }
        fx mViaMut: (mut o: Str) Int32 { return appendLen(GS, mut o) }
        fx mCStr: (mut o: Str) Int32 { return appendLenC(GS, mut o) }
        fx nUnrelated: (mut n: Int32) Int32 { return appendLenI(GS, mut n) }
        fx nNone: () Int32 { return lenS(GS) }
        fx gPeek: (v: MutView<Int32>, w: View<Int32>) Int32 { return peek(w, v) }
        fx gPlen: () Int32 {
            loc: Str = "abcd"
            return plen(loc) + plen("xy") * 10
        }
    """

    /**
     * One call: the callee, the Kira spelling of its first argument (which picks the call),
     * the argument texts the driver names, the text [CppExternEmitter.call] must write, the
     * C++ statements that set the state up (and install the writer), and Kira's value.
     */
    private data class Row(val name: String, val callee: String, val first: String, val args: List<String>, val receiver: String?, val text: String, val setup: String, val value: Int)

    private val rows = listOf(
        // ---- condition 1: the call may run any Kira code (an Fx in a List, in a struct field, a class receiver) ----
        Row("sGlobal", "lenAfterL", "GS", listOf("gs", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(kira::Str(gs)), kira::List<kira::Fn<void()>>(fs)))",
            "gs = LONG; Fs fs{[] { gs = kira::Str(LONGER) + gs; }};", 82),
        Row("sLent", "lenAfterL", "GSTRS.get(0)", listOf("kira::at(gstrs, 0)", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(kira::Str(kira::at(gstrs, 0))), kira::List<kira::Fn<void()>>(fs)))",
            "gstrs = {kira::Str(LONG)}; Fs fs{[] { gstrs = {kira::Str(LONGER)}; }};", 82),
        Row("sField", "lenAfterL", "GP.name", listOf("gp.name", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(kira::Str(gp.name)), kira::List<kira::Fn<void()>>(fs)))",
            "gp = w::Pt{LONG, 1}; Fs fs{[] { gp = w::Pt{kira::Str(LONGER), 2}; }};", 82),
        Row("sParam", "lenAfterL", "p", listOf("p", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(kira::Str(p)), kira::List<kira::Fn<void()>>(fs)))",
            "gs = LONG; const kira::Str& p = gs; Fs fs{[] { gs = LONGER; }};", 82),
        Row("sLiteral", "lenAfterL", "\"abc\"", listOf("\"abc\"", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(\"abc\"), kira::List<kira::Fn<void()>>(fs)))",
            "Fs fs{[] { gs = \"\"; }};", 3),
        Row("sConstant", "lenAfterL", "GREETING", listOf("greeting", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(greeting), kira::List<kira::Fn<void()>>(fs)))",
            "Fs fs{[] { gs = \"\"; }};", 2),
        Row("sComputed", "lenAfterL", "GS + \"!\"", listOf("gs + \"!\"", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterL(kira::ffi::in(gs + \"!\"), kira::List<kira::Fn<void()>>(fs)))",
            "gs = LONG; Fs fs{[] { gs = \"\"; }};", 83),
        Row("sStructFx", "lenAfterCb", "GS", listOf("gs", "c"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterCb(kira::ffi::in(kira::Str(gs)), Cb(c)))",
            "gs = LONG; const w::Cb c{[] { gs = LONGER; }};", 82),
        Row("sReceiver", "lenAfter", "GS", listOf("gs"), "pump",
            "kira::ffi::declared<std::int32_t>(pump->lenAfter(kira::ffi::in(kira::Str(gs))))",
            "gs = LONG; auto pump = std::make_shared<nat::Pump>(); pump->onPump = [] { gs = LONGER; };", 82),
        Row("cCStr", "lenAfterCL", "GS", listOf("gs", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenAfterCL(kira::ffi::CStrBuf(gs).c_str(), kira::List<kira::Fn<void()>>(fs)))",
            "gs = LONG; Fs fs{[] { gs = kira::Str(LONGER) + gs; }};", 82),
        Row("cList", "sumListL", "GL", listOf("gl", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::sumListL(kira::List<std::int32_t>(gl), kira::List<kira::Fn<void()>>(fs)))",
            "gl = {1, 2, 3}; Fs fs{[] { gl = kira::List<std::int32_t>(9, 7); }};", 6),
        Row("cArr", "sumArrL", "GA", listOf("ga", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::sumArrL(std::array<std::int32_t, 3>(ga), kira::List<kira::Fn<void()>>(fs)))",
            "ga = {1, 2, 3}; Fs fs{[] { ga = {7, 7, 7}; }};", 6),
        Row("cMaybe", "lenMaybeL", "GM", listOf("gm", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenMaybeL(kira::Maybe<kira::Str>(gm), kira::List<kira::Fn<void()>>(fs)))",
            "gm = kira::Str(LONG); Fs fs{[] { gm = std::nullopt; }};", 82),
        Row("cStruct", "ptLenL", "GP", listOf("gp", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::ptLenL(Pt(gp), kira::List<kira::Fn<void()>>(fs)))",
            "gp = w::Pt{LONG, 1}; Fs fs{[] { gp = w::Pt{\"x\", 2}; }};", 82),
        Row("cClass", "boxLenL", "b", listOf("b", "fs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::boxLenL(kira::Rc<::nat::Box>(b), kira::List<kira::Fn<void()>>(fs)))",
            "auto b = std::make_shared<nat::Box>(82); Fs fs{[&b] { b = std::make_shared<nat::Box>(1); }};", 82),
        // ---- condition 2: a mut argument whose type may hold the argument's storage ----
        Row("mViaMut", "appendLen", "GS", listOf("gs", "o"), null,
            "kira::ffi::declared<std::int32_t>(::nat::appendLen(kira::ffi::in(kira::Str(gs)), kira::ffi::out(o)))",
            "gs = LONG; kira::Str& o = gs;", 82),
        Row("mCStr", "appendLenC", "GS", listOf("gs", "o"), null,
            "kira::ffi::declared<std::int32_t>(::nat::appendLenC(kira::ffi::CStrBuf(gs).c_str(), kira::ffi::out(o)))",
            "gs = LONG; kira::Str& o = gs;", 82),
        // ---- no copy: a mut argument of an unrelated type, and nothing that writes ----
        Row("nUnrelated", "appendLenI", "GS", listOf("gs", "n"), null,
            "kira::ffi::declared<std::int32_t>(::nat::appendLenI(kira::ffi::in(gs), kira::ffi::out(n)))",
            "gs = LONG; std::int32_t n = 0;", 82),
        Row("nNone", "lenS", "GS", listOf("gs"), null,
            "kira::ffi::declared<std::int32_t>(::nat::lenS(kira::ffi::in(gs)))",
            "gs = LONG;", 82),
        // ---- R-G: bodiless pub prototypes (round-2 a5b, a5c): Kira's own declaration, its own spelling ----
        Row("gPeek", "peek", "w", listOf("wv", "mv"), null,
            "peek((wv).data(), (mv).data())",
            "kira::List<std::int32_t> ys{10, 20}; kira::List<std::int32_t> xs{1, 2}; const kira::View<std::int32_t> wv = kira::view(ys); const kira::MutView<std::int32_t> mv = kira::mutView(xs);", 10),
        Row("gPlen", "plen", "loc", listOf("loc"), null,
            "plen(loc.c_str())",
            "const kira::Str loc = \"abcd\";", 4),
    )

    private fun emitted(): Pair<String, CppEmitContextImpl> {
        val (em, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module(uri, module), uri = uri, options = CppOptions(lineDirectives = false))
        val errors = em.diagnostics.filter { it.isError && "is not lowered yet" !in it.message && "the body of" !in it.message }
        assertTrue(errors.isEmpty(), "errors:\n" + errors.joinToString("\n") { it.render() })
        return CppWriter.normalize(em.header) to ctx
    }

    private fun callOf(ctx: CppEmitContextImpl, callee: String, first: String): ResolvedCall =
        ctx.model.calls.values.firstOrNull { rc ->
            rc.fn?.name == callee && (rc.args.firstOrNull() as? ArgBinding.Given)?.expr?.let { KiraUnparser.text(it) } == first
        } ?: fail("no call $callee($first, ...) in the model; calls: " + ctx.model.calls.values.map { "${it.fn?.name}(${(it.args.firstOrNull() as? ArgBinding.Given)?.expr?.let { e -> KiraUnparser.text(e) }})" })

    private fun textOf(ctx: CppEmitContextImpl, row: Row): String = CppExternEmitter.call(ctx, callOf(ctx, row.callee, row.first), row.receiver, row.args)

    @Test
    fun aByReferenceArgumentIsCopiedExactlyWhenTheCallMayWriteItsStorage() {
        val (_, ctx) = emitted()
        val wrong = rows.mapNotNull { row ->
            val got = textOf(ctx, row)
            if (got == row.text) null else "${row.name}:\n  want ${row.text}\n  got  $got"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun theChecksFollowTheModulesOwnDeclarationsAndNameThemFromTheGlobalScope() {
        // w2-4 round-2 minor #3 (externnested2): an extern taking a class or struct this module
        // declares was checked at the top of the header with the bare name.
        val (header, _) = emitted()
        val lines = header.lines()
        val decl = lines.indexOfFirst { it.trim() == "struct Pt" }
        val check = lines.indexOfFirst { it.startsWith("KIRA_EXTERN_CHECK(nat::ptLenL(") }
        assertTrue(decl >= 0 && check > decl, "the check of ptLenL comes after struct Pt:\n$header")
        assertTrue(lines[check].contains("std::declval<const w::Pt&>()"), lines[check])
        assertTrue(lines.any { it.startsWith("KIRA_EXTERN_CHECK(nat::lenAfterCb(") && it.contains("std::declval<const w::Cb&>()") }, header)
    }

    private val nat = """
        #pragma once
        #include <array>
        #include <cstdint>
        #include <cstring>
        #include <functional>
        #include <memory>
        #include <optional>
        #include <string>
        #include <vector>

        namespace nat
        {
            template<class L>
            inline void runAll(const L& fs)
            {
                for (const auto& f : fs)
                {
                    f();
                }
            }
            template<class L>
            inline std::int32_t lenAfterL(const std::string& s, const L& fs)
            {
                runAll(fs);
                return static_cast<std::int32_t>(s.size());
            }
            template<class L>
            inline std::int32_t lenAfterCL(const char* s, const L& fs)
            {
                runAll(fs);
                return static_cast<std::int32_t>(std::strlen(s));
            }
            template<class C>
            inline std::int32_t lenAfterCb(const std::string& s, const C& c)
            {
                c.f();
                return static_cast<std::int32_t>(s.size());
            }
            inline std::int32_t appendLen(const std::string& s, std::string& out)
            {
                out = "x";
                return static_cast<std::int32_t>(s.size());
            }
            inline std::int32_t appendLenC(const char* s, std::string& out)
            {
                out = "a much longer string, so that the assignment cannot reuse the old heap buffer at all, and longer still";
                return static_cast<std::int32_t>(std::strlen(s));
            }
            inline std::int32_t appendLenI(const std::string& s, std::int32_t& n)
            {
                n = 5;
                return static_cast<std::int32_t>(s.size());
            }
            template<class L>
            inline std::int32_t sumListL(const std::vector<std::int32_t>& xs, const L& fs)
            {
                runAll(fs);
                return xs[0] + xs[1] + xs[2];
            }
            template<class A, class L>
            inline std::int32_t sumArrL(const A& a, const L& fs)
            {
                runAll(fs);
                std::int32_t s = 0;
                for (auto x : a)
                {
                    s += x;
                }
                return s;
            }
            template<class M, class L>
            inline std::int32_t lenMaybeL(const M& m, const L& fs)
            {
                runAll(fs);
                return m.has_value() ? static_cast<std::int32_t>(m->size()) : -1;
            }
            template<class P, class L>
            inline std::int32_t ptLenL(const P& p, const L& fs)
            {
                runAll(fs);
                return static_cast<std::int32_t>(p.name.size());
            }
            inline std::int32_t lenS(const std::string& s)
            {
                return static_cast<std::int32_t>(s.size());
            }
            class Box
            {
            public:
                explicit Box(std::int32_t n) : n_(n) {}
                std::int32_t size() const { return n_; }
            private:
                std::int32_t n_;
            };
            template<class B, class L>
            inline std::int32_t boxLenL(const B& b, const L& fs)
            {
                runAll(fs);
                return b->size();
            }
            class Pump
            {
            public:
                std::function<void()> onPump;
                std::int32_t lenAfter(const std::string& s)
                {
                    onPump();
                    return static_cast<std::int32_t>(s.size());
                }
            };
        }
    """.trimIndent() + "\n"

    private fun driver(ctx: CppEmitContextImpl): String = buildString {
        appendLine("#include \"w.kira.hxx\"")
        appendLine("#include <cstdio>")
        appendLine()
        appendLine("static const char* const LONG = \"$LONG\";")
        appendLine("static const char* const LONGER = \"$LONGER\";")
        appendLine("static kira::Str gs;")
        appendLine("static kira::List<std::int32_t> gl;")
        appendLine("static std::array<std::int32_t, 3> ga;")
        appendLine("static kira::Maybe<kira::Str> gm;")
        appendLine("static w::Pt gp;")
        appendLine("static kira::List<kira::Str> gstrs;")
        appendLine("static const char* const greeting = \"hi\";")
        appendLine("using Fs = kira::List<kira::Fn<void()>>;")
        appendLine()
        appendLine("namespace w")
        appendLine("{")
        appendLine("    std::int32_t peek(const std::int32_t* p, std::int32_t* q) { q[0] = p[0]; return p[0]; }")
        appendLine("    std::int32_t plen(const char* s) { return static_cast<std::int32_t>(std::strlen(s)); }")
        rows.forEach { row ->
            appendLine("    static std::int32_t row_${row.name}() { ${row.setup} return ${textOf(ctx, row)}; }")
        }
        appendLine("}")
        appendLine()
        appendLine("int main()")
        appendLine("{")
        rows.forEach { row -> appendLine("    std::printf(\"${row.name} %d\\n\", static_cast<int>(w::row_${row.name}()));") }
        appendLine("    return 0;")
        appendLine("}")
    }

    @TestFactory
    fun everyRowPrintsKirasValueOnEveryHostToolchain(): List<DynamicTest> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("${tc.id}: every row prints Kira's value") { run(tc) }
        }

    private fun run(tc: CppToolchain) {
        org.junit.jupiter.api.Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(tc)
        val (header, ctx) = emitted()
        val dir = File("build/tmp/cpp-ffi-rb/${tc.id}").apply { deleteRecursively(); mkdirs() }
        File(dir, "w.kira.hxx").writeText(header)
        File(dir, "nat.hxx").writeText(nat)
        File(dir, "main.cxx").writeText(driver(ctx))
        val result = CppCompileSupport.compile(
            sources = listOf(File(dir, "main.cxx")),
            includeDirs = listOf(dir, runtime),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.forToolchain(tc),
            outDir = File(dir, "out"),
        )
        if (!result.success) {
            fail("${tc.id}: the rows do not build:\n${result.describe()}")
        }
        val exe = result.exe ?: fail("${tc.id}: reported success without an executable")
        val ran = CppCompileSupport.run(exe, extraPathDirs = listOfNotNull(located.binDir))
        assertTrue(!ran.timedOut, "${tc.id}: the program timed out")
        assertEquals(0, ran.exitCode, "${tc.id}: exited ${ran.exitCode}\nstderr:\n${ran.stderr}")
        val want = rows.joinToString("\n") { "${it.name} ${it.value}" }
        assertEquals(want, ran.stdout.trim().replace("\r\n", "\n"), "${tc.id}: a row printed something other than Kira's value")
    }

    private companion object {
        const val LONG = "hello, world, a string long enough to live on the heap and not in the small buffer"
        const val LONGER = "a much longer string, so that the assignment cannot reuse the old heap buffer at all: "
    }
}
