#!/usr/bin/env python3
# The wstext golden: the module's main, whose output is the C++ backend's run of it, then send
# and close against the hand-written Page.send and Page.close they port (bibo's firmware/pilot/
# tools/dash/webdrive_fake.py at master deb187c, with its ws_frame, copied below unchanged onto a
# socket that keeps what is sent), over seeded text in ASCII and past it, every length form:
# the same bytes. Then the round trip's other half, in Python, as Kira cannot read text out of
# bytes yet: each frame's payload decodes back to the text and its reason. Str.bytes() is the
# UTF-8 on both targets, so its size is the same everywhere, where the py target's length()
# counts code points as Python's len() does.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import runpy
import sys
import threading

path = os.path.join(sys.argv[1], "src", "dash", "wstext.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("wstext_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: webdrive_fake.py as written by hand -------------------------------------------
def ws_frame(opcode, payload):
    n = len(payload)
    if n < 126:
        head = bytes([0x80 | opcode, n])
    elif n < 65536:
        head = bytes([0x80 | opcode, 126]) + n.to_bytes(2, "big")
    else:
        head = bytes([0x80 | opcode, 127]) + n.to_bytes(8, "big")
    return head + payload


class Kept:
    def __init__(self):
        self.sent = b""

    def sendall(self, data):
        self.sent += data


class Page:
    def __init__(self, sock):
        self.sock = sock
        self.wlock = threading.Lock()

    def send(self, text):
        try:
            with self.wlock:
                self.sock.sendall(ws_frame(1, text.encode()))
            return True
        except OSError:
            return False

    def close(self, code, reason):
        try:
            with self.wlock:
                self.sock.sendall(ws_frame(8, code.to_bytes(2, "big") + reason.encode()))
        except OSError:
            pass


def sent(call, *args):
    sock = Kept()
    getattr(Page(sock), call)(*args)
    return sock.sent


def payload(frame):
    n, at = frame[1] & 0x7F, 2
    if n == 126:
        n, at = int.from_bytes(frame[2:4], "big"), 4
    elif n == 127:
        n, at = int.from_bytes(frame[2:10], "big"), 10
    return frame[at:at + n]


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
ALPHABET = "abc XYZ 0189 {}\":,é✓€ü\U0001F697\t\n"
frames = 0
for k in range(3000):
    n = rng.choice([0, 1, 5, 40, 125, 126, 127, 200, rng.randint(0, 400), 21900, 22000])
    text = "".join(rng.choice(ALPHABET) for _ in range(n))
    mine = bytes(m.send(text))
    assert mine == sent("send", text), ascii(text[:20])
    assert payload(mine).decode("utf-8") == text
    code = rng.choice([1000, 1001, 1008, 1011, 4000, 4001, rng.getrandbits(16)])
    reason = text[:rng.randint(0, 120)]
    mine = bytes(m.close(code, reason))
    assert mine == sent("close", code, reason), (code, ascii(reason[:20]))
    body = payload(mine)
    assert int.from_bytes(body[:2], "big") == code and body[2:].decode("utf-8") == reason
    frames += 2
print("send and close against Page.send and Page.close: %d frames, every byte the same, each read back" % frames)
text = "café ✓ \U0001F697"
print("bytes() is the UTF-8: %d bytes for %d code points, as len(text.encode()) and len(text) are %d and %d" % (
    len(payload(bytes(m.send(text)))), len(text), len(text.encode()), len(text)))
