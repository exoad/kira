#!/usr/bin/env python3
# Py runtime harness: calls each shared _k_ helper of two runtimes over the rt_cases.py corpus and compares the results.
# Other modes: closure, parse, coverage, bench, mutants; each prints key=value lines and exits 1 on a failure.
import argparse
import ast
import collections
import faulthandler
import inspect
import io
import json
import os
import re
import shutil
import struct
import sys
import tempfile
import threading
import time
import tokenize
import types

sys.dont_write_bytecode = True
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import rt_fakes  # noqa: E402

BENCH_COMMENT = "Timings a changed runtime is held to within 1.2x of; recorded by rt_equiv.py bench --record."
CALL_LIMIT = 1000
CANON_LIMIT = 100000


class Runtime:
    """One runtime in its own namespace, with its own kira:errors module so two never share a throw or a reason."""

    def __init__(self, path=None, src=None, tag="a"):
        self.tag = tag
        self.path = path
        self.src = src if src is not None else open(path, encoding="utf-8").read()
        self.code = compile(self.src, path or "<mutant %s>" % tag, "exec")
        self.ns, self.errmod = self.load()

    def load(self):
        saved = sys.modules.pop("kira:errors", None)
        ns = {"__name__": "rt", "__file__": os.path.join(tmp_root(), "mod", "rt.py")}
        try:
            exec(self.code, ns)
            errmod = sys.modules.pop("kira:errors")
        finally:
            if saved is not None:
                sys.modules["kira:errors"] = saved
        return ns, errmod


_TMP = []


def tmp_root():
    if not _TMP:
        _TMP.append(os.path.join(tempfile.gettempdir(), "rt_equiv_%d" % os.getpid()))
    return _TMP[0]


def wipe(path):
    shutil.rmtree(path, ignore_errors=True)
    os.makedirs(os.path.join(path, "mod"), exist_ok=True)


class Ctx:
    def __init__(self, rt, tmp):
        self.rt = rt
        self.tmp = tmp


def canon(x, seen=None):
    """A value as plain tuples that == tells apart (NaN, signed zero, bool and int, the bytes class)."""
    t = type(x)
    if x is None:
        return ("N",)
    if t is bool:
        return ("b", x)
    if t is int:
        return ("i", x)
    if t is float:
        return ("f", "nan" if x != x else struct.pack("<d", x).hex())
    if t is str:
        return ("s", x.encode("utf-8", "surrogatepass"))
    if t is bytes or t is bytearray:
        return (t.__name__, bytes(x))
    if t is memoryview:
        try:
            return ("memoryview", x.tobytes(), x.readonly, x.format)
        except ValueError:
            return ("memoryview", "released")
    seen = set() if seen is None else seen
    if id(x) in seen:
        return ("cycle",)
    if t is list or t is tuple or t is collections.deque or t is set or t is frozenset or t is map:
        seen.add(id(x))
        try:
            items = [canon(e, seen) for e in x]
        finally:
            seen.discard(id(x))
        if t is set or t is frozenset:
            items.sort(key=repr)
        return (t.__name__, tuple(items), x.maxlen if t is collections.deque else None)
    if t is dict:
        seen.add(id(x))
        try:
            return ("dict", tuple((canon(k, seen), canon(v, seen)) for k, v in x.items()))
        finally:
            seen.discard(id(x))
    if isinstance(x, re.Pattern):
        return ("Pattern", x.pattern, x.flags)
    if isinstance(x, re.Match):
        return ("Match", x.re.pattern, x.string, x.span(), x.groups())
    if isinstance(x, type):
        return ("type", "rt" if x.__module__ == "rt" else x.__module__, x.__qualname__)
    if isinstance(x, BaseException):
        return canon_exc(x)
    if callable(x) and hasattr(x, "__name__") and not hasattr(t, "__slots__"):
        return ("func", x.__name__)
    slots = []
    for c in t.__mro__:
        s = c.__dict__.get("__slots__", ())
        slots += [s] if isinstance(s, str) else list(s)
    if slots or hasattr(x, "__dict__"):
        if t.__module__ in ("rt", "builtins") or t.__module__.startswith("kira:"):
            seen.add(id(x))
            try:
                names = slots or sorted(vars(x))
                return ("obj", t.__qualname__, tuple((n, canon(getattr(x, n, "<unset>"), seen)) for n in names))
            finally:
                seen.discard(id(x))
    return ("opaque", t.__module__, t.__qualname__)


def canon_exc(e):
    cause = type(e.__cause__).__qualname__ if e.__cause__ is not None else None
    extra = tuple(canon(getattr(e, n, None)) for n in ("errno", "code", "what", "pos", "msg", "returncode"))
    return ("exc", type(e).__module__ if type(e).__module__ != "rt" else "rt", type(e).__qualname__, str(e), extra, cause)


def normal_stderr(text):
    # A traceback names the runtime's lines; only the unindented lines (header and final message) are compared.
    return "\n".join(line for line in text.split("\n") if line and not line[0].isspace())


def tree(path):
    out = []
    for d, ds, fs in os.walk(path):
        ds.sort()
        for n in sorted(ds):
            out.append((os.path.relpath(os.path.join(d, n), path), "dir"))
        for n in sorted(fs):
            p = os.path.join(d, n)
            with open(p, "rb") as f:
                out.append((os.path.relpath(p, path), f.read()))
    return tuple(sorted(out, key=lambda e: e[0]))


def execute(rt, case, tmp):
    ns = rt.ns
    ctx = Ctx(ns, tmp)
    if case.fs:
        wipe(tmp)
    ns["_k_reasons"].__dict__.clear()
    swapped = []
    for name, over in (case.fakes or {}).items():
        over = over(ctx) if callable(over) else over
        swapped.append((name, ns[name]))
        ns[name] = rt_fakes.FakeMod(ns[name], over)
    out, err = io.StringIO(), io.StringIO()
    saved_errors = sys.modules.get("kira:errors")
    sys.modules["kira:errors"] = rt.errmod
    so, se = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = out, err
    sys.setrecursionlimit(CALL_LIMIT)
    try:
        try:
            r = case.run(ctx)
            sys.setrecursionlimit(CANON_LIMIT)
            res = ("ok", canon(r))
        except BaseException as e:
            sys.setrecursionlimit(CANON_LIMIT)
            res = canon_exc(e)
    finally:
        sys.setrecursionlimit(CALL_LIMIT)
        sys.stdout, sys.stderr = so, se
        if saved_errors is None:
            sys.modules.pop("kira:errors", None)
        else:
            sys.modules["kira:errors"] = saved_errors
        for name, real in swapped:
            ns[name] = real
    sys.setrecursionlimit(CANON_LIMIT)
    reasons = canon(dict(ns["_k_reasons"].__dict__))
    sys.setrecursionlimit(CALL_LIMIT)
    files = tree(tmp) if case.fs else None
    if case.fs:
        base = os.path.normcase(tmp)
        for k, m in list(sys.modules.items()):
            f = getattr(m, "__file__", None) or ""
            if os.path.normcase(str(k)).startswith(base) or os.path.normcase(f).startswith(base):
                del sys.modules[k]
    return (res, out.getvalue(), normal_stderr(err.getvalue()), reasons, files)


def has(rt, name):
    obj = rt.ns.get(name.split(".")[0], MISSING_NAME)
    for part in name.split(".")[1:]:
        obj = getattr(obj, part, MISSING_NAME) if obj is not MISSING_NAME else obj
    return obj is not MISSING_NAME


MISSING_NAME = object()


def runnable(rts, case):
    return all(has(rt, n) for rt in rts for n in case.needs)


def dead_skip(rts, case):
    """True when every need a runtime lacks is dead: a listed function, or a listed method of a listed class."""
    import rt_cases
    lacking = {n for rt in rts for n in case.needs if not has(rt, n)}
    return all(n in rt_cases.DEAD_FUNCTIONS or is_dead_method(n) for n in lacking)


def is_dead_method(name):
    import rt_cases
    cls, _, method = name.rpartition(".")
    return cls in rt_cases.DEAD_CLASSES and method in rt_cases.DEAD_METHODS


class Report:
    def __init__(self):
        self.total = 0
        self.skipped = 0
        self.skipped_dead = 0
        self.per_kind = collections.Counter()
        self.mismatches = []
        self.tally = collections.Counter()
        self.seconds = 0.0

    @property
    def ok(self):
        return not self.mismatches


BEFORE_CASE = None  # coverage mode re-arms its profiler here: a RecursionError near the limit switches one off


def differs(ra, rb):
    # Before 3.12 a nested tuple compares on the Python recursion limit, so the deep canon values need the high one.
    sys.setrecursionlimit(CANON_LIMIT)
    try:
        return ra != rb
    finally:
        sys.setrecursionlimit(CALL_LIMIT)


def compare(a, b, cases, first=False, tmp=None):
    """Run each case on both runtimes and collect the cases that differ."""
    tmp = tmp or tmp_root()
    rep = Report()
    start = time.perf_counter()
    wipe(tmp)
    for case in cases:
        if not runnable((a, b), case):
            rep.skipped += 1
            rep.skipped_dead += dead_skip((a, b), case)
            continue
        rep.total += 1
        if BEFORE_CASE is not None:
            BEFORE_CASE()
        rep.per_kind[case.kind] += 1
        ra, rb = execute(a, case, tmp), execute(b, case, tmp)
        rep.tally[ra[0][0] if ra[0][0] == "ok" else "exc " + ra[0][2]] += 1
        if differs(ra, rb):
            rep.mismatches.append((case, ra, rb))
            if first:
                break
    rep.seconds = time.perf_counter() - start
    shutil.rmtree(tmp, ignore_errors=True)
    return rep


def show(case, ra, rb):
    return "%s %s: A %s | B %s" % (case.kind, case.tag, repr(ra[0])[:200], repr(rb[0])[:200])


# ---- shape: arity, method sets, constants --------------------------------------------------------------------------

def sig(f):
    import inspect
    try:
        return str(inspect.signature(f))
    except (TypeError, ValueError):
        return "?"


def shape_of(name, obj):
    if isinstance(obj, type) and obj.__module__ == "rt":
        methods = {}
        for k, v in vars(obj).items():
            if isinstance(v, (staticmethod, classmethod)):
                methods[k] = (type(v).__name__, sig(v.__func__))
            elif callable(v) and hasattr(v, "__code__"):
                methods[k] = ("method", sig(v))
        slots = obj.__dict__.get("__slots__")
        return ("class", tuple(c.__name__ if c.__module__ != "rt" else "rt." + c.__name__ for c in obj.__bases__),
                tuple(sorted(methods.items())), tuple(slots) if slots is not None else None)
    if isinstance(obj, type):
        # A class from elsewhere (the shared Error) is its name and its bases: a panic is a RuntimeError, a throw is not.
        return ("type", obj.__module__, obj.__qualname__, tuple((b.__module__, b.__qualname__) for b in obj.__mro__[1:]))
    if hasattr(obj, "__code__") and getattr(obj, "__module__", None) in (None, "rt") and callable(obj):
        return ("function", sig(obj))
    if name == "_k_unset":
        # A sentinel is compared by identity, so its shape is that it is a bare object and nothing else.
        return ("sentinel", type(obj).__module__, type(obj).__qualname__)
    if isinstance(obj, threading.local):
        # The per-thread reasons: the type, and that it holds nothing before a case runs (a case clears it).
        return ("thread-local", type(obj).__module__, type(obj).__qualname__, tuple(sorted(obj.__dict__)))
    if isinstance(obj, json.JSONDecoder):
        # The shared decoder: its hooks and flags by what they are (a hook is a function, so by qualified name).
        return ("decoder", tuple(sorted((k, hook_name(v)) for k, v in vars(obj).items() if k != "scan_once")), type(obj.scan_once).__qualname__)
    return ("value", canon(obj))


def hook_name(v):
    if callable(v):
        return ("callable", getattr(v, "__module__", None), getattr(v, "__qualname__", type(v).__qualname__))
    return canon(v)


def shapes(rt):
    return {n: shape_of(n, o) for n, o in rt.ns.items() if n.startswith("_k_") and not isinstance(o, type(os))}


def without_dead(shape_map):
    import rt_cases
    out = {}
    for n, s in shape_map.items():
        if n in rt_cases.DEAD_FUNCTIONS:
            continue
        if n in rt_cases.DEAD_CLASSES and s[0] == "class":
            s = (s[0], s[1], tuple(m for m in s[2] if m[0] not in rt_cases.DEAD_METHODS), s[3])
        out[n] = s
    return out


def shape_diff(a, b):
    # A name the corpus lists as dead may be in one runtime and not the other (a deletion in progress).
    sa, sb = without_dead(shapes(a)), without_dead(shapes(b))
    out = ["only in A: " + n for n in sorted(set(sa) - set(sb))] + ["only in B: " + n for n in sorted(set(sb) - set(sa))]
    out += ["differs: %s A %r B %r" % (n, sa[n], sb[n]) for n in sorted(set(sa) & set(sb)) if sa[n] != sb[n]]
    return out, len(sa)


# ---- coverage: which executable lines of the runtime's functions the corpus ran ---------------------------------------

# Lines only one host reaches for real: (label, owner, line fragment). The report says whether the corpus ran each.
# A real Linux run is `python3.10 rt_equiv.py coverage --rt kira/py/runtime.py`.
PLATFORM_BRANCHES = [
    ("readyin-poll", "_k_readyin", "return bool(p.poll(s * 1000))"),
    ("poller-once-poll", "_k_Poller.once", "for h, re in p.poll("),
    ("poller-pollhup", "_k_Poller.once", "hup = re & _k_select.POLLHUP"),
    ("recvfrom-into", "_k_UdpSocket.recvFrom", "n, src = self.s.recvfrom_into(into)"),
    ("process-kill", "_k_Process.kill", "self.p.kill()"),
    ("syncdir-fsync", "_k_syncdir", "_k_os.fsync(fd)"),
    ("fileout-stale", "_k_FileOut.isStale", "not _k_os.path.exists(self.path)"),
]


def definitions(src):
    kinds = {"function": [], "class": [], "constant": []}
    for n in ast.parse(src).body:
        if isinstance(n, ast.FunctionDef):
            kinds["function"].append(n.name)
        elif isinstance(n, ast.ClassDef):
            kinds["class"].append(n.name)
        elif isinstance(n, ast.Assign):
            kinds["constant"] += [t.id for t in n.targets if isinstance(t, ast.Name)]
    return kinds


def function_codes(code):
    """Every function code object under code (class bodies and the module itself run at import and are not counted)."""
    out = []
    for c in code.co_consts:
        if isinstance(c, types.CodeType):
            if c.co_flags & inspect.CO_OPTIMIZED:
                out.append(c)
            out += function_codes(c)
    return out


def owners(src):
    """Line number -> 'func' or 'Class.method' for each line inside a top-level def or class."""
    out = {}
    for n in ast.parse(src).body:
        if isinstance(n, ast.FunctionDef):
            for ln in range(n.lineno, n.end_lineno + 1):
                out[ln] = n.name
        elif isinstance(n, ast.ClassDef):
            for m in n.body:
                if isinstance(m, ast.FunctionDef):
                    for ln in range(m.lineno, m.end_lineno + 1):
                        out[ln] = n.name + "." + m.name
    return out


def executable_lines(rt):
    """The lines in function bodies that hold code (a def line itself is run at import, not by a call)."""
    tree = ast.parse(rt.src)
    def_only = set()
    for n in ast.walk(tree):
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
            first = n.body[0].lineno
            for ln in range(min([d.lineno for d in n.decorator_list] + [n.lineno]), first):
                def_only.add(ln)
    lines = set()
    for c in function_codes(rt.code):
        for _s, _e, ln in c.co_lines():
            if ln is not None:
                lines.add(ln)
    return lines - def_only


def coverage(rt, cases):
    """Run the corpus on rt (against a twin, as equiv does) and collect the runtime-file lines that ran."""
    hit = set()
    filename = rt.code.co_filename
    twin = Runtime(src=rt.src, path=rt.path, tag="twin")
    mon = getattr(sys, "monitoring", None)
    if mon is not None:
        # 3.12+: a line event per line, each switched off after its first, so a line costs one callback once.
        tool = mon.COVERAGE_ID
        mon.use_tool_id(tool, "rt_equiv")

        def on_line(code, line):
            if code.co_filename == filename:
                hit.add(line)
            return mon.DISABLE

        mon.register_callback(tool, mon.events.LINE, on_line)
        mon.set_events(tool, mon.events.LINE)
        try:
            rep = compare(rt, twin, cases)
        finally:
            mon.set_events(tool, 0)
            mon.free_tool_id(tool)
        return hit, rep

    # Before 3.12: a trace function that follows only frames of the runtime's file, re-armed each case because a
    # RecursionError near the limit switches a trace off.
    global BEFORE_CASE

    def local(frame, event, arg):
        if event == "line":
            hit.add(frame.f_lineno)
        return local

    def tracer(frame, event, arg):
        if frame.f_code.co_filename == filename:
            hit.add(frame.f_lineno)
            return local
        return None

    threading.settrace(tracer)
    BEFORE_CASE = lambda: sys.settrace(tracer)
    try:
        rep = compare(rt, twin, cases)
    finally:
        BEFORE_CASE = None
        sys.settrace(None)
        threading.settrace(None)
    return hit, rep


# ---- closure check -------------------------------------------------------------------------------------------------

def empty_iter(it):
    """True for a literal that holds nothing, so a loop over it never binds its target."""
    return (
        (isinstance(it, (ast.Tuple, ast.List, ast.Set)) and not it.elts)
        or (isinstance(it, ast.Dict) and not it.keys)
        or (isinstance(it, ast.Constant) and isinstance(it.value, (str, bytes)) and not it.value)
    )


class Binder(ast.NodeVisitor):
    """The names a module's top level really binds: a def or class binds only its own name, a function a `global`
    only by assigning it. A bare annotation, a del, an augmented assign and an empty-literal loop target bind nothing."""

    def __init__(self):
        self.names = set()

    def visit_FunctionDef(self, n):
        self.names.add(n.name)
        self.scope(n)

    def scope(self, n):
        """A scope's own stores, minus augmented targets and bare annotations; a nested def or class is a scope of its
        own. What the scope declares `global` and also stores is bound at the module."""
        declared, stored, nested = set(), set(), []
        todo = list(ast.iter_child_nodes(n))
        while todo:
            x = todo.pop()
            if isinstance(x, ast.Global):
                declared.update(x.names)
            elif isinstance(x, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                stored.add(x.name)
                nested.append(x)
            elif isinstance(x, (ast.Lambda, ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
                pass
            else:
                if isinstance(x, ast.Name) and isinstance(x.ctx, ast.Store):
                    stored.add(x.id)
                elif isinstance(x, (ast.Import, ast.ImportFrom)):
                    stored.update((a.asname or a.name).split(".")[0] for a in x.names)
                if isinstance(x, ast.AugAssign):
                    todo.append(x.value)
                elif isinstance(x, (ast.For, ast.AsyncFor)) and empty_iter(x.iter):
                    todo.extend([x.iter] + x.body + x.orelse)
                elif isinstance(x, ast.AnnAssign) and x.value is None:
                    pass
                else:
                    todo.extend(ast.iter_child_nodes(x))
        self.names.update(declared & stored)
        for x in nested:
            self.scope(x)

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_ClassDef(self, n):
        self.names.add(n.name)
        self.scope(n)

    def visit_Lambda(self, n):
        pass

    def visit_ListComp(self, n):
        pass

    visit_SetComp = visit_DictComp = visit_GeneratorExp = visit_ListComp

    def visit_Name(self, n):
        if isinstance(n.ctx, ast.Store):
            self.names.add(n.id)

    def visit_AnnAssign(self, n):
        if n.value is not None:
            self.visit(n.target)
            self.visit(n.value)

    def visit_AugAssign(self, n):
        self.visit(n.value)

    def visit_For(self, n):
        it = n.iter
        if not empty_iter(it):
            self.visit(n.target)
        for x in [it] + n.body + n.orelse:
            self.visit(x)

    visit_AsyncFor = visit_For

    def visit_Import(self, n):
        self.names.update((a.asname or a.name).split(".")[0] for a in n.names)

    visit_ImportFrom = visit_Import

    def visit_ExceptHandler(self, n):
        for x in n.body:
            self.visit(x)


def bound_names(tree):
    b = Binder()
    for stmt in tree.body:
        b.visit(stmt)
    return b.names


def closure(src):
    """The _k_ tokens of src's code (comments and strings are not code) that nothing in it defines."""
    tree = ast.parse(src)
    bound = bound_names(tree)
    # An `except as` name is bound only while its handler runs: not after it, and not in a def or lambda inside it.
    lines = re.split(r"\r\n|\r|\n", src)

    def at(line, col):
        return (line, len(lines[line - 1].encode("utf-8")[:col].decode("utf-8", "ignore")))

    handlers = []
    for h in ast.walk(tree):
        if isinstance(h, ast.ExceptHandler) and h.name:
            later = [(at(d.lineno, d.col_offset), at(d.end_lineno, d.end_col_offset)) for d in ast.walk(h)
                     if isinstance(d, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.GeneratorExp))]
            handlers.append((h.name, h.lineno, h.end_lineno, later))
    undefined = {}
    prev = None
    for tok in tokenize.generate_tokens(io.StringIO(src).readline):
        in_handler = any(
            tok.string == h and lo <= tok.start[0] <= hi and not any(a <= tok.start < b for a, b in later)
            for h, lo, hi, later in handlers
        )
        if (
            tok.type == tokenize.NAME and tok.string.startswith("_k_") and tok.string not in bound and not in_handler
            and not (prev is not None and prev.string in (".", "def"))
        ):
            undefined.setdefault(tok.string, tok.start[0])
        if tok.type not in (tokenize.NL, tokenize.COMMENT):
            prev = tok
    twice = collections.Counter()
    for n in tree.body:
        if isinstance(n, (ast.FunctionDef, ast.ClassDef)):
            twice[n.name] += 1
        elif isinstance(n, ast.Assign):
            for t in n.targets:
                if isinstance(t, ast.Name):
                    twice[t.id] += 1
    return undefined, sorted(n for n, c in twice.items() if c > 1)


# ---- Python 3.10 parse ---------------------------------------------------------------------------------------------

LATER_NAMES = {
    "tomllib", "ExceptionGroup", "BaseExceptionGroup", "Self", "UTC", "StrEnum", "TaskGroup", "cbrt", "exp2", "batched",
    "is_integer", "process_cpu_count", "LiteralString", "Never", "assert_never", "reveal_type", "enum_member",
    "add_note",
}
# is_integer on int is 3.12's; float.is_integer is old, so a use on a float is a hit to read, not a failure.
SOFT_NAMES = {"is_integer"}


def parse310(src):
    """What Python 3.10 would refuse in src, as far as a newer interpreter can say: syntax by feature_version, then
    attribute and name uses that exist only later, then f-strings that reuse their quote (3.12 only)."""
    problems = []
    try:
        tree = ast.parse(src, feature_version=(3, 10))
    except SyntaxError as e:
        return ["syntax: %s (line %s)" % (e.msg, e.lineno)]
    for n in ast.walk(tree):
        name = n.attr if isinstance(n, ast.Attribute) else n.id if isinstance(n, ast.Name) else None
        if name in LATER_NAMES and name not in SOFT_NAMES:
            problems.append("later than 3.10: %s (line %d)" % (name, n.lineno))
        if isinstance(n, ast.JoinedStr):
            seg = ast.get_source_segment(src, n) or ""
            q = re.match(r"[a-zA-Z]*(\"\"\"|'''|\"|')", seg)
            for v in n.values:
                if isinstance(v, ast.FormattedValue):
                    inner = ast.get_source_segment(src, v.value) or ""
                    if (q and q.group(1)[0] in inner) or "\\" in inner:
                        problems.append("f-string expression reuses its quote or holds a backslash (line %d)" % n.lineno)
    return problems


# ---- microbench ----------------------------------------------------------------------------------------------------

def bench_items(rt):
    import random
    r = random.Random(2026)
    floats = [r.choice([0.0, -0.0, float("nan"), 1.5, -2.25, 1e300, 5e-324]) if r.random() < 0.05 else r.uniform(-1e6, 1e6)
              for _ in range(20000)]
    ints = [r.randint(-2 ** 62, 2 ** 62) for _ in range(20000)]
    small = [r.randint(-1000, 1000) for _ in range(100000)]
    near = [2 ** 62 - 1, -(2 ** 62)] + [r.randint(-5, 5) for _ in range(99998)]
    reals = [r.uniform(-1e6, 1e6) for _ in range(100000)]
    buf = bytearray(r.getrandbits(8) for _ in range(1 << 16))
    doc = rt.ns["_k_json"].dumps({"rows": [{"id": i, "name": "row %d" % i, "v": [i, i / 3, None, True], "t": "é😀"}
                                           for i in range(2500)]})
    text = [r.uniform(-1e9, 1e9) for _ in range(2000)] + [r.random() * 10.0 ** r.randint(-30, 30) for _ in range(1000)]
    ns = rt.ns

    def run_sort():
        ns["_k_sort"](list(floats), "Float64")
        ns["_k_sort"](list(ints), "Int64")

    def run_sum():
        ns["_k_sum"](small, "Int32")
        ns["_k_sum"](reals, "Float64")
        ns["_k_sum"](near, "Int64")

    def run_rdle():
        f = ns["_k_rdle"]
        for at in range(0, 40000, 2):
            f(buf, at, 4)

    def run_jparse():
        ns["_k_jparse"](doc)

    def run_ftext():
        f = ns["_k_ftext"]
        for v in text:
            f(v)

    def run_import():
        saved = sys.modules.pop("kira:errors", None)
        try:
            exec(rt.code, {"__name__": "rt_bench", "__file__": ns["__file__"]})
        finally:
            sys.modules.pop("kira:errors", None)
            if saved is not None:
                sys.modules["kira:errors"] = saved

    return [("sort", run_sort, 5), ("sum", run_sum, 5), ("rdle", run_rdle, 5), ("jparse", run_jparse, 5),
            ("ftext", run_ftext, 5), ("import", run_import, 9)]


def bench(a, b, reps=7):
    """Best of reps for each item on each runtime, interleaved so a slow moment hits both; microseconds per run."""
    rows = []
    ia, ib = dict((n, (f, k)) for n, f, k in bench_items(a)), dict((n, (f, k)) for n, f, k in bench_items(b))
    for name in ia:
        best = {"a": 1e99, "b": 1e99}
        for _ in range(reps):
            for tag, items, rt in (("a", ia, a), ("b", ib, b)):
                f, k = items[name]
                saved = sys.modules.get("kira:errors")
                sys.modules["kira:errors"] = rt.errmod
                try:
                    t = time.perf_counter()
                    for _ in range(max(1, k // 5)):
                        f()
                    d = (time.perf_counter() - t) / max(1, k // 5)
                finally:
                    if saved is None:
                        sys.modules.pop("kira:errors", None)
                    else:
                        sys.modules["kira:errors"] = saved
                best[tag] = min(best[tag], d)
        rows.append((name, best["a"] * 1e6, best["b"] * 1e6))
    return rows


# ---- modes ---------------------------------------------------------------------------------------------------------

def say(key, value):
    print("%s=%s" % (key, value))


def load_cases(repo):
    import rt_cases
    return rt_cases.build(repo)


def mode_equiv(args):
    a = Runtime(args.a, tag="a")
    b = Runtime(args.b, tag="b")
    cases = load_cases(args.repo)
    rep = compare(a, b, cases)
    say("python", sys.version.split()[0])
    say("cases", rep.total)
    say("skipped", rep.skipped)
    say("skipped_dead", rep.skipped_dead)
    for kind in sorted(rep.per_kind):
        say("kind." + kind, rep.per_kind[kind])
    for k, v in sorted(rep.tally.items()):
        say("outcome." + k.replace(" ", "_"), v)
    say("mismatches", len(rep.mismatches))
    for case, ra, rb in rep.mismatches[:15]:
        say("mismatch", show(case, ra, rb))
    diff, n = shape_diff(a, b)
    say("shapes", n)
    say("shape_diffs", len(diff))
    for d in diff[:15]:
        say("shape_diff", d[:300])
    say("seconds", "%.1f" % rep.seconds)
    return 0 if rep.ok and not diff else 1


def mode_coverage(args):
    rt = Runtime(args.rt, tag="a")
    cases = load_cases(args.repo)
    hit, rep = coverage(rt, cases)
    defs = definitions(rt.src)
    lines = executable_lines(rt)
    own = owners(rt.src)
    text = rt.src.split("\n")
    miss = sorted(lines - hit)
    say("platform", os.name)
    say("python", sys.version.split()[0])
    say("cases", rep.total)
    say("skipped", rep.skipped)
    for kind in sorted(rep.per_kind):
        say("kind." + kind, rep.per_kind[kind])
    say("lines", len(lines))
    say("lines_hit", len(lines & hit))
    say("lines_uncovered", len(miss))
    say("line_coverage", "%.2f" % (100.0 * len(lines & hit) / len(lines)))
    for ln in miss:
        say("uncovered", "%d %s | %s" % (ln, own.get(ln, "?"), text[ln - 1].strip()[:100]))
    entered = {own[ln] for ln in lines & hit if ln in own}
    say("never_entered", ",".join(sorted({own[ln] for ln in lines if ln in own} - entered)))
    # The branches only one host runs for real: each is said to be run or not on this one, never left out.
    unrun, gone = [], []
    for label, owner, frag in PLATFORM_BRANCHES:
        found = [ln for ln in lines if own.get(ln) == owner and frag in text[ln - 1]]
        state = "absent" if not found else "hit" if all(ln in hit for ln in found) else "uncovered"
        say("branch." + label, state)
        if state != "hit":
            unrun.append(label)
            if state == "absent":
                gone.append(label)
    say("branches_absent", ",".join(gone))
    say("branches_uncovered", ",".join(unrun))
    shape = shapes(rt)
    say("constants_compared", "%d/%d" % (sum(1 for n in defs["constant"] if n in shape), len(defs["constant"])))
    return 0


def mode_closure(args):
    src = open(args.rt, encoding="utf-8").read() if args.rt else ""
    if args.append:
        src += "\n" + open(args.append, encoding="utf-8").read()
    undefined, twice = closure(src)
    say("tokens_undefined", len(undefined))
    for n, line in sorted(undefined.items()):
        say("undefined", "%s:%d" % (n, line))
    say("defined_twice", ",".join(twice))
    return 0 if not undefined and not twice else 1


def strip_dead(src):
    """src without the names the corpus lists as dead, as their deletion leaves it: the harness must still pass."""
    import rt_cases
    drop = []
    for n in ast.parse(src).body:
        if isinstance(n, ast.FunctionDef) and n.name in rt_cases.DEAD_FUNCTIONS:
            drop.append((n.lineno, n.end_lineno))
        elif isinstance(n, ast.ClassDef) and n.name in rt_cases.DEAD_CLASSES:
            drop += [(m.lineno, m.end_lineno) for m in n.body if isinstance(m, ast.FunctionDef) and m.name in rt_cases.DEAD_METHODS]
    lines = src.split("\n")
    for start, end in sorted(drop, reverse=True):
        del lines[start - 1:end + 1]
    return "\n".join(lines), len(drop)


def dead_match(src):
    """How many of the corpus's dead names the runtime has: (functions, classes, methods) found, against listed."""
    import rt_cases
    body = ast.parse(src).body
    funcs = [n.name for n in body if isinstance(n, ast.FunctionDef) and n.name in rt_cases.DEAD_FUNCTIONS]
    classes = [n for n in body if isinstance(n, ast.ClassDef) and n.name in rt_cases.DEAD_CLASSES]
    methods = [m for c in classes for m in c.body if isinstance(m, ast.FunctionDef) and m.name in rt_cases.DEAD_METHODS]
    return (len(funcs), len(rt_cases.DEAD_FUNCTIONS)), (len(classes), len(rt_cases.DEAD_CLASSES)), (len(methods), len(classes))


def mode_strip(args):
    out, n = strip_dead(open(args.rt, encoding="utf-8").read())
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    say("stripped", n)
    (f, fl), (c, cl), (m, ml) = dead_match(open(args.rt, encoding="utf-8").read())
    say("dead.functions", "%d/%d" % (f, fl))
    say("dead.classes", "%d/%d" % (c, cl))
    say("dead.methods", "%d/%d" % (m, ml))
    # Every listed name is there (before the deletion) or no function and no method is (after it; the classes stay).
    say("dead.state", "present" if (f, c, m) == (fl, cl, ml) else "gone" if f == 0 and m == 0 else "DRIFTED")
    return 0


def mode_parse(args):
    src = open(args.rt, encoding="utf-8").read()
    if args.append:
        src += "\n" + open(args.append, encoding="utf-8").read()
    problems = parse310(src)
    say("interpreter", sys.version.split()[0])
    say("parse310_problems", len(problems))
    for p in problems[:20]:
        say("problem", p)
    return 0 if not problems else 1


def mode_bench(args):
    a, b = Runtime(args.a, tag="a"), Runtime(args.b, tag="b")
    bad = 0
    rows = bench(a, b, args.reps)
    if args.record:
        import json
        import platform
        best = 1e99
        for _ in range(7):
            t = time.perf_counter()
            sum(i * i for i in range(200000))
            best = min(best, time.perf_counter() - t)
        # The base numbers a change is held to; the calibration loop says how fast the machine was.
        with open(args.record, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"comment": BENCH_COMMENT, "python": sys.version.split()[0], "platform": platform.platform(), "calibration_us": round(best * 1e6, 1),
                       "reps": args.reps, "base_us": {n: round(ua, 1) for n, ua, _ in rows}}, f, indent=2)
            f.write("\n")
    for name, ua, ub in rows:
        ratio = ub / ua
        say("bench.%s.a_us" % name, "%.1f" % ua)
        say("bench.%s.b_us" % name, "%.1f" % ub)
        say("bench.%s.ratio" % name, "%.3f" % ratio)
        bad += ratio > args.bound
    say("bound", args.bound)
    say("over_bound", bad)
    return 0 if not bad else 1


def catch(text, mutant_text, cases, tries=3):
    """The first case that tells the mutant from the original and does so again on a fresh pair of runtimes, as
    (case, flakes): a difference that does not repeat is a flake (a timing race in a case) and is never a catch."""
    flakes = 0
    rest = cases
    while True:
        # A fresh original per run: a run that stops at its first difference leaves counters (the atomic writer's)
        # advanced in the runtime it ran on, and the next run would differ from it for that alone.
        rep = compare(Runtime(src=text, tag="a"), Runtime(src=mutant_text, tag="m"), rest, first=True)
        if not rep.mismatches:
            return None, flakes
        case = rep.mismatches[0][0]
        again = [compare(Runtime(src=text, tag="a"), Runtime(src=mutant_text, tag="m"), [case], first=True) for _ in range(tries)]
        if all(r.mismatches for r in again):
            return case, flakes
        flakes += 1
        rest = rest[[c is case for c in rest].index(True) + 1:]


def mode_mutants(args):
    import rt_mutants
    text = open(args.rt, encoding="utf-8").read()
    cases = load_cases(args.repo)
    missed = 0
    flaky = 0
    for m in rt_mutants.MUTANTS:
        case, flakes = catch(text, rt_mutants.apply(text, m), cases)
        flaky += flakes
        caught = case is not None
        where = "%s %s" % (case.kind, case.tag) if caught else "-"
        say("mutant." + m.id, ("caught by " + where) if caught else "MISSED")
        if caught == m.control:
            missed += 1
    say("mutants", len(rt_mutants.MUTANTS))
    say("missed", missed)
    say("flakes", flaky)
    pycache = [d for d, ds, fs in os.walk(HERE) if "__pycache__" in ds]
    say("pycache_dirs", len(pycache))
    return 0 if not missed and not pycache else 1


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="mode", required=True)
    for name in ("equiv", "bench"):
        s = sub.add_parser(name)
        s.add_argument("--a", required=True)
        s.add_argument("--b", required=True)
        s.add_argument("--repo", default=None)
        s.add_argument("--reps", type=int, default=7)
        s.add_argument("--bound", type=float, default=1.2)
        s.add_argument("--record", default=None)
    for name in ("coverage", "mutants", "closure", "parse", "strip-dead"):
        s = sub.add_parser(name)
        s.add_argument("--rt", required=True)
        s.add_argument("--repo", default=None)
        s.add_argument("--append", default=None)
        s.add_argument("--out", default=None)
    for s in sub.choices.values():
        s.add_argument("--timeout", type=int, default=600)
    args = p.parse_args()
    # A child that reads the stdin it inherits must meet the end of it, not a test runner's open pipe.
    null = os.open(os.devnull, os.O_RDONLY)
    os.dup2(null, 0)
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    if args.repo is None:
        args.repo = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
    # A case that never returns (a mutant can make a loop endless) ends the run with every thread's stack, not a hang.
    faulthandler.dump_traceback_later(args.timeout, exit=True)
    code = {"equiv": mode_equiv, "bench": mode_bench, "coverage": mode_coverage, "mutants": mode_mutants,
            "closure": mode_closure, "parse": mode_parse, "strip-dead": mode_strip}[args.mode](args)
    sys.exit(code)


if __name__ == "__main__":
    main()
