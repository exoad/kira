package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Round 5b's w2-6 finding: an extern argument the typer converted (`Coercion.WrapSome`,
 * `NoneOf`, `Upcast`, `FnRef`) reached C++ unconverted. The check states the parameter's type
 * (`kira::ffi::arg<kira::Maybe<T>>()`, `std::declval<const kira::Rc<Base>&>()`), so against a
 * C++ overload set or template the emitted call was another call than the checked one, and the
 * copy policy's W1 (a conversion is a temporary) lent the Kira storage itself. The rows are the
 * round-5b verifier's probes m3 and m4, and round 6's m5, each a function whose
 * value the driver checks on gcc, clang and msvc; each row's reason gives the value before the fix.
 */
class ExternCoercionRunTest {
    private val maybes = Module(
        "app:m",
        """
        pub @_extern(cpp = "m3::pick", header = "m3.hxx") fx pick: (m: Maybe<Str>) Int32;

        pub @_extern(cpp = "m3::pickN", header = "m3.hxx") fx pickN: (m: Maybe<Int32>) Int32;

        pub @_extern(cpp = "m3::lenAfter", header = "m3.hxx") fx lenAfter: (m: Maybe<Str>, f: Fx<Tuple0, Void>) Int32;

        pub @_extern(cpp = "m3::sizeOrNeg", header = "m3.hxx") fx sizeOrNeg: (m: Maybe<Str>) Int32;

        pub @_extern(cpp = "m3::sizeThen", header = "m3.hxx") fx sizeThen: (m: Maybe<Str>, k: Int32) Int32;

        pub @_extern(cpp = "m3::callKind", header = "m3.hxx") fx callKind: (f: Fx<Tuple0, Int32>) Int32;

        mut gs: Str = "$OLD"
        mut gm: Maybe<Str> = "$OLD"
        mut gls: List<Str> = ["$OLD"]

        fx reset: () Void {
            gs = "$OLD"
            gls = ["$OLD"]
        }

        fx two: () Int32 {
            return 2
        }

        fx bumpGs: () Int32 {
            gs = "x"
            return 0
        }

        pub fx mMaybe: () Int32 {
            return pick(gm)
        }

        pub fx mWrapStr: () Int32 {
            return pick(gs)
        }

        pub fx mWrapInt: () Int32 {
            n: Int32 = 5
            return pickN(n)
        }

        pub fx mMaybeInt: () Int32 {
            mn: Maybe<Int32> = 5
            return pickN(mn)
        }

        pub fx mGlobal: () Int32 {
            reset()
            return lenAfter(gs, fx () Void { gs = "x" })
        }

        pub fx mElement: () Int32 {
            reset()
            return lenAfter(gls[0], fx () Void { gls = ["x", "y", "z", "w", "v", "u", "t", "s", "r", "q", "p", "o", "n", "m", "l", "k", "j"] })
        }

        pub fx mLiteral: () Int32 {
            return pick("ab")
        }

        pub fx mNull: () Int32 {
            return sizeOrNeg(null)
        }

        pub fx mSibling: () Int32 {
            reset()
            return sizeThen(gs, bumpGs())
        }

        pub fx mFnRef: () Int32 {
            return callKind(two)
        }
        """,
    )

    /** One row: the function, Kira's value, the call its body must contain, and why (with the value before the fix). */
    private data class Row(val fn: String, val value: Int, val text: String, val why: String)

    private val maybeRows = listOf(
        Row("mMaybe", 147, "::m3::pick(gm)", "a Maybe given as itself takes the optional overload (147 before too)"),
        Row("mWrapStr", 147, "::m3::pick(kira::Maybe<kira::Str>(gs))", "a Str wrapped into the Maybe takes the optional overload the check resolved (247 before)"),
        Row("mWrapInt", 105, "::m3::pickN(kira::Maybe<std::int32_t>(n))", "an Int32 wrapped takes the optional overload (205 before)"),
        Row("mMaybeInt", 105, "::m3::pickN(mn)", "a Maybe<Int32> as itself (105 before too)"),
        Row("mGlobal", 47, "::m3::lenAfter(kira::Maybe<kira::Str>(gs), ", "the Maybe the template binds holds the Str as the call began (1 before: it bound gs, which the lambda replaced)"),
        Row("mElement", 47, "::m3::lenAfter(kira::Maybe<kira::Str>(kira::at(gls, 0)), ", "the Maybe holds the element (g++ printed -1971355728 before, and MSVC ASan reported a heap-use-after-free)"),
        Row("mLiteral", 102, "::m3::pick(kira::Maybe<kira::Str>(\"ab\"))", "a literal wrapped takes the optional overload (an ambiguous call before)"),
        Row("mNull", -1, "::m3::sizeOrNeg(kira::Maybe<kira::Str>(kira::none))", "null reaches the template as the empty Maybe (a compile error before: it bound kira::None)"),
        Row("mSibling", 47, "::m3::sizeThen(kira::Maybe<kira::Str>(t0_), t1_)", "OQ-1: the Str is read before the sibling that replaces it, then wrapped"),
        Row("mFnRef", 102, "::m3::callKind(kira::Fn<std::int32_t()>(two))", "a named function reaches the extern as the kira::Fn the check states (202 before: a function reference)"),
    )

    private val maybeTree by lazy { emitWith("ffi-coerce-maybe", maybes, "m3.hxx", M3) }

    private fun emitWith(name: String, module: Module, header: String, text: String): CppExprTestSupport.Tree =
        CppExprTestSupport.emit(name, listOf(module)).also { Files.writeString(it.root.resolve(header), text) }

    private fun body(source: String, fn: String): String {
        val head = Regex("\\n( +)[^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n;]*\\n\\1\\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n" + head.groupValues[1] + "}\n", head.range.last)
        return source.substring(head.range.last, if (close < 0) source.length else close)
    }

    private fun wrongTexts(source: String, rows: List<Row>): List<String> = rows.mapNotNull { row ->
        val b = body(source, row.fn)
        if (b.contains(row.text)) null else "${row.fn} (${row.why}):\n  want ${row.text}\n  in   ${b.trim()}"
    }

    @Test
    fun anArgumentTheTyperConvertedToAMaybeOrAnFxIsPassedAsTheParametersType() {
        val wrong = wrongTexts(maybeTree.source(maybes), maybeRows)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @TestFactory
    fun everyMaybeRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("maybe coercions [${tc.id}]") { check(tc, maybeTree, maybes, "m", maybeRows) }
    }

    // ---- class handles: an upcast to a parent or a trait, and a subclass wrapped into a Maybe of its parent ----

    private val classes = Module(
        "app:k",
        """
        trait Named {
            pub fx nm: () Str
        }

        pub class Base {
            pub name: Str = "$OLD"
        }

        pub class Kid: Base {
        }

        pub class Dog: Named {
            pub label: Str = "$OLD"

            override pub fx nm: () Str {
                return label
            }
        }

        pub class H {
            pub mut kid: Kid = Kid { }
            pub mut dog: Dog = Dog { }
        }

        mut gks: List<Kid> = []

        pub @_extern(cpp = "m4::nameLenAfter", header = "m4.hxx") fx nameLenAfter: (b: Base, f: Fx<Tuple0, Void>) Int32;

        pub @_extern(cpp = "m4::nmLenAfter", header = "m4.hxx") fx nmLenAfter: (t: Named, f: Fx<Tuple0, Void>) Int32;

        pub @_extern(cpp = "m4::maybeNameLenAfter", header = "m4.hxx") fx maybeNameLenAfter: (b: Maybe<Base>, f: Fx<Tuple0, Void>) Int32;

        pub fx kParent: () Int32 {
            h: H = H { }
            return nameLenAfter(h.kid, fx () Void { h.kid = Kid { name = "x" } })
        }

        pub fx kTrait: () Int32 {
            h: H = H { }
            return nmLenAfter(h.dog, fx () Void { h.dog = Dog { label = "x" } })
        }

        pub fx kMaybeParent: () Int32 {
            h: H = H { }
            return maybeNameLenAfter(h.kid, fx () Void { h.kid = Kid { name = "x" } })
        }

        pub fx kElement: () Int32 {
            gks = [Kid { }]
            return nameLenAfter(gks[0], fx () Void { gks = [Kid { name = "x" }, Kid { name = "y" }, Kid { name = "z" }] })
        }
        """,
    )

    private val classRows = listOf(
        Row("kParent", 47, "::m4::nameLenAfter(kira::Rc<Base>(h->kid), ", "m4: the Base handle holds the Kid the lambda unhooks (1 before: the template bound the slot)"),
        Row("kTrait", 47, "::m4::nmLenAfter(kira::Rc<impl_::Named>(h->dog), ", "a class upcast to a trait: the Named handle holds the Dog (1 before)"),
        Row("kMaybeParent", 47, "::m4::maybeNameLenAfter(kira::Maybe<kira::Rc<Base>>(h->kid), ", "a Kid wrapped into a Maybe<Base>, the nullable Rc of the parent (1 before)"),
        Row("kElement", 47, "::m4::nameLenAfter(kira::Rc<Base>(kira::at(gks, 0)), ", "an element of a global List, upcast: the handle holds it (a heap-use-after-free under MSVC ASan before)"),
    )

    /**
     * Kira classes are W2.4's part (`CppClassesPart`); on a branch where it is not registered a
     * class is `cpp.unsupported`, and these rows run where both packages are merged (the trial,
     * cpp-backend). CppExternEmitterTest pins the same spellings through the typed model alone.
     */
    private fun assumeClassesLowered() = Assumptions.assumeTrue(
        CppEmitParts.standard().classes !== CppClassesPart.Unsupported,
        "Kira classes are lowered by W2.4's CppClassesPart, which this branch does not register",
    )

    private val classTree by lazy { emitWith("ffi-coerce-classes", classes, "m4.hxx", M4) }

    @Test
    fun aClassHandleUpcastOrWrappedIsPassedAsTheParametersType() {
        assumeClassesLowered()
        val wrong = wrongTexts(classTree.source(classes), classRows)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @TestFactory
    fun everyClassRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("class coercions [${tc.id}]") {
            assumeClassesLowered()
            check(tc, classTree, classes, "k", classRows)
        }
    }

    private fun check(tc: CppToolchain, tree: CppExprTestSupport.Tree, module: Module, ns: String, rows: List<Row>) {
        val driver = buildString {
            append("#include \"").append(module.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
            append(CppExprTestSupport.CHECK_PRELUDE)
            append("\nint main()\n{\n")
            rows.forEach { row -> append("    check($ns::${row.fn}() == ${row.value}, \"${row.fn}() is ${row.value}: ${row.why}\");\n") }
            append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
            append("    return failures == 0 ? 0 : 1;\n}\n")
        }
        val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return
        assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
    }

    private companion object {
        const val OLD = "old-text-long-enough-to-live-on-the-heap-000000"

        val TOOLCHAINS = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC)

        /** The verifier's m3.hxx (an overload set and a template), with round 6's m5 templates. */
        val M3 = """
            #pragma once
            #include <cstdint>
            #include <functional>
            #include <optional>
            #include <string>
            #include <type_traits>

            namespace m3
            {
                inline std::int32_t pick(const std::optional<std::string>& m)
                {
                    return 100 + (m.has_value() ? static_cast<std::int32_t>(m->size()) : 0);
                }
                inline std::int32_t pick(const std::string& s)
                {
                    return 200 + static_cast<std::int32_t>(s.size());
                }
                inline std::int32_t pickN(std::optional<std::int32_t> m)
                {
                    return 100 + (m.has_value() ? *m : 0);
                }
                inline std::int32_t pickN(std::int32_t n)
                {
                    return 200 + n;
                }
                inline std::int32_t sizeOf(const std::optional<std::string>& m)
                {
                    return m.has_value() ? static_cast<std::int32_t>(m->size()) : -1;
                }
                inline std::int32_t sizeOf(const std::string& s)
                {
                    return static_cast<std::int32_t>(s.size());
                }
                template<class M, class F>
                std::int32_t lenAfter(const M& m, const F& f)
                {
                    f();
                    return sizeOf(m);
                }
                template<class M>
                std::int32_t sizeOrNeg(const M& m)
                {
                    return m.has_value() ? static_cast<std::int32_t>(m->size()) : -1;
                }
                template<class M>
                std::int32_t sizeThen(const M& m, std::int32_t k)
                {
                    return (m.has_value() ? static_cast<std::int32_t>(m->size()) : -1) + k;
                }
                template<class F>
                std::int32_t callKind(const F& f)
                {
                    return (std::is_same_v<F, std::function<std::int32_t()>> ? 100 : 200) + f();
                }
            }
        """.trimIndent() + "\n"

        /** The verifier's m4.hxx (a template that runs f, then reads the handle), with a trait's and a Maybe's. */
        val M4 = """
            #pragma once
            #include <cstdint>
            #include <string>

            namespace m4
            {
                template<class H, class F>
                std::int32_t nameLenAfter(const H& h, const F& f)
                {
                    f();
                    return static_cast<std::int32_t>(h->name.size());
                }
                template<class T, class F>
                std::int32_t nmLenAfter(const T& t, const F& f)
                {
                    f();
                    return static_cast<std::int32_t>(t->nm().size());
                }
                template<class B, class F>
                std::int32_t maybeNameLenAfter(const B& b, const F& f)
                {
                    f();
                    return b ? static_cast<std::int32_t>(b->name.size()) : -1;
                }
            }
        """.trimIndent() + "\n"
    }
}
