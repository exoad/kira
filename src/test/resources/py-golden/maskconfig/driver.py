#!/usr/bin/env python3
# main (the C++ run), then the Kira against lidarmask.py's config at bd6d8d9, its cleaners copied
# unchanged below, over seeded lidar-mask.json texts.   python driver.py <kira --target py --out dir>
import importlib.util
import json
import math
import os
import random
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dash", "maskconfig.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("maskconfig_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---- the oracle: lidarmask.py as written by hand ------------------------------------------------
BOX_LIMIT_M = 1.5
# TT-02 body, 0.19 x 0.40 m, centred on the sensor, as the page draws the car.
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


def _clean_wedges(wedges):
    out = []
    for w in wedges if isinstance(wedges, list) else []:
        try:
            a0, a1 = float(w["a0"]) % 360.0, float(w["a1"]) % 360.0
            r = int(min(max(float(w["r_max_mm"]), 0), 65535))
        except (KeyError, TypeError, ValueError):
            continue
        src = "capture" if w.get("src") == "capture" else "manual"
        out.append({"a0": round(a0, 2), "a1": round(a1, 2), "r_max_mm": r, "blind": bool(w.get("blind", False)),
                    "src": src})
    return out


def mask_saves(text):
    # Mask(path): its defaults, reload's json.load and set_config, _build's NaN check, then save's dump.
    on, box, wedges = True, dict(DEFAULT_BOX), []
    try:
        cfg = json.loads(text)
    except ValueError:
        cfg = None
    if isinstance(cfg, dict):
        on = bool(cfg.get("on", True))
        box = _clean_box(cfg.get("box"))
        wedges = _clean_wedges(cfg.get("wedges"))
        if any(w["a0"] != w["a0"] or w["a1"] != w["a1"] for w in wedges):
            return "ValueError: cannot convert float NaN to integer"
    return json.dumps({"on": on, "box": dict(box), "wedges": [dict(w) for w in wedges]}, indent=1)


# ---- seeded configs ---------------------------------------------------------------------------
rng = random.Random(20261006)
TEXTS = ["0.3", " 1.25 ", "\t-2", ".5", "5.", "+0.75", "1e-3", "nan", "-inf", "Infinity", "INF", "", "abc", "0x10",
         "1,5", "capture", "manual"]


def number():
    pick = rng.random()
    if pick < 0.3:
        return rng.uniform(-1000.0, 1000.0)
    if pick < 0.45:
        return rng.randint(-1000, 1000)
    if pick < 0.6:
        return round(rng.uniform(-2.0, 2.0), rng.randint(0, 5))
    if pick < 0.7:
        return rng.choice([math.nan, math.inf, -math.inf, -0.0, 0.0, 359.995, 360.0, -1e-20, 0.0005, 0.3125, 1.4995])
    if pick < 0.8:
        return rng.choice(TEXTS)
    if pick < 0.88:
        return rng.choice([True, False, None])
    return rng.choice([[], [1], {}, {"a": 1}, 65535.5, 1e9, -1e9])


def anything():
    return rng.choice([number(), number(), "", "x", None, [], {}, True, False, 0, 1, 0.0, math.nan, [0], {"k": 0}])


def wedge():
    w = {}
    for k in ("a0", "a1", "r_max_mm", "blind", "src", "extra"):
        if rng.random() < 0.85 or k in ("a0", "a1") and rng.random() < 0.9:
            if k == "blind":
                w[k] = anything()
            elif k == "src":
                w[k] = rng.choice(["capture", "manual", "Capture", None, 1, "capture "])
            elif k == "r_max_mm":
                w[k] = rng.choice([number(), rng.uniform(0, 70000), rng.randint(-5, 70000)])
            else:
                w[k] = number()
    return w if rng.random() < 0.9 else rng.choice([[1, 2, 3], "w", None, 4])


def config():
    cfg = {}
    if rng.random() < 0.7:
        cfg["on"] = anything()
    if rng.random() < 0.85:
        cfg["box"] = {k: number() for k in SIDES if rng.random() < 0.8} if rng.random() < 0.85 else anything()
    if rng.random() < 0.85:
        cfg["wedges"] = [wedge() for _ in range(rng.choice([0, 1, 2, 3, 6]))] if rng.random() < 0.9 else anything()
    if rng.random() < 0.1:
        cfg["src"] = "manual"
    return cfg


def damaged(text):
    i = rng.randrange(len(text) + 1)
    return text[:i] + rng.choice(["", ",", "}", "]", "\"", "x"]) + text[i + 1:]


same = refusals = broken = 0
for k in range(4000):
    cfg = config()
    text = json.dumps(cfg, indent=rng.choice([None, 1, 2]))
    if rng.random() < 0.08:
        text = damaged(text)
    elif rng.random() < 0.03:
        text = json.dumps(rng.choice([[cfg], 1, "x", None, True]))
    want = mask_saves(text)
    got = m.reload(text)
    assert got == want, (text, got, want)
    same += 1
    refusals += want.startswith("ValueError")
    try:
        json.loads(text)
    except ValueError:
        broken += 1
print("reload against lidarmask.py's Mask: %d lidar-mask.json texts, %d unreadable, %d refused for a NaN edge, "
      "every saved text the same" % (same, broken, refusals))
