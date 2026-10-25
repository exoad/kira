// A polymorphic class whose destructor is not virtual: -Wnon-virtual-dtor
// (gcc, clang, zig). MSVC's C4265 is off by default and not contracted.
namespace selftest
{
  struct Base
  {
    virtual int value() const
    {
      return 1;
    }
  };

  struct Derived : Base
  {
    int value() const override
    {
      return 2;
    }
  };
}

int main()
{
  selftest::Derived d;
  const selftest::Base& b = d;
  return b.value() == 2 ? 0 : 1;
}
