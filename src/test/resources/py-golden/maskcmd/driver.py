#!/usr/bin/env python3
# The maskcmd golden: the module's main, whose output is the C++ backend's run of it, then
# command against the hand-written Mask.command it ports (bibo's firmware/pilot/tools/dash/
# lidarmask.py at master deb187c: command and _clean_box copied below unchanged as the oracle,
# on a Mask that records what the command did), over seeded word lists. They agree on every
# list of ASCII words without whitespace or underscores; Python's float() also takes those,
# other scripts' digits, and text that underflows to 0.0 (1e-400), all of which toFloat64,
# strict as std::from_chars, refuses: those are listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import math
import os
import random
import runpy
import sys
import threading

path = os.path.join(sys.argv[1], "src", "dash", "maskcmd.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("maskcmd_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: lidarmask.py as written by hand -----------------------------------------------
BOX_LIMIT_M = 1.5
DEFAULT_BOX = {"front": 0.20, "back": 0.20, "left": 0.10, "right": 0.10}
SIDES = ("front", "back", "left", "right")


def _clean_box(box):
    out = dict(DEFAULT_BOX)
    if isinstance(box, dict):
        for k in SIDES:
            try:
                out[k] = round(min(max(float(box.get(k, out[k])), 0.0), BOX_LIMIT_M), 3)
            except (TypeError, ValueError):
                pass
    return out


class Mask:
    def __init__(self):
        self.lock = threading.Lock()
        self.on, self.box, self.wedges = True, None, []
        self.capturing, self.cap, self.result, self.version = False, None, None, 0
        self.did = None

    def start_capture(self):
        self.did = "capture"

    def _build(self):
        pass

    def save(self):
        if self.did is None:
            self.did = "box" if self.box is not None else "clear" if not self.capturing and self.cap is None else "?"

    def command(self, words):
        # "capture", "clear", "on", "off", "box <front> <back> <left> <right>"; True if understood.
        if words == ["capture"]:
            try:
                self.start_capture()
            except ImportError:
                # No maskcapture.kira.py (tools/kira_gen.py was not run): no capture starts,
                # and the control link that asked goes on reading.
                return False
        elif words == ["clear"]:
            # Also ends a capture still running, so its wedges never come back after.
            with self.lock:
                self.wedges = [w for w in self.wedges if w["src"] != "capture"]
                self.capturing, self.cap, self.result = False, None, None
                self._build()
            self.save()
        elif words in (["on"], ["off"]):
            with self.lock:
                self.on = words[0] == "on"
                self.version += 1
            self.did = words[0]
            self.save()
        elif len(words) == 5 and words[0] == "box":
            try:
                vals = [float(x) for x in words[1:]]
            except ValueError:
                return False
            if not all(math.isfinite(v) for v in vals):
                return False
            with self.lock:
                self.box = _clean_box(dict(zip(SIDES, vals)))
                self._build()
            self.save()
        else:
            return False
        return True


def oracle(words):
    mask = Mask()
    if not mask.command(list(words)):
        return "refused"
    if mask.did == "box":
        return ["box"] + [repr(mask.box[k]) for k in SIDES]
    return mask.did


def mine(words):
    said = m.command(list(words))
    if said.startswith("box"):
        return ["box"] + [repr(float(v)) for v in said.split(" ")[1:]]
    return said


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
WORDS = ["capture", "clear", "on", "off", "box", "", "Box", "nan", "inf", "-inf", "1e400", "-1e400",
         "abc", "0x1", "1,5", "-0", "-0.0", ".5", "5.", ".", "1e", "+1", "+-1", "nan(7)"]


def number():
    v = rng.uniform(-0.5, 2.0)
    return rng.choice(["%.6f" % v, "%.3f" % v, "%.4f" % v, "%g" % v, "%d" % round(v), "%.2e" % v,
                       repr(v), "%.4f" % (rng.randint(0, 3000) / 2000.0), "%.5f" % (rng.randint(0, 3000) / 2000.0)])


agree = 0
boxes = 0
for k in range(6000):
    n = rng.choice([1, 1, 2, 4, 5, 5, 5, 6])
    words = []
    for j in range(n):
        if j == 0 and n == 5 and rng.random() < 0.9:
            words.append("box")
        elif n == 5 and rng.random() < 0.85:
            words.append(number())
        else:
            words.append(rng.choice(WORDS + [number()]))
    want = oracle(words)
    got = mine(words)
    assert got == want, (words, got, want)
    agree += 1
    boxes += isinstance(want, list)
print("command against Mask.command: %d word lists, %d boxes among them, every answer the same" % (agree, boxes))

for words in (["box", "1_0", "0", "0", "0"], ["box", "0.5\t", "0", "0", "0"], ["box", "\n1", "0", "0", "0"],
              ["box", "١", "0", "0", "0"], ["box", "1e-400", "0", "0", "0"]):
    print("python's float() alone takes %s: %s, kira %s" % (ascii(words[1]), " ".join(oracle(words)), mine(words)))
