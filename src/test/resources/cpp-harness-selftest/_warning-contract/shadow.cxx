// One local shadowing another: -Wshadow (gcc, clang, zig), C4456 under /W4.
namespace selftest
{
  int shadowed(int seed)
  {
    int a = seed;
    {
      int a = seed + 1;
      seed = a;
    }
    return a + seed;
  }
}

int main()
{
  return selftest::shadowed(1) == 3 ? 0 : 1;
}
