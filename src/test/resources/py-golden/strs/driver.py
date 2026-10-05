#!/usr/bin/env python3
# The strs golden: the module's main, whose output is the C++ backend's run of it, then each
# panic as the C++ backend's run of that call alone gives it (kira::str's checks, where a Python
# slice stops short and an index past the end is Python's IndexError, Kira's index panic), then
# text past ASCII: the py target's one rule for text is that a Str counts code points, where C++
# counts the bytes of its UTF-8 (10 for "héllo €"); the case changes, trim and hashCode
# are C++'s for any text, hashCode being djb2 over the UTF-8 (C++ gives 8246534910121424176).
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "lang", "strs.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("strs_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def attempt(label, f, *args):
    try:
        print(label, "->", f(*args))
    except RuntimeError as e:
        print(label, "-> panic:", str(e).replace("kira: ", "", 1))
    except IndexError:
        print(label, "-> panic: index out of range")


attempt("substring(hello, 2, 1)", m.substringOf, "hello", 2, 1)
attempt("substring(hello, 0, 6)", m.substringOf, "hello", 0, 6)
attempt("substring(hello, 6, 6)", m.substringOf, "hello", 6, 6)
attempt("substring(hello, 5, 5)", lambda: "[" + m.substringOf("hello", 5, 5) + "]")
attempt("charAt(hello, 5)", m.charAtOf, "hello", 5)
attempt("charAt(, 0)", m.charAtOf, "", 0)

text = "héllo €"
print("code points:", m.lengthOf(text), "of", len(text.encode("utf-8")), "UTF-8 bytes")
print("substring by code point:", m.substringOf(text, 1, 2) == "é", m.charAtOf(text, 6) == "€")
print("hashCode over the UTF-8:", m.hashOf(text), m.hashOf(text) == 8246534910121424176)
print("ASCII case only:", m.upperOf("héllo été") == "HéLLO éTé",
      m.lowerOf("ÉTÉ OK") == "ÉtÉ ok")
print("trim of four only:", m.trimOf("\xa0x\xa0") == "\xa0x\xa0", m.trimOf("\x0bx\x0c") == "\x0bx\x0c")
print("strict parsers:", m.intOf("١٢"), m.intOf("1_0"), m.floatOf("1_0.5"), m.floatOf(" 2.5"),
      m.floatOf("١.5"))
