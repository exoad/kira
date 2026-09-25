// The warning-contract fixture: one unused parameter. -Wextra (gcc, clang,
// zig, arm-none-eabi) and /W4 (C4100) both flag it, so under -Werror or /WX
// this file must not compile. CppWarningContractTest asserts exactly that.
#include "kira/core.hxx"

namespace selftest
{
  int withUnused(int used, int unused)
  {
    return used * 2;
  }
}

int main()
{
  return selftest::withUnused(1, 2) == 2 ? 0 : 1;
}
