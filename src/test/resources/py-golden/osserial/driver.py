#!/usr/bin/env python3
# The osserial golden (K3): the module under -I -S, where no pyserial is found whether or not this Python has one;
# then a stand-in for pyserial, its descriptor a socket's (Windows selects one too), read and met by a SIGINT (D78).
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


class StandIn:
    def __init__(self, port, baud, timeout=None):
        self.ours, self.far = socket.socketpair()
        self.ours.setblocking(False)
        self.far.sendall(b"hello")
        self.fd = self.ours.fileno()
        self.is_open = True
        self.timeout = timeout

    @property
    def in_waiting(self):
        try:
            return len(self.ours.recv(65536, socket.MSG_PEEK))
        except BlockingIOError:
            return 0

    # pyserial waits out its timeout for the bytes; this waits for any, 3 s at most.
    def read(self, n):
        if self.timeout != 0:
            select.select([self.ours], [], [], 3 if self.timeout is None else self.timeout)
        try:
            return self.ours.recv(n)
        except BlockingIOError:
            return b""

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
