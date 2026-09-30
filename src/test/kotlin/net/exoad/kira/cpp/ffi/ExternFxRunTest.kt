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
 * Round 7's w2-6 finding (sc-round7.json, the W2.4 verifier's significant item): a lambda literal
 * given to an `@_extern` or `@_opaque` argument of type `Fx<...>` reached C++ as its closure type,
 * while the drift check states `kira::ffi::arg<kira::Fn<...>>()`, a `std::function`. Against a
 * template or an overload set the emitted call was then another call than the checked one: the
 * round-6 probe a2_fx's first row, `callKind(fx () Int32 { return 2 })` against its `template<class
 * F> callKind(const F&)`, printed 202 where Kira's value is 102, on g++, clang and MSVC alike.
 *
 * The rows are a2_fx's row 1 and its variants, each against a template (`const F&`, and a
 * forwarding `F&&`) and an overload set (the `std::function` against a template, and against a
 * function pointer): a lambda literal, a capturing one, one of one parameter, two lambdas meeting in
 * an if-expression, a method reached through a lambda (Kira has no method value:
 * `types.member.method-value`), an `@_opaque` method given a lambda, and a named function, bare and
 * through its module (round 6's FnRef path). Then the controls, arguments that are a `kira::Fn`
 * already and are passed as they are: a local, a parameter, a call's result, a List element, a
 * Maybe's value, a function value's result, and if-expressions. Each row's reason gives the value
 * before the fix, measured on g++, clang and MSVC alike with the fix taken out.
 */
class ExternFxRunTest {
    private val fns = Module(
        "app:fxfns",
        """
        pub fx four: () Int32 {
            return 4
        }
        """,
    )

    private val module = Module(
        "app:fxr",
        """
        use "app:fxfns"

        $EXTERNS

        @_extern(cpp = "f7::ptr", header = "f7.hxx")
        pub fx ptr: (g: Fx<Tuple0, Int32>) Int32;

        @_extern(cpp = "f7::fwdKind", header = "f7.hxx")
        pub fx fwdKind: (g: Fx<Tuple0, Int32>) Int32;

        @_extern(cpp = "f7::callKind1", header = "f7.hxx")
        pub fx callKind1: (g: Fx<Tuple1<Int32>, Int32>) Int32;

        @_extern(cpp = "f7::pick1", header = "f7.hxx")
        pub fx pick1: (g: Fx<Tuple1<Int32>, Int32>) Int32;

        @_extern(cpp = "f7::mkind", header = "f7.hxx")
        pub fx mkind: (m: Maybe<Fx<Tuple0, Int32>>) Int32;

        @_opaque @_extern(cpp = "f7::Box", header = "f7.hxx")
        pub class Box {
            pub fx kind: (g: Fx<Tuple0, Int32>) Int32;
            pub fx pick: (g: Fx<Tuple0, Int32>) Int32;
            pub fx get: () Int32;
        }

        @_extern(cpp = "f7::newBox", header = "f7.hxx")
        pub fx newBox: () Box;

        mut gc: Bool = true

        fx two: () Int32 {
            return 2
        }

        fx three: () Int32 {
            return 3
        }

        fx inc: (x: Int32) Int32 {
            return x + 1
        }

        fx makeFx: () Fx<Tuple0, Int32> {
            return fx () Int32 { return 6 }
        }

        fx relay: (g: Fx<Tuple0, Int32>) Int32 {
            return callKind(g)
        }

        pub fx aLambda: () Int32 {
            return callKind(fx () Int32 { return 2 })
        }

        pub fx aLambdaFwd: () Int32 {
            return fwdKind(fx () Int32 { return 2 })
        }

        pub fx aLambdaPick: () Int32 {
            return pick(fx () Int32 { return 2 })
        }

        pub fx aCapture: () Int32 {
            k: Int32 = two() + 3
            return callKind(fx () Int32 { return k })
        }

        pub fx aCapturePick: () Int32 {
            k: Int32 = two() + 3
            return pick(fx () Int32 { return k })
        }

        pub fx aLambda1: () Int32 {
            return callKind1(fx (x: Int32) Int32 { return x * 2 })
        }

        pub fx aLambdaPick1: () Int32 {
            return pick1(fx (x: Int32) Int32 { return x * 2 })
        }

        pub fx aTernary: () Int32 {
            return callKind(if gc { fx () Int32 { return 7 } } else { fx () Int32 { return 8 } })
        }

        pub fx aMethod: () Int32 {
            bx: Box = newBox()
            return callKind(fx () Int32 { return bx.get() })
        }

        pub fx aMethodPick: () Int32 {
            bx: Box = newBox()
            return pick(fx () Int32 { return bx.get() })
        }

        pub fx boxKind: () Int32 {
            bx: Box = newBox()
            return bx.kind(fx () Int32 { return 4 })
        }

        pub fx boxPick: () Int32 {
            bx: Box = newBox()
            return bx.pick(fx () Int32 { return 4 })
        }

        pub fx refKind: () Int32 {
            return callKind(two)
        }

        pub fx refPick: () Int32 {
            return pick(two)
        }

        pub fx refPtr: () Int32 {
            return ptr(two)
        }

        pub fx refFwd: () Int32 {
            return fwdKind(two)
        }

        pub fx refModule: () Int32 {
            return callKind(fxfns.four)
        }

        pub fx refModulePick: () Int32 {
            return pick(fxfns.four)
        }

        pub fx ref1: () Int32 {
            return callKind1(inc)
        }

        pub fx refPick1: () Int32 {
            return pick1(inc)
        }

        pub fx boxRefKind: () Int32 {
            bx: Box = newBox()
            return bx.kind(two)
        }

        pub fx boxRefPick: () Int32 {
            bx: Box = newBox()
            return bx.pick(two)
        }

        pub fx local: () Int32 {
            g: Fx<Tuple0, Int32> = fx () Int32 { return 8 }
            return callKind(g)
        }

        pub fx localPick: () Int32 {
            g: Fx<Tuple0, Int32> = fx () Int32 { return 8 }
            return pick(g)
        }

        pub fx viaParam: () Int32 {
            return relay(fx () Int32 { return 1 })
        }

        pub fx result: () Int32 {
            return callKind(makeFx())
        }

        pub fx element: () Int32 {
            fs: List<Fx<Tuple0, Int32>> = [fx () Int32 { return 9 }]
            return callKind(fs[0])
        }

        pub fx unwrapped: () Int32 {
            mf: Maybe<Fx<Tuple0, Int32>> = fx () Int32 { return 4 }
            return callKind(mf.unwrap())
        }

        pub fx valueResult: () Int32 {
            mk: Fx<Tuple0, Fx<Tuple0, Int32>> = makeFx
            return callKind(mk())
        }

        pub fx ifRefs: () Int32 {
            return callKind(if gc { two } else { three })
        }

        pub fx ifMixed: () Int32 {
            g: Fx<Tuple0, Int32> = fx () Int32 { return 8 }
            return callKind(if gc { g } else { fx () Int32 { return 0 } })
        }

        pub fx ifStatements: () Int32 {
            return callKind(if gc {
                z: Int32 = two() + 3
                fx () Int32 { return z }
            } else {
                two
            })
        }

        pub fx maybeLambda: () Int32 {
            return mkind(fx () Int32 { return 3 })
        }
        """,
    )

    /**
     * One row: the function, Kira's value, the call its body (or [textIn]'s) must contain, and why,
     * with the value before the fix.
     */
    private data class Row(val fn: String, val value: Int, val text: String, val why: String, val textIn: String = fn)

    private val rows = listOf(
        // a lambda literal: the closure type before, which each of these tells from a std::function
        Row("aLambda", 102, "::f7::callKind(kira::Fn<std::int32_t()>([]() -> std::int32_t", "a2_fx row 1: a lambda reaches the template as the kira::Fn the check states (202 before)"),
        Row("aLambdaFwd", 102, "::f7::fwdKind(kira::Fn<std::int32_t()>([]() -> std::int32_t", "a forwarding template sees the kira::Fn (202 before)"),
        Row("aLambdaPick", 102, "::f7::pick(kira::Fn<std::int32_t()>([]() -> std::int32_t", "the std::function overload the check resolved, not the template for other callables (202 before)"),
        Row("aCapture", 105, "::f7::callKind(kira::Fn<std::int32_t()>([k]() -> std::int32_t", "a capturing lambda (205 before)"),
        Row("aCapturePick", 105, "::f7::pick(kira::Fn<std::int32_t()>([k]() -> std::int32_t", "a capturing lambda at the overload set (205 before)"),
        Row("aLambda1", 106, "::f7::callKind1(kira::Fn<std::int32_t(std::int32_t)>([](", "a lambda of one parameter (206 before)"),
        Row("aLambdaPick1", 106, "::f7::pick1(kira::Fn<std::int32_t(std::int32_t)>([](", "a lambda of one parameter at the overload set (206 before)"),
        Row("aTernary", 107, "::f7::callKind(kira::Fn<std::int32_t()>(gc ? []() -> std::int32_t", "two lambdas in an if-expression (207 before: two captureless lambdas meet as a function pointer)"),
        Row("aMethod", 107, "::f7::callKind(kira::Fn<std::int32_t()>([bx]() -> std::int32_t", "a method through a lambda, as Kira passes one (207 before)"),
        Row("aMethodPick", 107, "::f7::pick(kira::Fn<std::int32_t()>([bx]() -> std::int32_t", "a method through a lambda at the overload set (207 before)"),
        Row("boxKind", 104, "->kind(kira::Fn<std::int32_t()>([]() -> std::int32_t", "an @_opaque template method given a lambda (204 before)"),
        Row("boxPick", 104, "->pick(kira::Fn<std::int32_t()>([]() -> std::int32_t", "an @_opaque method's overload set given a lambda (204 before)"),
        // a named function: round 6's FnRef path, unchanged
        Row("refKind", 102, "::f7::callKind(kira::Fn<std::int32_t()>(two))", "a named function (102 before too)"),
        Row("refPick", 102, "::f7::pick(kira::Fn<std::int32_t()>(two))", "a named function at the overload set (102 before too)"),
        Row("refPtr", 102, "::f7::ptr(kira::Fn<std::int32_t()>(two))", "a named function beside a function-pointer overload (102 before too; 302 before round 6)"),
        Row("refFwd", 102, "::f7::fwdKind(kira::Fn<std::int32_t()>(two))", "a named function at a forwarding template (102 before too)"),
        Row("refModule", 104, "::f7::callKind(kira::Fn<std::int32_t()>(::fxfns::four))", "a function named through its module (104 before too)"),
        Row("refModulePick", 104, "::f7::pick(kira::Fn<std::int32_t()>(::fxfns::four))", "the same at the overload set (104 before too)"),
        Row("ref1", 104, "::f7::callKind1(kira::Fn<std::int32_t(std::int32_t)>(inc))", "a named function of one parameter (104 before too)"),
        Row("refPick1", 104, "::f7::pick1(kira::Fn<std::int32_t(std::int32_t)>(inc))", "the same at the overload set (104 before too)"),
        Row("boxRefKind", 102, "->kind(kira::Fn<std::int32_t()>(two))", "an @_opaque template method given a named function (102 before too)"),
        Row("boxRefPick", 102, "->pick(kira::Fn<std::int32_t()>(two))", "an @_opaque overload set given a named function (102 before too)"),
        // a kira::Fn already: passed as it is, never wrapped twice
        Row("local", 108, "::f7::callKind(g)", "an Fx local is a kira::Fn, lent as it was (108 before too)"),
        Row("localPick", 108, "::f7::pick(g)", "the same at the overload set (108 before too)"),
        Row("viaParam", 101, "::f7::callKind(g)", "an Fx parameter passed on is a kira::Fn (101 before too)", textIn = "relay"),
        Row("result", 106, "::f7::callKind(makeFx())", "a Kira function's Fx result (106 before too)"),
        Row("element", 109, "::f7::callKind(kira::at(fs, 0))", "an element of a List of Fx (109 before too)"),
        Row("unwrapped", 104, "::f7::callKind(kira::unwrap(mf))", "a Maybe's value (104 before too)"),
        Row("valueResult", 106, "::f7::callKind(mk())", "a function value's Fx result (106 before too)"),
        Row("ifRefs", 102, "::f7::callKind(gc ? kira::Fn<std::int32_t()>(two) : kira::Fn<std::int32_t()>(three))", "an if-expression of named functions is typed by the expression part (102 before too)"),
        Row("ifMixed", 108, "::f7::callKind(gc ? g : []() -> std::int32_t", "a ternary with a kira::Fn branch is a kira::Fn (108 before too)"),
        Row("ifStatements", 105, "::f7::callKind([&]() -> kira::Fn<std::int32_t()>", "an if-expression with statements returns the kira::Fn (105 before too)"),
        Row("maybeLambda", 103, "::f7::mkind(kira::Maybe<kira::Fn<std::int32_t()>>([]() -> std::int32_t", "a lambda wrapped into a Maybe was typed already (103 before too)"),
    )

    private val tree by lazy { emitWith("ffi-fx", listOf(fns, module)) }

    private fun emitWith(name: String, modules: List<Module>): CppExprTestSupport.Tree =
        CppExprTestSupport.emit(name, modules).also { Files.writeString(it.root.resolve("f7.hxx"), F7) }

    private fun body(source: String, fn: String): String {
        val head = Regex("\\n( +)[^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n;]*\\n\\1\\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n" + head.groupValues[1] + "}\n", head.range.last)
        return source.substring(head.range.last, if (close < 0) source.length else close)
    }

    private fun wrongTexts(source: String, rows: List<Row>): List<String> = rows.mapNotNull { row ->
        val b = body(source, row.textIn)
        if (b.contains(row.text)) null else "${row.fn} (${row.why}):\n  want ${row.text}\n  in   ${b.trim()}"
    }

    @Test
    fun anFxArgumentThatIsNoKiraFnIsPassedAsTheParametersType() {
        val wrong = wrongTexts(tree.source(module), rows)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The checks state the kira::Fn (and the Maybe of one) the calls above now pass. */
    @Test
    fun theChecksStateTheKiraFn() {
        val header = tree.header(module)
        val want = listOf(
            "KIRA_EXTERN_CHECK(f7::callKind(kira::ffi::arg<kira::Fn<std::int32_t()>>()), std::int32_t, \"callKind\");",
            "KIRA_EXTERN_CHECK(f7::pick1(kira::ffi::arg<kira::Fn<std::int32_t(std::int32_t)>>()), std::int32_t, \"pick1\");",
            "KIRA_EXTERN_CHECK(f7::mkind(kira::ffi::arg<kira::Maybe<kira::Fn<std::int32_t()>>>()), std::int32_t, \"mkind\");",
            "KIRA_EXTERN_CHECK(std::declval<const f7::Box&>().kind(kira::ffi::arg<kira::Fn<std::int32_t()>>()), std::int32_t, \"Box.kind\");",
        )
        val missing = want.filterNot { header.contains(it) }
        assertTrue(missing.isEmpty(), "missing checks:\n${missing.joinToString("\n")}\nin:\n$header")
    }

    @TestFactory
    fun everyFxRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("extern Fx arguments [${tc.id}]") { check(tc, tree, module, "fxr", rows) }
    }

    // ---- a captureless lambda beside a function-pointer overload: an ambiguous call before ----

    private val ambiguous = Module(
        "app:fxamb",
        """
        $EXTERNS

        @_extern(cpp = "f7::ptr", header = "f7.hxx")
        pub fx ptr: (g: Fx<Tuple0, Int32>) Int32;

        pub fx ptrLambda: () Int32 {
            return ptr(fx () Int32 { return 2 })
        }
        """,
    )

    private val ambiguousRows = listOf(
        Row("ptrLambda", 102, "::f7::ptr(kira::Fn<std::int32_t()>([]() -> std::int32_t", "the std::function overload the check resolved (before, no compiler built the call: a lambda converts to both, ambiguously)"),
    )

    private val ambiguousTree by lazy { emitWith("ffi-fx-ambiguous", listOf(ambiguous)) }

    @Test
    fun aCapturelessLambdaIsPassedAsTheKiraFnBesideAFunctionPointerOverload() {
        val wrong = wrongTexts(ambiguousTree.source(ambiguous), ambiguousRows)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @TestFactory
    fun theAmbiguousRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("extern Fx beside a function pointer [${tc.id}]") { check(tc, ambiguousTree, ambiguous, "fxamb", ambiguousRows) }
    }

    // ---- Kira classes: a lambda capturing the receiver, and a method of a class object through a lambda ----

    private val classes = Module(
        "app:fxk",
        """
        $EXTERNS

        pub class Counter {
            pub mut n: Int32 = 5

            pub fx viaThis: () Int32 {
                return callKind(fx () Int32 { return n })
            }

            pub fx viaThisPick: () Int32 {
                return pick(fx () Int32 { return n })
            }

            pub fx get: () Int32 {
                return n
            }
        }

        pub fx kThis: () Int32 {
            c: Counter = Counter { }
            return c.viaThis()
        }

        pub fx kThisPick: () Int32 {
            c: Counter = Counter { }
            return c.viaThisPick()
        }

        pub fx kMethod: () Int32 {
            c: Counter = Counter { }
            return callKind(fx () Int32 { return c.get() })
        }

        pub fx kMethodPick: () Int32 {
            c: Counter = Counter { }
            return pick(fx () Int32 { return c.get() })
        }
        """,
    )

    private val classRows = listOf(
        Row("kThis", 105, "::f7::callKind(kira::Fn<std::int32_t()>([self = shared_from_this()]() -> std::int32_t", "a lambda over the receiver's field (205 before)", textIn = "viaThis"),
        Row("kThisPick", 105, "::f7::pick(kira::Fn<std::int32_t()>([self = shared_from_this()]() -> std::int32_t", "the same at the overload set (205 before)", textIn = "viaThisPick"),
        Row("kMethod", 105, "::f7::callKind(kira::Fn<std::int32_t()>([c]() -> std::int32_t", "a class's method through a lambda (205 before)"),
        Row("kMethodPick", 105, "::f7::pick(kira::Fn<std::int32_t()>([c]() -> std::int32_t", "the same at the overload set (205 before)"),
    )

    /**
     * Kira classes are W2.4's part (`CppClassesPart`); on a branch where it is not registered a
     * class is `cpp.unsupported`, and these rows run where both packages are merged (the trial,
     * cpp-backend), as ExternCoercionRunTest's class rows do.
     */
    private fun assumeClassesLowered() = Assumptions.assumeTrue(
        CppEmitParts.standard().classes !== CppClassesPart.Unsupported,
        "Kira classes are lowered by W2.4's CppClassesPart, which this branch does not register",
    )

    private val classTree by lazy { emitWith("ffi-fx-classes", listOf(classes)) }

    @Test
    fun aLambdaOfAClassIsPassedAsTheKiraFn() {
        assumeClassesLowered()
        val wrong = wrongTexts(classTree.source(classes), classRows)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @TestFactory
    fun everyClassRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> = TOOLCHAINS.map { tc ->
        DynamicTest.dynamicTest("extern Fx from a class [${tc.id}]") {
            assumeClassesLowered()
            check(tc, classTree, classes, "fxk", classRows)
        }
    }

    /** A method is no value in Kira: the method-reference variant is a lambda over the call (the rows above). */
    @Test
    fun aMethodAsAValueIsRefusedByTheTyper() {
        val refused = Module(
            "app:fxm",
            """
            $EXTERNS

            @_opaque @_extern(cpp = "f7::Box", header = "f7.hxx")
            pub class Box {
                pub fx get: () Int32;
            }

            @_extern(cpp = "f7::newBox", header = "f7.hxx")
            pub fx newBox: () Box;

            pub fx mValue: () Int32 {
                bx: Box = newBox()
                return callKind(bx.get)
            }
            """,
        )
        val result = CppExprTestSupport.emitRefused("ffi-fx-method", listOf(refused))
        assertTrue(result.diagnostics.any { it.isError && it.render().contains("types.member.method-value") }, result.diagnostics.joinToString("\n") { it.render() })
    }

    private fun check(tc: CppToolchain, tree: CppExprTestSupport.Tree, module: Module, ns: String, rows: List<Row>) {
        val driver = buildString {
            append("#include \"").append(module.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
            append(CppExprTestSupport.CHECK_PRELUDE)
            append("\nint main()\n{\n")
            rows.forEach { row ->
                append("    {\n        const std::int32_t got = $ns::${row.fn}();\n")
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

        /** The two externs every module here declares: a2_fx's template and an overload set. */
        const val EXTERNS = """@_extern(cpp = "f7::callKind", header = "f7.hxx")
        pub fx callKind: (g: Fx<Tuple0, Int32>) Int32;

        @_extern(cpp = "f7::pick", header = "f7.hxx")
        pub fx pick: (g: Fx<Tuple0, Int32>) Int32;"""

        /**
         * Templates and overload sets that tell a std::function (the kira::Fn the check states)
         * from any other callable: 100 + the call for the std::function, 200 + it otherwise, 300 +
         * it for a function pointer. callKind is a2_fx's r.hxx template, verbatim.
         */
        val F7 = """
            #pragma once
            #include <cstdint>
            #include <functional>
            #include <optional>
            #include <type_traits>
            #include <utility>

            namespace f7
            {
              using Fn0 = std::function<std::int32_t()>;
              using Fn1 = std::function<std::int32_t(std::int32_t)>;

              template<class F>
              std::int32_t callKind(const F& f)
              {
                  return (std::is_same_v<F, std::function<std::int32_t()>> ? 100 : 200) + f();
              }

              template<class F>
              std::int32_t fwdKind(F&& f)
              {
                  return (std::is_same_v<std::decay_t<F>, Fn0> ? 100 : 200) + f();
              }

              inline std::int32_t pick(const Fn0& f) { return 100 + f(); }
              template<class F>
              std::int32_t pick(const F& f) { return 200 + f(); }

              inline std::int32_t ptr(Fn0 f) { return 100 + f(); }
              inline std::int32_t ptr(std::int32_t (*f)()) { return 300 + f(); }

              template<class F>
              std::int32_t callKind1(const F& f) { return (std::is_same_v<F, Fn1> ? 100 : 200) + f(3); }
              inline std::int32_t pick1(const Fn1& f) { return 100 + f(3); }
              template<class F>
              std::int32_t pick1(const F& f) { return 200 + f(3); }

              template<class M>
              std::int32_t mkind(const M& m) { return (std::is_same_v<M, std::optional<Fn0>> ? 100 : 200) + (m ? (*m)() : -50); }

              class Box
              {
              public:
                  template<class F>
                  std::int32_t kind(const F& f) const { return (std::is_same_v<F, Fn0> ? 100 : 200) + f(); }
                  std::int32_t pick(const Fn0& f) const { return 100 + f(); }
                  template<class F>
                  std::int32_t pick(const F& f) const { return 200 + f(); }
                  std::int32_t get() const { return 7; }
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
