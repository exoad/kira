#!/usr/bin/env python3
# The numcalls golden (D83): main prints the C++ target's lines; then a Python caller hands callers the same
# numbers as ints, which answer as the doubles do, since a Num method lowers by its receiver's Kira type.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

spec = importlib.util.spec_from_file_location("numcalls_kira", os.path.join(sys.argv[1], "src", "app", "numcalls_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m._k_run()
for v, w in ((10000000000, 10000000000.0), (10**20, 1e20), (-3, 3)):
    m.callers(v, w)
