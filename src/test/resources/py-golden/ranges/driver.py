#!/usr/bin/env python3
# The ranges golden: main prints cpp-golden/ranges's lines but its Box's; then a Range is the runtime's, both
# ends in it, and its steps are Python's range one past its end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

spec = importlib.util.spec_from_file_location("ranges_kira", os.path.join(sys.argv[1], "src", "app", "ranges_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("ranges ->", m._k_run())
r = m._k_Range(-2, 2)
print("steps:", list(r._k_steps()), "ends in:", r.contains(-2), r.contains(2), "past:", r.contains(3))
