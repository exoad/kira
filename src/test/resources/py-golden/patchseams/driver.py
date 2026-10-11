#!/usr/bin/env python3
# The patchseams golden (D88): what a test patches in time and sys reaches the generated code, which reads each
# where it is called; a wait's limit runs on the clock the runtime bound at load, so a frozen
# time.monotonic, which bibo's world fakes install, stops no Mutex.waitUntil nor BlockingQueue.pop.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys
import threading
import time

spec = importlib.util.spec_from_file_location("seams_kira", os.path.join(sys.argv[1], "src", "app", "seams_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

real = (time.perf_counter_ns, time.time_ns, time.sleep, time.monotonic, sys.argv)
slept = []
time.perf_counter_ns = lambda: 123456789
time.time_ns = lambda: 42
time.sleep = slept.append
time.monotonic = lambda: 1000.0
sys.argv = ["patched", "a", "b"]
try:
    print("monoNowNs", m.mono(), "monoNowMs", m.monoMs(), "wallNowNs", m.wall())
    m.nap(1500)
    print("sleepMs(1500) slept", slept)
    print("args", m.argCount())
    for name, f in (("waitUntil", m.waitNever), ("pop", m.popNone)):
        got = []
        t = threading.Thread(target=lambda: got.append(f(80)), daemon=True)
        t.start()
        t.join(10)
        print(name, "(80 ms) under a frozen time.monotonic returned:", got)
finally:
    time.perf_counter_ns, time.time_ns, time.sleep, time.monotonic, sys.argv = real

print("restored: monoNowNs moves", m.mono() != 123456789, "| wallNowNs", m.wall() > 42)
