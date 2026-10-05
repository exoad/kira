#!/usr/bin/env python3
# The bebytes golden: the module's main, whose output is the C++ backend's run of it, then each
# call at the edge of its view as the C++ backend's run of that call alone gives it (a value, a
# float as trace's %g, or the panic kira::View's slice stops the program with), then the reads
# and writes against struct's big-endian ">H", ">I", ">Q" and ">d" over seeded values.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import math
import os
import random
import runpy
import struct
import sys

path = os.path.join(sys.argv[1], "src", "dbw", "bebytes.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("bebytes_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

CASES = [
    ("read16", (6, 4)),
    ("read16", (6, 5)),
    ("read16", (1, 0)),
    ("read32", (6, 2)),
    ("read32", (6, 3)),
    ("read32", (0, 0)),
    ("read64", (8, 0)),
    ("read64", (9, 2)),
    ("readF64", (8, 0)),
    ("readF64", (7, 0)),
    ("write16", (4, 2)),
    ("write16", (4, 3)),
    ("write32", (6, 3)),
    ("write64", (8, 1)),
    ("writeF64", (9, 2)),
    ("writeInside", (16, 4, 6, 2)),
    ("writeInside", (16, 4, 6, 3)),
    ("writeInside", (16, 12, 6, 0)),
]
for name, args in CASES:
    label = "%s %s" % (name, " ".join(str(a) for a in args))
    try:
        v = getattr(m, name)(*args)
        print(label, "->", "%g" % v if isinstance(v, float) else v)
    except RuntimeError as e:
        print(label, "-> panic:", str(e).replace("kira: ", "", 1))

rng = random.Random(20261005)
for k in range(5000):
    v16, v32, v64 = rng.getrandbits(16), rng.getrandbits(32), rng.getrandbits(64)
    f = struct.unpack("<d", rng.getrandbits(64).to_bytes(8, "little"))[0]
    if f != f:
        f = -0.0
    want = struct.pack(">HIQd", v16, v32, v64, f)
    got = bytes(m.put(v16, v32, v64, f))
    assert got == want, (v16, v32, v64, f)
    data = bytes(rng.getrandbits(8) for _ in range(rng.randint(8, 40)))
    at = rng.randint(0, len(data) - 8)
    assert m.get16(data, at) == struct.unpack_from(">H", data, at)[0]
    assert m.get32(data, at) == struct.unpack_from(">I", data, at)[0]
    assert m.get64(data, at) == struct.unpack_from(">Q", data, at)[0]
    g = m.getF64(data, at)
    w = struct.unpack_from(">d", data, at)[0]
    assert g == w or (g != g and w != w), (data, at)
    assert m.get64(memoryview(data)[at:], 0) == struct.unpack_from(">Q", data, at)[0]
print("put and the gets against struct's big-endian formats: 5000 rounds, every byte and value the same")
