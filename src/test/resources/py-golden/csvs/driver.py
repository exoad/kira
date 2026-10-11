#!/usr/bin/env python3
# The csvs golden (K6): main, then a CsvReader reads csv.reader's rows and stops where it raises, isMalformed for a
# csv.Error alone, csvError its str(e), cleared by each call that works (D86); csvLine is csv.writer's line.
#
#   python driver.py <the directory kira --target py --out wrote>
import csv
import importlib.util
import io
import os
import shutil
import sys
import tempfile

spec = importlib.util.spec_from_file_location("csvs_kira", os.path.join(sys.argv[1], "src", "app", "csvs_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("csvs ->", m._k_run())
work = tempfile.mkdtemp(prefix="kira-csvs-driver-", dir=sys.argv[1])
path = os.path.join(work, "x.csv")
texts = [b"", b"a,b\r\nc,d\r\n", b"a,\"b\nc\",d\n", b"\"unterminated", b"a,b\n\n\nc", b"\"a\"b,c", b" a , b \n", b"a,\"b\"\"c\"\r\n",
         b"\xef\xbb\xbfa,b\n", b"x\ry\rz", b"a\x00b\n", b"ok\n\"" + b"x" * 131073 + b"\"\nafter\n", b"a,\xff\nb\n", b"\xc3\xa9,\xe2\x82\xac\n",
         b"1,2\n3,\"4\n", b"a,\"b\r\nc\",\"d\re\"\r\n"]


def reference():
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        try:
            for row in csv.reader(f):
                rows.append(row)
        except csv.Error as e:
            return rows, True, str(e)
        except ValueError as e:
            return rows, False, str(e)
    return rows, False, ""


def kira():
    r = m.opened(path)
    rows = []
    row = m.nextRow(r)
    while row is not None:
        rows.append(row)
        row = m.nextRow(r)
    return rows, m.malformed(r), m.why()


same = 0
for t in texts:
    with open(path, "wb") as f:
        f.write(t)
    same += kira() == reference()
print("a CsvReader reads csv.reader's rows and stops at its error:", same, "of", len(texts))
other = os.path.join(work, "y.csv")
with open(other, "wb") as f:
    f.write(b"a\nb\n")
live = m.opened(other)
m.opened(os.path.join(work, "nope"))
first = m.nextRow(live), m.why()
m.opened(os.path.join(work, "nope"))
again = m.opened(other) is not None, m.why()
live.close()
with open(path, "wb") as f:
    f.write(b"ok\n\"" + b"x" * 131073 + b"\"\n")
r = m.opened(path)
stopped = m.nextRow(r), m.nextRow(r), m.malformed(r), m.why() != ""
print("a next and an openCsv that work clear a failed open's reason:", first, again)
print("a csv.Error stops a CsvReader, and the next call clears it:", stopped, (m.nextRow(r), m.malformed(r), m.why()))
with open(path, "wb") as f:
    f.write(b"ok\n\"" + b"x" * 131073 + b"\"\n")
try:
    m.strictRows(path)
    print("strictRows of a field past the limit returned")
except RuntimeError as e:
    print("strictRows of a field past the limit:", str(e)[:80])
cells = [[], [""], ["a"], ["a", "b,c"], ["q\"q"], ["l\nm", "c\rd"], [" a "], ["é", "€"], ["a\x00b"], ["", ""], ["x" * 200000]]


def written(c):
    out = io.StringIO()
    try:
        csv.writer(out).writerow(c)
    except csv.Error:
        return "panics"
    return out.getvalue()


def line(c):
    try:
        return m.line(c)
    except RuntimeError:
        return "panics"


print("csvLine is csv.writer's line, a panic where it raises:", sum(line(c) == written(c) for c in cells), "of", len(cells))
print("of the types:", type(m.line(["a"])).__name__, type(m.opened(path)).__name__, m.opened(os.path.join(work, "nope")), type(m.why()).__name__)
shutil.rmtree(work)
