// decls: the declaration emitter by itself. Every function the three headers
// declare is bodiless in Kira, so this driver defines the ones it calls and
// checks what the declarations say: enum bases and values, default member
// initializers, defaulted parameters, the alias, the constants and the inline
// state, across two modules in two directories (app:panel uses lib:shapes),
// and app:order's declaration order, private names, null defaults, number
// spellings, de-shadowed parameters and Unsafe.
#include "../expected/src/app/panel.kira.hxx"
#include "../expected/src/app/order.kira.hxx"

#include <cstdio>
#include <cstring>
#include <type_traits>

static_assert(order::TEN == 10 && order::NINE == 9, "Kira reads 010 and 09 as decimal");
static_assert(static_cast<int>(order::Kind::K_A) == 10 && static_cast<int>(order::Kind::K_B) == 0x7F, "an enum entry too");
static_assert(order::NEG1 == -1 && order::NEG32 == -1, "a hex literal past a signed width wraps as in C");
static_assert(order::TENS.size() == 10 && order::TENS[9] == 10, "an Arr size of 010 is ten");
static_assert(order::A == 2 && order::N == 4 && order::K == order::Kind::K_A, "forward references among values");
static_assert(std::is_same_v<order::Frame, std::array<std::uint8_t, 4>>, "an alias naming a later constant");
static_assert(order::ORIGIN.x == 0, "a constexpr constant of a struct type declared later");
static_assert(order::S{}.n == 5 && order::S{}.i.v == 1 && order::S{}.m == order::impl_::Mode::MODE_A, "private names a public struct uses");
static_assert(std::is_same_v<decltype(order::Cfg{}.limit), kira::Maybe<std::int32_t>>, "a Maybe field");

namespace order
{
  void Pt::setX(std::int32_t x_p)
  {
      x = x_p;
  }

  std::int32_t f(std::int32_t x)
  {
      return x * 2;
  }

  std::int32_t scaleBy(std::int32_t limit_p)
  {
      return limit_p * 3;
  }

  kira::Maybe<std::int32_t> find(const kira::Str& key, const kira::Maybe<std::int32_t>& hint)
  {
      return key.empty() ? kira::none : hint;
  }

  std::int32_t peek(const std::int32_t* p, std::int32_t* q)
  {
      *q = *p + 1;
      return *p;
  }
}

static_assert(std::is_same_v<std::underlying_type_t<golden::shapes::Kind>, std::int32_t>, "Int32 base");
static_assert(std::is_same_v<std::underlying_type_t<golden::shapes::Mask>, std::int16_t>, "Int16 base");
static_assert(std::is_same_v<std::underlying_type_t<golden::shapes::Unit>, std::int32_t>, "a Str enum numbers its entries");
static_assert(std::is_same_v<golden::shapes::Outline, std::array<std::int32_t, 8>>, "the alias names the constant as its size");
static_assert(golden::shapes::BIG == 5000000000, "an Int64 constant");
static_assert(golden::shapes::MASK_ALL == 0xFFFFFFFFu, "a UInt32 constant");
static_assert(golden::shapes::ORDER[0] == 3 && golden::shapes::DELTAS[0] == -1, "Arr constants are constexpr");
static_assert(std::is_same_v<decltype(golden::shapes::NAME), const char* const>, "a Str constant is a const char*");

namespace golden::shapes
{
  std::int32_t Point::manhattan() const
  {
      return (x < 0 ? -x : x) + (y < 0 ? -y : y);
  }

  void Point::shift(std::int32_t dx, std::int32_t dy)
  {
      x += dx;
      y += dy;
  }

  std::int32_t Box::area() const
  {
      return size * size;
  }

  std::int32_t scaled(std::int32_t v, std::int32_t by, Unit unit)
  {
      return unit == Unit::UNIT_MM ? v * by : v * by * 25;
  }
}

namespace panel
{
  Cell place(const Cell& c, std::int32_t x, ::golden::shapes::Kind kind)
  {
      Cell out = c;
      out.at.x = x;
      out.level = kind == ::golden::shapes::Kind::KIND_DOT ? Level::LEVEL_LOW : Level::LEVEL_HIGH;
      return out;
  }
}

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++failures;
      }
  }
}

int main()
{
    using namespace golden::shapes;
    std::printf("\ndecls - declarations by themselves\n\n");
    check(static_cast<int>(Kind::KIND_BOX) == 2, "an Int32 enum keeps its values");
    check(static_cast<int>(Mask::MASK_HIGH) == 0xF0, "an Int16 enum keeps its hex values");
    check(static_cast<int>(Unit::UNIT_IN) == 1, "a Str enum numbers its entries in order");
    check(SCALE == 100 && MAX_POINTS == 8, "integer constants");
    check(EPSILON == 0.001f && HALF == 0.5, "float constants take their own width");
    check(std::strcmp(NAME, "shapes") == 0 && TAG == 'S' && SIGNED, "Str, Char and Bool constants");
    {
        const Box b{};
        check(b.origin.x == 0 && b.origin.y == 0, "a require field is value-initialized");
        check(b.size == 100 && b.kind == Kind::KIND_BOX && b.unit == Unit::UNIT_MM, "defaults name constants and enum entries");
        check(b.label.empty() && b.outline.size() == 8, "a Str default and an alias-typed field");
        check(b.area() == 10000, "a const method declared in the struct");
    }
    {
        Point p{};
        p.shift(2);
        check(p.x == 2 && p.y == 0, "a defaulted method parameter is filled in");
        check(p.manhattan() == 2, "and a const method reads the fields");
    }
    check(scaled(7) == 700, "defaulted function parameters are filled in");
    check(scaled(7, 10, Unit::UNIT_IN) == 1750, "and can be given");
    created = 3;
    check(created == 3, "inline state in a header-only module");
    {
        const panel::Cell c{};
        check(c.at.x == 0 && c.level == panel::Level::LEVEL_LOW && c.weight == 0.25f && c.tag == 'S', "defaults from another module");
        check(panel::LIMIT == 100 && panel::CELLS == 8, "constants copied from another module");
        const panel::Cell placed = panel::place(c);
        check(placed.at.x == 100 && placed.level == panel::Level::LEVEL_LOW, "defaults from another module fill a call");
        panel::drawn = 1;
        check(panel::drawn == 1, "inline state in a module with no source file");
    }
    {
        order::cursor.x = 7;
        check(order::cursor.x == 7 && order::ORIGIN.y == 0, "state and a constant of a struct type declared later");
        order::Pt p{};
        p.setX(4);
        check(p.x == 4, "a parameter named like a field is renamed");
        check(order::f() == 10, "a default argument that is a private constant");
        check(order::scaleBy(2) == 6, "a parameter named like a module declaration is renamed");
        const order::Cfg cfg{};
        check(!kira::isSome(cfg.limit) && !kira::isSome(order::last), "null defaults are kira::none");
        check(!kira::isSome(order::find("k")) && kira::isSome(order::find("k", 3)), "a null default argument, filled or given");
        std::int32_t in = 41;
        std::int32_t out = 0;
        check(order::peek(&in, &out) == 41 && out == 42, "Unsafe<T> is const T*, T* when mut");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
