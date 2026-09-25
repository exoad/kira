// The control for the warning-contract fixtures: warning-free under every
// contracted flag, and C++20 (a `concept`), so it compiles only when
// -std=c++20 / /std:c++20 is really on. If this file failed, a fixture beside
// it would be failing for a reason other than its one warning.
#include "kira/core.hxx"
#include <concepts>

namespace selftest
{
  template<class T>
  concept Number = std::integral<T>;

  template<Number T>
  T twice(T v)
  {
    return static_cast<T>(v * 2);
  }

  int withBoth(int used, int other)
  {
    return used * 2 + other * 0;
  }
}

int main()
{
  return selftest::withBoth(1, 2) == 2 && selftest::twice(21) == 42 ? 0 : 1;
}
