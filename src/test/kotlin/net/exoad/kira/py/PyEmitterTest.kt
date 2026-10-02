package net.exoad.kira.py

import net.exoad.kira.Public
import net.exoad.kira.compiler.backend.codegen.py.PyBindingTable
import net.exoad.kira.compiler.backend.codegen.py.PyModuleEmitter
import net.exoad.kira.compiler.backend.codegen.py.PyRuntime
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The py emitter's lowering, in memory: the shapes Python needs where its operators differ from
 * Kira's, the names hand-written Python sees, and a `py.unsupported` refusal for each construct
 * the target leaves out (DECISIONS rule 0).
 */
class PyEmitterTest {
    private fun python(body: String): String = PyTestSupport.emit(body).python()

    /** The refusal [body] gets: exactly the py target's, naming [construct]. */
    private fun refused(body: String, construct: String) {
        val e = PyTestSupport.emit(body)
        assertNull(e.text, "the module was emitted:\n${e.text}")
        val errors = e.errors
        assertTrue(errors.isNotEmpty(), "no diagnostic")
        assertTrue(errors.all { it.contains("py.unsupported") }, "a refusal that is not the py target's:\n${errors.joinToString("\n")}")
        assertTrue(errors.any { it.contains(construct) && it.contains("is not supported on the py target yet") }, "no refusal names '$construct':\n${errors.joinToString("\n")}")
    }

    // ---- lowering ------------------------------------------------------------------------------

    @Test
    fun integerDivisionTruncatesAndRemainderTakesTheDividendsSign() {
        val py = python(
            """
            pub fx q: (a: Int32, b: Int32) Int32 {
                return a / b
            }

            pub fx r: (a: Int64, b: Int64) Int64 {
                return a % b
            }

            pub fx u: (a: UInt32, b: UInt32) UInt32 {
                return a / b
            }
            """
        )
        assertTrue(py.contains("return _k_divs(a, b, 32)"), py)
        assertTrue(py.contains("return _k_mods(a, b)"), py)
        assertTrue(py.contains("return _k_divu(a, b)"), py)
        assertFalse(py.substringAfter("def q(").contains("//"), "Python's floor division is never emitted for Kira's /:\n$py")
    }

    @Test
    fun eachIntegerTypeWrapsOrChecksItsResult() {
        val py = python(
            """
            pub fx a: (x: UInt8, y: UInt8) UInt8 {
                return x + y
            }

            pub fx b: (x: Int32, y: Int32) Int32 {
                return x * y
            }

            pub fx c: (x: Int8) Int8 {
                return -x
            }

            pub fx d: (x: Size) Size {
                return x - 1
            }

            pub fx e: (x: UInt16) UInt16 {
                return ~x
            }

            pub fx f: (x: Int64) Int32 {
                return x as Int32
            }
            """
        )
        assertTrue(py.contains("return _k_u8(x + y)"), py)
        assertTrue(py.contains("return _k_i32(x * y)"), py)
        assertTrue(py.contains("return _k_i8(-x)"), py)
        assertTrue(py.contains("return _k_u64(x - 1)"), py)
        assertTrue(py.contains("return _k_u16(~x)"), py)
        assertTrue(py.contains("return _k_as_i32(x)"), py)
    }

    @Test
    fun float64DivisionIsIeeesUnlessTheDivisorIsANonzeroConstant() {
        val py = python(
            """
            pub fx a: (x: Float64, y: Float64) Float64 {
                return x / y
            }

            pub fx b: (x: Float64) Float64 {
                return x / 2.0
            }
            """
        )
        assertTrue(py.contains("return _k_fdiv(x, y)"), py)
        assertTrue(py.contains("return x / 2.0"), py)
    }

    @Test
    fun aComparisonOperandIsParenthesizedSoPythonNeverChainsIt() {
        val py = python(
            """
            pub fx a: (x: Int32, y: Int32, z: Bool) Bool {
                return (x < y) == z
            }

            pub fx b: (x: Bool, y: Bool) Bool {
                return !x == y
            }
            """
        )
        assertTrue(py.contains("return (x < y) == z"), py)
        assertTrue(py.contains("return (not x) == y"), py)
    }

    @Test
    fun aUseOfTheStdlibBindsItsMagicFunctions() {
        val py = python(
            """
            use "kira:math"

            pub fx f: (a: Float64, b: Float64) Float64 {
                return max(a, min(b, 1.0))
            }
            """
        )
        assertTrue(py.contains("return _k_max(a, _k_min(b, 1.0))"), py)
        assertTrue(py.contains("def _k_max(a, b):\n    return b if b > a else a"), py)
    }

    @Test
    fun booleanAndUnaryOperatorsKeepKirasGrouping() {
        val py = python(
            """
            pub fx a: (x: Bool, y: Bool, z: Bool) Bool {
                return (x || y) && z
            }

            pub fx b: (x: Bool, y: Bool, z: Bool) Bool {
                return x || y && z
            }

            pub fx c: (p: Int32, q: Int32) Int32 {
                return -(p * q) - (p - q)
            }
            """
        )
        assertTrue(py.contains("return (x or y) and z"), py)
        assertTrue(py.contains("return x or y and z"), py)
        assertTrue(py.contains("return _k_i32(_k_i32(-_k_i32(p * q)) - _k_i32(p - q))"), py)
    }

    @Test
    fun pubNamesStayAndPrivateOnesTakeAnUnderscore() {
        val py = python(
            """
            pub class Meter {
                require pub start: Int32
                require scale: Int32
                pub mut reading: Int32 = 0
                mut ticks: Int32 = 0

                pub mut fx tick: () Int32 {
                    ticks += 1
                    reading = start + ticks * scale
                    return reading
                }

                fx hidden: () Int32 {
                    return ticks
                }
            }

            fx helper: () Int32 {
                return 1
            }

            LIMIT: Int32 = 3
            pub TOP: Int32 = 5
            """
        )
        assertTrue(py.contains("class Meter:"), py)
        assertTrue(py.contains("__slots__ = (\"start\", \"_scale\", \"reading\", \"_ticks\")"), py)
        assertTrue(py.contains("def __init__(self, start, scale):"), py)
        assertTrue(py.contains("def tick(self):"), py)
        assertTrue(py.contains("def _hidden(self):"), py)
        assertTrue(py.contains("def _helper():"), py)
        assertTrue(py.contains("_LIMIT = 3"), py)
        assertTrue(py.contains("TOP = 5"), py)
        assertTrue(py.contains("self.reading = _k_i32(self.start + _k_i32(self._ticks * self._scale))"), py)
    }

    @Test
    fun maybeIsNoneOrTheValue() {
        val py = python(
            """
            pub fx a: (m: Maybe<Float64>) Float64 {
                if m.isNull() {
                    return 0.0
                }
                return m.value
            }

            pub fx b: (x: Float64) Maybe<Float64> {
                if x < 0.0 {
                    return null
                }
                return x
            }
            """
        )
        assertTrue(py.contains("if (m is None):"), py)
        assertTrue(py.contains("return _k_value(m)"), py)
        assertTrue(py.contains("return None"), py)
        assertTrue(py.contains("return x"), py)
    }

    @Test
    fun anAssignmentWhoseValueHasAnEffectComputesItsIndexFirst() {
        val py = python(
            """
            mut at: Size = 0
            mut xs: List<Int32> = List<Int32> { }

            fx bump: () Int32 {
                at += 1
                return 7
            }

            fx f: (i: Size) Void {
                xs[at] = bump()
                xs[i] = bump()
                xs[at] = 3
                xs[at] += bump()
            }
            """
        )
        assertTrue(py.contains("_k_t0 = _at\n    _xs[_k_t0] = _bump()"), py)
        assertTrue(py.contains("_xs[i] = _bump()"), "a local index cannot change: $py")
        assertTrue(py.contains("_xs[_at] = 3"), "a pure value needs no temporary: $py")
        assertTrue(py.contains("_k_t1 = _at\n    _xs[_k_t1] = _k_i32(_xs[_k_t1] + _bump())"), py)
        assertTrue(py.contains("global _at"), py)
    }

    @Test
    fun aGlobalAssignedInInitiallyIsDeclaredGlobalInInit() {
        val py = python(
            """
            mut made: Int32 = 0

            class Thing {
                pub mut id: Int32 = 0

                initially {
                    made += 1
                    id = made
                }
            }
            """
        )
        assertTrue(py.contains("    def __init__(self):\n        global _made\n        self.id = 0\n        _made = _k_i32(_made + 1)\n        self.id = _made"), py)
    }

    @Test
    fun theRuntimeCarriesOnlyWhatTheModuleUses() {
        val py = python(
            """
            pub fx a: (x: Float64, y: Float64) Float64 {
                return x / y
            }
            """
        )
        assertTrue(py.contains("def _k_fdiv(a, b):"), py)
        assertTrue(py.contains("import math as _k_math"), "a helper brings what it uses:\n$py")
        assertFalse(py.contains("def _k_divs"), py)
        assertFalse(py.contains("def _k_panic"), py)
        assertTrue(py.startsWith("# Generated by Kira test (--target py) from "), py)
    }

    @Test
    fun everyPyBindingNamesItsReceiverFirstAndEachPlaceholderOnce() {
        val table = PyBindingTable().apply { loadDir(File(PyTestSupport.repoRoot, "kira").toPath()) }
        val runtime = PyRuntime(File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText())
        assertTrue(table.all().size >= 13, "py bindings: ${table.all().keys}")
        table.all().forEach { (key, binding) ->
            assertTrue(binding.isOrdered, "$key: ${binding.expr}")
            PyRuntime.HELPER.findAll(binding.expr).forEach { assertTrue(it.value in runtime.names, "$key names ${it.value}, which the runtime lacks") }
        }
    }

    @Test
    fun theRuntimeComesFromBesideTheProgramsStdlibNotFromAProcessWideSetting() {
        // Another test in the same JVM may leave the stdlib registry pointing anywhere (the full
        // suite did: every emit here then failed on a missing runtime.py).
        val saved = Public.Builtin.intrinsicalStandardLibrarySources
        try {
            Public.Builtin.intrinsicalStandardLibrarySources =
                arrayOf(File(PyTestSupport.repoRoot, "src/test/resources/py-golden/numbers/src/lang/numbers.kira").path)
            val py = python(
                """
                pub fx a: (x: Int32, y: Int32) Int32 {
                    return x / y
                }
                """
            )
            assertTrue(py.contains("def _k_divs(a, b, bits):"), py)
        } finally {
            Public.Builtin.intrinsicalStandardLibrarySources = saved
        }
    }

    @Test
    fun theLadderLowersWithoutARefusal() {
        val ladder = File(PyTestSupport.repoRoot, "src/test/resources/py-golden/ladder/src/firmware/pilot/tools/dash/ladder.kira").readText()
        val e = PyTestSupport.emit(ladder.substringAfter('\n'), uri = "firmware:pilot.tools.dash.ladder")
        val py = e.python()
        assertTrue(py.contains("class Ladder:"), py)
        assertTrue(py.contains("def __init__(self, level, top):"), py)
        assertTrue(py.contains("def update(self, deliveredKbs, offeredKbs, ageMs, dropped, nowS, fps):"), py)
        assertEquals(emptyList(), e.errors)
    }

    // ---- refusals ------------------------------------------------------------------------------

    @Test
    fun inheritanceIsRefusedSoOq2NeverArises() {
        refused(
            """
            class Base {
                pub mut seen: Int32 = 0
            }

            class Kid: Base {
                pub k: Int32 = 5
            }
            """,
            "the subclass Kid (class inheritance)",
        )
    }

    @Test
    fun aTraitIsRefused() {
        refused(
            """
            trait Shape {
                pub fx area: () Float64;
            }
            """,
            "the trait Shape",
        )
    }

    @Test
    fun anEnumIsRefused() {
        refused(
            """
            enum Mode {
                A,
                B
            }
            """,
            "the enum Mode",
        )
    }

    @Test
    fun float32IsRefused() {
        refused(
            """
            pub fx f: (x: Float32) Float32 {
                return x
            }
            """,
            "Float32",
        )
    }

    @Test
    fun aGenericClassIsRefused() {
        refused(
            """
            class Box<T> {
                require pub v: T
            }
            """,
            "the generic class Box",
        )
    }

    @Test
    fun aForLoopIsRefused() {
        refused(
            """
            fx f: () Void {
                for i: Int32 in 0..3 {
                    trace(i)
                }
            }
            """,
            "a for loop (write it as a while loop)",
        )
    }

    @Test
    fun aMutParameterIsRefused() {
        refused(
            """
            fx f: (mut x: Int32) Void {
                x = 1
            }
            """,
            "the mut parameter 'x'",
        )
    }

    @Test
    fun aListThatWouldBeCopiedIsRefused() {
        refused(
            """
            fx f: () Int32 {
                mut xs: List<Int32> = List<Int32> { }
                ys: List<Int32> = xs
                return 0
            }
            """,
            "a List copied, passed or returned",
        )
        refused(
            """
            fx f: (xs: List<Int32>) Size {
                return xs.size()
            }
            """,
            "a List parameter 'xs'",
        )
    }

    @Test
    fun aShadowingLocalIsRefused() {
        refused(
            """
            fx f: () Int32 {
                x: Int32 = 1
                if x > 0 {
                    x: Int32 = 2
                    return x
                }
                return x
            }
            """,
            "a local 'x' that shadows another",
        )
    }

    @Test
    fun aNamePythonReservesIsRefused() {
        refused(
            """
            fx f: () Int32 {
                len: Int32 = 1
                return len
            }
            """,
            "'len' is a name generated Python uses",
        )
    }

    @Test
    fun aValueGivenToADefaultedFieldIsRefused() {
        refused(
            """
            class Box {
                pub v: Int32 = 0
            }

            fx f: () Int32 {
                b: Box = Box { v = 3 }
                return b.v
            }
            """,
            "a value given to the defaulted field 'v'",
        )
    }

    @Test
    fun aLambdaIsRefused() {
        refused(
            """
            fx f: () Int32 {
                g: Fx<Tuple1<Int32>, Int32> = fx (x: Int32) Int32 {
                    return x
                }
                return g(1)
            }
            """,
            "Fx value",
        )
    }

    @Test
    fun aFloatInAnInterpolationIsRefused() {
        refused(
            """
            fx f: (x: Float64) Str {
                return "x=${'$'}{x}"
            }
            """,
            "a Float64 as text",
        )
    }

    @Test
    fun aShiftIsRefused() {
        refused(
            """
            fx f: (x: UInt32) UInt32 {
                return x << 2
            }
            """,
            "the operator <<",
        )
    }

    @Test
    fun printOtherThanTraceIsRefused() {
        refused(
            """
            fx f: () Void {
                print("x")
            }
            """,
            "'print' (only trace prints on the py target)",
        )
    }

    @Test
    fun aMapIsRefused() {
        refused(
            """
            fx f: () Int32 {
                m: Map<Str, Int32> = Map<Str, Int32> { }
                return 0
            }
            """,
            "the type Map",
        )
    }

    @Test
    fun aUseOfAnotherWorkspaceModuleIsRefused() {
        val unit = net.exoad.kira.types.TyperTestSupport.unitOf(
            net.exoad.kira.types.TyperTestSupport.module("test:lib", "pub fx one: () Int32 {\n    return 1\n}"),
            net.exoad.kira.types.TyperTestSupport.module("test:main", "use \"test:lib\"\n\nfx f: () Int32 {\n    return lib.one()\n}"),
        )
        val (diagnostics, _) = net.exoad.kira.compiler.backend.codegen.py.KiraPyBackend.plan(unit, PyTestSupport.repoRoot.toPath(), version = "test")
        val errors = diagnostics.filter { it.isError }.map { it.render() }
        assertTrue(errors.any { it.contains("a use of another module ('test:lib')") && it.contains(PyModuleEmitter.UNSUPPORTED_CODE) }, errors.joinToString("\n"))
    }
}
