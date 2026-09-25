// nm-check fixture: scalar operator new and operator delete (_Znwj, _ZdlPv on
// 32-bit arm-none-eabi). CppHarnessSelfTest asserts that nmCheck reports both.
namespace selftest
{
  int* make(int v)
  {
    return new int(v);
  }

  void drop(int* p)
  {
    delete p;
  }
}
