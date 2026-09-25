// nm-check fixture: the C heap. CppHarnessSelfTest asserts that nmCheck
// reports malloc and free.
#include <cstdlib>

namespace selftest
{
  void* grab(unsigned n)
  {
    return std::malloc(n);
  }

  void drop(void* p)
  {
    std::free(p);
  }
}
