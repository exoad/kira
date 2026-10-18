#!/usr/bin/env python3
# The importname golden (D85): x_kira.py is a name Python imports, and a module is one module whichever side,
# Python's import or Kira's use, loads it first; _k_run() returns main's code where the guard exits with it.
#
#   python driver.py <the directory kira --target py --out wrote>
import os
import shutil
import subprocess
import sys
import tempfile

out = sys.argv[1]
app = os.path.join(out, "src", "app")


def copies(path):
    # The distinct module objects of one file, and the sys.modules keys that hold them.
    real = os.path.normcase(os.path.realpath(path))
    held = [(k, m) for k, m in list(sys.modules.items()) if getattr(m, "__file__", None) and os.path.normcase(os.path.realpath(m.__file__)) == real]
    return len({id(m) for _, m in held}), sorted("path" if os.path.isabs(k) else k for k, _ in held)


sys.path.insert(0, app)
import lib_kira  # noqa: E402

print("imported first: registered as", copies(lib_kira.__file__))
import main_kira  # noqa: E402

print("used after:", main_kira.viaLib(), lib_kira.count, copies(lib_kira.__file__))
print("_k_run ->", main_kira._k_run(), "| lib at", lib_kira.count)

import quiet_kira  # noqa: E402

print("void _k_run ->", quiet_kira._k_run())

later = "import sys; sys.path.insert(0, sys.argv[1]); import main_kira; import lib_kira; print(main_kira.viaLib(), lib_kira.count, main_kira.viaLib(), lib_kira.count)"
r = subprocess.run([sys.executable, "-c", later, app], capture_output=True, text=True)
print("used first, imported after:", r.stdout.strip() or r.stderr.strip())

pkg = os.path.join(out, "src", "pkg")
with open(os.path.join(pkg, "__init__.py"), "w") as f:
    f.write("")
sys.path.insert(0, os.path.join(out, "src"))
import pkg.a_kira  # noqa: E402
from pkg import b_kira  # noqa: E402

print("in a package:", pkg.a_kira.twice(), b_kira.ticks, b_kira.__name__, sys.modules["pkg.b_kira"] is b_kira, copies(b_kira.__file__))

# A package whose files are links (colcon --symlink-install), and a name an earlier sys.path entry holds.
site, shadow = tempfile.mkdtemp(), tempfile.mkdtemp()
os.mkdir(os.path.join(site, "pkg"))
open(os.path.join(site, "pkg", "__init__.py"), "w").close()
for n in ("a_kira.py", "b_kira.py"):
    os.symlink(os.path.join(out, "src", "pkg", n), os.path.join(site, "pkg", n))
linked = "import sys; sys.path.insert(0, sys.argv[1]); import pkg.a_kira; from pkg import b_kira; print(pkg.a_kira.twice(), b_kira.ticks, pkg.a_kira._k_m_golden_py_importname_pkg_b is b_kira)"
r = subprocess.run([sys.executable, "-c", linked, site], capture_output=True, text=True)
print("a package of links:", r.stdout.strip() or r.stderr.strip())
with open(os.path.join(shadow, "lib_kira.py"), "w") as f:
    f.write("count = 'shadow'\n")
shadowed = "import sys; sys.path[:0] = sys.argv[1:]; import main_kira; import lib_kira; print(main_kira.viaLib(), lib_kira.count, main_kira._k_m_golden_py_importname_app_lib is lib_kira)"
r = subprocess.run([sys.executable, "-c", shadowed, shadow, app], capture_output=True, text=True)
print("a name an earlier entry holds:", r.stdout.strip() or r.stderr.strip())
shutil.rmtree(site)
shutil.rmtree(shadow)


def program(args, code):
    r = subprocess.run([sys.executable] + args, capture_output=True, text=True, cwd=app)
    lines = r.stdout.splitlines() + r.stderr.splitlines()[-1:]
    print("%s -> exit %d %s" % (" ".join(code), r.returncode, lines))


program(["main_kira.py", "x"], ["main_kira.py", "x"])
program(["-c", "import sys; sys.path.insert(0, sys.argv[1]); import main_kira; sys.exit(main_kira._k_run())", app], ["console script main"])
program(["quiet_kira.py"], ["quiet_kira.py"])
program(["-c", "exec(compile(open('quiet_kira.py').read(), 'quiet_kira.py', 'exec'))"], ["quiet_kira.py exec'd without a file"])
program(["thrower_kira.py"], ["thrower_kira.py"])
program(["-c", "import sys; sys.path.insert(0, sys.argv[1]); import thrower_kira; sys.exit(thrower_kira._k_run())", app], ["console script thrower"])
