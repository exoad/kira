#!/usr/bin/env python3
# The osserial golden (K3): the module run where no pyserial can be found (-I -S: no site-packages), so the lines are
# the same whether or not this Python has one installed.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import subprocess
import sys

path = os.path.join(sys.argv[1], "src", "app", "serial_kira.py")
ran = subprocess.run([sys.executable, "-I", "-S", path], capture_output=True, text=True)
print(ran.stdout + "exit %d" % ran.returncode, ran.stderr, sep="\n", end="")
