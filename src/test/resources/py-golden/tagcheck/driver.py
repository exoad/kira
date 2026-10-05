#!/usr/bin/env python3
# The tagcheck golden: the module's main, whose output is the C++ backend's run of it, then
# tagOf, label and note against the hand-written tag_of and checked they port (bibo's
# firmware/pilot/tools/dash/tagset.py at master deb187c, copied below unchanged with the
# constants they read; BY_ID holds the 48 printed ids), over seeded ASCII text: the same answer,
# refusal text included. Past ASCII, Python's strip() and repr() also take Unicode's whitespace
# and escape its unprintable characters, and a length counts code points on the py target as in
# Python (the C++ target counts UTF-8 bytes): those are listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import re
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dash", "tagcheck.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("tagcheck_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: tagset.py as written by hand --------------------------------------------------
NOTE_MAX = 80                 # characters
BY_ID = {i: i for i in range(48)}
LABEL_IDS = ("none", "landmark", "home", "waypoint", "slow", "stop", "door", "test", "keep_out", "dock",
             "junction", "calibration", "bobo")
ID_TEXT = re.compile(r"^(0|[1-9][0-9]{0,5})$")
CONTROL = re.compile(r"[\x00-\x1f\x7f]")


class Refused(ValueError):
    """A request this registry will not take: HTTP 400, its text the reason, short, for the page."""


def tag_of(text):
    if not isinstance(text, str) or not ID_TEXT.match(text) or int(text) not in BY_ID:
        raise Refused(f"no tag {text!r}")
    return BY_ID[int(text)]


def checked(body):
    # The fields of one POST, each optional, at least one: label, note, placed.
    if not isinstance(body, dict) or not body:
        raise Refused("no label, note or placed")
    out = {}
    for k, v in body.items():
        if k == "label":
            if not isinstance(v, str) or v not in LABEL_IDS:
                raise Refused(f"unknown label {v!r}")
            out[k] = v
        elif k == "note":
            if not isinstance(v, str):
                raise Refused("note not text")
            v = v.strip()
            if CONTROL.search(v):
                raise Refused("note not one line")
            if len(v) > NOTE_MAX:
                raise Refused(f"note over {NOTE_MAX} characters")
            out[k] = v
        elif k == "placed":
            if not isinstance(v, bool):
                raise Refused("placed not true or false")
            out[k] = v
        else:
            raise Refused(f"unknown field {k!r}")
    return out


def oracle(f):
    try:
        return True, str(f())
    except Refused as e:
        return False, str(e)


def mine(a):
    return a.ok, a.text


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
ASCII = "".join(chr(c) for c in range(128))
PIECES = ["0", "1", "12", "47", "48", "007", "9", "\n", " ", "'", '"', "\\", "\t", "x", "home", "keep_out",
          "keep-out", "\x7f", "\x00", "\x0b", "\x1f", "\r\n"]


def text():
    k = rng.random()
    if k < 0.4:
        return "".join(rng.choice(PIECES) for _ in range(rng.randint(0, 4)))
    if k < 0.7:
        return "".join(rng.choice(ASCII) for _ in range(rng.randint(0, 6)))
    return "".join(rng.choice(" \tab\x0c") for _ in range(rng.randint(0, 3))) + \
        "".join(rng.choice(ASCII[32:127]) for _ in range(rng.randint(70, 90))) + \
        "".join(rng.choice(" \r\n\x1c\x1d") for _ in range(rng.randint(0, 3)))


ids = labels = notes = 0
for k in range(20000):
    t = text()
    assert mine(m.tagOf(t)) == oracle(lambda: tag_of(t)), ascii(t)
    ids += 1
    t = rng.choice([text(), rng.choice(LABEL_IDS)])
    assert mine(m.label(t)) == oracle(lambda: checked({"label": t})["label"]), ascii(t)
    labels += 1
    t = text()
    assert mine(m.note(t)) == oracle(lambda: checked({"note": t})["note"]), ascii(t)
    notes += 1
for c in range(128):
    assert m.pyRepr(chr(c)) == repr(chr(c)), c
    assert m.pyRepr("'" + chr(c)) == repr("'" + chr(c)), c
print("tagOf against tag_of: %d texts; label and note against checked: %d and %d, every answer the same" % (
    ids, labels, notes))
print("pyRepr against repr: every ASCII character, alone and after a quote, the same")

for t in ("\xa0spaced　", "\x85", "café " * 16):
    (ok, want), (got_ok, got) = oracle(lambda: checked({"note": t})["note"]), mine(m.note(t))
    print("past ASCII note %s: python %s %d characters, kira %s %d" % (ascii(t[:9]), ok, len(want), got_ok, len(got)))
print("past ASCII repr %s: python %s, kira %s" % (ascii("\xa0"), ascii(repr("\xa0")), ascii(m.pyRepr("\xa0"))))
