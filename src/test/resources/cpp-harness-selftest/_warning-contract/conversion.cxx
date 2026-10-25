// One narrowing conversion, double to int: -Wconversion (-Wfloat-conversion)
// on the GNU family and arm, C4244 under /W4.
namespace selftest
{
  int narrow(double d)
  {
    return d;
  }
}

int main()
{
  return selftest::narrow(2.0) == 2 ? 0 : 1;
}
