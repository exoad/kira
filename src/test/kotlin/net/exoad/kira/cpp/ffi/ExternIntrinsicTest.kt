package net.exoad.kira.cpp.ffi

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@_extern` as design 7.2 spells it: `cpp =`, `c =`, `header =` and one positional symbol,
 * on a function, a class, a struct, a constant and a method of an extern class; anything
 * else is refused by the semantic pass with a message naming the parameter.
 */
class ExternIntrinsicTest {
    private fun TypedProgram.member(uri: String, name: String) = module(uri)!!.members[name]

    private fun semantic(body: String): List<String> {
        val uri = "test:extern"
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = true,
        )
        return result.semanticResults!!.diagnostics.map { it.message }
    }

    private fun assertRefused(body: String, vararg fragments: String) {
        val messages = semantic(body)
        assertTrue(messages.isNotEmpty(), "expected the semantic pass to refuse:\n$body")
        for (f in fragments) {
            assertTrue(messages.any { it.contains(f) }, "expected a diagnostic containing '$f', got:\n${messages.joinToString("\n")}")
        }
    }

    @Test
    fun everyTargetOfDesign72IsAccepted() {
        val messages = semantic(
            """
            @_extern(cpp = "bibo::Scan", header = "car.hxx")
            pub struct Scan {
                pub fx ahead: () Float32;
            }

            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx arm: () Bool;
                @_extern(cpp = "Ok") pub fx ok: () Bool;
            }

            @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h")
            pub class DrawList {
                @_extern(cpp = "AddLine") pub mut fx addLine: (color: UInt32) Void;
            }

            @_extern(cpp = "bibo::openCar", header = "car.seam.hxx")
            pub fx openCar: () Car;

            @_extern("c_cos", c = "cos", cpp = "std::cos", header = "<cmath>")
            pub fx cosine: (x: Float64) Float64;

            @_extern(cpp = "ImGuiWindowFlags_None", header = "imgui.h")
            pub WINDOW_FLAGS_NONE: Int32;
            """
        )
        assertEquals(emptyList(), messages)
    }

    @Test
    fun theTypedModelKeepsEveryParameterAndTheMemberInheritsItsOwner() {
        val p = TyperTestSupport.snippet(
            """
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx arm: () Bool;
                @_extern(cpp = "Ok") pub fx ok: () Bool;
            }
            @_extern("c_cos", c = "cos", cpp = "std::cos", header = "<cmath>")
            pub fx cosine: (x: Float64) Float64;
            @_extern(cpp = "ImGuiWindowFlags_None", header = "imgui.h")
            pub WINDOW_FLAGS_NONE: Int32;
            """
        )
        TyperTestSupport.expectNoErrors(p)
        val car = p.member("test:main", "Car") as ClassSymbol
        assertEquals(Foreign.Extern(mapOf("cpp" to "bibo::Car", "header" to "car.hxx")), car.foreign)
        assertEquals(Foreign.Extern(emptyMap()), car.method("arm")!!.foreign)
        assertEquals(Foreign.Extern(mapOf("cpp" to "Ok")), car.method("ok")!!.foreign)
        assertEquals(
            Foreign.Extern(mapOf("symbol" to "c_cos", "c" to "cos", "cpp" to "std::cos", "header" to "<cmath>")),
            (p.member("test:main", "cosine") as FnSymbol).foreign,
        )
        assertEquals(
            Foreign.Extern(mapOf("cpp" to "ImGuiWindowFlags_None", "header" to "imgui.h")),
            (p.member("test:main", "WINDOW_FLAGS_NONE") as GlobalSymbol).foreign,
        )
    }

    @Test
    fun anUnknownNamedParameterIsRefusedByName() {
        assertRefused(
            """
            @_extern(cxx = "bibo::Car", header = "car.hxx")
            pub class Car { }
            """,
            "@_extern does not take 'cxx'", "cpp =", "c =", "header =",
        )
    }

    @Test
    fun twoPositionalStringsAreRefused() {
        assertRefused(
            """
            @_extern("bibo::openCar", "car.hxx")
            pub fx openCar: () Int32;
            """,
            "@_extern accepts at most one positional string",
        )
    }

    @Test
    fun aNonStringParameterIsRefused() {
        assertRefused(
            """
            @_extern(cpp = 16)
            pub fx sixteen: () Int32;
            """,
            "'cpp' is a string literal",
        )
        assertRefused(
            """
            @_extern(3)
            pub fx three: () Int32;
            """,
            "symbol is a string literal",
        )
    }

    @Test
    fun aTargetOutsideTheListIsStillRefused() {
        assertRefused(
            """
            @_extern(cpp = "bibo::Level")
            pub enum Level: Int32 { L_A = 0 }
            """,
            "'@_extern' cannot be applied to EnumDecl",
        )
    }

    @Test
    fun aConstantIsAModuleLevelDeclaration() {
        // Measured before: a local `@_extern(cpp = "probe::LOCAL") loc: Int32 = 3` inside a body
        // got no diagnostic, and the C++ emitter lowered it as a plain local, marker dropped.
        assertRefused(
            """
            fx main: () Int32 {
                @_extern(cpp = "probe::LOCAL") loc: Int32 = 3
                return loc
            }
            """,
            "@_extern names a module-level constant; 'loc' is declared inside a body",
        )
        assertRefused(
            """
            @_extern(cpp = "bibo::Pt", header = "car.hxx")
            pub struct Pt {
                @_extern(cpp = "X") pub x: Int32 = 0
            }
            """,
            "@_extern names a module-level constant; 'x' is declared inside a class or struct",
        )
    }

    @Test
    fun aMethodTakesTheMarkerOnlyInsideAnExternClass() {
        assertRefused(
            """
            pub class Counter {
                @_extern(cpp = "VtxCount") pub fx count: () Int32;
            }
            """,
            "@_extern on the method 'count': its class or struct 'Counter' must be extern itself",
        )
        // Inside an extern class or struct it names the C++ member (design 7.2).
        assertEquals(
            emptyList(),
            semantic(
                """
                @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h")
                pub class DrawList {
                    @_extern(cpp = "VtxCount") pub fx count: () Int32;
                }
                @_extern(cpp = "bibo::Scan", header = "car.hxx")
                pub struct Scan {
                    @_extern(cpp = "Ahead") pub fx ahead: () Float32;
                }
                """
            ),
        )
    }

    /**
     * Under `--target c` and `--target js` the backend lowers `@_extern` on a free function
     * only. Measured before on the C backend: an extern constant became a new zero global and
     * the program printed 0 for INT_MAX; an extern class's method became a free C extern of
     * its name, dropping the body of the user's own `fx count`; JS wrote `const IMAX;`. The
     * semantic pass refuses each at its declaration, naming the target, and exits 1 through
     * the CLI's diagnostic count. `--target none` (and every test, mode NONE) takes them all.
     */
    @Test
    fun theCAndJsTargetsTakeAFreeFunctionOnly() {
        val cases = listOf(
            "@_extern(c = \"INT_MAX\", header = \"limits.h\")\npub IMAX: Int32;" to "@_extern on the constant 'IMAX' reaches C++ only (--target cpp, design 7.2): the C backend takes @_extern on a free function",
            "@_extern(\"INT_MAX\")\npub IMAX: Int32;" to "@_extern on the constant 'IMAX' reaches C++ only",
            "@_extern(cpp = \"ImVec2\", header = \"imgui.h\")\npub struct Vec2 { pub x: Float32 = 0.0 }" to "@_extern on the struct 'Vec2' reaches C++ only",
            "@_extern(cpp = \"bibo::Car\", header = \"car.hxx\")\npub class Car { pub fx ok: () Bool; }" to "@_extern on the class 'Car' reaches C++ only",
            "@_opaque @_extern(cpp = \"ImDrawList\", header = \"imgui.h\")\npub class DrawList {\n    @_extern(cpp = \"VtxCount\") pub fx count: () Int32;\n}" to "@_extern on the method 'count' reaches C++ only",
        )
        val previous = GeneratedProvider.outputMode
        try {
            for (mode in listOf(GeneratedProvider.OutputTarget.C, GeneratedProvider.OutputTarget.JS)) {
                GeneratedProvider.outputMode = mode
                for ((body, message) in cases) {
                    assertRefused(body, message.replace("the C backend", "the ${mode.name} backend"))
                }
                // A free function is what these backends lower: still accepted.
                assertEquals(emptyList(), semantic("@_extern(\"fopen\")\npub fx openFile: (path: Str) Int32;"), mode.name)
            }
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.CPP
            val everyTarget = cases.map { it.first }.filterNot { it.startsWith("@_extern(\"INT_MAX\")") }.joinToString("\n")
            assertEquals(emptyList(), semantic(everyTarget), "cpp takes every target of 7.2")
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }
}
