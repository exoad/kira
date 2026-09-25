// One unused local: -Wall (-Wunused-variable) on the GNU family and arm,
// C4189 under /W4.
namespace selftest
{
  int withUnusedLocal(int used)
  {
    int unused = used + 1;
    return used * 2;
  }
}

int main()
{
  return selftest::withUnusedLocal(1) == 2 ? 0 : 1;
}
