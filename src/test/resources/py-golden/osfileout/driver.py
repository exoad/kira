#!/usr/bin/env python3
# The osfileout golden (critique 12): main, then a FileOut as a test sees it: builtins.open with a positional binary
# mode and no buffer, a write that fails as a full disk does, and isStale reading os.path.exists when it is called.
#
#   python driver.py <the directory kira --target py --out wrote>
import builtins
import importlib.util
import os
import sys
import tempfile

out = sys.argv[1]
spec = importlib.util.spec_from_file_location("fileout_kira", os.path.join(out, "src", "app", "fileout_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("main ->", m._k_run())

root = tempfile.mkdtemp(prefix="kira-fileout-driver-")
log = os.path.join(root, "spy.log")
real = builtins.open
opens = []


def spy(*args, **kwargs):
    f = real(*args, **kwargs)
    if str(args[0]).endswith("spy.log"):
        opens.append((args[1:], kwargs, type(f).__name__))
    return f


builtins.open = spy
try:
    print("appended:", m.append(log, "seen"))
finally:
    builtins.open = real
print("the open a spy sees:", opens)
with open(log, "rb") as f:
    print("its bytes:", f.read())


class Full:
    def __init__(self, f):
        self.f = f

    def __getattr__(self, name):
        return getattr(self.f, name)

    def write(self, data):
        raise OSError(28, "No space left on device")


builtins.open = lambda *args, **kwargs: Full(real(*args, **kwargs))
try:
    print("a full disk:", m.append(log, "lost"), m.reason(), m.kind())
finally:
    builtins.open = real

f = m.handle(log)
exists = os.path.exists
print(type(f).__name__, "stale:", f.isStale())
os.path.exists = lambda p: False
try:
    print("its path gone:", f.isStale())
finally:
    os.path.exists = exists
f.close()
print("closed:", f.isStale())
os.remove(log)
os.rmdir(root)
