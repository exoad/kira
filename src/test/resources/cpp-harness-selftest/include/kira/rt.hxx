// Harness self-test stand-in for kira/cpp/kira/rt.hxx (hosted umbrella).
#pragma once
#include "kira/core.hxx"
#include <cstdio>

#if defined(KIRA_PROFILE_FREESTANDING)
#error "kira/rt.hxx is hosted only; a freestanding module includes kira/core.hxx"
#endif

namespace kira
{
  inline void trace(Int32 v)
  {
    std::printf("%d\n", v);
  }

  inline void trace(bool v)
  {
    std::printf("%d\n", v ? 1 : 0);
  }
}
