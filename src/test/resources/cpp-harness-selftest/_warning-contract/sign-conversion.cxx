// One signed-to-unsigned conversion: -Wsign-conversion, which the design
// contracts for gcc, clang and zig (in C++ it is not part of -Wconversion,
// so arm's line does not carry it, and MSVC's C4365 is off by default).
namespace selftest
{
  unsigned widen(int i)
  {
    return i;
  }
}

int main()
{
  return selftest::widen(2) == 2u ? 0 : 1;
}
