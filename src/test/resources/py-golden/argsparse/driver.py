#!/usr/bin/env python3
# The argsparse golden (K7): main as a program beside argparse's parser built as decode_ext and train_ext built theirs,
# each command line's stdout, stderr and exit code alike; then parse from Python, the program errors, the value types.
#
#   python driver.py <the directory kira --target py --out wrote> [reference argv...]
import argparse
import builtins
import contextlib
import importlib.util
import io
import os
import subprocess
import sys

out = sys.argv[1]
path = os.path.join(out, "src", "app", "cli_kira.py")


# argparse's type=int, but only within Int64: a ValueError past it, which argparse words as for any int it refuses.
def int64(text):
    v = builtins.int(text)
    if not -(1 << 63) <= v < 1 << 63:
        raise ValueError(text)
    return v


int64.__name__ = "int"

if sys.argv[2:3] == ["reference"]:
    ap = argparse.ArgumentParser(prog="decode", description="Decodes the tag heads.")
    ap.add_argument("onnx")
    ap.add_argument("--samples", type=int64, default=200)
    ap.add_argument("--steps-per-epoch", type=int64, default=400, help="steps in each epoch")
    ap.add_argument("--out", default="runs/tagnet")
    ap.add_argument("--plan", help="pulse:seconds,... in place of --us/--seconds")
    ap.add_argument("--hold", type=float, default=1.5, help="seconds at each point")
    ap.add_argument("--duty", type=float, help="50%% of the period at most")
    ap.add_argument("--export-only", action="store_true")
    ap.add_argument("-v", action="store_true", help="print every frame")
    a = ap.parse_args(sys.argv[3:])
    print("onnx=%s samples=%d steps=%d out=%s plan=%s hold=%r duty=%s export=%s v=%s" % (
        a.onnx, a.samples, a.steps_per_epoch, a.out, a.plan or "none", a.hold, "none" if a.duty is None else repr(a.duty),
        str(a.export_only).lower(), str(a.v).lower()))
    sys.exit(0)

spec = importlib.util.spec_from_file_location("cli_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
env = dict(os.environ, COLUMNS="80", PYTHONIOENCODING="utf-8")


def ran(command):
    r = subprocess.run(command, capture_output=True, env=env)
    return r.returncode, r.stdout.decode("utf-8").replace("\r", ""), r.stderr.decode("utf-8").replace("\r", "")


lines = [
    [], ["m.onnx"], ["m.onnx", "--samples", "7", "--out", "o", "--plan", "1500:1.0", "--hold", "2", "--duty", "0.25", "--export-only", "-v"],
    ["--help"], ["m.onnx", "-h"], ["m.onnx", "--samples", "x"], ["m.onnx", "--samples", "9223372036854775807"],
    ["m.onnx", "--samples", "9223372036854775808"], ["m.onnx", "--samples", "-9223372036854775809"], ["m.onnx", "--samples", "1_000"],
    ["m.onnx", "--samples", " 12 "], ["m.onnx", "--samples", "٣"], ["m.onnx", "--samp", "5"], ["m.onnx", "--hold", "nan"],
    ["m.onnx", "--hold", "1e999"], ["m.onnx", "--hold", "1_5.0"], ["m.onnx", "--hold", "x"], ["m.onnx", "--bogus"], ["m.onnx", "--out"],
    ["a", "b"], ["m.onnx", "--steps-per-epoch=9"], ["m.onnx", "--", "-v"], ["m.onnx", "--plan", "é"], ["m.onnx", "--s", "1"],
]
same = 0
for argv in lines:
    got, want = ran([sys.executable, path] + argv), ran([sys.executable, __file__, out, "reference"] + argv)
    same += got == want
    if got != want:
        print("DIFFERS for", argv, got, want)
print("each command line prints and exits as argparse's parser:", same, "of", len(lines))
code, said, err = ran([sys.executable, path, "--help"])
print("--help:", code, said.splitlines()[0], "...", said.count("\n"), "lines; 50% kept:", "50% of the period" in said, err == "")
for argv in ([], ["m.onnx", "--samples", "9223372036854775808"], ["m.onnx", "--bogus"]):
    code, said, err = ran([sys.executable, path] + argv)
    print(argv, "->", code, repr(said), err.splitlines()[0][:22], "...", err.splitlines()[-1])

p = m.parsed(["m.onnx", "--hold", "3", "--duty", "1e-5"])
values = [m.textOf(p, "onnx"), m.wholeOf(p, "--samples"), m.realOf(p, "--hold"), m.realOf(p, "--duty"), m.textOf(p, "--plan"), m.isSet(p, "-v")]
print("parse from Python:", values, [type(v).__name__ for v in values])
for argv in (["--samples", "x"], ["--help"]):
    with contextlib.redirect_stderr(io.StringIO()) as e, contextlib.redirect_stdout(io.StringIO()) as o:
        try:
            m.parsed(argv)
            print("no exit", file=sys.__stdout__)
        except SystemExit as x:
            caught = x.code
    print(argv, "raises SystemExit", caught, "having written", len(o.getvalue()) > 0, len(e.getvalue()) > 0)


def panics(f, *args):
    try:
        f(*args)
        return "returned"
    except RuntimeError as e:
        return str(e)


print(panics(m.wholeOf, p, "--out"))
print(panics(m.textOf, p, "--nope"))
print(panics(m.isSet, p, "onnx"))
print(panics(m.twice))
print(panics(m.undashed))
print(panics(m.dashed))
