// macros: the generated header after <windows.h>. Without NOMINMAX, windows.h
// defines min and max as function-like macros; the header's kira/macro_push.hxx
// takes them down for its own declarations, and kira/macro_pop.hxx puts them
// back for the code that follows it.
#include <windows.h>
#include "../expected/src/win/names.kira.hxx"

#include <cstdio>

static_assert(MAX_PATH == 260, "macro_pop.hxx gave windows.h's macros back");
static_assert(max(1, 2) == 2, "and max is windows.h's macro again");

// The header's own guard, reused: without it `max(` below would expand.
#include "kira/macro_push.hxx"
namespace names
{
  std::int32_t Range::min() const
  {
      return lo;
  }

  std::int32_t Range::max() const
  {
      return hi;
  }

  std::int32_t min(std::int32_t a, std::int32_t b)
  {
      return a < b ? a : b;
  }

  std::int32_t max(std::int32_t a, std::int32_t b)
  {
      return a < b ? b : a;
  }
}

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
    std::printf("\nmacros - a generated header after <windows.h>\n\n");
    const names::Range r{};
    check(r.lo == 0 && r.hi == names::INTERVAL, "a struct whose methods are named min and max");
    check(r.min() == 0 && r.max() == 5, "and they are callable");
    check(names::min(3, 4) == 3 && names::max(3, 4) == 4, "free functions named min and max");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
#include "kira/macro_pop.hxx"
