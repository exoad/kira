#!/usr/bin/env python3
# main, then each panic's message, rt_test's jsonput, jsonadd, jsoncycle and jsondeep on C++.
import importlib.util
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "lang", "jsondoc.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("jsondoc_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

for call in (lambda: m.putOn([]), lambda: m.addTo({}), m.holdsItself, m.tooDeep):
    try:
        call()
        print("no panic")
    except RuntimeError as e:
        print(e)
print(m.floatOf(3), m.floatOf(2 ** 53 + 1), m.floatOf(-0.0), m.floatOf(True))
