package net.exoad.kira.cpp.ffi

import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Round 6's w2-6 finding: a literal given to an `@_extern` or `@_opaque` argument reached C++ as
 * C++'s own literal (`5` is an `int`, `"s"` a `const char[2]`), while the drift check states the
 * declared type (`q::w(kira::ffi::arg<std::int64_t>())`). Against an overload set or a template
 * the emitted call was then another call than the checked one. The rows are the round-6
 * verifier's probes lit (all 20 rows) and lit2 (all 6), over its q.hxx and l2.hxx verbatim; its
 * a2_fx's `@_opaque` row (`r3.w(5)`, as `bx.w(5)`); round 7's l3 rows for the other literal kinds;
 * and literals at the edges of their types. Each row's reason gives the value before the fix,
 * measured on g++, clang and MSVC alike with the fix taken out.
 */
class ExternLiteralRunTest {
    private val module = Module(
        "app:lit",
        """
        LIM: Int64 = 5

        mut gc: Bool = true

        @_extern(cpp = "q::w", header = "q.hxx")
        pub fx w: (x: Int64) Int32;

        @_extern(cpp = "q::u", header = "q.hxx")
        pub fx u: (x: UInt64) Int32;

        @_extern(cpp = "q::b", header = "q.hxx")
        pub fx b: (x: Int8) Int32;

        @_extern(cpp = "q::ub", header = "q.hxx")
        pub fx ub: (x: UInt8) Int32;

        @_extern(cpp = "q::h", header = "q.hxx")
        pub fx h: (x: Int16) Int32;

        @_extern(cpp = "q::sz", header = "q.hxx")
        pub fx sz: (x: Size) Int32;

        @_extern(cpp = "q::f", header = "q.hxx")
        pub fx f: (x: Float32) Int32;

        @_extern(cpp = "q::width", header = "q.hxx")
        pub fx width64: (x: Int64) Int32;

        @_extern(cpp = "q::width", header = "q.hxx")
        pub fx width8: (x: Int8) Int32;

        @_extern(cpp = "q::width", header = "q.hxx")
        pub fx width16: (x: UInt16) Int32;

        @_extern(cpp = "l2::w", header = "l2.hxx")
        pub fx lw: (x: Int64) Int32;

        @_extern(cpp = "l2::width", header = "l2.hxx")
        pub fx lwidth64: (x: Int64) Int32;

        @_extern(cpp = "l2::u32", header = "l2.hxx")
        pub fx u32: (x: UInt32) Int32;

        @_extern(cpp = "l2::mwidth", header = "l2.hxx")
        pub fx mwidth: (m: Maybe<Int64>) Int32;

        @_extern(cpp = "l3::f", header = "l3.hxx")
        pub fx f64: (x: Float64) Int32;

        @_extern(cpp = "l3::isF64", header = "l3.hxx")
        pub fx isF64: (x: Float64) Int32;

        @_extern(cpp = "l3::isBool", header = "l3.hxx")
        pub fx isBool: (x: Bool) Int32;

        @_extern(cpp = "l3::isChar", header = "l3.hxx")
        pub fx isChar: (x: Char) Int32;

        @_extern(cpp = "l3::isCStr", header = "l3.hxx")
        pub fx isCStr: (x: CStr) Int32;

        @_extern(cpp = "l3::isU8", header = "l3.hxx")
        pub fx isU8: (x: UInt8) Int32;

        @_opaque @_extern(cpp = "l3::Box", header = "l3.hxx")
        pub class Box {
            pub fx w: (x: Int64) Int32;
            pub fx width: (x: Int64) Int32;
        }

        @_extern(cpp = "l3::newBox", header = "l3.hxx")
        pub fx newBox: () Box;

        pub fx litW: () Int32 {
            return w(5)
        }

        pub fx litU: () Int32 {
            return u(5)
        }

        pub fx litB: () Int32 {
            return b(5)
        }

        pub fx litUb: () Int32 {
            return ub(5)
        }

        pub fx litH: () Int32 {
            return h(5)
        }

        pub fx litSz: () Int32 {
            return sz(5)
        }

        pub fx litF: () Int32 {
            return f(1.5)
        }

        pub fx litWidth64: () Int32 {
            return width64(5)
        }

        pub fx litWidth8: () Int32 {
            return width8(5)
        }

        pub fx litWidth16: () Int32 {
            return width16(5)
        }

        pub fx namedW: () Int32 {
            n: Int64 = 5
            return w(n)
        }

        pub fx namedB: () Int32 {
            x: Int8 = 3
            return b(x)
        }

        pub fx sumW: () Int32 {
            n: Int64 = 5
            return w(n + 1)
        }

        pub fx sumB: () Int32 {
            x: Int8 = 3
            y: Int8 = 4
            return b(x + y)
        }

        pub fx sumUb: () Int32 {
            ux: UInt8 = 3
            return ub(ux + ux)
        }

        pub fx mulH: () Int32 {
            hx: Int16 = 3
            return h(hx * hx)
        }

        pub fx sumWidth8: () Int32 {
            x: Int8 = 3
            y: Int8 = 4
            return width8(x + y)
        }

        pub fx mulWidth64: () Int32 {
            n: Int64 = 5
            return width64(n * 2)
        }

        pub fx negB: () Int32 {
            x: Int8 = 3
            return b(-x)
        }

        pub fx negWidth8: () Int32 {
            x: Int8 = 3
            return width8(-x)
        }

        pub fx negW: () Int32 {
            return lw(-5)
        }

        pub fx ifW: () Int32 {
            return lw(if gc { 5 } else { 6 })
        }

        pub fx constW: () Int32 {
            return lw(LIM)
        }

        pub fx litU32: () Int32 {
            return u32(5)
        }

        pub fx wrappedWidth: () Int32 {
            return mwidth(5)
        }

        pub fx lit2Width64: () Int32 {
            return lwidth64(7)
        }

        pub fx edgeUb: () Int32 {
            return ub(255)
        }

        pub fx edgeB: () Int32 {
            return b(-128) + b(127)
        }

        pub fx edgeH: () Int32 {
            return h(-32768)
        }

        pub fx edgeU: () Int32 {
            return u(9223372036854775807)
        }

        pub fx edgeW: () Int32 {
            return w(-9223372036854775807 - 1) + w(9223372036854775807)
        }

        pub fx edgeF: () Int32 {
            return f(3.4e38)
        }

        pub fx edgeSz: () Int32 {
            return sz(0)
        }

        pub fx sumLitW: () Int32 {
            return w(2 + 3)
        }

        pub fx litF64: () Int32 {
            return f64(1.5)
        }

        pub fx litIsF64: () Int32 {
            return isF64(2)
        }

        pub fx litIsBool: () Int32 {
            return isBool(true)
        }

        pub fx litIsChar: () Int32 {
            return isChar('a')
        }

        pub fx litIsCStr: () Int32 {
            return isCStr("abc")
        }

        pub fx litIsU8: () Int32 {
            return isU8(200)
        }

        pub fx boxW: () Int32 {
            bx: Box = newBox()
            return bx.w(5)
        }

        pub fx boxWidth: () Int32 {
            bx: Box = newBox()
            return bx.width(5)
        }
        """,
    )

    /**
     * One row: the function, Kira's value, the call its body must contain (null for a control row
     * whose argument is no literal, checked by value only), and why, with the value before the fix.
     */
    private data class Row(val fn: String, val value: Int, val text: String?, val why: String)

    private val rows = listOf(
        // round 6's lit, all 20 of its rows: an overload set on the int32 and the declared type, then a template on width
        Row("litW", 64, "::q::w(std::int64_t{5})", "an Int64 literal takes the int64 overload the check states (32 before)"),
        Row("litU", 64, "::q::u(std::uint64_t{5})", "a UInt64 literal (32 before)"),
        Row("litB", 8, "::q::b(std::int8_t{5})", "an Int8 literal (32 before)"),
        Row("litUb", 8, "::q::ub(std::uint8_t{5})", "a UInt8 literal (32 before)"),
        Row("litH", 16, "::q::h(std::int16_t{5})", "an Int16 literal (32 before)"),
        Row("litSz", 64, "::q::sz(kira::Size{5})", "a Size literal (32 before)"),
        Row("litF", 32, "::q::f(float{1.5f})", "a Float32 literal (32 before too: 1.5f is a float)"),
        Row("litWidth64", 8, "::q::width(std::int64_t{5})", "a template sees the Int64 (4 before)"),
        Row("litWidth8", 1, "::q::width(std::int8_t{5})", "a template sees the Int8 (4 before)"),
        Row("litWidth16", 2, "::q::width(std::uint16_t{5})", "a template sees the UInt16 (4 before)"),
        Row("namedW", 64, "::q::w(n)", "a named Int64 is passed as itself (64 before too)"),
        Row("namedB", 8, "::q::b(x)", "a named Int8 is passed as itself (8 before too)"),
        Row("sumW", 64, null, "Int64 arithmetic is an int64 (64 before too)"),
        Row("sumB", 8, "::q::b(static_cast<std::int8_t>(x + y))", "narrow arithmetic is cast already (8 before too)"),
        Row("sumUb", 8, null, "UInt8 arithmetic is cast already (8 before too)"),
        Row("mulH", 16, null, "Int16 arithmetic is cast already (16 before too)"),
        Row("sumWidth8", 1, null, "Int8 arithmetic in a template (1 before too)"),
        Row("mulWidth64", 8, null, "Int64 arithmetic in a template (8 before too)"),
        Row("negB", 8, null, "a negated Int8 is cast already (8 before too)"),
        Row("negWidth8", 1, null, "a negated Int8 in a template (1 before too)"),
        // round 6's lit2, all 6 of its rows, against its own l2.hxx
        Row("negW", 64, "::l2::w(std::int64_t{-5})", "a negated literal (32 before)"),
        Row("ifW", 64, "::l2::w(gc ? std::int64_t{5} : std::int64_t{6})", "an if-expression's literals are typed by the expression part (64 before too)"),
        Row("constW", 64, "::l2::w(LIM)", "a module constant is its declared type (64 before too)"),
        Row("litU32", 33, "::l2::u32(std::uint32_t{5u})", "a UInt32 literal (33 before too: 5u is an unsigned int on the host)"),
        Row("wrappedWidth", 8, "::l2::mwidth(kira::Maybe<std::int64_t>(std::int64_t{5}))", "a literal wrapped into a Maybe (8 before too)"),
        Row("lit2Width64", 8, "::l2::width(std::int64_t{7})", "a literal in a template (4 before)"),
        // round 7: a literal at the edge of its type still fits it inside the braces (the typer
        // refuses one that does not: types.literal.range)
        Row("edgeUb", 8, "::q::ub(std::uint8_t{255", "the largest UInt8 (32 before)"),
        Row("edgeB", 16, "::q::b(std::int8_t{-128", "the smallest and largest Int8 (64 before)"),
        Row("edgeH", 16, "::q::h(std::int16_t{-32768", "the smallest Int16 (32 before)"),
        Row("edgeU", 64, "::q::u(std::uint64_t{", "a UInt64 past an int (before, the call did not compile: u(long long) is ambiguous)"),
        Row("edgeW", 128, "::q::w(std::int64_t{", "the smallest and largest Int64 (128 before too: literals past an int are wide)"),
        Row("edgeF", 32, "::q::f(float{", "a Float32 near its largest (32 before too)"),
        Row("edgeSz", 64, "::q::sz(kira::Size{0", "a Size zero (32 before)"),
        // round 7's l3: the other literal kinds, and an @_opaque method (round 6's a2_fx: r3.w(5))
        Row("sumLitW", 64, "::q::w(std::int64_t{std::int64_t{2} + std::int64_t{3}})", "arithmetic of literals (64 before too: its operands were typed)"),
        Row("litF64", 64, "::l3::f(double{1.5})", "a Float64 literal (64 before too)"),
        Row("litIsF64", 1, "::l3::isF64(double{2.0})", "an integer literal at a Float64 is exactly a double (1 before too)"),
        Row("litIsBool", 1, "::l3::isBool(bool{true})", "true is exactly a bool (1 before too)"),
        Row("litIsChar", 1, "::l3::isChar(char{'a'})", "a Char literal is exactly a char (1 before too)"),
        Row("litIsCStr", 1, "::l3::isCStr(static_cast<const char*>(\"abc\"))", "a Str literal at a CStr is the const char* the check states, not a const char[4] (0 before)"),
        Row("litIsU8", 1, "::l3::isU8(std::uint8_t{200})", "a UInt8 literal is exactly a std::uint8_t (0 before: an int)"),
        Row("boxW", 64, "->w(std::int64_t{5})", "an @_opaque method's Int64 literal takes the int64 overload (32 before)"),
        Row("boxWidth", 8, "->width(std::int64_t{5})", "an @_opaque template method sees the Int64 (4 before)"),
    )

    private val tree by lazy {
        CppExprTestSupport.emit("ffi-literals", listOf(module)).also { t ->
            Files.writeString(t.root.resolve("q.hxx"), Q)
            Files.writeString(t.root.resolve("l2.hxx"), L2)
            Files.writeString(t.root.resolve("l3.hxx"), L3)
        }
    }

    private fun body(source: String, fn: String): String {
        val head = Regex("\\n( +)[^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n;]*\\n\\1\\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n" + head.groupValues[1] + "}\n", head.range.last)
        return source.substring(head.range.last, if (close < 0) source.length else close)
    }

    @Test
    fun aLiteralArgumentIsSpelledAsTheParametersType() {
        val source = tree.source(module)
        val wrong = rows.filter { it.text != null }.mapNotNull { row ->
            val b = body(source, row.fn)
            if (b.contains(row.text!!)) null else "${row.fn} (${row.why}):\n  want ${row.text}\n  in   ${b.trim()}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The check states the declared type for every one of these parameters, so the call above is the checked call. */
    @Test
    fun theChecksStateTheDeclaredTypes() {
        val header = tree.header(module)
        val want = listOf(
            "KIRA_EXTERN_CHECK(q::w(kira::ffi::arg<std::int64_t>()), std::int32_t, \"w\");",
            "KIRA_EXTERN_CHECK(q::width(kira::ffi::arg<std::int8_t>()), std::int32_t, \"width8\");",
            "KIRA_EXTERN_CHECK(l3::isCStr(std::declval<const char*>()), std::int32_t, \"isCStr\");",
            "KIRA_EXTERN_CHECK(std::declval<const l3::Box&>().w(kira::ffi::arg<std::int64_t>()), std::int32_t, \"Box.w\");",
        )
        val missing = want.filterNot { header.contains(it) }
        assertTrue(missing.isEmpty(), "missing checks:\n${missing.joinToString("\n")}\nin:\n$header")
    }

    @TestFactory
    fun everyLiteralRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("extern literals [${tc.id}]") { check(tc) }
    }

    private fun check(tc: CppToolchain) {
        val driver = buildString {
            append("#include \"").append(module.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
            append(CppExprTestSupport.CHECK_PRELUDE)
            append("\nint main()\n{\n")
            rows.forEach { row ->
                append("    {\n        const std::int32_t got = lit::${row.fn}();\n")
                append("        std::printf(\"  ${row.fn} = %d\\n\", static_cast<int>(got));\n")
                append("        check(got == ${row.value}, \"${row.fn}() is ${row.value}: ${row.why.replace("\"", "\\\"")}\");\n    }\n")
            }
            append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
            append("    return failures == 0 ? 0 : 1;\n}\n")
        }
        val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return
        assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
    }

    private companion object {
        val TOOLCHAINS = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC)

        /** The round-6 verifier's q.hxx (scratchpad v26r6w/atk/lit), verbatim. */
        val Q = """
            #pragma once
            #include <cstddef>
            #include <cstdint>

            // Overload sets and templates on scalar widths: the check (kira::ffi::arg<T>()) resolves the
            // overload that takes T itself; a call handed a value of another C++ type resolves another.
            namespace q
            {
              inline std::int32_t w(std::int32_t) { return 32; }
              inline std::int32_t w(std::int64_t) { return 64; }

              inline std::int32_t u(std::int32_t) { return 32; }
              inline std::int32_t u(std::uint64_t) { return 64; }

              inline std::int32_t b(std::int32_t) { return 32; }
              inline std::int32_t b(std::int8_t) { return 8; }

              inline std::int32_t ub(std::int32_t) { return 32; }
              inline std::int32_t ub(std::uint8_t) { return 8; }

              inline std::int32_t h(std::int32_t) { return 32; }
              inline std::int32_t h(std::int16_t) { return 16; }

              inline std::int32_t sz(std::int32_t) { return 32; }
              inline std::int32_t sz(std::size_t) { return 64; }

              inline std::int32_t f(double) { return 64; }
              inline std::int32_t f(float) { return 32; }

              // A template takes whatever the call passes: its width in bytes.
              template<class T>
              std::int32_t width(T) { return static_cast<std::int32_t>(sizeof(T)); }
            }
        """.trimIndent() + "\n"

        /** The round-6 verifier's l2.hxx (v26r6w/atk/lit2), verbatim. */
        val L2 = """
            #pragma once
            #include <cstdint>

            namespace l2
            {
              inline std::int32_t w(std::int32_t) { return 32; }
              inline std::int32_t w(std::int64_t) { return 64; }

              inline std::int32_t u32(std::int32_t) { return 32; }
              inline std::int32_t u32(std::uint32_t) { return 33; }

              template<class T>
              std::int32_t width(T) { return static_cast<std::int32_t>(sizeof(T)); }

              template<class M>
              std::int32_t mwidth(const M& m) { return m ? static_cast<std::int32_t>(sizeof(*m)) : -1; }
            }
        """.trimIndent() + "\n"

        /** Round 7's l3.hxx: templates that say whether they were handed exactly T, and an opaque class's overload set and template. */
        val L3 = """
            #pragma once
            #include <cstdint>
            #include <type_traits>

            namespace l3
            {
              inline std::int32_t f(float) { return 32; }
              inline std::int32_t f(double) { return 64; }

              template<class T, class U>
              std::int32_t same(const U&) { return std::is_same_v<T, U> ? 1 : 0; }

              template<class U>
              std::int32_t isF64(const U& u) { return same<double>(u); }
              template<class U>
              std::int32_t isBool(const U& u) { return same<bool>(u); }
              template<class U>
              std::int32_t isChar(const U& u) { return same<char>(u); }
              template<class U>
              std::int32_t isCStr(const U& u) { return same<const char*>(u); }
              template<class U>
              std::int32_t isU8(const U& u) { return same<std::uint8_t>(u); }

              class Box
              {
              public:
                  std::int32_t w(std::int32_t) const { return 32; }
                  std::int32_t w(std::int64_t) const { return 64; }
                  template<class T>
                  std::int32_t width(T) const { return static_cast<std::int32_t>(sizeof(T)); }
              };
              inline Box* newBox()
              {
                  static Box b;
                  return &b;
              }
            }
        """.trimIndent() + "\n"
    }
}
