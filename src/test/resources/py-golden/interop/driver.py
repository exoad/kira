#!/usr/bin/env python3
# The interop golden: hand-written Python using the module interop.kira compiles to.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

path = os.path.join(sys.argv[1], "src", "app", "interop.kira.py")
spec = importlib.util.spec_from_file_location("interop_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def attempt(label, f, *args):
    try:
        print(label, "->", f(*args))
    except RuntimeError as e:
        print(label, "-> RuntimeError:", e)
    except IndexError as e:
        print(label, "-> IndexError:", e)


a = m.Account("ada", 100)
print(a.owner, a.balance, a.note)
print(a.deposit(50), a.withdraw(120), a.withdraw(100), a.balance)
print(a.moves(), a.move(0), a.move(1))
print(a.noteOr("none"))
a.note = "rent"
print(a.noteOr("none"))
b = m.Account(owner="bo", limit=0)
print(b.withdraw(1), b.balance, b.moves())
print("private members:", hasattr(a, "history"), hasattr(a, "_history"), hasattr(a, "limit"), hasattr(a, "_limit"))
print("module attributes:", [n for n in ("Account", "Point", "div", "_div", "_k_value", "_k_i32", "_k_divs") if hasattr(m, n)])
p = m.Point(1.5, 2.0).plus(m.Point(x=0.25, y=-1.0))
print(p.x, p.y)
attempt("7 / 2", m.div, 7, 2)
attempt("-7 / 2", m.div, -7, 2)
attempt("-7 % 2", m.rem, -7, 2)
attempt("7 / 0", m.div, 7, 0)
attempt("lowest / -1", m.div, -2 ** 31, -1)
attempt("7 % 0", m.rem, 7, 0)
attempt("Int32 max + 1", m.add, 2 ** 31 - 1, 1)
attempt("Int32 min + -1", m.add, -2 ** 31, -1)
attempt("Int64 2^62 * 2", m.mul64, 2 ** 62, 2)
attempt("Int64 2^61 * 2", m.mul64, 2 ** 61, 2)
attempt("value of 2.5", m.valueOf, 2.5)
attempt("value of None", m.valueOf, None)
attempt("orZero of None", m.orZero, None)
attempt("move past the end", a.move, 5)
print("isSome:", m.isSome(None), m.isSome(0), m.isSome(3))
print("isNone:", m.isNone(None), m.isNone(0))
print("ratio:", m.ratio(1.0, 0.0), m.ratio(-1.0, 0.0), m.ratio(0.0, 0.0) != m.ratio(0.0, 0.0), m.ratio(3.0, 4))
