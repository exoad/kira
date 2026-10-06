# The py target's runtime: the helpers generated Python calls where Python's own operators
# differ from Kira's. The emitter copies into each generated module only the definitions it
# uses (and the ones those use), in this file's order, so a generated module imports nothing
# of Kira's. Every name here starts with _k_, which no Kira name can take on this target.
# Python 3.10 is the oldest this must run on (the board's).

import builtins as _k_builtins
import collections as _k_collections
import importlib.util as _k_importlib
import math as _k_math
import os as _k_os
import re as _k_re
import struct as _k_struct
import sys as _k_sys


# x.kira.py is no name an import statement can give: a used module is loaded by its path, once
# per process under its real path, so every module that uses it shares its globals.
def _k_key(path):
    return _k_os.path.normcase(_k_os.path.realpath(path))


def _k_use(here, path):
    key = _k_key(_k_os.path.join(_k_os.path.dirname(_k_os.path.abspath(here)), path))
    m = _k_sys.modules.get(key)
    if m is None:
        spec = _k_importlib.spec_from_file_location(key, key)
        m = _k_importlib.module_from_spec(spec)
        _k_sys.modules[key] = m
        try:
            spec.loader.exec_module(m)
        except _k_builtins.BaseException:
            del _k_sys.modules[key]
            raise
    return m


# In a use cycle the module run as __main__ must be found by the one that uses it back, not
# loaded a second time.
def _k_self():
    g = _k_self.__globals__
    m = _k_sys.modules.get(g["__name__"])
    if m is not None and m.__dict__ is g:
        _k_sys.modules.setdefault(_k_key(g["__file__"]), m)


# A field a construction leaves out (None is a Maybe's value, so it cannot say so).
_k_unset = object()


# A program error stops the program (Kira's panic): a bad index, a failed check, a division
# by zero. Python callers see a RuntimeError.
def _k_panic(what):
    raise RuntimeError("kira: " + what)


def _k_assert(ok, message):
    if not ok:
        _k_panic("assertion failed: " + message)


# kira:os exit: SystemExit, which no Kira try catches, flushes stdout as C++'s exit does.
def _k_exit(code):
    raise _k_builtins.SystemExit(code)


# Maybe<T> is None or the value itself; reading the value of None is a program error.
def _k_value(m):
    if m is None:
        _k_panic("the value of a null Maybe")
    return m


def _k_or(m, default):
    return default if m is None else m


def _k_mcopy(m, copy):
    return None if m is None else copy(m)


# enumOf<E>(raw) (D25): the first entry whose C++ value is raw, or none.
def _k_enumof(order, raw):
    for v in order:
        if v == raw:
            return v
    return None


# A throw (D41) is this class's exception, one class for every generated module so that one
# catches what another throws; a panic is a RuntimeError, which no Kira try catches.
def _k_errors():
    m = _k_sys.modules.get("kira:errors")
    if m is None:
        m = _k_builtins.type(_k_sys)("kira:errors")
        m.Error = _k_builtins.type("Error", (_k_builtins.Exception,), {"__module__": "kira:errors"})
        m = _k_sys.modules.setdefault("kira:errors", m)
    return m.Error


_k_Error = _k_errors()


def _k_throw(message):
    raise _k_Error(message)


# Result (D39) is (True, value) or (False, error).
def _k_unwrap(r):
    if not r[0]:
        _k_panic("unwrap of an error Result")
    return r[1]


def _k_unwrap_err(r):
    if r[0]:
        _k_panic("unwrapErr of a success Result")
    return r[1]


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


# A Float64 in an interpolation or `as Str` is the shortest text that reads back to it, as C++'s
# std::to_chars writes it (D50): fixed, or scientific with a two-digit exponent when that is
# shorter (fixed on a tie), a large integer in its exact digits, and any NaN nan.
def _k_ftext(v):
    if v != v:
        return "nan"
    if _k_math.isinf(v):
        return "inf" if v > 0.0 else "-inf"
    sign = "-" if _k_math.copysign(1.0, v) < 0.0 else ""
    v = abs(v)
    if v == 0.0:
        return sign + "0"
    mantissa, _, exp = ("%r" % v).partition("e")
    whole, _, frac = mantissa.partition(".")
    digits = (whole + frac).rstrip("0")
    point = len(whole) + int(exp or "0") - (len(digits) - len(digits.lstrip("0")))
    digits = digits.lstrip("0")
    if point >= len(digits):
        fixed = "%.0f" % v
    elif point > 0:
        fixed = digits[:point] + "." + digits[point:]
    else:
        fixed = "0." + "0" * -point + digits
    e = point - 1
    sci = digits[0] + ("." + digits[1:] if len(digits) > 1 else "") + ("e-" if e < 0 else "e+") + "%02d" % abs(e)
    return sign + (fixed if len(fixed) <= len(sci) else sci)


# FloatNum.fixed (D50): %.*f, whose correct rounding (a tie to even, on the double's exact value)
# is C's; places clamped to 0..9 and any NaN nan, as on the C++ target.
def _k_fixed(v, places):
    if v != v:
        return "nan"
    return "%.*f" % (min(max(places, 0), 9), v)


# IntNum.toHex (D51): lowercase and no prefix; a negative value is "-" and its magnitude's digits.
def _k_hex(v):
    return "%x" % v


# Str is a Python str, so its lengths and indices count code points where C++ counts the bytes
# of its UTF-8: the same numbers for ASCII text. substring is checked as kira::str::substring
# is, where a Python slice stops short.
def _k_substr(s, start, end):
    if start > end or end > len(s):
        _k_panic("substring out of range")
    return s[start:end]


def _k_find(s, v):
    at = s.find(v)
    return None if at < 0 else at


# split keeps empty pieces, as Python's does, and an empty delimiter gives the Str back whole,
# where Python raises.
def _k_split(s, d):
    return s.split(d) if d else [s]


# toLower and toUpper change A-Z and a-z only, as kira::str's do; Python's lower() and upper()
# change every cased character.
_k_lower = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")


_k_upper = str.maketrans("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")


# toInt64 is kira::parseInt64: an optional sign, then ASCII digits and nothing else, none past
# Int64. Python's int() also takes spaces, underscores and other scripts' digits.
def _k_toint(s):
    body = s[1:] if s[:1] in ("+", "-") else s
    if not (body.isascii() and body.isdigit()):
        return None
    body = body.lstrip("0")
    if len(body) > 19:
        return None
    v = int(body or "0")
    v = -v if s[0] == "-" else v
    return v if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF else None


# toFloat64 is std::from_chars after one leading "+": decimal text with an optional exponent, or
# inf, infinity, nan and nan(chars) in any case, and nothing else; none where the value is
# beyond a double, or rounds to zero from text that is not zero. Python's float() also takes
# spaces and underscores, returns inf and 0.0 there, and refuses nan(chars).
_k_decimal = _k_re.compile(r"[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")


_k_special = _k_re.compile(r"[+-]?(?:inf|infinity|nan(?:\([0-9A-Za-z_]*\))?)", _k_re.A | _k_re.I)


def _k_tofloat(s):
    if _k_decimal.fullmatch(s):
        v = float(s)
        if _k_math.isinf(v):
            return None
        if v == 0.0 and s.lower().partition("e")[0].strip("+-.0"):
            return None
        return v
    if _k_special.fullmatch(s):
        return float(s.partition("(")[0])
    return None


# Str.bytes (D54): the text's UTF-8, which is what a Str holds on the C++ target when its text is
# valid UTF-8 there, so the same bytes; a Char above 127 is a raw byte there and a Latin-1 code
# point here. A lone surrogate, which only hand-written Python can hand over, is encoded as it is.
def _k_utf8(s):
    return bytearray(s.encode("utf-8", "surrogatepass"))


# Str.of (D55): the text the UTF-8 holds, each maximal ill-formed subpart one U+FFFD, which is
# what kira::str::of writes for the same bytes.
def _k_strof(v):
    return bytes(v).decode("utf-8", "replace")


# hashCode is djb2 over the UTF-8 of the text, as kira::str::hashCode over its bytes, wrapped
# to 64 bits and read as an Int64: C++'s number for text that is valid UTF-8 there.
def _k_strhash(s):
    h = 5381
    for b in s.encode("utf-8", "surrogatepass"):
        h = (h * 33 + b) & 0xFFFFFFFFFFFFFFFF
    return h - 0x10000000000000000 if h >> 63 else h


# List.contains and Map.containsValue compare with ==, as kira::list::contains and kira::Map
# do: a NaN is in no List. Python's `in` takes an element that is the very object first.
def _k_contains(xs, v):
    return v == v and v in xs


# List.toArr, Arr.clone, View.toList and MutView.toList (D52): a new list of the elements, a
# bytearray of bytes (a memoryview's included).
def _k_copy(xs):
    return bytearray(xs) if isinstance(xs, (bytes, bytearray, memoryview)) else list(xs)


# Set.add and Set.remove say whether they changed the Set, as kira::Set's do.
def _k_setadd(s, v):
    if v in s:
        return False
    s[v] = None
    return True


def _k_setdel(s, v):
    if v in s:
        del s[v]
        return True
    return False


# Stack.pop and peek, Queue.dequeue and peek, Deque.popFront and popBack: none when empty.
def _k_pop(s):
    return s.pop() if s else None


def _k_peek(s):
    return s[-1] if s else None


def _k_popleft(q):
    return q.popleft() if q else None


def _k_peekleft(q):
    return q[0] if q else None


# Keeps the deque, as _k_mapset keeps the dict.
def _k_dqset(q, v):
    if q is not v:
        q.clear()
        q.extend(v)


# Keeps the dict, as `xs[:] = v` keeps a list: a mut parameter may be bound to it.
def _k_mapset(m, v):
    if m is not v:
        m.clear()
        m.update(v)


# View.from and View.slice (a List's, an Arr's, a MutView's) are checked as kira::View's are: a
# start or a length past the end is a program error, where Python's slice stops short. A view
# of bytes is a memoryview, which shares them, so a MutView<UInt8> writes them; a view of any
# other element is a copied slice, which only a View, read-only, can be.
def _k_from(v, at):
    if at > len(v):
        _k_panic("slice out of range")
    return (memoryview(v) if isinstance(v, (bytes, bytearray)) else v)[at:]


def _k_slice(v, at, n):
    if at > len(v) or n > len(v) - at:
        _k_panic("slice out of range")
    return (memoryview(v) if isinstance(v, (bytes, bytearray)) else v)[at:at + n]


# kira:bytes: n bytes little-endian at `at` of a bytearray or a memoryview, each checked as
# kira::readU32Le's b.slice(at, 4) is, so a short view is a program error.
def _k_span(b, at, n):
    if at > len(b) or n > len(b) - at:
        _k_panic("slice out of range")


def _k_rdle(b, at, n):
    _k_span(b, at, n)
    return int.from_bytes(b[at:at + n], "little")


def _k_wrle(b, at, n, v):
    _k_span(b, at, n)
    b[at:at + n] = v.to_bytes(n, "little")


def _k_rdf64(b, at):
    _k_span(b, at, 8)
    return _k_struct.unpack_from("<d", b, at)[0]


def _k_wrf64(b, at, v):
    _k_span(b, at, 8)
    _k_struct.pack_into("<d", b, at, v)


# The big-endian twins (D53), checked the same way.
def _k_rdbe(b, at, n):
    _k_span(b, at, n)
    return int.from_bytes(b[at:at + n], "big")


def _k_wrbe(b, at, n, v):
    _k_span(b, at, n)
    b[at:at + n] = v.to_bytes(n, "big")


def _k_rdf64be(b, at):
    _k_span(b, at, 8)
    return _k_struct.unpack_from(">d", b, at)[0]


def _k_wrf64be(b, at, v):
    _k_span(b, at, 8)
    _k_struct.pack_into(">d", b, at, v)
