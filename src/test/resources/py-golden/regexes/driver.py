#!/usr/bin/env python3
# The regexes golden (K6): main, then each Regex and Match method is re's own, on patterns Python compiled too (E1);
# compile is none where re raises (a repeat's OverflowError, deep nesting's RecursionError), and the rest panic there.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import re
import sys

out = sys.argv[1]
spec = importlib.util.spec_from_file_location("regexes_kira", os.path.join(out, "src", "app", "regexes_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
spec = importlib.util.spec_from_file_location("why_kira", os.path.join(out, "src", "app", "why_kira.py"))
why = importlib.util.module_from_spec(spec)
spec.loader.exec_module(why)
print("regexes ->", m._k_run())


def shown(hit):
    return None if hit is None else (hit.span(), hit.groups())


def tally(name, pairs):
    print(name, "is re's:", sum(a == b for a, b in pairs), "of", len(pairs))


patterns = [(r"a+", 0), (r"(a)(b)?", 0), (r"\bcat\b", re.I), (r"^\w+$", re.M), (r"(?P<n>\d+)-(?P<m>\d+)?", 0), (r"x*", 0),
            (r"é|ß", re.I), (r"(?<=a)b", 0), (r"\s+", 0), (r".$", 0)]
texts = ["", "a", "aab ab", "Cat cAT concat", "one\ntwo\n", "12-34 5-", "xxyxx", "ÉSS ß", "ab b", "  a\tb \n", "ab\n"]
compiled = [re.compile(p, f) for p, f in patterns]
tally("compile", [(m.compiled(p, f), re.compile(p, f)) for p, f in patterns])
tally("search", [(shown(m.searched(r, t)), shown(r.search(t))) for r in compiled for t in texts])
tally("matchStart", [(shown(m.atStart(r, t)), shown(r.match(t))) for r in compiled for t in texts])
tally("matchAt", [(shown(m.atPos(r, t, k)), shown(r.match(t, k))) for r in compiled for t in texts for k in range(len(t) + 2)])
tally("fullMatch", [(shown(m.whole(r, t)), shown(r.fullmatch(t))) for r in compiled for t in texts])
tally("findIter", [([shown(x) for x in m.each(r, t)], [shown(x) for x in r.finditer(t)]) for r in compiled for t in texts])
tally("findAll", [(m.allOf(r, t), r.findall(t)) for r in compiled if r.groups <= 1 for t in texts])
templates = ["-", r"<\g<0>>", "", r"\\", "\\n"]
tally("sub", [(m.replaced(r, s, t), r.sub(s, t)) for r in compiled if r.groups == 0 for s in templates for t in texts])
tally("split", [(m.pieces(r, t), r.split(t)) for r in compiled if r.groups == 0 for t in texts])
named = re.compile(r"(?P<n>\d+)-(?P<m>\d+)?")
hit = m.searched(named, "x 12- 3")
print("groups:", m.groupOf(hit, 0), m.groupOf(hit, 1), m.groupOf(hit, 2), m.namedOf(hit, "n"), m.namedOf(hit, "m"), m.span(hit))
bad = ["(", "a)", "[a", "*a", "a{2,1}", "(?P<x>a)(?P<x>b)", r"\p", "(?<!a+)b", "a\\", "a{4294967296}", "a{1,4294967296}", "(" * 1000 + ")" * 1000]
seen = 0
for p in bad:
    try:
        re.compile(p)
    except (re.error, OverflowError, RecursionError) as e:
        seen += m.compiled(p, 0) is None and why.whyNot() == str(e)
print("regexError is str(e) of each bad pattern, read in app:why:", seen, "of", len(bad))
print("a LOCALE flag on a str pattern:", m.compiled("a", re.LOCALE), why.whyNot())
for what, call in [("findAll of two groups", lambda: m.allOf(re.compile(r"(a)(b)"), "ab")),
                   ("sub with a bad escape", lambda: m.replaced(re.compile("a"), r"\q", "a")),
                   ("sub naming a group the pattern lacks", lambda: m.replaced(re.compile("a"), r"\g<x>", "a")),
                   ("split by a group that took no part", lambda: m.pieces(re.compile(r"(a)|b"), "xbx")),
                   ("a group the pattern lacks", lambda: m.groupOf(m.searched(re.compile("a"), "a"), 3)),
                   ("a name the pattern lacks", lambda: m.namedOf(m.searched(re.compile("a"), "a"), "q"))]:
    try:
        call()
        print(what, "returned")
    except RuntimeError as e:
        print(what + ":", e)
print("of the types:", type(m.compiled("a", 0)) is re.Pattern, type(m.searched(re.compile("a"), "a")) is re.Match, type(m.each(re.compile("a"), "aa")).__name__)
