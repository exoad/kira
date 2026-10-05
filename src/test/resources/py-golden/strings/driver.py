#!/usr/bin/env python3
# The strings golden: cpp-golden/strings' driver/main.cxx in Python, its checks one for one and
# in its order, under a comma-decimal locale when the machine has one (Python's float text and
# parsing ignore it, as kira's do). Two checks pass fewer pieces: describe leaves out the Float32
# and the integer enum the py target does not hold. A Char crosses into Python as its code
# point, an int. The expected output is the C++ backend's run of the same checks over the same
# module, which prints cpp-golden/strings' expected output line for line.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import locale
import os
import struct
import sys

path = os.path.join(sys.argv[1], "src", "lang", "strings.kira.py")
spec = importlib.util.spec_from_file_location("strings_kira", path)
strings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strings)

checks = 0
failures = 0


def check(ok, what):
    global checks, failures
    checks += 1
    if ok:
        print("  ok    %s" % what)
    else:
        print("  FAIL  %s" % what)
        failures += 1


def checkStr(got, want, what):
    ok = got == want
    check(ok, what)
    if not ok:
        print('        got  "%s"\n        want "%s"' % (got, want))


def isInt(m, want):
    return m is not None and m == want


def isFloat(m, want):
    return m is not None and m == want


def isSome(m):
    return m is not None


# As a GUI toolkit or a library may: printf and strtod now follow de_DE.
for name in ("de_DE.UTF-8", "de_DE.utf8", "de-DE", "German_Germany.1252"):
    try:
        locale.setlocale(locale.LC_ALL, name)
        break
    except locale.Error:
        pass
print("\nstrings - text, interpolation and strict parsing\n")
checkStr(strings.greet("mochi", 3), "hello mochi, you are 3", "interpolation")
checkStr(strings.describe(True, 0.1, ord("x"), 200), "b=true f=0.1 c=x u=200", "every piece kind")
checkStr(strings.describe(False, 1e21, ord("-"), 0), "b=false f=1e+21 c=- u=0", "and exponents")
checkStr(strings.show(2.5), "2.5", "as Str: 2.5, not 2,5")
checkStr(strings.show(100.0), "100", "as Str: 100")
checkStr(strings.show(0.1 + 0.2), "0.30000000000000004", "as Str is the shortest text that reads back")
checkStr(strings.flag(True), "true", "text(Bool) is true")
checkStr(strings.flag(False), "false", "and false")
nan = struct.unpack("<d", (0x7FF8000000000000).to_bytes(8, "little"))[0]
negNan = struct.unpack("<d", (0xFFF8000000000000).to_bytes(8, "little"))[0]
inf = float("inf")
checkStr(strings.show(nan) + " " + strings.show(negNan), "nan nan", "as Str: any NaN is nan")
checkStr(strings.show(1e5) + " " + strings.show(-0.0001) + " " + strings.show(0.001), "1e+05 -1e-04 0.001",
         "as Str: scientific when shorter, fixed on a tie")
checkStr(strings.show(1.2345678901234568e20), "123456789012345683968", "as Str: a large integer's exact digits")
checkStr(strings.fixedOf(0.25, 1) + " " + strings.fixedOf(-0.25, 1) + " " + strings.fixedOf(2.5, 0) + " " +
         strings.fixedOf(1.5, 0) + " " + strings.fixedOf(0.5, 0),
         "0.2 -0.2 2 2 0", "fixed: an exact half goes to even")
checkStr(strings.fixedOf(0.15, 1) + " " + strings.fixedOf(0.35, 1) + " " + strings.fixedOf(2.675, 2) + " " +
         strings.fixedOf(1.005, 2),
         "0.1 0.3 2.67 1.00", "fixed: a decimal half is the double's exact value")
checkStr(strings.fixedOf(0.45, 1) + " " + strings.fixedOf(99.95, 1) + " " + strings.fixedOf(123456.789, 0),
         "0.5 100.0 123457", "fixed: rounds up past a half")
checkStr(strings.fixedOf(-0.0, 1) + " " + strings.fixedOf(-0.0001, 3), "-0.0 -0.000", "fixed: a negative zero keeps its sign")
checkStr(strings.fixedOf(nan, 2) + " " + strings.fixedOf(negNan, 2) + " " + strings.fixedOf(inf, 1) + " " +
         strings.fixedOf(-inf, 1),
         "nan nan inf -inf", "fixed: NaN and the infinities")
checkStr(strings.fixedOf(1e21, 1), "1000000000000000000000.0", "fixed: 1e21 in full")
checkStr(strings.fixedOf(1.5, -1) + " " + strings.fixedOf(0.1, 12), "2 0.100000000", "fixed: places clamped to 0..9")
checkStr(strings.speedLine(3.25), "3.2 m/s", "fixed in an interpolation")
checkStr(strings.hexOf(255) + " " + strings.hexOf(-255) + " " + strings.hexOf(0), "ff -ff 0", "toHex: lowercase, a sign")
checkStr(strings.hexOf(-2147483648), "-80000000", "toHex: Int32 min")
checkStr(strings.hex64(18446744073709551615), "ffffffffffffffff", "toHex: UInt64 max")
checkStr(strings.bitsOf(-1) + " " + strings.bitsOf(-4096), "ffff f000", "toHex of as UInt16: the bits")
checkStr(strings.padded(42), "00042", "padStart")
checkStr(strings.padded(123456), "123456", "padStart never cuts")
check(isInt(strings.parseInt("1541"), 1541) and isInt(strings.parseInt("+7"), 7) and isInt(strings.parseInt("-0"), 0),
      "toInt64: digits and an optional sign")
check(isInt(strings.parseInt("-9223372036854775808"), -9223372036854775808), "toInt64: Int64 min")
check(not isSome(strings.parseInt("9223372036854775808")), "toInt64: overflow is none")
check(not isSome(strings.parseInt("1541abc")) and not isSome(strings.parseInt(" 5")) and
      not isSome(strings.parseInt("")) and not isSome(strings.parseInt("5.0")),
      "toInt64 refuses a tail, a space, nothing and a decimal")
check(isFloat(strings.parseFloat("2.50"), 2.5) and isFloat(strings.parseFloat("+2.5"), 2.5), "toFloat64, and a leading +")
check(isFloat(strings.parseFloat("1e3"), 1000.0) and isFloat(strings.parseFloat("-0.125"), -0.125), "exponent and sign")
check(not isSome(strings.parseFloat("2,5")) and not isSome(strings.parseFloat("2.5 ")) and
      not isSome(strings.parseFloat("")) and not isSome(strings.parseFloat("+-1")),
      "toFloat64 refuses a comma, a tail, nothing and two signs")
checkStr(strings.joined("a", "b"), "a b", "Str + Str")
checkStr(strings.bracketed("x"), "<x>", "a literal on the left of +")
check(strings.initial("Kira") == ord("K"), "s[0] is a Char")
check(strings.same("abc", "ab" + "c") and not strings.same("a", "b"), "== compares content")
checkStr(strings.middle("[abc]"), "abc", "substring")
checkStr(strings.middle("x"), "", "and a guard")
locale.setlocale(locale.LC_ALL, "C")
print("\n%d checks, %d failed" % (checks, failures))
sys.exit(0 if failures == 0 else 1)
