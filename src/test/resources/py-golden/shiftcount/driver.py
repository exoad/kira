#!/usr/bin/env python3
# The shiftcount golden: each shift at the edges of its count, 0 and the width less one giving
# a value and the width, a negative count or a constant outside the width stopping the program.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

path = os.path.join(sys.argv[1], "src", "app", "shiftcount.kira.py")
spec = importlib.util.spec_from_file_location("shiftcount_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def attempt(label, f, *args):
    try:
        print(label, "->", f(*args))
    except RuntimeError as e:
        print(label, "-> RuntimeError:", e)


attempt("Int8 1 << 0", m.shl8, 1, 0)
attempt("Int8 1 << 7", m.shl8, 1, 7)
attempt("Int8 1 << 8", m.shl8, 1, 8)
attempt("Int8 1 << -1", m.shl8, 1, -1)
attempt("UInt16 65535 >> 15", m.shrU16, 65535, 15)
attempt("UInt16 65535 >> 16", m.shrU16, 65535, 16)
attempt("UInt16 65535 >> 2^40", m.shrU16, 65535, 2 ** 40)
attempt("UInt16 65535 >> -2^40", m.shrU16, 65535, -(2 ** 40))
attempt("Int32 -1 >>> 31", m.ushr32, -1, 31)
attempt("Int32 -1 >>> 32", m.ushr32, -1, 32)
attempt("Int32 -1 >>> 255", m.ushr32, -1, 255)
attempt("UInt64 1 << 63", m.shlU64, 1, 63)
attempt("UInt64 1 << 64", m.shlU64, 1, 64)
attempt("Size 2^63 >> 63", m.shrSize, 2 ** 63, 63)
attempt("Size 2^63 >> 64", m.shrSize, 2 ** 63, 64)
attempt("Size 2^63 >> -1", m.shrSize, 2 ** 63, -1)
attempt("UInt32 1 << 32", m.shlBy32, 1)
attempt("Int64 -8 >> -1", m.shrByMinusOne, -8)
attempt("Int16 x = 3; x <<= 15", m.shlInPlace, 3, 15)
attempt("Int16 x = 3; x <<= 16", m.shlInPlace, 3, 16)
attempt("Int64 x = -1; x >>>= 63", m.ushrInPlace, -1, 63)
attempt("Int64 x = -1; x >>>= 64", m.ushrInPlace, -1, 64)
attempt("Int64 x = -1; x >>>= -64", m.ushrInPlace, -1, -64)
