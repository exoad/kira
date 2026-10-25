#!/usr/bin/env python3
# The paths golden (K5): main's checks hold on posixpath and ntpath alike; then each kira:path function is this host's
# os.path, relPath None where relpath raises, joinPaths of no parts a panic.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys

spec = importlib.util.spec_from_file_location("paths_kira", os.path.join(sys.argv[1], "src", "app", "paths_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("paths ->", m._k_run())
paths = ["", ".", "..", "a", "a/", "/a", "a/b", "a\\b", "a/./b/../c", "//x/y", "C:", "C:\\", "C:a", "C:\\a\\b.txt", "D:/x",
         "\\\\server\\share\\f", "~", "~/x", "~nobody/x", "x/~", ".bashrc", "a.b/c", "x.tar.gz", "a//b", "a/b/", "é/ü.txt"]


def relpath(p, start):
    try:
        return os.path.relpath(p, start)
    except ValueError:
        return None


cases = [
    ("joinPath", [(m.joined(a, b), os.path.join(a, b)) for a in paths for b in paths]),
    ("joinPaths", [(m.joinedAll([a, b, "c"]), os.path.join(a, b, "c")) for a in paths for b in paths]),
    ("baseName", [(m.base(p), os.path.basename(p)) for p in paths]),
    ("dirName", [(m.dirOf(p), os.path.dirname(p)) for p in paths]),
    ("stripExt", [(m.stem(p), os.path.splitext(p)[0]) for p in paths]),
    ("normPath", [(m.norm(p), os.path.normpath(p)) for p in paths]),
    ("absPath", [(m.absOf(p), os.path.abspath(p)) for p in paths]),
    ("relPath", [(m.rel(a, b), relpath(a, b)) for a in paths for b in paths]),
    ("expandUser", [(m.home(p), os.path.expanduser(p)) for p in paths]),
    ("slashed", [(m.slash(p), p.replace(os.sep, "/")) for p in paths]),
]
for name, got in cases:
    print(name, "is os.path's:", sum(a == b and type(a) is type(b) for a, b in got), "of", len(got))
print("relPath across drives or of nothing is None:", m.rel("C:\\a", "D:\\b") is None if os.name == "nt" else True, m.rel("", "a"))
try:
    m.joinedAll([])
    print("joinPaths of no parts returned")
except RuntimeError as e:
    print("joinPaths of no parts:", e)
