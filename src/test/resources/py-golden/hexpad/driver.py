#!/usr/bin/env python3
# The hexpad golden: the module's main, whose output is the C++ backend's run of it, then each
# function against what it ports: Python's f"0x{v:04X}", '0x%02X', f"{v:04x}" and f"{v:08x}" over
# every UInt16 and UInt8 and seeded UInt32s, and dbw_gen.py's byte_table and crc16 (bibo's
# tools/dbw_gen.py at master deb187c, copied below unchanged, INDENT given) over seeded bytes.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dbw", "hexpad.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("hexpad_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: dbw_gen.py as written by hand -------------------------------------------------
INDENT = 4


def byte_table(ind, decl, data):
    out = [ind + decl + ' =', ind + '{']
    for i in range(0, len(data), 12):
        out.append(ind + ' ' * INDENT + ', '.join('0x%02X' % b for b in data[i:i + 12]) + ',')
    out.append(ind + '};')
    return out


def crc16(data, poly, init, xorout):
    crc = init
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ poly) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc ^ xorout


# ---- the cases ---------------------------------------------------------------------------------
for v in range(0x10000):
    assert m.hex4(v) == f"0x{v:04X}", v
    assert m.crcLine(v) == f"0x{v:04x}", v
    assert m.usbIds(v, 0xFFFF - v) == f"{v:04x}:{0xFFFF - v:04x}", v
for b in range(0x100):
    assert m.hex2(b) == "0x%02X" % b, b
rng = random.Random(20261005)
for k in range(20000):
    v = rng.getrandbits(32) >> rng.randint(0, 31)
    assert m.build(v) == f"build {v:08x}", v
print("hex4, crcLine and usbIds against Python's formats: every UInt16 the same")
print("hex2 against '0x%02X': every UInt8 the same; build against f\"{id:08x}\": 20000 UInt32s the same")
tables = 0
crcs = 0
for k in range(400):
    data = bytes(rng.getrandbits(8) for _ in range(rng.randint(0, 60)))
    ind = " " * rng.randint(0, 8)
    assert m.byteTable(ind, " " * INDENT, "constexpr UInt8 T[%d]" % len(data), data) == \
        byte_table(ind, "constexpr UInt8 T[%d]" % len(data), data), data
    tables += 1
    poly, init, xorout = rng.getrandbits(16), rng.getrandbits(16), rng.getrandbits(16)
    assert m.crc16(data, poly, init, xorout) == crc16(data, poly, init, xorout), data
    crcs += 1
print("byteTable against byte_table: %d tables, every line the same" % tables)
print("crc16 against dbw_gen's crc16: %d inputs and parameters, every CRC the same" % crcs)
