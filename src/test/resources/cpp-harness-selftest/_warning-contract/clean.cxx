// The control for warns.cxx: the same program with every parameter used.
// It must compile under every toolchain, or the contract test would be
// failing warns.cxx for a reason other than the warning.
#include "kira/core.hxx"

namespace selftest
{
  int withBoth(int used, int other)
  {
    return used * 2 + other * 0;
  }
}

int main()
{
  return selftest::withBoth(1, 2) == 2 ? 0 : 1;
}
