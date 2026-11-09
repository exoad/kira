# The corpus for rt_equiv.py: boundary inputs for every kind of helper, as cases that call it by name on a runtime.
# A case builds its own arguments, so two runtimes never share an object.
import copy
import errno
import importlib.util
import itertools
import math
import os
import random
import struct
import sys
import threading
import time

import rt_fakes
from rt_fakes import MISSING, boom, boom_exc

CASES = []

# Names a deletion removes: a case that needs one is skipped when either runtime lacks it, and the shape check
# ignores them, so the deletion passes without touching this file.
DEAD_FUNCTIONS = ("_k_mapset", "_k_dqset")
DEAD_CLASSES = ("_k_Datagram", "_k_Ready", "_k_Ran", "_k_HttpReply", "_k_HttpRequest", "_k_HttpAnswer")
DEAD_METHODS = ("_k_set",)

# Cheap kinds first, so a mutation run that stops at its first difference rarely reaches a process or a socket.
KIND_ORDER = ["int", "float", "text", "slice", "bytes", "radix", "collections", "json", "ffi", "host", "misc", "os"]


class Case:
    __slots__ = ("kind", "tag", "run", "fakes", "fs", "needs")

    def __init__(self, kind, tag, run, fakes, fs, needs):
        self.kind, self.tag, self.run, self.fakes, self.fs, self.needs = kind, tag, run, fakes, fs, needs


class L:
    """An argument built per runtime: f(ctx)."""

    def __init__(self, f):
        self.f = f


def fresh(ctx, x):
    return x.f(ctx) if isinstance(x, L) else copy.deepcopy(x)


def add(kind, tag, run, fakes=None, fs=False, needs=()):
    CASES.append(Case(kind, tag, run, fakes, fs, tuple(needs)))


def call(kind, name, *args, fakes=None, fs=False, needs=()):
    def run(ctx):
        a = [fresh(ctx, x) for x in args]
        return ctx.rt[name](*a), a

    add(kind, name, run, fakes, fs, (name,) + tuple(needs))


def calls(kind, name, tuples, **opts):
    for t in tuples:
        call(kind, name, *t, **opts)


def grid(kind, name, *axes, **opts):
    calls(kind, name, itertools.product(*axes), **opts)


def method(kind, cls, steps, fakes=None, fs=False, needs=(), tag=None):
    """Build cls(*ctor) and run steps (a list of (method, args)), keeping every result and the object's end state."""

    def run(ctx):
        ctor, plan = steps
        obj = ctx.rt[cls](*[fresh(ctx, x) for x in ctor])
        out = []
        for name, args in plan:
            a = [fresh(ctx, x) for x in args]
            try:
                out.append((name, getattr(obj, name)(*a), a))
            except BaseException as e:
                out.append((name, "raised", type(e).__qualname__, str(e)))
        return out, obj

    add(kind, tag or cls + "." + "+".join(n for n, _ in steps[1]), run, fakes, fs, (cls,) + tuple(needs))


# ---- edges ---------------------------------------------------------------------------------------------------------

INTS = [0, 1, -1, 2, -2, 7, 127, 128, -128, -129, 255, 256, 32767, 32768, -32768, -32769, 65535, 65536,
        2 ** 31 - 1, -2 ** 31, 2 ** 31, -2 ** 31 - 1, 2 ** 32 - 1, 2 ** 32, 2 ** 53, 2 ** 53 + 1,
        2 ** 63 - 1, -2 ** 63, 2 ** 63, -2 ** 63 - 1, 2 ** 64 - 1, 2 ** 64, -2 ** 64, 2 ** 100]
DIV = [0, 1, -1, 2, -2, 7, -7, 2 ** 31 - 1, -2 ** 31, 2 ** 31, 2 ** 63 - 1, -2 ** 63, 2 ** 64 - 1]
FLOATS = [0.0, -0.0, math.nan, math.inf, -math.inf, 5e-324, -5e-324, 1e-323, 2.2250738585072014e-308,
          2.225073858507201e-308, 1.7976931348623157e308, -1.7976931348623157e308, 1e16, 1e15,
          9999999999999998.0, 1e17, 123456789012345680.0, 0.0001, -0.0001, 0.00001, 9.999999999999999e-05,
          0.1, 0.2, 0.30000000000000004, 1 / 3, 3.0, -3.0, 100.0, 1e21, 1e22, 1e23, 2.0 ** 53, 2.0 ** 63,
          2.0 ** 64, 0.5, 1.5, -0.5, -1.5, 2.5, -2.5, 0.49999999999999994, 4.35, 5e-05, 1e-07, 1.0, -1.0, 10.0,
          1e100, 9007199254740993.0, 1.7976931348623155e308, 4.9e-324, 2.5e-324, 0.9999999999999999, 1.0000000000000002]
SUB = [0.0, -0.0, math.nan, math.inf, -math.inf, 5e-324, 1.7976931348623157e308, 0.5, -0.5, 1.5, -1.5, 2.0, -2.0,
       3.0, -3.0, 1e-300, 1e300, 0.1, 2.5, 1.0, -1.0]
STRS = ["", "a", "abc", "héllo", "日本語", "\U0001F600", "a\U0001F600b", "\ud800", "a\udc00b",
        "😀", "AZaz[`@{", "ÀÉÎ", "\x00", "tab\t", "line\r\nx", " x", "ß", "İ",
        " \t\n\r\v\f\x1c\x1d\x1e\x1f\x85\xa0 x"]
BADUTF8 = [b"", b"a", b"\xff", b"\xc0\xaf", b"\xe0\x80\xaf", b"\xed\xa0\x80", b"\xed\xbf\xbf", b"\xf4\x90\x80\x80",
           b"\xf8\x88\x80\x80\x80", b"\xe2\x82", b"\xf0\x9f\x98", b"\xf0\x9f\x98\x80", b"\xf0\x9f\x98\x80\xf0", b"a\xffb",
           b"\x80", b"\xbf\xbf", b"\xe2\x28\xa1", b"\xef\xbb\xbf", b"\xef\xbf\xbe", b"\xc2\x80", b"\xdf\xbf",
           b"\xe0\xa0\x80", b"\xf0\x90\x80\x80", b"\xf4\x8f\xbf\xbf", b"\xfe\xff", b"abc\xe6\x97", b"\xc3\xa9\xc3",
           bytes(range(256))]


def lazy_mv(data, readonly=False):
    def make(ctx):
        m = memoryview(bytearray(data))
        return m.toreadonly() if readonly else m

    return L(make)


# ---- kinds ---------------------------------------------------------------------------------------------------------

def ints():
    for n in ("_k_i32", "_k_i64", "_k_i8", "_k_i16", "_k_u8", "_k_u16", "_k_u32", "_k_u64", "_k_as_i32", "_k_as_i64",
              "_k_hex", "_k_pyint64"):
        calls("int", n, [(v,) for v in INTS])
    grid("int", "_k_divs", DIV, DIV, [32, 64])
    grid("int", "_k_divu", [v for v in DIV if v >= 0], DIV)
    grid("int", "_k_mods", DIV, DIV)
    grid("int", "_k_modu", [v for v in DIV if v >= 0], DIV)
    grid("int", "_k_count", [-1, 0, 1, 7, 8, 31, 32, 63, 64, 65, 2 ** 40], [8, 16, 32, 64])
    grid("int", "_k_f2i", FLOATS + [0, 5, -5, 2 ** 64, 2 ** 1000], [8, 16, 32, 64], [True, False])
    calls("int", "_k_fbits", [(v,) for v in FLOATS + [0, 1, -1, 2 ** 53, 2 ** 63 - 1, 2 ** 1024]])
    calls("int", "_k_btext", [(v,) for v in (True, False, 0, 1, 2, None, "", "x")])
    for t, vals in (("Int32", [[], [1, 2, 3], [2 ** 31 - 1], [2 ** 31 - 1, 1], [-2 ** 31, -1], [-2 ** 31], [2 ** 30, 2 ** 30],
                                [2 ** 31 - 1, 5, -5], [2 ** 31 - 1] * 3 + [-2 ** 31] * 3, [1] * 5000, [2 ** 31 - 1] + [0] * 5000 + [1],
                                [2 ** 31 - 1] + [-1] * 9000 + [1] * 9000]),
                    ("Int64", [[], [2 ** 63 - 1], [2 ** 63 - 1, 1], [-2 ** 63, -1], [-2 ** 63], [2 ** 62, 2 ** 62], [2 ** 62] * 3 + [-2 ** 62] * 3,
                               [2 ** 63 - 1] + [-1] * 9000 + [1] * 9000, [2 ** 63] , [1] * 10000]),
                    ("Int8", [[], [127, 1], [-128, -1], [100] * 50]), ("Int16", [[32767, 1], [-32768, -1], [30000] * 5]),
                    ("UInt8", [[255, 1], [200, 100], []]), ("UInt16", [[65535, 1]]), ("UInt32", [[2 ** 32 - 1, 1], [2 ** 31] * 3]),
                    ("UInt64", [[2 ** 64 - 1, 1], [2 ** 63] * 3, [2 ** 64 - 1] * 5]), ("Size", [[2 ** 64 - 1, 2], []]),
                    ("Float64", [[], [0.1] * 10, [0.1, 0.2, 0.3], [1e100, 1.0, -1e100], [math.nan, 1.0], [math.inf, -math.inf],
                                 [-0.0], [-0.0, -0.0], [0.1] * 5000, [1e16, 1.0, 1.0, 1.0]]),
                    ("Nope", [[1]])):
        calls("int", "_k_sum", [(v, t) for v in vals])


def floats():
    for n in ("_k_sqrt", "_k_floor", "_k_ceil", "_k_round", "_k_sin", "_k_cos", "_k_tan", "_k_exp", "_k_ftext",
              "_k_gtext", "_k_roundeven", "_k_pyformat_g"):
        if n == "_k_pyformat_g":
            continue
        calls("float", n, [(v,) for v in FLOATS + [0, 3, -3, 10 ** 400 if n == "_k_exp" else 5]])
    r = random.Random(11)
    randoms = [struct.unpack("<d", struct.pack("<Q", r.getrandbits(64)))[0] for _ in range(1500)]
    randoms += [r.uniform(-1, 1) * 10.0 ** r.randint(-30, 30) for _ in range(500)]
    randoms += [float(r.randint(-10 ** 18, 10 ** 18)) for _ in range(200)] + [round(r.uniform(-1000, 1000), r.randint(0, 8)) for _ in range(300)]
    for n in ("_k_ftext", "_k_gtext", "_k_round", "_k_floor", "_k_ceil"):
        calls("float", n, [(v,) for v in randoms])
    grid("float", "_k_fixed", FLOATS, [-3, 0, 1, 2, 9, 10, 12, 400])
    calls("float", "_k_fixed", [(v, 3) for v in randoms[:600]])
    grid("float", "_k_pow", SUB + [0.25, 4.0, 1e10], SUB + [0.5, 1e10, 7.0, -7.0, 2 ** 53 + 0.0])
    grid("float", "_k_fmod", SUB, SUB)
    grid("float", "_k_floormod", SUB + [5, 7], SUB + [0, 3])
    grid("float", "_k_fdiv", SUB + [0, 5], SUB + [0, 4])
    grid("float", "_k_min", SUB, SUB)
    grid("float", "_k_max", SUB, SUB)
    grid("float", "_k_min", [1, -3, 2 ** 63], [1, -3, 2 ** 63 + 1])
    grid("float", "_k_max", [1, -3, 2 ** 63], [1, -3, 2 ** 63 + 1])
    grid("float", "_k_roundto", FLOATS[:30] + [1e300, 123456.789], [-400, -3, -1, 0, 1, 2, 3, 15, 400])
    # A rounding that overflows is the infinity of the value's sign.
    calls("float", "_k_roundto", [(v, n) for v in (1.7e308, -1.7e308, 1.5e308, -1.5e308, 5e307, -5e307) for n in (-308, -307, -306)])
    grid("float", "_k_pyformat", FLOATS + [3, 10 ** 30], ["", ".2f", "e", "g", ".3e", "10.4", "x", ">10", "%", ",.2f", "+.1f", "nope"])


def texts():
    for s in STRS:
        n = len(s)
        cuts = sorted({-1, 0, 1, 2, n - 1, n, n + 1})
        grid("slice", "_k_substr", [s], cuts, cuts)
    grid("text", "_k_find", STRS, ["", "a", "\U0001F600", "\ud800", "b", "x", "é"])
    grid("text", "_k_split", STRS + [",a,,b,", "a--b--c", ",", "--"], ["", "a", ",", "--", "\U0001F600"])
    for s in STRS:
        add("text", "lower", lambda ctx, s=s: s.translate(ctx.rt["_k_lower"]), needs=("_k_lower",))
        add("text", "upper", lambda ctx, s=s: s.translate(ctx.rt["_k_upper"]), needs=("_k_upper",))
        add("text", "words", lambda ctx, s=s: ctx.rt["_k_words"].findall(s), needs=("_k_words",))
        add("text", "ascii-class", lambda ctx, s=s: [(ord(c) in ctx.rt["_k_spaces"], ord(c) in ctx.rt["_k_letters"]) for c in s],
            needs=("_k_spaces", "_k_letters"))
    calls("text", "_k_utf8", [(s,) for s in STRS])
    calls("text", "_k_strhash", [(s,) for s in STRS + ["x" * 5000, "\U0001F600" * 3000]])
    for b in BADUTF8:
        call("text", "_k_strof", b)
        call("text", "_k_strof", bytearray(b))
        call("text", "_k_strof", lazy_mv(b))
    texts_ints = ["0", "-0", "+0", "7", "-7", "+7", "", "+", "-", "--1", "+-1", " 1", "1 ", "1_0", "0x10", "1e3", "1.0", "٣", "１２",
                  "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "+9223372036854775807",
                  "0" * 30 + "5", "0" * 30, "1" * 19, "1" * 20, "9" * 19, "9" * 25, "-" + "0" * 25 + "1", "²", "1\n", "\n1", "\x001"]
    calls("text", "_k_toint", [(s,) for s in texts_ints])
    floatish = ["0", "-0", "+0", "0.0", "-0.0", "1", "1.", ".5", "-.5", "+.5", "+-1", "--1", "1e5", "1E5", "1e+5", "1e-5", "1e", "1e+", ".", "-", "+", "",
                " 1", "1 ", "1_0", "0x10", "inf", "-inf", "+inf", "INF", "Infinity", "-INFINITY", "infinit", "infx", "nan", "NaN", "-nan", "+NaN",
                "nan(abc)", "nan()", "NAN(a_B1)", "nan(a-b)", "nan(", "1e400", "-1e400", "1e-400", "-1e-400", "0e400", "0.0e-400", "4.9e-324",
                "2e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1.7976931348623157e308", "1.7976931348623159e308",
                "9" * 400, "0." + "0" * 400 + "1", "1" + "0" * 400, "٣", "1٣", "1,5", "1.5.5", "0e0", "00.00", "0" * 400, "1e0000000000005"]
    calls("text", "_k_tofloat", [(s,) for s in floatish])
    calls("text", "_k_pyfloat", [(s,) for s in floatish + ["  1.5  ", "1_000.5", "٣.5", "infinity", "\n1"]])
    calls("text", "_k_pyint", [(s,) for s in texts_ints + ["  12  ", "1_000", "-1_0", "1__0", "٣٤", "0b1", "9" * 30, "-" + "9" * 30]])
    calls("text", "_k_wrapwords", [(t, w) for t in ("", "a bb ccc dddd", "a-b-c d", "x" * 50 + " y", "tab\tsep  two\nnew", "\U0001F600 \U0001F600\U0001F600") for w in (-1, 0, 1, 2, 5, 80)])
    calls("text", "_k_utcstamp", [(n,) for n in (0, 1, -1, 999999, 1000000, -1000000, 86399999999999, 86400000000000, -86400000000000, 1700000000123456789,
                                              2 ** 40, -2 ** 40, 253402300799999999999, 253402300800000000000, -62135596800000000000,
                                              -62135596800000000001, 2 ** 62, -2 ** 62, 951782400000000000, 4107542400000000000)])
    calls("text", "_k_joinall", [(p,) for p in ([], ["a"], ["a", "b"], ["a", "/b"], ["", "a"], ["a", ""], ["a", "b", "c"], ("x", "y"), [1, 2], ["a\x00b"])])
    calls("text", "_k_relpath", [(p, s) for p, s in (("a/b", "a"), ("a", "a/b"), ("", "a"), ("a", ""), ("/", "/"), ("a/../b", "."), ("x", "y"), ("a\x00", "b"))])
    calls("text", "_k_jsontext", [(b,) for b in (b"{}", b"[1]", "[1]".encode("utf-16"), "[1]".encode("utf-16-le"), "[1]".encode("utf-16-be"),
                                           "[1]".encode("utf-32"), "[1]".encode("utf-32-le"), b"\xef\xbb\xbf[1]", b"", b"\x00", b"\xff\xfe", b"\x00\x00\x00",
                                           bytearray(b"[2]"), "\ud800".encode("utf-16-le", "surrogatepass"), b"\x00\x00\x00\x00")])
    calls("text", "_k_urlpart", [(t, p) for t in ("/a/b?x=1&y=2", "http://h/p?q", "?q", "", "/a;b?c#d", "//h/p", "http://[::1/x", "http://[bad/x", "/é?z=é", "x")
                                 for p in ("path", "query", "netloc", "fragment", "nope")])
    calls("text", "_k_queryfirst", [(q, k) for q in ("x=1&y=2", "x=1&x=2", "x=&y=2", "x", "", "x=%41%e9", "x=a+b", "&&x=1", "x=1;y=2") for k in ("x", "y", "z")])


def slices():
    seqs = [[], [1], [1, 2, 3, 4], list("abcde"), bytearray(b""), bytearray(b"abcde"), b"abcde", (1, 2, 3)]
    for v in seqs:
        n = len(v)
        pts = sorted({-1, 0, 1, 2, n - 1, n, n + 1, 2 ** 40})
        grid("slice", "_k_from", [v], pts)
        grid("slice", "_k_slice", [v], pts, pts)
    for data in (b"", b"abc", b"abcdef"):
        n = len(data)
        pts = sorted({-1, 0, 1, 2, n - 1, n, n + 1})
        for ro in (False, True):
            grid("slice", "_k_from", [lazy_mv(data, ro)], pts)
            grid("slice", "_k_slice", [lazy_mv(data, ro)], pts, pts)
        # a MutView writes through to the bytes it was cut from
        add("slice", "memoryview-writes", lambda ctx, d=data: (lambda b: (ctx.rt["_k_slice"](b, 0, len(b)).__setitem__(slice(0, 1), b"Z") if b else None, b))(bytearray(d)))
    grid("slice", "_k_span", [bytearray(b"12345678"), lazy_mv(b"12345678")], [-1, 0, 1, 7, 8, 9], [0, 1, 4, 8, 9, -1])
    # the first byte of a view written, then the original read: they share
    add("slice", "view-shares", lambda ctx: (lambda b: (ctx.rt["_k_from"](b, 1).__setitem__(0, 90), b))(bytearray(b"abc")))


def byteops():
    buf = bytearray(range(1, 17))
    for n in (0, 1, 2, 3, 4, 8, 9, 16, 17):
        for at in (-1, 0, 1, 8, 12, 15, 16, 17):
            call("bytes", "_k_rdle", buf, at, n)
            call("bytes", "_k_rdbe", buf, at, n)
            call("bytes", "_k_rdle", lazy_mv(bytes(buf)), at, n)
        for v in (0, 1, 255, 256, 2 ** 32, 2 ** 64 - 1, 2 ** 64, -1, 2 ** (8 * n) - 1 if n else 0, 2 ** (8 * n) if n else 1):
            call("bytes", "_k_wrle", bytearray(16), 4, n, v)
            call("bytes", "_k_wrbe", bytearray(16), 4, n, v)
            call("bytes", "_k_wrle", bytearray(16), 14, n, v)
            call("bytes", "_k_wrbe", lazy_mv(bytes(16)), 0, n, v)
    for at in (-1, 0, 4, 8, 9, 20):
        call("bytes", "_k_rdf64", buf, at)
        call("bytes", "_k_rdf64be", buf, at)
        call("bytes", "_k_rdf64", lazy_mv(bytes(buf)), at)
        for v in (0.0, -0.0, math.nan, math.inf, 5e-324, 1.5, 1e308, 7):
            call("bytes", "_k_wrf64", bytearray(16), at, v)
            call("bytes", "_k_wrf64be", bytearray(16), at, v)
    calls("bytes", "_k_wrf64", [(bytearray(16), 0, "x"), (bytearray(16), 0, 2 ** 1030)])
    calls("bytes", "_k_fromhex", [(t,) for t in ("", "00", "ff", "FF", "0f 1e", "0", "0g", "abc", "  ab  ", "a b", "٣٤", "ab\ncd", "0x10")])
    for data in (b"", b"abcabc", bytearray(b"abcabc")):
        for pat in (b"", b"a", b"c", b"ca", b"abcabc", b"abcabcd", b"z", bytearray(b"bc")):
            for start in (-1, 0, 1, 3, 6, 7, 100):
                call("bytes", "_k_findbytes", data, pat, start)
                call("bytes", "_k_findbytes", lazy_mv(bytes(data)), pat, start)


def radix():
    words = ["", "+", "-", "0", "-0", "+0", "1", "-1", "7", "z", "Z", "zz", "7fffffffffffffff", "8000000000000000", "-8000000000000000",
             "-8000000000000001", "FFFFFFFFFFFFFFFF", "1" * 63, "1" * 64, "1" * 65, "0" * 100 + "1", "0" * 70, "٣", " 1", "1_0",
             "+-1", "9223372036854775807", "9223372036854775808", "zzzzzzzzzzzz", "1y2p0ij32e8e7", "1y2p0ij32e8e8", "-1y2p0ij32e8e8",
             "-1y2p0ij32e8e9", "a", "A", "g", "G", "10", "\x00", "１"]
    grid("radix", "_k_tointr", words, list(range(2, 37)) + [0, 1, 37, -1, 64, 2 ** 40])


def collections_():
    nan = math.nan
    calls("collections", "_k_contains", [(xs, v) for xs in ([], [1, 2], [nan], [0.0], [-0.0], ["a"], [None], [True], [1.0]) for v in (1, nan, 0.0, -0.0, "a", None, 1.0, True)])
    calls("collections", "_k_copy", [(x,) for x in ([], [1, [2]], (1, 2), b"ab", bytearray(b"ab"), "ab", {1: None})] + [(lazy_mv(b"abc"),), (lazy_mv(b"abc", True),)])
    calls("collections", "_k_setadd", [(d, v) for d in ({}, {1: None}, {"a": None}) for v in (1, "a", 2, None, nan)])
    calls("collections", "_k_setdel", [(d, v) for d in ({}, {1: None}, {"a": None, 1: None}) for v in (1, "a", 2, None)])
    for f in ("_k_pop", "_k_peek"):
        calls("collections", f, [([],), ([1],), ([1, 2],), (["a", None],), ([0],), ([None],)])
    for f in ("_k_popleft", "_k_peekleft"):
        add("collections", f, lambda ctx, f=f: (lambda q: (ctx.rt[f](q), list(q)))(__import__("collections").deque()))
        add("collections", f, lambda ctx, f=f: (lambda q: (ctx.rt[f](q), list(q)))(__import__("collections").deque([1, 2])))
        add("collections", f, lambda ctx, f=f: (lambda q: (ctx.rt[f](q), list(q)))(__import__("collections").deque([0])))
    calls("collections", "_k_join", [(xs, sep) for xs in ([], ["a"], ["a", "b"], ["\ud800", "x"], ["a", 1]) for sep in ("", ",", "\U0001F600")])
    f64 = [[], [1.0], [3.0, 1.0, 2.0], [0.0, -0.0], [-0.0, 0.0], [nan, 1.0, 0.0], [1.0, nan], [nan], [nan, nan], [0.0, 1.0, -0.0, nan, -1.0],
           [math.inf, -math.inf, nan, 0.0], [-0.0], [0.0], [2.0, 1.0, -0.0, 0.0, -1.0], [5e-324, -5e-324, 0.0, -0.0]]
    ints_ = [[], [1], [3, 1, 2], [-5, 5, 0], [2 ** 63 - 1, -2 ** 63], [1, 1, 1]]
    for f in ("_k_sort", "_k_minof", "_k_maxof"):
        calls("collections", f, [(xs, "Float64") for xs in f64] + [(xs, "Int64") for xs in ints_] + [(xs, "Int32") for xs in ints_[:3]]
              + [(bytearray(b"cab"), "UInt8"), (bytearray(b""), "UInt8"), ([0.0, -0.0], "Int64"), ([nan], "Int64"), (["b", "a"], "Str"), ([], "Float64")])
    call("collections", "_k_map", L(lambda ctx: ctx.rt["_k_u8"]), [1, 256, 257])
    add("collections", "_k_map", lambda ctx: list(ctx.rt["_k_map"](ctx.rt["_k_u8"], [1, 256, 257, -1])))
    calls("collections", "_k_or", [(None, 1), (0, 1), (False, 1), ("", "d"), (nan, 1)])
    add("collections", "_k_mcopy", lambda ctx: (ctx.rt["_k_mcopy"](None, list), ctx.rt["_k_mcopy"]([1, 2], list), ctx.rt["_k_mcopy"]([], list)))
    calls("collections", "_k_value", [(None,), (0,), (False,), ("",), ([],)])
    calls("collections", "_k_unwrap", [((True, 5),), ((False, "e"),), ((True, None),)])
    calls("collections", "_k_unwrap_err", [((True, 5),), ((False, "e"),)])
    calls("collections", "_k_assert", [(True, "m"), (False, "m"), (0, "x"), (1, "x"), (None, "n"), (False, "")])
    calls("collections", "_k_panic", [("x",), ("",), ("é",)])
    add("collections", "_k_throw", lambda ctx: ctx.rt["_k_throw"]("boom"))
    add("collections", "_k_Error", lambda ctx: (lambda E: (E.__module__, E.__name__, str(E("x")), issubclass(E, Exception), ctx.rt["_k_errors"]().Error is E))(ctx.rt["_k_Error"]))
    add("collections", "_k_exit", lambda ctx: ctx.rt["_k_exit"](3))
    add("collections", "_k_exit", lambda ctx: ctx.rt["_k_exit"](None))
    add("collections", "_k_dqset", lambda ctx: (lambda q, v: (ctx.rt["_k_dqset"](q, v), list(q), list(v)))(__import__("collections").deque([1, 2, 3]), __import__("collections").deque([9])), needs=("_k_dqset",))
    add("collections", "_k_dqset", lambda ctx: (lambda q: (ctx.rt["_k_dqset"](q, q), list(q)))(__import__("collections").deque([1, 2])), needs=("_k_dqset",))
    add("collections", "_k_dqset", lambda ctx: (lambda q: (ctx.rt["_k_dqset"](q, [4, 5]), list(q)))(__import__("collections").deque()), needs=("_k_dqset",))
    add("collections", "_k_mapset", lambda ctx: (lambda m, v: (ctx.rt["_k_mapset"](m, v), m, v))({1: 2, 3: 4}, {5: 6}), needs=("_k_mapset",))
    add("collections", "_k_mapset", lambda ctx: (lambda m: (ctx.rt["_k_mapset"](m, m), m))({1: 2}), needs=("_k_mapset",))
    add("collections", "_k_mapset", lambda ctx: (lambda m: (ctx.rt["_k_mapset"](m, {}), m))({1: 2}), needs=("_k_mapset",))


def structs():
    shapes = [("_k_Datagram", (3, "h", 80), (4, "h", 80)), ("_k_Ready", (5, 1), (5, 2)), ("_k_Ran", (0, "o", "e"), (1, "o", "e")),
              ("_k_HttpReply", (200, bytearray(b"x")), (404, bytearray(b"x"))), ("_k_HttpRequest", ("GET", "/", "h", bytearray(b"b")), ("GET", "/", "h", bytearray())),
              ("_k_HttpAnswer", (200, "t", bytearray(b"b"), {"a": "1"}), (200, "t", bytearray(b"b"), {"a": "2"}))]
    for name, one, two in shapes:
        def run(ctx, name=name, one=one, two=two):
            C = ctx.rt[name]
            a, b, c = C(*copy.deepcopy(one)), C(*copy.deepcopy(two)), C(*copy.deepcopy(one))
            k = a._k_clone()
            k_independent = k is not a and k == a
            if hasattr(k, "body"):
                k.body.extend(b"!")
            return (a == c, a == b, k_independent, k == a, a, b, k, C.__slots__)

        add("misc", name, run)
        add("misc", name + "._k_set", lambda ctx, name=name, one=one, two=two: (lambda C: (lambda a, b: (a._k_set(b), a, b, a == b))(C(*copy.deepcopy(one)), C(*copy.deepcopy(two))))(ctx.rt[name]), needs=(name + "._k_set",))
        add("misc", name + ".eq-other", lambda ctx, name=name, one=one: ctx.rt[name](*copy.deepcopy(one)) == object())




def ranges():
    # kira:range's Range (both ends in it) and the two helpers of `x in r`: integer, empty and float ranges, a NaN.
    for lo, hi, v in ((1, 4, 1), (1, 4, 4), (1, 4, 0), (1, 4, 5), (5, 4, 5), (-2, 2, -2), (-2.5, 2.5, 2.5), (0.0, 1.0, math.nan)):
        add("misc", "_k_Range %r..%r %r" % (lo, hi, v), lambda ctx, lo=lo, hi=hi, v=v: (lambda r: (
            r.contains(v), r._op_in_(v), list(r._k_steps()) if isinstance(lo, int) else None))(ctx.rt["_k_Range"](lo, hi)))
    # A struct as the others are, with no _k_set: an immutable Range is never assigned into.
    add("misc", "_k_Range", lambda ctx: (lambda C: (lambda a, b, k: (a == C(1, 4), a == b, k is not a and k == a, a, b, C.__slots__))(
        C(1, 4), C(1, 5), C(1, 4)._k_clone()))(ctx.rt["_k_Range"]))
    add("misc", "_k_Range.eq-other", lambda ctx: ctx.rt["_k_Range"](1, 4) == object())
    # a..<b: the end left out, empty when begin is not below it, and never a step at 0.
    for lo, hi, v in ((1, 4, 1), (1, 4, 3), (1, 4, 4), (1, 4, 0), (4, 4, 4), (5, 4, 4), (0, 0, 0), (0.0, 1.0, 1.0), (0.0, 1.0, math.nan)):
        add("misc", "_k_Range %r..<%r %r" % (lo, hi, v), lambda ctx, lo=lo, hi=hi, v=v: (lambda r: (
            r.contains(v), r._op_in_(v), list(r._k_steps()) if isinstance(lo, int) else None, r.exclusive))(ctx.rt["_k_Range"](lo, hi, True)))
    add("misc", "_k_Range.exclusive", lambda ctx: (lambda C: (C(1, 4, True) == C(1, 4, True), C(1, 4, True) == C(1, 4), C(1, 4, True)._k_clone() == C(1, 4, True),
                                                           C(1, 4, True)._k_clone().exclusive, C(1, 4).exclusive))(ctx.rt["_k_Range"]))
    calls("misc", "_k_within", [(5, 1, 9), (0, 1, 9), (1, 1, 9), (9, 1, 9), (10, 1, 9), (1.5, 1.0, 1.5), (math.nan, 0.0, 1.0)])
    calls("misc", "_k_within_open", [(5, 1, 9), (0, 1, 9), (1, 1, 9), (8, 1, 9), (9, 1, 9), (1.5, 1.0, 1.5), (1.4, 1.0, 1.5), (math.nan, 0.0, 1.0)])
    add("misc", "_k_inop", lambda ctx: [ctx.rt["_k_inop"](v, ctx.rt["_k_Range"](1, 3)) for v in (0, 1, 3, 4)])


def json_cases(repo):
    path = os.path.join(repo, "kira", "cpp", "tests", "json_corpus.py")
    saved = sys.modules.pop("kira:errors", None)
    spec = importlib.util.spec_from_file_location("json_corpus", path)
    jc = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(jc)
    sys.modules.pop("kira:errors", None)
    if saved is not None:
        sys.modules["kira:errors"] = saved
    r = random.Random(20261025)
    values = []
    for _ in range(400):
        v, _enc = jc.value(r, 0, r.choice([0, 1, 2, 3, 4, 6]))
        values.append(v)
    for v in values:
        call("json", "_k_jdump", v)
        call("json", "_k_jpretty", v, 2, False)
        call("json", "_k_jpretty", v, 0, True)
        call("json", "_k_jpretty", v, "\t", False)
        call("json", "_k_jcheck", v)
        call("json", "_k_jfault", v)
        call("json", "_k_jsound", v, True)
    docs = list(jc.DOC_EDGES) + list(jc.NUMBER_EDGES) + ["[" + t + "]" for t in jc.NUMBER_EDGES] + list(jc.STRING_EDGES)
    docs += ['{"a":' + t + "}" for t in jc.STRING_EDGES] + ["[" + t + "]" for t in jc.STRING_EDGES] + jc.deep_docs()
    docs += [jc.number_text(r) for _ in range(400)]
    docs += [jc.rand_doc(r, values) for _ in range(500)]
    for _ in range(1200):
        text = jc.rand_doc(r, values) if r.random() < 0.7 else r.choice(docs)
        for _ in range(r.choice([1, 1, 2, 3])):
            text = jc.mutate(r, text)
        docs.append(text)
    for text in docs:
        add("json", "_k_jparse", lambda ctx, t=text: (ctx.rt["_k_jparse"](t), ctx.rt["_k_jerror"]()), needs=("_k_jparse", "_k_jerror"))
    for text in docs[:300]:
        call("json", "_k_jstrict", text)
        call("json", "_k_jbig", text)
        call("json", "_k_jdeep", text, len(text))
    # A document over 512 opens that never goes 512 deep: the closes must lower the depth, or the 513th open is "too deep".
    for text in ("[" * 300 + "]" * 300 + "[" * 300, "[" * 512 + "]" + "[", "[" * 512 + "]" + "[" + "]" * 512, "{" * 256 + "[" * 256 + "]" * 256 + "}" * 256 + "[" * 300,
                 "[" * 513, "[" * 512 + "[", "[{" * 300 + "}]" * 300 + "[" * 100, "[]" * 600, "}" * 600 + "[" * 513):
        call("json", "_k_jdeep", text, len(text))
        call("json", "_k_jdeep", text, len(text) // 2)
    call("json", "_k_jint64", "9223372036854775807")
    calls("json", "_k_jint64", [(s,) for s in ("9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "0", "1" * 25, "-0")])
    # depth: lists and objects 1 .. 1500 deep, built here so no copy walks them
    for n in (1, 2, 100, 510, 511, 512, 513, 514, 515, 600, 1000, 1200):
        for opener in ("list", "dict"):
            for op in ("_k_jdump", "_k_jcheck", "_k_jfault", "_k_jsound_true", "_k_jsound_false", "_k_jpretty", "_k_jtext"):
                def run(ctx, n=n, opener=opener, op=op):
                    v = 0
                    for _ in range(n):
                        v = [v] if opener == "list" else {"k": v}
                    R = ctx.rt
                    if op == "_k_jsound_true":
                        return R["_k_jsound"](v, True)
                    if op == "_k_jsound_false":
                        return R["_k_jsound"](v, False)
                    if op == "_k_jpretty":
                        return R["_k_jpretty"](v, 1, False)
                    if op == "_k_jtext":
                        return R["_k_jtext"](v, None, True)
                    return R[op](v)

                add("json", "%s depth %s %d" % (op, opener, n), run, needs=("_k_" + op.split("_k_")[1].split("_")[0],))
    def cyc(ctx, shape):
        R = ctx.rt
        if shape == "self-list":
            a = []
            a.append(a)
        elif shape == "self-dict":
            a = {}
            a["k"] = a
        elif shape == "mutual":
            a, b = [], {}
            a.append(b)
            b["x"] = a
        elif shape == "deep-cycle":
            a = []
            cur = a
            for _ in range(300):
                nxt = []
                cur.append(nxt)
                cur = nxt
            cur.append(a)
        elif shape == "shared":
            x = [1]
            a = [x, x, [x, x]]
        else:
            x = [0]
            for _ in range(520):
                x = [x, x]
            a = x
        out = []
        # a doubling DAG is 2^520 paths to dump or walk; only the memoised check may meet it
        for op in (("_k_jcheck",) if shape == "shared-deep" else ("_k_jdump", "_k_jfault", "_k_jcheck")):
            try:
                out.append(R[op](a))
            except BaseException as e:
                out.append((type(e).__qualname__, str(e)))
        out.append(R["_k_jsound"](a, False) if shape == "shared" else "skipped")
        return out

    for shape in ("self-list", "self-dict", "mutual", "deep-cycle", "shared", "shared-deep"):
        add("json", "cycle " + shape, lambda ctx, s=shape: cyc(ctx, s), needs=("_k_jdump", "_k_jfault", "_k_jcheck"))
    odd = [True, 1, 1.5, "s", None, [], {}, [1], {"a": 1}, 2 ** 63, -2 ** 63 - 1, math.nan, math.inf, b"x", (1,), {1: 2}, {"a": {1: 2}}, [object], {"a": 2 ** 70}, 1 + 0, 0, False, ""]
    for v in odd:
        for n in ("_k_jasbool", "_k_jasint", "_k_jasfloat", "_k_jasstr", "_k_jkeys", "_k_jsize", "_k_jmaybef", "_k_jcheck", "_k_jone", "_k_jdump", "_k_jfault"):
            call("json", n, v)
        for k in ("a", "", 0, "b"):
            call("json", "_k_jget", v, k)
            call("json", "_k_jhas", v, k)
        for i in (0, 1, -1, 5):
            call("json", "_k_jat", v, i)
        call("json", "_k_jput", v, "n", 1)
        call("json", "_k_jadd", v, 1)
    call("json", "_k_jmaybef", None)
    call("json", "_k_jmaybef", 2 ** 1030)


def hosts():
    for name, make in rt_fakes.HOST_ERRORS:
        for fn in ("_k_oscategory", "_k_oskind"):
            add("host", "%s %s" % (fn, name), lambda ctx, fn=fn, make=make: ctx.rt[fn](make()), needs=(fn,))
        add("host", "_k_osfail " + name, lambda ctx, make=make: (ctx.rt["_k_osfail"](make()), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()), needs=("_k_osfail", "_k_oserror"))
        for f in ("_k_osget", "_k_osdo"):
            def run(ctx, make=make, f=f):
                def work(*a):
                    raise make()

                return (ctx.rt[f](-7, work, 1) if f == "_k_osget" else ctx.rt[f](work, 1)), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()

            add("host", "%s %s" % (f, name), run, needs=(f,))
        add("host", "_k_pytextfail " + name, lambda ctx, make=make: (ctx.rt["_k_pytextfail"](make()), ctx.rt["_k_pytexterror"]()), needs=("_k_pytextfail",))
    add("host", "_k_osget ok", lambda ctx: (ctx.rt["_k_osget"](-7, lambda a, b: a + b, 1, 2), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()))
    add("host", "_k_osdo ok", lambda ctx: (ctx.rt["_k_osdo"](lambda: None), ctx.rt["_k_oserror"]()))
    add("host", "_k_osget other", lambda ctx: ctx.rt["_k_osget"](0, lambda: 1 / 0))
    add("host", "_k_osfailwith", lambda ctx: (ctx.rt["_k_osfailwith"]("what", "why"), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()))
    add("host", "_k_osok", lambda ctx: (ctx.rt["_k_osfailwith"]("a", "b"), ctx.rt["_k_osok"](), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()))
    for f in ("exists", "isfile", "isdir"):
        add("host", "_k_ostest " + f, lambda ctx, f=f: (ctx.rt["_k_ostest"](getattr(os.path, f), ctx.tmp), ctx.rt["_k_ostest"](getattr(os.path, f), os.path.join(ctx.tmp, "nope")), ctx.rt["_k_oserror"]()), fs=True, needs=("_k_ostest",))
    for code in rt_fakes.ERRNOS:
        e = lambda code=code: OSError(code, os.strerror(code))
        ov = lambda code=code: {"open": boom(code)}
        for fn, args in (("_k_readfile", ("p", -1)), ("_k_readfile", ("p", 5)), ("_k_readtext", ("p", "")), ("_k_readtext", ("p", None)),
                         ("_k_writefile", ("p", b"x")), ("_k_openout", ("p", "wb")), ("_k_opencsv", ("p",))):
            def run(ctx, fn=fn, args=args):
                a = [os.path.join(ctx.tmp, args[0])] + list(args[1:])
                return ctx.rt[fn](*a), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"](), getattr(ctx.rt["_k_reasons"], "csv", "")

            add("host", "%s open fails errno %d" % (fn, code), run, fakes={"_k_builtins": ov()}, fs=True, needs=(fn,))
        # a writeatomic whose fsync, replace or remove fails: the error, the reason and no temporary left behind
        for stage in ("fsync", "replace", "remove", "open"):
            def run(ctx, code=code, stage=stage):
                return ctx.rt["_k_writeatomic"](os.path.join(ctx.tmp, "t.bin"), b"data"), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()

            over = {stage: boom(code)} if stage != "remove" else {"replace": boom(code), "remove": boom(code)}
            target = "_k_builtins" if stage == "open" else "_k_os"
            add("host", "_k_writeatomic %s errno %d" % (stage, code), run, fakes={target: over}, fs=True, needs=("_k_writeatomic",))
    add("host", "_k_syncdir open fails", lambda ctx: ctx.rt["_k_syncdir"](ctx.tmp), fakes={"_k_os": {"open": boom(errno.EACCES), "name": "posix"}}, fs=True)
    add("host", "_k_syncdir other error", lambda ctx: ctx.rt["_k_syncdir"](ctx.tmp), fakes={"_k_os": {"open": boom_exc(lambda: ValueError("x")), "name": "posix"}}, fs=True)
    add("host", "_k_syncdir fsync fails", lambda ctx: ctx.rt["_k_syncdir"](ctx.tmp), fakes={"_k_os": {"fsync": boom(errno.EIO), "name": "posix"}}, fs=True)
    add("host", "_k_syncdir nt", lambda ctx: ctx.rt["_k_syncdir"](ctx.tmp), fakes={"_k_os": {"name": "nt", "open": boom(errno.EIO)}}, fs=True)
    # replace: retried on Windows while the target is held, at once elsewhere
    for winerror, osname, fails in ((5, "nt", 3), (32, "nt", 3), (5, "nt", 99), (32, "nt", 20), (32, "nt", 21), (6, "nt", 1), (None, "nt", 1), (5, "posix", 1)):
        def fakes(ctx, winerror=winerror, osname=osname, fails=fails):
            state = {"n": 0}
            log = ctx.__dict__.setdefault("log", [])

            def replace(a, b):
                state["n"] += 1
                log.append(("replace", state["n"]))
                if state["n"] <= fails:
                    e = PermissionError(errno.EACCES, "held", a)
                    if winerror is not None:
                        e.winerror = winerror
                    raise e

            ctx.clock = rt_fakes.Clock()
            return {"replace": replace, "name": osname}

        def run(ctx, winerror=winerror, fails=fails):
            try:
                r = ctx.rt["_k_replace"]("a", "b")
            except BaseException as e:
                r = (type(e).__qualname__, str(e))
            return r, ctx.log, ctx.clock.asked

        add("host", "_k_replace winerror %s fails %d" % (winerror, fails), run,
            fakes={"_k_os": fakes, "_k_time": lambda ctx: {"sleep": ctx.clock.sleep}}, needs=("_k_replace",))
    for fn, attr in (("_k_affinity", "sched_setaffinity"), ("_k_nice", "nice")):
        args = (1, {0}) if fn == "_k_affinity" else (3,)
        add("host", fn + " missing", lambda ctx, fn=fn, args=args: ctx.rt[fn](*args), fakes={"_k_os": {attr: MISSING}})
        add("host", fn + " present", lambda ctx, fn=fn, args=args: ctx.rt[fn](*args), fakes={"_k_os": {attr: lambda *a: ("called", a)}})
        add("host", fn + " fails", lambda ctx, fn=fn, args=args: ctx.rt[fn](*args), fakes={"_k_os": {attr: boom(errno.EPERM)}})
    add("host", "_k_freebytes", lambda ctx: ctx.rt["_k_freebytes"]("x"), fakes={"_k_shutil": {"disk_usage": lambda p: type("U", (), {"free": 12345})()}})
    add("host", "_k_freebytes fails", lambda ctx: ctx.rt["_k_freebytes"]("x"), fakes={"_k_shutil": {"disk_usage": boom(errno.ENOENT)}})
    for ms in (0, -5, 1, 1500, 86400000, 86400001, 2 * 86400000 + 5, 10 ** 12):
        def run(ctx, ms=ms):
            ctx.rt["_k_sleep"](ms)
            return ctx.clock.asked

        add("host", "_k_sleep %d" % ms, run, fakes={"_k_time": lambda ctx: {"sleep": (setattr(ctx, "clock", rt_fakes.Clock()) or ctx.clock.sleep)}}, needs=("_k_sleep",))
    # the end of the process, as a fake records it
    for code, shown in ((0, False), (3, False), (70, True), (None, True), (None, False), (255, False)):
        def run(ctx, code=code, shown=shown):
            try:
                ctx.rt["_k_end"](code, shown)
            except rt_fakes.Ended as e:
                return ("ended", e.what, e.code, ctx.rt["_k_os"].name)

        add("host", "_k_end %s %s" % (code, shown), run, fakes={"_k_os": lambda ctx: dict(rt_fakes.fake_end(), name="posix")}, needs=("_k_end",))
        add("host", "_k_end nt %s %s" % (code, shown), run, fakes={"_k_os": lambda ctx: dict(rt_fakes.fake_end(), name="nt")}, needs=("_k_end",))

    def raiser(kind):
        def f(*a):
            if kind == "throw":
                raise ctx_rt["x"]["_k_Error"]("thrown")
            if kind == "panic":
                raise RuntimeError("kira: p")
            if kind == "exit":
                raise SystemExit(5)
            if kind == "exit0":
                raise SystemExit(None)
            if kind == "exitstr":
                raise SystemExit("bye")
            if kind == "interrupt":
                raise KeyboardInterrupt()
            if kind == "other":
                raise ValueError("v")
            return ("ok", a)

        return f

    ctx_rt = {}
    for kind in ("throw", "panic", "exit", "exit0", "exitstr", "interrupt", "other", "fine"):
        for entry in ("_k_main", "_k_callback", "_k_kirafx", "_k_callback-owner", "_k_callback-thrown"):
            def run(ctx, kind=kind, entry=entry):
                ctx_rt["x"] = ctx.rt
                f = raiser(kind)
                try:
                    if entry == "_k_main":
                        return ctx.rt["_k_main"](f, 1, 2)
                    if entry == "_k_callback":
                        return ctx.rt["_k_callback"](f, (1,), None)
                    if entry == "_k_kirafx":
                        return ctx.rt["_k_kirafx"](f, 3)
                    if entry == "_k_callback-owner":
                        return ctx.rt["_k_callback"](f, (), threading.get_ident())
                    return ctx.rt["_k_callback"](f, (), None, None)
                except rt_fakes.Ended as e:
                    return ("ended", e.what, e.code)

            add("host", "%s %s" % (entry, kind), run, fakes={"_k_os": lambda ctx: dict(rt_fakes.fake_end(), name="posix")}, needs=(entry.split("-")[0],))


def files():
    ov_nt = {"_k_os": {"name": "posix"}}

    def put(ctx, name, data):
        p = os.path.join(ctx.tmp, name)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
        return p

    big = bytes(i % 251 for i in range(65536 * 2 + 17))
    for n in (-1, 0, 1, 5, 65535, 65536, 65537, 70000, 200000, 131089, 131090):
        add("os", "_k_readfile %d" % n, lambda ctx, n=n: (ctx.rt["_k_readfile"](put(ctx, "big", big), n), ctx.rt["_k_oserror"]()), fs=True, needs=("_k_readfile",))
    add("os", "_k_readfile missing", lambda ctx: (ctx.rt["_k_readfile"](os.path.join(ctx.tmp, "none"), -1), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()), fs=True, needs=("_k_readfile",))
    add("os", "_k_readfile dir", lambda ctx: (ctx.rt["_k_readfile"](ctx.tmp, -1), ctx.rt["_k_oserrkind"]()), fs=True, needs=("_k_readfile",))
    add("os", "_k_readfile nul", lambda ctx: (ctx.rt["_k_readfile"]("a\x00b", -1), ctx.rt["_k_oserror"]()), fs=True, needs=("_k_readfile",))
    for text in (b"", b"a\r\nb\rc\nd", b"\xef\xbb\xbfbom", b"\xffbad", "héllo \U0001F600".encode(), b"\xed\xa0\x80", b"line\n"):
        for nl in ("", None, "\n", "\r\n"):
            add("os", "_k_readtext %r %r" % (text[:8], nl), lambda ctx, text=text, nl=nl: (ctx.rt["_k_readtext"](put(ctx, "t", text), nl), ctx.rt["_k_oserror"]()), fs=True, needs=("_k_readtext",))
    for data in (b"", b"abc", bytearray(b"xyz"), lazy_mv(b"mv"), "str", None, 5):
        add("os", "_k_writefile %r" % type(data).__name__, lambda ctx, data=data: (ctx.rt["_k_writefile"](os.path.join(ctx.tmp, "w"), fresh(ctx, data)), ctx.rt["_k_oserror"]()), fs=True, needs=("_k_writefile",))
    add("os", "_k_writefile missing dir", lambda ctx: (ctx.rt["_k_writefile"](os.path.join(ctx.tmp, "no", "w"), b"x"), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()), fs=True)
    add("os", "_k_writefile onto dir", lambda ctx: (ctx.rt["_k_writefile"](ctx.tmp, b"x"), ctx.rt["_k_oserrkind"]()), fs=True)
    add("os", "_k_writeatomic ok", lambda ctx: [ctx.rt["_k_writeatomic"](os.path.join(ctx.tmp, "a.bin"), b"one"), ctx.rt["_k_writeatomic"](os.path.join(ctx.tmp, "a.bin"), bytearray(b"two")),
                                               ctx.rt["_k_oserror"]()], fs=True)
    add("os", "_k_writeatomic missing dir", lambda ctx: (ctx.rt["_k_writeatomic"](os.path.join(ctx.tmp, "x", "a"), b"1"), ctx.rt["_k_oserror"]()), fs=True)
    add("os", "_k_writeatomic bad data", lambda ctx: (ctx.rt["_k_writeatomic"](os.path.join(ctx.tmp, "a"), "str"), ctx.rt["_k_oserror"]()), fs=True)
    def lists(ctx):
        for n in ("b", "a", "c/d", "c/e", "é"):
            put(ctx, n, b"1")
        R = ctx.rt
        rel = lambda xs: [os.path.relpath(x, ctx.tmp) for x in xs]
        return (R["_k_listdir"](ctx.tmp), rel(R["_k_globbed"](os.path.join(ctx.tmp, "*"))), rel(R["_k_walkfiles"](ctx.tmp)), R["_k_makedirs"](os.path.join(ctx.tmp, "m", "n")),
                R["_k_makedirs"](os.path.join(ctx.tmp, "m", "n")), R["_k_touch"](os.path.join(ctx.tmp, "a"), 1000000), os.stat(os.path.join(ctx.tmp, "a")).st_mtime)

    add("os", "listing", lists, fs=True, needs=("_k_listdir", "_k_globbed", "_k_walkfiles", "_k_makedirs", "_k_touch"))
    add("os", "_k_listdir missing", lambda ctx: ctx.rt["_k_listdir"](os.path.join(ctx.tmp, "no")), fs=True)
    add("os", "_k_makedirs over file", lambda ctx: (put(ctx, "f", b"x"), ctx.rt["_k_makedirs"](os.path.join(ctx.tmp, "f")))[1], fs=True)
    add("os", "_k_touch missing", lambda ctx: ctx.rt["_k_touch"](os.path.join(ctx.tmp, "no"), 5), fs=True)
    add("os", "_k_moddir", lambda ctx: ctx.rt["_k_moddir"]() == os.path.dirname(os.path.realpath(ctx.rt["__file__"])), fs=True)
    add("os", "_k_moddir nofile", lambda ctx: (ctx.rt.pop("__file__"), ctx.rt["_k_moddir"]() == os.path.realpath("."), ctx.rt.__setitem__("__file__", os.path.join(ctx.tmp, "mod", "rt.py")))[1], fs=True)
    add("os", "_k_key", lambda ctx: [ctx.rt["_k_key"](p) == os.path.normcase(os.path.realpath(p)) for p in (ctx.tmp, ".", "a/../b", os.path.join(ctx.tmp, "x"))], fs=True)

    # FileOut
    def fileout(ctx, steps):
        R = ctx.rt
        out = R["_k_openout"](os.path.join(ctx.tmp, "o.bin"), "wb")
        log = []
        for name, args in steps:
            a = [fresh(ctx, x) for x in args]
            try:
                log.append((name, getattr(out, name)(*a), R["_k_oserror"]()))
            except BaseException as e:
                log.append((name, type(e).__qualname__, str(e)))
        return log, out.isStale() if hasattr(out, "isStale") else None

    plans = [[("write", (b"abc",)), ("write", (bytearray(b"def"),)), ("size", ()), ("sync", ()), ("isStale", ()), ("close", ()), ("write", (b"x",)), ("size", ()), ("sync", ()), ("isStale", ())],
             [("write", (lazy_mv(b"mvmv"),)), ("write", ("text",)), ("write", (None,)), ("write", (b"",)), ("close", ()), ("close", ())],
             [("write", (b"x" * 200000,)), ("size", ())]]
    for i, plan in enumerate(plans):
        add("os", "FileOut plan %d" % i, lambda ctx, plan=plan: fileout(ctx, plan), fs=True, needs=("_k_FileOut", "_k_openout"))
    add("os", "FileOut stale", lambda ctx: (lambda o: (os.remove(os.path.join(ctx.tmp, "o.bin")), o.isStale(), o.close())[1:])(ctx.rt["_k_openout"](os.path.join(ctx.tmp, "o.bin"), "wb")), fs=True)
    def stale(ctx):
        R = ctx.rt
        path = os.path.join(ctx.tmp, "s.bin")
        f = open(path, "wb")
        here, gone = R["_k_FileOut"](f, path), R["_k_FileOut"](f, os.path.join(ctx.tmp, "gone.bin"))
        log = [here.isStale(), gone.isStale()]
        f.close()
        return log + [here.isStale(), gone.isStale(), R["_k_FileOut"](None, path).isStale()]

    # A path that is not there, with the file still open: a host that will not remove an open file (Windows) shows it too.
    add("os", "FileOut stale by path", stale, fs=True, needs=("_k_FileOut",))
    add("os", "_k_openout bad mode", lambda ctx: (ctx.rt["_k_openout"](os.path.join(ctx.tmp, "o"), "zz"), ctx.rt["_k_oserror"]()), fs=True)
    add("os", "_k_openout append", lambda ctx: (put(ctx, "o", b"12"), (lambda f: (f.write(b"34"), f.close()))(ctx.rt["_k_openout"](os.path.join(ctx.tmp, "o"), "ab")))[1], fs=True)
    add("os", "FileOut default", lambda ctx: (lambda f: (f.write(b"x"), f.size(), f.sync(), f.isStale(), f.close()))(ctx.rt["_k_FileOut"]()))

    class FakeFile:
        def __init__(self, fail):
            self.closed = False
            self.fail = fail

        def write(self, d):
            if self.fail == "write":
                raise OSError(errno.ENOSPC, "full")
            return 1 if self.fail == "slow" else len(d)

        def fileno(self):
            if self.fail == "fileno":
                raise OSError(errno.EBADF, "bad")
            return 99

        def close(self):
            if self.fail == "close":
                raise OSError(errno.EIO, "closeio")
            self.closed = True

    for fail in ("write", "slow", "fileno", "close", "none"):
        add("os", "FileOut fake " + fail, lambda ctx, fail=fail: (lambda f: [f.write(b"abc"), f.sync(), f.size(), f.close(), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()])(ctx.rt["_k_FileOut"](FakeFile(fail), "p")),
            fakes={"_k_os": {"fsync": lambda fd: None, "fstat": lambda fd: type("S", (), {"st_size": 4})()}})
    add("os", "FileOut sync fails", lambda ctx: (lambda f: [f.sync(), f.size(), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()])(ctx.rt["_k_FileOut"](FakeFile("none"), "p")),
        fakes={"_k_os": {"fsync": boom(errno.EIO), "fstat": boom(errno.EBADF)}})

    # runCapture, spawn and the Process classes on a real child (the interpreter itself, so any host has it)
    py = sys.executable
    scripts = ["print('hi')", "import sys; sys.stdout.write('a\\r\\nb\\rc'); sys.stderr.write('e\\xe9')", "import sys; sys.exit(7)", "import sys; sys.stdout.buffer.write(b'\\xff\\xfe')",
               "import sys; print(sys.stdin.read().upper())", "import os; os._exit(300)", "raise SystemExit(-1)", "import time; time.sleep(5)"]
    for i, code in enumerate(scripts):
        for given in (None, "inéput", ""):
            ms = 100 if i == len(scripts) - 1 else 0
            if i == len(scripts) - 1 and given is not None:
                continue
            add("os", "_k_runcapture %d %r" % (i, given), lambda ctx, code=code, given=given, ms=ms: (ctx.rt["_k_runcapture"]([py, "-c", code], given, ms), ctx.rt["_k_oserror"](), ctx.rt["_k_oserrkind"]()),
                needs=("_k_runcapture",))
    add("os", "_k_runcapture empty", lambda ctx: (ctx.rt["_k_runcapture"]([], None, 0), ctx.rt["_k_oserror"]()))
    add("os", "_k_runcapture missing exe", lambda ctx: (ctx.rt["_k_runcapture"]([os.path.join(ctx.tmp, "no-such-exe")], None, 0), ctx.rt["_k_oserrkind"]()), fs=True)
    add("os", "_k_runcapture surrogate input", lambda ctx: ctx.rt["_k_runcapture"]([py, "-c", "pass"], "\ud800", 0))
    add("os", "_k_runinherit empty", lambda ctx: (ctx.rt["_k_runinherit"]([]), ctx.rt["_k_oserror"]()))
    add("os", "_k_runinherit missing", lambda ctx: (ctx.rt["_k_runinherit"]([os.path.join(ctx.tmp, "no-such-exe")]), ctx.rt["_k_oserrkind"]()), fs=True)
    for rc in (0, 1, 255, 256, -1, -9, -15, 2 ** 31, 2 ** 32 - 1, -2 ** 31):
        call("os", "_k_code", rc)
    add("os", "_k_lftext", lambda ctx: [ctx.rt["_k_lftext"](b) for b in (b"a\r\nb\rc\n", b"\xff", b"", b"\r\r\n\n", "é".encode())])
    add("os", "_k_flushed", lambda ctx: ctx.rt["_k_flushed"]())
    add("os", "_k_flushed bad streams", lambda ctx: ctx.rt["_k_flushed"](), fakes={"_k_sys": {"stdout": None, "stderr": object()}})

    def spawn_plain(ctx):
        R = ctx.rt
        p = R["_k_spawn"]([py, "-c", "import sys; sys.stdout.write('line1\\nline2\\n')"], False)
        buf = bytearray(64)
        got = bytearray()
        for _ in range(10):
            n = p.readStdout(buf)
            if n <= 0:
                break
            got += buf[:n]
        code = p.wait()
        return type(p).__qualname__, bytes(got), code, p.isRunning(), p.pid() > 0, p.kill(), p.readStdout(buf)

    add("os", "spawn Process", spawn_plain, needs=("_k_spawn", "_k_Process"))

    def spawn_piped(ctx):
        R = ctx.rt
        p = R["_k_spawn"]([py, "-c", "import sys; d=sys.stdin.read(); sys.stderr.write('e1\\ne2 '+d+'\\n'); sys.stdout.write(d.upper())"], True)
        w = p.writeStdin(b"abc")
        p.closeStdin()
        lines = [p.readStderrLine(), p.readStderrLine(), p.readStderrLine()]
        buf = bytearray(16)
        n = p.readStdout(buf)
        return type(p).__qualname__, w, lines, bytes(buf[:max(n, 0)]), p.wait(), p.writeStdin(b"x"), R["_k_oserror"]()

    add("os", "spawn PipedProcess", spawn_piped, needs=("_k_spawn", "_k_PipedProcess"))
    add("os", "spawn bad", lambda ctx: (ctx.rt["_k_spawn"]([], False), ctx.rt["_k_oserror"](), ctx.rt["_k_spawn"]([], True), ctx.rt["_k_oserror"](), ctx.rt["_k_spawn"](["\x00"], False), ctx.rt["_k_oserrkind"]()))
    add("os", "Process default", lambda ctx: (lambda p: [p.readStdout(bytearray(4)), ctx.rt["_k_oserror"](), p.wait(), ctx.rt["_k_oserror"](), p.isRunning(), p.stdoutHandle(), p.pid(), p.kill()])(ctx.rt["_k_Process"]()))
    add("os", "PipedProcess default", lambda ctx: (lambda p: [p.writeStdin(b"x"), ctx.rt["_k_oserror"](), p.closeStdin(), p.readStderrLine(), ctx.rt["_k_oserror"]()])(ctx.rt["_k_PipedProcess"]()))

    def killed(ctx):
        p = ctx.rt["_k_spawn"]([py, "-c", "import time; time.sleep(30)"], False)
        r = p.isRunning()
        p.kill()
        return r, p.wait(), p.isRunning()

    add("os", "Process kill", killed)

    # sockets on the loopback; ports differ per run so only whether one was given is kept
    def udp(ctx):
        R = ctx.rt
        a, b = R["_k_UdpSocket"](), R["_k_UdpSocket"]()
        log = [a.recvFrom(bytearray(4)), a.localPort(), R["_k_oserror"](), a.sendTo(b"x", "127.0.0.1", 1), R["_k_oserror"](), a.bind("999.1.1.1", 0), R["_k_oserror"](), a.bind("127.0.0.1", 70000), R["_k_oserror"]()]
        log += [a.bind("127.0.0.1", 0), b.bind("127.0.0.1", 0)]
        pa, pb = a.localPort(), b.localPort()
        log += [pa > 0 and pb > 0, a.sendTo(b"hello", "127.0.0.1", pb)]
        into, got = bytearray(3), None
        for _ in range(200):
            got = b.recvFrom(into)
            if got is not None:
                break
            time.sleep(0.005)
        log += [got.size if got else None, got.host if got else None, got.port == pa if got else None, bytes(into), a.handle() >= 0]
        a.close()
        b.close()
        log += [a.handle(), a.close()]
        return log

    add("os", "UdpSocket loopback", udp, needs=("_k_UdpSocket", "_k_Datagram"))

    def udp_idle(ctx):
        # Nothing has been sent: the drive link's usual path is a recvFrom that finds nothing and says so.
        u = ctx.rt["_k_UdpSocket"]()
        log = [u.bind("127.0.0.1", 0)]
        into = bytearray(8)
        log += [u.recvFrom(into), u.recvFrom(into), bytes(into), ctx.rt["_k_oserror"]()]
        u.close()
        return log

    add("os", "UdpSocket idle recvFrom", udp_idle, needs=("_k_UdpSocket",))
    for host, port in (("", 0), ("127.0.0.1", 0), ("1.2.3.4", 5), ("localhost", 5), ("::1", 5), ("256.1.1.1", 5), ("127.0.0.1", -1), ("127.0.0.1", 65535), ("127.0.0.1", 65536), ("1.2.3", 1)):
        add("os", "_k_addr %r %d" % (host, port), lambda ctx, host=host, port=port: (ctx.rt["_k_addr"](host, port), ctx.rt["_k_oserror"]()))

    def tcp(ctx):
        R = ctx.rt
        l, c = R["_k_TcpListener"](), R["_k_TcpStream"]()
        log = [l.localPort(), R["_k_oserror"](), l.accept(), R["_k_oserror"](), l.listen(-1), R["_k_oserror"](), l.listen(70000)]
        ok = l.listen(0)
        port = l.localPort()
        log += [ok, port > 0, l.accept()]
        log += [c.isOpen(), c.read(bytearray(4)), R["_k_oserror"](), c.write(b"x"), R["_k_oserror"](), c.connect("127.0.0.1", port)]
        server = None
        for _ in range(200):
            server = l.accept()
            if server is not None:
                break
            time.sleep(0.005)
        log += [server is not None, c.isOpen(), c.write(b"ping"), type(server).__qualname__]
        if server is not None:
            buf = bytearray(8)
            n = server.read(buf)
            log += [n, bytes(buf[:max(n, 0)]), server.write(b"pong")]
            r = bytearray(8)
            m = c.read(r)
            log += [m, bytes(r[:max(m, 0)])]
            server.close()
            log += [c.read(bytearray(8)), c.isOpen()]
        c.close()
        l.close()
        log += [c.isOpen(), l.localPort(), c.handle(), l.handle()]
        return log

    add("os", "TcpListener TcpStream loopback", tcp, needs=("_k_TcpListener", "_k_TcpStream"))
    add("os", "TcpStream refused", lambda ctx: (lambda c: (c.connect("127.0.0.1", 1), ctx.rt["_k_oserrkind"](), c.connect("nope", 1), ctx.rt["_k_oserror"]()))(ctx.rt["_k_TcpStream"]()))
    add("os", "Poller", lambda ctx: poller(ctx), needs=("_k_Poller", "_k_Ready"))
    add("os", "Serial closed", lambda ctx: (lambda s: [s.isOpen(), s.read(bytearray(4), 0), ctx.rt["_k_oserror"](), s.write(b"x"), ctx.rt["_k_oserror"](), s.handle(), s.close(), s.read(bytearray(0), 5)])(ctx.rt["_k_Serial"]()))
    add("os", "Serial open fails", lambda ctx: (lambda s: [s.open(os.path.join(ctx.tmp, "no-such-port"), 9600), ctx.rt["_k_oserror"]()[:12], s.isOpen()])(ctx.rt["_k_Serial"]()), fs=True)

    class FakePort:
        def __init__(self, fail=None):
            self.is_open, self.timeout, self.fail, self.fd = True, None, fail, None
            self.in_waiting = 2
            self.sent = []

        def read(self, n):
            if self.fail == "read":
                raise OSError(errno.EIO, "serial io")
            return b"abc"[:n] if n else b""

        def write(self, d):
            if self.fail == "write":
                raise OSError(errno.EPIPE, "serial pipe")
            self.sent.append(bytes(d))
            return len(d)

        def close(self):
            self.is_open = False

    for fail in (None, "read", "write"):
        def serial(ctx, fail=fail):
            s = ctx.rt["_k_Serial"]()
            s.p = FakePort(fail)
            into = bytearray(8)
            return [s.isOpen(), s.read(into, 10), bytes(into), ctx.rt["_k_oserror"](), s.write(bytearray(b"wx")), ctx.rt["_k_oserror"](), s.handle(), s.p.sent, s.p.timeout, s.read(bytearray(0), 1), s.close(), s.isOpen()]

        add("os", "Serial fake port %s" % fail, serial, needs=("_k_Serial",))


def poller(ctx):
    import socket
    R = ctx.rt
    a, b = socket.socketpair()
    try:
        p = R["_k_Poller"]()
        log = []
        fd = a.fileno()
        shown = lambda rs: [(r.handle == fd, r.handle, r.events) if r.handle < 0 else (r.handle == fd, r.events) for r in rs]
        log.append(("wait empty", p.wait(0)))
        for call_ in (("add", (fd, 1)), ("add", (fd, 3)), ("wait", (0,)), ("remove", (fd,)), ("remove", (12345,)), ("add", (-5, 1)), ("wait", (0,)), ("add", (fd, 1)), ("wait", (0,))):
            try:
                res = getattr(p, call_[0])(*call_[1])
                log.append((call_[0], shown(res) if call_[0] == "wait" else res))
            except BaseException as e:
                log.append((call_[0], type(e).__qualname__, str(e)[:60]))
        b.send(b"x")
        log.append(("wait", shown(p.wait(200))))
        b.close()
        log.append(("wait closed", shown(p.wait(200))))
        log.append(("wait huge", shown(p.wait(2 ** 40))))
        log.append(R["_k_oserror"]())
        return log
    finally:
        a.close()
        b.close()


class FakePoll:
    """select.poll() with a scripted answer, recording what the runtime registered and asked."""

    def __init__(self, script, log):
        self.script, self.log = script, log

    def register(self, h, mask):
        self.log.append(("register", h, mask))

    def poll(self, t):
        self.log.append(("poll", t))
        return list(self.script)


def fake_select(script):
    def over(ctx):
        log = ctx.__dict__.setdefault("plog", [])
        return {"poll": lambda: FakePoll(script, log), "POLLIN": 1, "POLLPRI": 2, "POLLOUT": 4, "POLLERR": 8, "POLLHUP": 16, "POLLNVAL": 32}

    return over


class FakeSock:
    """A UDP socket that answers each recv with the next of a script: an exception to raise or a (bytes, source)."""

    def __init__(self, script):
        self.script = list(script)

    def _next(self):
        x = self.script.pop(0)
        if isinstance(x, BaseException):
            raise x
        return x

    def recvfrom(self, n):
        return self._next()

    def recvfrom_into(self, into):
        data, src = self._next()
        into[:len(data)] = data
        return len(data), src

    def close(self):
        pass


class FakeChild:
    def __init__(self, log):
        self.log, self._handle = log, 7

    def poll(self):
        return None

    def kill(self):
        self.log.append("kill")


def posix_branches():
    """The branches only one host reaches, run through fakes on any host (a real POSIX host also runs the real ones)."""
    posix, nt = {"name": "posix"}, {"name": "nt"}
    add("os", "_k_readyin posix poll", lambda ctx: [ctx.rt["_k_readyin"](5, 0.25), ctx.rt["_k_readyin"](5, 0), ctx.__dict__["plog"]],
        fakes={"_k_os": posix, "_k_select": fake_select([(5, 1)])}, needs=("_k_readyin",))
    add("os", "_k_readyin posix none", lambda ctx: [ctx.rt["_k_readyin"](5, 1.5), ctx.__dict__["plog"]], fakes={"_k_os": posix, "_k_select": fake_select([])}, needs=("_k_readyin",))
    # Poller.once: every poll event mask, the hang-up alone (a pipe whose writer closed) among them
    for i, script in enumerate(([(3, 16)], [(3, 1), (4, 4)], [(5, 8)], [(3, 32)], [(3, 16 | 4)], [(3, 1 | 4), (5, 8 | 16)], [(3, 2)], [])):
        for s in (None, 0, 0.5):
            def run(ctx, s=s):
                p = ctx.rt["_k_Poller"]()
                for h, e in ((3, 1), (4, 2), (5, 3), (-1, 1)):
                    p.add(h, e)
                return [p.once(s), ctx.__dict__["plog"]]

            add("os", "Poller.once posix %d %r" % (i, s), run, fakes={"_k_os": posix, "_k_select": fake_select(script)}, needs=("_k_Poller", "_k_Ready"))
    # a real hang-up: the writer of a pipe closes with nothing sent, so a POSIX host answers POLLHUP alone
    if os.name != "nt":
        def pipe_hup(ctx):
            r, w = os.pipe()
            try:
                p = ctx.rt["_k_Poller"]()
                p.add(r, 1)
                before = [(x.handle == r, x.events) for x in p.wait(0)]
                os.close(w)
                w = -1
                return before, [(x.handle == r, x.events) for x in p.wait(500)]
            finally:
                os.close(r)
                if w >= 0:
                    os.close(w)

        add("os", "Poller real pipe hang-up", pipe_hup, needs=("_k_Poller", "_k_Ready"))
    # syncdir: the fsync and the close of a directory descriptor
    for fail in (None, errno.EIO):
        def run(ctx):
            return ctx.rt["_k_syncdir"](ctx.tmp), ctx.__dict__["slog"]

        def over(ctx, fail=fail):
            log = ctx.__dict__.setdefault("slog", [])

            def fsync(fd):
                log.append(("fsync", fd))
                if fail:
                    raise OSError(fail, "io")

            return {"name": "posix", "open": lambda d, flags: log.append(("open", flags)) or 77, "fsync": fsync, "close": lambda fd: log.append(("close", fd))}

        add("host", "_k_syncdir fake fd %r" % fail, run, fakes={"_k_os": over}, fs=True, needs=("_k_syncdir",))

    # Process.kill: POSIX kills the child, Windows terminates its handle and ignores a refusal
    def kill(ctx, running):
        log = ctx.__dict__.setdefault("klog", [])
        p = ctx.rt["_k_Process"](FakeChild(log) if running else None)
        return [p.kill(), log]

    add("os", "Process.kill posix", lambda ctx: kill(ctx, True), fakes={"_k_os": posix}, needs=("_k_Process",))
    add("os", "Process.kill posix none", lambda ctx: kill(ctx, False), fakes={"_k_os": posix}, needs=("_k_Process",))
    for refuse in (False, True):
        def over(ctx, refuse=refuse):
            log = ctx.__dict__.setdefault("klog", [])

            def term(handle, code):
                log.append(("terminate", handle, code))
                if refuse:
                    raise PermissionError(5, "denied")

            return {"_winapi": type("W", (), {"TerminateProcess": staticmethod(term)})()}

        add("os", "Process.kill nt refuse=%s" % refuse, lambda ctx: kill(ctx, True), fakes={"_k_os": nt, "_k_subprocess": over}, needs=("_k_Process",))
    # recvFrom on both hosts' calls: data, nothing waiting, the reset a closed peer leaves (ignored on Windows), a hard error
    src = ("10.0.0.9", 4242)
    scripts = ([(b"ab", src)], [BlockingIOError(11, "again")], [ConnectionResetError(104, "reset"), (b"x", src)], [ConnectionResetError(104, "reset"), BlockingIOError(11, "again")],
               [OSError(errno.EIO, "io")], [(b"longer than the buffer", src)], [(b"", src)])
    for osname in ("posix", "nt"):
        for i, script in enumerate(scripts):
            def run(ctx, script=script):
                u = ctx.rt["_k_UdpSocket"]()
                u.s = FakeSock(script)
                into = bytearray(4)
                return [u.recvFrom(into), bytes(into), ctx.rt["_k_oserror"](), len(u.s.script)]

            add("os", "UdpSocket.recvFrom fake %s %d" % (osname, i), run, fakes={"_k_os": {"name": osname}}, needs=("_k_UdpSocket", "_k_Datagram"))


def waits():
    import socket
    add("os", "_k_readyin", lambda ctx: (lambda a, b: (ctx.rt["_k_readyin"](a, 0.01), b.send(b"x"), ctx.rt["_k_readyin"](a, 1.0), a.close(), b.close())[0::2])(*socket.socketpair()))
    add("os", "_k_readable", lambda ctx: (lambda a, b: (b.send(b"x"), ctx.rt["_k_readable"](a), a.close(), b.close())[1])(*socket.socketpair()))
    add("os", "_k_readable nt fake", lambda ctx: ctx.rt["_k_readable"](object()), fakes={"_k_os": {"name": "nt"}})
    for ms in (-1, 0, 1, 30):
        def run(ctx, ms=ms):
            calls = []

            def once(s):
                calls.append(None if s is None else round(min(s, 0.05), 2))
                return len(calls) >= 3

            return ctx.rt["_k_sliced"](ms, once), len(calls) if ms < 0 else None

        add("os", "_k_sliced %d" % ms, run)

    def other_thread(ctx):
        out = []
        t = threading.Thread(target=lambda: out.append(ctx.rt["_k_sliced"](40, lambda s: ("once", None if s is None else round(s, 3)))))
        t.start()
        t.join()
        return out

    add("os", "_k_sliced other thread", other_thread)

    def interrupted(ctx):
        n = {"i": 0}
        e = ctx.rt["_k_errors"]()

        def once(s):
            n["i"] += 1
            if n["i"] == 2:
                e.signalled = e.__dict__.get("signalled", 0) + 1
            if n["i"] > 6:
                raise AssertionError("not interrupted after six slices")
            return False

        try:
            return ctx.rt["_k_sliced"](-1, once)
        except BaseException as x:
            return type(x).__qualname__, getattr(x, "errno", None), n["i"]

    add("os", "_k_sliced interrupted", interrupted)

    class CV:
        def __init__(self, flips):
            self.flips = flips
            self.waits = 0

        def wait(self, t):
            self.waits += 1
            time.sleep(0.002)

    for ms in (0, -1, 25):
        for flips in (0, 2, 99):
            def run(ctx, ms=ms, flips=flips):
                cv = CV(flips)
                r = ctx.rt["_k_waitfor"](cv, lambda: cv.waits >= flips if flips != 99 else False, ms) if not (ms < 0 and flips == 99) else "skipped"
                return r, (cv.waits if flips != 99 else None)

            add("misc", "_k_waitfor ms %d flips %d" % (ms, flips), run)
    add("os", "_k_onsignal", lambda ctx: ctx.rt["_k_onsignal"](99999, ctx.rt["_k_Atomic"](False, None)), needs=("_k_onsignal",))
    def onsignal_real(ctx):
        # A real signal on the main thread: the handler stores true and counts it, and a wait in slices is interrupted.
        import signal
        sig = getattr(signal, "SIGUSR1", None) or signal.SIGBREAK
        old = signal.getsignal(sig)
        flag = ctx.rt["_k_Atomic"](False, None)
        n = {"i": 0}

        def once(s):
            n["i"] += 1
            if n["i"] == 2:
                signal.raise_signal(sig)
            if n["i"] > 6:
                raise AssertionError("not interrupted after six slices")
            return False

        try:
            ok = ctx.rt["_k_onsignal"](sig, flag)
            before = flag.load()
            try:
                waited = ctx.rt["_k_sliced"](-1, once)
            except BaseException as x:
                waited = (type(x).__qualname__, getattr(x, "errno", None))
            return ok, before, flag.load(), waited, n["i"], ctx.rt["_k_errors"]().__dict__.get("signalled", 0)
        finally:
            signal.signal(sig, old)

    add("os", "_k_onsignal real signal", onsignal_real, needs=("_k_onsignal", "_k_Atomic"))
    add("os", "_k_onsignal off-thread", lambda ctx: (lambda out: (threading.Thread(target=lambda: out.append(ctx.rt["_k_onsignal"](2, ctx.rt["_k_Atomic"](False, None)))).start(), time.sleep(0.05), out)[2])([]))
    add("os", "_k_escapestdout", lambda ctx: ctx.rt["_k_escapestdout"](), fakes={"_k_sys": lambda ctx: {"stdout": type("S", (), {"reconfigure": lambda s, **k: ctx.__dict__.setdefault("seen", k)})()}})
    add("os", "_k_escapestdout none", lambda ctx: ctx.rt["_k_escapestdout"]())


def ffi():
    specs = [("I", -2 ** 31, 2 ** 31 - 1), ("F",), ("B",), ("S",), ("Y",), ("O",), ("V",), ("J",), ("E", (1, 2, 3)), ("M", ("I", 0, 255)), ("L", ("S",)), ("D", ("S",), ("I", 0, 9)),
             ("Z", ("I", 0, 9)), ("T", ("I", 0, 9), ("S",)), ("L", ("M", ("F",))), ("Q",)]
    values = [0, 1, -1, 255, 256, 2 ** 31, -2 ** 31 - 1, 2 ** 70, 1.5, math.nan, True, False, "s", "", b"by", bytearray(b"ba"), None, [], [1, 2], ["a"], [None, 1.5], {}, {"a": 1}, {"a": 2},
              {"a": 20}, {1: 2}, {1, 2}, frozenset({3, 1}), {"b", "a"}, (1, "x"), (1,), (1, 2, 3), (), [[1.5]], [1.5, 2], {"k": [1, {"z": None}]}, 2, 3, 10 ** 400]
    for spec in specs:
        for v in values:
            def run(ctx, spec=spec, v=v):
                R = ctx.rt
                g = copy.deepcopy(v)
                return R["_k_check"](g, spec, "where", "Declared<T>"), g

            add("ffi", "_k_check %s %r" % (spec[0], str(v)[:20]), run, needs=("_k_check",))
    for v in values + [[object], [[[]]], {"a": float("inf")}, [True, 3], {"a": [1, 2, {"b": 3}]}, bytearray(b"x")]:
        call("ffi", "_k_out", v)
        call("ffi", "_k_in", v)
        call("ffi", "_k_jcheck", v)
        call("ffi", "_k_jone", v)
    calls("ffi", "_k_shown", [(v,) for v in (1, "x" * 100, [1, 2, 3], None, b"b", {"a": 1}, "é", 2 ** 200, -0.0, [None] * 30)])
    add("ffi", "_k_shown bad repr", lambda ctx: ctx.rt["_k_shown"](type("Bad", (), {"__repr__": lambda s: 1 / 0})()))
    add("ffi", "_k_out structs", lambda ctx: ctx.rt["_k_out"]([ctx.rt["_k_Ready"](1, 2), (ctx.rt["_k_Datagram"](1, "h", [3, bytearray(b"x")]),)]))
    for s in (("J",), ("L", ("J",)), ("I", 0, 1), ("T", ("J",), ("S",)), ("M", ("D", ("S",), ("J",))), ("D", ("S",), ("L", ("Y",))), ("Z", ("J",))):
        call("ffi", "_k_hasjson", s)
    calls("ffi", "_k_hasjson", [(("T",),), (("I", 0, 5),)])

    def raises_cases(ctx, names, ext_names):
        R = ctx.rt
        ext = type("Ext", (), {"__dict__": {}})()
        ns_ = {"MyErr": type("MyErr", (ValueError,), {}), "Other": type("Other", (Exception,), {}), "NotExc": int, "notclass": 5}
        ext = type("M", (), {})()
        ext.__dict__.update(ns_)
        try:
            return R["_k_raises"](ext, "where", names)
        except BaseException as e:
            return type(e).__qualname__, str(e)

    for names in (["MyErr"], ["ValueError", "MyErr"], ["MyErr", "ValueError"], ["Other", "OSError"], ["NotExc"], ["notclass"], ["Exception"], ["RuntimeError"], ["BaseException"],
                  ["Missing"], ["os.error"], ["OSError", "FileNotFoundError"], ["FileNotFoundError", "OSError"], [], ["subprocess.CalledProcessError"], ["ArithmeticError", "ZeroDivisionError"]):
        add("ffi", "_k_raises %s" % names, lambda ctx, names=names: raises_cases(ctx, names, None), needs=("_k_raises",))

    def thrown(ctx, exc, names):
        R = ctx.rt
        classes = tuple(getattr(__import__("builtins"), n) for n in names)
        try:
            R["_k_thrown"](exc(), classes, list(names))
            return "no throw"
        except R["_k_Error"] as e:
            return str(e), type(e.__cause__).__qualname__

    for make in (lambda: ValueError("bad"), lambda: ValueError(), lambda: KeyError("k"), lambda: OSError(2, "x"), lambda: RuntimeError("r")):
        add("ffi", "_k_thrown", lambda ctx, make=make: thrown(ctx, make, ("OSError", "ValueError", "KeyError")), needs=("_k_thrown",))
    add("ffi", "_k_lend", lambda ctx: (lambda b: [(lambda l: (l.__enter__().tobytes(), l.__enter__().readonly, l.__exit__(None, None, None)))(ctx.rt["_k_lend"](b, w)) for w in (True, False)] + [b.extend(b"z"), b])(bytearray(b"abc")), needs=("_k_lend",))

    def lend_keep(ctx):
        b = bytearray(b"abc")
        with ctx.rt["_k_lend"](b, True) as m:
            kept = m
        try:
            kept.tobytes()
        except ValueError as e:
            return str(e), b.extend(b"d"), b

    add("ffi", "_k_lend released", lend_keep)

    def fx(ctx, f, specs, args, owner=False):
        R = ctx.rt
        obj = R["_k_fx"](f, specs, "ext.f")
        try:
            if owner:
                with obj:
                    return obj(*args), obj.json
            return obj(*args), obj.json
        except R["_k_Error"] as e:
            return "Error", str(e)
        except BaseException as e:
            return type(e).__qualname__, str(e)

    sp = [(("I", 0, 9), "Int"), (("J",), "Json"), (("S",), "Str")]
    for args in ((1, [1, b"x"], "a"), (1, {"a": 2}, "a"), (1, 2), (11, {}, "a"), (1, {1: 2}, "a"), (1, [1], 5)):
        for fn_ in (lambda *a: list(a), lambda *a: bytearray(b"ret"), lambda *a: 1 / 0, lambda *a: (_ for _ in ()).throw(SystemExit(4))):
            add("ffi", "_k_fx %r" % (args,), lambda ctx, args=args, fn_=fn_: fx(ctx, fn_, sp, args), fakes={"_k_os": lambda ctx: dict(rt_fakes.fake_end(), name="posix")}, needs=("_k_fx",))
        add("ffi", "_k_fx owner %r" % (args,), lambda ctx, args=args: fx(ctx, lambda *a: (_ for _ in ()).throw(ctx.rt["_k_Error"]("deep")), sp, args, True), fakes={"_k_os": lambda ctx: dict(rt_fakes.fake_end(), name="posix")})

    def ext(ctx, names, body):
        R = ctx.rt
        d = os.path.join(ctx.tmp, "mod")
        os.makedirs(d, exist_ok=True)
        here = os.path.join(d, "x.py")
        if body is not None:
            with open(os.path.join(d, "x_ext.py"), "w") as f:
                f.write(body)
        try:
            m = R["_k_extern"](here, "x_ext.py", names)
            return sorted(n for n in m.__dict__ if not n.startswith("__"))
        except BaseException as e:
            return type(e).__qualname__, str(e).replace(ctx.tmp, "<tmp>")

    for body, names in (("def f(a, b):\n    return a\n", [("f", 2)]), ("def f(a, b):\n    return a\n", [("f", 1)]), ("def f(a, b=1):\n    return a\n", [("f", 1), ("f", 2)]),
                        ("def f(*a):\n    pass\n", [("f", 5)]), ("import time\nsleep = time.sleep\n", [("sleep", 1)]), ("x = 1\n", [("g", 1)]), (None, [("f", 1)]),
                        ("raise ValueError('at import')\n", []), ("x = 1\n", [])):
        add("ffi", "_k_extern %r %r" % ((body or "")[:12], names), lambda ctx, body=body, names=names: ext(ctx, names, body), fs=True, needs=("_k_extern",))

    def use(ctx):
        R = ctx.rt
        d = os.path.join(ctx.tmp, "mod")
        os.makedirs(os.path.join(d, "pkg"), exist_ok=True)
        for rel, body in (("a.py", "v = 1\n"), ("pkg/__init__.py", ""), ("pkg/b.py", "w = 2\n"), ("bad.py", "raise KeyError('x')\n")):
            with open(os.path.join(d, rel), "w") as f:
                f.write(body)
        here = os.path.join(d, "main.py")
        out = []
        for rel in ("a.py", "a.py", "pkg/b.py", "bad.py", "bad.py", "missing.py"):
            try:
                m = R["_k_use"](here, rel)
                out.append((rel, sorted(k for k in m.__dict__ if not k.startswith("__"))))
            except BaseException as e:
                out.append((rel, type(e).__qualname__, str(e).replace(ctx.tmp, "<tmp>")[:50]))
        sys.path.insert(0, d)
        try:
            for rel in ("pkg/b.py",):
                out.append(R["_k_named"](os.path.join(d, rel), R["_k_key"](os.path.join(d, rel))))
            out.append(R["_k_finds"]("a", R["_k_key"](os.path.join(d, "a.py"))))
            out.append(R["_k_finds"]("nope.nothing", "k"))
            out.append(R["_k_finds"]("os", "k"))
            out.append(R["_k_held"]("none"))
        finally:
            sys.path.remove(d)
            for k in [k for k in sys.modules if k.startswith(os.path.normcase(d)) or k in ("a", "pkg", "pkg.b", "bad")]:
                del sys.modules[k]
        return out

    add("ffi", "_k_use + _k_named + _k_finds", use, fs=True, needs=("_k_use", "_k_named", "_k_finds", "_k_held"))
    add("ffi", "_k_self", lambda ctx: ctx.rt["_k_self"]())
    add("ffi", "_k_Ref", lambda ctx: (lambda r: (r.value, setattr(r, "value", 2), r.value))(ctx.rt["_k_Ref"](1)))


def http_loopback(ctx):
    """A server on the loopback and the client against it: the requests the handler saw, and each reply."""
    R = ctx.rt
    seen = []
    port = {}

    def handle(req):
        seen.append((req.method, req.path, req.host.replace(str(port["n"]), "PORT"), bytes(req.body)))
        A = R["_k_HttpAnswer"]
        if req.path == "/ok":
            return A(200, "text/plain", bytearray(b"hi"), {"X-A": "1"})
        if req.path == "/empty404":
            return A(404, "text/plain", bytearray(), {})
        if req.path == "/body500":
            return A(500, "text/x", bytearray(b"broken"), {})
        if req.path == "/close":
            return A(0, "t", bytearray(), {})
        if req.path == "/to-ok":
            return A(302, "t", bytearray(), {"Location": "/ok"})
        if req.path == "/to-gopher":
            return A(302, "t", bytearray(), {"Location": "gopher://x/y"})
        if req.path == "/echo":
            return A(200, "application/octet-stream", bytearray(req.body.upper()), {})
        return A(204, "t", bytearray(), {})

    def admit(path, n):
        A = R["_k_HttpAnswer"]
        seen.append(("admit", path, n))
        return A(413, "t", bytearray(b"too big"), {}) if path == "/big" or n < 0 else A(0, "t", bytearray(), {})

    srv = R["_k_httpbind"]("127.0.0.1", 0, handle, admit)
    port["n"] = srv.port()
    out = [srv.port() > 0]
    t = threading.Thread(target=lambda: out.append(("served", srv.serve())))
    t.start()
    base = "http://127.0.0.1:%d" % port["n"]
    for path, data, ctype in (("/ok", None, None), ("/empty404", None, None), ("/body500", None, None), ("/close", None, None), ("/to-ok", None, None), ("/to-gopher", None, None),
                              ("/echo", b"abc", "text/plain"), ("/echo", b"", None), ("/big", None, None), ("/big", b"x", None), ("/other", None, None), ("/ok?q=1", None, None)):
        r = R["_k_httpcall"](base + path, data, ctype, 3000)
        why = R["_k_httperror"]().replace(str(port["n"]), "PORT")[:60]
        if path == "/big" and data is not None:
            # The server answers 413 and closes without reading the body, so the client sees the 413 or a reset by
            # timing alone. The GET above is the exact 413; here only "a 413 or no reply" is kept, the admit call is in seen.
            ok = r is None or r.status == 413
            r, why = ("413 or aborted", ok), ""
        out.append((path, r, why))
    srv.shutdown()
    t.join()
    out.append(("again", srv.serve(), srv.shutdown()))
    out.append(seen)
    return out


def misc():
    pats =["a+", "(", "[", "(?P<n>x)", "(a)(b)", "\\", "a{2,1}", "a{99999999999999999999}", "(" * 400 + ")" * 400, "(?<=a+)b", "*", "(?P<n>a)(?P<n>b)", "\\p{L}", "x|", "", "(a)|(b)", "é+", "\\1"]
    for p in pats:
        for flags in (0, 2, 16, 99999999999999):
            add("misc", "_k_recompile %r %d" % (p[:12], flags), lambda ctx, p=p, flags=flags: (ctx.rt["_k_recompile"](p, flags), ctx.rt["_k_reerror"]()), needs=("_k_recompile",))
    for p, text in (("a+", "xaaay"), ("(a)(b)", "ab ab"), ("(a)", "a a"), ("x", ""), ("", "ab"), ("(a)|(b)", "ab"), ("\\b", "a b"), ("(a)?b", "b ab")):
        for fn in ("_k_refindall",):
            add("misc", "%s %r" % (fn, p), lambda ctx, p=p, text=text, fn=fn: ctx.rt[fn](ctx.rt["_k_recompile"](p, 0), text), needs=(fn,))
        add("misc", "_k_resplit %r" % p, lambda ctx, p=p, text=text: ctx.rt["_k_resplit"](ctx.rt["_k_recompile"](p, 0), text), needs=("_k_resplit",))
        for rep in ("-", "\\1", "\\g<n>", "\\", "\\9", "\\g<x>", "", "\\n"):
            add("misc", "_k_resub %r %r" % (p, rep), lambda ctx, p=p, text=text, rep=rep: ctx.rt["_k_resub"](ctx.rt["_k_recompile"](p, 0), rep, text), needs=("_k_resub",))
        for g in (0, 1, 2, "n", "zz", -1, 5):
            add("misc", "_k_regroup %r %r" % (p, g), lambda ctx, p=p, text=text, g=g: (lambda m: ctx.rt["_k_regroup"](m, g) if m else None)(ctx.rt["_k_recompile"](p, 0).search(text)), needs=("_k_regroup",))
    add("misc", "_k_Regex _k_Match", lambda ctx: (ctx.rt["_k_Regex"] is __import__("re").Pattern, ctx.rt["_k_Match"] is __import__("re").Match, ctx.rt["_k_Random"] is random.Random))
    for seed in (0, 1, 2 ** 40):
        for stop in (-1, 0, 1, 2, 10, 2 ** 40, 2 ** 70):
            add("misc", "_k_below", lambda ctx, seed=seed, stop=stop: [ctx.rt["_k_below"](random.Random(seed), stop) for _ in range(3)], needs=("_k_below",))
        for lo, hi in ((0, 0), (0, 1), (1, 0), (-5, 5), (-2 ** 63, 2 ** 63 - 1), (5, 4)):
            add("misc", "_k_between", lambda ctx, seed=seed, lo=lo, hi=hi: [ctx.rt["_k_between"](random.Random(seed), lo, hi) for _ in range(3)], needs=("_k_between",))
        for k in (-1, 0, 1, 8, 32, 63, 64, 100):
            add("misc", "_k_bits", lambda ctx, seed=seed, k=k: [ctx.rt["_k_bits"](random.Random(seed), k) for _ in range(3)], needs=("_k_bits",))
    csvs = [b"", b"a,b\n1,2\n", b"a,\"b\nc\"\r\n", b"\"unterminated", b"x\x00y,z\n", b"\xff,bad\n", b"a,b\r\n\r\nc", b"\"a\"b,c\n", b"a," + b"x" * 200000 + b"\n", "hé,\U0001F600\n".encode()]
    for i, data in enumerate(csvs):
        def run(ctx, data=data):
            p = os.path.join(ctx.tmp, "c.csv")
            with open(p, "wb") as f:
                f.write(data)
            rd = ctx.rt["_k_opencsv"](p)
            rows = []
            for _ in range(6):
                row = rd.next()
                rows.append((row if row is None or len(str(row)) < 80 else len(row), rd.isMalformed(), ctx.rt["_k_csverror"]()[:50]))
                if row is None:
                    break
            rd.close()
            return rows, rd.next()

        add("misc", "csv reader %d" % i, run, fs=True, needs=("_k_opencsv", "_k_CsvReader"))
    add("misc", "_k_opencsv missing", lambda ctx: (ctx.rt["_k_opencsv"](os.path.join(ctx.tmp, "none")) is None, ctx.rt["_k_csverror"]().replace(ctx.tmp, "<tmp>")), fs=True)
    add("misc", "CsvReader default", lambda ctx: (lambda r: [r.next(), r.isMalformed(), r.close()])(ctx.rt["_k_CsvReader"]()))
    calls("misc", "_k_csvline", [(c,) for c in ([], [""], ["a", "b"], ["a,b", 'q"q', "n\nl"], [1, None, 2.5], ["é", "\U0001F600"], ["\x00"], [["x"]], "abc", ["a\rb"])])
    # args
    def full(spec):
        spec.positional("src", "the source 100%")
        spec.text("--name", "dflt", "a name")
        spec.whole("--n", 5, "a count")
        spec.real("--x", 1.5, "a real")
        spec.flag("--v", "verbose")

    argvs = [[], ["f"], ["f", "--name", "z", "--n", "7", "--x", "2.5", "--v"], ["f", "--n", "9223372036854775807"], ["f", "--n", "9223372036854775808"], ["f", "--n", "abc"],
             ["f", "--x", "inf"], ["f", "--x", "nan"], ["--help"], ["f", "extra"], ["f", "--nope"], ["f", "--n"], ["f", "--n=-4"], ["f", "--v", "--v"], ["-h"], ["f", "--na", "p"]]
    for argv in argvs:
        def run(ctx, argv=argv):
            R = ctx.rt
            spec = R["_k_ArgSpec"]("prog", "about")
            full(spec)
            try:
                p = spec.parse(argv)
            except SystemExit as e:
                return "exit", e.code
            res = []
            for call_, n in (("textOf", "src"), ("textOf", "--name"), ("wholeOf", "--n"), ("realOf", "--x"), ("isSet", "--v"), ("textOf", "--n"), ("wholeOf", "zz"), ("isSet", "src")):
                try:
                    res.append(getattr(p, call_)(n))
                except BaseException as e:
                    res.append((type(e).__qualname__, str(e)))
            return res

        add("misc", "ArgSpec parse %r" % argv, run, needs=("_k_ArgSpec", "_k_Parsed"))
    for name, kind in (("", "positional"), ("-x", "positional"), ("x", "flag"), ("--", "flag"), ("--ok", "flag"), ("-s", "text")):
        def run(ctx, name=name, kind=kind):
            spec = ctx.rt["_k_ArgSpec"]("p", "100% %(prog)")
            try:
                if kind == "positional":
                    spec.positional(name, "h")
                elif kind == "flag":
                    spec.flag(name, "h")
                    spec.flag(name, "dup")
                else:
                    spec.text(name, "d", "h")
                return "ok"
            except BaseException as e:
                return type(e).__qualname__, str(e)

        add("misc", "ArgSpec add %r %s" % (name, kind), run)
    for t in ("7", "-7", " 7", "7.0", "9223372036854775808", "-9223372036854775809", "x", "1_0", "٣", ""):
        add("misc", "_k_argint " + t, lambda ctx, t=t: ctx.rt["_k_argint"](t), needs=("_k_argint",))
    # http helpers that need no socket
    class Reader:
        def __init__(self, data):
            self.data = data
            self.i = 0

        def read(self, n):
            chunk = self.data[self.i:self.i + n]
            self.i += len(chunk)
            return chunk

    class Resp:
        def __init__(self, data):
            self.fp = Reader(data)

    for size in (0, 1, 65535, 65536, 65537, 200000):
        for n in (0, 1, size, size + 1, size + 70000):
            if n > 400000:
                continue
            add("misc", "_k_httpbody %d %d" % (size, n), lambda ctx, size=size, n=n: ctx.rt["_k_httpbody"](Reader(bytes(size)), n), needs=("_k_httpbody",))
            def pieces(ctx, size=size, n=n):
                try:
                    return ctx.rt["_k_httppieces"](Resp(bytes(size)), n)
                except BaseException as e:
                    return type(e).__qualname__, str(e), getattr(e, "partial", None) and len(e.partial)

            add("misc", "_k_httppieces %d %d" % (size, n), pieces, needs=("_k_httppieces",))
    for url in ("ftp://x/", "file:///x", "gopher://x", "nothing", "", "http://", "http://127.0.0.1:1/", "http://127.0.0.1:99999/", "http://[::1", "\x00http://x"):
        for data, ctype in ((None, None), (b"x", "text/plain")):
            add("misc", "_k_httpcall %r" % url, lambda ctx, url=url, data=data, ctype=ctype: (ctx.rt["_k_httpcall"](url, data, ctype, 300), ctx.rt["_k_httperror"]()), needs=("_k_httpcall",))
    add("misc", "_k_httpopener", lambda ctx: (ctx.rt["_k_httpopener"]() is ctx.rt["_k_httpopener"](), [type(h).__qualname__ for h in ctx.rt["_k_httpopener"]().handlers if type(h).__module__ == "rt"]))
    for status, ctype, headers in ((0, "t", {}), (99, "t", {}), (1000, "t", {}), (200, "t", {"X-A": "1"}), (200, "t", {"Content-Type": "x"}), (200, "t", {"content-length": "1"}),
                                   (200, "t\nx", {}), (200, "t", {"a\rb": "c"}), (200, "t", {"a": "b\n"}), (200, "Ā", {}), (200, "é", {"é": "ü"}), (404, "t", {})):
        add("misc", "_k_httpcheck %d" % status, lambda ctx, s=status, c=ctype, h=headers: ctx.rt["_k_httpcheck"](ctx.rt["_k_HttpAnswer"](s, c, bytearray(), dict(h))), needs=("_k_httpcheck",))
    add("misc", "_k_httpbind fails", lambda ctx: [(ctx.rt["_k_httpbind"](h, p, None, None), ctx.rt["_k_httperror"]()) for h, p in (("999.9.9.9", 0), ("127.0.0.1", -1), ("127.0.0.1", 70000), ("127.0.0.1", "x"))], needs=("_k_httpbind",))
    add("misc", "HttpServer unbound", lambda ctx: (lambda s: [s.port(), s.serve(), ctx.rt["_k_httperror"](), s.shutdown(), s.serve()])(ctx.rt["_k_HttpServer"]()), needs=("_k_HttpServer",))
    add("misc", "HttpServer shutdown before serve", lambda ctx: (lambda s: [s.port() > 0, s.shutdown(), s.serve(), ctx.rt["_k_httperror"]()])(ctx.rt["_k_httpbind"]("127.0.0.1", 0, None, None)), needs=("_k_httpbind",))
    add("misc", "HttpServer loopback", http_loopback, needs=("_k_httpbind", "_k_HttpServer", "_k_httpcall"))
    # sync primitives, one thread
    for wrap_name in (None, "_k_i8", "_k_u8", "_k_i32"):
        def run(ctx, wrap_name=wrap_name):
            R = ctx.rt
            a = R["_k_Atomic"](100 if wrap_name else 0.0, R[wrap_name] if wrap_name else None)
            out = [a.load(), a.add(100), a.add(-300), a.swap(5), a.store(7), a.load(), a.compareSwap(7, 8), a.compareSwap(7, 9), a.load()]
            if wrap_name is None:
                a.store(-0.0)
                out += [a.compareSwap(0.0, 1.0), a.compareSwap(-0.0, 1.0), a.load()]
                a.store(math.nan)
                out += [a.compareSwap(math.nan, 2.0), a.load()]
            return out

        add("misc", "Atomic %s" % wrap_name, run, needs=("_k_Atomic",))
    add("misc", "Atomic bool", lambda ctx: (lambda a: [a.compareSwap(True, False), a.compareSwap(False, True), a.load()])(ctx.rt["_k_Atomic"](True, ctx.rt["_k_u8"])))
    add("misc", "Mutex", lambda ctx: (lambda m: [m.lock(lambda v: v.append(1)), m.waitUntil(lambda v: len(v) == 1, 0), m.waitUntil(lambda v: len(v) == 2, 10), m.waitUntil(lambda v: True, -1), m.value])(ctx.rt["_k_Mutex"]([])))
    def queue(ctx):
        q = ctx.rt["_k_BlockingQueue"]()
        out = [q.size(), q.pop(0), q.pop(5), q.push(1), q.push(2), q.size(), q.pop(0), q.isClosed(), q.close(), q.isClosed(), q.push(3), q.size(), q.pop(-1), q.pop(-1), q.pop(10)]
        return out

    add("misc", "BlockingQueue", queue, needs=("_k_BlockingQueue",))

    def thread(ctx):
        R = ctx.rt
        out = []
        t = R["_k_Thread"]("w", lambda: (out.append(1), time.sleep(0.01), out.append(2)))
        before = t.stopRequested()
        t.requestStop()
        t.join()
        t.join()
        mine = []
        t2 = R["_k_Thread"]("self", lambda: mine.append(R["_k_Thread"]))
        t2.join()
        return out, before, t.stopRequested(), mine == [R["_k_Thread"]]

    add("misc", "Thread", thread, needs=("_k_Thread",))
    # test suite output
    def suite(ctx):
        s = ctx.rt["_k_Suite"]("Title")
        s.check(True, "one")
        s.check(False, "two")
        s.checkStr("a", "a", "same")
        s.checkStr("a", "b", "differ é")
        return s.finish(), s.checks, s.failures

    add("misc", "Suite", suite, needs=("_k_Suite",))
    add("misc", "Suite clean", lambda ctx: (lambda s: (s.check(True, "x"), s.finish()))(ctx.rt["_k_Suite"]("T")), needs=("_k_Suite",))
    add("misc", "_k_errors identity", lambda ctx: (ctx.rt["_k_errors"]() is ctx.rt["_k_errors"](), ctx.rt["_k_errors"]().Error is ctx.rt["_k_Error"], ctx.rt["_k_errors"]().reasons is ctx.rt["_k_reasons"]))
    add("misc", "_k_deadline", lambda ctx: (ctx.rt["_k_deadline"] is time.monotonic, ctx.rt["_k_errors"]().deadline is ctx.rt["_k_deadline"]))


def build(repo):
    CASES.clear()
    ints()
    floats()
    texts()
    slices()
    byteops()
    radix()
    collections_()
    structs()
    ranges()
    json_cases(repo)
    hosts()
    files()
    waits()
    posix_branches()
    ffi()
    misc()
    return sorted(CASES, key=lambda c: KIND_ORDER.index(c.kind))
