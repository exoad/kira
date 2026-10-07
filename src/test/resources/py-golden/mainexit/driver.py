#!/usr/bin/env python3
# The mainexit golden: each generated main run as a program, its stdout, exit status and stderr.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import subprocess
import sys
import time


# C++'s abort is SIGABRT on POSIX and 3 on Windows, where intmain's own 3 is no abort.
def run(name, *args, aborts=False):
    path = os.path.join(sys.argv[1], "src", "app", name + ".kira.py")
    began = time.monotonic()
    r = subprocess.run([sys.executable, path] + list(args), capture_output=True, text=True)
    if "linger" in args:
        print("linger took under 10 s:", time.monotonic() - began < 10)
    aborted = r.returncode in (-6, 134) or (os.name == "nt" and r.returncode == 3)
    print("%s %s -> exit %s" % (name, " ".join(args), "abort" if aborts and aborted else r.returncode))
    for line in r.stdout.splitlines():
        print("  " + line)
    err = r.stderr.splitlines()
    if err:
        print("  traceback:", err[0] == "Traceback (most recent call last):",
              "| cause:", any(l.startswith("FileNotFoundError") for l in err),
              "| direct cause:", "The above exception was the direct cause of the following exception:" in err)
        print("  last:", err[-1])


run("mainexit", "x", "y z")
run("mainexit", "throw", "absent.txt")
run("mainexit", "exit")
run("mainexit", "panic", aborts=True)
run("mainexit", "linger")
run("intmain")
run("intmain", "stop")
run("argsmain", "a", "b")


class Stop(BaseException):
    pass


def sleeping(s):
    slept.append(s)
    if len(slept) == 2:
        raise Stop


spec = importlib.util.spec_from_file_location("mainexit_kira", os.path.join(sys.argv[1], "src", "app", "mainexit.kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
slept, real = [], time.sleep
time.sleep = sleeping
try:
    m.nap(0x7FFFFFFFFFFFFFFF)
except Stop:
    pass
time.sleep = real
print("nap(Int64 max) sleeps", slept)
try:
    m.quit(6)
except SystemExit as e:
    print("quit(6) is SystemExit", e.code, "to a Python caller")
