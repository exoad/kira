#!/usr/bin/env python3
# The bytesplus golden (K5): main prints cpp-golden/bytesplus's lines; then find is bytes.find over data and over a
# lent slice (a memoryview), crc is binascii.crc_hqx and hex is bytes.fromhex (None where it raises), each of the
# value Python gives, over every start, a sweep of data and inits and texts of each kind of space.
#
#   python driver.py <the directory kira --target py --out wrote>
import binascii
import importlib.util
import os
import random
import sys

spec = importlib.util.spec_from_file_location("bytesplus_kira", os.path.join(sys.argv[1], "src", "app", "bytesplus_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("bytesplus ->", m._k_run())
draws = random.Random(7)
datas = [b"", b"a", b"abcabc", b"\xff\xd8\x00\xff\xd9\xff\xd9", bytes(draws.randrange(3) for _ in range(64))]
patterns = [b"", b"a", b"bc", b"abc", b"\xff\xd9", b"\x00\x01", b"abcabcabc"]
found = [(m.find(bytearray(d), bytearray(p), s), d.find(p, s)) for d in datas for p in patterns for s in range(len(d) + 3)]
print("find is bytes.find:", sum(a == b for a, b in found), "of", len(found))
lent = [(m.findIn(bytearray(d), at, bytearray(p)), d[at:].find(p)) for d in datas for p in patterns for at in range(len(d) + 1)]
print("and over a lent slice:", sum(a == b for a, b in lent), "of", len(lent))
sums = [(bytes(draws.randrange(256) for _ in range(draws.randrange(40))), draws.randrange(65536)) for _ in range(300)]
crcs = [(m.crc(bytearray(d), init), binascii.crc_hqx(d, init)) for d, init in sums]
print("crc is binascii.crc_hqx:", sum(a == b for a, b in crcs), "of", len(crcs))


def fromhex(text):
    try:
        return bytes.fromhex(text)
    except ValueError:
        return None


texts = ["", "00", "0a0B", "0a 0b", " 0a ", "0a0", "0 a", "zz", "0x10", "0A 0B", "0a 0b", "٠١"]
texts += ["0a" + chr(c) + "0b" for c in range(33)] + [chr(c) + "ff" + chr(c) for c in range(33)]
got = [(m.hex(t), fromhex(t)) for t in texts]
print("hex is bytes.fromhex:", sum((a is None and b is None) or (a is not None and bytes(a) == b) for a, b in got), "of", len(got))
print("of the types:", type(m.hex("0a")).__name__, m.hex("0"), type(m.find(bytearray(b"ab"), bytearray(b"b"), 0)).__name__, type(m.crc(bytearray(b"x"), 1)).__name__)
