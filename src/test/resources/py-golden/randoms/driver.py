#!/usr/bin/env python3
# The randoms golden (K6): main, then a Random draws random.Random's of its seed, draw for draw; one Python made is a
# Random (critique 21), advanced in place; a draw with a bad argument panics.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import sys

spec = importlib.util.spec_from_file_location("randoms_kira", os.path.join(sys.argv[1], "src", "app", "randoms_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("randoms ->", m._k_run())


def kinds(r, py):
    if py:
        return [[r.random() for _ in range(50)], [r.uniform(-3.5, 2.25) for _ in range(50)], [r.randrange(7) for _ in range(50)],
                [r.randrange(10**18) for _ in range(20)], [r.randint(-3, 3) for _ in range(50)], [r.randint(-(1 << 63), (1 << 63) - 1) for _ in range(20)],
                [r.getrandbits(k) for k in range(64)], [r.uniform(5.0, 5.0)], [r.random()]]
    return [m.floats(r, 50), m.uniforms(r, 50, -3.5, 2.25), m.belows(r, 50, 7), m.belows(r, 20, 10**18), m.betweens(r, 50, -3, 3),
            m.betweens(r, 20, -(1 << 63), (1 << 63) - 1), [m.bitsOf(r, 1, k)[0] for k in range(64)], m.uniforms(r, 1, 5.0, 5.0), m.floats(r, 1)]


seeds = [0, 1, 7, -7, 12345, 1 << 62, -(1 << 63), (1 << 63) - 1]
same = sum(kinds(m.seeded(s), False) == kinds(random.Random(s), True) for s in seeds)
print("newRandom draws random.Random's, every kind in turn:", same, "of", len(seeds))
own = random.Random(99)
mine = kinds(own, False)
print("a random.Random of Python's own is a Random:", mine == kinds(random.Random(99), True), type(m.seeded(1)) is random.Random)
ref = random.Random(99)
kinds(ref, True)
print("and is advanced in place:", own.random() == ref.random())
for what, call in [("below(0)", lambda: m.belows(random.Random(1), 1, 0)), ("below(-1)", lambda: m.belows(random.Random(1), 1, -1)),
                   ("between(2, 1)", lambda: m.betweens(random.Random(1), 1, 2, 1)), ("bits(64)", lambda: m.bitsOf(random.Random(1), 1, 64)),
                   ("bits(-1)", lambda: m.bitsOf(random.Random(1), 1, -1))]:
    try:
        call()
        print(what, "returned")
    except RuntimeError as e:
        print(what + ":", e)
print("of the types:", [type(v[0]).__name__ for v in kinds(m.seeded(3), False)])
