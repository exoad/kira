// Driver for the hello self-test case, printing bibo's check format.
#include "src/hello.kira.hxx"
#include <cstdio>

namespace
{
  int checks = 0;
  int failed = 0;

  void check(const char* name, bool ok)
  {
    ++checks;
    if(!ok)
    {
      ++failed;
    }
    std::printf("%s %s\n", ok ? "ok" : "FAIL", name);
  }
}

int main()
{
  check("add(2, 3) == 5", hello::add(2, 3) == 5);
  check("add wraps at the top", hello::add(2147483647, 1) == -2147483647 - 1);
  ::kira::trace(hello::add(40, 2));
  std::printf("%d checks, %d failed\n", checks, failed);
  return failed == 0 ? 0 : 1;
}
