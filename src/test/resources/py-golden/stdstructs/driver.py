#!/usr/bin/env python3
# The stdstructs golden: main, whose lines are the C++ backend's run of it and whose result is its exit
# code there, then what a stdlib struct is to Python (D84): the runtime's class of its name, slots in field order.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

path = os.path.join(sys.argv[1], "src", "app", "stdstructs_kira.py")
spec = importlib.util.spec_from_file_location("stdstructs_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("main ->", m._k_run())
d = m.made(4, "h")
print(type(d).__name__, type(d).__slots__, type(d.size).__name__, type(d.host).__name__, type(d.port).__name__)
print(d == m.made(4, "h"), d == m.made(4, "i"), d._k_clone() is d, d._k_clone() == d)
print(m.Seen().ready, type(m.Seen().last).__name__)
