#!/usr/bin/env python3
# The callbacks golden: callbacks.kira's main run once per case as a program, its exit status
# (C++'s abort is SIGABRT on POSIX and 3 on Windows), its stdout and its last line on stderr.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import subprocess
import sys

path = os.path.join(sys.argv[1], "src", "app", "callbacks.kira.py")


def run(*args):
    r = subprocess.run([sys.executable, path] + list(args), capture_output=True, text=True)
    code = "abort" if r.returncode in (-6, 134) or (os.name == "nt" and r.returncode == 3) else r.returncode
    print("%s -> exit %s" % (" ".join(args) or "happy", code))
    for line in r.stdout.splitlines():
        print("  " + line)
    err = r.stderr.splitlines()
    if err:
        print("  stderr:", err[-1])


run()
for case in ("none", "bool", "arity", "pair", "panic", "swallow-throw", "swallow-panic", "exit", "thread-throw", "thread-panic", "thread-exit", "kept-throw"):
    run(case)
