# The py target's runtime: the helpers generated Python calls where Python's own operators
# differ from Kira's. The emitter copies into each generated module only the definitions it
# uses (and the ones those use), in this file's order, so a generated module imports nothing
# of Kira's. Every name here starts with _k_, which no Kira name can take on this target.
# Python 3.10 is the oldest this must run on (the board's).

import math as _k_math


# A program error stops the program (Kira's panic): a bad index, a failed check, a division
# by zero. Python callers see a RuntimeError.
def _k_panic(what):
    raise RuntimeError("kira: " + what)


# Maybe<T> is None or the value itself; reading the value of None is a program error.
def _k_value(m):
    if m is None:
        _k_panic("the value of a null Maybe")
    return m


def _k_or(m, default):
    return default if m is None else m


# Int32 and Int64 overflow is a program error (D8); Int8 and Int16 wrap, as C++ narrows them
# (R1); every unsigned type wraps, Size as the 64 bits of a hosted target.
def _k_i32(v):
    if -0x80000000 <= v <= 0x7FFFFFFF:
        return v
    _k_panic("Int32 overflow")


def _k_i64(v):
    if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF:
        return v
    _k_panic("Int64 overflow")


def _k_i8(v):
    return ((v + 0x80) & 0xFF) - 0x80


def _k_i16(v):
    return ((v + 0x8000) & 0xFFFF) - 0x8000


def _k_u8(v):
    return v & 0xFF


def _k_u16(v):
    return v & 0xFFFF


def _k_u32(v):
    return v & 0xFFFFFFFF


def _k_u64(v):
    return v & 0xFFFFFFFFFFFFFFFF


# `as` from one integer type to another wraps, Int32 and Int64 included.
def _k_as_i32(v):
    return ((v + 0x80000000) & 0xFFFFFFFF) - 0x80000000


def _k_as_i64(v):
    return ((v + 0x8000000000000000) & 0xFFFFFFFFFFFFFFFF) - 0x8000000000000000


# Integer / truncates toward zero and % takes the dividend's sign, as C++ does; Python's // and
# % floor. A zero divisor, and the one signed quotient that overflows (lowest / -1), are
# program errors; x % -1 is 0.
def _k_divs(a, b, bits):
    if b == 0:
        _k_panic("integer division by zero")
    if b == -1 and a == -(1 << (bits - 1)):
        _k_panic("integer division overflows")
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def _k_divu(a, b):
    if b == 0:
        _k_panic("integer division by zero")
    return a // b


def _k_mods(a, b):
    if b == 0:
        _k_panic("integer remainder by zero")
    r = abs(a) % abs(b)
    return -r if a < 0 else r


def _k_modu(a, b):
    if b == 0:
        _k_panic("integer remainder by zero")
    return a % b


# Float64 / by zero is IEEE 754's, as on Kira's other targets: an infinity signed by both
# operands, or NaN for 0 / 0 and NaN / 0. Python raises instead.
def _k_fdiv(a, b):
    if b:
        return a / b
    if a != a or not a:
        return _k_math.nan
    return _k_math.copysign(_k_math.inf, a) * _k_math.copysign(1.0, b)


# A shift count must be in 0 until the width, as kira::shl and kira::shr check (R12): any
# other count, of any integer type, is a program error. The emitter calls this only for a
# count that is not such a constant.
def _k_count(n, bits):
    if 0 <= n < bits:
        return n
    _k_panic("shift count out of range")


# Float to integer saturates and takes NaN to 0 (D9), as kira::as does.
def _k_f2i(v, bits, signed):
    if v != v:
        return 0
    lo = -(1 << (bits - 1)) if signed else 0
    hi = (1 << (bits - 1)) - 1 if signed else (1 << bits) - 1
    if v <= float(lo):
        return lo
    if v >= float(hi):
        return hi
    return int(v)


# kira:math min and max: the first argument unless the second is strictly less or greater,
# as std::min and std::max choose (NaN and signed zeros included).
def _k_min(a, b):
    return b if b < a else a


def _k_max(a, b):
    return b if b > a else a


# kira:math's other functions are C++'s <cmath> on a double (C's Annex F), where Python's math
# raises or returns an int. sqrt of a negative number (-0.0 is not one) is NaN.
def _k_sqrt(v):
    return _k_math.nan if v < 0.0 else _k_math.sqrt(v)


# pow: Python's math.pow is C's wherever C's result is finite and at every infinity or NaN
# argument but a zero base and a -inf exponent, where Python 3.10 raises (3.11 returns C's
# +inf); it raises where C returns NaN (a negative base and a non-integer exponent) or an
# infinity (a zero base and a negative exponent, or an overflow), negative for a negative base
# and an odd integer exponent.
def _k_pow(a, b):
    try:
        return _k_math.pow(a, b)
    except (ValueError, OverflowError):
        if _k_math.isinf(b):
            return _k_math.inf
        if a < 0.0 and _k_math.fmod(b, 1.0) != 0.0:
            return _k_math.nan
        if _k_math.fmod(abs(b), 2.0) == 1.0:
            return _k_math.copysign(_k_math.inf, a)
        return _k_math.inf


# floor, ceil and round return a Float64: an infinity or NaN is itself, and the result takes
# the argument's sign, as C's does (ceil(-0.5) and round(-0.4) are -0.0). round takes a half
# away from zero, where Python's round takes it to even.
def _k_floor(v):
    if not _k_math.isfinite(v):
        return v
    return _k_math.copysign(float(_k_math.floor(v)), v)


def _k_ceil(v):
    if not _k_math.isfinite(v):
        return v
    return _k_math.copysign(float(_k_math.ceil(v)), v)


def _k_round(v):
    if not _k_math.isfinite(v):
        return v
    r = _k_math.floor(abs(v))
    if abs(v) - r >= 0.5:
        r += 1
    return _k_math.copysign(float(r), v)


# sin, cos and tan of an infinity are NaN, as C's are; Python raises.
def _k_sin(v):
    return _k_math.sin(v) if _k_math.isfinite(v) else _k_math.nan


def _k_cos(v):
    return _k_math.cos(v) if _k_math.isfinite(v) else _k_math.nan


def _k_tan(v):
    return _k_math.tan(v) if _k_math.isfinite(v) else _k_math.nan


# Text: a Bool in an interpolation or `as Str` is true or false; trace prints it as 1 or 0
# and a float as %g (D42).
def _k_btext(b):
    return "true" if b else "false"


def _k_gtext(v):
    return "%g" % v
