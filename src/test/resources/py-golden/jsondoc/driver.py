#!/usr/bin/env python3
# main (cpp-golden/json's run), then what Python hands the module: each panic's message, which the
# C++ runtime's is (rt_test's jsonput, jsonadd and jsoncycle), and ints given as Float64s.
#   python driver.py <kira --target py --out dir>
import importlib.util
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "lang", "jsondoc.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("jsondoc_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

for call in (lambda: m.putOn([]), lambda: m.addTo({}), m.holdsItself):
    try:
        call()
        print("no panic")
    except RuntimeError as e:
        print(e)
print(m.floatOf(3), m.floatOf(2 ** 53 + 1), m.floatOf(-0.0), m.floatOf(True))
