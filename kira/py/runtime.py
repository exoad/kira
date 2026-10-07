# The py target's runtime: the helpers generated Python calls where Python's own operators
# differ from Kira's. The emitter copies into each generated module only the definitions it
# uses (and the ones those use), in this file's order, so a generated module imports nothing
# of Kira's. Every name here starts with _k_, which no Kira name can take on this target.
# Python 3.10 is the oldest this must run on (the board's).

import builtins as _k_builtins
import collections as _k_collections
import functools as _k_functools
import importlib.util as _k_importlib
import inspect as _k_inspect
import itertools as _k_itertools
import json as _k_json
import math as _k_math
import operator as _k_operator
import os as _k_os
import re as _k_re
import struct as _k_struct
import sys as _k_sys
import threading as _k_threading
import time as _k_time
import traceback as _k_traceback


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


_k_map = _k_builtins.map


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


# fmod (D60): math.fmod is C's, but raises where C returns NaN (a zero divisor, an infinite dividend).
def _k_fmod(a, b):
    try:
        return _k_math.fmod(a, b)
    except ValueError:
        return _k_math.nan


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


_k_digits = "0123456789abcdefghijklmnopqrstuvwxyz"


def _k_tointr(s, radix):
    if not 2 <= radix <= 36:
        _k_panic("radix out of range")
    body = s[1:] if s[:1] in ("+", "-") else s
    if not body or not body.isascii() or body.lower().strip(_k_digits[:radix]):
        return None
    body = body.lstrip("0")
    if len(body) > 64:
        return None
    v = int(body or "0", radix)
    v = -v if s[0] == "-" else v
    return v if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF else None


# splitWhitespace (D57): the six of C's isspace, where str.split() also takes \x1c-\x1f and Unicode spaces.
_k_words = _k_re.compile(r"[^ \t\n\r\v\f]+")


# Char.isWhitespace and isLetter (D59): ASCII only, where str.isspace() and isalpha() are Unicode's.
_k_spaces = _k_builtins.frozenset((9, 10, 11, 12, 13, 32))


_k_letters = _k_builtins.frozenset(range(65, 91)) | _k_builtins.frozenset(range(97, 123))


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


def _k_pop(s):
    return s.pop() if s else None


def _k_peek(s):
    return s[-1] if s else None


def _k_popleft(q):
    return q.popleft() if q else None


def _k_peekleft(q):
    return q[0] if q else None


def _k_dqset(q, v):
    if q is not v:
        q.clear()
        q.extend(v)


# List.joinToString (D58): Kira evaluates the List before the separator.
def _k_join(xs, sep):
    return sep.join(xs)


# List.sort, minOrNull and maxOrNull (D61) take Python's order, which is Kira's, except for a
# Float64 list holding a zero or a NaN: Kotlin's compareTo puts -0.0 below 0.0 and NaN last.
def _k_fkey(v):
    return (1,) if v != v else (0, v, _k_math.copysign(1.0, v))


def _k_ftotal(xs, t):
    return t == "Float64" and (0.0 in xs or _k_builtins.any(_k_builtins.map(_k_math.isnan, xs)))


def _k_sort(xs, t):
    if _k_ftotal(xs, t):
        xs.sort(key=_k_fkey)
    elif isinstance(xs, bytearray):
        s = list(xs)
        s.sort()
        xs[:] = s
    else:
        xs.sort()


def _k_minof(xs, t):
    if not _k_ftotal(xs, t):
        return min(xs, default=None)
    top = max(xs, key=_k_fkey)
    return top if top != top else min(xs, key=_k_fkey)


def _k_maxof(xs, t):
    return max(xs, key=_k_fkey) if _k_ftotal(xs, t) else max(xs, default=None)


# List.sum (D61): a Float64 adds left to right as C++ does, where 3.12's sum() compensates.
_k_widths = {"Int8": (8, True), "Int16": (16, True), "UInt8": (8, False), "UInt16": (16, False), "UInt32": (32, False), "UInt64": (64, False), "Size": (64, False)}


def _k_sum(xs, t):
    if t == "Float64":
        return _k_functools.reduce(_k_operator.add, xs, 0.0)
    if t != "Int32" and t != "Int64":
        bits, signed = _k_widths[t]
        v = _k_builtins.sum(xs) & ((1 << bits) - 1)
        return v - (1 << bits) if signed and v >> (bits - 1) else v
    top = 1 << (31 if t == "Int32" else 63)
    n = len(xs)
    # No partial sum leaves n * min .. n * max, so most lists need no partial sums at all.
    if not xs or (n * max(max(xs), 0) < top and n * min(min(xs), 0) >= -top):
        return _k_builtins.sum(xs)
    total = 0
    at = 0
    while at < n:
        sums = list(_k_itertools.accumulate(xs[at:at + 4096], initial=total))
        if min(sums) < -top or max(sums) >= top:
            _k_panic(t + " overflow")
        total = sums[-1]
        at += 4096
    return total


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


# kira:json (D63): a Json is the value json.loads gives, None for JSON null. bool is an int in
# Python, so a kind is its exact class.
def _k_jasbool(j):
    return j if j.__class__ is bool else None


def _k_jasint(j):
    return j if j.__class__ is int else None


def _k_jasfloat(j):
    return float(j) if j.__class__ is float or j.__class__ is int else None


def _k_jasstr(j):
    return j if j.__class__ is str else None


def _k_jget(j, k):
    return j.get(k) if j.__class__ is dict else None


def _k_jat(j, i):
    return j[i] if j.__class__ is list and i < len(j) else None


def _k_jhas(j, k):
    return j.__class__ is dict and k in j


def _k_jkeys(j):
    return list(j) if j.__class__ is dict else []


def _k_jsize(j):
    return len(j) if j.__class__ is list or j.__class__ is dict else 0


def _k_jput(j, k, v):
    if j.__class__ is not dict:
        _k_panic("Json.put on a Json that is no object")
    j[k] = v


def _k_jadd(j, v):
    if j.__class__ is not list:
        _k_panic("Json.add on a Json that is no array")
    j.append(v)


def _k_jmaybef(m):
    return None if m is None else float(m)


def _k_jdump(j):
    return _k_jtext(j, None, True)


def _k_jpretty(j, indent, ascii):
    return _k_jtext(j, indent, ascii)


def _k_jtext(j, indent, ascii):
    try:
        text = _k_json.dumps(j, indent=indent, ensure_ascii=ascii)
    except (ValueError, _k_builtins.RecursionError):
        text = None
    if text is None or (text.count("[") + text.count("{") > 512 and not _k_jsound(j, False)):
        _k_panic(_k_jfault(j) or "Nesting deeper than 512")
    return text


# What kira::json's writer stops at first, in its order: a container 513 deep or one inside itself.
def _k_jfault(j):
    if j.__class__ is not list and j.__class__ is not dict:
        return None
    above = {_k_builtins.id(j)}
    stack = [(_k_builtins.iter(j.values() if j.__class__ is dict else j), j)]
    while stack:
        for x in stack[-1][0]:
            if x.__class__ is list or x.__class__ is dict:
                if len(stack) == 512:
                    return "Nesting deeper than 512"
                if _k_builtins.id(x) in above:
                    return "a Json that holds itself"
                if x:
                    above.add(_k_builtins.id(x))
                    stack.append((_k_builtins.iter(x.values() if x.__class__ is dict else x), x))
                    break
        else:
            above.discard(_k_builtins.id(stack.pop()[1]))
    return None


# No container 513 deep in a value without cycles and, with ints, no integer outside Int64.
def _k_jsound(v, ints):
    if v.__class__ is int:
        return not ints or -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF
    level = [v] if v.__class__ is list or v.__class__ is dict else []
    for _ in _k_builtins.range(512):
        if not level:
            return True
        nxt = []
        add = nxt.append
        for c in level:
            for x in (c.values() if c.__class__ is dict else c):
                t = x.__class__
                if t is dict or t is list:
                    add(x)
                elif ints and t is int and not -0x8000000000000000 <= x <= 0x7FFFFFFFFFFFFFFF:
                    return False
        level = nxt
    return not level


_k_jstate = _k_threading.local()


_k_jdecoder = _k_json.JSONDecoder()


# Json.parse (D66): json.loads when nothing is 513 deep nor an integer outside Int64, else the text
# read again to fail where and as kira::json's scanner does.
def _k_jparse(text):
    _k_jstate.error = ""
    try:
        v = _k_jdecoder.decode(text)
    except (ValueError, _k_builtins.RecursionError):
        return _k_jstrict(text)
    return v if _k_jsound(v, True) else _k_jstrict(text)


class _k_JRange(_k_builtins.Exception):
    pass


def _k_jint64(s):
    if len(s) < 21:
        v = int(s)
        if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF:
            return v
    raise _k_JRange


_k_jbrackets = _k_re.compile(r'"(?:[^"\\]|\\.)*"?|[\[\]{}]', _k_re.S)


_k_jnumbers = _k_re.compile(r'"(?:[^"\\]|\\.)*"?|-?(?:0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?', _k_re.S)


# Where kira::json's scanner stops at a bracket 513 deep, before end; -1 when it does not.
def _k_jdeep(text, end):
    if text.count("[", 0, end) + text.count("{", 0, end) <= 512:
        return -1
    depth = 0
    for m in _k_jbrackets.finditer(text, 0, end):
        c = m.group()
        if c == "[" or c == "{":
            depth += 1
            if depth > 512:
                return m.start()
        elif c == "]" or c == "}":
            depth -= 1
    return -1


def _k_jbig(text):
    for m in _k_jnumbers.finditer(text):
        t = m.group()
        if t[0] != '"' and m.group(1) is None and m.group(2) is None:
            try:
                _k_jint64(t)
            except _k_JRange:
                return m.start()
    return 0


def _k_jstrict(text):
    why = "Nesting deeper than 512"
    try:
        v = _k_json.loads(text, parse_int=_k_jint64)
        at = _k_jdeep(text, len(text))
        if at < 0:
            return v
    except _k_json.JSONDecodeError as e:
        at = _k_jdeep(text, e.pos)
        if at < 0:
            _k_jstate.error = str(e)
            return None
    except _k_JRange:
        big = _k_jbig(text)
        at = _k_jdeep(text, big)
        if at < 0:
            why, at = "Integer out of Int64 range", big
    except _k_builtins.RecursionError:
        at = _k_jdeep(text, len(text))
        if at < 0:
            raise
    _k_jstate.error = str(_k_json.JSONDecodeError(why, text, at))
    return None


def _k_jerror():
    return _k_builtins.getattr(_k_jstate, "error", "")


# An uncaught throw at main (D73) ends the program with C++'s runMain status, 70; the traceback,
# with the cause of a raises= throw, stays on stderr.
def _k_main(main):
    try:
        return main()
    except _k_Error:
        _k_sys.stdout.flush()
        _k_traceback.print_exc()
        raise _k_builtins.SystemExit(70)


# kira:time sleepMs (D72): at least ms milliseconds; zero or less returns at once.
def _k_sleep(ms):
    if ms > 0:
        _k_time.sleep(ms / 1000)


# The sidecar X_ext.py beside X.kira (D67), loaded once per process by its real path; an extern it
# does not define as a function of the extern's arity is a panic here, at import.
def _k_extern(here, path, names):
    m = _k_use(here, path)
    for n, k in names:
        try:
            _k_inspect.signature(m.__dict__.get(n)).bind(*range(k))
        except _k_builtins.TypeError:
            _k_panic("%s defines no function %s taking %d argument%s" % (_k_os.path.basename(path), n, k, "" if k == 1 else "s"))
        except ValueError:
            # A builtin such as time.sleep has no signature to check.
            pass
    return m


# raises= (D69): each name resolves in the sidecar's globals, then the builtins, dotted by attribute,
# to an Exception that catches neither Kira's throw nor its panic, nor follows one that catches it.
def _k_raises(ext, where, names):
    out = []
    for n in names:
        parts = n.split(".")
        c = (ext.__dict__ if ext is not None else {}).get(parts[0], _k_builtins.__dict__.get(parts[0]))
        for p in parts[1:]:
            c = _k_builtins.getattr(c, p, None)
        if not (isinstance(c, _k_builtins.type) and _k_builtins.issubclass(c, _k_builtins.Exception)):
            _k_panic("%s raises %s, which is no Exception class" % (where, n))
        if _k_builtins.issubclass(RuntimeError, c) or _k_builtins.issubclass(_k_Error, c):
            _k_panic("%s raises %s, which would catch Kira's own throw or panic" % (where, n))
        for d, m in _k_builtins.zip(out, names):
            if _k_builtins.issubclass(c, d):
                _k_panic("%s raises %s after %s, which catches it first" % (where, n, m))
        out.append(c)
    return _k_builtins.tuple(out)


# A listed exception is the Kira throw "Name: str(e)" (D69), Name the first listed class it is.
def _k_thrown(e, classes, names):
    for c, n in _k_builtins.zip(classes, names):
        if isinstance(e, c):
            text = str(e)
            raise _k_Error(n + ": " + text if text else n) from e


def _k_shown(v):
    r = _k_builtins.repr(v)
    return (r if len(r) <= 40 else r[:37] + "...") + " (" + _k_builtins.type(v).__name__ + ")"


# What Python hands back (D68), checked against the declared type's spec and rebuilt where Kira holds
# a value, so a list, dict or bytes the sidecar keeps is never Kira's; an int is a Float64 if exact.
def _k_check(v, spec, where, declared):
    k = spec[0]
    c = v.__class__
    if k == "I":
        if c is int and spec[1] <= v <= spec[2]:
            return v
    elif k == "F":
        if c is float:
            return v
        if c is int and -0x20000000000000 <= v <= 0x20000000000000:
            return float(v)
    elif k == "B":
        if c is bool:
            return v
    elif k == "S":
        if c is str:
            return v
    elif k == "Y":
        if c is bytes or c is bytearray:
            return bytearray(v)
    elif k == "M":
        return None if v is None else _k_check(v, spec[1], where, declared)
    elif k == "L":
        if c is list:
            return [_k_check(x, spec[1], where, declared) for x in v]
    elif k == "D":
        if c is dict:
            return {_k_check(a, spec[1], where, declared): _k_check(b, spec[2], where, declared) for a, b in v.items()}
    elif k == "T":
        if c is _k_builtins.tuple and len(v) == len(spec) - 1:
            return _k_builtins.tuple([_k_check(x, s, where, declared) for x, s in _k_builtins.zip(v, spec[1:])])
    elif k == "E":
        if c is int and v in spec[1]:
            return v
    elif k == "J":
        if _k_jcheck(v):
            return v
    elif k == "O":
        if v is not None:
            return v
    elif k == "V":
        if v is None:
            return v
    _k_panic("%s gave %s where %s was declared" % (where, _k_shown(v), declared))


# A Json from Python: only what json.loads makes, every int in Int64, no container 513 deep.
def _k_jcheck(v):
    level, depth = [v], 0
    while level:
        nxt = []
        for x in level:
            t = x.__class__
            if t is dict or t is list:
                if depth == 512 or t is dict and not _k_builtins.all([k.__class__ is str for k in x]):
                    return False
                nxt.extend(x.values() if t is dict else x)
            elif not (t is str or t is float or t is bool or x is None or t is int and -0x8000000000000000 <= x <= 0x7FFFFFFFFFFFFFFF):
                return False
        level, depth = nxt, depth + 1
    return True


# A View<UInt8> or MutView<UInt8> lent to Python for the call only (D68): released after it, so a
# sidecar that keeps it fails where it uses it, and the bytearray can grow again.
class _k_lend:
    __slots__ = ("b", "m")

    def __init__(self, b, writable):
        self.b = memoryview(b)
        self.m = self.b if writable else self.b.toreadonly()

    def __enter__(self):
        return self.m

    def __exit__(self, *exc):
        self.m.release()
        self.b.release()


# A callback Python runs (D76) ends the process on what no Kira try below it can catch: a panic
# as C++'s abort does, exit with its code, a throw with 70 (D73) unless owner is this thread.
def _k_callback(f, args, owner):
    try:
        return f(*args)
    except _k_Error:
        if owner == _k_threading.get_ident():
            raise
        _k_end(70, True)
    except _k_builtins.SystemExit as e:
        _k_end(e.code if e.code.__class__ is int else 0 if e.code is None else 1, False)
    except _k_builtins.KeyboardInterrupt:
        raise
    except _k_builtins.BaseException:
        _k_end(None, True)


# Abort is 3 on Windows, where os.abort could raise Windows Error Reporting's dialog.
def _k_end(code, shown):
    try:
        _k_sys.stdout.flush()
        if shown:
            _k_traceback.print_exc()
        _k_sys.stderr.flush()
    finally:
        if code is None and _k_os.name != "nt":
            _k_os.abort()
        _k_os._exit(3 if code is None else code)


# An Fx handed to a sidecar (D75): what Python passes in is checked as a result is (D68), a
# List<UInt8> result goes back as bytes, and the extern call it was handed to owns it (D76).
class _k_fx:
    __slots__ = ("f", "specs", "where", "out", "owner")

    def __init__(self, f, specs, where, out):
        self.f = f
        self.specs = specs
        self.where = where
        self.out = out
        self.owner = None

    def __enter__(self):
        self.owner = _k_threading.get_ident()
        return self

    def __exit__(self, *exc):
        self.owner = None

    def __call__(self, *args):
        return _k_callback(self.run, args, self.owner)

    def run(self, *args):
        n = len(self.specs)
        if len(args) != n:
            _k_panic("%s was called with %d argument%s where %d %s declared" % (self.where, len(args), "" if len(args) == 1 else "s", n, "was" if n == 1 else "were"))
        r = self.f(*[_k_check(a, s, self.where, d) for a, (s, d) in _k_builtins.zip(args, self.specs)])
        return r if self.out is None else self.out(r)


# Ref<T> (D74): one cell, shared by every copy of the reference, a closure's capture included.
class _k_Ref:
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value


# kira:sync (D77). A Thread is a daemon thread started at once, its body a callback Python runs
# (D76); dropping the handle never joins it, so a program joins what must finish.
class _k_Thread:
    __slots__ = ("t", "stop")

    def __init__(self, name, body):
        self.stop = False
        self.t = _k_threading.Thread(target=_k_callback, args=(body, (), None), name=name, daemon=True)
        self.t.start()

    def stopRequested(self):
        return self.stop

    def requestStop(self):
        self.stop = True

    def join(self):
        if self.t is _k_threading.current_thread():
            _k_panic("Thread.join from its own body: a thread cannot wait for itself")
        self.t.join()


# A wait as kira::sync's: negative without limit, zero a check, else in waits of at most an hour,
# as Condition.wait refuses one past threading.TIMEOUT_MAX.
def _k_waitfor(cv, pred, ms):
    if ms < 0:
        return cv.wait_for(pred)
    end = _k_time.monotonic() + ms / 1000
    while not pred():
        left = end - _k_time.monotonic()
        if left <= 0:
            return pred()
        cv.wait(min(left, 3600.0))
    return True


# The body gets the value itself, a list, dict or struct it writes in place, under the lock.
class _k_Mutex:
    __slots__ = ("value", "cv")

    def __init__(self, value):
        self.value = value
        self.cv = _k_threading.Condition(_k_threading.Lock())

    def lock(self, body):
        with self.cv:
            body(self.value)
            self.cv.notify_all()

    def waitUntil(self, pred, ms):
        with self.cv:
            return _k_waitfor(self.cv, lambda: pred(self.value), ms)


# Every write under one lock, reentrant so an onSignal handler can store while its thread holds it;
# an integer add wraps as C++'s fetch_add does.
class _k_Atomic:
    __slots__ = ("v", "wrap", "lk")

    def __init__(self, value, wrap):
        self.v = value
        self.wrap = wrap
        self.lk = _k_threading.RLock()

    def load(self):
        return self.v

    def store(self, value):
        with self.lk:
            self.v = value

    def add(self, delta):
        with self.lk:
            self.v = self.v + delta if self.wrap is None else self.wrap(self.v + delta)
            return self.v

    def swap(self, value):
        with self.lk:
            old = self.v
            self.v = value
            return old

    def compareSwap(self, expected, desired):
        with self.lk:
            if self.v != expected:
                return False
            self.v = desired
            return True


class _k_Queue:
    __slots__ = ("q", "cv", "closed")

    def __init__(self):
        self.q = _k_collections.deque()
        self.cv = _k_threading.Condition(_k_threading.Lock())
        self.closed = False

    def push(self, value):
        with self.cv:
            if not self.closed:
                self.q.append(value)
                self.cv.notify()

    def pop(self, ms):
        with self.cv:
            _k_waitfor(self.cv, lambda: self.closed or len(self.q) > 0, ms)
            return self.q.popleft() if self.q else None

    def close(self):
        with self.cv:
            self.closed = True
            self.cv.notify_all()

    def isClosed(self):
        return self.closed

    def size(self):
        return len(self.q)
