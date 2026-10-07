#!/usr/bin/env python3
# The externload golden: each module whose sidecar does not match its externs panics at import.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

for name in ("missing", "arity", "badname", "order", "broad", "notclass", "fine"):
    path = os.path.join(sys.argv[1], "src", "app", name + ".kira.py")
    spec = importlib.util.spec_from_file_location(name + "_kira", path)
    m = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(m)
        print(name, "-> loaded")
    except RuntimeError as e:
        print(name, "->", e)

m.sleepS(0)
try:
    m.g()
except sys.modules["kira:errors"].Error as e:
    print("fine.g ->", e.args[0])
