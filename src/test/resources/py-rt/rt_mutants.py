# Planted mutants for rt_equiv.py: one small edit each to a copy of the runtime, which the harness must tell apart
# from the original. `old` must occur once in the source, so an edit that stopped matching fails loudly instead of
# passing as "caught nothing". The controls change nothing that runs and must NOT be caught: they show a pass is
# not an accident of the comparison.
import collections

Mutant = collections.namedtuple("Mutant", "id old new control")


def m(id, old, new):
    return Mutant(id, old, new, False)


MUTANTS = [
    m("i32-upper-edge", "def _k_i32(v):\n    if -0x80000000 <= v <= 0x7FFFFFFF:", "def _k_i32(v):\n    if -0x80000000 <= v < 0x7FFFFFFF:"),
    m("i64-lower-edge", "def _k_i64(v):\n    if -0x8000000000000000 <= v <=", "def _k_i64(v):\n    if -0x8000000000000000 < v <="),
    m("i8-no-wrap", "    return ((v + 0x80) & 0xFF) - 0x80", "    return v & 0xFF"),
    m("u64-mask", "def _k_u64(v):\n    return v & 0xFFFFFFFFFFFFFFFF", "def _k_u64(v):\n    return v & 0x7FFFFFFFFFFFFFFF"),
    m("div-floors", "    q = abs(a) // abs(b)\n    return -q if (a < 0) != (b < 0) else q", "    return a // b"),
    m("mod-sign", "    r = abs(a) % abs(b)\n    return -r if a < 0 else r", "    return abs(a) % abs(b)"),
    m("divs-lowest", "    if b == -1 and a == -(1 << (bits - 1)):\n        _k_panic(\"integer division overflows\")\n", ""),
    m("fdiv-sign", "copysign(_k_math.inf, a) * _k_math.copysign(1.0, b)", "copysign(_k_math.inf, a)"),
    m("f2i-nan", "    if v != v:\n        return 0\n    lo =", "    if v != v:\n        return 1\n    lo ="),
    m("fbits-unsigned", 'return _k_struct.unpack("<q", _k_struct.pack("<d", v))[0]', 'return _k_struct.unpack("<Q", _k_struct.pack("<d", v))[0]'),
    m("round-half-even", "    if abs(v) - r >= 0.5:", "    if abs(v) - r > 0.5:"),
    m("floor-sign", "    return _k_math.copysign(float(_k_math.floor(v)), v)", "    return float(_k_math.floor(v))"),
    m("ftext-tie", "return sign + (fixed if len(fixed) <= len(sci) else sci)", "return sign + (fixed if len(fixed) < len(sci) else sci)"),
    m("fixed-clamp", "min(max(places, 0), 9)", "min(max(places, 0), 10)"),
    m("substr-end", "    if start > end or end > len(s):", "    if start > end or end >= len(s):"),
    m("slice-length", "    if at > len(v) or n > len(v) - at:\n        _k_panic(\"slice out of range\")\n    return (memoryview", "    if at > len(v) or n >= len(v) - at:\n        _k_panic(\"slice out of range\")\n    return (memoryview"),
    m("span-start", "def _k_span(b, at, n):\n    if at > len(b) or n > len(b) - at:", "def _k_span(b, at, n):\n    if at >= len(b) or n > len(b) - at:"),
    m("toint-upper", "    return v if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF else None\n\n\n_k_digits",
      "    return v if -0x8000000000000000 <= v < 0x7FFFFFFFFFFFFFFF else None\n\n\n_k_digits"),
    m("tointr-radix-edge", "    if not 2 <= radix <= 36:", "    if not 2 <= radix <= 35:"),
    m("tointr-lower", "    return v if -0x8000000000000000 <= v <= 0x7FFFFFFFFFFFFFFF else None\n\n\n# splitWhitespace",
      "    return v if -0x8000000000000000 < v <= 0x7FFFFFFFFFFFFFFF else None\n\n\n# splitWhitespace"),
    m("tofloat-inf", "        if _k_math.isinf(v):\n            return None\n", "        if False:\n            return None\n"),
    m("strof-ignore", 'return bytes(v).decode("utf-8", "replace")', 'return bytes(v).decode("utf-8", "ignore")'),
    m("strhash-seed", "    h = 5381\n", "    h = 5382\n"),
    m("utf8-strict", 'return bytearray(s.encode("utf-8", "surrogatepass"))', 'return bytearray(s.encode("utf-8", "replace"))'),
    m("sum-limit", "    top = 1 << (31 if t == \"Int32\" else 63)", "    top = 1 << (31 if t == \"Int32\" else 62)"),
    m("sum-float-fsum", "        return _k_functools.reduce(_k_operator.add, xs, 0.0)", "        return _k_math.fsum(xs)"),
    m("sort-zero-order", "    return t == \"Float64\" and (0.0 in xs or ", "    return t == \"Float64\" and ("),
    m("contains-nan", "    return v == v and v in xs", "    return v in xs"),
    m("json-depth-sound", "    for _ in _k_builtins.range(512):\n        if not level:", "    for _ in _k_builtins.range(513):\n        if not level:"),
    m("json-depth-fault", "                if len(stack) == 512:", "                if len(stack) == 513:"),
    m("json-depth-check", "    return height[_k_builtins.id(v)] <= 512", "    return height[_k_builtins.id(v)] <= 511"),
    m("json-int-range", "Integer out of Int64 range", "Integer out of range"),
    m("json-self-text", "a Json that holds itself", "a Json holding itself"),
    m("oskind-exists", '(b.FileExistsError, "Exists")', '(b.FileExistsError, "Exist")'),
    m("oskind-errno-class", "if isinstance(e, b.OSError) and isinstance(e.errno, int) else e", "if False else e"),
    m("oscategory-subprocess", '"ImportError", "subprocess.SubprocessError")', '"ImportError", "subprocess.SubprocessErrorX")'),
    m("atomic-leaves-tmp", "        try:\n            _k_os.remove(tmp)\n        except (_k_builtins.OSError, ValueError):\n            pass\n", "        pass\n"),
    m("atomic-no-fsync", "            _k_os.fsync(f.fileno())\n        _k_replace", "            pass\n        _k_replace"),
    m("replace-retry-count", "attempt >= 20", "attempt >= 19"),
    m("replace-winerror", "(5, 32)", "(5,)"),
    m("sliced-no-interrupt", "        if e.__dict__.get(\"signalled\", 0) != seen:\n            raise", "        if False:\n            raise"),
    m("utcstamp-ms", "ms % 1000)", "ms % 999)"),
    m("lftext-cr", '.replace("\\r\\n", "\\n").replace("\\r", "\\n")', '.replace("\\r\\n", "\\n")'),
    m("code-signal", "return 128 - rc if rc < 0 else", "return 128 + rc if rc < 0 else"),
    m("check-int-range", "if c is int and spec[1] <= v <= spec[2]:", "if c is int and spec[1] <= v < spec[2]:"),
    m("regroup-msg", "which its pattern lacks", "which it lacks"),
    m("recompile-reason", "        _k_reasons.regex = str(e)\n        return None", "        _k_reasons.regex = \"re: \" + str(e)\n        return None"),
    m("httppieces-expected", "IncompleteRead(bytes(data), n - len(data))", "IncompleteRead(bytes(data), n)"),
    # The edits an earlier review planted that the corpus could not see.
    m("jdeep-no-decrement", "        elif c == \"]\" or c == \"}\":\n            depth -= 1\n    return -1", "        elif c == \"]\" or c == \"}\":\n            depth -= 0\n    return -1"),
    m("jdeep-edge-511", "            if depth > 512:\n                return m.start()", "            if depth > 511:\n                return m.start()"),
    m("roundto-overflow-sign", "    except OverflowError:\n        return _k_math.copysign(_k_math.inf, v)\n\n\ndef _k_roundeven", "    except OverflowError:\n        return _k_math.inf\n\n\ndef _k_roundeven"),
    m("isstale-no-exists", "return self.f is None or self.f.closed or not _k_os.path.exists(self.path)", "return self.f is None or self.f.closed"),
    m("udp-nothing-waiting", "            except _k_builtins.BlockingIOError:\n                return None\n            except _k_builtins.ConnectionResetError", "            except _k_builtins.BlockingIOError:\n                return _k_Datagram(0, \"\", 0)\n            except _k_builtins.ConnectionResetError"),
    m("poller-no-hup", "got[h] = (1 if re & _k_select.POLLIN or hup else 0)", "got[h] = (1 if re & _k_select.POLLIN else 0)"),
    m("poller-hup-error", "| (4 if re & (_k_select.POLLERR | _k_select.POLLNVAL) or hup else 0)", "| (4 if re & (_k_select.POLLERR | _k_select.POLLNVAL) else 0)"),
    m("readyin-poll-ms", "return bool(p.poll(s * 1000))", "return bool(p.poll(s))"),
    m("syncdir-no-fsync", "            _k_os.fsync(fd)\n        finally:", "            pass\n        finally:"),
    m("kill-posix-term", "        else:\n            self.p.kill()", "        else:\n            self.p.terminate()"),
    m("recvfrom-reset-ignored", "                if _k_os.name != \"nt\":\n                    _k_osfail(e)\n                    return None", "                if _k_os.name == \"nt\":\n                    _k_osfail(e)\n                    return None"),
    Mutant("control-no-change", "def _k_btext(b):", "def _k_btext(b):", True),
    Mutant("control-comment-only", "# floor, ceil and round return a Float64", "# floor, ceil and round give a Float64", True),
]


def apply(src, mutant):
    if src.count(mutant.old) != 1:
        raise SystemExit("mutant %s: its text occurs %d times in the runtime, not once" % (mutant.id, src.count(mutant.old)))
    return src.replace(mutant.old, mutant.new)
