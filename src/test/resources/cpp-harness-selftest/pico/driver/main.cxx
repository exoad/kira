// Driver for the pico self-test case: a freestanding, header-only module that
// reaches the checked ops (kira::div, kira::mod), a case.yaml define, and a
// static_assert the arm build must pass. It compiles under every toolchain;
// only the hosted ones link and run it.
//
// The profile follows the toolchain (design 8.2): arm-none-eabi builds the
// Pico image with KIRA_PROFILE_FREESTANDING=1, every host toolchain compiles
// this same header hosted. Either way round is a harness bug, and the hosted
// link on kira::panic would catch the second one anyway.
#include "src/blink.kira.hxx"
#include <cstdio>

#if defined(__arm__)
#if !defined(KIRA_PROFILE_FREESTANDING)
#error "arm-none-eabi builds the Pico image: the harness must define KIRA_PROFILE_FREESTANDING=1 for it"
#endif
#else
#if defined(KIRA_PROFILE_FREESTANDING)
#error "design 8.2: a host toolchain compiles a freestanding case hosted; KIRA_PROFILE_FREESTANDING=1 is for the Pico image only"
#endif
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
