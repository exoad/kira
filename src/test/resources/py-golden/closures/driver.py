#!/usr/bin/env python3
# cpp-golden/closures' driver/main.cxx, check for check, then fxvalues' main.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "lang", "closures.kira.py")
spec = importlib.util.spec_from_file_location("closures_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

checks = 0
failures = 0


def check(ok, what):
    global checks, failures
    checks += 1
    if ok:
        print("  ok    %s" % what)
    else:
        print("  FAIL  %s" % what)
        failures += 1


print("\nclosures - captures by value, Ref for shared state\n")
check(m.scaleBy(4)(2) == 8, "[k]: a free function's local")
check(m.applyTo(lambda v: v * v, 7) == 49, "a C++ lambda passes to a template Fx parameter")
check(m.addAll([1, 2, 3], 10) == 36, "a capturing lambda to a template parameter")
g = m.Gain()
a = g.scaler()
b = g.twicer()
g.k = 100
check(a(2) == 6, "[c_k = k]: a struct field, copied under its own name")
check(b(2) == 12, "[*this]: the struct, copied, for a method call")
ctr = m.Counter()
check(ctr.offset(1) == 6, "[this]: a class method's lambda that does not escape")
mult = ctr.multiplier()
ctr.k = 7
check(mult(2) == 14, "[self = shared_from_this()]: an escaping lambda sees later writes")
del ctr
check(mult(3) == 21, "and keeps the object alive after the last Rc is gone")
on_stack = m.Counter(k=2)
check(on_stack.offset(1) == 3, "a stack-constructed class still runs its non-escaping lambdas")
t = m.tally()
check(t() == 1 and t() == 2, "[counter]: a Ref<Int32> is mutable through the copy")
copy = t
check(copy() == 3 and t() == 4, "and a copy of the closure shares it")
check(m.tally()() == 1, "while a new tally starts its own")
print("\n%d checks, %d failed" % (checks, failures))
sys.stdout.flush()
runpy.run_path(os.path.join(sys.argv[1], "src", "lang", "fxvalues.kira.py"), run_name="__main__")
sys.exit(0 if failures == 0 else 1)
