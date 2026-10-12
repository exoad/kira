#!/usr/bin/env python3
# The osserial golden (K3, K7): the module under -I -S, where no pyserial is found whether or not this Python has one;
# then a stand-in for pyserial, read as critique 14 reads it, written, reopened, closed and met by a SIGINT (D78).
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import types

path = os.path.join(sys.argv[1], "src", "app", "serial_kira.py")
ran = subprocess.run([sys.executable, "-I", "-S", path], capture_output=True, text=True)
print(ran.stdout + "exit %d" % ran.returncode, ran.stderr, sep="\n", end="")


class SerialException(OSError):
    pass


# pyserial's Serial as kira:os reads it: read(size) waits for size bytes until its timeout runs out (None: no limit),
# through select as serialposix's does; a "nofd" port has no descriptor, as serialwin32's has none.
class StandIn:
    greeting = b"hello"
    made = []

    def __init__(self, port, baud, timeout=None):
        if port == "missing":
            raise SerialException(2, "could not open port missing: [Errno 2] No such file or directory: 'missing'")
        if baud <= 0:
            raise ValueError("Not a valid baudrate: %r" % baud)
        self.ours, self.far = socket.socketpair()
        self.ours.setblocking(False)
        if StandIn.greeting:
            self.far.sendall(StandIn.greeting)
        if port != "nofd":
            self.fd = self.ours.fileno()
        self.is_open = True
        self.timeout = timeout
        StandIn.made.append(self)

    @property
    def in_waiting(self):
        try:
            return len(self.ours.recv(65536, socket.MSG_PEEK))
        except BlockingIOError:
            return 0

    def read(self, size=1):
        end = None if self.timeout is None else time.monotonic() + self.timeout
        got = b""
        while len(got) < size:
            left = None if end is None else max(end - time.monotonic(), 0)
            if not select.select([self.ours], [], [], left)[0]:
                break
            piece = self.ours.recv(size - len(got))
            if not piece:
                raise SerialException("device reports readiness to read but returned no data")
            got += piece
            if end is not None and time.monotonic() >= end:
                break
        return got

    def write(self, data):
        self.ours.sendall(data)
        return len(data)

    def close(self):
        self.is_open = False
        self.ours.close()
        self.far.close()


sys.modules["serial"] = types.ModuleType("serial")
sys.modules["serial"].Serial = StandIn
spec = importlib.util.spec_from_file_location("serial_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
before = signal.getsignal(signal.SIGINT)
t = threading.Thread(target=lambda: (time.sleep(0.3), signal.raise_signal(signal.SIGINT)), daemon=True)
t.start()
try:
    print(m.readOnSignal())
finally:
    t.join()
    signal.signal(signal.SIGINT, before)

StandIn.greeting = b""


def timed(f, *args):
    begin = time.monotonic()
    return f(*args), time.monotonic() - begin


def onthread(f, *args):
    got = []
    th = threading.Thread(target=lambda: got.append(timed(f, *args)), daemon=True)
    th.start()
    th.join(10)
    return got[0]


# "ab" now and "cd" 300 ms on: each read waits for its first byte only, then takes what waits, never the whole buffer.
def bursts(port, far, call):
    far.sendall(b"ab")
    threading.Timer(0.3, far.sendall, (b"cd",)).start()
    (a, ta), (b, tb) = call(m.readSome, port, 8, 2000), call(m.readSome, port, 8, 2000)
    return "%s then %s, at once %s, the second when it came %s" % (a, b, ta < 1.0, 0.15 < ta + tb < 1.8)


p = m.openPort("stand-in", 115200)
ps = StandIn.made[-1]
print("two bursts, on the main thread:", bursts(p, ps.far, timed))
print("and on another, through pyserial's timeout:", bursts(p, ps.far, onthread))
ps.far.sendall(b"hello")
print("a buffer smaller than what waits:", m.readSome(p, 2, 1000), m.readSome(p, 8, 1000))
(r0, t0), (r2, t2) = timed(m.readSome, p, 8, 0), timed(m.readSome, p, 8, 200)
(o0, u0), (o2, u2) = onthread(m.readSome, p, 8, 0), onthread(m.readSome, p, 8, 200)
print("nothing waiting:", r0, t0 < 0.1, r2, 0.15 < t2 < 1.0, "and on another thread", o0, u0 < 0.1, o2, 0.15 < u2 < 1.0)
ps.far.sendall(b"x")
first = onthread(m.readSome, p, 8, 1 << 62)[0]
ps.far.sendall(b"y")
print("a wait past INT_MAX ms:", first, m.readSome(p, 8, 1 << 62))
q = m.openPort("nofd", 115200)
print("a port without a descriptor, as Windows' pyserial:", bursts(q, StandIn.made[-1].far, timed))
m.shut(q)
print("write:", m.writeText(p, "ping"), ps.far.recv(16))
print("open again:", m.reopen(p, "stand-in"), "the old one closed", not ps.is_open,
      m.state(p) == "open true handle %d" % (-1 if os.name == "nt" else StandIn.made[-1].fd))
m.shut(p)
m.shut(p)
print("closed twice:", m.state(p), m.readSome(p, 4, 10), m.why(), m.writeText(p, "x"))
for port, baud in (("missing", 115200), ("stand-in", -5)):
    bad = m.openPort(port, baud)
    print("an open pyserial refuses:", m.state(bad), m.why())
