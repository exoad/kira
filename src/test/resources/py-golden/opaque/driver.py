#!/usr/bin/env python3
# The opaque golden: user.kira's main, then what a wrong handle or method result is.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import runpy
import sys

runpy.run_path(os.path.join(sys.argv[1], "src", "app", "user.kira.py"), run_name="__main__")
m = runpy.run_path(os.path.join(sys.argv[1], "src", "app", "opaque.kira.py"))


def attempt(label, f, *args):
    try:
        print(label, "->", repr(f(*args)))
    except RuntimeError as e:
        print(label, "-> panic:", e)


attempt("broken", m["broken"])
attempt("wrong", m["_k_o_Counter_wrong"], m["counter"]("z"))
attempt("maybeCounter('')", m["maybeCounter"], "")
