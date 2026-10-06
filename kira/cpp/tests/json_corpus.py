#!/usr/bin/env python3
# json_corpus.py gen DIR [SEED]: DIR/corpus.in for json_corpus.cxx, and corpus.want, the py target's
# answers (runtime.py over CPython's json). json_corpus.py check DIR: corpus.got against corpus.want.
import math
import os
import random
import runpy
import struct
import sys

RT = runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "py", "runtime.py"))
FORMS = [(None, True), (0, True), (1, False), (2, True), (4, False)]


def wtf8(s):
    return s.encode("utf-8", "surrogatepass")


def blob(b):
    return b"%d:" % len(b) + b


INT_EDGES = [0, 1, -1, 7, -42, 2 ** 31 - 1, -2 ** 31, 2 ** 31, 2 ** 32, 2 ** 53, 2 ** 53 + 1, -2 ** 53 - 1,
             10 ** 15, 10 ** 16, 10 ** 17, -10 ** 18, 2 ** 63 - 1, -2 ** 63, -2 ** 63 + 1, 2 ** 63 - 2]
FLOAT_EDGES = [0.0, -0.0, math.nan, -math.nan, math.inf, -math.inf, 5e-324, -5e-324, 1e-323,
               2.2250738585072014e-308, 2.225073858507201e-308, 1.7976931348623157e308, -1.7976931348623157e308,
               1e16, -1e16, 1e15, 9999999999999998.0, 1e17, 1.5e16, 123456789012345680.0, 1234567890123456.8,
               0.0001, -0.0001, 0.00001, 9.999999999999999e-05, 0.00012345, 1.2345e-05, 0.1, 0.2, 0.3,
               0.1 + 0.2, 1 / 3, 2 / 3, 3.0, -3.0, 100.0, 1e21, 1e22, 1e23, 2.0 ** 53, 2.0 ** 63, 2.0 ** 64,
               0.5, 1.5, -1.5e300, 1e-300, 4.35, 5e-05, 1e-07, 1.0, 10.0, 1e100, 9007199254740993.0,
               float(2 ** 63 - 1), 7.0e-10, 1.7976931348623155e308]
CHARS = [
    lambda r: chr(r.randint(0x20, 0x7E)),
    lambda r: chr(r.randint(0x20, 0x7E)),
    lambda r: r.choice('"\\/'),
    lambda r: chr(r.randint(0, 0x1F)),
    lambda r: "\x7f",
    lambda r: chr(r.randint(0x80, 0xFF)),
    lambda r: chr(r.randint(0x100, 0x7FF)),
    lambda r: chr(r.randint(0x800, 0xD7FF)),
    lambda r: chr(r.randint(0xD800, 0xDFFF)),
    lambda r: chr(r.randint(0xE000, 0xFFFF)),
    lambda r: chr(r.randint(0x10000, 0x10FFFF)),
    lambda r: r.choice("\ufffd\u2028\u2029\ufeff\u00e9\U0001F600\U0010FFFF\ud83d\ude00"),
]


def rand_float(r):
    pick = r.random()
    if pick < 0.2:
        return r.choice(FLOAT_EDGES)
    if pick < 0.6:
        return struct.unpack("<d", struct.pack("<Q", r.getrandbits(64)))[0]
    if pick < 0.75:
        return r.uniform(-1.0, 1.0) * 10.0 ** r.randint(-30, 30)
    if pick < 0.9:
        return round(r.uniform(-1000.0, 1000.0), r.randint(0, 6))
    return float(r.randint(-10 ** 18, 10 ** 18))


def rand_int(r):
    pick = r.random()
    if pick < 0.35:
        return r.choice(INT_EDGES)
    if pick < 0.7:
        return r.randint(-1000, 1000)
    return r.randint(-2 ** 63, 2 ** 63 - 1)


def rand_str(r, n):
    return "".join(r.choice(CHARS)(r) for _ in range(n))


def scalar(r):
    pick = r.random()
    if pick < 0.05:
        return None, b"n"
    if pick < 0.12:
        b = r.random() < 0.5
        return b, b"t" if b else b"f"
    if pick < 0.35:
        i = rand_int(r)
        return i, b"i%d;" % i
    if pick < 0.65:
        d = rand_float(r)
        return d, b"d" + b"%016x" % struct.unpack("<Q", struct.pack("<d", d))[0]
    s = rand_str(r, r.choice([0, 1, 2, 3, 5, 8, 13, 24]))
    return s, b"s" + blob(wtf8(s))


def value(r, depth, deepest):
    pick = r.random()
    if depth >= deepest or pick < 0.4:
        return scalar(r)
    if pick < 0.7:
        out, enc = [], []
        for _ in range(r.choice([0, 1, 2, 3, 4, 6])):
            v, e = value(r, depth + 1, deepest)
            out.append(v)
            enc.append(e)
        return out, b"a%d;" % len(out) + b"".join(enc)
    pool = [rand_str(r, r.randint(0, 4)) for _ in range(3)]
    out, enc = {}, []
    n = r.choice([0, 1, 2, 3, 5, 7])
    for _ in range(n):
        k = r.choice(pool) if r.random() < 0.3 else rand_str(r, r.randint(0, 6))
        v, e = value(r, depth + 1, deepest)
        out[k] = v
        enc.append(blob(wtf8(k)) + e)
    return out, b"o%d;" % n + b"".join(enc)


def chain(depth, leaf):
    v, e = leaf
    for k in range(depth):
        if k % 2:
            v, e = [v], b"a1;" + e
        else:
            v, e = {"k": v}, b"o1;" + blob(b"k") + e
    return v, e


def dumps_of(v):
    out = []
    for indent, ascii in FORMS:
        text = RT["_k_jdump"](v) if indent is None else RT["_k_jpretty"](v, indent, ascii)
        out.append(wtf8(text))
    return out


NUMBER_EDGES = ["0", "-0", "-0.0", "0.0", "1", "-1", "01", "-01", "00", "1.", ".5", "-", "-.5", "1e", "1e+", "1e-",
                "1E5", "1e05", "1e+05", "1E-5", "2.5E+3", "-Infinity", "Infinity", "NaN", "-NaN", "nan", "inf",
                "Infinityx", "-Infinit", "Infinit", "NaNa", "1e400", "-1e400", "1e-400", "-1e-400", "4.9e-324",
                "2e-324", "3e-324", "2.4703282292062328e-324", "2.4703282292062327e-324", "1.7976931348623158e308",
                "1.7976931348623159e308", "1.7976931348623157e308", "9223372036854775807", "9223372036854775808",
                "-9223372036854775808", "-9223372036854775809", "18446744073709551616", "1" * 25, "1" * 5000,
                "-" + "9" * 400, "0." + "0" * 400 + "1", "1" + "0" * 400 + ".0", "123456789012345678901234567890e-10",
                "0.1e1", "1e-0", "1e+0", "-0e-0", "1.5e", "1.5e+", "1x", "1 2", "1,2", "+1", "0x10", "1_000",
                "1.0e99999999999999999999", "1.0e-99999999999999999999", "0e99999999999999999999"]
STRING_EDGES = ['""', '"a"', '"\\"\\\\\\/\\b\\f\\n\\r\\t"', '"\\u0000\\u001f\\u007f\\u0080\\u00ff"', '"\\uD83D\\uDE00"',
                '"\\ud83d\\ude00"', '"\\ud83d"', '"\\ude00"', '"\\ude00\\ud83d"', '"\\ud83d\\ud83d\\ude00"',
                '"\\ud83dx\\ude00"', '"\\ud83d\\u0041"', '"\\ud83d\\uZZZZ"', '"\\ud83d\\u12"', '"\\ud83d\\u"',
                '"\\ud800\\udc00', '"\\ud800\\udc00"x', '"\\u12"', '"\\u12', '"\\u"', '"\\uZZZZ"', '"\\u00e"',
                '"\\u00E9"', '"\\x41"', '"\\a"', '"\\', '"\\"', '"abc', '"', '"a\nb"', '"a\tb"', '"a\x00b"',
                '"a\x1fb"', '"a\x7fb"', '"\u00e9\U0001F600"', '"\ud800"', "'a'", '"\\u0041\\u00e9\\u4e2d"',
                '"\\uFFFF\\uFFFE\\uFEFF"', '"\\/"', '"\\ud834\\udd1e\\ud834"']
DOC_EDGES = ["", " ", "\t\n\r ", "null", "true", "false", "nul", "tru", "fals", "nulll", "True", "None", "[]", "{}",
             "[ ]", "{ }", "[", "]", "{", "}", "[1,]", "[1,,2]", "[,1]", "[1 2]", "{\"a\":1,}", "{\"a\" 1}",
             "{\"a\":}", "{\"a\"}", "{a:1}", "{'a':1}", "{1:1}", "{\"a\":1 \"b\":2}", "{\"a\":1,\"a\":2,\"b\":3,\"a\":4}",
             "[1]x", "[1] x", "[1]\n\n  x", "1 ", " 1", "\ufeff[1]", "\ufeff", "[1] // c", "/* c */ [1]", "[1] /",
             "[\"a\"\n,\n\"b\"\n]", "{\"\\u00e9\":\"\u00e9\"}", "\u00e9", "[\u00e9]", "[1,\u00e9]", "{\"a\u00e9\":1,\u00e9}",
             "\n\n[\n1,\n\u00e9\n]", "[\"\ud800\", \ud800]", "{\"a\":[1,{\"b\":null}],\"c\":\"d\"}", "[NaN, Infinity, -Infinity]",
             "[1e400, -1e400]", "{\"\":\"\"}", "[[[[[[[[]]]]]]]]", "[{}]", "[[],{},[{}]]", "\x00", "[\x00]", "[1]\x00"]


def number_text(r):
    sign = "-" if r.random() < 0.4 else ""
    n = r.choice([1, 2, 5, 9, 15, 16, 17, 18, 19, 20, 25, 40])
    digits = "".join(r.choice("0123456789") for _ in range(n)).lstrip("0") or "0"
    frac = "." + "".join(r.choice("0123456789") for _ in range(r.randint(1, 30))) if r.random() < 0.6 else ""
    exp = (r.choice("eE") + r.choice(["", "+", "-"]) + str(r.randint(0, 330))) if r.random() < 0.5 else ""
    return sign + digits + frac + exp


def deep(n, inner, opener="[", closer="]"):
    return opener * n + inner + closer * n


def deep_docs():
    out = []
    for n in [1, 100, 511, 512, 513, 514, 600, 900, 990, 1000, 1500]:
        out.append(deep(n, ""))
        out.append(deep(n, "1"))
        out.append(deep(n, "1", "[", "]") + " x")
        out.append(deep(n, "1", "[", ""))
        out.append(deep(n, "1,", "[", "]"))
        out.append('{"a":' * n + "1" + "}" * n)
        out.append('{"a":' * n + "1" + "}" * (n - 1))
        out.append("[" * n + "99999999999999999999" + "]" * n)
        out.append("[" * n + '"\\x"' + "]" * n)
        out.append(('[{"k":' * (n // 2)) + "true" + ("}]" * (n // 2)))
    out.append("[" * 513 + "]" * 512)
    out.append("[" * 512 + "99999999999999999999,[" + "]" * 514)
    out.append("[99999999999999999999," + "[" * 600 + "]" * 601)
    out.append("[1," + "[" * 520 + "x" + "]" * 521)
    out.append("[" + '"[[[[",' * 600 + "1]")
    out.append('["' + "[" * 600 + '"]')
    out.append("[" * 512 + '"\\' + "]" * 512)
    return out


def mutate(r, text):
    if not text:
        return r.choice(["[", "x", " "])
    i = r.randrange(len(text) + 1)
    pick = r.random()
    junk = r.choice([",", "]", "}", "[", "{", '"', "\\", ":", "0", "1", "-", ".", "e", "+", "a", "n", "N", "I", " ", "\n",
                     "\t", "\x00", "\x01", "\x1f", "\x7f", "\u00e9", "\ud800", "\U0001F600", "\ufeff", "/", "'", "u",
                     "\\u", "\\u00", "true", "null", "NaN"])
    if pick < 0.3:
        return text[:i] + text[i + 1:]
    if pick < 0.6:
        return text[:i] + junk + text[i:]
    if pick < 0.8:
        return text[:i]
    j = r.randrange(len(text) + 1)
    return text[:min(i, j)] + text[max(i, j):]


def rand_doc(r, values):
    v = r.choice(values)
    indent = r.choice([None, None, 0, 1, 2, "\t"])
    seps = r.choice([None, (",", ":"), (", ", ": "), (" ,", " :"), (" , ", " : ")])
    text = RT["_k_json"].dumps(v, indent=indent, separators=seps, ensure_ascii=r.random() < 0.5)
    if indent is not None and r.random() < 0.5:
        text = text.replace("\n", r.choice(["\r\n", "\n\r", "\n \t", "\r"]))
    if r.random() < 0.2:
        text = r.choice([" ", "\n", "\t", "\r\n "]) + text + r.choice(["", " ", "\n", "\t\r"])
    return text


def doc_answer(text):
    v = RT["_k_jparse"](text)
    why = RT["_k_jerror"]()
    if why:
        return wtf8("error " + why), False
    return wtf8("ok " + RT["_k_jdump"](v) + "\n" + RT["_k_jpretty"](v, 1, False)), True


def gen(out_dir, seed):
    r = random.Random(seed)
    corpus = bytearray()
    want = []
    values = []
    for k in range(4000):
        v, e = value(r, 0, r.choice([0, 1, 2, 3, 4, 6]))
        values.append(v)
        corpus += b"D" + e + b"\n"
        want += dumps_of(v)
    for n in [64, 200, 300]:
        v, e = chain(n, scalar(r))
        corpus += b"D" + e + b"\n"
        want += dumps_of(v)
    docs = list(DOC_EDGES) + NUMBER_EDGES + ["[" + t + "]" for t in NUMBER_EDGES] + STRING_EDGES
    docs += ['{"a":' + t + "}" for t in STRING_EDGES] + ["[" + t + "]" for t in STRING_EDGES] + deep_docs()
    docs += [number_text(r) for _ in range(1500)]
    for _ in range(2500):
        docs.append(rand_doc(r, values))
    for _ in range(4000):
        text = rand_doc(r, values) if r.random() < 0.7 else r.choice(docs)
        for _ in range(r.choice([1, 1, 1, 2, 3])):
            text = mutate(r, text)
        docs.append(text)
    refused = 0
    for text in docs:
        corpus += b"P" + blob(wtf8(text)) + b"\n"
        a, ok = doc_answer(text)
        want.append(a)
        refused += 0 if ok else 1
    with open(os.path.join(out_dir, "corpus.in"), "wb") as f:
        f.write(corpus)
    with open(os.path.join(out_dir, "corpus.want"), "wb") as f:
        f.write(b"".join(blob(a) + b"\n" for a in want))
    with open(os.path.join(out_dir, "corpus.meta"), "w") as f:
        f.write("%d %d %d %d" % (len(values) + 3, len(docs), refused, seed))


def answers(path):
    data = open(path, "rb").read()
    out, i = [], 0
    while i < len(data):
        colon = data.index(b":", i)
        n = int(data[i:colon])
        out.append(data[colon + 1:colon + 1 + n])
        i = colon + 1 + n + 1
    return out


def check(out_dir):
    values, docs, refused, seed = map(int, open(os.path.join(out_dir, "corpus.meta")).read().split())
    want = answers(os.path.join(out_dir, "corpus.want"))
    got = answers(os.path.join(out_dir, "corpus.got"))
    if len(got) != len(want):
        print("json_corpus: %d answers from C++, %d from Python" % (len(got), len(want)))
        return 1
    bad = [k for k in range(len(want)) if got[k] != want[k]]
    for k in bad[:8]:
        print("answer %d differs\n  py:  %r\n  c++: %r" % (k, want[k][:400], got[k][:400]))
    print("json_corpus: seed %d, %d values in %d forms and %d documents (%d refused): %d of %d answers differ"
          % (seed, values, len(FORMS), docs, refused, len(bad), len(want)))
    return 1 if bad else 0


if __name__ == "__main__":
    if sys.argv[1] == "gen":
        gen(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 20261005)
    else:
        sys.exit(check(sys.argv[2]))
