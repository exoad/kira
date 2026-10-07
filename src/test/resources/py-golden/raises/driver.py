#!/usr/bin/env python3
# The raises golden: raises.kira's main, then the throws and what passes through, as Python sees them.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "app", "raises.kira.py")
m = runpy.run_path(path, run_name="__main__")
Error = sys.modules["kira:errors"].Error


def attempt(label, f, *args):
    try:
        print(label, "->", repr(f(*args)))
    except Error as e:
        print(label, "-> Kira throw:", e.args[0], "| cause:", type(e.__cause__).__name__)
    except RuntimeError as e:
        print(label, "-> panic:", e)
    except BaseException as e:
        print(label, "-> passed through:", type(e).__name__, e)


attempt("readText", m["readText"], "absent.txt")
attempt("lookup", m["lookup"], "absent")
attempt("escape value", m["escape"], "value")
attempt("escape interrupt", m["escape"], "interrupt")
attempt("escape exit", m["escape"], "exit")
attempt("wrongResult", m["wrongResult"])
