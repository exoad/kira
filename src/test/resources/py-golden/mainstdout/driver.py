#!/usr/bin/env python3
# The mainstdout golden (D87): a main that calls escapeStdout writes what a cp1252 stdout lacks as escapes, on a
# pipe and under a wrapper; one that does not stops there, as Python does, so no pipe gets text it did not ask for.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import io
import os
import subprocess
import sys

app = os.path.join(sys.argv[1], "src", "app")


def run(name, encoding):
    env = dict(os.environ, PYTHONIOENCODING=encoding)
    r = subprocess.run([sys.executable, os.path.join(app, name + "_kira.py")], capture_output=True, env=env)
    aborted = r.returncode in (-6, 134) or (os.name == "nt" and r.returncode == 3)
    err = r.stderr.decode("utf-8", "replace").splitlines()
    print("%s on a %s pipe -> exit %s, stdout %r%s" % (name, encoding, "abort" if aborted else r.returncode, r.stdout.replace(b"\r\n", b"\n"),
                                                      ", last: " + err[-1].partition(":")[0] if err else ""))


run("escaped", "cp1252")
run("escaped", "utf-8")
run("strict", "cp1252")
run("strict", "utf-8")

spec = importlib.util.spec_from_file_location("escaped_kira", os.path.join(app, "escaped_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
raw = io.BytesIO()
wrapper = io.TextIOWrapper(raw, encoding="cp1252", newline="\n")
kept, sys.stdout = sys.stdout, wrapper
try:
    m._k_run()
    wrapper.flush()
    written = raw.getvalue()
finally:
    sys.stdout = kept
print("escaped under a cp1252 wrapper ->", written)
