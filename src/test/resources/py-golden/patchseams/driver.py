#!/usr/bin/env python3
# The patchseams golden (D88): what a test patches in time and sys reaches the generated code where it is called,
# and every module's waits share one clock, so a frozen time.monotonic stops none, loaded before or after it.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import sys
import threading
import time

out = sys.argv[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name + "_kira", os.path.join(out, "src", "app", name + "_kira.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


m = load("seams")
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
    late = load("late")
    for name, f in (("waitUntil", m.waitNever), ("pop", m.popNone), ("a module loaded since: waitUntil", late.waitNever)):
        got = []
        t = threading.Thread(target=lambda: got.append(f(80)), daemon=True)
        t.start()
        t.join(10)
        print(name, "(80 ms) under a frozen time.monotonic returned:", got)
finally:
    time.perf_counter_ns, time.time_ns, time.sleep, time.monotonic, sys.argv = real

print("restored: monoNowNs moves", m.mono() != 123456789, "| wallNowNs", m.wall() > 42)
