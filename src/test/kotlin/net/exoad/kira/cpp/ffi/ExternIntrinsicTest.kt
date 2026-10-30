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
            pub @_extern(cpp = "bibo::Scan", header = "car.hxx") Scan: Struct {
                pub ahead: () Float32;
            }

            pub @_extern(cpp = "bibo::Car", header = "car.hxx") Car: Mutable<Any> {
                pub @_mutates arm: () Bool;
                pub @_extern(cpp = "Ok") ok: () Bool;
            }

            pub @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h") DrawList: Mutable<Any> {
                pub @_mutates @_extern(cpp = "AddLine") addLine: (color: UInt32) Void;
            }

            pub @_extern(cpp = "bibo::openCar", header = "car.seam.hxx") openCar: () Car;

            pub @_extern("c_cos", c = "cos", cpp = "std::cos", header = "<cmath>") cosine: (x: Float64) Float64;

            pub @_extern(cpp = "ImGuiWindowFlags_None", header = "imgui.h") WINDOW_FLAGS_NONE: Int32;
            """
        )
        assertEquals(emptyList(), messages)
    }

    @Test
    fun theTypedModelKeepsEveryParameterAndTheMemberInheritsItsOwner() {
        val p = TyperTestSupport.snippet(
            """
            pub @_extern(cpp = "bibo::Car", header = "car.hxx") Car: Mutable<Any> {
                pub @_mutates arm: () Bool;
                pub @_extern(cpp = "Ok") ok: () Bool;
            }
            pub @_extern("c_cos", c = "cos", cpp = "std::cos", header = "<cmath>") cosine: (x: Float64) Float64;
            pub @_extern(cpp = "ImGuiWindowFlags_None", header = "imgui.h") WINDOW_FLAGS_NONE: Int32;
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
            pub @_extern(cxx = "bibo::Car", header = "car.hxx") Car: Any { }
            """,
            "@_extern does not take 'cxx'", "cpp =", "c =", "header =",
        )
    }

    @Test
    fun twoPositionalStringsAreRefused() {
        assertRefused(
            """
            pub @_extern("bibo::openCar", "car.hxx") openCar: () Int32;
            """,
            "@_extern accepts at most one positional string",
        )
    }

    @Test
    fun aNonStringParameterIsRefused() {
        assertRefused(
            """
            pub @_extern(cpp = 16) sixteen: () Int32;
            """,
            "'cpp' is a string literal",
        )
        assertRefused(
            """
            pub @_extern(3) three: () Int32;
            """,
            "symbol is a string literal",
        )
    }

    @Test
    fun aConstantIsAModuleLevelDeclaration() {
        // Measured before: a local `@_extern(cpp = "probe::LOCAL") loc: Int32 = 3` inside a body
        // got no diagnostic, and the C++ emitter lowered it as a plain local, marker dropped.
        assertRefused(
            """
            main: () Int32 {
                @_extern(cpp = "probe::LOCAL") loc: Int32 = 3
                loc
            }
            """,
            "@_extern names a module-level constant; 'loc' is declared inside a body",
        )
        assertRefused(
            """
            pub @_extern(cpp = "bibo::Pt", header = "car.hxx") Pt: Struct {
                pub @_extern(cpp = "X") x: Int32 = 0
            }
            """,
            "@_extern names a module-level constant; 'x' is declared inside a class or struct",
        )
    }

    /**
     * `T { }` of an extern class or struct is its C++ default construction (item 2): accepted with no values, and
     * refused with values, since the C++ side owns a constructor's arguments. An `@_opaque` handle is never built here.
     */
    @Test
    fun anExternClassOrStructIsBuiltWithNoValues() {
        val extern = """
            pub @_extern(cpp = "probe::Car", header = "probe.hxx") Car: Any {
                pub speed: () Int32;
            }

            pub @_extern(cpp = "ImVec2", header = "imgui.h") Vec2: Struct {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }
        """
        TyperTestSupport.expectNoErrors(TyperTestSupport.snippet("$extern\n\nmain: () Int32 {\n    car: Car = Car { }\n    v: Vec2 = Vec2 { }\n    0\n}"))
        val withValues = TyperTestSupport.snippet("$extern\n\nmain: () Int32 {\n    car: Car = Car { 1 }\n    0\n}")
        assertTrue(withValues.diagnostics.any { it.message.contains("Car is made by the C/C++ side") }, withValues.diagnostics.map { it.message }.toString())
        val opaque = TyperTestSupport.snippet(
            """
            pub @_opaque @_extern(cpp = "probe::Handle", header = "probe.hxx") Handle: Any {
            }

            main: () Int32 {
                h: Handle = Handle { }
                0
            }
            """
        )
        assertTrue(opaque.diagnostics.any { it.message.contains("Handle is made by the C/C++ side") }, opaque.diagnostics.map { it.message }.toString())
    }

    /** `@_enum` types a parameter of an extern function, so it is refused on a Kira function or a prototype, and it names a C++ enum. */
    @Test
    fun anEnumMarkerNeedsAnExternOnTheSameFunction() {
        assertRefused(
            """
            pub @_enum(mode = "bibo::Mode") setPlain: (mode: Int32) Void;
            """,
            "@_enum needs @_extern on the same function",
        )
        assertRefused(
            """
            pub @_enum(mode = 3) @_extern(cpp = "bibo::setMode", header = "bibo.hxx") setMode: (mode: Int32) Void;
            """,
            "@_enum's 'mode' is a string literal, not IntegerLiteral",
        )
    }

    @Test
    fun aMethodTakesTheMarkerOnlyInsideAnExternClass() {
        assertRefused(
            """
            pub Counter: Any {
                pub @_extern(cpp = "VtxCount") count: () Int32;
            }
            """,
            "@_extern on the method 'count': its class or struct 'Counter' must be extern itself",
        )
        // Inside an extern class or struct it names the C++ member (design 7.2).
        assertEquals(
            emptyList(),
            semantic(
                """
                pub @_opaque @_extern(cpp = "ImDrawList", header = "imgui.h") DrawList: Any {
                    pub @_extern(cpp = "VtxCount") count: () Int32;
                }
                pub @_extern(cpp = "bibo::Scan", header = "car.hxx") Scan: Struct {
                    pub @_extern(cpp = "Ahead") ahead: () Float32;
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
            "pub @_extern(c = \"INT_MAX\", header = \"limits.h\") IMAX: Int32;" to "@_extern on the constant 'IMAX' reaches C++ only (--target cpp, design 7.2): the C backend takes @_extern on a free function",
            "pub @_extern(\"INT_MAX\") IMAX: Int32;" to "@_extern on the constant 'IMAX' reaches C++ only",
            "pub @_extern(cpp = \"ImVec2\", header = \"imgui.h\") Vec2: Struct { pub x: Float32 = 0.0 }" to "@_extern on the struct 'Vec2' reaches C++ only",
            "pub @_extern(cpp = \"bibo::Car\", header = \"car.hxx\") Car: Any { pub ok: () Bool; }" to "@_extern on the class 'Car' reaches C++ only",
            "pub @_opaque @_extern(cpp = \"ImDrawList\", header = \"imgui.h\") DrawList: Any {\n    pub @_extern(cpp = \"VtxCount\") count: () Int32;\n}" to "@_extern on the method 'count' reaches C++ only",
            // A free function whose marker names a C++ symbol and no C one: bound to its Kira name,
            // the C backend wrote `extern Int32 twice(Int32 n);` and called it (measured).
            "pub @_extern(cpp = \"probe::twice\", header = \"probe.hxx\") twice: (n: Int32) Int32;" to "@_extern on the function 'twice' names a C++ symbol only (cpp =); the C backend calls the C symbol: add c = \"name\"",
        )
        val previous = GeneratedProvider.outputMode
        try {
            for (mode in listOf(GeneratedProvider.OutputTarget.C, GeneratedProvider.OutputTarget.JS)) {
                GeneratedProvider.outputMode = mode
                for ((body, message) in cases) {
                    assertRefused(body, message.replace("the C backend", "the ${mode.name} backend"))
                }
                // A free function with a C symbol is what these backends lower: still accepted, a cpp = beside it or not.
                assertEquals(emptyList(), semantic("pub @_extern(\"fopen\") openFile: (path: Str) Int32;"), mode.name)
                assertEquals(emptyList(), semantic("pub @_extern(c = \"twice_c\", cpp = \"probe::twice\", header = \"probe.hxx\") twice: (n: Int32) Int32;"), mode.name)
                assertEquals(emptyList(), semantic("pub @_extern(header = \"probe.h\") twice: (n: Int32) Int32;"), "${mode.name}: the Kira name is the symbol")
            }
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.CPP
            val everyTarget = cases.map { it.first }.filterNot { it.startsWith("pub @_extern(\"INT_MAX\")") }.joinToString("\n")
            assertEquals(emptyList(), semantic(everyTarget), "cpp takes every target of 7.2")
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }

    /** `raises =` names Python exceptions (D69), and py binds an extern by its Kira name alone (D67). */
    @Test
    fun raisesReachesPyOnlyAndPyTakesNoOtherName() {
        val raises = "pub @_extern(raises = \"OSError\") readText: (path: Str) Str;"
        val previous = GeneratedProvider.outputMode
        try {
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NONE
            assertEquals(emptyList(), semantic(raises))
            for (mode in listOf(GeneratedProvider.OutputTarget.C, GeneratedProvider.OutputTarget.JS, GeneratedProvider.OutputTarget.CPP)) {
                GeneratedProvider.outputMode = mode
                assertRefused(raises, "@_extern's raises = names Python exceptions, which the ${mode.name} backend has none of")
            }
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.PY
            assertEquals(emptyList(), semantic("$raises\npub @_extern home: () Str;"))
            listOf(
                "pub @_extern(cpp = \"ns::f\", header = \"f.hxx\") f: () Int32;" to "names cpp =, header =",
                "pub @_extern(c = \"f_c\", raises = \"OSError\") f: () Int32;" to "names c =",
                "pub @_extern(\"f_c\") f: () Int32;" to "names a positional symbol",
            ).forEach { (body, names) ->
                assertRefused(body, "@_extern on 'f' $names; the py backend binds an extern to the function of its own name in the module's sidecar")
            }
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }

    /** A method of an @_opaque class takes `raises =` for py (D70), so under none too; elsewhere its class must be extern. */
    @Test
    fun anOpaqueClassesMethodTakesTheMarkerOnPyAndNone() {
        val body = "pub @_opaque Sock: Any {\n    pub @_extern(raises = \"TimeoutError\") recv: (n: Int32) List<UInt8>;\n}"
        val previous = GeneratedProvider.outputMode
        try {
            for (mode in listOf(GeneratedProvider.OutputTarget.PY, GeneratedProvider.OutputTarget.NONE)) {
                GeneratedProvider.outputMode = mode
                assertEquals(emptyList(), semantic(body), mode.name)
            }
            for (mode in listOf(GeneratedProvider.OutputTarget.CPP, GeneratedProvider.OutputTarget.C)) {
                GeneratedProvider.outputMode = mode
                assertRefused(body, "@_extern on the method 'recv': its class or struct 'Sock' must be extern itself")
            }
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }
}
