# The py target's runtime: the helpers generated Python calls where Python's operators differ from Kira's. A
# generated module copies the definitions it uses, in this file's order, and imports nothing of Kira's. Each name
# starts with _k_, which no Kira name can take; Python 3.10 is the oldest this runs on. A helper calls a stdlib
# function through its module when it runs, so a test's patch reaches it (D88).

import argparse as _k_argparse
import binascii as _k_binascii
import builtins as _k_builtins
import collections as _k_collections
import csv as _k_csv
import datetime as _k_datetime
import errno as _k_errno
import functools as _k_functools
import glob as _k_glob
import http.client as _k_httpclient
import http.server as _k_httpserver
import importlib.util as _k_importlib
import inspect as _k_inspect
import io as _k_io
import itertools as _k_itertools
import json as _k_json
import math as _k_math
import operator as _k_operator
import os as _k_os
import random as _k_random
import re as _k_re
import select as _k_select
import shutil as _k_shutil
import signal as _k_signal
import socket as _k_socket
import struct as _k_struct
import subprocess as _k_subprocess
import sys as _k_sys
import tempfile as _k_tempfile
import textwrap as _k_textwrap
import threading as _k_threading
import time as _k_time
import traceback as _k_traceback
import urllib.error as _k_urlerror
import urllib.parse as _k_urlparse
import urllib.request as _k_urlrequest


# A used module is loaded once per process (D85): under its real path, and under the name Python's import
# finds it by, so Kira and Python share one.
def _k_key(path):
    return _k_os.path.normcase(_k_os.path.realpath(path))


# The module under a real path, unless the import that put it there failed and Python dropped its name.
def _k_held(key):
    m = _k_sys.modules.get(key)
    if m is not None and _k_sys.modules.get(m.__name__) is not m:
        del _k_sys.modules[key]
        return None
    return m


# The name a directory on sys.path or an imported package gives the file at p (a link's own place, not its
# target), when Python's import of that name finds this file.
def _k_named(p, key):
    d, base = _k_os.path.split(p)
    parts = [base[:-3]] if base.endswith(".py") else []
    roots = {_k_key(r or ".") for r in _k_sys.path if r.__class__ is str}
    while parts and parts[-1].isidentifier():
        name = ".".join(parts[::-1])
        if _k_key(d) in roots and _k_finds(name, key):
            return name
        if not _k_os.path.isfile(_k_os.path.join(d, "__init__.py")):
            return None
        d, pkg = _k_os.path.split(d)
        parts.append(pkg)
    return None


def _k_finds(name, key):
    if "." in name and name.rpartition(".")[0] not in _k_sys.modules:
        return False
    try:
        spec = _k_importlib.find_spec(name)
    except (_k_builtins.ImportError, ValueError):
        return False
    return spec is not None and spec.origin is not None and _k_key(spec.origin) == key


def _k_use(here, path):
    p = _k_os.path.abspath(_k_os.path.join(_k_os.path.dirname(here), path))
    key = _k_key(p)
    m = _k_held(key)
    if m is not None:
        return m
    name = _k_named(p, key)
    if name is not None and name in _k_sys.modules:
        m = _k_sys.modules[key] = _k_sys.modules[name]
        return m
    spec = _k_importlib.spec_from_file_location(name or key, p)
    m = _k_importlib.module_from_spec(spec)
    names = [key] if name is None else [key, name]
    for n in names:
        _k_sys.modules[n] = m
    try:
        spec.loader.exec_module(m)
    except _k_builtins.BaseException:
        for n in names:
            if _k_sys.modules.get(n) is m:
                del _k_sys.modules[n]
        raise
    parent, _, child = (name or "").rpartition(".")
    if parent:
        _k_builtins.setattr(_k_sys.modules[parent], child, m)
    return m


# Every module registers itself under its real path as it loads (D85): a use of it finds the one Python
# imported, or the one run as __main__, instead of loading it again. Code exec'd without a file has no path.
def _k_self():
    g = _k_self.__globals__
    m = _k_sys.modules.get(g.get("__name__"))
    f = g.get("__file__")
    if f and m is not None and m.__dict__ is g and _k_held(_k_key(f)) is None:
        _k_sys.modules[_k_key(f)] = m


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


def _k_map(f, xs):
    return _k_builtins.map(f, xs)


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
    return m


_k_Error = _k_errors().Error


# Why the last stdlib call that can fail did (Json.error's text, ...): per thread, and one for every generated
# module, as C++ keeps one per thread for the process.
_k_reasons = _k_errors().__dict__.setdefault("reasons", _k_threading.local())


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


# A Float64's hashCode, kira::hashCode's: the bits of its double, an int a Python caller handed over included.
def _k_fbits(v):
    return _k_struct.unpack("<q", _k_struct.pack("<d", v))[0]


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


# exp past the largest double is C's inf, where Python's math raises.
def _k_exp(v):
    try:
        return _k_math.exp(v)
    except OverflowError:
        return _k_math.inf


# floorMod is Python's own float %, NaN for a zero divisor, as C++'s fmod gives, where Python raises.
def _k_floormod(a, b):
    return float(a) % b if b else _k_math.nan


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


# findBytes is bytes.find over the view, a memoryview's bytes copied first (it has no find).
def _k_findbytes(data, pattern, start):
    return (data if data.__class__ is bytearray else bytes(data)).find(pattern, start)


def _k_fromhex(text):
    try:
        return bytearray.fromhex(text)
    except ValueError:
        return None


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


_k_jdecoder = _k_json.JSONDecoder()


# Json.parse (D66): json.loads when nothing is 513 deep nor an integer outside Int64, else the text
# read again to fail where and as kira::json's scanner does.
def _k_jparse(text):
    _k_reasons.json = ""
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
            _k_reasons.json = str(e)
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
    _k_reasons.json = str(_k_json.JSONDecodeError(why, text, at))
    return None


def _k_jerror():
    return _k_builtins.getattr(_k_reasons, "json", "")


# Under main a throw ends the program with runMain's 70 and its traceback (D73), a panic aborts (D81)
# and exit ends it with its code, each at once as C++'s exit and abort do, waiting on no thread.
def _k_main(main, *args):
    try:
        return main(*args)
    except _k_Error:
        _k_end(70, True)
    except _k_builtins.SystemExit as e:
        _k_end(e.code if e.code.__class__ is int else 0 if e.code is None else 1, False)
    except _k_builtins.KeyboardInterrupt:
        raise
    except _k_builtins.BaseException:
        _k_end(None, True)


# kira:os onSignal (D78): a handler that only stores true, which Python runs on the main thread, and counts the
# signal for _k_sliced; false from any other thread, where signal.signal refuses, and for a signal it cannot catch.
def _k_onsignal(sig, flag):
    def caught(n, frame):
        flag.store(True)
        e = _k_errors()
        e.signalled = e.__dict__.get("signalled", 0) + 1

    try:
        _k_signal.signal(sig, caught)
    except (ValueError, _k_builtins.OSError):
        return False
    return True


# kira:os's structs (D84): each the class of its Kira name, the fields in declaration order, built with every value
# and copied and compared as a user struct is.
class _k_Datagram:
    __slots__ = ("size", "host", "port")

    def __init__(self, size, host, port):
        self.size = size
        self.host = host
        self.port = port

    def _k_clone(self):
        return _k_Datagram(self.size, self.host, self.port)

    def _k_set(self, o):
        self.size = o.size
        self.host = o.host
        self.port = o.port

    def __eq__(self, o):
        return self.size == o.size and self.host == o.host and self.port == o.port


class _k_Ready:
    __slots__ = ("handle", "events")

    def __init__(self, handle, events):
        self.handle = handle
        self.events = events

    def _k_clone(self):
        return _k_Ready(self.handle, self.events)

    def _k_set(self, o):
        self.handle = o.handle
        self.events = o.events

    def __eq__(self, o):
        return self.handle == o.handle and self.events == o.events


class _k_Ran:
    __slots__ = ("code", "out", "err")

    def __init__(self, code, out, err):
        self.code = code
        self.out = out
        self.err = err

    def _k_clone(self):
        return _k_Ran(self.code, self.out, self.err)

    def _k_set(self, o):
        self.code = o.code
        self.out = o.out
        self.err = o.err

    def __eq__(self, o):
        return self.code == o.code and self.out == o.out and self.err == o.err


# kira:os's reasons (D86): a call that can fail clears them, and a failure leaves the category of what Python raised
# with str(e), and a kind every target names alike.
def _k_osok():
    _k_reasons.os = ""
    _k_reasons.oskind = ""


def _k_osfail(e):
    c = _k_oscategory(e)
    text = str(e)
    _k_reasons.os = c + ": " + text if text else c
    _k_reasons.oskind = _k_oskind(e)


# The name raises= would give: the first of these the class is (OSError for every subclass), else its own; subprocess's
# by name, so no module that reads a file imports subprocess.
def _k_oscategory(e):
    for c in _k_builtins.type(e).__mro__:
        n = c.__name__ if c.__module__ == "builtins" else c.__module__ + "." + c.__name__
        if n in ("OSError", "ValueError", "OverflowError", "ImportError", "subprocess.SubprocessError"):
            return n
    return _k_builtins.type(e).__name__


# lastErrorKind's names, as C++ sorts errno and Win32's codes; subprocess's TimeoutExpired by name.
def _k_oskind(e):
    b = _k_builtins
    for c, k in ((b.FileNotFoundError, "NotFound"), (b.FileExistsError, "Exists"), (b.PermissionError, "Permission"), (b.TimeoutError, "Timeout"), (b.InterruptedError, "Interrupted")):
        if isinstance(e, c):
            return k
    return "Timeout" if b.type(e).__name__ == "TimeoutExpired" else "Other"


# A failure kira:os finds itself, worded as C++'s: "what: why".
def _k_osfailwith(what, why):
    _k_reasons.os = what + ": " + why
    _k_reasons.oskind = "Other"


def _k_oserror():
    return _k_builtins.getattr(_k_reasons, "os", "")


def _k_oserrkind():
    return _k_builtins.getattr(_k_reasons, "oskind", "")


# A kira:os call: f(*args), else fallback with the reason, for an OSError, a ValueError (a NUL in a path) or an
# OverflowError (a number past the C type).
def _k_osget(fallback, f, *args):
    _k_osok()
    try:
        return f(*args)
    except (_k_builtins.OSError, ValueError, OverflowError) as e:
        _k_osfail(e)
        return fallback


def _k_osdo(f, *args):
    _k_osok()
    try:
        f(*args)
    except (_k_builtins.OSError, ValueError, OverflowError) as e:
        _k_osfail(e)
        return False
    return True


# exists, isFile and isDir: false for any error, which leaves no reason.
def _k_ostest(f, path):
    _k_osok()
    return f(path)


# readFile (all of it, n < 0) and readFileHead, in C++'s 64 KiB chunks, as a read of n would take n bytes up front;
# readText (newline "": the text as it is) and readTextLf (None: universal newlines), strict UTF-8.
def _k_readfile(path, n):
    _k_osok()
    try:
        with _k_builtins.open(path, "rb") as f:
            if n < 0:
                return bytearray(f.read())
            out = bytearray()
            while len(out) < n:
                chunk = f.read(min(n - len(out), 65536))
                if not chunk:
                    break
                out += chunk
            return out
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return None


def _k_readtext(path, newline):
    _k_osok()
    try:
        with _k_builtins.open(path, encoding="utf-8", newline=newline) as f:
            return f.read()
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return None


def _k_writefile(path, data):
    _k_osok()
    try:
        with _k_builtins.open(path, "wb") as f:
            f.write(data)
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return False
    return True


# writeFileAtomic as kira::os's: <path>.<pid>.<n>.tmp, n counted for the process as C++'s static is, flushed to disk,
# renamed over path as _k_replace retries it, then the directory flushed; a failure removes the temporary.
def _k_writeatomic(path, data):
    _k_osok()
    n = _k_builtins.next(_k_errors().__dict__.setdefault("writers", _k_itertools.count()))
    tmp = "%s.%d.%d.tmp" % (path, _k_os.getpid(), n)
    try:
        with _k_builtins.open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            _k_os.fsync(f.fileno())
        _k_replace(tmp, path)
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        try:
            _k_os.remove(tmp)
        except (_k_builtins.OSError, ValueError):
            pass
        return False
    _k_syncdir(_k_os.path.dirname(_k_os.path.abspath(path)))
    return True


# os.replace, retried on Windows for about 200 ms while another handle holds the target (ERROR_ACCESS_DENIED,
# ERROR_SHARING_VIOLATION), as kira::os's MoveFileEx is.
def _k_replace(source, target):
    attempt = 0
    while True:
        try:
            _k_os.replace(source, target)
            return
        except _k_builtins.PermissionError as e:
            if _k_os.name != "nt" or _k_builtins.getattr(e, "winerror", None) not in (5, 32) or attempt >= 20:
                raise
        _k_time.sleep(0.001 if attempt < 10 else 0.02)
        attempt += 1


# A rename's directory entry flushed to disk, at best: Windows opens no directory, and an error here loses no data.
def _k_syncdir(d):
    if _k_os.name == "nt":
        return
    try:
        fd = _k_os.open(d, _k_os.O_RDONLY)
        try:
            _k_os.fsync(fd)
        finally:
            _k_os.close(fd)
    except _k_builtins.OSError:
        pass


def _k_listdir(path):
    return _k_builtins.sorted(_k_os.listdir(path))


def _k_makedirs(path):
    _k_os.makedirs(path, exist_ok=True)


def _k_freebytes(path):
    return _k_shutil.disk_usage(path).free


def _k_touch(path, s):
    _k_os.utime(path, (s, s))


def _k_globbed(pattern):
    return _k_builtins.sorted(_k_glob.glob(pattern))


def _k_walkfiles(top):
    return _k_builtins.sorted([_k_os.path.join(d, f) for d, _, fs in _k_os.walk(top) for f in fs])


def _k_affinity(pid, cpus):
    f = _k_builtins.getattr(_k_os, "sched_setaffinity", None)
    if f is None:
        raise _k_builtins.OSError("setAffinity is Linux's")
    f(pid, cpus)


def _k_nice(increment):
    f = _k_builtins.getattr(_k_os, "nice", None)
    if f is None:
        raise _k_builtins.OSError("nice is POSIX's")
    return f(increment)


# moduleDir: the directory of the generated module this helper was copied into, its links resolved; for code exec'd
# without a file, the current directory, where its relative paths resolve.
def _k_moddir():
    f = _k_moddir.__globals__.get("__file__")
    return _k_os.path.dirname(_k_os.path.realpath(f)) if f else _k_os.path.realpath(".")


# FileOut (critique 12): builtins.open in binary with no buffer of Python's, so a write reaches the OS whole and a
# test's spy on open sees it; isStale reads os.path.exists.
class _k_FileOut:
    __slots__ = ("f", "path")

    def __init__(self, f=None, path=""):
        self.f = f
        self.path = path

    def write(self, data):
        _k_osok()
        if self.f is None or self.f.closed:
            _k_osfailwith("write", "the file is closed")
            return False
        try:
            with memoryview(data) as v:
                done = 0
                while done < len(v):
                    done += self.f.write(v[done:])
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return False
        return True

    def sync(self):
        return self.call("sync", lambda fd: _k_os.fsync(fd) or True, False)

    def size(self):
        return self.call("size", lambda fd: _k_os.fstat(fd).st_size, -1)

    def call(self, what, f, fallback):
        _k_osok()
        if self.f is None or self.f.closed:
            _k_osfailwith(what, "the file is closed")
            return fallback
        try:
            return f(self.f.fileno())
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return fallback

    def isStale(self):
        return self.f is None or self.f.closed or not _k_os.path.exists(self.path)

    def close(self):
        if self.f is not None:
            try:
                self.f.close()
            except _k_builtins.OSError:
                pass


def _k_openout(path, mode):
    _k_osok()
    try:
        return _k_FileOut(_k_builtins.open(path, mode, buffering=0), path)
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return None


# A blocking kira:os wait of ms (negative: no limit) that an onSignal handler ends, as EINTR ends C++'s (D78): the main
# thread waits in 50 ms slices, between which Python runs the handler; another thread waits whole. once(s) waits s.
def _k_sliced(ms, once):
    if _k_threading.current_thread() is not _k_threading.main_thread():
        return once(None if ms < 0 else ms / 1000)
    e = _k_errors()
    seen = e.__dict__.get("signalled", 0)
    end = None if ms < 0 else _k_deadline() + ms / 1000
    while True:
        left = 0.05 if end is None else min(end - _k_deadline(), 0.05)
        got = once(max(left, 0.0))
        if got or end is not None and _k_deadline() >= end:
            return got
        if e.__dict__.get("signalled", 0) != seen:
            raise _k_builtins.InterruptedError(_k_errno.EINTR, _k_os.strerror(_k_errno.EINTR))


# Waits for f (a socket, or on POSIX any descriptor) to be readable, in _k_sliced's slices on the main thread.
def _k_readable(f):
    if _k_threading.current_thread() is _k_threading.main_thread() and (_k_os.name != "nt" or isinstance(f, _k_socket.socket)):
        _k_sliced(-1, lambda s: _k_readyin(f, s))


# Whether f is readable within s seconds: poll on POSIX, which takes a descriptor past FD_SETSIZE, select on Windows.
def _k_readyin(f, s):
    if _k_os.name == "nt":
        return bool(_k_select.select([f], [], [], s)[0])
    p = _k_select.poll()
    p.register(f, _k_select.POLLIN)
    return bool(p.poll(s * 1000))


# kira:os's sockets: IPv4 by number ("" is every interface), non-blocking UDP and listeners, blocking streams. On
# Windows a closed port's ICMP reply is no reset of the next recvFrom, as kira::os turns SIO_UDP_CONNRESET off.
def _k_addr(host, port):
    if not 0 <= port <= 65535:
        _k_osfailwith("address", "port out of range")
        return None
    try:
        _k_socket.inet_pton(_k_socket.AF_INET, host or "0.0.0.0")
    except (_k_builtins.OSError, ValueError):
        _k_osfailwith("address", "not a numeric IPv4 host")
        return None
    return (host or "0.0.0.0", port)


def _k_bound(kind, port, host, setup):
    a = _k_addr(host, port)
    if a is None:
        return None
    try:
        s = _k_socket.socket(_k_socket.AF_INET, kind)
    except _k_builtins.OSError as e:
        _k_osfail(e)
        return None
    try:
        setup(s, a)
        s.setblocking(False)
    except _k_builtins.OSError as e:
        s.close()
        _k_osfail(e)
        return None
    return s


class _k_UdpSocket:
    __slots__ = ("s",)

    def __init__(self, s=None):
        self.s = s

    def bind(self, host, port):
        _k_osok()
        self.close()
        self.s = _k_bound(_k_socket.SOCK_DGRAM, port, host, lambda s, a: s.bind(a))
        return self.s is not None

    def localPort(self):
        return _k_localport(self.s, "bound")

    def sendTo(self, data, host, port):
        _k_osok()
        if self.s is None:
            _k_osfailwith("sendTo", "socket is not bound")
            return -1
        a = _k_addr(host, port)
        if a is None:
            return -1
        try:
            return self.s.sendto(data, a)
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return -1

    def recvFrom(self, into):
        _k_osok()
        if self.s is None:
            _k_osfailwith("recvFrom", "socket is not bound")
            return None
        while True:
            try:
                if _k_os.name == "nt":
                    data, src = self.s.recvfrom(65536)
                    n = min(len(data), len(into))
                    into[:n] = data[:n]
                else:
                    n, src = self.s.recvfrom_into(into)
                return _k_Datagram(n, src[0], src[1])
            except _k_builtins.BlockingIOError:
                return None
            except _k_builtins.ConnectionResetError as e:
                if _k_os.name != "nt":
                    _k_osfail(e)
                    return None
            except _k_builtins.OSError as e:
                _k_osfail(e)
                return None

    def handle(self):
        return -1 if self.s is None else self.s.fileno()

    def close(self):
        if self.s is not None:
            self.s.close()
            self.s = None


def _k_localport(s, state):
    _k_osok()
    if s is None:
        _k_osfailwith("localPort", "socket is not " + state)
        return -1
    try:
        return s.getsockname()[1]
    except _k_builtins.OSError as e:
        _k_osfail(e)
        return -1


# A restarted server binds its port while old connections linger in TIME_WAIT; on Windows SO_REUSEADDR would let a
# second socket steal the port, so it stays off there.
def _k_listening(s, a):
    if _k_os.name != "nt":
        s.setsockopt(_k_socket.SOL_SOCKET, _k_socket.SO_REUSEADDR, 1)
    s.bind(a)
    s.listen(16)


class _k_TcpListener:
    __slots__ = ("s",)

    def __init__(self, s=None):
        self.s = s

    def listen(self, port):
        _k_osok()
        self.close()
        self.s = _k_bound(_k_socket.SOCK_STREAM, port, "", _k_listening)
        return self.s is not None

    def localPort(self):
        return _k_localport(self.s, "listening")

    # Windows hands out a socket in the listener's non-blocking mode: blocking, as TcpStream promises.
    def accept(self):
        _k_osok()
        if self.s is None:
            _k_osfailwith("accept", "socket is not listening")
            return None
        try:
            c = self.s.accept()[0]
        except _k_builtins.BlockingIOError:
            return None
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return None
        try:
            c.setblocking(True)
        except _k_builtins.OSError as e:
            c.close()
            _k_osfail(e)
            return None
        return _k_TcpStream(c)

    def handle(self):
        return -1 if self.s is None else self.s.fileno()

    def close(self):
        if self.s is not None:
            self.s.close()
            self.s = None


class _k_TcpStream:
    __slots__ = ("s",)

    def __init__(self, s=None):
        self.s = s

    def connect(self, host, port):
        _k_osok()
        self.close()
        a = _k_addr(host, port)
        if a is None:
            return False
        try:
            s = _k_socket.socket(_k_socket.AF_INET, _k_socket.SOCK_STREAM)
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return False
        try:
            s.connect(a)
        except _k_builtins.OSError as e:
            s.close()
            _k_osfail(e)
            return False
        self.s = s
        return True

    def read(self, into):
        _k_osok()
        if self.s is None:
            _k_osfailwith("read", "stream is closed")
            return -1
        try:
            _k_readable(self.s)
            return self.s.recv_into(into)
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return -1

    def write(self, data):
        _k_osok()
        if self.s is None:
            _k_osfailwith("write", "stream is closed")
            return -1
        try:
            self.s.sendall(data)
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return -1
        return len(data)

    def isOpen(self):
        return self.s is not None

    def handle(self):
        return -1 if self.s is None else self.s.fileno()

    def close(self):
        if self.s is not None:
            self.s.close()
            self.s = None


# Poller over poll on POSIX (POLLHUP is POLL_READ and POLL_ERROR) and select on Windows (a peer's close is POLL_READ
# alone, where WSAPoll adds POLL_ERROR), in the order added; a negative handle is skipped, POLL_ERROR on Windows, as C++.
class _k_Poller:
    __slots__ = ("w",)

    def __init__(self):
        self.w = {}

    def add(self, handle, events):
        self.w[handle] = events

    def remove(self, handle):
        self.w.pop(handle, None)

    def wait(self, ms):
        _k_osok()
        if not self.w:
            return []
        try:
            return _k_sliced(min(ms, 2147483647), self.once)
        except (_k_builtins.OSError, ValueError, OverflowError) as e:
            _k_osfail(e)
            return []

    def once(self, s):
        got = {}
        if _k_os.name == "nt":
            live = {h: e for h, e in self.w.items() if h >= 0}
            for h in self.w:
                if h < 0:
                    got[h] = 4
            if live:
                r, w, x = _k_select.select([h for h, e in live.items() if e & 1], [h for h, e in live.items() if e & 2], list(live), 0 if got else s)
                for bit, hs in ((1, r), (2, w), (4, x)):
                    for h in hs:
                        got[h] = got.get(h, 0) | bit
        else:
            p = _k_select.poll()
            for h, e in self.w.items():
                if h >= 0:
                    p.register(h, (_k_select.POLLIN if e & 1 else 0) | (_k_select.POLLOUT if e & 2 else 0))
            for h, re in p.poll(None if s is None else s * 1000):
                hup = re & _k_select.POLLHUP
                got[h] = (1 if re & _k_select.POLLIN or hup else 0) | (2 if re & _k_select.POLLOUT else 0) | (4 if re & (_k_select.POLLERR | _k_select.POLLNVAL) or hup else 0)
        return [_k_Ready(h, got[h]) for h in self.w if got.get(h)]


# Serial on pyserial, imported at open. read waits up to ms (at most C++'s INT_MAX) for the first byte, on the main
# thread on a POSIX port's descriptor in _k_sliced's slices, then takes in_waiting, as kira::os's poll and read do.
class _k_Serial:
    __slots__ = ("p",)

    def __init__(self):
        self.p = None

    def open(self, path, baud):
        _k_osok()
        self.close()
        try:
            self.p = _k_builtins.__import__("serial").Serial(path, baud, timeout=0)
        except (_k_builtins.ImportError, _k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return False
        return True

    def read(self, into, ms):
        _k_osok()
        if not self.isOpen():
            _k_osfailwith("read", "port is not open")
            return -1
        if len(into) == 0:
            return 0
        ms = min(ms, 2147483647)
        try:
            t = None if ms < 0 else ms / 1000
            fd = _k_builtins.getattr(self.p, "fd", None)
            if fd is not None and _k_threading.current_thread() is _k_threading.main_thread():
                if not _k_sliced(ms, lambda s: _k_readyin(fd, s)):
                    return 0
                t = 0
            if self.p.timeout != t:
                self.p.timeout = t
            data = self.p.read(1)
            if data:
                data += self.p.read(min(self.p.in_waiting, len(into) - 1))
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return -1
        into[:len(data)] = data
        return len(data)

    def write(self, data):
        _k_osok()
        if not self.isOpen():
            _k_osfailwith("write", "port is not open")
            return -1
        try:
            return self.p.write(bytes(data))
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return -1

    def isOpen(self):
        return self.p is not None and self.p.is_open

    def handle(self):
        return self.p.fd if self.isOpen() and _k_os.name != "nt" else -1

    def close(self):
        if self.p is not None:
            self.p.close()
            self.p = None


# kira:os's Process: Popen with stdout an unbuffered pipe and no other descriptor but stdin and stderr; wait is 128 +
# the ending signal (137 after kill, Windows too), and a dropped running one is killed and reaped, as C++'s is.
class _k_Process:
    __slots__ = ("p",)

    def __init__(self, p=None):
        self.p = p

    def readStdout(self, into):
        _k_osok()
        out = None if self.p is None else self.p.stdout
        if out is None or out.closed:
            _k_osfailwith("readStdout", "no pipe")
            return -1
        try:
            _k_readable(out)
            n = out.readinto(into)
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return -1
        return 0 if n is None else n

    def kill(self):
        if self.p is None or self.p.poll() is not None:
            return
        if _k_os.name == "nt":
            try:
                _k_subprocess._winapi.TerminateProcess(self.p._handle, 137)
            except _k_builtins.PermissionError:
                pass
        else:
            self.p.kill()

    def wait(self):
        _k_osok()
        if self.p is None:
            _k_osfailwith("wait", "no process")
            return -1
        try:
            return _k_code(self.p.wait())
        except _k_builtins.OSError as e:
            _k_osfail(e)
            return -1

    def isRunning(self):
        _k_osok()
        return self.p is not None and self.p.poll() is None

    def stdoutHandle(self):
        out = None if self.p is None else self.p.stdout
        return -1 if out is None or out.closed or _k_os.name == "nt" else out.fileno()

    def pid(self):
        return -1 if self.p is None else self.p.pid

    def __del__(self):
        try:
            if self.p is not None and self.p.poll() is None:
                self.kill()
                self.p.wait()
        except _k_builtins.BaseException:
            pass


# PipedProcess (critique 14): spawnPiped's child, its stdin written whole (unbuffered), stderr read by line.
class _k_PipedProcess(_k_Process):
    __slots__ = ("err",)

    def __init__(self, p=None):
        _k_Process.__init__(self, p)
        self.err = None if p is None else _k_io.BufferedReader(p.stderr)

    def writeStdin(self, data):
        _k_osok()
        if self.p is None or self.p.stdin.closed:
            _k_osfailwith("writeStdin", "stdin is closed")
            return -1
        try:
            with memoryview(data) as v:
                done = 0
                while done < len(v):
                    done += self.p.stdin.write(v[done:])
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return -1
        return done

    def closeStdin(self):
        if self.p is not None:
            try:
                self.p.stdin.close()
            except _k_builtins.OSError:
                pass

    def readStderrLine(self):
        _k_osok()
        if self.err is None:
            _k_osfailwith("readStderrLine", "no pipe")
            return None
        try:
            line = self.err.readline()
        except (_k_builtins.OSError, ValueError) as e:
            _k_osfail(e)
            return None
        return line.decode("utf-8", "replace") if line else None


# A child's exit status as kira::os reports it: a signal's 128 + its number, a Windows code as the Int32 C++ casts it to.
def _k_code(rc):
    return 128 - rc if rc < 0 else _k_as_i32(rc)


# What a child writes after this process's own buffered lines, as C++'s fflush(nullptr) before the fork orders them.
def _k_flushed():
    for s in (_k_sys.stdout, _k_sys.stderr):
        try:
            s.flush()
        except (_k_builtins.AttributeError, _k_builtins.OSError, ValueError):
            pass


def _k_spawn(argv, piped):
    _k_osok()
    if not argv:
        _k_osfailwith("spawnPiped" if piped else "spawnProcess", "empty argv")
        return None
    _k_flushed()
    pipe = _k_subprocess.PIPE
    try:
        p = _k_subprocess.Popen(list(argv), stdin=pipe if piped else None, stdout=pipe, stderr=pipe if piped else None, bufsize=0)
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return None
    return _k_PipedProcess(p) if piped else _k_Process(p)


# runCapture's text: UTF-8 with U+FFFD for a bad byte, CRLF and CR as LF.
def _k_lftext(b):
    return b.decode("utf-8", "replace").replace("\r\n", "\n").replace("\r", "\n")


def _k_runcapture(argv, given, ms):
    _k_osok()
    if not argv:
        _k_osfailwith("runCapture", "empty argv")
        return None
    _k_flushed()
    try:
        data = None if given is None else given.encode("utf-8", "surrogatepass")
        r = _k_subprocess.run(list(argv), input=data, capture_output=True, timeout=ms / 1000 if ms > 0 else None)
    except (_k_builtins.OSError, ValueError, _k_subprocess.SubprocessError) as e:
        _k_osfail(e)
        return None
    return _k_Ran(_k_code(r.returncode), _k_lftext(r.stdout), _k_lftext(r.stderr))


def _k_runinherit(argv):
    _k_osok()
    if not argv:
        _k_osfailwith("runInherit", "empty argv")
        return -1
    _k_flushed()
    try:
        return _k_code(_k_subprocess.run(list(argv)).returncode)
    except (_k_builtins.OSError, ValueError) as e:
        _k_osfail(e)
        return -1


# kira:io escapeStdout (D87): from then on a character stdout's encoding lacks is written as a \u escape, not a
# stop; only a program that asks gets it, so text it pipes to another is never changed unasked.
def _k_escapestdout():
    reconfigure = _k_builtins.getattr(_k_sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(errors="backslashreplace")


# kira:time sleepMs (D72): at least ms milliseconds, a day at a time, which no platform's sleep
# overflows on; zero or less returns at once.
def _k_sleep(ms):
    while ms > 0:
        step = min(ms, 86400000)
        _k_time.sleep(step / 1000)
        ms -= step


# kira:time utcStamp, kira::time's text: the day from the ordinal of 1970-01-01, the milliseconds floored.
def _k_utcstamp(ns):
    ms = ns // 1000000
    d = _k_datetime.date.fromordinal(719163 + ms // 86400000)
    ms %= 86400000
    return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ" % (d.year, d.month, d.day, ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)


# kira:path: os.path.join of every part, none a program error (Python's TypeError); relpath, none where Python
# raises (another drive, an empty path).
def _k_joinall(parts):
    if not parts:
        _k_panic("joinPaths of no parts")
    return _k_os.path.join(*parts)


def _k_relpath(path, start):
    try:
        return _k_os.path.relpath(path, start)
    except ValueError:
        return None


# kira:pytext's calls that can fail (D86): none where Python raises, the reason named as Python names it.
def _k_pytextfail(e):
    _k_reasons.pytext = ("OverflowError: " if isinstance(e, OverflowError) else "ValueError: ") + str(e)


def _k_pyint(text):
    _k_reasons.pytext = ""
    try:
        v = int(text)
    except ValueError as e:
        _k_pytextfail(e)
        return None
    return _k_pyint64(v)


# None for an int past Int64, which Python itself would hold, with linkstate's OverflowError text.
def _k_pyint64(v):
    if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF:
        return v
    _k_reasons.pytext = "OverflowError: past 64 bits"
    return None


def _k_pyfloat(text):
    _k_reasons.pytext = ""
    try:
        return float(text)
    except ValueError as e:
        _k_pytextfail(e)
        return None


def _k_pyformat(v, spec):
    _k_reasons.pytext = ""
    try:
        return _k_builtins.format(float(v), spec)
    except (ValueError, OverflowError) as e:
        _k_pytextfail(e)
        return None


# round(v, n) past the largest double is the infinity of v's sign, the double strtod rounds it to, where Python raises.
def _k_roundto(v, n):
    try:
        return _k_builtins.round(float(v), n)
    except OverflowError:
        return _k_math.copysign(_k_math.inf, v)


def _k_roundeven(v):
    _k_reasons.pytext = ""
    try:
        r = _k_builtins.round(float(v))
    except (ValueError, OverflowError) as e:
        _k_pytextfail(e)
        return None
    return _k_pyint64(r)


def _k_pytexterror():
    return _k_builtins.getattr(_k_reasons, "pytext", "")


# textwrap.wrap with each word kept whole; a width of 0, which Python refuses, is a program error.
def _k_wrapwords(text, width):
    if width == 0:
        _k_panic("wrapWords of a width of 0")
    return _k_textwrap.wrap(text, width=width, break_long_words=False, break_on_hyphens=False)


# kira:regex (E1): a Regex and a Match are re's own objects, so a Python caller's pattern is a Regex.
_k_Regex = _k_re.Pattern


_k_Match = _k_re.Match


# compile's reason (D86) is str(e) alone: re.error's class is named "error", and PatternError on 3.13 (critique 3).
# A repeat past re's limit is an OverflowError, deep nesting a RecursionError: none as well.
def _k_recompile(pattern, flags):
    _k_reasons.regex = ""
    try:
        return _k_re.compile(pattern, flags)
    except (_k_re.error, ValueError, OverflowError, _k_builtins.RecursionError) as e:
        _k_reasons.regex = str(e)
        return None


def _k_reerror():
    return _k_builtins.getattr(_k_reasons, "regex", "")


# What Python raises for a group the pattern lacks, findall's tuples, a bad template and split's None is a program error.
def _k_regroup(m, g):
    try:
        return m.group(g)
    except _k_builtins.IndexError:
        _k_panic("Match.group of " + _k_builtins.repr(g) + ", which its pattern lacks")


def _k_refindall(p, text):
    if p.groups > 1:
        _k_panic("Regex.findAll of a pattern of %d groups" % p.groups)
    return p.findall(text)


# A template's unknown group name is an IndexError, every other fault re.error.
def _k_resub(p, replacement, text):
    try:
        return p.sub(replacement, text)
    except (_k_re.error, _k_builtins.IndexError) as e:
        _k_panic("Regex.sub's replacement: " + str(e))


def _k_resplit(p, text):
    pieces = p.split(text)
    if None in pieces:
        _k_panic("Regex.split by a group that took no part")
    return pieces


# kira:random (critique 21): random.Random itself, so a Python caller's own is a Random; a draw it refuses panics.
_k_Random = _k_random.Random


def _k_below(r, stop):
    if stop <= 0:
        _k_panic("Random.below(%d): the stop must be above 0" % stop)
    return r.randrange(stop)


def _k_between(r, lo, hi):
    if lo > hi:
        _k_panic("Random.between(%d, %d): lo is above hi" % (lo, hi))
    return r.randint(lo, hi)


def _k_bits(r, k):
    if not 0 <= k <= 63:
        _k_panic("Random.bits(%d): k is outside 0..63" % k)
    return r.getrandbits(k)


# kira:csv: csv.reader over a file read as UTF-8 with newline=""; the reasons (D86) are str(e), as csv.Error's class
# is named Error (critique 3), and isMalformed tells a csv.Error, which a caller may end the program on (critique 15).
class _k_CsvReader:
    __slots__ = ("f", "rows", "bad")

    def __init__(self, f=None):
        self.f = f
        self.rows = None if f is None else _k_csv.reader(f)
        self.bad = False

    def next(self):
        _k_reasons.csv = ""
        self.bad = False
        if self.rows is None:
            return None
        try:
            row = _k_builtins.next(self.rows, None)
        except _k_csv.Error as e:
            self.bad = True
            return self.stop(e)
        except (_k_builtins.OSError, ValueError) as e:
            return self.stop(e)
        if row is None:
            self.close()
        return row

    def stop(self, e):
        _k_reasons.csv = str(e)
        self.close()
        return None

    def isMalformed(self):
        return self.bad

    def close(self):
        f = self.f
        self.f = None
        self.rows = None
        if f is not None:
            try:
                f.close()
            except _k_builtins.OSError:
                pass


def _k_opencsv(path):
    _k_reasons.csv = ""
    try:
        return _k_CsvReader(_k_builtins.open(path, encoding="utf-8", newline=""))
    except (_k_builtins.OSError, ValueError) as e:
        _k_reasons.csv = str(e)
        return None


def _k_csvline(cells):
    out = _k_io.StringIO()
    try:
        _k_csv.writer(out).writerow(cells)
    except _k_csv.Error as e:
        _k_panic("csvLine: " + str(e))
    return out.getvalue()


def _k_csverror():
    return _k_builtins.getattr(_k_reasons, "csv", "")


# kira:http's structs (D84), as kira:os's.
class _k_HttpReply:
    __slots__ = ("status", "body")

    def __init__(self, status, body):
        self.status = status
        self.body = body

    def _k_clone(self):
        return _k_HttpReply(self.status, bytearray(self.body))

    def _k_set(self, o):
        self.status = o.status
        self.body = o.body

    def __eq__(self, o):
        return self.status == o.status and self.body == o.body


class _k_HttpRequest:
    __slots__ = ("method", "path", "host", "body")

    def __init__(self, method, path, host, body):
        self.method = method
        self.path = path
        self.host = host
        self.body = body

    def _k_clone(self):
        return _k_HttpRequest(self.method, self.path, self.host, bytearray(self.body))

    def _k_set(self, o):
        self.method = o.method
        self.path = o.path
        self.host = o.host
        self.body = o.body

    def __eq__(self, o):
        return self.method == o.method and self.path == o.path and self.host == o.host and self.body == o.body


class _k_HttpAnswer:
    __slots__ = ("status", "contentType", "body", "headers")

    def __init__(self, status, contentType, body, headers):
        self.status = status
        self.contentType = contentType
        self.body = body
        self.headers = headers

    def _k_clone(self):
        return _k_HttpAnswer(self.status, self.contentType, bytearray(self.body), dict(self.headers))

    def _k_set(self, o):
        self.status = o.status
        self.contentType = o.contentType
        self.body = o.body
        self.headers = o.headers

    def __eq__(self, o):
        return self.status == o.status and self.contentType == o.contentType and self.body == o.body and self.headers == o.headers


# kira:http's client (D86): urlopen of an http or https URL, any status a reply (an HTTPError's code and body), none
# when no reply came, the reason str(e); a wait is at most C++'s INT_MAX ms, past which settimeout overflows.
def _k_httpcall(url, data, ctype, ms):
    _k_reasons.http = ""
    try:
        req = _k_urlrequest.Request(url, None if data is None else bytes(data), {} if ctype is None else {"Content-Type": ctype})
        if req.type not in ("http", "https"):
            raise _k_urlerror.URLError("unknown url type: " + req.type)
        try:
            r = _k_urlrequest.urlopen(req, timeout=min(ms, 2147483647) / 1000 if ms > 0 else None)
            status = r.status
        except _k_urlerror.HTTPError as e:
            r, status = e, e.code
        try:
            return _k_HttpReply(status, bytearray(r.read()))
        finally:
            r.close()
    except (_k_builtins.OSError, ValueError, _k_httpclient.HTTPException) as e:
        _k_reasons.http = str(e)
        return None


def _k_httperror():
    return _k_builtins.getattr(_k_reasons, "http", "")


def _k_jsontext(body):
    _k_reasons.http = ""
    b = bytes(body)
    try:
        return b.decode(_k_json.detect_encoding(b), "surrogatepass")
    except ValueError as e:
        _k_reasons.http = str(e)
        return None


# urlparse's path or query; a target it refuses (a bad IPv6 host) is none, as a client may send one.
def _k_urlpart(target, part):
    _k_reasons.http = ""
    try:
        return _k_builtins.getattr(_k_urlparse.urlparse(target), part)
    except ValueError as e:
        _k_reasons.http = str(e)
        return None


def _k_queryfirst(query, key):
    found = _k_urlparse.parse_qs(query).get(key)
    return found[0] if found else None


# kira:http's server: SO_REUSEADDR off on Windows, where it lets a second server take the port, as kira:os keeps it.
class _k_Httpd(_k_httpserver.ThreadingHTTPServer):
    allow_reuse_address = _k_os.name != "nt"


# Each request asks the server's Kira Fx through _k_kirafx (E6, D76); access lines are not logged.
class _k_HttpGet(_k_httpserver.BaseHTTPRequestHandler):
    def do_GET(self):
        self.answer("GET", bytearray())

    def answer(self, method, body):
        self.reply(_k_kirafx(self.server.kira_handle, _k_HttpRequest(method, self.path, self.headers.get("Host") or "", body)))

    def reply(self, a):
        _k_kirafx(_k_httpheaders, a)
        if a.status == 0:
            self.close_connection = True
        elif a.status >= 400 and not a.body:
            self.send_error(a.status)
        else:
            self.send_response(a.status)
            self.send_header("Content-Type", a.contentType)
            self.send_header("Content-Length", str(len(a.body)))
            for k, v in a.headers.items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(a.body)

    def log_message(self, *args):
        pass


# A POST's length as int() reads Content-Length (none is 0, unreadable -1), clamped to what an Int64 holds; the body is
# read before handle runs, so its answer never meets unread bytes, which a close would reset.
class _k_HttpPost(_k_HttpGet):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        n = max(-(1 << 62), min(n, 1 << 62))
        a = _k_kirafx(self.server.kira_admit, self.path, n)
        if a.status != 0:
            self.close_connection = True
            self.reply(a)
        else:
            self.answer("POST", _k_httpbody(self.rfile, n))


# n bytes in 64 KiB pieces, fewer when the client stops: a Content-Length past what comes costs only what came.
def _k_httpbody(f, n):
    out = bytearray()
    while len(out) < n:
        piece = f.read(min(n - len(out), 65536))
        if not piece:
            break
        out += piece
    return out


# A header Python's own lines already send, or a line break, which would end the headers early, is a program error.
def _k_httpheaders(a):
    for k in a.headers:
        if k.lower() in ("content-type", "content-length"):
            _k_panic("HttpAnswer.headers sets " + k + ", which contentType and the body set")
    for t in [a.contentType] + list(a.headers) + list(a.headers.values()):
        if "\r" in t or "\n" in t:
            _k_panic("HttpAnswer's header text " + _k_shown(t) + " holds a line break")


def _k_httpbind(host, port, handle, admit):
    _k_reasons.http = ""
    try:
        h = _k_Httpd((host, port), _k_HttpGet if admit is None else _k_HttpPost)
    except (_k_builtins.OSError, ValueError, OverflowError, _k_builtins.TypeError) as e:
        _k_reasons.http = str(e)
        return None
    h.kira_handle = handle
    h.kira_admit = admit
    return _k_HttpServer(h)


# serve is serve_forever until shutdown, which ends a serve not begun yet at once (BaseServer.shutdown would wait).
class _k_HttpServer:
    __slots__ = ("h", "lk", "state")

    def __init__(self, h=None):
        self.h = h
        self.lk = _k_threading.Lock()
        self.state = "bound" if h is not None else "unbound"

    def port(self):
        return -1 if self.h is None else self.h.server_address[1]

    def serve(self):
        _k_reasons.http = ""
        with self.lk:
            if self.state != "bound":
                if self.state != "closed":
                    _k_reasons.http = "the server is " + self.state
                return self.state == "closed"
            self.state = "serving"
        try:
            self.h.serve_forever(0.5)
        except (_k_builtins.OSError, ValueError) as e:
            _k_reasons.http = str(e)
            return False
        finally:
            with self.lk:
                self.state = "closed"
            self.h.server_close()
        return True

    def shutdown(self):
        with self.lk:
            state = self.state
            if state != "unbound":
                self.state = "closed"
        if state == "serving":
            self.h.shutdown()
        elif state == "bound":
            self.h.server_close()


# kira:args: argparse with the program's own prog; a bad command line ends with 2 and --help with 0, as parse_args ends
# them. A help text is plain: its % is no format of argparse's.
class _k_ArgSpec:
    __slots__ = ("p", "names")

    def __init__(self, prog=None, description=None):
        self.p = _k_argparse.ArgumentParser(prog=prog, description=description)
        self.names = {}

    def positional(self, name, about):
        self.add(name, "text", False, dict(help=about))

    def text(self, flag, fallback, about):
        self.add(flag, "text", True, dict(default=fallback, help=about))

    def whole(self, flag, fallback, about):
        self.add(flag, "whole", True, dict(default=fallback, type=_k_argint, help=about))

    def real(self, flag, fallback, about):
        self.add(flag, "real", True, dict(default=fallback, type=float, help=about))

    def flag(self, flag, about):
        self.add(flag, "flag", True, dict(action="store_true", help=about))

    def add(self, name, kind, option, kw):
        if not name or name.startswith("-") != option:
            _k_panic("ArgSpec: %r is no %s" % (name, "flag (it needs a leading -)" if option else "positional name (it has a leading -)"))
        kw["help"] = kw["help"].replace("%", "%%")
        try:
            a = self.p.add_argument(name, **kw)
        except (_k_argparse.ArgumentError, ValueError) as e:
            _k_panic("ArgSpec: " + str(e))
        self.names[name] = (a.dest, kind)

    def parse(self, argv):
        return _k_Parsed(self.p.parse_args(list(argv)), dict(self.names))


# whole's type: int()'s text within Int64, else argparse's own "invalid int value".
def _k_argint(text):
    try:
        v = int(text)
    except ValueError:
        v = None
    if v is None or not -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF:
        raise _k_argparse.ArgumentTypeError("invalid int value: %r" % text)
    return v


class _k_Parsed:
    __slots__ = ("ns", "names")

    def __init__(self, ns=None, names=None):
        self.ns = ns
        self.names = {} if names is None else names

    def value(self, name, kind):
        d = self.names.get(name)
        if d is None:
            _k_panic("Parsed: no argument %r was declared" % name)
        if d[1] != kind:
            _k_panic("Parsed: %r is a %s argument, not a %s one" % (name, d[1], kind))
        return _k_builtins.getattr(self.ns, d[0])

    def textOf(self, name):
        return self.value(name, "text")

    def wholeOf(self, name):
        return self.value(name, "whole")

    def realOf(self, name):
        return self.value(name, "real")

    def isSet(self, name):
        return self.value(name, "flag")


# The sidecar X_ext.py beside X.kira (D67), found beside this file's real path and loaded once per
# process; a missing one, or an extern it lacks or takes other arguments for, is a panic at import.
def _k_extern(here, path, names):
    path = _k_os.path.realpath(_k_os.path.join(_k_os.path.dirname(_k_os.path.realpath(here)), path))
    if not _k_os.path.isfile(path):
        _k_panic("the sidecar %s is missing" % path)
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
        c = ext.__dict__.get(parts[0], _k_builtins.__dict__.get(parts[0]))
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


# A value a panic names, its repr cut to 40 characters; a repr that raises must not hide the panic.
def _k_shown(v):
    try:
        r = _k_builtins.repr(v)
    except _k_builtins.Exception:
        r = "an object whose repr raises"
    return (r if len(r) <= 40 else r[:37] + "...") + " (" + _k_builtins.type(v).__name__ + ")"


# What Kira hands a sidecar (D68): each list, dict, tuple and struct a new one at every level and each
# List<UInt8> an immutable bytes, so nothing Python writes reaches Kira; anything else goes as itself.
def _k_out(v):
    c = _k_builtins.type(v)
    if c is list:
        return [_k_out(x) for x in v]
    if c is bytearray:
        return bytes(v)
    if c is _k_builtins.tuple:
        return _k_builtins.tuple([_k_out(x) for x in v])
    if c is dict:
        return {k: _k_out(x) for k, x in v.items()}
    if _k_builtins.hasattr(c, "_k_clone"):
        o = object.__new__(c)
        for s in c.__slots__:
            _k_builtins.setattr(o, s, _k_out(_k_builtins.getattr(v, s)))
        return o
    return v


# What Python hands back (D68), checked against the declared type's spec and rebuilt where Kira holds
# a value, so a list, dict or bytes the sidecar keeps is never Kira's; an int is a Float64 if exact.
def _k_check(v, spec, where, declared):
    k = spec[0]
    c = _k_builtins.type(v)
    if k == "I":
        if c is int and spec[1] <= v <= spec[2]:
            return v
    elif k == "F":
        if c is float:
            return v
        if c is int:
            try:
                f = float(v)
            except OverflowError:
                f = None
            if f == v:
                return f
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


# A Json from Python: what json.loads makes, no container 513 deep nor inside itself. Depth first, as
# _k_jfault walks, each container once however often it is shared, its height kept for the next.
def _k_jcheck(v):
    t = _k_builtins.type(v)
    if not _k_jone(v):
        return False
    if t is not dict and t is not list:
        return True
    height = {}
    path = [[v, _k_builtins.iter(v.values() if t is dict else v), 0]]
    on = {_k_builtins.id(v)}
    while path:
        top = path[-1]
        for x in top[1]:
            c = _k_builtins.type(x)
            if c is dict or c is list:
                h = height.get(_k_builtins.id(x))
                if h is None:
                    if _k_builtins.id(x) in on or len(path) == 512 or not _k_jone(x):
                        return False
                    path.append([x, _k_builtins.iter(x.values() if c is dict else x), 0])
                    on.add(_k_builtins.id(x))
                    break
                top[2] = max(top[2], h)
            elif not _k_jone(x):
                return False
        else:
            path.pop()
            on.discard(_k_builtins.id(top[0]))
            height[_k_builtins.id(top[0])] = top[2] + 1
            if path:
                path[-1][2] = max(path[-1][2], top[2] + 1)
    return height[_k_builtins.id(v)] <= 512


# One Json node: a scalar json.loads makes, an int within Int64, a list, or a dict whose keys are str.
def _k_jone(x):
    c = _k_builtins.type(x)
    if c is dict:
        return _k_builtins.all([_k_builtins.type(k) is str for k in x])
    return c is list or c is str or c is float or c is bool or x is None or c is int and -0x8000000000000000 <= x <= 0x7FFFFFFFFFFFFFFF


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
# as C++'s abort does, exit with its code, a throw with thrown (70, D73) unless owner is this thread.
def _k_callback(f, args, owner, thrown=70):
    try:
        return f(*args)
    except _k_Error:
        if owner == _k_threading.get_ident():
            raise
        _k_end(thrown, True)
    except _k_builtins.SystemExit as e:
        _k_end(e.code if e.code.__class__ is int else 0 if e.code is None else 1, False)
    except _k_builtins.KeyboardInterrupt:
        raise
    except _k_builtins.BaseException:
        _k_end(None, True)


# A Kira Fx a runtime class calls on a thread Python owns (E6, D76): no Kira try is under it, so a throw
# ends the process with 70, a panic aborts and an exit ends it, wherever the class would have caught them.
def _k_kirafx(f, *args):
    return _k_callback(f, args, None)


# Ends the process at once, as C++'s exit (fflush, then _Exit) and abort do; a None code aborts, which
# is status 3 on Windows, where os.abort could raise Windows Error Reporting's dialog.
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


# An Fx handed to a sidecar (D75): what Python passes in is checked as a result is and a Json copied
# for Kira (D68), its result goes back as _k_out makes an argument, and its extern call owns it (D76).
class _k_fx:
    __slots__ = ("f", "specs", "where", "json", "owner")

    def __init__(self, f, specs, where):
        self.f = f
        self.specs = specs
        self.where = where
        self.json = _k_builtins.tuple([_k_hasjson(s) for s, d in specs])
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
        given = [_k_check(a, s, self.where, d) for a, (s, d) in _k_builtins.zip(args, self.specs)]
        return _k_out(self.f(*[_k_out(v) if j else v for v, j in _k_builtins.zip(given, self.json)]))


def _k_hasjson(spec):
    return spec[0] == "J" or _k_builtins.any([_k_hasjson(x) for x in spec[1:] if _k_builtins.type(x) is _k_builtins.tuple and x and _k_builtins.type(x[0]) is str])


# Ref<T> (D74): one cell, shared by every copy of the reference, a closure's capture included.
class _k_Ref:
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value


# kira:sync (D77). A Thread is a daemon thread started at once and joined only by join(); its body
# is a callback whose throw aborts as C++'s std::terminate does (D82).
class _k_Thread:
    __slots__ = ("t", "stop")

    def __init__(self, name, body):
        self.stop = False
        self.t = _k_threading.Thread(target=_k_callback, args=(body, (), None, None), name=name, daemon=True)
        self.t.start()

    def stopRequested(self):
        return self.stop

    def requestStop(self):
        self.stop = True

    def join(self):
        me = _k_threading.current_thread()
        if self.t is me:
            _k_panic("Thread.join from its own body: a thread cannot wait for itself")
        while self.t.is_alive():
            self.t.join(0.05 if me is _k_threading.main_thread() else None)


# The clock a wait's limit is measured on, the first module's for every one (D88): a test that freezes
# time.monotonic, before or after a module loads, stops no wait.
_k_deadline = _k_errors().__dict__.setdefault("deadline", _k_time.monotonic)


# A wait as kira::sync's: negative without limit, zero one check, else up to ms. The main thread waits
# in slices, between which Python runs an onSignal handler (D78) that another thread's signal left it.
def _k_waitfor(cv, pred, ms):
    if ms == 0:
        return pred()
    end = None if ms < 0 else _k_deadline() + ms / 1000
    step = 0.05 if _k_threading.current_thread() is _k_threading.main_thread() else 3600.0
    while not pred():
        left = step if end is None else end - _k_deadline()
        if left <= 0:
            return pred()
        cv.wait(min(left, step))
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


# Every write under one lock, reentrant so an onSignal handler can store while its thread holds it; an integer
# add wraps as C++'s fetch_add does, and a Float64's or Bool's compareSwap (no wrap) compares bits as it does.
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
            v = self.v
            if (_k_struct.pack("<d", v) != _k_struct.pack("<d", expected)) if self.wrap is None else v != expected:
                return False
            self.v = desired
            return True


class _k_BlockingQueue:
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


# kira:test's Suite (D80): bibo's check format, printed as kira/cpp/kira/test.hxx prints it;
# finish's result is main's exit status.
class _k_Suite:
    __slots__ = ("checks", "failures")

    def __init__(self, title):
        self.checks = 0
        self.failures = 0
        print("\n" + title + "\n")

    def check(self, ok, what):
        self.checks += 1
        if ok:
            print("  ok    " + what)
        else:
            print("  FAIL  " + what)
            self.failures += 1

    def checkStr(self, got, want, what):
        self.check(got == want, what)
        if got != want:
            print("        got  \"" + got + "\"\n        want \"" + want + "\"")

    def finish(self):
        print("\n%d checks, %d failed\n" % (self.checks, self.failures))
        return 0 if self.failures == 0 else 1
