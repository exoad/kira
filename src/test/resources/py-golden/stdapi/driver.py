#!/usr/bin/env python3
# The stdapi golden: the module's main, whose output is the C++ backend's run of it, then each
# panic as the C++ backend's run of that call alone gives it.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "lang", "stdapi.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("stdapi_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def attempt(label, f, *args):
    try:
        print(label, "->", f(*args))
    except RuntimeError as e:
        print(label, "-> panic:", str(e).replace("kira: ", "", 1))


attempt("toInt64Radix(1, 37)", m.radixOf, "1", 37)
attempt("toInt64Radix(1, 1)", m.radixOf, "1", 1)
attempt("toInt64Radix(, 0)", m.radixOf, "", 0)
attempt("toInt64Radix(z, 36)", m.radixOf, "z", 36)
attempt("sum([2147483647, 1, -5])", m.sum32, [2147483647, 1, -5])
attempt("sum([-9223372036854775808, -1])", m.sum64, [-9223372036854775808, -1])
attempt("sum([2147483647, -1, 1])", m.sum32, [2147483647, -1, 1])
attempt("sum([-2147483647, -1, -1])", m.sum32, [-2147483647, -1, -1])
