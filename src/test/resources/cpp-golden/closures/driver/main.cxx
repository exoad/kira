// closures: every capture lowering, run. A struct's closures hold a copy; a
// class's escaping closure holds the object and sees later writes; a Ref is
// shared, mutable state.
#include "../expected/src/lang/closures.kira.hxx"

#include <cstdio>

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++failures;
      }
  }
}

int main()
{
    std::printf("\nclosures - captures by value, Ref for shared state\n\n");
    check(closures::scaleBy(4)(2) == 8, "[k]: a free function's local");
    check(closures::applyTo([](std::int32_t v) { return v * v; }, 7) == 49, "a C++ lambda passes to a template Fx parameter");
    check(closures::addAll(kira::List<std::int32_t>{1, 2, 3}, 10) == 36, "a capturing lambda to a template parameter");
    {
        closures::Gain g;
        const kira::Fn<std::int32_t(std::int32_t)> a = g.scaler();
        const kira::Fn<std::int32_t(std::int32_t)> b = g.twicer();
        g.k = 100;
        check(a(2) == 6, "[c_k = k]: a struct field, copied under its own name");
        check(b(2) == 12, "[*this]: the struct, copied, for a method call");
    }
    kira::Fn<std::int32_t(std::int32_t)> m;
    {
        const kira::Rc<closures::Counter> ctr = std::make_shared<closures::Counter>();
        check(ctr->offset(1) == 6, "[this]: a class method's lambda that does not escape");
        m = ctr->multiplier();
        ctr->k = 7;
        check(m(2) == 14, "[self = shared_from_this()]: an escaping lambda sees later writes");
    }
    check(m(3) == 21, "and keeps the object alive after the last Rc is gone");
    {
        closures::Counter onStack{2};
        check(onStack.offset(1) == 3, "a stack-constructed class still runs its non-escaping lambdas");
    }
    const kira::Fn<std::int32_t()> t = closures::tally();
    check(t() == 1 && t() == 2, "[counter]: a Ref<Int32> is mutable through the copy");
    const kira::Fn<std::int32_t()> copy = t;
    check(copy() == 3 && t() == 4, "and a copy of the closure shares it");
    check(closures::tally()() == 1, "while a new tally starts its own");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
