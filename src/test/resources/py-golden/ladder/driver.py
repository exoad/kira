#!/usr/bin/env python3
# The ladder golden: the Ladder that ladder.kira compiles to, step by step against the
# hand-written one it replaces (bibo's firmware/pilot/tools/dash/streamenc.py at master 5508e4a,
# copied below unchanged as the oracle), over seeded links, seeded noise and edge cases. Each
# step must give the same level and verdict. Prints a summary of what the oracle did.
#
#   python driver.py <the directory kira --target py --out wrote>
import collections
import importlib.util
import math
import os
import random
import sys

LADDER = [
    {"w": 320, "h": 240, "fps": 5.0, "q": 6},
    {"w": 320, "h": 240, "fps": 8.0, "q": 6},
    {"w": 480, "h": 360, "fps": 10.0, "q": 8},
    {"w": 640, "h": 480, "fps": 10.0, "q": 9},
    {"w": 640, "h": 480, "fps": 12.0, "q": 7},
    {"w": 640, "h": 480, "fps": 15.0, "q": 7},
]
TOP = len(LADDER) - 1
DEFAULT_LEVEL = 4


# ---- the oracle: streamenc.Ladder as written by hand -----------------------------------------
class Ladder:
    DOWN_BAD = 2              # bad seconds of the last DOWN_OF
    DOWN_OF = 3
    FULL_S = 4                # seconds summed for the full-link rule
    PROBE_FULL_S = 2          # the same, in a level just stepped up into
    FULL_RATIO = 0.9
    UP_S = 5                  # good seconds before a step up
    UP_RATIO = 0.95
    PROBE_S = 15.0            # a fall this soon after stepping up into a level is a failed try
    BACKOFF_MAX_S = 60.0
    FORGET_S = 120.0

    def __init__(self, level=DEFAULT_LEVEL):
        self._level = max(0, min(TOP, int(level)))
        self._hist = collections.deque(maxlen=int(self.BACKOFF_MAX_S) + 1)  # (delivered, offered, verdict)
        self._good = 0
        self._entered_up = None               # when the current level was stepped up into
        self._fails = {}                      # level -> (failed tries, last failure s)
        self._warm = True                     # the page's first second: its encoder starting, not the link
        self.verdict = ""                     # the last second's: good, fair, bad, severe, full, up, idle, warm

    @property
    def level(self):
        return self._level

    def _wait_up(self, level, now_s):
        fails, last = self._fails.get(level, (0, None))
        if fails and now_s - last > self.FORGET_S:
            self._fails.pop(level, None)
            fails = 0
        return min(self.UP_S * (2 ** fails), self.BACKOFF_MAX_S)

    def _move(self, level, now_s):
        down = level < self._level
        if down and self._entered_up is not None and now_s - self._entered_up <= self.PROBE_S:
            fails, _ = self._fails.get(self._level, (0, None))
            self._fails[self._level] = (fails + 1, now_s)
        self._entered_up = now_s if not down else None
        self._level = level
        self._hist.clear()
        self._good = 0

    def _sum_ratio(self, n):
        last = list(self._hist)[-n:]
        offered = sum(o for _, o, _ in last)
        return sum(d for d, _, _ in last) / offered if offered > 0 else 1.0

    def update(self, delivered_kbs, offered_kbs, age_ms, dropped, now_s):
        if not offered_kbs or offered_kbs <= 0.0:
            self.verdict = "idle"             # nothing offered: no evidence either way
            self._good = 0
            return self._level
        if self._warm:
            self._warm = False
            self.verdict = "warm"
            return self._level
        delivered = max(0.0, delivered_kbs or 0.0)
        ratio = delivered / offered_kbs
        fps = LADDER[self._level]["fps"]
        dropped = dropped or 0
        if ratio < 0.4 or (age_ms is not None and age_ms > 1500):
            v = "severe"
        elif ratio < 0.75 or (age_ms is not None and age_ms > 500) or dropped >= max(2, 0.3 * fps):
            v = "bad"
        elif ratio >= 0.85 and (age_ms is None or age_ms < 300) and dropped <= max(1, 0.1 * fps):
            v = "good"
        else:
            v = "fair"
        self._hist.append((delivered, offered_kbs, v))
        self._good = self._good + 1 if v == "good" else 0
        nbad = sum(1 for _, _, x in list(self._hist)[-self.DOWN_OF:] if x in ("bad", "severe"))
        trying = self._entered_up is not None and now_s - self._entered_up <= self.PROBE_S
        full_s = self.PROBE_FULL_S if trying else self.FULL_S
        full = len(self._hist) >= full_s and self._sum_ratio(full_s) < self.FULL_RATIO
        if self._level > 0 and (v == "severe" or nbad >= self.DOWN_BAD or full):
            self.verdict = v if v != "fair" else "full"
            self._move(max(0, self._level - (2 if ratio < 0.5 else 1)), now_s)
        elif (self._level < TOP and self._good >= self._wait_up(self._level + 1, now_s) and
              self._sum_ratio(self._good) >= self.UP_RATIO):
            self.verdict = "up"
            self._move(self._level + 1, now_s)
        else:
            self.verdict = v
        return self._level


class Counted(Ladder):
    """The oracle, counting the waits it asks for and the failures it forgets, so the summary
    shows the backoff was exercised. It changes nothing the oracle does."""
    waits = collections.Counter()

    def _wait_up(self, level, now_s):
        before = self._fails.get(level, (0, None))[0]
        wait = super()._wait_up(level, now_s)
        Counted.waits["wait %g s" % wait] += 1
        if before and level not in self._fails:
            Counted.waits["forgotten"] += 1
        return wait


# ---- the sequences -----------------------------------------------------------------------------
NIGHT_KBS = [lv["fps"] * kb for lv, kb in zip(LADDER, (5.0, 5.0, 7.3, 12.1, 14.6, 14.6))]


def pick(rnd, items):
    return items[int(rnd.random() * len(items))]


def clock(rnd, t):
    """The next second's now_s: mostly a second on, sometimes the same, back, or far ahead."""
    r = rnd.random()
    if r < 0.03:
        return t
    if r < 0.05:
        return t - rnd.random() * 30.0
    if r < 0.07:
        return t + 100.0 + rnd.random() * 200.0
    return t + 1.0


class Link:
    """A link that carries cap KB/s, as streamenc_test.py's Link: an overloaded one delivers its
    capacity and drops the rest, and a share of a second's frames is acknowledged the next."""
    LAG = 0.15

    def __init__(self):
        self.carried = None

    def second(self, level, cap, rtt_ms=60.0):
        offered = NIGHT_KBS[level]
        cap = max(0.0, cap)
        carried = min(offered, cap * 0.97)
        prev = self.carried if self.carried is not None else carried
        self.carried = carried
        delivered = min(cap * 0.97, (1.0 - self.LAG) * carried + self.LAG * prev)
        fps = LADDER[level]["fps"]
        frame_kb = offered / fps
        dropped = round(fps * (1.0 - delivered / offered))
        if delivered <= 0.0:
            age = None
        elif offered > cap * 0.97:
            age = rtt_ms + 2 * frame_kb / cap * 1000.0
        else:
            age = rtt_ms + frame_kb / cap * 1000.0
        return delivered, offered, age, dropped


def link_run(rnd, steps, start_t, clocked):
    """A link whose capacity holds for a while, then moves: good, weak, between two levels, stalled."""
    link, t, left, cap = Link(), start_t, 0, 0.0
    out = []
    for _ in range(steps):
        if left <= 0:
            left = 5 + int(rnd.random() * 60)
            kind = rnd.random()
            if kind < 0.3:
                cap = 1e9
            elif kind < 0.4:
                cap = 0.0
            else:
                k = int(rnd.random() * TOP)
                cap = (NIGHT_KBS[k] + NIGHT_KBS[k + 1]) / 2.0 * (0.6 + rnd.random() * 0.8)
        left -= 1
        noisy = cap * (0.85 + rnd.random() * 0.3) if cap < 1e9 else cap
        out.append((noisy, t))
        t = clocked(rnd, t)
    return out


def drive(gen, start, link_caps, stats, label):
    """Both ladders over a link: each second's numbers come from the level both are at."""
    oracle, kira, link = Counted(start), gen.Ladder(int(start), TOP), Link()
    same(oracle, kira, label, -1)
    for step, (cap, t) in enumerate(link_caps):
        d, o, a, dr = link.second(oracle.level, cap)
        step_both(oracle, kira, (d, o, a, dr, t), stats, label, step)


def replay(gen, start, seq, stats, label):
    oracle, kira = Counted(start), gen.Ladder(int(start), TOP)
    same(oracle, kira, label, -1)
    for step, args in enumerate(seq):
        step_both(oracle, kira, args, stats, label, step)


def step_both(oracle, kira, args, stats, label, step):
    before = oracle.level
    want = oracle.update(*args)
    got = kira.update(*args, LADDER[kira.level]["fps"] if 0 <= kira.level <= TOP else 0.0)
    if got != want:
        sys.exit("MISMATCH %s step %d: %r gave level %r, the oracle %r" % (label, step, args, got, want))
    same(oracle, kira, label, step, args)
    stats["steps"] += 1
    stats["verdict " + oracle.verdict] += 1
    if oracle.level > before:
        stats["ups"] += 1
    elif oracle.level < before:
        stats["downs"] += 1


def same(oracle, kira, label, step, args=None):
    if kira.level != oracle.level or (step >= 0 and kira.verdict != oracle.verdict):
        sys.exit("MISMATCH %s step %d: %r gave level %r verdict %r, the oracle %r %r"
                 % (label, step, args, kira.level, kira.verdict, oracle.level, oracle.verdict))


NAN = float("nan")
INF = float("inf")
DELIVERED = [None, 0, 0.0, -0.0, -1.0, 1e-12, 1e300, INF, NAN]
OFFERED = [None, 0, 0.0, -0.0, -5.0, 1e-9, 1e300, INF, NAN]
AGES = [None, 0.0, 299.999, 300.0, 499.9, 500.0, 500.0001, 1500.0, 1500.1, 1e300, NAN, -5.0]
DROPPED = [None, 0, 1, 2, 3, 4, 10, -3, 1000000]


def noise(rnd, steps, start_t):
    out, t = [], start_t
    for _ in range(steps):
        off = pick(rnd, OFFERED) if rnd.random() < 0.25 else rnd.random() * 300.0
        if rnd.random() < 0.25:
            d = pick(rnd, DELIVERED)
        else:
            d = (off if isinstance(off, float) and math.isfinite(off) else 100.0) * rnd.random() * 1.3
        a = pick(rnd, AGES) if rnd.random() < 0.4 else rnd.random() * 3000.0
        dr = pick(rnd, DROPPED) if rnd.random() < 0.4 else int(rnd.random() * 20)
        out.append((d, off, a, dr, t))
        t = clock(rnd, t)
    return out


def edges():
    """Fixed sequences: first seconds, idle and unknown seconds, odd clocks."""
    good = (900.0, 150.0, 50.0, 0)
    out = {
        "warm then stalled": [(0.0, 150.0, None, 12, 0.0), (0.0, 150.0, None, 12, 1.0)] * 10,
        "only idle": [(0.0, 0.0, None, 0, float(t)) for t in range(40)],
        "only unknowns": [(None, None, None, None, float(t)) for t in range(40)],
        "offered 0 and good": [((0.0, 0, None, 0) if t % 3 else good) + (float(t),) for t in range(200)],
        "one timestamp": [good + (5.0,) for _ in range(300)],
        "time backwards": [good + (1000.0 - t,) for t in range(300)],
        "huge times": [good + (1e12 + t,) for t in range(300)],
        "negative times": [good + (-1e6 + t,) for t in range(300)],
        "fail and forget": [((900.0, 150.0, 50.0, 0) if (t // 20) % 2 else (30.0, 150.0, 900.0, 9)) + (t * 7.0,)
                            for t in range(400)],
    }
    return out


def main():
    out_dir = sys.argv[1]
    path = os.path.join(out_dir, "src", "firmware", "pilot", "tools", "dash", "ladder.kira.py")
    spec = importlib.util.spec_from_file_location("ladder_kira", path)
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)

    rnd = random.Random(20261002)
    stats = collections.Counter()
    sequences = 0
    starts = [-5, 0, 1, 2, 3, 4, 5, 99]
    for n in range(1500):
        start = pick(rnd, starts)
        clocked = clock if n % 2 else (lambda r, t: t + 1.0)
        drive(gen, start, link_run(rnd, 150, rnd.random() * 1000.0, clocked), stats, "link %d" % n)
        sequences += 1
    for n in range(1500):
        replay(gen, pick(rnd, starts), noise(rnd, 80, rnd.random() * 100.0), stats, "noise %d" % n)
        sequences += 1
    for name, seq in edges().items():
        for start in (0, DEFAULT_LEVEL, TOP):
            replay(gen, start, seq, stats, "%s from %d" % (name, start))
            sequences += 1

    print("ladder.kira against streamenc.py's Ladder: %d sequences, %d steps, every level and verdict the same"
          % (sequences, stats["steps"]))
    print("the oracle stepped up %d times and down %d times" % (stats["ups"], stats["downs"]))
    for key in sorted(k for k in stats if k.startswith("verdict ")):
        print("  %-14s %d" % (key[8:], stats[key]))
    print("waits asked for before a step up, and failures forgotten:")
    for key in sorted(Counted.waits, key=lambda k: (len(k), k)):
        print("  %-14s %d" % (key, Counted.waits[key]))


if __name__ == "__main__":
    main()
