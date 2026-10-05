#!/usr/bin/env python3
# The dashtext golden: the module's main, whose output is the C++ backend's run of it, then scan
# and command against the hand-written code they port (bibo's firmware/pilot/tools/dash/
# bibodash.py at master deb187c): Lidar.scan's parse, the reader's strip and "F " test, the text
# half of ws_reader's loop and ws_command, copied below unchanged as the oracle on stub pages,
# cameras and a lidar that record what each call did, over seeded lines and frames, a frame's
# bytes read with Str.of (D55) as ws_reader decodes them, ill-formed UTF-8 included. They
# agree on all of them; what only Python takes (other scripts' digits, a ping time float() alone
# reads, a number past Int64) is listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import array
import importlib.util
import os
import random
import runpy
import sys
import time

path = os.path.join(sys.argv[1], "src", "dash", "dashtext.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("dashtext_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: bibodash.py as written by hand, on stubs ---------------------------------------
ROTATIONS = (0, 90, 180, 270)
LIDAR = 255
did = []


def record_all(on):
    did.append("all %d" % on)


class AnyIndex:
    # A camera index equal to whatever it is compared with, which it keeps.
    def __eq__(self, other):
        self.seen = other
        return True


class Cam:
    def __init__(self):
        self.index = AnyIndex()

    def set_rot(self, deg):
        did.append("rot %d %d" % (self.index.seen, deg))

    def set_low(self, on):
        did.append("low %d %d" % (self.index.seen, on))

    def set_recording(self, on):
        did.append("rec %d %d" % (self.index.seen, on))


def camera_list():
    return [Cam()]


class MaskStub:
    def command(self, words):
        did.append("mask" + "".join(" [%s]" % w for w in words))


class LidarStub:
    def __init__(self):
        self.mask = MaskStub()

    def set_rot(self, deg):
        did.append("rot lidar %d" % deg)

    def set_recording(self, on):
        did.append("rec lidar %d" % on)


lidar = LidarStub()


class SetStub:
    def __init__(self, name):
        self.name = name

    def add(self, i):
        did.append("%s %d 1" % (self.name, i))

    def discard(self, i):
        did.append("%s %d 0" % (self.name, i))

    def __contains__(self, i):
        return False


class PageStub:
    def __init__(self):
        self.ctrl, self.video = None, None
        self.cams, self.h264cams = SetStub("cam"), SetStub("h264")

    def refresh(self):
        pass

    def sync_h264(self, restart=None):
        pass

    def acked(self, conn, feed):
        did.append("ack %d" % feed)

    def __setattr__(self, k, v):
        if k in ("sel", "both"):
            did.append("%s %d" % (k, v))
        if k == "rtt_ms":
            did.append("rtt %r" % v)
        object.__setattr__(self, k, v)


class ConnStub:
    def __init__(self, kind):
        self.kind = kind

    def put_text(self, tag, text):
        did.append("ping [%s]" % text.split(" ")[1])


class Handler:
    def frame(self, page, conn, frames):
        # ws_reader's loop over decoded frames, from the words on (heard and acks of opcodes
        # before them left out).
        for data in frames:
                words = data.decode("utf-8", "replace").split(" ")
                if words[0] == "h":
                    continue                  # the page's heartbeat: heard, nothing to do
                if words[0] == "a" and len(words) == 2 and words[1].isdigit():
                    page.acked(conn, int(words[1]))
                elif words[0] == "p" and len(words) in (2, 3):
                    # "p <page ms> [its last round trip, ms]": echoed for the page's clock;
                    # the round trip is kept for the link log.
                    if len(words) == 3:
                        try:
                            page.rtt_ms, page.rtt_at = float(words[2]), time.monotonic()
                        except ValueError:
                            pass
                    conn.put_text("P", "P %s %.1f" % (words[1], time.monotonic() * 1000.0))
                elif conn.kind == "ctrl":
                    self.ws_command(page, words)

    def ws_command(self, page, words):
        # Not `command`: BaseHTTPRequestHandler keeps the request's verb there.
        if words[0] == "all" and len(words) == 2:
            record_all(words[1] == "1")
        elif words[0] == "sel" and len(words) == 2 and words[1].isdigit():
            page.sel = int(words[1])
            page.refresh()
        elif words[0] == "both" and len(words) == 2:
            page.both = words[1] == "1"
            page.refresh()
        elif words[0] == "rot" and len(words) == 3 and words[1].isdigit() and words[2].isdigit():
            i, deg = int(words[1]), int(words[2])
            if deg not in ROTATIONS:
                return
            if i == LIDAR:
                lidar.set_rot(deg)
            for cam in camera_list():
                if cam.index == i:
                    cam.set_rot(deg)
        elif words[0] == "h264" and len(words) == 3 and words[1].isdigit():
            i, on = int(words[1]), words[2] == "1"
            (page.h264cams.add if on else page.h264cams.discard)(i)
            page.refresh()
            page.sync_h264(restart=i)     # the page starts its decoder over: a fresh init
        elif words[0] == "mask" and len(words) >= 2:
            # "mask capture|clear|on|off", "mask box <front> <back> <left> <right>" (metres)
            lidar.mask.command(words[1:])
        elif words[0] == "low" and len(words) == 3 and words[1].isdigit():
            for cam in camera_list():
                if cam.index == int(words[1]):
                    cam.set_low(words[2] == "1")
        elif words[0] in ("cam", "rec") and len(words) == 3 and words[1].isdigit():
            i, on = int(words[1]), words[2] == "1"
            if words[0] == "cam":
                for c in (page.ctrl, page.video):
                    if c is not None:
                        c.reset(i)
                (page.cams.add if on else page.cams.discard)(i)
                page.refresh()
                if i in page.h264cams:
                    page.sync_h264(restart=i)
            elif i == LIDAR:
                lidar.set_recording(on)
            else:
                for cam in camera_list():
                    if cam.index == i:
                        cam.set_recording(on)


def oracle_command(data, ctrl):
    did.clear()
    Handler().frame(PageStub(), ConnStub("ctrl" if ctrl else "video"), [data])
    if not did:
        return "heartbeat" if data.split(b" ")[0] == b"h" else "ignored"
    if did[0].startswith("rtt "):
        return did[1] + " " + did[0]      # the time is kept, then the ping echoed
    # rot on the lidar also asks every camera; the lidar's is the one that counts here
    return did[0]


def same_rtt(said):
    # A ping's time compared as the float it is: Kira writes the shortest text, Python repr.
    head, sep, v = said.partition(" rtt ")
    return head + sep + repr(float(v)) if sep else said


def oracle_scan(raw):
    # Lidar.run's reader, then Lidar.scan's parse (its mask, records and sends left out).
    text = raw.decode("ascii", "replace").strip()
    if not text.startswith("F "):
        return None
    angles, dists, quals = array.array("H"), array.array("H"), array.array("B")
    words = text.split(" ")
    rot_mhz = int(words[2]) if len(words) > 2 and words[2].isdigit() else 0
    for p in words[3:]:
        f = p.split(",")
        if len(f) >= 2 and f[0].isdigit() and f[1].isdigit():
            d = int(f[1])
            if d <= 0 or d >= 65536:
                continue
            angles.append(int(f[0]) % 36000)
            dists.append(d)
            quals.append(min(int(f[2]), 255) if len(f) > 2 and f[2].isdigit() else 0)
    return rot_mhz, list(angles), list(dists), list(quals)


def mine_scan(raw):
    line = raw.decode("ascii", "replace")
    if not m.isScan(line):
        return None
    s = m.scan(line)
    return s.rotMhz, list(s.angles), list(s.dists), list(s.quals)


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)


def digits():
    return rng.choice(["%d" % rng.randint(0, 40000), "%d" % rng.randint(0, 70000), "0", "00042", "",
                       "%d" % rng.randint(0, 10 ** 30), "-1", "+5", "x", "1x", "255", "65535", "65536"])


scans = 0
for k in range(4000):
    points = []
    for j in range(rng.randint(0, 12)):
        p = ",".join(digits() for _ in range(rng.choice([1, 2, 2, 3, 3, 3, 4])))
        points.append(p)
    head = rng.choice(["F", "F", "F", "ERR", "f", "F2"])
    # a word that is a number stays inside Int64: a rot past it is listed at the end
    words = [head, digits()[:18], digits()[:18]] + points
    text = rng.choice([" ", " ", "  "]).join(words[:rng.randint(1, len(words))])
    raw = (rng.choice(["", " ", "\t", "\x0b"]) + text + rng.choice(["", "\r", " \r", "\x1f"])).encode("ascii")
    if rng.random() < 0.05:
        raw += b"\xff,1"
    assert mine_scan(raw) == oracle_scan(raw), raw
    scans += 1
print("scan against Lidar.scan: %d lines, every point the same" % scans)

# A page's frame is bytes: frame reads them with Str.of (D55), as ws_reader's
# data.decode("utf-8", "replace"), ill-formed UTF-8 included.
VERBS = [b"h", b"a", b"p", b"all", b"sel", b"both", b"rot", b"h264", b"mask", b"low", b"cam", b"rec", b"zap", b"",
         b"H", b"h\xff", b"\xc3\xa9"]
ARGS = [b"1", b"0", b"255", b"3", b"90", b"180", b"270", b"45", b"0270", b"x", b"", b"4.5", b"nan", b"1e3", b"-1",
        b"+2", b"capture", b"box", b"0.3", b"007", b"12", b"\xff", b"1\x80", b"\xe2\x82", b"caf\xc3\xa9",
        b"\xed\xa0\x80", b"\xc0\xaf"]
frames = 0
for k in range(20000):
    words = [rng.choice(VERBS)] + [rng.choice(ARGS) for _ in range(rng.choice([0, 1, 1, 2, 2, 2, 3, 5]))]
    data = b" ".join(words)
    ctrl = rng.random() < 0.8
    assert same_rtt(m.frame(data, ctrl)) == same_rtt(oracle_command(data, ctrl)), (data, ctrl)
    frames += 1
print("frame against ws_reader's words and ws_command: %d frames, ill-formed UTF-8 among them, every action the same" % frames)

for text in ("sel ٣", "rot 255 ٩٠", "p 1 1_0", "p 1 1e-400", "sel 99999999999999999999"):
    data = text.encode()
    print("python alone reads %s: %s, kira %s" % (ascii(text), oracle_command(data, True), m.frame(data, True)))
raw = b"F 1 99999999999999999999 1,2"
print("python alone reads %s: rot %d, kira rot %d" % (raw.decode(), oracle_scan(raw)[0], mine_scan(raw)[0]))
