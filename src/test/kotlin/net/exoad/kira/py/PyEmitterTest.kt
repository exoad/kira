package net.exoad.kira.py

import net.exoad.kira.Public
import net.exoad.kira.compiler.backend.codegen.py.PyBinding
import net.exoad.kira.compiler.backend.codegen.py.PyBindingTable
import net.exoad.kira.compiler.backend.codegen.py.PyModuleEmitter
import net.exoad.kira.compiler.backend.codegen.py.PyNames
import net.exoad.kira.compiler.backend.codegen.py.PyRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun aContainerReachedThroughACallIsLocatedBeforeTheKeyAndTheValueAndOnce() {
        val py = python(
            """
            class Holder {
                pub mut items: Map<Str, Int32> = Map<Str, Int32> { }
                pub mut xs: List<Int32> = List<Int32> { }
            }

            fx holderOf: (h: Holder) Holder {
                trace("holder")
                return h
            }

            fx key: () Str {
                trace("key")
                return "k"
            }

            fx val: () Int32 {
                trace("val")
                return 1
            }

            fx f: (h: Holder) Void {
                holderOf(h).items[key()] = val()
                holderOf(h).items[key()] = 5
                holderOf(h).xs[0] += 5
                h.xs[0] = val()
            }
            """
        )
        assertTrue(py.contains("    _k_t0 = _holderOf(h)\n    _k_t1 = _key()\n    _k_t0.items[_k_t1] = _val()\n"), py)
        assertTrue(py.contains("    _holderOf(h).items[_key()] = 5\n"), "a constant value runs nothing, so Python's order is Kira's:\n$py")
        assertTrue(py.contains("    _k_t2 = _holderOf(h)\n    _k_t2.xs[0] = _k_i32(_k_t2.xs[0] + 5)\n"), py)
        assertTrue(py.contains("    h.xs[0] = _val()\n"), py)
    }

    @Test
    fun fmodIsMathFmodWithCsNaNs() {
        val py = python(
            """
            use "kira:math"

            pub fx f: (a: Float64, b: Float64) Float64 {
                return fmod(a, b) + fmod(a + b, 2.0)
            }
            """
        )
        assertTrue(py.contains("return _k_fmod(a, b) + _k_fmod(a + b, 2.0)"), py)
        assertTrue(py.contains("def _k_fmod(a, b):") && py.contains("        return _k_math.nan"), py)
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
        assertTrue(py.contains("return _k_substr(s, 1, 3) + s[0] + s.strip(\" \\t\\n\\v\\f\\r\").translate(_k_lower) + (\"a\" + s).translate(_k_upper)"), py)
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
    fun strOfReadsUtf8AsPythonsReplacingDecode() {
        val py = python(
            """
            fx f: (v: View<UInt8>, xs: List<UInt8>) Str {
                return Str.of(v) + Str.of(xs.from(1)) + Str.of(xs)
            }
            """
        )
        assertTrue(py.contains("return _k_strof(v) + _k_strof(_k_from(xs, 1)) + _k_strof(xs)"), py)
        assertTrue(py.contains("return bytes(v).decode(\"utf-8\", \"replace\")"), py)
        assertTrue(python("fx g: (v: View<UInt8>) Str {\n    return Str.of(bytes = v)\n}").contains("return _k_strof(v)"), "a named argument in its parameter's place")
    }

    @Test
    fun theKotlinNamedStrMethodsKeepKiraStrsRules() {
        val py = python(
            """
            pub fx a: (s: Str, fill: Char) Str {
                return s.padEnd(4, fill) + s.trimStart() + s.trimEnd() + s.replace("a", "")
            }

            pub fx b: (s: Str) List<Str> {
                return s.splitWhitespace()
            }

            pub fx c: (s: Str) Int64 {
                return s.toInt64Radix(16).unwrapOr(0)
            }
            """
        )
        assertTrue(py.contains("return s.ljust(4, chr(fill)) + s.lstrip(\" \\t\\n\\v\\f\\r\") + s.rstrip(\" \\t\\n\\v\\f\\r\") + s.replace(\"a\", \"\")"), py)
        assertTrue(py.contains("return _k_words.findall(s)") && py.contains("_k_words = _k_re.compile(r\"[^ \\t\\n\\r\\v\\f]+\")"), py)
        assertTrue(py.contains("return _k_or(_k_tointr(s, 16), 0)") && py.contains("_k_digits = ") && py.contains("def _k_panic("), py)
    }

    @Test
    fun joinToStringTakesTheListBeforeTheSeparator() {
        val py = python(
            """
            fx f: (xs: List<Str>, sep: Str) Str {
                return xs.joinToString(sep) + "a b".splitWhitespace().joinToString(",")
            }
            """
        )
        assertTrue(py.contains("return _k_join(xs, sep) + _k_join(_k_words.findall(\"a b\"), \",\")"), py)
        assertTrue(py.contains("return sep.join(xs)"), py)
    }

    @Test
    fun aCharsTestsAreAsciisOnItsCodePoint() {
        val py = python(
            """
            fx f: (s: Str, i: Size, c: Char) Bool {
                return s[i].isDigit() || c.isWhitespace() || 'x'.isLetter()
            }
            """
        )
        assertTrue(py.contains("return (48 <= ord(s[i]) <= 57) or (c in _k_spaces) or (120 in _k_letters)"), py)
        assertTrue(py.contains("_k_spaces = _k_builtins.frozenset((9, 10, 11, 12, 13, 32))") && py.contains("_k_letters = _k_builtins.frozenset("), py)
    }

    @Test
    fun aListsSortSumMinAndMaxNameTheirElementType() {
        val py = python(
            """
            class Bag {
                pub mut fs: List<Float64> = List<Float64> { }
                pub mut bs: List<UInt8> = List<UInt8> { }

                pub mut fx tidy: () Float64 {
                    fs.sort()
                    bs.sort()
                    return fs.sum() + (bs.sum() as Float64)
                }
            }

            fx f: (mut xs: List<Int32>, names: List<Str>) Int32 {
                xs.sort()
                return xs.sum() + xs.minOrNull().unwrapOr(0) + (names.maxOrNull().unwrapOr("").length() as Int32)
            }
            """
        )
        assertTrue(py.contains("_k_sort(self.fs, \"Float64\")") && py.contains("_k_sort(self.bs, \"UInt8\")"), py)
        assertTrue(py.contains("_k_sum(self.fs, \"Float64\") + float(_k_sum(self.bs, \"UInt8\"))"), py)
        assertTrue(py.contains("_k_sort(xs, \"Int32\")") && py.contains("_k_sum(xs, \"Int32\")") && py.contains("_k_minof(xs, \"Int32\")") && py.contains("_k_maxof(names, \"Str\")"), py)
        assertTrue(py.contains("return _k_functools.reduce(_k_operator.add, xs, 0.0)") && py.contains("import itertools as _k_itertools"), py)
        assertTrue(py.contains("xs.sort(key=_k_fkey)") && py.contains("import builtins as _k_builtins"), py)
    }

    @Test
    fun aStrIsOrderedAsPythonOrdersIt() {
        val py = python(
            """
            fx f: (a: Str, b: Str) Bool {
                return a < b || a >= "m"
            }
            """
        )
        assertTrue(py.contains("return a < b or a >= \"m\""), py)
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
        assertTrue(py.contains("        self.last = 120 if last is _k_unset else last\n        self.none = 0 if none is _k_unset else none"), "a Char's zero value is 0:\n$py")
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
            "the name 'ord', which generated Python uses, is not supported",
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
        assertTrue(py.contains("def __init__(self, start, scale, reading=_k_unset):"), py)
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
        assertTrue(py.contains("    def __init__(self, id=_k_unset):\n        global _made\n        self.id = 0 if id is _k_unset else id\n        _made = _k_i32(_made + 1)\n        self.id = _made"), py)
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
    fun aFieldIsCopiedForACalleeThatMayWriteWhatItsCallerSeesNotForOneThatOnlyPrints() {
        val py = python(
            """
            fx show: (xs: List<Int32>) Size {
                trace(xs.size())
                return xs.size()
            }

            fx into: (xs: List<Int32>, mut out: List<Int32>) Size {
                out.add(1)
                return xs.size()
            }

            class Holder {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut fx f: () Size {
                    mut out: List<Int32> = List<Int32> { }
                    return show(items) + into(items, mut out)
                }
            }
            """
        )
        assertTrue(py.contains("_show(self.items)"), "a CONFINED callee writes nothing its caller sees:\n$py")
        assertTrue(py.contains("_into(list(self.items), out)"), "a mut operand may be the field:\n$py")
    }

    @Test
    fun aLocalBesideAnImpureSiblingIsCopiedOnlyWhenTheSiblingWritesItByName() {
        val py = python(
            """
            mut cursor: Size = 0

            fx next: () Size {
                cursor += 1
                return cursor - 1
            }

            fx at: (v: List<UInt8>, i: Size) UInt32 {
                return v[i] as UInt32
            }

            fx fill: (p: MutView<UInt8>) Size {
                p[0] = 1
                return 0
            }

            fx push: (mut xs: List<UInt8>) Size {
                xs.add(1)
                return 0
            }

            fx walk: (n: Size) UInt32 {
                mut buf: List<UInt8> = List<UInt8> { }
                buf.add(5)
                mut s: UInt32 = 0
                while cursor < buf.size() {
                    s = s * 33 + at(buf, next())
                }
                s += at(buf, push(mut buf))
                s += at(buf, fill(buf.from(0)))
                s += at(buf, buf.removeAt(0) as Size)
                return s
            }
            """
        )
        assertTrue(py.contains("_at(buf, _next())"), "a call that cannot reach the local leaves it uncopied:\n$py")
        assertTrue(py.contains("_at(bytearray(buf), _push(buf))"), "a mut argument writes it:\n$py")
        assertTrue(py.contains("_at(bytearray(buf), _fill(_k_from(buf, 0)))"), "a MutView of it writes it:\n$py")
        assertTrue(py.contains("_at(bytearray(buf), buf.pop(0))"), "a mut fx on it writes it:\n$py")
    }

    @Test
    fun aGlobalStartsAfterTheGlobalsItReadsAndAConstantIsNeverCopiedAsAnArgument() {
        val py = python(
            """
            A: Arr<Int32, 2> = B
            B: Arr<Int32, 2> = [7, 8]
            mut k: Arr<UInt8, 2> = KEY
            KEY: Arr<UInt8, 2> = [0xAB, 0xCD]

            fx total: (xs: Arr<Int32, 2>) Int32 {
                return xs[0] + xs[1]
            }

            fx f: () Int32 {
                return total(A) + total(B)
            }
            """
        )
        assertTrue(py.contains("_B = [7, 8]\n_A = _B\n_KEY = bytearray((171, 205))\n_k = bytearray(_KEY)"), py)
        assertTrue(py.contains("_total(_A) + _total(_B)"), py)
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
    fun aMapIsADictAndItsMethodsBindToItsOwn() {
        val py = python(
            """
            mut at: Int32 = 0

            fx key: () Int32 {
                at += 1
                return at
            }

            fx f: (k: Str, x: Float64) Size {
                mut m: Map<Str, Int32> = Map<Str, Int32> { }
                m.put(k, 1)
                m[k] = 2
                g: Maybe<Int32> = m.get(k)
                r: Maybe<Int32> = m.remove(k)
                ks: Arr<Str> = m.keys()
                vs: Arr<Int32> = m.valuesArr()
                mut t: Map<UInt8, UInt8> = Map<UInt8, UInt8> { }
                t[1] = 2
                tk: Arr<UInt8> = t.keys()
                tv: Arr<UInt8> = t.valuesArr()
                mut f: Map<Int32, Float64> = Map<Int32, Float64> { }
                f[-1] = x
                f[key()] = x + (at as Float64)
                if m.containsKey(k) && f.containsValue(x) && !m.isEmpty() {
                    m.clear()
                }
                return m.size()
            }
            """
        )
        assertTrue(py.contains("    m = {}\n    m[k] = 1\n    m[k] = 2\n    g = m.get(k)\n    r = m.pop(k, None)"), py)
        assertTrue(py.contains("    ks = list(m)\n    vs = list(m.values())"), py)
        assertTrue(py.contains("    tk = bytearray(t)\n    tv = bytearray(t.values())"), "a List<UInt8> of keys or values is bytes:\n$py")
        assertTrue(py.contains("    f[-1] = x\n"), "a signed key is a key, never an index from the end:\n$py")
        assertTrue(py.contains("    _k_t0 = _key()\n    f[_k_t0] = x + float(_at)"), "the key is located before the value runs (D33):\n$py")
        assertTrue(py.contains("if (k in m) and _k_contains(f.values(), x) and not (not m):\n        m.clear()"), py)
        assertTrue(py.contains("    return len(m)"), py)
    }

    @Test
    fun putAndContainsKeyTakeTheFasterFormOnlyWhereTheirOrderCannotShow() {
        val py = python(
            """
            mut at: Int32 = 0

            fx key: () Str {
                at += 1
                return "k"
            }

            class Holder {
                pub mut items: Map<Str, Int32> = Map<Str, Int32> { }

                pub fx mine: () Holder {
                    return this
                }

                pub mut fx f: (xs: List<Int32>, k: Str) Bool {
                    items.put(k, xs[0] + 1)
                    items.put(key(), 1)
                    items.put(k + "x", xs[1])
                    mine().items.put(k, 2)
                    return items.containsKey(k + "y") && items.containsKey(key())
                }
            }
            """
        )
        assertTrue(py.contains("        self.items[k] = _k_i32(xs[0] + 1)\n"), py)
        assertTrue(py.contains("        self.items.__setitem__(_key(), 1)\n"), "an argument that writes keeps the call's order:\n$py")
        assertTrue(py.contains("        self.items.__setitem__(k + \"x\", xs[1])\n"), "two that may stop the program keep it:\n$py")
        assertTrue(py.contains("        self.mine().items.__setitem__(k, 2)\n"), "a receiver a call gives keeps it:\n$py")
        assertTrue(py.contains("return ((k + \"y\") in self.items) and self.items.__contains__(_key())"), py)
    }

    @Test
    fun aMapIsCopiedWhereASecondNameCouldSeeAWriteAsAListIs() {
        val py = python(
            """
            mut seed: Map<Str, Int32> = Map<Str, Int32> { }

            class Holder {
                require pub mut items: Map<Str, Int32>

                pub fx snapshot: () Map<Str, Int32> {
                    return items
                }

                pub mut fx reset: (mut xs: Map<Str, Int32>) Void {
                    items = Map<Str, Int32> { }
                    xs["r"] = 1
                }
            }

            fx count: (xs: Map<Str, Int32>) Size {
                seed["n"] = 1
                return xs.size()
            }

            fx fill: (mut xs: Map<Str, Int32>, ys: Map<Str, Int32>) Void {
                xs = ys
                xs["f"] = 2
            }

            fx f: (h: Holder) Size {
                mut a: Map<Str, Int32> = Map<Str, Int32> { }
                mut b: Map<Str, Int32> = a
                fill(mut a, seed)
                fill(mut h.items, a)
                h.reset(mut h.items)
                seed = a
                k: Holder = Holder { a }
                return count(seed) + b.size()
            }
            """
        )
        assertTrue(py.contains("        self.items = dict(items)"), "__init__ stores a copy of what it is given:\n$py")
        assertTrue(py.contains("        return dict(self.items)"), "a field returned is copied:\n$py")
        assertTrue(py.contains("        _k_mapset(self.items, {})\n        xs[\"r\"] = 1"), "a field keeps its dict, which a mut parameter may be:\n$py")
        assertTrue(py.contains("def _fill(xs, ys):\n    _k_mapset(xs, ys)\n    xs[\"f\"] = 2"), py)
        assertTrue(py.contains("    b = dict(a)\n    _fill(a, dict(_seed))\n    _fill(h.items, a)\n    h.reset(h.items)\n    _k_mapset(_seed, a)\n    k = _Holder(a)"), py)
        assertTrue(py.contains("_count(dict(_seed))"), "a callee that writes may write the global it is given:\n$py")
        assertTrue(py.contains("def _k_mapset(m, v):\n    if m is not v:\n        m.clear()\n        m.update(v)"), py)
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
        assertTrue(py.contains("        self.data = bytearray() if data is _k_unset else bytearray(data)\n        self.words = [0] * 3 if words is _k_unset else list(words)"), py)
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
            listOfNotNull(binding.place, binding.statement).forEach { alt ->
                assertEquals(binding.placeholders.sorted(), PyBinding(alt).placeholders.sorted(), "$key: $alt names each of ${binding.expr}'s once")
            }
            PyRuntime.HELPER.findAll(binding.expr).forEach { assertTrue(it.value in runtime.names, "$key names ${it.value}, which the runtime lacks") }
        }
    }

    @Test
    fun everyBuiltinTheRuntimeOrABindingNamesIsOneNoKiraNameCanTake() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val table = PyBindingTable().apply { loadDir(File(PyTestSupport.repoRoot, "kira").toPath()) }
        val bindings = File(PyTestSupport.repoRoot, "build/tmp/py-builtins/bindings.py").apply { parentFile.mkdirs() }
        bindings.writeText(
            table.all().values.flatMap { listOfNotNull(it.expr, it.place, it.statement) }
                .joinToString("\n", postfix = "\n") { it.replace(PyBinding.PLACEHOLDER, "_k_a").replace("{list}", "list") }
        )
        val runtime = File(PyTestSupport.repoRoot, "kira/py/runtime.py").toPath()
        assertEquals(emptyList(), PyTestSupport.unreservedBuiltins(listOf(runtime, bindings.toPath()), PyNames.RESERVED))
    }

    @Test
    fun aStructsCloneAndAUseCycleNameNoBuiltinAKiraNameCouldShadow() {
        val (py, errors) = PyTestSupport.emitProgram(
            "app:main" to """
                use "app:other"

                pub globals: Int32 = 5
                pub mut type: Int32 = 3

                pub struct Pt {
                    pub x: Int32 = 0
                }

                pub fx main: () Int32 {
                    a: Pt = Pt { 1 }
                    mut b: Pt = a
                    b.x = type
                    return globals + o() + b.x
                }
                """,
            "app:other" to """
                use "app:main"

                pub fx o: () Int32 {
                    return 1
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("        c = object.__new__(self.__class__)"), main)
        assertTrue(main.contains("_k_self()\n_k_m_app_other = _k_use("), main)
        assertTrue(main.contains("    g = _k_self.__globals__"), main)
        assertTrue(main.contains("if __name__ == \"__main__\":\n    _k_exit(main())"), main)
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
    fun aRangeIsPythonsRangeAndAListThatMayBeWrittenIsWalkedAsACopy() {
        val py = python(
            """
            mut log: List<Int32> = List<Int32> { }

            class Bag {
                require pub items: List<Int32>

                pub fx total: (xs: List<Int32>, mut ys: List<Int32>) Int32 {
                    mut t: Int32 = 0
                    for x: Int32 in items {
                        trace(x)
                    }
                    for x: Int32 in xs {
                        trace(x)
                    }
                    for y: Int32 in ys {
                        trace(y)
                    }
                    for g: Int32 in log {
                        trace(g)
                    }
                    for k: Int32 in items {
                        t += k
                    }
                    for h: Int32 in log {
                        t += h
                    }
                    return t
                }
            }

            fx f: (n: Size, v: View<UInt8>) UInt32 {
                mut t: UInt32 = 0
                for i: Size in 1..n {
                    t += i as UInt32
                }
                for b: UInt8 in v {
                    t += b as UInt32
                }
                return t
            }
            """
        )
        assertTrue(py.contains("for i in range(1, n):"), py)
        assertTrue(py.contains("for b in v:"), py)
        assertTrue(py.contains("for x in list(self.items):"), py)
        assertTrue(py.contains("for x in xs:"), py)
        assertTrue(py.contains("for y in list(ys):"), py)
        assertTrue(py.contains("for g in list(_log):"), py)
        // A body that only reads walks any range in place, as C++ lends it.
        assertTrue(py.contains("for k in self.items:"), py)
        assertTrue(py.contains("for h in _log:"), py)
    }

    @Test
    fun aDefaultIsTheDefsOwnWhenItFoldsAndAPubFieldIsAKeywordOfInit() {
        val py = python(
            """
            pub ROW: List<Int32> = [1, 2]

            class Knob {
                require pub name: Str
                pub mut level: Int32 = 3
                pub mut tags: List<Int32> = List<Int32> { }
                mut hidden: Int32 = 9
            }

            class Pair {
                require pub left: Knob
            }

            fx scale: (x: Int32, k: Int32 = 2, s: Str = "a", m: Maybe<Int32> = null) Int32 {
                return x * k
            }

            fx total: (xs: List<Int32> = ROW, extra: Int32 = 0) Size {
                return xs.size()
            }

            fx f: () Int32 {
                a: Knob = Knob { "a" }
                b: Knob = Knob { "b", 4 }
                return scale(1) + scale(1, k = 5) + (total() as Int32) + a.level + b.level
            }
            """
        )
        assertTrue(py.contains("def _scale(x, k=2, s=\"a\", m=None):"), py)
        assertTrue(py.contains("def _total(xs, extra=0):"), py)
        assertTrue(py.contains("def __init__(self, name, level=_k_unset, tags=_k_unset):"), py)
        assertTrue(py.contains("    def __init__(self, left):\n        self.left = left"), py)
        assertTrue(py.contains("        self.level = 3 if level is _k_unset else level\n        self.tags = [] if tags is _k_unset else list(tags)\n        self._hidden = 9"), py)
        assertTrue(py.contains("a = _Knob(\"a\")\n    b = _Knob(name=\"b\", level=4)"), py)
        assertTrue(py.contains("_scale(1) + _scale(1, k=5)"), py)
        assertTrue(py.contains("_total(xs=ROW)"), py)
    }

    @Test
    fun theLadderLowersWithoutARefusal() {
        val ladder = File(PyTestSupport.repoRoot, "src/test/resources/py-golden/ladder/src/firmware/pilot/tools/dash/ladder.kira").readText()
        val e = PyTestSupport.emit(ladder.substringAfter('\n'), uri = "firmware:pilot.tools.dash.ladder")
        val py = e.python()
        assertTrue(py.contains("class Ladder:"), py)
        assertTrue(py.contains("def __init__(self, level, top, verdict=_k_unset):"), py)
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
    fun anEnumIsANamespaceOfItsEntriesCppValues() {
        val py = python(
            """
            pub enum Mode {
                A,
                B = 5,
                C = 5
            }

            enum Tint: Str {
                RED = "red"
            }

            fx f: (m: Mode, t: Tint) Str {
                n: Maybe<Mode> = enumOf<Mode>(5)
                if m == Mode.C && t != Tint.RED && n.isSome() {
                    trace(m)
                }
                return "${'$'}{m} ${'$'}{t} ${'$'}{m as Int32}"
            }
            """
        )
        assertTrue(py.contains("class Mode:\n    A = 0\n    B = 5\n    C = 5\n    _k_names = {0: \"A\", 5: \"B\"}\n    _k_order = (0, 5, 5)"), py)
        assertTrue(py.contains("class _Tint:\n    RED = 0\n    _k_names = {0: \"red\"}\n    _k_order = (0,)"), py)
        assertTrue(py.contains("n = _k_enumof(Mode._k_order, 5)"), py)
        assertTrue(py.contains("if m == Mode.C and t != _Tint.RED and (n is not None):\n        print(m)"), py)
        assertTrue(py.contains("return Mode._k_names.get(m, \"\") + \" \" + _Tint._k_names.get(t, \"\") + \" \" + str(m)"), py)
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
    fun theLegacyForLoopIsRefused() {
        refused(
            """
            fx f: () Void {
                for mut i: 0..3 {
                    trace(i)
                }
            }
            """,
            "the legacy `for mut i: ...` loop",
        )
    }

    @Test
    fun aLoopVariableThatShadowsALocalIsRefused() {
        refused(
            """
            fx f: () Void {
                i: Int32 = 0
                if i == 0 {
                    for i: Int32 in 0..3 {
                        trace(i)
                    }
                }
            }
            """,
            "a loop variable 'i' that shadows a local of its name",
        )
    }

    @Test
    fun aStructIsCopiedWhereASecondNameCouldSeeAWrite() {
        val py = python(
            """
            pub struct Pt {
                pub x: Int32 = 0
                pub tags: List<Int32> = List<Int32> { }

                pub fx me: () Pt {
                    return this
                }
            }

            fx move: (mut p: Pt) Void {
                p = Pt { 1 }
            }

            fx f: (q: Pt) Int32 {
                mut ps: List<Pt> = List<Pt> { }
                ps.add(q)
                mut a: Pt = ps[0]
                move(mut a)
                ps[0] = a
                b: Maybe<Pt> = a
                return a.x + ps.get(0).x
            }
            """
        )
        assertTrue(py.contains("    def _k_clone(self):\n        c = object.__new__(self.__class__)\n        c.x = self.x\n        c.tags = list(self.tags)\n        return c"), py)
        assertTrue(py.contains("    def _k_set(self, o):\n        self.x = o.x\n        self.tags = o.tags"), py)
        assertTrue(py.contains("        return self._k_clone()"), py)
        assertTrue(py.contains("    p._k_set(Pt(x=1))"), py)
        assertTrue(py.contains("    ps.append(q._k_clone())\n    a = ps[0]._k_clone()\n    _move(a)\n    ps[0] = a._k_clone()\n    b = a._k_clone()"), py)
    }

    @Test
    fun aListOfStructsIsCopiedThroughMapSoACopyOfATreeCostsOneFramePerLevel() {
        val py = python(
            """
            pub struct Node {
                pub v: Int32 = 0
                pub kids: List<Node> = List<Node> { }
            }

            fx f: (n: Node) Node {
                mut q: Queue<Node> = Queue<Node> { }
                q.enqueue(n)
                mut more: Queue<Node> = q
                return n
            }
            """
        )
        assertTrue(py.contains("        c.kids = list(_k_map(Node._k_clone, self.kids))"), py)
        assertTrue(py.contains("    more = _k_collections.deque(_k_map(Node._k_clone, q))"), py)
        assertTrue(py.contains("_k_map = _k_builtins.map"), py)
    }

    @Test
    fun aStructComparedByEqualsOrContainsHasTheMemberwiseEqCppDefaults() {
        val py = python(
            """
            pub struct Pt {
                pub x: Int32 = 0
                pub y: Int32 = 0
            }

            pub struct Seg {
                pub a: Pt = Pt { }
                pub tags: List<Int32> = List<Int32> { }
            }

            pub struct Other {
                pub x: Int32 = 0
            }

            fx f: (xs: List<Pt>, s: Seg, t: Seg, o: Other) Bool {
                return xs.contains(Pt { 1 }) && s != t && o.x == 0
            }
            """
        )
        assertTrue(py.contains("    def __eq__(self, o):\n        return self.x == o.x and self.y == o.y"), py)
        assertTrue(py.contains("    def __eq__(self, o):\n        return self.a == o.a and self.tags == o.tags"), py)
        assertEquals(2, Regex("def __eq__").findAll(py).count(), "Other is never compared:\n$py")
        assertTrue(py.contains("_k_contains(xs, Pt(x=1)) and s != t"), py)
    }

    @Test
    fun aLoopVariableIsCopiedWhereItIsReturnedAsCppCopiesItsConstReference() {
        val py = python(
            """
            pub struct Pt {
                pub x: Int32 = 0
            }

            fx first: (xs: List<Pt>) Pt {
                for p: Pt in xs {
                    return p
                }
                return Pt { }
            }

            fx row: (v: View<List<Int32>>, c: Bool) List<Int32> {
                for r: List<Int32> in v {
                    return if c { r } else { List<Int32> { } }
                }
                mut own: List<Int32> = List<Int32> { }
                return own
            }
            """
        )
        assertTrue(py.contains("    for p in xs:\n        return p._k_clone()"), py)
        assertTrue(py.contains("        return list(r if c else [])"), py)
        assertTrue(py.contains("    return own\n"), "a local that owns its value is returned as itself:\n$py")
    }

    @Test
    fun aMutParameterOtherThanAListAMapOrAStructIsRefused() {
        refused(
            """
            fx f: (mut x: Int32) Void {
                x = 1
            }
            """,
            "the mut parameter 'x': Int32 (only a List, a Map or a struct is passed by reference)",
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
            "the name 'len', which generated Python uses, is not supported",
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
            "the name 'OverflowError', which generated Python uses, is not supported",
        )
        refused(
            """
            pub fx ValueError: () Int32 {
                return 1
            }
            """,
            "the name 'ValueError', which generated Python uses, is not supported",
        )
    }

    @Test
    fun aValueGivenToAPrivateFieldAtAConstructionIsRefused() {
        refused(
            """
            class Box {
                hidden: Int32 = 0

                pub fx again: () Box {
                    return Box { hidden = 3 }
                }
            }
            """,
            "a value given to the private field 'hidden' at a construction",
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
    fun printPrintlnEprintAndExitAreTracesFormatOnTheirStreams() {
        val py = python(
            """
            use "kira:io"
            use "kira:os"

            fx f: (ok: Bool) Void {
                print(ok)
                println(2.5)
                eprint("e")
                assert(ok, "bad")
                exit(3)
            }
            """
        )
        assertTrue(py.contains("    print(1 if ok else 0, end=\"\")\n    print(_k_gtext(2.5))\n"), py)
        assertTrue(py.contains("    print(\"e\", end=\"\", file=_k_sys.stderr, flush=True)\n    _k_assert(ok, \"bad\")\n    _k_exit(3)"), py)
        assertTrue(py.contains("import sys as _k_sys"), py)
        assertTrue(py.contains("raise _k_builtins.SystemExit(code)"), py)
    }

    @Test
    fun aThrowRaisesTheOneErrorClassATryCatchesAndAResultIsATuple() {
        val py = python(
            """
            fx parse: (s: Str) Int64 {
                v: Maybe<Int64> = s.toInt64()
                if v.isNone() {
                    throw "bad ${'$'}{s}"
                }
                return v.unwrap()
            }

            fx pick: (ok: Bool) Int32 {
                return if ok { 1 } else { throw "no" }
            }

            fx check: (x: Int32) Result<Int32, Str> {
                if x < 0 {
                    return Result.error("negative")
                }
                return Result.success(x)
            }

            fx f: () Int64 {
                try {
                    return parse("x")
                } on e: Str {
                    trace(e)
                }
                r: Result<Int32, Str> = check(1)
                if r.isOk() && !r.isErr() {
                    return (r.unwrap() + r.value) as Int64
                }
                trace(r.unwrapErr())
                return -1
            }
            """
        )
        assertTrue(py.contains("        raise _k_Error(\"bad \" + s)"), py)
        assertTrue(py.contains("    return 1 if ok else _k_throw(\"no\")"), py)
        assertTrue(py.contains("    try:\n        return _parse(\"x\")\n    except _k_Error as _k_t0:\n        e = _k_t0.args[0]\n        print(e)"), py)
        assertTrue(py.contains("return (False, \"negative\")"), py)
        assertTrue(py.contains("return (True, x)"), py)
        assertTrue(py.contains("if r[0] and not (not r[0]):"), py)
        assertTrue(py.contains("_k_i32(_k_unwrap(r) + _k_unwrap(r))"), py)
        assertTrue(py.contains("print(_k_unwrap_err(r))"), py)
        assertTrue(py.contains("_k_Error = _k_errors()"), py)
    }

    @Test
    fun aTupleIsAPythonTupleOfCopiesAndMapEntriesAreItsItems() {
        val py = python(
            """
            use "kira:tuples"

            fx f: (xs: List<Int32>, m: Map<Str, Int32>) Int32 {
                t: Tuple3<Str, List<Int32>, Int32> = Tuple3<Str, List<Int32>, Int32> { "a", xs, 2 }
                mut inner: List<Int32> = t.second
                inner.add(t.size())
                es: Arr<Tuple2<Str, Int32>> = m.entries()
                built: Map<Str, Int32> = Map<Str, Int32> { values = es }
                for e: Tuple2<Str, Int32> in m {
                    trace(e.first)
                }
                r: Result<List<Int32>, Str> = Result.success(xs)
                return t.third + es[0].second + (built.size() as Int32) + (r.unwrap().size() as Int32)
            }
            """
        )
        assertTrue(py.contains("t = (\"a\", list(xs), 2)"), py)
        assertTrue(py.contains("inner = list(t[1])\n    inner.append(3)"), py)
        assertTrue(py.contains("es = list(m.items())\n    built = dict(list(es))"), py)
        assertTrue(py.contains("for e in m.items():\n        print(e[0])"), py)
        assertTrue(py.contains("r = (True, list(xs))"), py)
        assertTrue(py.contains("t[2] + es[0][1]"), py)
    }

    @Test
    fun aMapKeyedByAFloatIsRefusedAsANaNKeyDiffersBetweenTheTargets() {
        refused(
            """
            fx f: () Size {
                m: Map<Float64, Int32> = Map<Float64, Int32> { }
                return m.size()
            }
            """,
            "a Map<Float64, Int32> (a float key: a NaN key differs between kira::Map and a dict)",
        )
    }

    @Test
    fun aMapKeyedByAClassIsRefused() {
        refused(
            """
            class Box {
                pub v: Int32 = 0
            }

            fx f: (m: Map<Box, Int32>) Size {
                return m.size()
            }
            """,
            "a Map<Box, Int32> (a Map's key is a Str, an integer, a Bool, a Char or an enum)",
        )
    }

    @Test
    fun containersOfContainersAreCopiedDeeply() {
        val py = python(
            """
            fx f: (m: Map<Str, List<UInt8>>, g: List<List<Int32>>) Size {
                mut h: List<List<Int32>> = g
                mut row: List<Int32> = g[0]
                h.add(row)
                mut copy: Map<Str, List<UInt8>> = m
                o: Maybe<List<Int32>> = row
                vs: Arr<List<UInt8>> = m.valuesArr()
                return copy.size() + h.size() + vs.size() + o.unwrap().size()
            }
            """
        )
        assertTrue(py.contains("h = [list(_k_e0) for _k_e0 in g]\n    row = list(g[0])\n    h.append(list(row))"), py)
        assertTrue(py.contains("copy = {_k_k0: bytearray(_k_v0) for _k_k0, _k_v0 in m.items()}"), py)
        assertTrue(py.contains("o = list(row)"), py)
        assertTrue(py.contains("vs = [bytearray(_k_e0) for _k_e0 in list(m.values())]"), py)
    }

    @Test
    fun aMaybeOfAMaybeIsRefusedWhereAMapOfMaybesGivesOne() {
        refused(
            """
            fx f: (m: Map<Str, Maybe<Int32>>) Bool {
                return m.get("a").isSome()
            }
            """,
            "a Maybe<Maybe<Int32>>",
        )
    }

    @Test
    fun aSetIsADictAndAStackAListAndAQueueOrDequeACollectionsDeque() {
        val py = python(
            """
            fx f: (q: Queue<Int32>) Int32 {
                mut s: Set<Str> = Set<Str> { }
                added: Bool = s.add("a")
                mut st: Stack<Int32> = Stack<Int32> { }
                st.push(1)
                mut d: Deque<Int32> = Deque<Int32> { }
                d.pushFront(2)
                mut copy: Queue<Int32> = q
                copy.enqueue(3)
                return st.pop().unwrapOr(0) + d.popBack().unwrapOr(0) + copy.dequeue().unwrapOr(0)
            }
            """
        )
        assertTrue(py.contains("s = {}\n    added = _k_setadd(s, \"a\")\n    st = []\n    st.append(1)\n    d = _k_collections.deque()\n    d.appendleft(2)\n    copy = _k_collections.deque(q)\n    copy.append(3)"), py)
        assertTrue(py.contains("_k_or(_k_pop(st), 0)"), py)
        assertTrue(py.contains("_k_or(_k_popleft(copy), 0)"), py)
        assertTrue(py.contains("import collections as _k_collections"), py)
    }

    @Test
    fun aSetOfFloatsIsRefusedAsAMapKeyedByOneIs() {
        refused(
            """
            fx f: (s: Set<Float64>) Size {
                return s.size()
            }
            """,
            "a Set<Float64> (a float key: a NaN key differs between kira::Map and a dict)",
        )
    }

    @Test
    fun aTupleWrittenOutOfOrderWithEffectsIsRefused() {
        refused(
            """
            use "kira:tuples"

            fx two: () Int32 {
                trace("two")
                return 2
            }

            fx f: () Int32 {
                t: Tuple2<Int32, Int32> = Tuple2<Int32, Int32> { second = two(), first = 1 }
                return t.first
            }
            """,
            "a Tuple whose values, written out of their order, have effects",
        )
    }

    @Test
    fun aCompoundAssignmentThroughAMapIndexIsTheTypersErrorNotTheTargets() {
        val e = PyTestSupport.emit(
            """
            fx f: () Size {
                mut m: Map<Str, Int32> = Map<Str, Int32> { }
                m["a"] += 1
                return m.size()
            }
            """
        )
        assertNull(e.text)
        assertTrue(e.errors.any { it.contains("types.index.map-read") }, e.errors.joinToString("\n"))
        assertTrue(e.errors.none { it.contains(PyModuleEmitter.UNSUPPORTED_CODE) }, e.errors.joinToString("\n"))
    }

    @Test
    fun dictIsANamePythonReserves() {
        refused(
            """
            fx f: () Int32 {
                dict: Int32 = 1
                return dict
            }
            """,
            "the name 'dict', which generated Python uses, is not supported",
        )
    }


    // ---- programs of several modules ----------------------------------------------------------

    @Test
    fun aUsedModuleIsLoadedByItsPathAndNamedThroughItsModuleObject() {
        val (py, errors) = PyTestSupport.emitProgram(
            "lib:units" to """
                pub SCALE: Int32 = 10
                pub mut calls: Int32 = 0

                pub class Meter {
                    require pub label: Str

                    pub fx scaled: (x: Int32) Int32 {
                        return x * SCALE
                    }
                }

                pub fx bump: () Int32 {
                    calls += 1
                    return calls
                }
                """,
            "app:main" to """
                use "lib:units"

                fx f: () Int32 {
                    m: Meter = Meter { "a" }
                    calls = 5
                    units.calls = units.calls + 1
                    return m.scaled(bump()) + units.bump() + SCALE
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("_k_m_lib_units = _k_use(__file__, \"../lib/units.kira.py\")"), main)
        assertTrue(main.contains("m = _k_m_lib_units.Meter(\"a\")"), main)
        assertTrue(main.contains("    _k_m_lib_units.calls = 5\n    _k_m_lib_units.calls = _k_i32(_k_m_lib_units.calls + 1)"), main)
        assertTrue(main.contains("return _k_i32(_k_i32(m.scaled(_k_m_lib_units.bump()) + _k_m_lib_units.bump()) + _k_m_lib_units.SCALE)"), main)
        assertFalse(main.contains("global calls"), main)
        assertFalse(main.contains("_k_self"), main)
        val lib = py.getValue("lib:units")
        assertFalse(lib.contains("_k_use"), lib)
        assertTrue(lib.contains("def bump():\n    global calls"), lib)
    }

    @Test
    fun aModuleInAUseCycleRegistersItselfBeforeItLoadsTheOther() {
        val (py, errors) = PyTestSupport.emitProgram(
            "app:even" to """
                use "app:odd"

                pub K: Int32 = 2

                pub fx isEven: (n: Int32) Bool {
                    return n == 0 || isOdd(n - 1)
                }
                """,
            "app:odd" to """
                use "app:even"

                pub fx isOdd: (n: Int32) Bool {
                    return n != 0 && isEven(n - 1)
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val even = py.getValue("app:even")
        // Its constants first, so the module that uses it back reads them while it loads.
        assertTrue(even.contains("K = 2\n\n\n_k_self()\n_k_m_app_odd = _k_use(__file__, \"odd.kira.py\")"), even)
        assertTrue(even.contains("return n == 0 or _k_m_app_odd.isOdd(_k_i32(n - 1))"), even)
        val odd = py.getValue("app:odd")
        assertTrue(odd.contains("_k_self()\n_k_m_app_even = _k_use(__file__, \"even.kira.py\")"), odd)
    }

    @Test
    fun aStdlibFunctionACarriedOneCallsIsCarriedToo() {
        val (py, errors) = PyTestSupport.emitProgram(
            "kira:extra" to """
                pub fx inner: (v: Float64) Float64 {
                    return v * 2.0
                }

                pub fx outer: (v: Float64) Float64 {
                    return inner(v) + 1.0
                }
                """,
            "app:main" to """
                use "kira:extra"

                pub fx f: (v: Float64) Float64 {
                    return outer(v)
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("def _k_kira_extra_outer(v):\n    return _k_kira_extra_inner(v) + 1.0"), main)
        assertTrue(main.contains("def _k_kira_extra_inner(v):"), main)
    }

    @Test
    fun aKiraWrittenStdlibFunctionIsCarriedByTheModuleThatCallsIt() {
        val py = python(
            """
            use "kira:math"
            use "kira:os"

            fx f: (x: Float64) Float64 {
                return clamp(deg2rad(x), lerp(0.0, -1.0, 0.5), 1.0)
            }

            fx g: () Int32 {
                return POLL_WRITE + SIGNAL_TERM
            }
            """
        )
        assertTrue(py.contains("def _k_kira_math_clamp(value, lo, hi):\n    return _k_max(lo, _k_min(value, hi))"), py)
        assertTrue(py.contains("def _k_kira_math_deg2rad(degrees):"), py)
        assertTrue(py.contains("def _k_kira_math_lerp(a, b, t):"), py)
        assertFalse(py.contains("_k_kira_math_sign"), py)
        assertTrue(py.contains("return _k_kira_math_clamp(_k_kira_math_deg2rad(x), _k_kira_math_lerp(0.0, -1.0, 0.5), 1.0)"), py)
        assertTrue(py.contains("return _k_i32(2 + 15)"), py)
        assertFalse(py.contains("_k_use"), py)
    }
}
