#!/usr/bin/env python3
# The utcstamp golden (K5): main prints cpp-golden/utcstamp's lines; then stamp(ns) over Int64 is the text datetime
# gives the same instant, the milliseconds floored, as linkstate's utcNow wrote it.
#
#   python driver.py <the directory kira --target py --out wrote>
import datetime
import importlib.util
import os
import random
import sys

spec = importlib.util.spec_from_file_location("utcstamp_kira", os.path.join(sys.argv[1], "src", "app", "utcstamp_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("utcstamp ->", m._k_run())
epoch = datetime.datetime(1970, 1, 1)


def reference(ns):
    t = epoch + datetime.timedelta(microseconds=ns // 1000)
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (t.microsecond // 1000)


lo, hi = -(1 << 63), (1 << 63) - 1
day = 86400 * 10**9
values = [lo, lo + 1, hi, hi - 1]
for k in range(-106752, 106752, 97):
    values += [k * day - 1, k * day, k * day + 999999, k * day + 1000000]
draws = random.Random(5)
values += [draws.randrange(lo, hi + 1) for _ in range(20000)]
values = [v for v in values if lo <= v <= hi]
wrong = [(v, m.stamp(v), reference(v)) for v in values if m.stamp(v) != reference(v)]
print("stamps equal datetime's:", len(values) - len(wrong), "of", len(values), wrong[:3])
print("a stamp is a", type(m.stamp(0)).__name__)
