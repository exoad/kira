#!/usr/bin/env python3
# main (the C++ run), then the Kira against powerpage.py's stats() and /data body at bd6d8d9, copied
# unchanged below, over seeded windows.   python driver.py <kira --target py --out dir>
import importlib.util
import json
import math
import os
import random
import runpy
import sys
import types

path = os.path.join(sys.argv[1], "src", "pp", "powerdata.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("powerdata_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: powerpage.py as written by hand, its Tally answering the seeded numbers -------
CURRENT = None
_powerpage = types.SimpleNamespace(Tally=lambda: CURRENT)


def stats(buckets):
    t = _powerpage.Tally()
    for b in buckets:
        t.add(b)
    # j_wh is sum()'s: the int 0 over no buckets.
    return {"samples": t.samples, "jetson_up": t.jetsonUpShare(), "j_avg_w": t.inAvgW(),
            "j_peak_w": t.inMaxW(), "j_wh": t.inWh() if buckets else 0, "j_min_v": t.inMinV(),
            "j_oc_new": t.newOcEvents(), "pi_cpu_avg": t.cpuAvgPct(), "pi_temp_max": t.piTempC(),
            "j_temp_max": t.jTempC()}


def clean(v):
    return None if isinstance(v, float) and not math.isfinite(v) else v


def oracle_body(now, seconds, latest, st, series):
    body = {"now": now, "seconds": seconds, "latest": latest, "stats": st,
            "series": [[clean(v) for v in p] for p in series]}
    return json.dumps(body)


# ---- seeded windows -----------------------------------------------------------------------------
rng = random.Random(20261006)


def maybe_float(odd=0.15):
    pick = rng.random()
    if pick < 0.2:
        return None
    if pick < 0.2 + odd:
        return rng.choice([math.nan, math.inf, -math.inf, -0.0, 0.0, 1e16, 1e-7])
    return rng.choice([rng.uniform(0, 60), round(rng.uniform(0, 100), rng.randint(0, 4)), rng.uniform(-5, 5) * 10 ** rng.randint(-9, 9)])


class Tally:
    def __init__(self, n_buckets):
        self.samples = 0 if n_buckets == 0 else rng.randint(1, 1800)
        self.v = [maybe_float(0.05) for _ in range(9)]
        self.wh = rng.uniform(0, 20)

    def add(self, b):
        pass

    def jetsonUpShare(self): return self.v[0]
    def inAvgW(self): return self.v[1]
    def inMaxW(self): return self.v[2]
    def inWh(self): return self.wh
    def inMinV(self): return self.v[3]
    def newOcEvents(self): return self.v[4]
    def cpuAvgPct(self): return self.v[5]
    def piTempC(self): return self.v[6]
    def jTempC(self): return self.v[7]


COLUMNS = ["t", "pi_cpu_pct", "pi_load1", "pi_temp_c", "pi_mhz", "j_in_mv", "j_in_ma", "j_cpu_gpu_cv_mw",
           "j_soc_mw", "j_temp_c", "j_oc_events", "j_fan_pwm", "j_mode"]


def latest_row():
    if rng.random() < 0.15:
        return None
    row = {"t": rng.uniform(1.7e9, 1.8e9)}
    live = rng.random() < 0.5
    for c in COLUMNS[1:]:
        if rng.random() < 0.15:
            row[c] = None
        elif c == "j_mode":
            row[c] = rng.choice(["15W", "MAXN", "7W", "é"])
        elif live and c in ("j_in_mv", "j_in_ma", "j_oc_events", "j_fan_pwm"):
            row[c] = rng.randint(0, 20000)
        else:
            row[c] = maybe_float()
    return row


bodies = 0
points = 0
for k in range(3000):
    now = rng.uniform(1.7e9, 1.8e9)
    seconds = rng.choice([60, 3600, 86400, 7 * 86400, rng.randint(60, 7 * 86400)])
    raw = rng.random() < 0.5
    n = rng.choice([0, 0, 1, 2, 5, 30])
    buckets = [object() for _ in range(rng.choice([0, 0, 1, 3]))]
    CURRENT = Tally(len(buckets))
    st = stats(buckets)
    series, kira_series = [], []
    for _ in range(n):
        if raw:
            p = [rng.uniform(1.7e9, 1.8e9)] + [maybe_float() for _ in range(4)]
            series.append([p[0], p[1], p[1], p[2], p[3], p[4]])
            kira_series.append(m.rawPoint(*p))
        else:
            p = [int(rng.uniform(1.7e9, 1.8e9) // 60) * 60] + [maybe_float() for _ in range(5)]
            series.append(p)
            kira_series.append(m.bucketPoint(*p))
        points += 1
    latest = latest_row()
    t = CURRENT
    kira_st = m.stats(t.samples, len(buckets), t.v[0], t.v[1], t.v[2], t.wh, t.v[3], t.v[4], t.v[5], t.v[6], t.v[7])
    got = m.body(now, seconds, latest, kira_st, kira_series)
    want = oracle_body(now, seconds, latest, st, series)
    assert got == want, (got, want)
    bodies += 1
print("body against powerpage.py's json.dumps of its /data body: %d bodies, %d series points, the same bytes" % (bodies, points))

# A count the feed hands over as an int, through the Tally's Float64, is a float as on the C++ target.
CURRENT = Tally(1)
CURRENT.v[4] = 3
print("an int j_oc_new: python %s, kira %s" % (json.dumps(stats([object()])["j_oc_new"]),
                                               json.dumps(m.stats(1, 1, None, None, None, 0.0, None, 3, None, None, None)["j_oc_new"])))
