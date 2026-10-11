#!/usr/bin/env python3
# The osproc golden (K3, K4): main, then a Process dropped while its child runs (killed and reaped, as C++'s
# destructor does), a spawn a test refuses through subprocess.Popen (R1), and a readStdout a SIGINT meets (D78).
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import signal
import subprocess
import sys
import threading
import time

out = sys.argv[1]
spec = importlib.util.spec_from_file_location("proc_kira", os.path.join(out, "src", "app", "proc_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("main ->", m._k_run())


def alive(pid):
    if os.name == "nt":
        import ctypes

        k = ctypes.windll.kernel32
        h = k.OpenProcess(0x00100000, False, pid)
        if not h:
            return False
        try:
            return k.WaitForSingleObject(h, 0) == 0x102
        finally:
            k.CloseHandle(h)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


print("a dropped running child is gone:", not alive(m.dropRunning()))
real = subprocess.Popen


def refuse(*args, **kwargs):
    raise OSError("no ffmpeg")


subprocess.Popen = refuse
try:
    print("subprocess.Popen refused:", m.spawned(), m.reason())
finally:
    subprocess.Popen = real
print("restored:", m.spawned())

# POSIX ends the read at the signal, as EINTR ends C++'s; Windows reads the pipe to the child's end, as C++ does.
before = signal.getsignal(signal.SIGINT)
t = threading.Thread(target=lambda: (time.sleep(0.3), signal.raise_signal(signal.SIGINT)), daemon=True)
t.start()
try:
    installed, n, kind, flag, took = m.readOnSignal().split(" ")
finally:
    t.join()
    signal.signal(signal.SIGINT, before)
posix = os.name != "nt"
ended = (n, kind) == (("-1", "Interrupted") if posix else ("0", ""))
print("a SIGINT and readStdout:", installed, ended, flag, (int(took) < 900) == posix)
