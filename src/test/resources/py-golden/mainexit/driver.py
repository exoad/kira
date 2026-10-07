#!/usr/bin/env python3
# The mainexit golden: each generated main run as a program, its stdout, exit status and stderr.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import subprocess
import sys


def run(name, *args):
    path = os.path.join(sys.argv[1], "src", "app", name + ".kira.py")
    r = subprocess.run([sys.executable, path] + list(args), capture_output=True, text=True)
    print("%s %s -> exit %d" % (name, " ".join(args), r.returncode))
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
run("intmain")
run("intmain", "stop")
