#!/usr/bin/env python3
# The echo golden: echo.kira's main as a program, then with a line its handler throws on, which
# ends the process with 70 where socketserver's handle_error would print it and serve on.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import subprocess
import sys

path = os.path.join(sys.argv[1], "src", "app", "echo.kira.py")


def run(*args):
    r = subprocess.run([sys.executable, path] + list(args), capture_output=True, text=True, timeout=60)
    print("%s -> exit %s" % (" ".join(args) or "echo", r.returncode))
    for line in r.stdout.splitlines():
        print("  " + line)
    err = r.stderr.splitlines()
    if err:
        print("  stderr:", err[-1])


run()
run("boom")
