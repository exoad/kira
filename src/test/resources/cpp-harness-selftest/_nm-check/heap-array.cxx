// nm-check fixture: operator new[] and operator delete[]. On 32-bit
// arm-none-eabi these are _Znaj and _ZdaPv (size_t mangles as j), which a
// pattern written for x64 (_Znam) never matches. CppHarnessSelfTest asserts
// that nmCheck reports both.
namespace selftest
{
  int* make(unsigned n)
  {
    return new int[n];
  }

  void drop(int* p)
  {
    delete[] p;
  }
}
