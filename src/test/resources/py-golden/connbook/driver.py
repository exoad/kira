#!/usr/bin/env python3
# main (the C++ run), then the Kira Conn against bibodash.py's at 7f26371, copied unchanged below,
# every dict's keys in order after each call.   python driver.py <kira --target py --out dir>
import collections
import importlib.util
import math
import os
import random
import runpy
import socket
import struct
import sys
import threading

path = os.path.join(sys.argv[1], "src", "dash", "connbook.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("connbook_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: bibodash.py's Conn as written by hand -----------------------------------------
H264_BASE = 128               # socket feed 128 + camera index is that camera's H.264
H264_IN_FLIGHT = 8            # fragments a page may hold unacknowledged (~0.27 s at 30 fps)
H264_BACKLOG = 30             # a page further behind than this skips to the next keyframe
LIDAR = 255                   # the lidar's slot among a page's feeds
VIDEO_IN_FLIGHT = 1
LIDAR_IN_FLIGHT = 2           # ~2 KB a scan: two in flight is 40 ms of a 10 Hz lidar


def ws_frame(opcode, payload):
    return opcode, bytes(payload)


class Conn:
    # One WebSocket of a page. Everything waits in a newest-wins slot: text by kind ("P" the
    # ping echo, "h" the heartbeat, "status"), frames by feed, so a slow link skips and never
    # queues. A frame goes out only while its feed has room in flight; the page acknowledges
    # each one once drawn ("a <feed>").
    def __init__(self, kind, sock):
        self.kind, self.sock = kind, sock
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.text = {}
        self.latest = {}              # feed -> (message, capture board ms)
        self.unacked = {}             # feed -> deque of (bytes, capture board ms), sent, not drawn
        # H.264 feed -> {"q": fragments waiting, "key": waiting for a keyframe}. Unlike a JPEG,
        # a fragment needs the ones before it, so these queue in order; a page that falls
        # H264_BACKLOG behind drops the queue and resumes at the next keyframe.
        self.h264 = {}
        self.pong = False
        self.alive = True

    def close(self):
        self.alive = False
        self.wake.set()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def put_text(self, key, text):
        with self.lock:
            self.text[key] = text
        self.wake.set()

    def put_pong(self):
        with self.lock:
            self.pong = True
        self.wake.set()

    def put_frame(self, feed, msg, ms):
        # True when it replaced a frame that never went out: a drop.
        with self.lock:
            dropped = feed in self.latest
            self.latest[feed] = (msg, ms)
        self.wake.set()
        return dropped

    def room(self, feed):
        cap = H264_IN_FLIGHT if feed >= H264_BASE and feed != LIDAR else (
            LIDAR_IN_FLIGHT if feed == LIDAR else VIDEO_IN_FLIGHT)
        return len(self.unacked.get(feed, ())) < cap

    def has_work(self):
        with self.lock:
            return bool(self.pong or self.text or any(self.room(f) for f in self.latest) or
                        any(st["q"] and self.room(f) for f, st in self.h264.items()))

    def take(self):
        out = []
        with self.lock:
            if self.pong:
                out.append(ws_frame(0xA, b""))
                self.pong = False
            for key in ("P", "h", "status"):
                text = self.text.pop(key, None)
                if text is not None:
                    out.append(ws_frame(0x1, text.encode("utf-8")))
            for feed in list(self.latest):
                if self.room(feed):
                    msg, ms = self.latest.pop(feed)
                    self.unacked.setdefault(feed, collections.deque()).append((len(msg), ms))
                    out.append(ws_frame(0x2, msg))
            for feed, st in self.h264.items():
                while st["q"] and self.room(feed):
                    msg = st["q"].popleft()
                    self.unacked.setdefault(feed, collections.deque()).append(
                        (len(msg), struct.unpack_from("<d", msg, 2)[0]))
                    out.append(ws_frame(0x2, msg))
        return out

    def ack(self, feed):
        with self.lock:
            q = self.unacked.get(feed)
            item = q.popleft() if q else None
        self.wake.set()
        return item

    def oldest_unacked_ms(self):
        with self.lock:
            stamps = [q[0][1] for f, q in self.unacked.items() if q and f != LIDAR and q[0][1]]
        return min(stamps) if stamps else None

    def reset(self, feed):
        # A feed switched off and on again: frames in flight then are never drawn.
        with self.lock:
            self.unacked.pop(feed, None)
            self.latest.pop(feed, None)

    def has_h264(self, f):
        with self.lock:
            return f in self.h264

    def want_h264(self, f, on, init_msg):
        # (Re)starts H.264 feed f: the encoder's init segment first, then frames from the next
        # keyframe. init_msg is None until the encoder has made one.
        with self.lock:
            self.unacked.pop(f, None)
            if on:
                st = {"q": collections.deque(), "key": True}
                if init_msg:
                    st["q"].append(init_msg)
                self.h264[f] = st
            else:
                self.h264.pop(f, None)
        self.wake.set()

    def offer_h264(self, f, kind, msg):
        # kind 0 is an init segment (a new encoder: the page starts over), 1 a keyframe, 2 not.
        # Returns (offered, dropped).
        with self.lock:
            st = self.h264.get(f)
            if st is None:
                return False, 0
            dropped = 0
            if kind == 0:
                st["q"].clear()
                st["q"].append(msg)
                st["key"] = True
                self.unacked.pop(f, None)
            else:
                if st["key"] and kind != 1:
                    return False, 1
                st["key"] = False
                st["q"].append(msg)
                if len(st["q"]) > H264_BACKLOG:
                    n = len(st["q"])
                    st["q"] = collections.deque(m for m in st["q"] if m[1] == 0)
                    st["key"] = True
                    dropped = n - len(st["q"])
        self.wake.set()
        return True, dropped


# ---- the two side by side ----------------------------------------------------------------------
def same(a, b):
    if isinstance(a, float) and isinstance(b, float):
        return a == b or (math.isnan(a) and math.isnan(b))
    if isinstance(a, (tuple, list)) and isinstance(b, (tuple, list)):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b


def books(o, k):
    a = (list(o.text.items()),
         [(f, bytes(msg), ms) for f, (msg, ms) in o.latest.items()],
         [(f, list(q)) for f, q in o.unacked.items()],
         [(f, [bytes(x) for x in st["q"]], st["key"]) for f, st in o.h264.items()],
         o.pong)
    b = (list(k.text.items()),
         [(f, bytes(s.msg), s.ms) for f, s in k.latest.items()],
         [(f, [(x.size, x.ms) for x in fl.items]) for f, fl in k.unacked.items()],
         [(f, [bytes(x.msg) for x in st.q], st.key) for f, st in k.h264.items()],
         k.pong)
    return a, b


rng = random.Random(20261005)
FEEDS = [0, 1, 2, LIDAR, 128, 129, 130]
KEYS = ["P", "h", "status", "P", "h", "status", "x"]
TEXTS = ["p 12", "h", "status ok", "vcu ARMED", "", "café → \U0001f697"]


def ms():
    return rng.choice([rng.uniform(0, 1e6), float(rng.randrange(1000)), 0.0, -0.0, math.nan, 1e300])


def fragment(kind):
    tail = bytes(rng.randrange(256) for _ in range(rng.randrange(6)))
    return bytes([rng.randrange(256), kind]) + struct.pack("<d", ms()) + tail


calls = collections.Counter()
runs = 0
for run in range(300):
    o, k = Conn("ctrl", None), m.Conn()
    for step in range(rng.randrange(20, 160)):
        op = rng.choice(["text", "pong", "frame", "frame", "frame", "take", "take", "ack", "ack", "room",
                         "work", "oldest", "reset", "want", "has", "offer", "offer", "offer"])
        feed = rng.choice(FEEDS)
        if op == "text":
            key, t = rng.choice(KEYS[:6] if rng.random() < 0.97 else KEYS), rng.choice(TEXTS)
            got, want = k.putText(key, t), o.put_text(key, t)
        elif op == "pong":
            got, want = k.putPong(), o.put_pong()
        elif op == "frame":
            msg, at = bytes(rng.randrange(256) for _ in range(rng.randrange(12))), ms()
            got, want = k.putFrame(feed, msg, at), o.put_frame(feed, msg, at)
        elif op == "take":
            got, want = [(x.opcode, bytes(x.payload)) for x in k.take()], o.take()
        elif op == "ack":
            s = k.ack(feed)
            got, want = (None if s is None else (s.size, s.ms)), o.ack(feed)
        elif op == "room":
            got, want = k.room(feed), o.room(feed)
        elif op == "work":
            got, want = k.hasWork(), o.has_work()
        elif op == "oldest":
            got, want = k.oldestUnackedMs(), o.oldest_unacked_ms()
        elif op == "reset":
            got, want = k.reset(feed), o.reset(feed)
        elif op == "want":
            on, init = rng.random() < 0.8, rng.choice([None, b"", fragment(0)])
            got, want = k.wantH264(feed, on, init or b""), o.want_h264(feed, on, init)
        elif op == "has":
            got, want = k.hasH264(feed), o.has_h264(feed)
        else:
            kind = rng.choice([0, 1, 2, 2, 2, 2, 2, 2])
            msg = fragment(rng.choice([kind, kind, kind, 0, 1, 2]))
            n = rng.choice([1, 1, 1, 5, 40])
            for _ in range(n - 1):
                k.offerH264(feed, kind, msg)
                o.offer_h264(feed, kind, msg)
            r = k.offerH264(feed, kind, msg)
            got, want = (r.offered, r.dropped), o.offer_h264(feed, kind, msg)
        assert same(got, want), (run, step, op, feed, got, want)
        a, b = books(o, k)
        assert same(a, b), (run, step, op, feed, a, b)
        calls[op] += 1
    runs += 1
print("Conn against bibodash.Conn: %d runs, %d calls (%s), every result and every dict's keys, in order, "
      "and entries the same" % (runs, sum(calls.values()), " ".join("%s %d" % kv for kv in sorted(calls.items()))))
