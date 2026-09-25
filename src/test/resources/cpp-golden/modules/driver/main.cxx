// modules: two modules in two directories. report includes units by relative
// path (no include path is added for it), units is header-only in a namespace
// the manifest chose, and its private helper sits in impl_.
#include "../expected/src/app/report.kira.hxx"

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
    std::printf("\nmodules - relative includes, namespaces, header-only\n\n");
    check(golden::units::SCALE == 100, "units lives in golden::units, as the manifest says");
    check(golden::units::percent(42, 84) == 50, "a header-only function");
    check(golden::units::percent(300, 100) == 100 && golden::units::percent(-5, 100) == 0, "which calls its private helper");
    check(golden::units::percent(1, 0) == 0, "and guards a zero whole");
    check(golden::units::impl_::clampPct(150) == 100, "the helper is in impl_, reachable only by that name");
    check(report::line("x", 30).pct == 30, "a default argument from another module: ::golden::units::SCALE");
    check(report::render(report::line("load", 42, 84)) == "load: 50% #####", "report renders through units");
    const report::Line idle;
    check(idle.label.empty() && idle.pct == 0, "a struct's defaults");
    check(report::render(report::Line{.label = "idle"}) == "idle: 0% ", "and a designated initialiser");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
