#!/usr/bin/env python3
# The osnet golden (K3): main; a Poller.wait and a TcpStream.read a SIGINT 300 ms in ends (D78), a closed socket's
# handle as each platform's C++ polls it, a wait past INT_MAX ms off the main thread, and a read past FD_SETSIZE.
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


def onsignal(f):
    before = signal.getsignal(signal.SIGINT)
    t = threading.Thread(target=lambda: (time.sleep(0.3), signal.raise_signal(signal.SIGINT)), daemon=True)
    t.start()
    try:
        return f()
    finally:
        t.join()
        signal.signal(signal.SIGINT, before)


print(onsignal(m.waitOnSignal))
print(onsignal(m.readOnSignal))
got = m.closedBeside()
want = "bound true sent true: %s, kind ''" % ("closed 4, live 1" if os.name == "nt" else "live 1")
print("a closed socket's handle beside a live one, as C++ polls them:", True if got == want else got)
res = []
t = threading.Thread(target=lambda: res.append(m.waitLong()), daemon=True)
t.start()
t.join(10)
print("off the main thread:", res)

# POSIX descriptors past FD_SETSIZE, which select refuses and poll takes; a Windows handle is no such index.
fillers = []
if os.name != "nt":
    import resource

    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    want = 2048 if hard == resource.RLIM_INFINITY else min(2048, hard)
    if soft != resource.RLIM_INFINITY and soft < want:
        resource.setrlimit(resource.RLIMIT_NOFILE, (want, hard))
    fillers = [os.open(os.devnull, os.O_RDONLY) for _ in range(1100)]
try:
    said, _, handle = m.echoRead().rpartition(" from ")
finally:
    for f in fillers:
        os.close(f)
print(said + ", past FD_SETSIZE:", os.name == "nt" or int(handle) >= 1024)
