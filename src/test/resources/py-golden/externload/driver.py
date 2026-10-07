#!/usr/bin/env python3
# The externload golden: each module whose sidecar does not match its externs panics at import.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import shutil
import sys
import tempfile

for name in ("missing", "arity", "badname", "order", "broad", "notclass", "fine"):
    path = os.path.join(sys.argv[1], "src", "app", name + ".kira.py")
    spec = importlib.util.spec_from_file_location(name + "_kira", path)
    m = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(m)
        print(name, "-> loaded")
    except RuntimeError as e:
        print(name, "->", e)

m.sleepS(0)
try:
    m.g()
except sys.modules["kira:errors"].Error as e:
    print("fine.g ->", e.args[0])

# fine.kira.py copied where its sidecar is not, and fine.kira.py reached through a symlink.
tmp = tempfile.mkdtemp()
generated = os.path.join(sys.argv[1], "src", "app", "fine.kira.py")
for label, path in (("copied", os.path.join(tmp, "copied.kira.py")), ("linked", os.path.join(tmp, "linked.kira.py"))):
    if label == "copied":
        shutil.copy(generated, path)
    else:
        os.symlink(generated, path)
    spec = importlib.util.spec_from_file_location(label, path)
    try:
        spec.loader.exec_module(importlib.util.module_from_spec(spec))
        print(label, "-> loaded")
    except RuntimeError as e:
        path = str(e)[len("kira: the sidecar "):-len(" is missing")]
        print(label, "-> kira: the sidecar .../%s is missing, an absolute path: %s" % (os.path.basename(path), os.path.isabs(path)))
shutil.rmtree(tmp)
