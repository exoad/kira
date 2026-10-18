package net.exoad.kira.py

import net.exoad.kira.Public
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.backend.codegen.py.PyBinding
import net.exoad.kira.compiler.backend.codegen.py.PyBindingTable
import net.exoad.kira.compiler.backend.codegen.py.PyModuleEmitter
import net.exoad.kira.compiler.backend.codegen.py.PyNames
import net.exoad.kira.compiler.backend.codegen.py.PyRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private fun refused(body: String, construct: String, e: PyTestSupport.Emitted = PyTestSupport.emit(body)) {
        assertNull(e.text, "the module was emitted:\n${e.text}")
        val errors = e.errors
        assertTrue(errors.isNotEmpty(), "no diagnostic")
        assertTrue(errors.all { it.contains("py.unsupported") }, "a refusal that is not the py target's:\n${errors.joinToString("\n")}")
        assertTrue(errors.any { it.contains(construct) && it.contains("is not supported on the py target yet") }, "no refusal names '$construct':\n${errors.joinToString("\n")}")
    }

    /** The refusal a manifest's `py: { unsupported }` decision gives [body] (D83): [construct] and its reason. */
    private fun decided(body: String, construct: String, why: String) {
        val e = PyTestSupport.emit(body)
        assertNull(e.text, "the module was emitted:\n${e.text}")
        assertEquals(listOf("$construct is not supported on the py target: $why"), e.errors.map { it.substringAfter("py.unsupported: ") }, e.errors.joinToString("\n"))
    }

    // ---- lowering ------------------------------------------------------------------------------

    @Test
    fun integerDivisionTruncatesAndRemainderTakesTheDividendsSign() {
        val py = python(
            """
            pub q: (a: Int32, b: Int32) Int32 {
                return a / b
            }

            pub r: (a: Int64, b: Int64) Int64 {
                return a % b
            }

            pub u: (a: UInt32, b: UInt32) UInt32 {
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
            pub a: (x: UInt8, y: UInt8) UInt8 {
                return x + y
            }

            pub b: (x: Int32, y: Int32) Int32 {
                return x * y
            }

            pub c: (x: Int8) Int8 {
                return -x
            }

            pub d: (x: Size) Size {
                return x - 1
            }

            pub e: (x: UInt16) UInt16 {
                return ~x
            }

            pub f: (x: Int64) Int32 {
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
            pub a: (x: Float64, y: Float64) Float64 {
                return x / y
            }

            pub b: (x: Float64) Float64 {
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
            pub a: (x: Int32, y: Int32, z: Bool) Bool {
                return (x < y) == z
            }

            pub b: (x: Bool, y: Bool) Bool {
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
            kira.math as math

            pub f: (a: Float64, b: Float64) Float64 {
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
            pub a: (x: UInt32) UInt32 {
                return x << 2
            }

            pub b: (x: UInt32, n: Int64) UInt32 {
                return x >> n
            }

            pub c: (x: UInt8, n: UInt8) UInt8 {
                return x << n
            }

            pub d: (x: UInt32) UInt32 {
                return x << 32
            }

            pub e: (x: Int64) Int64 {
                return x >> -1
            }

            pub g: (x: Size) Size {
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
            pub a: (x: Int32, n: Int32) Int32 {
                return x << n
            }

            pub b: (x: Int64) Int64 {
                return x << 63
            }

            pub c: (x: Int8, n: Int32) Int8 {
                return x >>> n
            }

            pub d: (x: Int16) Int16 {
                return x >> 3
            }

            pub e: (x: UInt16) UInt16 {
                return x >>> 15
            }

            pub g: (x: Int64) Int64 {
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
            pub a: (x: UInt16, y: UInt16) UInt16 {
                return (x << 4 | y >> 4) ^ (x & y) << 2
            }

            pub b: (x: UInt32, y: UInt32) UInt32 {
                return (x + y) >> 1
            }

            pub c: (x: Int32) Int32 {
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
            pub f: (v: Int32, n: Int32) Int32 {
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

            next: () Size {
                at += 1
                return at
            }

            bit: () Int32 {
                return 3
            }

            Flags: Any {
                pub mut bits: UInt8 = 0

                pub mut set: (n: Int32) Void {
                    bits |= 1 << n
                }
            }

            f: (i: Size) Void {
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
            Holder: Any {
                pub mut items: Map<Str, Int32> = Map<Str, Int32> { }
                pub mut xs: List<Int32> = List<Int32> { }
            }

            holderOf: (h: Holder) Holder {
                trace("holder")
                return h
            }

            key: () Str {
                trace("key")
                return "k"
            }

            val: () Int32 {
                trace("val")
                return 1
            }

            f: (h: Holder) Void {
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
            kira.math as math

            pub f: (a: Float64, b: Float64) Float64 {
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
            kira.math as math

            pub f: (a: Float64, b: Float64) Float64 {
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
            kira.math as math

            pub f: (a: Float64) Float64 {
                return abs(a)
            }
            """
        )
        assertTrue(py.contains("return _k_math.fabs(a)"), py)
        assertTrue(py.contains("import math as _k_math"), py)
        // A module's own registration (D85) is the only helper it carries.
        assertEquals(listOf("_k_key", "_k_held", "_k_self"), Regex("def (_k_\\w+)").findAll(py).map { it.groupValues[1] }.toList(), "fabs needs no helper:\n$py")
    }

    @Test
    fun aFloat64AsTextIsTheShortestTextStdToCharsWrites() {
        val py = python(
            """
            pub a: (x: Float64) Str {
                return "x=${'$'}{x} m"
            }

            pub b: (x: Float64) Str {
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
            pub speed: (mps: Float64) Str {
                return "${'$'}{mps.fixed(1)} m/s"
            }

            pub ids: (build: UInt32, seq: Int16) Str {
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
            pub a: (s: Str, t: Str) Size {
                return s.length() + s.size() + s.find(t).unwrapOr(0)
            }

            pub b: (s: Str, t: Str) Bool {
                return s.isEmpty() || s.contains(t) || s.startsWith(t) || s.endsWith("x") || s.equals(t)
            }

            pub c: (s: Str) Str {
                return s.substring(1, 3) + s.charAt(0) + s.trim().toLower() + ("a" + s).toUpper()
            }

            pub d: (s: Str) List<Str> {
                return s.split(",")
            }

            pub e: (s: Str) Int64 {
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
            f: (s: Str) Size {
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
            f: (v: View<UInt8>, xs: List<UInt8>) Str {
                return Str.of(v) + Str.of(xs.from(1)) + Str.of(xs)
            }
            """
        )
        assertTrue(py.contains("return _k_strof(v) + _k_strof(_k_from(xs, 1)) + _k_strof(xs)"), py)
        assertTrue(py.contains("return bytes(v).decode(\"utf-8\", \"replace\")"), py)
        assertTrue(python("g: (v: View<UInt8>) Str {\n    return Str.of(bytes = v)\n}").contains("return _k_strof(v)"), "a named argument in its parameter's place")
    }

    @Test
    fun theKotlinNamedStrMethodsKeepKiraStrsRules() {
        val py = python(
            """
            pub a: (s: Str, fill: Char) Str {
                return s.padEnd(4, fill) + s.trimStart() + s.trimEnd() + s.replace("a", "")
            }

            pub b: (s: Str) List<Str> {
                return s.splitWhitespace()
            }

            pub c: (s: Str) Int64 {
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
            f: (xs: List<Str>, sep: Str) Str {
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
            f: (s: Str, i: Size, c: Char) Bool {
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
            Bag: Any {
                pub mut fs: List<Float64> = List<Float64> { }
                pub mut bs: List<UInt8> = List<UInt8> { }

                pub mut tidy: () Float64 {
                    fs.sort()
                    bs.sort()
                    return fs.sum() + (bs.sum() as Float64)
                }
            }

            f: (mut xs: List<Int32>, names: List<Str>) Int32 {
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
            f: (a: Str, b: Str) Bool {
                return a < b || a >= "m"
            }
            """
        )
        assertTrue(py.contains("return a < b or a >= \"m\""), py)
    }

    @Test
    fun aJsonIsTheValueJsonLoadsGives() {
        val py = python(
            """
            kira.json as json

            pub Doc: Any {
                pub body: Json
                pub mut items: List<Json> = []
            }

            pub build: (t: Str, x: Float64, n: Int64, m: Maybe<Float64>, s: Maybe<Str>) Str {
                d: Doc = Doc { body = Json.parse(t) }
                o: Json = Json.obj()
                o.put("x", Json.of(x))
                o.put("n", Json.of(n))
                o.put("m", Json.of(m))
                o.put("s", Json.of(s))
                o.put("z", Json.null())
                a: Json = Json.arr()
                a.add(d.body.get("k").at(2))
                o.put("a", a)
                d.items.add(o)
                if d.body.isObj() && d.body.has("k") && d.body.get("k").isArr() {
                    return o.pretty(2, false) + Json.error()
                }
                return "${'$'}{d.body.get("v").asInt64().unwrapOr(-1)} ${'$'}{d.body.size()} ${'$'}{d.body.keys().size()}" + o.dump()
            }
            """
        )
        assertTrue(py.contains("d = Doc(body=_k_jparse(t))"), py)
        assertTrue(py.contains("_k_jput(o, \"x\", float(x))"), "a Float64 is float() of itself:\n$py")
        assertTrue(py.contains("_k_jput(o, \"n\", n)") && py.contains("_k_jput(o, \"m\", _k_jmaybef(m))") && py.contains("_k_jput(o, \"s\", s)"), py)
        assertTrue(py.contains("_k_jput(o, \"z\", None)") && py.contains("o = {}") && py.contains("a = []"), py)
        assertTrue(py.contains("_k_jadd(a, _k_jat(_k_jget(d.body, \"k\"), 2))"), py)
        assertTrue(py.contains("if (d.body.__class__ is dict) and _k_jhas(d.body, \"k\") and (_k_jget(d.body, \"k\").__class__ is list):"), py)
        assertTrue(py.contains("return _k_jpretty(o, 2, False) + _k_jerror()"), py)
        assertTrue(py.contains("_k_or(_k_jasint(_k_jget(d.body, \"v\")), -1)") && py.contains("_k_jsize(d.body)") && py.contains("_k_jkeys(d.body)"), py)
        assertTrue(py.contains("text = _k_json.dumps(j, indent=indent, ensure_ascii=ascii)") && py.contains("import json as _k_json") && py.contains("import threading as _k_threading"), py)
    }

    @Test
    fun aCharIsItsCodePoint() {
        val py = python(
            """
            SEP: Char = ':'

            Cursor: Any {
                pub mut last: Char = 'x'
                pub mut none: Char = '\0'
            }

            pub digit: (s: Str, i: Size) Bool {
                return s[i] >= '0' && s.at(i) <= '9' && s[i] != SEP
            }

            pub value: (c: Char) Int32 {
                return (c as Int32) - ('0' as Int32)
            }

            pub byte: (c: Char) UInt8 {
                return c as UInt8
            }

            pub char: (n: Int32, b: UInt8) Str {
                return "${'$'}{n as Char}${'$'}{b as Char}${'$'}{'\n'}" + ((9 as Char) as Str)
            }

            pub hex: (crc: UInt16) Str {
                return "0x${'$'}{crc.toHex().padStart(4, '0')}"
            }

            pub show: (c: Char) Void {
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
            ord: (c: Char) Int32 {
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
            pub a: (x: Bool, y: Bool, z: Bool) Bool {
                return (x || y) && z
            }

            pub b: (x: Bool, y: Bool, z: Bool) Bool {
                return x || y && z
            }

            pub c: (p: Int32, q: Int32) Int32 {
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
            pub Meter: Any {
                pub start: Int32
                scale: Int32
                pub mut reading: Int32 = 0
                mut ticks: Int32 = 0

                pub mut tick: () Int32 {
                    ticks += 1
                    reading = start + ticks * scale
                    return reading
                }

                hidden: () Int32 {
                    return ticks
                }
            }

            helper: () Int32 {
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
            pub a: (m: Maybe<Float64>) Float64 {
                if m.isNull() {
                    return 0.0
                }
                return m.value
            }

            pub b: (x: Float64) Maybe<Float64> {
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

            bump: () Int32 {
                at += 1
                return 7
            }

            f: (i: Size) Void {
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

            Thing: Any {
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

            Holder: Any {
                pub mut items: List<Int32>

                pub snapshot: () List<Int32> {
                    return items
                }

                pub mut grow: () Int32 {
                    items.add(0)
                    return 1
                }
            }

            size: (xs: List<Int32>) Size {
                return xs.size()
            }

            count: (xs: List<Int32>) Size {
                seed.add(1)
                return xs.size()
            }

            lenOf: (xs: List<Int32>, k: Int32) Size {
                return xs.size() + (k as Size)
            }

            push: (mut xs: List<Int32>) Int32 {
                xs.add(1)
                return 1
            }

            echo: (xs: List<Int32>) List<Int32> {
                return xs
            }

            fresh: () List<Int32> {
                mut out: List<Int32> = List<Int32> { }
                out.add(1)
                return out
            }

            pick: (c: Bool, a: List<Int32>) List<Int32> {
                return if c { a } else { fresh() }
            }

            f: (h: Holder) Void {
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
            show: (xs: List<Int32>) Size {
                trace(xs.size())
                return xs.size()
            }

            into: (xs: List<Int32>, mut out: List<Int32>) Size {
                out.add(1)
                return xs.size()
            }

            Holder: Any {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut f: () Size {
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

            next: () Size {
                cursor += 1
                return cursor - 1
            }

            at: (v: List<UInt8>, i: Size) UInt32 {
                return v[i] as UInt32
            }

            fill: (p: MutView<UInt8>) Size {
                p[0] = 1
                return 0
            }

            push: (mut xs: List<UInt8>) Size {
                xs.add(1)
                return 0
            }

            walk: (n: Size) UInt32 {
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

            total: (xs: Arr<Int32, 2>) Int32 {
                return xs[0] + xs[1]
            }

            f: () Int32 {
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

            Holder: Any {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut reset: (mut xs: List<Int32>) Void {
                    items = List<Int32> { }
                    xs.add(1)
                }
            }

            fill: (mut xs: List<Int32>, ys: List<Int32>) Void {
                xs = ys
                xs.add(2)
                xs[0] = 3
            }

            f: (h: Holder) Void {
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
            f: (x: Float64) Bool {
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

            key: () Int32 {
                at += 1
                return at
            }

            f: (k: Str, x: Float64) Size {
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

            key: () Str {
                at += 1
                return "k"
            }

            Holder: Any {
                pub mut items: Map<Str, Int32> = Map<Str, Int32> { }

                pub mine: () Holder {
                    return this
                }

                pub mut f: (xs: List<Int32>, k: Str) Bool {
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

            Holder: Any {
                pub mut items: Map<Str, Int32>

                pub snapshot: () Map<Str, Int32> {
                    return items
                }

                pub mut reset: (mut xs: Map<Str, Int32>) Void {
                    items = Map<Str, Int32> { }
                    xs["r"] = 1
                }
            }

            count: (xs: Map<Str, Int32>) Size {
                seed["n"] = 1
                return xs.size()
            }

            fill: (mut xs: Map<Str, Int32>, ys: Map<Str, Int32>) Void {
                xs = ys
                xs["f"] = 2
            }

            f: (h: Holder) Size {
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
            pub Arr<UInt8, 4> as Cmd

            Buf: Any {
                pub mut data: List<UInt8> = List<UInt8> { }
                pub mut words: Arr<Int32, 3> = Arr<Int32, 3> { }
            }

            f: (xs: List<UInt8>, a: Arr<UInt8>) List<UInt8> {
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
            kira.bytes as bytes

            Link: Any {
                pub mut buf: List<UInt8> = List<UInt8> { }

                pub head: () UInt16 {
                    return readU16Le(buf, 0)
                }
            }

            sum: (v: View<UInt8>) UInt32 {
                mut s: UInt32 = 0
                mut i: Size = 0
                while i < v.size() {
                    s += v[i] as UInt32
                    i += 1
                }
                return s + (v.from(1).size() as UInt32) + (v.slice(0, 1).get(0) as UInt32)
            }

            fill: (p: MutView<UInt8>, v: UInt8) Void {
                p[0] = v
                p.set(1, v)
                writeU32Le(p, 2, 0xA1B2C3D4)
                writeF64Le(p.from(6), 0, 1.5)
            }

            g: () UInt32 {
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
            kira.bytes as bytes

            f: (v: View<UInt8>, p: MutView<UInt8>) UInt64 {
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
            f: (v: View<UInt8>, w: View<Int32>) Size {
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
            at: (v: View<Int32>, i: Size) Int32 {
                return v[i]
            }

            f: () Int32 {
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
            pub a: (x: Float64, y: Float64) Float64 {
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
            listOfNotNull(binding.place, binding.statement, binding.float).forEach { alt ->
                assertEquals(binding.placeholders.sorted(), PyBinding(alt).placeholders.sorted(), "$key: $alt names each of ${binding.expr}'s once")
            }
            binding.float?.let { assertTrue(PyBinding(it).isOrdered, "$key: $it") }
            listOfNotNull(binding.expr, binding.float).forEach { text ->
                PyRuntime.HELPER.findAll(text).forEach { assertTrue(it.value in runtime.names, "$key names ${it.value}, which the runtime lacks") }
            }
        }
    }

    @Test
    fun everyBuiltinTheRuntimeOrABindingNamesIsOneNoKiraNameCanTake() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val table = PyBindingTable().apply { loadDir(File(PyTestSupport.repoRoot, "kira").toPath()) }
        val bindings = File(PyTestSupport.repoRoot, "build/tmp/py-builtins/bindings.py").apply { parentFile.mkdirs() }
        bindings.writeText(
            table.all().values.flatMap { listOfNotNull(it.expr, it.place, it.statement, it.float) }
                .joinToString("\n", postfix = "\n") { it.replace(PyBinding.PLACEHOLDER, "_k_a").replace("{list}", "list") }
        )
        val runtime = File(PyTestSupport.repoRoot, "kira/py/runtime.py").toPath()
        assertEquals(emptyList(), PyTestSupport.unreservedBuiltins(listOf(runtime, bindings.toPath()), PyNames.RESERVED))
    }

    /** kira/cpp/kira/test.hxx's lines, byte for byte, and finish's 1 or 0. */
    @Test
    fun theSuiteBlockPrintsBibosCheckFormat() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val runtime = PyRuntime(File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText())
        val script = File(PyTestSupport.repoRoot, "build/tmp/py-suite/suite.py").apply { parentFile.mkdirs() }
        val driver = """
            t = _k_Suite("suite - its lines")
            t.check(True, "kept")
            t.checkStr("a", "a", "same text")
            t.check(False, "broken")
            t.checkStr("got", "want", "other text")
            failed = t.finish()
            print(failed, _k_Suite("empty").finish())
        """.trimIndent()
        script.writeText(runtime.select(setOf("_k_Suite")) + "\n\n\n" + driver + "\n")
        val ran = PyTestSupport.run(listOf(PyTestSupport.python!!, script.absolutePath), script.parentFile)
        assertEquals(0, ran.exitCode, ran.all)
        assertEquals(
            "\nsuite - its lines\n\n  ok    kept\n  ok    same text\n  FAIL  broken\n  FAIL  other text\n" +
                "        got  \"got\"\n        want \"want\"\n\n4 checks, 2 failed\n\n" +
                "\nempty\n\n\n0 checks, 0 failed\n\n1 0\n",
            ran.stdout,
        )
    }

    @Test
    fun aStructsCloneAndAUseCycleNameNoBuiltinAKiraNameCouldShadow() {
        val (py, errors) = PyTestSupport.emitProgram(
            "app:main" to """
                app.other as other

                pub globals: Int32 = 5
                pub mut type: Int32 = 3

                pub Pt: Struct {
                    pub x: Int32 = 0
                }

                pub main: () Int32 {
                    a: Pt = Pt { 1 }
                    mut b: Pt = a
                    b.x = type
                    return globals + o() + b.x
                }
                """,
            "app:other" to """
                app.main as main

                pub o: () Int32 {
                    return 1
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("        c = object.__new__(self.__class__)"), main)
        assertTrue(main.contains("_k_self()\n_k_m_app_other = _k_use("), main)
        assertTrue(main.contains("    g = _k_self.__globals__"), main)
        assertTrue(main.contains("def _k_run():\n    return _k_main(main)\n\n\nif __name__ == \"__main__\":\n    _k_exit(_k_run())"), main)
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
                pub a: (x: Int32, y: Int32) Int32 {
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

            Bag: Any {
                pub items: List<Int32>

                pub total: (xs: List<Int32>, mut ys: List<Int32>) Int32 {
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

            f: (n: Size, v: View<UInt8>) UInt32 {
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

            Knob: Any {
                pub name: Str
                pub mut level: Int32 = 3
                pub mut tags: List<Int32> = List<Int32> { }
                mut hidden: Int32 = 9
            }

            Pair: Any {
                pub left: Knob
            }

            scale: (x: Int32, k: Int32 = 2, s: Str = "a", m: Maybe<Int32> = null) Int32 {
                return x * k
            }

            total: (xs: List<Int32> = ROW, extra: Int32 = 0) Size {
                return xs.size()
            }

            f: () Int32 {
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
            Base: Any {
                pub mut seen: Int32 = 0
            }

            Kid: Base {
                pub k: Int32 = 5
            }
            """,
            "the subclass Kid (class inheritance)",
        )
    }

    @Test
    fun aMaybeOfJsonAndAMapOfJsonAreRefusedForJsonNullIsNone() {
        refused(
            """
            kira.json as json

            pub f: (m: Maybe<Json>) Bool {
                return m.isSome()
            }
            """,
            "a Maybe<Json> (none and JSON null are both None on the py target)",
        )
        refused(
            """
            kira.json as json

            pub f: () Size {
                m: Map<Str, Json> = Map<Str, Json> { }
                return m.size()
            }
            """,
            "a Map<Str, Json> (get's None could not tell a missing key from JSON null)",
        )
    }

    @Test
    fun aTraitIsRefused() {
        refused(
            """
            Shape: Any {
                pub area: () Float64;
            }
            """,
            "the trait Shape",
        )
    }

    @Test
    fun float32IsRefused() {
        refused(
            """
            pub f: (x: Float32) Float32 {
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
            Box<T>: Any {
                pub v: T
            }
            """,
            "the generic class Box",
        )
    }

    @Test
    fun theLegacyForLoopIsRefused() {
        refused(
            """
            f: () Void {
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
            f: () Void {
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
            pub Pt: Struct {
                pub x: Int32 = 0
                pub tags: List<Int32> = List<Int32> { }

                pub me: () Pt {
                    return this
                }
            }

            move: (mut p: Pt) Void {
                p = Pt { 1 }
            }

            f: (q: Pt) Int32 {
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
            pub Node: Struct {
                pub v: Int32 = 0
                pub kids: List<Node> = List<Node> { }
            }

            f: (n: Node) Node {
                mut q: Queue<Node> = Queue<Node> { }
                q.enqueue(n)
                mut more: Queue<Node> = q
                return n
            }
            """
        )
        assertTrue(py.contains("        c.kids = list(_k_map(Node._k_clone, self.kids))"), py)
        assertTrue(py.contains("    more = _k_collections.deque(_k_map(Node._k_clone, q))"), py)
        assertTrue(py.contains("def _k_map(f, xs):\n    return _k_builtins.map(f, xs)"), py)
    }

    @Test
    fun aStructComparedByEqualsOrContainsHasTheMemberwiseEqCppDefaults() {
        val py = python(
            """
            pub Pt: Struct {
                pub x: Int32 = 0
                pub y: Int32 = 0
            }

            pub Seg: Struct {
                pub a: Pt = Pt { }
                pub tags: List<Int32> = List<Int32> { }
            }

            pub Other: Struct {
                pub x: Int32 = 0
            }

            f: (xs: List<Pt>, s: Seg, t: Seg, o: Other) Bool {
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
            pub Pt: Struct {
                pub x: Int32 = 0
            }

            first: (xs: List<Pt>) Pt {
                for p: Pt in xs {
                    return p
                }
                return Pt { }
            }

            row: (v: View<List<Int32>>, c: Bool) List<Int32> {
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
            f: (mut x: Int32) Void {
                x = 1
            }
            """,
            "the mut parameter 'x': Int32 (only a List, a Map, a struct or a class is passed by reference)",
        )
    }

    @Test
    fun aMutViewThatWouldWriteACopiedSliceIsRefused() {
        refused(
            """
            fill: (p: MutView<Int32>) Void {
                p[0] = 1
            }
            """,
            "a MutView<Int32> (a MutView<UInt8> is the one that writes through)",
        )
        refused(
            """
            f: () Void {
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
            n: (v: View<Char>) Size {
                return v.size()
            }

            f: () Size {
                return n("abc")
            }
            """,
            "the parameter 'v': a View<Char> (text is a Str on the py target)",
        )
    }

    @Test
    fun aShadowingLocalIsRefused() {
        refused(
            """
            f: () Int32 {
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
    fun aLocalNamedAsPythonReservesIsRenamed() {
        val py = python(
            """
            f: (from: Int32) Int32 {
                len: Int32 = 1
                return len + from
            }
            """
        )
        assertTrue(py.contains("def _f(k_from):") && py.contains("k_len = 1") && py.contains("k_len + k_from"), py)
    }

    @Test
    fun theErrorsPowCatchesAreNamesPythonReserves() {
        refused(
            """
            pub OverflowError: Any {
                pub v: Int32 = 0
            }
            """,
            "the name 'OverflowError', which generated Python uses, is not supported",
        )
        refused(
            """
            pub ValueError: () Int32 {
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
            Box: Any {
                hidden: Int32 = 0

                pub again: () Box {
                    return Box { hidden = 3 }
                }
            }
            """,
            "a value given to the private field 'hidden' at a construction",
        )
    }

    @Test
    fun aLambdaIsAModuleFunctionOrAFactoryOfItsCaptures() {
        val py = python(
            """
            f: (k: Int32, xs: List<Int32>) Int32 {
                g: Fx<Tuple1<Int32>, Int32> = (x: Int32) Int32 {
                    return x
                }
                h: Fx<Tuple1<Int32>, Int32> = (x: Int32) Int32 {
                    return x * k + xs.size() as Int32
                }
                return g(1) + h(2)
            }
            """
        )
        assertTrue(py.contains("    g = _k_lam0\n    h = _k_lam1(k, list(xs))\n    return _k_i32(g(1) + h(2))"), py)
        assertTrue(py.contains("def _k_lam0(x):\n    return x\n"), py)
        assertTrue(py.contains("def _k_lam1(k, xs):\n    def _k_fn(x):\n        return _k_i32(_k_i32(x * k) + _k_as_i32(len(xs)))\n    return _k_fn"), py)
    }

    @Test
    fun aCaptureIsTheClosuresOwnSoWhatItHandsOnIsACopy() {
        val py = python(
            """
            Pt: Struct {
                pub x: Int32 = 0

                pub later: () Fx<Tuple0, Int32> {
                    return () Int32 {
                        return x
                    }
                }

                pub whole: () Fx<Tuple0, Pt> {
                    return () Pt {
                        return this
                    }
                }
            }

            f: (xs: List<Int32>) Fx<Tuple0, List<Int32>> {
                return () List<Int32> {
                    inner: Fx<Tuple0, Size> = () Size {
                        return xs.size()
                    }
                    return xs
                }
            }
            """
        )
        assertTrue(py.contains("return _k_lam0(self.x)"), py)
        assertTrue(py.contains("return _k_lam1(self._k_clone())"), py)
        assertTrue(py.contains("def _k_lam1(self):\n    def _k_fn():\n        return self._k_clone()"), py)
        assertTrue(py.contains("return _k_lam2(list(xs))"), py)
        assertTrue(py.contains("        inner = _k_lam3(xs)\n        return list(xs)"), py)
    }

    @Test
    fun aNamedFunctionARefAndAnFxFieldAreValuesPythonCalls() {
        val py = python(
            """
            pub Bank: Any {
                pub onAdd: Fx<Tuple1<Int32>, Int32>

                pub add: (v: Int32) Int32 {
                    return onAdd(v)
                }
            }

            pub double: (x: Int32) Int32 {
                return x * 2
            }

            f: (b: Bank) Int32 {
                n: Ref<Int32> = Ref<Int32> { value = 1 }
                n.value = n.value + b.onAdd(2)
                fill: Fx<Tuple1<mut List<Int32>>, Void> = (mut out: List<Int32>) Void {
                    out.add(n.value)
                }
                mut xs: List<Int32> = List<Int32> { }
                fill(mut xs)
                return Bank { double }.add(n.value)
            }
            """
        )
        assertTrue(py.contains("return self.onAdd(v)"), py)
        assertTrue(py.contains("n = _k_Ref(1)\n    n.value = _k_i32(n.value + b.onAdd(2))"), py)
        assertTrue(py.contains("    fill(xs)\n"), py)
        assertTrue(py.contains("return Bank(double).add(n.value)"), py)
        assertTrue(py.contains("class _k_Ref:"), py)
    }

    @Test
    fun anFxThatWouldWriteAScalarThroughMutIsRefused() {
        refused(
            """
            f: () Void {
                g: Fx<Tuple1<mut Int32>, Void> = (mut x: Int32) Void {
                    x += 1
                }
            }
            """,
            "an Fx taking a mut Int32 (only a List, a Map, a struct or a class is passed by reference)",
        )
    }

    @Test
    fun float64RemainderIsTheTypersErrorNotTheTargets() {
        val e = PyTestSupport.emit(
            """
            f: (x: Float64, y: Float64) Float64 {
                return x % y
            }
            """
        )
        assertNull(e.text)
        assertTrue(e.errors.any { it.contains("% takes integers") }, e.errors.joinToString("\n"))
        assertTrue(e.errors.none { it.contains(PyModuleEmitter.UNSUPPORTED_CODE) }, e.errors.joinToString("\n"))
    }

    /** D83: a `py: { unsupported }` entry is a decision, refused naming its reason. */
    @Test
    fun anIntegersAbsIsADecidedRefusalThatSaysWhy() {
        decided(
            """
            f: (x: Int32) Int32 {
                return x.abs()
            }
            """,
            "'Num.abs'",
            "an integer's abs wraps its lowest value by its width, which the binding cannot see; kira:math's abs takes a Float64",
        )
        decided("f: (u: UInt64) Float64 {\n    return bitCast<Float64>(u)\n}", "'bitCast'", "a bit cast (writeF64Le then readU64Le gives a Float64's bits)")
    }

    /** Num's own methods bind on py (D83) by the receiver's Kira type: golden numcalls runs them against the C++ target's output. */
    @Test
    fun numsOwnMethodsAreEqualityItsHashAndAs() {
        val py = python(
            """
            f: (x: Int32, v: Float64) Float64 {
                return x.toFloat64() + v.toInt32().toFloat64() + (x.hashCode() + v.toInt64()) as Float64
            }

            g: (x: Int32) Bool {
                return x.equals(3)
            }

            h: (v: Float64, n: Int64) Int64 {
                return v.hashCode() + n.toInt32().toInt64()
            }
            """
        )
        assertTrue(py.contains("float(x) + float(_k_f2i(v, 32, True))"), py)
        assertTrue(py.contains("_k_as_i64(x) + _k_f2i(v, 64, True)"), py)
        assertTrue(py.contains("    return (x == 3)"), py)
        assertTrue(py.contains("_k_fbits(v) + _k_as_i64(_k_as_i32(n))"), py)
    }

    /** A decided call whose result type py also refuses is refused with its decision's reason. */
    @Test
    fun aDecisionIsNamedBeforeTheTypeItReturns() {
        decided("f: (s: Str) Size {\n    return s.view().size()\n}", "'Str.view'", "a View<Char> (text is a Str on the py target)")
    }

    @Test
    fun kiraMathOnAFloat32IsRefused() {
        refused(
            """
            kira.math as math

            f: (x: Float32) Float32 {
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
            kira.io as io
            kira.os as os

            f: (ok: Bool) Void {
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
            parse: (s: Str) Int64 {
                v: Maybe<Int64> = s.toInt64()
                if v.isNone() {
                    throw "bad ${'$'}{s}"
                }
                return v.unwrap()
            }

            pick: (ok: Bool) Int32 {
                return if ok { 1 } else { throw "no" }
            }

            check: (x: Int32) Result<Int32, Str> {
                if x < 0 {
                    return Result.error("negative")
                }
                return Result.success(x)
            }

            f: () Int64 {
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
        assertTrue(py.contains("_k_Error = _k_errors().Error"), py)
    }

    @Test
    fun aTupleIsAPythonTupleOfCopiesAndMapEntriesAreItsItems() {
        val py = python(
            """
            kira.tuples as tuples

            f: (xs: List<Int32>, m: Map<Str, Int32>) Int32 {
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
            f: () Size {
                m: Map<Float64, Int32> = Map<Float64, Int32> { }
                return m.size()
            }
            """,
            "a Map<Float64, Int32> (a float key: a NaN key differs between kira::Map and a dict)",
        )
    }

    @Test
    fun aMapKeyedByAClassIsTheTypersError() {
        val e = PyTestSupport.emit(
            """
            Box: Any {
                pub v: Int32 = 0
            }

            f: (m: Map<Box, Int32>) Size {
                return m.size()
            }
            """
        )
        assertNull(e.text)
        assertTrue(e.errors.any { it.contains("types.type.key") }, e.errors.joinToString("\n"))
        assertTrue(e.errors.none { it.contains(PyModuleEmitter.UNSUPPORTED_CODE) }, e.errors.joinToString("\n"))
    }

    @Test
    fun containersOfContainersAreCopiedDeeply() {
        val py = python(
            """
            f: (m: Map<Str, List<UInt8>>, g: List<List<Int32>>) Size {
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
            f: (m: Map<Str, Maybe<Int32>>) Bool {
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
            f: (q: Queue<Int32>) Int32 {
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
            f: (s: Set<Float64>) Size {
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
            kira.tuples as tuples

            two: () Int32 {
                trace("two")
                return 2
            }

            f: () Int32 {
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
            f: () Size {
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
            pub dict: () Int32 {
                return 1
            }
            """,
            "the name 'dict', which generated Python uses, is not supported",
        )
    }


    @Test
    fun aParameterIsNamedByItsOwnModuleAtEveryCall() {
        val (py, errors) = PyTestSupport.emitProgram(
            "lib:span" to """
                pub LIMIT: List<Int32> = List<Int32> { }

                pub g: (from: Int32, count: List<Int32> = LIMIT) Int32 {
                    return from
                }
                """,
            "app:main" to """
                lib.span as span

                pub count: () Int32 {
                    return 1
                }

                f: () Int32 {
                    return g(from = count()) + g(1)
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("k_from=") && main.contains("count=") && !main.contains("k_count="), main)
    }

    @Test
    fun aParameterNamedLikeItsModulesDeclarationIsRefused() {
        refused(
            """
            pub helper: () Int32 {
                return 1
            }

            pub span: (from: Int32, helper: Int32 = 2) Int32 {
                return from + helper
            }
            """,
            "a parameter named like the module's own 'helper'",
        )
    }

    @Test
    fun aLambdaLocalNamedSelfIsRenamedBesideTheCapturedThis() {
        val py = python(
            """
            C: Any {
                pub n: Int32 = 2

                pub twice: () Int32 {
                    return n * 2
                }

                pub run: () Int32 {
                    f: Fx<Tuple0, Int32> = () Int32 {
                        self: Int32 = twice()
                        return self
                    }
                    return f()
                }
            }
            """
        )
        assertTrue(py.contains("k_self = "), py)
    }

    @Test
    fun aUnicodeEscapeIsFourHexDigitsOfACodePoint() {
        listOf("\\u-001", "\\u+0e9", "\\u00", "\\u0000", "\\ud800").forEach { esc ->
            val e = assertFailsWith<DiagnosticsException>(esc) { PyTestSupport.emit("f: () Str {\n    return \"x${esc}y\"\n}") }
            assertTrue(e.tag == "KiraParser::parseStringLiteral" && e.message.contains("takes four hex digits"), e.message)
        }
    }

    // ---- programs of several modules ----------------------------------------------------------

    @Test
    fun aUsedModuleIsLoadedByItsPathAndNamedThroughItsModuleObject() {
        val (py, errors) = PyTestSupport.emitProgram(
            "lib:units" to """
                pub SCALE: Int32 = 10
                pub mut calls: Int32 = 0

                pub Meter: Any {
                    pub label: Str

                    pub scaled: (x: Int32) Int32 {
                        return x * SCALE
                    }
                }

                pub bump: () Int32 {
                    calls += 1
                    return calls
                }
                """,
            "app:main" to """
                lib.units as units

                f: () Int32 {
                    m: Meter = Meter { "a" }
                    calls = 5
                    units.calls = units.calls + 1
                    return m.scaled(bump()) + units.bump() + SCALE
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val main = py.getValue("app:main")
        assertTrue(main.contains("_k_m_lib_units = _k_use(__file__, \"../lib/units_kira.py\")"), main)
        assertTrue(main.contains("m = _k_m_lib_units.Meter(\"a\")"), main)
        assertTrue(main.contains("    _k_m_lib_units.calls = 5\n    _k_m_lib_units.calls = _k_i32(_k_m_lib_units.calls + 1)"), main)
        assertTrue(main.contains("return _k_i32(_k_i32(m.scaled(_k_m_lib_units.bump()) + _k_m_lib_units.bump()) + _k_m_lib_units.SCALE)"), main)
        assertFalse(main.contains("global calls"), main)
        // Every module registers itself under its real path before it loads another (D85).
        assertTrue(main.contains("\n\n\n_k_self()\n_k_m_lib_units = _k_use("), main)
        val lib = py.getValue("lib:units")
        assertFalse(lib.contains("_k_use"), lib)
        assertTrue(lib.contains("\n\n\n_k_self()\n"), lib)
        assertTrue(lib.contains("def bump():\n    global calls"), lib)
    }

    @Test
    fun aModuleInAUseCycleRegistersItselfBeforeItLoadsTheOther() {
        val (py, errors) = PyTestSupport.emitProgram(
            "app:even" to """
                app.odd as odd

                pub K: Int32 = 2

                pub isEven: (n: Int32) Bool {
                    return n == 0 || isOdd(n - 1)
                }
                """,
            "app:odd" to """
                app.even as even

                pub isOdd: (n: Int32) Bool {
                    return n != 0 && isEven(n - 1)
                }
                """,
        )
        assertEquals(emptyList(), errors)
        val even = py.getValue("app:even")
        // Its constants first, so the module that uses it back reads them while it loads.
        assertTrue(even.contains("K = 2\n\n\n_k_self()\n_k_m_app_odd = _k_use(__file__, \"odd_kira.py\")"), even)
        assertTrue(even.contains("return n == 0 or _k_m_app_odd.isOdd(_k_i32(n - 1))"), even)
        val odd = py.getValue("app:odd")
        assertTrue(odd.contains("_k_self()\n_k_m_app_even = _k_use(__file__, \"even_kira.py\")"), odd)
    }

    @Test
    fun aStdlibFunctionACarriedOneCallsIsCarriedToo() {
        val (py, errors) = PyTestSupport.emitProgram(
            "kira:extra" to """
                pub inner: (v: Float64) Float64 {
                    return v * 2.0
                }

                pub outer: (v: Float64) Float64 {
                    return inner(v) + 1.0
                }
                """,
            "app:main" to """
                kira.extra as extra

                pub f: (v: Float64) Float64 {
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
            kira.math as math
            kira.os as os

            f: (x: Float64) Float64 {
                return clamp(deg2rad(x), lerp(0.0, -1.0, 0.5), 1.0)
            }

            g: () Int32 {
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

    // ---- the sidecar (D67-D70) -------------------------------------------------------------------

    /** [body] as module build:tmp.pyext.[name], its source build/tmp/pyext/[name].kira, beside a sidecar holding [sidecar] (null: none). */
    private fun besideSidecar(body: String, name: String, sidecar: String? = ""): PyTestSupport.Emitted {
        val file = File(PyTestSupport.repoRoot, "build/tmp/pyext/${name}_ext.py").apply { parentFile.mkdirs() }
        if (sidecar == null) file.delete() else file.writeText(sidecar)
        return PyTestSupport.emit(body, "build:tmp.pyext.$name")
    }

    @Test
    fun anExternIsAWrapperOfItsSidecarsFunctionThatChecksWhatComesBack() {
        val py = besideSidecar(
            """
            @_extern(raises = "OSError json.JSONDecodeError") readText: (path: Str) Str

            pub @_extern globSorted: (pattern: Str) List<Str>

            f: () Size {
                return globSorted(readText("p")).size()
            }
            """,
            "wrapper",
        ).python()
        assertTrue(py.contains("_k_x = _k_extern(__file__, \"wrapper_ext.py\", ((\"readText\", 1), (\"globSorted\", 1)))"), py)
        assertTrue(py.contains("_k_r0 = _k_raises(_k_x, \"wrapper_ext.readText\", (\"OSError\", \"json.JSONDecodeError\"))"), py)
        assertTrue(
            py.contains(
                "def _readText(path):\n    try:\n        _k_r = _k_x.readText(path)\n    except _k_r0 as _k_e:\n" +
                    "        _k_thrown(_k_e, _k_r0, (\"OSError\", \"json.JSONDecodeError\"))\n" +
                    "    return _k_check(_k_r, (\"S\",), \"wrapper_ext.readText\", \"Str\")"
            ),
            py,
        )
        assertTrue(py.contains("def globSorted(pattern):\n    return _k_check(_k_x.globSorted(pattern), (\"L\", (\"S\",)), \"wrapper_ext.globSorted\", \"List<Str>\")"), py)
        assertTrue(py.contains("return len(globSorted(_readText(\"p\")))"), py)
    }

    @Test
    fun aViewIsLentForTheCallAndAValueGoesOutAsACopy() {
        val py = besideSidecar(
            """
            Pt: Struct {
                pub x: Int32 = 0
            }

            @_extern send: (data: View<UInt8>, into: MutView<UInt8>, raw: List<UInt8>, xs: List<Int32>, p: Pt, m: Map<Str, Int32>) Int64
            """,
            "lend",
        ).python()
        assertTrue(
            py.contains(
                "def _send(data, into, raw, xs, p, m):\n" +
                    "    with _k_lend(data, False) as _k_v0, _k_lend(into, True) as _k_v1:\n" +
                    "        _k_r = _k_x.send(_k_v0, _k_v1, bytes(raw), list(xs), _k_out(p), _k_out(m))\n" +
                    "    return _k_check(_k_r, (\"I\", -9223372036854775808, 9223372036854775807), \"lend_ext.send\", \"Int64\")"
            ),
            py,
        )
    }

    @Test
    fun anOpaqueClassIsNoPythonClassAndItsMethodIsCalledOnTheHandle() {
        val py = besideSidecar(
            """
            pub @_opaque Sock: Any {
                pub @_extern(raises = "TimeoutError") recv: (size: Int32) List<UInt8>
            }

            f: (s: Sock, socks: List<Sock>, spare: Maybe<Sock>) Size {
                kept: List<Sock> = socks
                return s.recv(4).size() + kept.size()
            }
            """,
            "handle",
        ).python()
        assertFalse(py.contains("class Sock"), py)
        assertTrue(py.contains("_k_x = _k_extern(__file__, \"handle_ext.py\", ())\n_k_r0 = _k_raises(_k_x, \"Sock.recv\", (\"TimeoutError\",))"), py)
        assertTrue(py.contains("def _k_o_Sock_recv(_k_o, size):\n    try:\n        _k_r = _k_o.recv(size)\n"), py)
        assertTrue(py.contains("    return _k_check(_k_r, (\"Y\",), \"Sock.recv\", \"List<UInt8>\")"), py)
        assertTrue(py.contains("kept = list(socks)"), py)
        assertTrue(py.contains("len(_k_o_Sock_recv(s, 4))"), py)
    }

    @Test
    fun aModuleWithExternsAndNoSidecarIsAnError() {
        val e = besideSidecar("@_extern f: () Int32", "lonely", sidecar = null)
        assertNull(e.text)
        assertTrue(e.errors.any { it.contains("error: py.extern: lonely.kira declares externs, whose sidecar lonely_ext.py is not beside it") }, e.errors.joinToString("\n"))
        val raises = besideSidecar("pub @_opaque Sock: Any {\n    pub @_extern(raises = \"TimeoutError\") recv: () Int32\n}", "raising", sidecar = null)
        assertTrue(raises.errors.any { it.contains("py.extern: raising.kira") }, "a raises = name resolves in the sidecar first:\n${raises.errors.joinToString("\n")}")
    }

    @Test
    fun anExternIsRefusedWhatCannotCrossToOrFromPython() {
        listOf(
            "@_extern(cpp = \"ns::f\", header = \"f.hxx\") f: () Int32" to "@_extern's cpp =, header = on the extern 'f'",
            "@_extern(\"sym\") f: () Int32" to "@_extern's positional symbol on the extern 'f'",
            "@_extern(raises = \"OSError Exception\") f: () Int32" to "raises = \"Exception\" (it would catch Kira's own throw and panic)",
            "@_extern(raises = \"KeyboardInterrupt\") f: () Int32" to "raises = \"KeyboardInterrupt\" (no Exception, it always passes through)",
            "Pt: Struct {\n    pub x: Int32 = 0\n}\n@_extern f: () Pt" to "the result of the extern 'f': Pt, a Kira object Python cannot make",
            "@_extern f: () Arr<Int32, 2>" to "the result of the extern 'f': an Arr<Int32, 2> (a List comes back)",
            "@_extern f: (c: Char) Void" to "the parameter 'c' of the extern 'f': a Char (a one-character Str crosses)",
            "@_extern f: (s: List<Set<Int32>>) Void" to "the parameter 's' of the extern 'f': a Set<Int32> inside a value (a Set crosses as a parameter or a result)",
            "@_extern f: (v: View<Int32>) Void" to "the parameter 'v' of the extern 'f': a View<Int32> (a View or MutView of UInt8 is what is lent)",
            "@_extern f: (mut n: List<Int32>) Void" to "the mut parameter 'n' of the extern 'f'",
            "@_extern f: (n: Int32 = 3) Void" to "a default on the parameter 'n' of the extern 'f'",
            "@_extern f: () Int32 {\n    return 1\n}" to "the extern 'f' with a body",
            "pub f: () Int32;" to "the body-less function 'f' (a sidecar's function is an @_extern fx)",
            "@_extern f: () Fx<Tuple0, Int32>" to "the result of the extern 'f': an Fx<Tuple0, Int32> (Python hands Kira no function)",
            "@_extern f: (g: Fx<Tuple1<mut List<Int32>>, Void>) Void" to "(Python writes back through no mut parameter)",
            "Pt: Struct {\n    pub x: Int32 = 0\n}\n@_extern f: (g: Fx<Tuple1<Pt>, Void>) Void" to "the parameter 'g' of the extern 'f': Pt, a Kira object Python cannot make",
            "@_extern f: (g: Fx<Tuple1<View<UInt8>>, Void>) Void" to "the parameter 'g' of the extern 'f': a View<UInt8> (Python is lent a view, never gives one)",
            "@_extern f: (gs: List<Fx<Tuple0, Int32>>) Void" to "inside a value (only an Fx parameter is wrapped)",
            "@_extern f: (g: Fx<Tuple0, Fx<Tuple0, Int32>>) Void" to "(an Fx returning an Fx)",
            "Holder: Any {\n    pub n: Int32 = 0\n    pub f: Fx<Tuple1<Int32>, Void>\n}\n@_extern f: (h: Holder) Void" to
                "the parameter 'h' of the extern 'f': Holder, whose field 'f' is an Fx<Tuple1<Int32>, Void> inside a value (only an Fx parameter is wrapped)",
            "Holder: Any {\n    pub n: Int32 = 0\n    pub f: Fx<Tuple1<Int32>, Void>\n}\n@_extern f: (g: Fx<Tuple0, Holder>) Void" to
                "the parameter 'g' of the extern 'f': Holder, whose field 'f' is an Fx<Tuple1<Int32>, Void> inside a value",
            "In: Struct {\n    pub s: Set<Int32> = Set<Int32> { }\n}\nOut: Struct {\n    pub i: In = In { }\n}\n@_extern f: (o: Out) Void" to
                "the parameter 'o' of the extern 'f': Out, whose field 'i' is In, whose field 's' is a Set<Int32>",
            "C: Struct {\n    pub c: Char = 'x'\n}\n@_extern f: (c: List<C>) Void" to "C, whose field 'c' is a Char (a one-character Str crosses)",
        ).forEach { (body, construct) -> refused(body, construct, besideSidecar(body, "refused")) }
    }

    @Test
    fun aClassThatHoldsItselfCrossesOnceChecked() {
        val e = besideSidecar(
            """
            pub Node: Any {
                pub mut next: Maybe<Node> = null
                pub mut items: List<Node> = List<Node> { }
            }

            @_extern visit: (n: Node, f: Fx<Tuple0, Node>) Void
            """,
            "selfref",
        )
        assertTrue(e.text != null, e.errors.joinToString("\n"))
    }

    @Test
    fun anFxHandedToTheSidecarChecksWhatPythonPassesIt() {
        val py = besideSidecar(
            """
            @_extern each: (xs: List<Str>, f: Fx<Tuple2<Str, Maybe<Int64>>, List<UInt8>>) Int32

            size: (s: Str, n: Maybe<Int64>) List<UInt8> {
                return s.bytes()
            }

            g: () Int32 {
                return each(["a"], size)
            }
            """,
            "fxarg",
        ).python()
        assertTrue(
            py.contains(
                "def _each(xs, f):\n    with _k_fx(f, (((\"S\",), \"Str\"), ((\"M\", (\"I\", -9223372036854775808, 9223372036854775807)), " +
                    "\"Maybe<Int64>\")), \"the Fx f given to fxarg_ext.each\") as _k_f1:\n        _k_r = _k_x.each(list(xs), _k_f1)\n" +
                    "    return _k_check(_k_r, (\"I\", -2147483648, 2147483647), \"fxarg_ext.each\", \"Int32\")"
            ),
            py,
        )
        assertTrue(py.contains("return _each([\"a\"], _size)"), py)
        assertTrue(py.contains("class _k_fx:"), py)
    }

    @Test
    fun kiraTimeAndArgsAreBoundAndMainRunsUnderKMain() {
        val py = python(
            """
            kira.os as os
            kira.time as time

            main: () Void {
                t: Int64 = monoNowNs() + monoNowMs() + wallNowNs()
                sleepMs(5)
                trace(args().size())
            }
            """
        )
        assertTrue(py.contains("_k_time.perf_counter_ns() + (_k_time.perf_counter_ns() // 1000000)"), py)
        assertTrue(py.contains("_k_time.time_ns()"), py)
        assertTrue(py.contains("    _k_sleep(5)\n"), py)
        assertTrue(py.contains("print(len(list(_k_sys.argv)))"), py)
        assertTrue(py.contains("def _k_main(main, *args):"), py)
        assertTrue(py.trimEnd().endsWith("def _k_run():\n    return _k_main(_main)\n\n\nif __name__ == \"__main__\":\n    _k_run()"), py)
    }

    // ---- kira:sync (D77) ---------------------------------------------------------------------------

    @Test
    fun kiraSyncIsTheRuntimesThreadMutexAtomicAndQueue() {
        val py = python(
            """
            kira.sync as sync

            Tally: Struct {
                pub count: Int32 = 0
            }

            f: () Int64 {
                hits: Atomic<Int32> = Atomic<Int32> { value = 0 }
                tally: Mutex<Tally> = Mutex<Tally> { value = Tally { } }
                q: BlockingQueue<List<Int32>> = BlockingQueue<List<Int32>> { }
                mut row: List<Int32> = [1]
                t: Thread = spawn("w", () Void {
                    hits.add(1)
                    q.push(row)
                    tally.lock((mut s: Tally) Void {
                        s.count += 1
                    })
                })
                t.join()
                return q.size() as Int64 + hits.load() as Int64
            }
            """
        )
        assertTrue(py.contains("hits = _k_Atomic(0, _k_as_i32)"), py)
        assertTrue(py.contains("tally = _k_Mutex(_Tally())"), py)
        assertTrue(py.contains("q = _k_BlockingQueue()"), py)
        assertTrue(py.contains("t = _k_Thread(\"w\", _k_lam0(hits, q, list(row), tally))"), py)
        assertTrue(py.contains("        hits.add(1)\n        q.push(list(row))\n        tally.lock(_k_lam1)"), py)
        assertTrue(py.contains("def _k_lam1(s):\n    s.count = _k_i32(s.count + 1)"), py)
        assertTrue(py.contains("    t.join()\n"), py)
        assertTrue(py.contains("class _k_Thread:") && py.contains("def _k_callback(f, args, owner, thrown=70):"), py)
        assertTrue(py.contains("target=_k_callback, args=(body, (), None, None)"), py)
    }

    @Test
    fun onSignalSetsAnAtomicFlagFromAPythonHandler() {
        val py = python(
            """
            kira.os as os
            kira.sync as sync

            f: () Bool {
                stop: Atomic<Bool> = Atomic<Bool> { value = false }
                return onSignal(SIGNAL_INT, stop) && onSignal(SIGNAL_TERM, stop)
            }
            """
        )
        assertTrue(py.contains("stop = _k_Atomic(False, None)\n    return _k_onsignal(2, stop) and _k_onsignal(15, stop)"), py)
        assertTrue(py.contains("import signal as _k_signal") && py.contains("def _k_onsignal(sig, flag):"), py)
    }

    @Test
    fun aMutClassParameterGivenANewObjectIsRefused() {
        refused(
            """
            Box: Any {
                pub mut n: Int64 = 0
            }

            reset: (mut b: Box) Void {
                b.n = 1
                b = Box { }
            }
            """,
            "a new Box assigned to the mut parameter 'b'",
        )
    }

    @Test
    fun kiraSyncRefusesWhatPythonCannotHoldAsCDoes() {
        refused("kira.sync as sync\nf: () Void {\n    m: Mutex<Int32> = Mutex<Int32> { value = 0 }\n}", "a Mutex<Int32> (a Mutex on py holds a List, a Map, a struct or a class)")
        refused("kira.sync as sync\nf: () Void {\n    a: Atomic<Str> = Atomic<Str> { value = \"\" }\n}", "an Atomic<Str> (an Atomic holds a Bool, an integer or a Float64)")
        refused("kira.sync as sync\nf: () Bool {\n    a: Atomic<Bool> = Atomic<Bool> { value = false }\n    return a.add(true)\n}", "Atomic<Bool>.add (C++'s Atomic has none)")
    }

    @Test
    fun anOpaqueHandleIsComparedNeitherThroughAStructNorByContains() {
        val handle = "pub @_opaque Sock: Any {\n    pub id: () Int32\n}\n"
        listOf(
            "${handle}Peer: Struct {\n    pub s: Sock\n}\nf: (a: Peer, b: Peer) Bool {\n    return a == b\n}" to "a comparison of Peer, which holds an opaque handle",
            "${handle}Peer: Struct {\n    pub s: List<Sock>\n}\nf: (a: Peer, b: Peer) Bool {\n    return a != b\n}" to "a comparison of Peer, which holds an opaque handle",
            "${handle}f: (xs: List<Sock>, s: Sock) Bool {\n    return xs.contains(s)\n}" to "'contains' on a List<Sock>, which compares an opaque handle",
            "${handle}f: (m: Map<Str, Sock>, s: Sock) Bool {\n    return m.containsValue(s)\n}" to "'containsValue' on a Map<Str, Sock>, which compares an opaque handle",
        ).forEach { (body, construct) -> refused(body, construct) }
    }

    // ---- kira:test (D80) -------------------------------------------------------------------------

    @Test
    fun aSuiteIsTheRuntimesClassBuiltFromItsTitleAndItsMethodsBind() {
        val py = python(
            """
            kira.test as test

            Runner: Any {
                pub t: Suite
            }

            both: (t: Suite, n: Int32) Void {
                t.check(n > 1, "above one")
                t.checkStr("${'$'}{n}", "3", "reads as 3")
            }

            pub main: () Int32 {
                name: Str = "suite"
                t: Suite = Suite { title = name + "!" }
                r: Runner = Runner { t }
                both(r.t, 3)
                kept: List<Suite> = [t, Suite { "other" }]
                return kept[0].finish()
            }
            """
        )
        assertTrue(py.contains("\nclass _k_Suite:\n"), py)
        assertTrue(py.contains("    t.check(n > 1, \"above one\")\n    t.checkStr(str(n), \"3\", \"reads as 3\")\n"), py)
        assertTrue(py.contains("    t = _k_Suite(name + \"!\")\n    r = _Runner(t)\n    _both(r.t, 3)\n"), py)
        assertTrue(py.contains("    kept = [t, _k_Suite(\"other\")]\n    return kept[0].finish()\n"), py)
        assertTrue(py.contains("        self.t = t\n"), "a Suite is one object, never copied:\n$py")
        assertTrue(py.trimEnd().endsWith("_k_exit(_k_run())"), py)
    }

    /** K3, K4: kira:os on py is the runtime's classes and helpers, each leaving its reason on the shared reasons (D86). */
    @Test
    fun kiraOsIsTheRuntimesClassesAndHelpers() {
        val py = python(
            """
            kira.os as os

            f: (path: Str) Bool {
                s: UdpSocket = UdpSocket {}
                ok: Bool = s.bind("127.0.0.1", 0) && exists(path) && readFile(path).value.size() > 0
                out: Maybe<FileOut> = openAppend(path)
                ran: Maybe<Ran> = runCapture([path], null, 0)
                return ok && lastErrorKind() == "" && out.isSome() && ran.value.code == 0
            }
            """
        )
        assertTrue(py.contains("    s = _k_UdpSocket()\n    ok = s.bind(\"127.0.0.1\", 0) and _k_ostest(_k_os.path.exists, path) and len(_k_value(_k_readfile(path, -1))) > 0\n"), py)
        assertTrue(py.contains("    out = _k_openout(path, \"ab\")\n    ran = _k_runcapture([path], None, 0)\n"), py)
        assertTrue(py.contains("_k_oserrkind() == \"\""), py)
        assertTrue(py.contains("\nclass _k_UdpSocket:\n") && py.contains("\nclass _k_FileOut:\n") && py.contains("\nclass _k_Ran:\n"), py)
        assertTrue(py.contains("\n_k_reasons = _k_errors().__dict__.setdefault(\"reasons\", _k_threading.local())\n"), py)
        val reads = python("kira.os as os\n\nf: (path: Str) Str {\n    return readText(path).value + lastError()\n}")
        listOf("subprocess", "socket", "select", "shutil").forEach { assertFalse(reads.contains("import $it as"), "a module that reads a file imports no $it:\n$reads") }
    }

    /** K5, K6: the Python-semantics modules are Python's own calls; Random is random.Random (critique 21), a CsvReader the runtime's. */
    @Test
    fun thePythonSemanticsModulesArePythonsOwnCalls() {
        val py = python(
            """
            kira.path as path
            kira.pytext as pytext
            kira.regex as regex
            kira.random as random
            kira.csv as csv

            f: (p: Str, rx: Regex) Bool {
                r: Maybe<Regex> = compile("a+", IGNORE_CASE)
                g: Random = newRandom(7)
                rows: Maybe<CsvReader> = openCsv(joinPath(p, "x.csv"))
                return r.isSome() && pyInt(p).isSome() && g.below(3) >= 0 && rows.isSome() && rx.search(p).isSome() && strRepr(p) != regexError()
            }
            """
        )
        assertTrue(py.contains("    r = _k_recompile(\"a+\", 2)\n    g = _k_Random(7)\n    rows = _k_opencsv(_k_os.path.join(p, \"x.csv\"))\n"), py)
        assertTrue(py.contains("(_k_pyint(p) is not None) and _k_below(g, 3) >= 0") && py.contains("(rx.search(p) is not None) and _k_builtins.repr(p) != _k_reerror()"), py)
        assertTrue(py.contains("\n_k_Random = _k_random.Random\n") && py.contains("\nclass _k_CsvReader:\n") && py.contains("import csv as _k_csv"), py)
        val path = python("kira.path as path\n\nf: (p: Str) Str {\n    return slashed(normPath(p))\n}")
        assertTrue(path.contains("    return _k_os.path.normpath(p).replace(_k_os.sep, \"/\")\n"), path)
        listOf("csv", "random", "re", "textwrap", "datetime", "binascii").forEach { assertFalse(path.contains("import $it as"), "a module of paths imports no $it:\n$path") }
        val plus = python("kira.math as math\nkira.bytes as bytes\nkira.time as time\n\nf: (b: List<UInt8>) Str {\n    return utcStamp(findBytes(b, b, 0) + crc16Xmodem(b, 0) as Int64) + \"${'$'}{floorMod(-1.0, 3.0)} ${'$'}{exp(1.0)}\"\n}")
        assertTrue(plus.contains("_k_utcstamp(_k_i64(_k_findbytes(b, b, 0) + _k_binascii.crc_hqx(b, 0)))") && plus.contains("_k_floormod(-1.0, 3.0)") && plus.contains("_k_exp(1.0)"), plus)
    }

    /** K7: kira:http is runtime.py's helpers over urllib and http.server, its handler a Kira function; kira:args argparse's. */
    @Test
    fun kiraHttpAndArgsAreTheRuntimesHelpersOverPythonsOwn() {
        val py = python(
            """
            kira.http as http
            kira.args as args

            pub handle: (r: HttpRequest) HttpAnswer {
                return HttpAnswer { status = 404 }
            }

            pub f: (url: Str) Bool {
                got: Maybe<HttpReply> = httpGet(url, 500)
                s: Maybe<HttpServer> = httpServer("127.0.0.1", 0, handle)
                spec: ArgSpec = argSpec("tool", "what it does")
                spec.whole("--n", 3, "how many")
                b: List<UInt8> = "x".bytes()
                return got.isSome() && httpPost(url, b, "text/plain", 0).isSome() && s.isSome() && spec.parse(["--n", "4"]).isSet("--n")
            }
            """
        )
        assertTrue(py.contains("    got = _k_httpcall(url, None, None, 500)\n    s = _k_httpbind(\"127.0.0.1\", 0, handle, None)\n    spec = _k_ArgSpec(\"tool\", \"what it does\")\n    spec.whole(\"--n\", 3, \"how many\")\n"), py)
        assertTrue(py.contains("    return _k_HttpAnswer(404, \"application/json\", bytearray(()), {})\n") && py.contains("_k_httpcall(url, b, \"text/plain\", 0)"), py)
        assertTrue(py.contains("\nclass _k_HttpGet(_k_httpserver.BaseHTTPRequestHandler):\n") && py.contains("\nclass _k_ArgSpec:\n") && py.contains("import argparse as _k_argparse"), py)
        val split = python("kira.http as http\n\nf: (t: Str) Str {\n    return urlPath(t).unwrapOr(\"/\") + queryFirst(urlQuery(t).unwrapOr(\"\"), \"s\").unwrapOr(\"\")\n}")
        assertTrue(split.contains("_k_urlpart(t, \"path\")") && split.contains("import urllib.parse as _k_urlparse"), split)
        listOf("http.server", "http.client", "urllib.request", "argparse").forEach { assertFalse(split.contains("import $it as"), "a module that splits a URL imports no $it:\n$split") }
    }

    /** A stdlib class that is Python's own is made only by its module's calls: Regex { } would be re.Pattern(), a TypeError. */
    @Test
    fun aStdlibClassThatIsPythonsOwnIsNeverConstructed() {
        decided("kira.regex as regex\n\nf: () Regex {\n    return Regex { }\n}", "a construction of Regex", "Regex is Python's re.Pattern, which only its module's calls make")
        decided("kira.random as random\n\nf: () Random {\n    return Random { }\n}", "a construction of Random", "Random is Python's random.Random, which only its module's calls make")
        val runtime = PyRuntime(File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText())
        assertEquals("re.Match", runtime.aliasOf("_k_Match"))
        listOf("_k_CsvReader", "_k_Process", "_k_Error", "_k_reasons").forEach { assertNull(runtime.aliasOf(it), it) }
        assertTrue(python("kira.csv as csv\n\nf: () CsvReader {\n    return CsvReader { }\n}").contains("    return _k_CsvReader()\n"))
    }

    /** A runtime class whose __init__ needs more than its fields is made only by its module's calls: ArgSpec { } has no prog. */
    @Test
    fun aStdlibClassThatNeedsItsModulesCallIsNeverConstructed() {
        decided("kira.args as args\n\nf: () ArgSpec {\n    return ArgSpec { }\n}", "a construction of ArgSpec", "ArgSpec is made only by its module's calls")
        decided("kira.args as args\n\nf: () Parsed {\n    return Parsed { }\n}", "a construction of Parsed", "Parsed is made only by its module's calls")
        val runtime = PyRuntime(File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText())
        assertEquals(listOf(2, 2, 0, 0, 2, 1), listOf("_k_ArgSpec", "_k_Parsed", "_k_HttpServer", "_k_Serial", "_k_Thread", "_k_Suite").map { runtime.requiredArgs(it) })
        assertTrue(python("kira.http as http\n\nf: () HttpServer {\n    return HttpServer { }\n}").contains("    return _k_HttpServer()\n"))
    }

    @Test
    fun aStdlibClassTheRuntimeLacksIsStillRefused() {
        refused("f: (u: StrBuf<64>) Void {\n}", "the parameter 'u': the type StrBuf")
    }

    // ---- stdlib classes and structs on py (E1, D84) ----------------------------------------------

    /** E1: a stdlib magic class is the runtime's class of its name whether or not C++ has a header for its module. */
    @Test
    fun aMagicClassOfAModuleWithoutACppHeaderIsTheRuntimesClassOfItsName() {
        val (py, errors) = PyTestSupport.emitProgram(
            "kira:extra" to "pub @_magic Suite: Any {\n    pub title: Str\n}",
            "app:main" to "kira.extra as extra\n\nf: () Void {\n    t: Suite = Suite { title = \"x\" }\n}",
        )
        assertEquals(emptyList(), errors)
        assertTrue(py.getValue("app:main").contains("    t = _k_Suite(\"x\")\n"), py.getValue("app:main"))
        val ref = python("f: () Int32 {\n    r: Ref<Int32> = Ref<Int32> { value = 1 }\n    return r.value\n}")
        assertTrue(ref.contains("    r = _k_Ref(1)\n"), "a Ref keeps its own construction:\n$ref")
    }

    @Test
    fun aStdlibStructIsBuiltWithItsDefaultsCopiedAndComparedAsAUserStruct() {
        val py = python(
            """
            kira.os as os

            Seen: Struct {
                pub last: Datagram = Datagram { }
            }

            f: (d: Datagram) Bool {
                mut e: Datagram = d
                e.port = 2
                r: Ready = Ready { events = POLL_READ }
                xs: List<Ready> = [r]
                ys: List<Ready> = xs
                s: Seen = Seen { }
                return e == d && ys[0] != r && s.last == Datagram { host = "h" }
            }
            """
        )
        assertTrue(py.contains("    e = d._k_clone()\n    e.port = 2\n    r = _k_Ready(-1, 1)\n"), py)
        assertTrue(py.contains("    ys = list(_k_map(_k_Ready._k_clone, xs))\n"), py)
        assertTrue(py.contains("        self.last = _k_Datagram(0, \"\", 0) if last is _k_unset else last._k_clone()\n"), py)
        assertTrue(py.contains("return e == d and ys[0] != r and s.last == _k_Datagram(0, \"h\", 0)"), py)
        assertTrue(py.contains("\nclass _k_Datagram:\n") && py.contains("\nclass _k_Ready:\n"), py)
    }

    /** D84: each stdlib struct's runtime class holds its fields in declaration order, by the names the emitter reaches them by, with the methods it calls. */
    @Test
    fun everyStdlibStructsRuntimeClassHoldsItsFieldsInOrder() {
        val runtime = File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText().replace("\r\n", "\n")
        val program = net.exoad.kira.types.TyperTestSupport.snippet("kira.os as os\nkira.http as http\n")
        val structs = program.modules.filter { it.isStdlib }.flatMap { it.declarations }
            .filterIsInstance<net.exoad.kira.compiler.analysis.types.ClassSymbol>()
            .filter { it.kind == net.exoad.kira.compiler.analysis.types.ClassKind.STRUCT }
        assertEquals(listOf("Datagram", "HttpAnswer", "HttpReply", "HttpRequest", "Ran", "Ready"), structs.map { it.name }.sorted())
        structs.forEach { s ->
            val block = runtime.substringAfter("\nclass _k_${s.name}:\n", "").substringBefore("\n\n\n")
            // pyName: a field that is not pub is _name.
            val slots = s.fields.joinToString(", ") { "\"${if (it.isPub) it.name else "_${it.name}"}\"" }
            assertTrue(block.contains("    __slots__ = ($slots)\n"), "_k_${s.name}'s slots are not $slots:\n$block")
            assertTrue(block.contains("    def __init__(self, ${s.fields.joinToString(", ") { it.name }}):"), block)
            listOf("_k_clone(self)", "_k_set(self, o)", "__eq__(self, o)").forEach { assertTrue(block.contains("    def $it:"), "_k_${s.name} lacks $it") }
        }
    }

    @Test
    fun aStdlibStructNeverCrossesToASidecar() {
        listOf(
            "kira.os as os\n\n@_extern send: (d: Datagram) Bool" to "the parameter 'd' of the extern 'send': a Datagram",
            "kira.os as os\n\nHold: Struct {\n    pub r: Ready\n}\n\n@_extern put: (h: Hold) Bool" to "the parameter 'h' of the extern 'put': Hold, whose field 'r' is a Ready",
            "kira.os as os\n\n@_extern got: () Maybe<Ready>" to "the result of the extern 'got': a Ready",
        ).forEach { (body, construct) -> refused(body, construct, besideSidecar(body, "stdstruct")) }
    }

    // ---- the patch surface (D88) and Fx-calling runtime classes (E6) ------------------------------

    /** D88: a test that patches the stdlib reaches the runtime, which binds no stdlib function at load but the waits' clock. */
    @Test
    fun theRuntimeReadsEveryStdlibFunctionWhereItCallsIt() {
        val runtime = File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText().replace("\r\n", "\n")
        assertTrue(runtime.lines().none { it.startsWith("from ") || it.trimStart().startsWith("from ") && " import " in it }, "a from-import in runtime.py")
        // A function a top-level line takes from a module without calling it is bound at load; a class (_k_re.Pattern) is no function.
        val uncalled = Regex("\\b_k_[a-z]+\\.[a-z_]\\w*(?![\\w(])")
        val bound = Regex("^(_k_\\w+) = (.*)$", RegexOption.MULTILINE).findAll(runtime)
            .filter { uncalled.containsMatchIn(it.groupValues[2]) }.map { it.groupValues[1] }.toList()
        assertEquals(listOf("_k_deadline"), bound, "a stdlib function bound at load, which a test's patch would not reach")
        assertTrue(runtime.contains("\n_k_deadline = _k_errors().__dict__.setdefault(\"deadline\", _k_time.monotonic)\n"), "the waits' clock is one for every module")
        assertTrue(runtime.contains("so a test's patch reaches it (D88)"), "runtime.py's header states the rule")
    }

    /** E6: a runtime class that calls a Kira Fx on a thread Python owns ends the process on its throw, panic or exit. */
    @Test
    fun aKiraFxARuntimeClassCallsEndsTheProcessOnWhatNoTryCatches() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val runtime = PyRuntime(File(PyTestSupport.repoRoot, "kira/py/runtime.py").readText())
        val script = File(PyTestSupport.repoRoot, "build/tmp/py-kirafx/kirafx.py").apply { parentFile.mkdirs() }
        val driver = """
            import sys
            import threading

            def thrower():
                _k_throw("from a handler")

            def body(what):
                print("returns", _k_kirafx(lambda n: n + 1, 4), flush=True)
                {"throw": thrower, "panic": lambda: _k_panic("bad"), "exit": lambda: _k_exit(5), "none": lambda: None}[what]()
                print("after", what, flush=True)

            t = threading.Thread(target=lambda: _k_kirafx(body, sys.argv[1]))
            t.start()
            t.join()
            print("main went on")
        """.trimIndent()
        script.writeText(runtime.select(setOf("_k_kirafx", "_k_throw", "_k_panic", "_k_exit")) + "\n\n\n" + driver + "\n")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        for ((what, want) in listOf("throw" to 70, "panic" to (if (windows) 3 else 134), "exit" to 5, "none" to 0)) {
            val ran = PyTestSupport.run(listOf(PyTestSupport.python!!, script.absolutePath, what), script.parentFile)
            assertEquals(want, if (ran.exitCode == -6) 134 else ran.exitCode, "$what:\n${ran.all}")
            assertEquals(if (what == "none") "returns 5\nafter none\nmain went on\n" else "returns 5\n", ran.stdout, "$what:\n${ran.all}")
        }
    }
}
