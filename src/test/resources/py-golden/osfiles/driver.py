#!/usr/bin/env python3
# The osfiles golden (K3, K4): osfiles2's main, whose source and lines are cpp-golden/osfiles2's, osfiles' py-only one,
# then what the values are to Python, a reason one module leaves and another reads (D86), and a rename a test fails (R1).
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

out = sys.argv[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name + "_kira", os.path.join(out, "src", "app", name + "_kira.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


both = load("osfiles2")
py = load("osfiles")
sys.argv = [os.path.join(out, "osfiles2")]
print("osfiles2 ->", both._k_run())
print("osfiles ->", py._k_run())
d = os.path.join(out, "osfiles2.d")
p = os.path.join(d, "b.txt")
nope = os.path.join(d, "nope")
head = both.head(p, 3)
print(type(head).__name__, bytes(head), type(both.changed(p)).__name__, both.head(nope, 1), both.changed(nope))
print("one module's failure, another's reason:", both.missing(nope), py.kind(), py.reason().startswith("OSError: [Errno 2] "))
real = os.replace


def refuse(source, target):
    raise OSError("forced")


os.replace = refuse
try:
    print("os.replace refused:", both.atomic(p, "x"), py.reason(), py.kind(), [n for n in os.listdir(d) if n.endswith(".tmp")])
finally:
    os.replace = real
print("restored:", both.atomic(p, "y"), open(p, encoding="utf-8").read(), [n for n in os.listdir(d) if n.endswith(".tmp")])
