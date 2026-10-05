#!/usr/bin/env python3
# The shortview golden: each call at the edge of its view, as the C++ backend's run of that call
# alone in main gives it: a value (a float as trace's %g), or the panic kira::View's check
# stops the program with. A Kira panic is a RuntimeError on the py target, and an index past the
# end of a View, a MutView or a List is Python's IndexError, which is Kira's index panic.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

path = os.path.join(sys.argv[1], "src", "dbw", "shortview.kira.py")
spec = importlib.util.spec_from_file_location("shortview_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

CASES = [
    ("read16", (6, 4)),
    ("read16", (6, 5)),
    ("read16", (6, 7)),
    ("read32", (6, 2)),
    ("read32", (6, 3)),
    ("read32", (0, 0)),
    ("read64", (8, 0)),
    ("read64", (9, 2)),
    ("readF64", (8, 0)),
    ("readF64", (7, 0)),
    ("write32", (6, 2)),
    ("write32", (6, 3)),
    ("write64", (8, 0)),
    ("write64", (10, 3)),
    ("writeInside", (16, 4, 4, 2)),
    ("writeInside", (16, 4, 4, 3)),
    ("tail", (5, 5)),
    ("tail", (5, 6)),
    ("part", (5, 2, 3)),
    ("part", (5, 2, 4)),
    ("part", (5, 6, 0)),
    ("peek", (6, 2, 3)),
    ("peek", (6, 2, 4)),
    ("poke", (8, 2, 3, 2)),
    ("poke", (8, 2, 3, 3)),
    ("peekInts", (5, 1, 3)),
    ("peekInts", (5, 1, 4)),
]

if __name__ == "__main__":
    for name, args in CASES:
        label = "%s(%s)" % (name, ", ".join(str(a) for a in args))
        try:
            v = getattr(m, name)(*args)
            print(label, "->", "%g" % v if isinstance(v, float) else v)
        except RuntimeError as e:
            print(label, "-> panic:", str(e).replace("kira: ", "", 1))
        except IndexError:
            print(label, "-> panic: index out of range")
