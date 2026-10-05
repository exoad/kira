#!/usr/bin/env python3
# The drivewords golden: the module's main, whose output is the C++ backend's run of it, then
# read against the hand-written reading it ports (bibo's firmware/pilot/tools/dash/
# webdrive_fake.py at master deb187c: the text part of Handler.drive's frame loop, message, and
# Car.control's and Car.command's ints, copied below unchanged onto a car and a page that record
# what each call did), over seeded ASCII frames: the same action for every one. int() also takes
# underscores, other scripts' digits and ints past Int64, and split() Unicode's whitespace:
# those are listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import runpy
import sys
import threading

path = os.path.join(sys.argv[1], "src", "dash", "drivewords.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("drivewords_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: webdrive_fake.py as written by hand, on stubs ----------------------------------
did = []


class PageStub:
    hidden = 0

    def send(self, text):
        pass


class CarStub:
    def __init__(self):
        self.lock = threading.Lock()
        self.t0 = 0.0

    def take(self, page):
        did.append("hold take")
        return True, "held"

    def release(self, page, why):
        did.append("hold release" if why == "hold 0" else "hidden")

    def status(self, page):
        return ""

    def control(self, page, words):
        # c <seq> <steer> <throttle> <buttons> <epoch> <mode>
        try:
            seq, steer, thr, buttons, epoch, mode = (int(w) for w in words[1:7])
        except ValueError:
            did.append("control dropped")
            return
        did.append("control %d %d %d %d %d %d" % (seq, steer, thr, buttons, epoch, mode))

    def command(self, page, words):
        try:
            cid, verb, a0, a1, a2, epoch = (int(w) for w in words[1:7])
        except ValueError:
            did.append("command dropped")
            return None
        did.append("command %d %d %d %d %d %d" % (cid, verb, a0, a1, a2, epoch))
        return None


car = CarStub()


def mono_ms():
    return 0.0


class Handler:
    def frames(self, page, frames):
        # The text part of the frame loop (its other opcodes, counts and clock left out).
        newest_c = None
        for op, data in frames:
                    words = data.decode("utf-8", "replace").split()
                    if not words:
                        continue
                    if words[0] == "c" and len(words) >= 7:
                        newest_c = words      # several in one read: only the newest counts
                        continue
                    self.message(page, words)
        if newest_c is not None:
            with car.lock:
                car.control(page, newest_c)

    def message(self, page, words):
        w0 = words[0]
        with car.lock:
            if w0 == "hold" and len(words) >= 2:
                if words[1] == "1":
                    ok, text = car.take(page)
                    page.send(f"hold {int(ok)} {text}")
                else:
                    car.release(page, "hold 0")
                    page.send("hold 0 released")
            elif w0 == "cmd" and len(words) >= 7:
                ack = car.command(page, words)
                if ack:
                    page.send(ack)
            elif w0 == "p" and len(words) >= 2:
                page.send(f"P {words[1]} {round(mono_ms() - car.t0)}")
                did.append("ping " + words[1])
            elif w0 == "hidden":
                page.hidden += 1
                car.release(page, "the page said hidden")
            else:
                return
            page.send(car.status(page))


def oracle(text):
    did.clear()
    Handler().frames(PageStub(), [(1, text.encode("utf-8"))])
    if not did:
        return "nothing" if not text.split() else "ignored"
    return did[0]


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
VERBS = ["c", "hold", "cmd", "p", "hidden", "C", "x", ""]
ARGS = ["1", "0", "-250", "+1600", "007", "3.0", "x", "-", "+", "9223372036854775807", "-9223372036854775808",
        "12a", "1500", "65535"]
SPACE = [" ", " ", "  ", "\t", "\r\n", "\x0b", "\x0c", "\x1c", "\x1f"]
frames = 0
for k in range(30000):
    words = [rng.choice(VERBS)] + [rng.choice(ARGS) for _ in range(rng.choice([0, 1, 2, 5, 6, 6, 6, 7]))]
    text = rng.choice(["", " ", "\t"]) + "".join(w + rng.choice(SPACE) for w in words)
    assert m.read(text) == oracle(text), ascii(text)
    frames += 1
for c in range(128):
    text = "c" + chr(c) + "1 2 3 4 5 6"
    assert m.words(text) == text.split() and m.read(text) == oracle(text), c
print("read against the frame loop and message: %d frames and every ASCII separator, every action the same" % frames)

for text in ("c 1_0 2 3 4 5 6", "cmd ٤ 2 1 0 0 3", "c 1 2 3 4 5 9223372036854775808", "hold\xa01", "p　1"):
    print("python alone reads %s: %s, kira %s" % (ascii(text), oracle(text), m.read(text)))
