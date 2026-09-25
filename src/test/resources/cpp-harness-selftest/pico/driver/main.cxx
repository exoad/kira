// Driver for the pico self-test case: freestanding profile, header-only
// module, a case.yaml define, and a static_assert the arm build must pass.
// It compiles under every toolchain; only the hosted ones link and run it.
#include "blink.kira.hxx"
#include <cstdio>

#if !defined(KIRA_PROFILE_FREESTANDING)
#error "case.yaml says freestanding, but the harness did not define KIRA_PROFILE_FREESTANDING"
#endif
#if !defined(KIRA_SELFTEST_TICKS) || KIRA_SELFTEST_TICKS != 7
#error "case.yaml's defines did not reach the compiler"
#endif

static_assert(blink::isOn(0), "tick 0 is on");
static_assert(!blink::isOn(blink::PERIOD_TICKS), "the first period ends off");

int main()
{
  int checks = 0;
  int failed = 0;
  for(::kira::Int32 tick = 0; tick < KIRA_SELFTEST_TICKS; ++tick)
  {
    const bool expected = (tick < 4);
    const bool ok = blink::isOn(tick) == expected;
    ++checks;
    if(!ok)
    {
      ++failed;
    }
    std::printf("%s tick %d %s\n", ok ? "ok" : "FAIL", static_cast<int>(tick), expected ? "on" : "off");
  }
  std::printf("%d checks, %d failed\n", checks, failed);
  return failed == 0 ? 0 : 1;
}
