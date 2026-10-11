#!/usr/bin/env python3
# The mathplus golden (K5): main prints cpp-golden/mathplus's lines; then mod is Python's own % to the bit over signed
# zeros, subnormals, infinities and NaN (NaN for a zero divisor, where Python raises), and hyp, angle and power are
# math.hypot, atan2 and exp (inf where exp raises), a float whether a Python caller hands ints or floats.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import math
import os
import struct
import sys

spec = importlib.util.spec_from_file_location("mathplus_kira", os.path.join(sys.argv[1], "src", "app", "mathplus_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("mathplus ->", m._k_run())
inf = math.inf
values = [0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 3.0, -3.0, 360.0, -360.0, 1e-300, -1e-300, 5e-324, -5e-324, 1e300, -1e300,
          1.7976931348623157e308, -1.7976931348623157e308, inf, -inf, math.nan, 2.675, -7.25, 0.1, 359.99999999999994]


def same(a, b):
    return (a != a and b != b) or struct.pack("<d", a) == struct.pack("<d", b)


def python_mod(a, b):
    try:
        return a % b
    except ZeroDivisionError:
        return math.nan


def python_exp(v):
    try:
        return math.exp(v)
    except OverflowError:
        return inf


pairs = [(a, b) for a in values for b in values]
checks = [
    ("mod", [(m.mod(a, b), python_mod(a, b)) for a, b in pairs]),
    ("hyp", [(m.hyp(a, b), math.hypot(a, b)) for a, b in pairs]),
    ("angle", [(m.angle(a, b), math.atan2(a, b)) for a, b in pairs]),
    ("power", [(m.power(v), python_exp(v)) for v in values + [709.78, 709.79, -745.2, -745.1]]),
]
for name, got in checks:
    print(name, "is Python's to the bit:", sum(same(a, b) for a, b in got), "of", len(got))
print("of ints:", [type(v).__name__ for v in (m.mod(5, 3), m.hyp(3, 4), m.angle(1, 1), m.power(0))], m.mod(-5, 3), m.mod(5, 0))
