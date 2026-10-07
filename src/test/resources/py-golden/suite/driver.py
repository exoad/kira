#!/usr/bin/env python3
# The suite golden: the program passing, then failing, its stdout as it printed it (the C++
# target's bytes) and its exit status.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import subprocess
import sys

path = os.path.join(sys.argv[1], "src", "app", "suite.kira.py")
for args in ([], ["fail"]):
    r = subprocess.run([sys.executable, path] + args, capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    print("-> exit %d" % r.returncode)
    sys.stdout.write(r.stderr)
