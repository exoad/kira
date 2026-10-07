#!/usr/bin/env python3
# The externs golden: externs.kira's run, then each give* extern handed a value by the sidecar.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

path = os.path.join(sys.argv[1], "src", "app", "externs.kira.py")
spec = importlib.util.spec_from_file_location("externs_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.run()


def attempt(f, k):
    try:
        v = f(k)
        print(f.__name__, k, "->", type(v).__name__, repr(v) if len(repr(v)) < 60 else "%d deep" % depth(v))
    except RuntimeError as e:
        print(f.__name__, k, "-> panic:", e)


def depth(v):
    n = 0
    while isinstance(v, list):
        n, v = n + 1, (v[0] if v else None)
    return n


for f, keys in (
    (m.giveInt32, ("int", "none", "true", "2^31", "1.5", "str")),
    (m.giveUInt8, ("255", "-1")),
    (m.giveSize, ("2^64-1", "-1")),
    (m.giveFloat, ("1.5", "int", "2^53", "2^53+1", "true", "none")),
    (m.giveBool, ("true", "int")),
    (m.giveStr, ("str", "bytes", "none")),
    (m.giveMaybe, ("none", "int", "str")),
    (m.giveList, ("list", "tuple", "holey")),
    (m.giveBytes, ("bytes", "bytearray", "ints")),
    (m.giveMap, ("map", "intkey")),
    (m.giveTuple, ("pair", "tuple", "pairlist")),
    (m.giveLevel, ("int", "3")),
    (m.giveJson, ("json", "none", "set", "2^64", "intkeyjson", "512 deep", "513 deep")),
    (m.giveVoid, ("none", "int")),
):
    for k in keys:
        attempt(f, k)
