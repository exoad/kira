package net.exoad.kira.cpp.ffi

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@_constructible` on an `@_extern` struct: `T { }` builds the C++ object by value with its
 * default constructor, as a local, a global and a field of a class, and Kira never copies it.
 * The run rows use a header whose class has its copy constructor deleted, so a copy anywhere
 * in the emitted C++ fails to compile.
 */
class ExternConstructibleTest {
    private val module = Module(
        "app:srvo",
        """
        $SERVO

        arm: Mutable<Servo> { }

        Rig: Mutable<Any> {
            pub servo: Mutable<Servo> { }
            pub start: (pin: Int32) Int32 {
                servo.attach(pin, 500, 2500)
            }
        }

        pub local: () Int32 {
            s: Mutable<Servo> { }
            fresh: Bool = !s.attached()
            s.attach(3, 500, 2500)
            s.writeMicroseconds(1500)
            fresh && s.attached() ? s.us() : -2
        }

        pub still: () Int32 {
            s: Servo { }
            s.attached() ? 1 : 0
        }

        pub global: () Int32 {
            arm.attach(7, 500, 2500)
            arm.writeMicroseconds(1200)
            arm.detach()
            arm.attached() ? -1 : arm.us()
        }

        pub field: () Int32 {
            r: Rig { }
            pin: Int32 = r.start(9)
            r.servo.writeMicroseconds(1700)
            pin * 10000 + r.servo.us()
        }
        """,
    )

    private val tree by lazy {
        CppExprTestSupport.emit("ffi-constructible", listOf(module)).also { Files.writeString(it.root.resolve("srv.hxx"), SRV) }
    }

    @Test
    fun eachObjectIsBuiltInPlaceAndCalledOnItself() {
        val source = tree.source(module)
        listOf(
            "::srv::Servo s = ::srv::Servo{};",
            "const ::srv::Servo s = ::srv::Servo{};",
            "::srv::Servo arm = ::srv::Servo{};",
            "::srv::Servo servo;",
            "s.attach(std::int32_t{3}, std::int32_t{500}, std::int32_t{2500})",
            "arm.writeMicroseconds(std::int32_t{1200})",
            "r->servo.writeMicroseconds(std::int32_t{1700})",
        ).forEach { assertTrue(source.contains(it), "missing '$it' in:\n$source") }
    }

    @Test
    fun theDriftCheckStatesTheDefaultConstructorAndEveryMember() {
        val header = tree.header(module)
        listOf(
            "static_assert(std::is_default_constructible_v<srv::Servo>, \"Kira's Servo { } ${CppExternEmitter.DRIFT_MESSAGE}: it has no default constructor\");",
            "KIRA_EXTERN_CHECK(std::declval<srv::Servo&>().attach(kira::ffi::arg<std::int32_t>(), kira::ffi::arg<std::int32_t>(), kira::ffi::arg<std::int32_t>()), std::int32_t, \"Servo.attach\");",
            "KIRA_EXTERN_CHECK((std::declval<srv::Servo&>().writeMicroseconds(kira::ffi::arg<std::int32_t>()), 0), int, \"Servo.writeMicroseconds\");",
            "KIRA_EXTERN_CHECK(std::declval<const srv::Servo&>().attached(), bool, \"Servo.attached\");",
        ).forEach { assertTrue(header.contains(it), "missing '$it' in:\n$header") }
    }

    @TestFactory
    fun everyRowRunsOnEveryCompiler(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("constructible extern [${tc.id}]") {
            val rows = listOf("local" to 1500, "still" to 0, "global" to 1200, "field" to 91700)
            val driver = buildString {
                append("#include \"").append(module.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                append(CppExprTestSupport.CHECK_PRELUDE)
                append("\nint main()\n{\n")
                rows.forEach { (fn, want) ->
                    append("    {\n        const std::int32_t got = srvo::$fn();\n")
                    append("        std::printf(\"  $fn = %d\\n\", static_cast<int>(got));\n")
                    append("        check(got == $want, \"$fn() is $want\");\n    }\n")
                }
                append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                append("    return failures == 0 ? 0 : 1;\n}\n")
            }
            val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
            assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
        }
    }

    /** A header whose class lost its default constructor fails the build with Kira's message. */
    @TestFactory
    fun aClassWithoutADefaultConstructorFailsTheDriftCheck(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("constructible drift [${tc.id}]") {
            Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
            val located = CppToolchains.requireOrSkip(tc)
            val drifted = CppExprTestSupport.emit("ffi-constructible-drift-${tc.id}", listOf(module))
            Files.writeString(drifted.root.resolve("srv.hxx"), SRV.replace("Servo() = default;", "explicit Servo(std::int32_t pin) : pin_(pin) {}"))
            val result = CppCompileSupport.compile(
                sources = drifted.sources(),
                includeDirs = listOf(drifted.root.toFile(), drifted.runtimeDir.toFile()),
                defines = emptyList(),
                toolchain = located,
                profile = CppProfile.forToolchain(tc),
                outDir = drifted.root.resolve("out/${tc.id}").toFile(),
            )
            assertTrue(!result.success, "${tc.id}: the drifted header built")
            assertTrue(result.diagnostics.contains("Kira's Servo { } ${CppExternEmitter.DRIFT_MESSAGE}: it has no default constructor"), "${tc.id}:\n${result.describe()}")
        }
    }

    @Test
    fun noValueOfItIsCopiedAssignedPassedReturnedOrCaptured() {
        val refused = Module(
            "app:srvx",
            """
            $SERVO

            spare: Mutable<Servo> { }

            Pair: Struct {
                pub servo: Servo { }
            }

            take: (x: Servo) Bool {
                x.attached()
            }

            back: () Servo {
                Servo { }
            }

            pub bad: () Void {
                s: Mutable<Servo> { }
                t: Servo = s
                s = Servo { }
                u: Mutable<Servo> = spare
                b: Bool = take(s)
                f: Fx<T0, Bool> = () Bool { s.attached() }
            }
            """,
        )
        val result = CppExprTestSupport.emitRefused("ffi-constructible-copy", listOf(refused))
        val copies = result.diagnostics.filter { it.isError && it.code == "rules.extern.copy" }
        val lines = copies.mapNotNull { it.position?.lineNumber }.toSortedSet()
        val text = result.diagnostics.joinToString("\n") { it.render() }
        // Pair's field; back's construction; t's copy; the assignment and its construction; u's copy; the argument; the capture.
        assertEquals(sortedSetOf(12, 20, 25, 26, 27, 28, 29), lines, text)
        assertTrue(copies.any { it.message.contains("'Pair' is a value Kira copies, so it cannot hold the Servo 'servo'") }, text)
        assertTrue(copies.any { it.message.contains("This lambda captures the Servo 's' by copy") }, text)
        assertTrue(copies.any { it.message.contains("'Servo { }' builds a C++ object in place, so it is only the initializer of a declaration") }, text)
    }

    @Test
    fun theConstructionTakesNoValuesAndAMutatingCallNeedsAMutableBinding() {
        val refused = Module(
            "app:srvy",
            """
            $SERVO

            pub bad: () Void {
                s: Servo { }
                s.detach()
                t: Mutable<Servo> = Servo { 3 }
            }
            """,
        )
        val text = CppExprTestSupport.emitRefused("ffi-constructible-values", listOf(refused)).diagnostics.joinToString("\n") { it.render() }
        assertTrue(text.contains("rules.mutability.method"), text)
        assertTrue(text.contains("Servo is built by its C++ default constructor and takes no values: write 'Servo { }'"), text)
    }

    @Test
    fun anExternTypeWithoutTheMarkerIsStillMadeByTheCppSide() {
        val refused = Module(
            "app:srvz",
            """
            pub @_extern(cpp = "srv::Servo", header = "srv.hxx") Servo: Struct {
                pub attached: () Bool
            }

            pub bad: () Bool {
                s: Servo { }
                s.attached()
            }
            """,
        )
        val text = CppExprTestSupport.emitRefused("ffi-constructible-unmarked", listOf(refused)).diagnostics.joinToString("\n") { it.render() }
        assertTrue(text.contains("Servo is made by the C/C++ side; call the function that returns one"), text)
    }

    private fun semantic(body: String): List<String> {
        val uri = "test:constructible"
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = true,
        )
        return result.semanticResults!!.diagnostics.map { it.message }
    }

    private fun assertRefused(body: String, fragment: String, what: String) {
        val messages = semantic(body)
        assertTrue(messages.any { it.contains(fragment) }, "$what: expected '$fragment', got:\n${messages.joinToString("\n")}")
    }

    @Test
    fun theOtherTargetsRefuseTheMarker() {
        val previous = GeneratedProvider.outputMode
        try {
            for (mode in listOf(GeneratedProvider.OutputTarget.C, GeneratedProvider.OutputTarget.JS, GeneratedProvider.OutputTarget.PY)) {
                GeneratedProvider.outputMode = mode
                for (body in listOf(SERVO, SERVO.replace("@_constructible @_extern(cpp = \"srv::Servo\", header = \"srv.hxx\")", "@_extern(cpp = \"srv::Servo\", header = \"srv.hxx\") @_constructible"))) assertRefused(
                    body,
                    "@_constructible on 'Servo' builds a C++ object by value with its default constructor, which reaches --target cpp only: " +
                        "the ${mode.name} backend has no C++ class to construct",
                    mode.name,
                )
            }
            for (mode in listOf(GeneratedProvider.OutputTarget.CPP, GeneratedProvider.OutputTarget.NONE)) {
                GeneratedProvider.outputMode = mode
                assertEquals(emptyList(), semantic(SERVO), mode.name)
            }
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }

    @Test
    fun theMarkerGoesOnAnExternStructOnly() {
        assertRefused(
            "pub @_constructible @_extern(cpp = \"srv::Servo\", header = \"srv.hxx\") Servo: Mutable<Any> {\n    pub attached: () Bool\n}",
            "declare it as an @_extern struct ('Servo: Struct { ... }'), not a class or an @_opaque handle",
            "a class",
        )
        assertRefused(
            "pub @_constructible @_opaque @_extern(cpp = \"srv::Servo\", header = \"srv.hxx\") Servo: Struct {\n    pub attached: () Bool\n}",
            "not a class or an @_opaque handle",
            "an opaque handle",
        )
        assertRefused(
            "pub @_constructible Point: Struct {\n    pub x: Int32 = 0\n}",
            "only an @_extern struct is built by its C++ default constructor",
            "a Kira struct",
        )
        assertRefused(
            "pub @_constructible(1) @_extern(cpp = \"srv::Servo\", header = \"srv.hxx\") Servo: Struct {\n    pub attached: () Bool\n}",
            "@_constructible takes no arguments",
            "an argument",
        )
    }

    private companion object {
        const val SERVO = """pub @_constructible @_extern(cpp = "srv::Servo", header = "srv.hxx") Servo: Struct {
            pub @_mutates attach: (pin: Int32, minUs: Int32, maxUs: Int32) Int32
            pub @_mutates writeMicroseconds: (us: Int32) Void
            pub @_mutates detach: () Void
            pub attached: () Bool
            pub us: () Int32
        }"""

        /** arduino-pico's Servo, cut down, with its copy deleted: any copy in the emitted C++ fails to compile. */
        val SRV = """
            #pragma once
            #include <cstdint>

            namespace srv
            {
              class Servo
              {
              public:
                  Servo() = default;
                  Servo(const Servo&) = delete;
                  Servo& operator=(const Servo&) = delete;
                  Servo(Servo&&) = default;

                  std::int32_t attach(std::int32_t pin, std::int32_t minUs, std::int32_t maxUs)
                  {
                      pin_ = pin;
                      lo_ = minUs;
                      hi_ = maxUs;
                      return pin;
                  }

                  void writeMicroseconds(std::int32_t us) { us_ = us; }
                  void detach() { pin_ = -1; }
                  bool attached() const { return pin_ >= 0; }
                  std::int32_t us() const { return us_; }

              private:
                  std::int32_t pin_ = -1;
                  std::int32_t lo_ = 0;
                  std::int32_t hi_ = 0;
                  std::int32_t us_ = 0;
              };
            }
        """.trimIndent() + "\n"
    }
}
