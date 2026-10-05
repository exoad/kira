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
    fun aShiftByAConstantInsideTheWidthIsBareAndAnyOtherCountIsChecked() {
        val py = python(
            """
            pub fx a: (x: UInt32) UInt32 {
                return x << 2
            }

            pub fx b: (x: UInt32, n: Int64) UInt32 {
                return x >> n
            }

            pub fx c: (x: UInt8, n: UInt8) UInt8 {
                return x << n
            }

            pub fx d: (x: UInt32) UInt32 {
                return x << 32
            }

            pub fx e: (x: Int64) Int64 {
                return x >> -1
            }

            pub fx g: (x: Size) Size {
                return x << 40
            }
            """
        )
        assertTrue(py.contains("def a(x):\n    return _k_u32(x << 2)"), py)
        assertTrue(py.contains("def b(x, n):\n    return x >> _k_count(n, 32)"), py)
        assertTrue(py.contains("def c(x, n):\n    return _k_u8(x << _k_count(n, 8))"), py)
        assertTrue(py.contains("def d(x):\n    return _k_u32(x << _k_count(32, 32))"), "a constant at the width is checked, as kira::shl does:\n$py")
        assertTrue(py.contains("def e(x):\n    return x >> _k_count(-1, 64)"), py)
        assertTrue(py.contains("def g(x):\n    return _k_u64(x << 40)"), "Size is 64 bits on the py target:\n$py")
        assertTrue(py.contains("def _k_count(n, bits):\n    if 0 <= n < bits:\n        return n\n    _k_panic(\"shift count out of range\")"), py)
    }

    @Test
    fun aSignedLeftShiftWrapsAndUnsignedRightShiftReadsTheBitsAsUnsigned() {
        val py = python(
            """
            pub fx a: (x: Int32, n: Int32) Int32 {
                return x << n
            }

            pub fx b: (x: Int64) Int64 {
                return x << 63
            }

            pub fx c: (x: Int8, n: Int32) Int8 {
                return x >>> n
            }

            pub fx d: (x: Int16) Int16 {
                return x >> 3
            }

            pub fx e: (x: UInt16) UInt16 {
                return x >>> 15
            }

            pub fx g: (x: Int64) Int64 {
                return 1 << x
            }
            """
        )
        assertTrue(py.contains("return _k_as_i32(x << _k_count(n, 32))"), "C++20 wraps a signed left shift, never Int32's overflow check:\n$py")
        assertTrue(py.contains("return _k_as_i64(x << 63)"), py)
        assertTrue(py.contains("return _k_i8((x & 0xFF) >> _k_count(n, 8))"), py)
        assertTrue(py.contains("def d(x):\n    return x >> 3"), "Python's >> is arithmetic, as C++20's is:\n$py")
        assertTrue(py.contains("def e(x):\n    return x >> 15"), py)
        assertTrue(py.contains("return _k_as_i64(1 << _k_count(x, 64))"), py)
        assertFalse(py.contains("def _k_i32("), py)
    }

    @Test
    fun aShiftKeepsPythonsGroupingOfTheOtherBitwiseOperators() {
        val py = python(
            """
            pub fx a: (x: UInt16, y: UInt16) UInt16 {
                return (x << 4 | y >> 4) ^ (x & y) << 2
            }

            pub fx b: (x: UInt32, y: UInt32) UInt32 {
                return (x + y) >> 1
            }

            pub fx c: (x: Int32) Int32 {
                return (x & 0xFF) >>> 4
            }
            """
        )
        assertTrue(py.contains("return (_k_u16(x << 4) | y >> 4) ^ _k_u16((x & y) << 2)"), py)
        assertTrue(py.contains("return _k_u32(x + y) >> 1"), py)
        assertTrue(py.contains("return _k_as_i32((x & 255 & 0xFFFFFFFF) >> 4)"), py)
    }

    @Test
    fun aBitwiseOrShiftCompoundAssignmentIsTheOperationAssigned() {
        val py = python(
            """
            pub fx f: (v: Int32, n: Int32) Int32 {
                mut x: Int32 = v
                x &= 0x0F
                x |= 0x30
                x ^= v
                x <<= 3
                x <<= n
                x >>= n
                x >>>= 1
                x >>>= n
                return x
            }
            """
        )
        assertTrue(
            py.contains(
                "    x = v\n    x = x & 15\n    x = x | 48\n    x = x ^ v\n    x = _k_as_i32(x << 3)\n    x = _k_as_i32(x << _k_count(n, 32))\n" +
                    "    x = x >> _k_count(n, 32)\n    x = _k_as_i32((x & 0xFFFFFFFF) >> 1)\n    x = _k_as_i32((x & 0xFFFFFFFF) >> _k_count(n, 32))\n"
            ),
            py,
        )
    }

    @Test
    fun anIndexedOrFieldCompoundAssignmentLocatesItsTargetBeforeTheValue() {
        val py = python(
            """
            mut at: Size = 0
            mut xs: List<UInt8> = List<UInt8> { }

            fx next: () Size {
                at += 1
                return at
            }

            fx bit: () Int32 {
                return 3
            }

            class Flags {
                pub mut bits: UInt8 = 0

                pub mut fx set: (n: Int32) Void {
                    bits |= 1 << n
                }
            }

            fx f: (i: Size) Void {
                xs[next()] |= 0x80
                xs[at] <<= bit()
                xs[i] >>>= 1
                xs[0] ^= 0xFF
            }
            """
        )
        assertTrue(py.contains("_k_t0 = _next()\n    _xs[_k_t0] = _xs[_k_t0] | 128"), py)
        assertTrue(py.contains("_k_t1 = _at\n    _xs[_k_t1] = _k_u8(_xs[_k_t1] << _k_count(_bit(), 8))"), py)
        assertTrue(py.contains("_xs[i] = _xs[i] >> 1"), py)
        assertTrue(py.contains("_xs[0] = _xs[0] ^ 255"), py)
        assertTrue(py.contains("self.bits = self.bits | _k_u8(1 << _k_count(n, 8))"), py)
    }

    @Test
    fun kiraMathBindsToItsHelpersEachAFloat64AsCsIs() {
        val py = python(
            """
            use "kira:math"

            pub fx f: (a: Float64, b: Float64) Float64 {
                return sqrt(a) + pow(a, b) + floor(a) + ceil(b) + round(a) + sin(a) + cos(b) + tan(a) + abs(b)
            }
            """
        )
        assertTrue(
            py.contains(
                "return _k_sqrt(a) + _k_pow(a, b) + _k_floor(a) + _k_ceil(b) + _k_round(a) + _k_sin(a) + _k_cos(b) + _k_tan(a) + _k_math.fabs(b)"
            ),
            py,
        )
        listOf("_k_sqrt(v)", "_k_pow(a, b)", "_k_floor(v)", "_k_ceil(v)", "_k_round(v)", "_k_sin(v)", "_k_cos(v)", "_k_tan(v)").forEach {
            assertTrue(py.contains("def $it:"), "no $it:\n$py")
        }
        assertTrue(py.contains("import math as _k_math"), py)
        assertFalse(py.contains("def _k_min"), "only the helpers the module uses:\n$py")
        val code = py.lines().filterNot { it.trimStart().startsWith("#") }
        assertTrue(code.none { Regex("""(?<![\w.])round\(""").containsMatchIn(it) }, "never Python's half-to-even round:\n$py")
    }

    @Test
    fun aMathHelperIsCarriedOnlyWhenUsed() {
        val py = python(
            """
            use "kira:math"

            pub fx f: (a: Float64) Float64 {
                return abs(a)
            }
            """
        )
        assertTrue(py.contains("return _k_math.fabs(a)"), py)
        assertTrue(py.contains("import math as _k_math"), py)
        assertFalse(py.contains("def _k_"), "fabs needs no helper:\n$py")
    }

    @Test
    fun aFloat64AsTextIsTheShortestTextStdToCharsWrites() {
        val py = python(
            """
            pub fx a: (x: Float64) Str {
                return "x=${'$'}{x} m"
            }

            pub fx b: (x: Float64) Str {
                return x as Str
            }
            """
        )
        assertTrue(py.contains("return \"x=\" + _k_ftext(x) + \" m\""), py)
        assertTrue(py.contains("return _k_ftext(x)"), py)
        assertTrue(py.contains("def _k_ftext(v):"), py)
        assertTrue(py.contains("import math as _k_math"), "a helper brings what it uses:\n$py")
        assertFalse(py.contains("repr("), "a Kira name could shadow repr, which PyNames does not reserve:\n$py")
    }

    @Test
    fun fixedAndToHexBindToPrintfsFormats() {
        val py = python(
            """
            pub fx speed: (mps: Float64) Str {
                return "${'$'}{mps.fixed(1)} m/s"
            }

            pub fx ids: (build: UInt32, seq: Int16) Str {
                return build.toHex() + ":" + (seq as UInt16).toHex()
            }
            """
        )
        assertTrue(py.contains("return _k_fixed(mps, 1) + \" m/s\""), py)
        assertTrue(py.contains("return _k_hex(build) + \":\" + _k_hex(_k_u16(seq))"), py)
        assertTrue(py.contains("def _k_fixed(v, places):") && py.contains("def _k_hex(v):"), py)
        assertFalse(py.contains("def _k_ftext"), "only the helpers the module uses:\n$py")
    }

    @Test
    fun theStrMethodsBindToPythonsWithKiraStrsRules() {
        val py = python(
            """
            pub fx a: (s: Str, t: Str) Size {
                return s.length() + s.size() + s.find(t).unwrapOr(0)
            }

            pub fx b: (s: Str, t: Str) Bool {
                return s.isEmpty() || s.contains(t) || s.startsWith(t) || s.endsWith("x") || s.equals(t)
            }

            pub fx c: (s: Str) Str {
                return s.substring(1, 3) + s.charAt(0) + s.trim().toLower() + ("a" + s).toUpper()
            }

            pub fx d: (s: Str) List<Str> {
                return s.split(",")
            }

            pub fx e: (s: Str) Int64 {
                return s.toInt64().unwrapOr(0) + s.hashCode() + (s.toFloat64().unwrapOr(0.0) as Int64)
            }
            """
        )
        assertTrue(py.contains("return _k_u64(_k_u64(len(s) + len(s)) + _k_or(_k_find(s, t), 0))"), py)
        assertTrue(py.contains("return (len(s) == 0) or s.__contains__(t) or s.startswith(t) or s.endswith(\"x\") or (s == t)"), py)
        assertTrue(py.contains("return _k_substr(s, 1, 3) + s[0] + s.strip(\" \\t\\n\\r\").translate(_k_lower) + (\"a\" + s).translate(_k_upper)"), py)
        assertTrue(py.contains("return _k_split(s, \",\")"), py)
        assertTrue(py.contains("_k_toint(s)") && py.contains("_k_strhash(s)") && py.contains("_k_tofloat(s)"), py)
        assertTrue(py.contains("_k_lower = str.maketrans(") && py.contains("import re as _k_re"), "the tables and the regex module come with their users:\n$py")
        assertTrue(py.contains("def _k_tofloat(s):") && py.contains("_k_decimal = _k_re.compile("), py)
    }

    @Test
    fun aStrsBytesAreItsUtf8() {
        val py = python(
            """
            fx f: (s: Str) Size {
                b: List<UInt8> = s.bytes()
                return b.size() + s.bytes().size()
            }
            """
        )
        assertTrue(py.contains("    b = _k_utf8(s)\n    return _k_u64(len(b) + len(_k_utf8(s)))"), "a call's bytearray is its own:\n$py")
        assertTrue(py.contains("return bytearray(s.encode(\"utf-8\", \"surrogatepass\"))"), py)
    }

    @Test
    fun aCharIsItsCodePoint() {
        val py = python(
            """
            SEP: Char = ':'

            class Cursor {
                pub mut last: Char = 'x'
                pub mut none: Char
            }

            pub fx digit: (s: Str, i: Size) Bool {
                return s[i] >= '0' && s.at(i) <= '9' && s[i] != SEP
            }

            pub fx value: (c: Char) Int32 {
                return (c as Int32) - ('0' as Int32)
            }

            pub fx byte: (c: Char) UInt8 {
                return c as UInt8
            }

            pub fx char: (n: Int32, b: UInt8) Str {
                return "${'$'}{n as Char}${'$'}{b as Char}${'$'}{'\n'}" + ((9 as Char) as Str)
            }

            pub fx hex: (crc: UInt16) Str {
                return "0x${'$'}{crc.toHex().padStart(4, '0')}"
            }

            pub fx show: (c: Char) Void {
                trace(c)
            }
            """
        )
        assertTrue(py.contains("_SEP = 58"), "a Char constant is its code:\n$py")
        assertTrue(py.contains("        self.last = 120\n        self.none = 0"), "a Char's zero value is 0:\n$py")
        assertTrue(py.contains("return ord(s[i]) >= 48 and ord(s[i]) <= 57 and ord(s[i]) != _SEP"), py)
        assertTrue(py.contains("return _k_i32(c - 48)"), "an integer type that holds every code point takes the code as it is:\n$py")
        assertTrue(py.contains("return _k_u8(c)"), "a narrower one wraps it:\n$py")
        assertTrue(py.contains("return chr(_k_u8(n)) + chr(b) + chr(10) + chr(9)"), py)
        assertTrue(py.contains("return \"0x\" + _k_hex(crc).rjust(4, chr(48))"), py)
        assertTrue(py.contains("print(chr(c))"), py)
    }

    @Test
    fun chrAndOrdAreNamesPythonReserves() {
        refused(
            """
            fx ord: (c: Char) Int32 {
                return c as Int32
            }
            """,
            "'ord' is a name generated Python uses",
        )
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
    fun aListIsCopiedWhereASecondNameCouldSeeAWrite() {
        val py = python(
            """
            mut seed: List<Int32> = List<Int32> { }

            class Holder {
                require pub mut items: List<Int32>

                pub fx snapshot: () List<Int32> {
                    return items
                }

                pub mut fx grow: () Int32 {
                    items.add(0)
                    return 1
                }
            }

            fx size: (xs: List<Int32>) Size {
                return xs.size()
            }

            fx count: (xs: List<Int32>) Size {
                seed.add(1)
                return xs.size()
            }

            fx lenOf: (xs: List<Int32>, k: Int32) Size {
                return xs.size() + (k as Size)
            }

            fx push: (mut xs: List<Int32>) Int32 {
                xs.add(1)
                return 1
            }

            fx echo: (xs: List<Int32>) List<Int32> {
                return xs
            }

            fx fresh: () List<Int32> {
                mut out: List<Int32> = List<Int32> { }
                out.add(1)
                return out
            }

            fx pick: (c: Bool, a: List<Int32>) List<Int32> {
                return if c { a } else { fresh() }
            }

            fx f: (h: Holder) Void {
                mut a: List<Int32> = fresh()
                mut b: List<Int32> = a
                mut c: List<Int32> = seed
                mut d: List<Int32> = h.items
                a = b
                n: Size = size(a) + size(h.items) + size(seed) + count(seed) + lenOf(a, push(mut a)) + lenOf(h.items, h.grow())
                k: Holder = Holder { a }
            }
            """
        )
        assertTrue(py.contains("        self.items = list(items)"), "__init__ stores a copy of what it is given:\n$py")
        assertTrue(py.contains("        return list(self.items)"), "a field returned is copied:\n$py")
        assertTrue(py.contains("    return list(xs)"), "a parameter returned is copied:\n$py")
        assertTrue(py.contains("    out.append(1)\n    return out"), "a local returned dies, so it is not copied:\n$py")
        assertTrue(py.contains("    return list(a if c else _fresh())"), py)
        assertTrue(py.contains("    a = _fresh()\n    b = list(a)\n    c = list(_seed)\n    d = list(h.items)\n    a = list(b)"), "a variable stored is copied, a call is not:\n$py")
        assertTrue(py.contains("_size(a) + _size(h.items)) + _size(_seed)"), "a pure callee is given the field or global itself:\n$py")
        assertTrue(py.contains("_count(list(_seed))"), "a callee that writes may write the global it is given:\n$py")
        assertTrue(py.contains("_lenOf(list(a), _push(a))"), "a local a later argument writes is copied first:\n$py")
        assertTrue(py.contains("_lenOf(list(h.items), h.grow())"), py)
        assertTrue(py.contains("k = _Holder(a)"), py)
    }

    @Test
    fun aMutListParameterIsTheCallersListAndAnAssignmentKeepsIt() {
        val py = python(
            """
            mut seed: List<Int32> = List<Int32> { }

            class Holder {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut fx reset: (mut xs: List<Int32>) Void {
                    items = List<Int32> { }
                    xs.add(1)
                }
            }

            fx fill: (mut xs: List<Int32>, ys: List<Int32>) Void {
                xs = ys
                xs.add(2)
                xs[0] = 3
            }

            fx f: (h: Holder) Void {
                mut a: List<Int32> = List<Int32> { }
                fill(mut a, seed)
                fill(mut h.items, a)
                fill(mut seed, a)
                h.reset(mut h.items)
                seed = a
                a = seed
            }
            """
        )
        assertTrue(py.contains("def _fill(xs, ys):\n    xs[:] = ys\n    xs.append(2)\n    xs[0] = 3"), py)
        assertTrue(py.contains("        self.items[:] = []\n        xs.append(1)"), "a field keeps its list, which a mut parameter may be:\n$py")
        assertTrue(py.contains("    _fill(a, list(_seed))\n    _fill(h.items, a)\n    _fill(_seed, a)\n    h.reset(h.items)"), py)
        assertTrue(py.contains("    _seed[:] = a\n    a = list(_seed)"), py)
    }

    @Test
    fun listSetAndContainsBindToSetitemAndAnEqualityTest() {
        val py = python(
            """
            fx f: (x: Float64) Bool {
                mut xs: List<Float64> = List<Float64> { }
                xs.add(x)
                xs.set(0, x + 1.0)
                return xs.contains(x)
            }
            """
        )
        assertTrue(py.contains("xs.__setitem__(0, x + 1.0)"), py)
        assertTrue(py.contains("return _k_contains(xs, x)"), py)
        assertTrue(py.contains("def _k_contains(xs, v):"), py)
    }

    @Test
    fun aListOfUInt8IsABytearrayAndAnArrAList() {
        val py = python(
            """
            pub alias Cmd as Arr<UInt8, 4>

            class Buf {
                pub mut data: List<UInt8> = List<UInt8> { }
                pub mut words: Arr<Int32, 3> = Arr<Int32, 3> { }
            }

            fx f: (xs: List<UInt8>, a: Arr<UInt8>) List<UInt8> {
                mut out: List<UInt8> = [0xFF, 0xD8]
                mut one: Arr<UInt8> = [7]
                mut ints: List<Int32> = [1, 2]
                mut p: Cmd = Cmd { }
                p[1] = 3
                mut copy: List<UInt8> = List<UInt8> { values = a }
                out.addAll(xs.toArr())
                out.addAll(a.clone())
                ys: List<UInt8> = xs
                return out
            }
            """
        )
        assertTrue(py.contains("        self.data = bytearray()\n        self.words = [0] * 3"), py)
        assertTrue(py.contains("    out = bytearray((255, 216))\n    one = bytearray((7,))\n    ints = [1, 2]\n    p = bytearray(4)\n    p[1] = 3"), py)
        assertTrue(py.contains("    copy = bytearray(a)"), "a List made from an Arr copies it:\n$py")
        assertTrue(py.contains("    out.extend(_k_copy(xs))\n    out.extend(_k_copy(a))"), py)
        assertTrue(py.contains("    ys = bytearray(xs)"), "a List of UInt8 is copied as a bytearray:\n$py")
    }

    @Test
    fun aViewIsWhatItWasLentFromAndFromAndSliceAreCheckedMemoryviews() {
        val py = python(
            """
            use "kira:bytes"

            class Link {
                pub mut buf: List<UInt8> = List<UInt8> { }

                pub fx head: () UInt16 {
                    return readU16Le(buf, 0)
                }
            }

            fx sum: (v: View<UInt8>) UInt32 {
                mut s: UInt32 = 0
                mut i: Size = 0
                while i < v.size() {
                    s += v[i] as UInt32
                    i += 1
                }
                return s + (v.from(1).size() as UInt32) + (v.slice(0, 1).get(0) as UInt32)
            }

            fx fill: (p: MutView<UInt8>, v: UInt8) Void {
                p[0] = v
                p.set(1, v)
                writeU32Le(p, 2, 0xA1B2C3D4)
                writeF64Le(p.from(6), 0, 1.5)
            }

            fx g: () UInt32 {
                mut xs: List<UInt8> = List<UInt8> { }
                fill(xs.view(), 1)
                fill(xs.from(2), 1)
                ints: List<Int32> = [1, 2]
                return sum(xs) + sum(xs.slice(0, 2)) + readU32Le(xs, 0) + (readF64Le(xs.from(1), 0) as UInt32)
            }
            """
        )
        assertTrue(py.contains("        return _k_rdle(self.buf, 0, 2)"), "a View of a field is the field, never copied:\n$py")
        assertTrue(py.contains("len(_k_from(v, 1))"), py)
        assertTrue(py.contains("_k_slice(v, 0, 1)[0]"), py)
        assertTrue(py.contains("    p[0] = v\n    p.__setitem__(1, v)\n    _k_wrle(p, 2, 4, 2712847316)\n    _k_wrf64(_k_from(p, 6), 0, 1.5)"), py)
        assertTrue(py.contains("    _fill(xs, 1)\n    _fill(_k_from(xs, 2), 1)"), py)
        assertTrue(py.contains("_sum(xs) + _sum(_k_slice(xs, 0, 2))"), py)
        assertTrue(py.contains("_k_rdle(xs, 0, 4)"), py)
        assertTrue(py.contains("_k_rdf64(_k_from(xs, 1), 0)"), py)
        assertTrue(py.contains("import struct as _k_struct"), "_k_rdf64 brings struct:\n$py")
        assertTrue(py.contains("def _k_span(b, at, n):"), py)
    }

    @Test
    fun kiraBytesBigEndianSetIsTheLittleEndianOnesTwin() {
        val py = python(
            """
            use "kira:bytes"

            fx f: (v: View<UInt8>, p: MutView<UInt8>) UInt64 {
                writeU16Be(p, 0, readU16Be(v, 0))
                writeU32Be(p, 2, readU32Be(v, 2))
                writeU64Be(p, 6, readU64Be(v, 6))
                writeF64Be(p, 14, readF64Be(v, 14))
                return readU64Be(p.from(6), 0)
            }
            """
        )
        assertTrue(py.contains("    _k_wrbe(p, 0, 2, _k_rdbe(v, 0, 2))\n    _k_wrbe(p, 2, 4, _k_rdbe(v, 2, 4))\n    _k_wrbe(p, 6, 8, _k_rdbe(v, 6, 8))"), py)
        assertTrue(py.contains("    _k_wrf64be(p, 14, _k_rdf64be(v, 14))\n    return _k_rdbe(_k_from(p, 6), 0, 8)"), py)
        assertTrue(py.contains("def _k_span(b, at, n):") && py.contains("import struct as _k_struct"), "each checks its span as the little-endian ones do:\n$py")
    }

    @Test
    fun aViewsToListIsANewListOfItsElements() {
        val py = python(
            """
            fx f: (v: View<UInt8>, w: View<Int32>) Size {
                a: List<UInt8> = v.slice(1, 2).toList()
                b: List<Int32> = w.toList()
                mut c: List<UInt8> = List<UInt8> { }
                c.add(1)
                d: List<UInt8> = c.from(0).toList()
                return a.size() + b.size() + d.size()
            }
            """
        )
        assertTrue(py.contains("    a = _k_copy(_k_slice(v, 1, 2))\n    b = _k_copy(w)"), "a call's List is its own, never copied again:\n$py")
        assertTrue(py.contains("    d = _k_copy(_k_from(c, 0))"), py)
        assertTrue(py.contains("return bytearray(xs) if isinstance(xs, (bytes, bytearray, memoryview)) else list(xs)"), py)
    }

    @Test
    fun aMutViewOfAnotherElementIsReadAsACopiedSlice() {
        val py = python(
            """
            fx at: (v: View<Int32>, i: Size) Int32 {
                return v[i]
            }

            fx f: () Int32 {
                mut xs: List<Int32> = [1, 2, 3]
                return at(xs.from(1), 0) + at(xs, 2)
            }
            """
        )
        assertTrue(py.contains("return _k_i32(_at(_k_from(xs, 1), 0) + _at(xs, 2))"), py)
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
    fun aMutParameterOtherThanAListIsRefused() {
        refused(
            """
            fx f: (mut x: Int32) Void {
                x = 1
            }
            """,
            "the mut parameter 'x': Int32 (only a List is passed by reference)",
        )
    }

    @Test
    fun aMutViewThatWouldWriteACopiedSliceIsRefused() {
        refused(
            """
            fx fill: (p: MutView<Int32>) Void {
                p[0] = 1
            }
            """,
            "a MutView<Int32> (a MutView<UInt8> is the one that writes through)",
        )
        refused(
            """
            fx f: () Void {
                mut xs: List<Int32> = [1, 2, 3]
                xs.from(1).set(0, 5)
            }
            """,
            "a MutView<Int32>",
        )
    }

    @Test
    fun aViewOfCharIsRefusedAsTextIsAStr() {
        refused(
            """
            fx n: (v: View<Char>) Size {
                return v.size()
            }

            fx f: () Size {
                return n("abc")
            }
            """,
            "the parameter 'v': a View<Char> (text is a Str on the py target)",
        )
        refused(
            """
            fx f: (s: Str) Size {
                return s.view().size()
            }
            """,
            "a View<Char> (text is a Str on the py target)",
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
    fun theErrorsPowCatchesAreNamesPythonReserves() {
        refused(
            """
            pub class OverflowError {
                pub v: Int32 = 0
            }
            """,
            "'OverflowError' is a name generated Python uses",
        )
        refused(
            """
            pub fx ValueError: () Int32 {
                return 1
            }
            """,
            "'ValueError' is a name generated Python uses",
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
    fun float64RemainderIsTheTypersErrorNotTheTargets() {
        val e = PyTestSupport.emit(
            """
            fx f: (x: Float64, y: Float64) Float64 {
                return x % y
            }
            """
        )
        assertNull(e.text)
        assertTrue(e.errors.any { it.contains("% takes integers") }, e.errors.joinToString("\n"))
        assertTrue(e.errors.none { it.contains(PyModuleEmitter.UNSUPPORTED_CODE) }, e.errors.joinToString("\n"))
    }

    @Test
    fun anIntegersAbsHasNoPyBinding() {
        refused(
            """
            fx f: (x: Int32) Int32 {
                return x.abs()
            }
            """,
            "'Int32.abs' (it has no py binding)",
        )
    }

    @Test
    fun kiraMathOnAFloat32IsRefused() {
        refused(
            """
            use "kira:math"

            fx f: (x: Float32) Float32 {
                return sqrt(x)
            }
            """,
            "Float32",
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
