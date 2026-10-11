#!/usr/bin/env python3
# The pytext golden (K6): main pins bibo's fixtures; then each kira:pytext function, its reason and its value's type are
# Python's own on this interpreter, roundTo the infinity of its sign where round raises OverflowError.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import json
import math
import os
import sys
import textwrap

spec = importlib.util.spec_from_file_location("pytext_kira", os.path.join(sys.argv[1], "src", "app", "pytext_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("pytext ->", m._k_run())


def raised(f, *args):
    try:
        return f(*args), ""
    except (ValueError, OverflowError) as e:
        return None, ("OverflowError: " if isinstance(e, OverflowError) else "ValueError: ") + str(e)


def int64(v):
    if v is not None and not -(1 << 63) <= v < (1 << 63):
        return None, "OverflowError: past 64 bits"
    return v, ""


# Equal and of one type, a NaN to a NaN, at every level of a tuple or a list.
def same(a, b):
    if type(a) is not type(b):
        return False
    if type(a) in (tuple, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b or (a != a and b != b)


def tally(name, pairs):
    print(name, "is Python's:", sum(same(a, b) for a, b in pairs), "of", len(pairs))


numbers = ["0", "-0", "+81", " 82", "8_0", "1_000", "1__0", "_1", "1_", "٨٠", "١.٥", "x", "a'b", 'a"b', "1.5", "0x10", "",
           " ", "1e3", "1E-3", ".5", "5.", "nan", " -Infinity ", "inf", "1,5", "9223372036854775807", "9223372036854775808",
           "-9223372036854775808", "-9223372036854775809", "1" * 4300, "1" * 4301, " 1 ", "0_0", "+-1", "１２"]
ints = []
for t in numbers:
    v, why = raised(int, t)
    if why == "":
        v, why = int64(v)
    ints.append(((m.intOf(t), m.why()), (v, why)))
tally("pyInt and its reason", ints)
tally("pyFloat and its reason", [((m.floatOf(t), m.why()), raised(float, t)) for t in numbers])
texts = ["", "a", "it's", 'a"b', "'\"", "\\", "\t\n\r\x00\x7f", "é", "​", "\U0001f600", "\ud800", "\x85\xa0", "x" * 50]
tally("strRepr", [(m.reprOf(s), repr(s)) for s in texts])
floats = [0.0, -0.0, 1.0, 0.1, 1e16, 1e-5, 1e-4, 123456789.0, 1e22, 5e-324, 1.7976931348623157e308, float("inf"), float("-inf"),
          float("nan"), 2.675, -1234.5678, 0.5, 1.5, 2.5, -2.5, 9.3e18, 1e19, -9.3e18]
tally("floatRepr", [(m.floatText(v), repr(v)) for v in floats])
docs = ['{"a": [1, 2.5, true, null, "x"]}', "[]", "{}", '"\\u00e9\\n"', "1e400", "-0.0", "[1.0, 100000000000000000000.0]", '{"k": {"j": [false]}}']
tally("jsonRepr", [(m.jsonText(d), repr(json.loads(d))) for d in docs])
specs = ["", "g", ".2f", ",", ",.2f", "+", "e", ".2e", "E", "%", ".1%", "10.3f", "<10", "*^12.1f", "=+10.2f", "_", "_.3f", "z.2f",
         "#g", ".0f", ".20g", "d", "s", ".x", "10.10.10", "n"]
tally("formatFloat and its reason", [((m.formatted(v, s), m.why()), raised(format, v, s)) for v in floats for s in specs])
def roundref(v, n):
    try:
        return round(v, n)
    except OverflowError:
        return math.copysign(math.inf, v)


tally("roundTo", [(m.rounded(v, n), roundref(v, n)) for v in floats + [-1.7976931348623157e308] for n in (-400, -308, -2, -1, 0, 1, 2, 5, 400)])
rounded = []
for v in floats:
    r, why = raised(round, v)
    if why == "":
        r, why = int64(r)
    rounded.append(((m.roundedInt(v), m.why()), (r, why)))
tally("roundHalfEven and its reason", rounded)
words = ["", " Straße ", "ǅ", "İ", "ΣΑΣ", "ﬁ", "abc", " a\tb\x1c\x1dc　", " \x85x\x0b\x0c", "MiXeD 1"]
tally("upper, lower, strip, lstrip, rstrip", [(m.cased(s), [s.upper(), s.lower(), s.strip(), s.lstrip(), s.rstrip()]) for s in words])
tally("isUpper", [(m.allUpper(s), s.isupper()) for s in words])
tally("splitSpace", [(m.words(s), s.split()) for s in words])
tally("splitLines", [(m.lines(s), s.splitlines()) for s in words + ["a\nb\r\nc\rd\x1ee f\n", "\n\n"]])
tally("findFrom", [(m.found(s, sub, at), s.find(sub, at)) for s in ("abcabc", "", "ΣΑΣ") for sub in ("", "c", "bc", "Σ") for at in range(8)])
prose = "The quick brown-fox jumps over the lazy dog; supercalifragilistic words stay whole.\nA second  line\tends."
tally("wrapWords", [(m.wrapped(prose, w), textwrap.wrap(prose, width=w, break_long_words=False, break_on_hyphens=False)) for w in range(1, 40)])
codes = list(range(0, 0x300)) + [0x85, 0xa0, 0x1680, 0x2000, 0x2028, 0x3000, 0x660, 0x669, 0xbc, 0xbd, 0x2155, 0x4e00, 0x10ffff]
kinds = []
for c in codes:
    ch = chr(c)
    kinds.append((m.kinds(c), " ".join("true" if f else "false" for f in (ch.isspace(), ch.isalnum(), ch.isalpha(), ch.isdigit()))))
tally("isSpace, isAlnum, isAlpha, isDigit", kinds)
try:
    m.wrapped("a b", 0)
    print("wrapWords of width 0 returned")
except RuntimeError as e:
    print("wrapWords of width 0:", e)
print("of the types:", [type(v).__name__ for v in (m.intOf("1"), m.floatOf("1"), m.formatted(1.0, ""), m.rounded(1.0, 0), m.roundedInt(1.5), m.found("a", "a", 0))], m.intOf("x"))
