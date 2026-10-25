#!/usr/bin/env python3
# The osfiles golden (K3, K4): osfiles2's main (cpp-golden/osfiles2's source and lines), osfiles' py-only one, the
# values to Python, a reason read across modules (D86), renames failed or held up (R1, critique 5), moduleDir's links.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

out = sys.argv[1]
app = os.path.join(out, "src", "app")


def load(name, where=app, alias=None):
    spec = importlib.util.spec_from_file_location(alias or name + "_kira", os.path.join(where, name + "_kira.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def temporaries():
    return [n for n in os.listdir(d) if n.endswith(".tmp")]


both = load("osfiles2")
py = load("osfiles")
sys.argv = [os.path.join(out, "osfiles2")]
print("osfiles2 ->", both._k_run())
print("osfiles ->", py._k_run())
d = os.path.join(out, "osfiles2.d")
p = os.path.join(d, "b.txt")
nope = os.path.join(d, "nope")
head = both.head(p, 3)
print(type(head).__name__, bytes(head), type(both.changed(p)).__name__, both.head(nope, 1), both.changed(nope))
print("readFileHead of more than memory holds:", bytes(both.head(p, 4000000000 * 300)))
print("one module's failure, another's reason:", both.missing(nope), py.kind(), py.reason().startswith("OSError: [Errno 2] "))
real = os.replace


def refuse(source, target):
    raise OSError("forced")


os.replace = refuse
try:
    print("os.replace refused:", both.atomic(p, "x"), py.reason(), py.kind(), temporaries())
finally:
    os.replace = real
print("restored:", both.atomic(p, "y"), open(p, encoding="utf-8").read(), temporaries())

# Windows refuses to rename over a file another handle holds open; it is let go at the first refusal, which C++'s
# retry outlasts. POSIX renames at once.
held = open(p, "rb")


def letgo(source, target):
    try:
        real(source, target)
    except PermissionError:
        held.close()
        raise


os.replace = letgo
try:
    done = both.atomic(p, "held")
finally:
    os.replace = real
    held.close()
print("a target held open, let go at its first refusal:", done, open(p, encoding="utf-8").read(), temporaries())
os.mkdir(p + ".tmp")
try:
    print("a directory at <path>.tmp:", both.atomic(p, "apart"), open(p, encoding="utf-8").read())
finally:
    os.rmdir(p + ".tmp")

link = os.path.join(out, "linked")
if os.name == "nt":
    import _winapi

    _winapi.CreateJunction(app, link)
else:
    os.symlink(app, link)
try:
    print("moduleDir through a linked directory is the real one:", load("osfiles", link, "osfiles_linked").here() == py.here())
finally:
    os.unlink(link)
nofile = {"__name__": "osfiles_nofile"}
exec(compile(open(os.path.join(app, "osfiles_kira.py"), encoding="utf-8").read(), "osfiles_kira", "exec"), nofile)
print("and the current directory for code exec'd without a file:", nofile["here"]() == os.path.realpath("."))
