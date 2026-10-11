#!/usr/bin/env python3
# The osnet golden (K3): main, then a Poller.wait(10000) on the main thread that a SIGINT raised from another thread
# 300 ms in ends at once, with the flag set and lastErrorKind Interrupted (D78), as EINTR ends C++'s.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import signal
import sys
import threading
import time

out = sys.argv[1]
spec = importlib.util.spec_from_file_location("net_kira", os.path.join(out, "src", "app", "net_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("main ->", m._k_run())

before = signal.getsignal(signal.SIGINT)
t = threading.Thread(target=lambda: (time.sleep(0.3), signal.raise_signal(signal.SIGINT)), daemon=True)
t.start()
try:
    print(m.waitOnSignal())
finally:
    t.join()
    signal.signal(signal.SIGINT, before)
