// decls: the declaration emitter by itself. Every function the three headers
// declare is bodiless in Kira, so this driver defines the ones it calls and
// checks what the declarations say: constants and their widths, default member
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
static_assert(order::K_A == 10 && order::K_B == 0x7F, "a constant read before its declaration too");
static_assert(order::NEG1 == -1, "a 64-bit hex literal folds as in C");
static_assert(order::TENS.size() == 10 && order::TENS[9] == 10, "an Arr size of 010 is ten");
static_assert(order::A == 2 && order::N == 4 && order::K == order::K_A, "forward references among values");
static_assert(std::is_same_v<order::Frame, std::array<std::uint8_t, 4>>, "an alias naming a later constant");
static_assert(order::ORIGIN.x == 0, "a constexpr constant of a struct type declared later");
static_assert(order::S{}.n == 5 && order::S{}.i.v == 1 && order::S{}.m == order::impl_::MODE_A, "private names a public struct uses");
static_assert(std::is_same_v<decltype(order::Cfg{}.limit), kira::Maybe<std::int32_t>>, "a Maybe field");
static_assert(order::MU8.has_value() && *order::MU8 == 200 && *order::MI16 == 5 && *order::Narrow{}.a == 7 && order::Narrow{}.b == 7, "literals under a Maybe carry the narrow type");
static_assert(std::is_same_v<decltype(order::MF), const kira::Maybe<float>> && *order::MF == 0.5f, "a float literal under a Maybe<Float32> is a float");
static_assert(std::is_same_v<decltype(order::COUNT), const std::int32_t> && std::is_same_v<decltype(order::RATIO), const float>, "Int and Float are Int32 and Float32");

namespace order
{
  Pt Pt::withX(std::int32_t x_p) const
  {
      Pt p = *this;
      p.x = x_p;
      return p;
  }

  std::int32_t scaleBy(std::int32_t limit_p)
  {
      return limit_p * 3;
  }

  std::int32_t Sized::grow(std::int32_t by) const
  {
      return width + by;
  }

  kira::Maybe<std::int32_t> find(const kira::Str& key, const kira::Maybe<std::int32_t>& hint)
  {
      return key.empty() ? kira::none : hint;
  }

  std::int32_t peek(const std::int32_t* p)
  {
      return *p;
  }

  std::int32_t narrow(const kira::Maybe<std::int16_t>& x)
  {
      return kira::isSome(x) ? kira::unwrap(x) : -1;
  }
}

static_assert(std::is_same_v<decltype(golden::shapes::KIND_BOX), const std::int32_t>, "an Int32 constant");
static_assert(std::is_same_v<decltype(golden::shapes::MASK_HIGH), const std::int16_t>, "an Int16 constant");
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

  Point Point::shifted(std::int32_t dx, std::int32_t dy) const
  {
      Point p = *this;
      p.x += dx;
      p.y += dy;
      return p;
  }

  std::int32_t Box::area() const
  {
      return size * size;
  }

  std::int32_t scaled(std::int32_t v, std::int32_t by, const kira::Str& unit)
  {
      return unit == UNIT_MM ? v * by : v * by * 25;
  }
}

namespace panel
{
  Cell place(const Cell& c, std::int32_t x, std::int32_t kind)
  {
      Cell out = c;
      out.at.x = x;
      out.level = kind == ::golden::shapes::KIND_DOT ? LEVEL_LOW : LEVEL_HIGH;
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
    check(KIND_BOX == 2, "an Int32 constant keeps its value");
    check(MASK_HIGH == 0xF0, "an Int16 constant keeps its hex value");
    check(std::strcmp(UNIT_IN, "in") == 0, "a Str constant keeps its text");
    check(SCALE == 100 && MAX_POINTS == 8, "integer constants");
    check(EPSILON == 0.001f && HALF == 0.5, "float constants take their own width");
    check(std::strcmp(NAME, "shapes") == 0 && TAG == 'S' && SIGNED, "Str, Char and Bool constants");
    // the Kira text is "??= ??/ ..." verbatim; the header spells each pair ?\? so no trigraph reaches gcc or clang
    check(std::strcmp(TRIGRAPHS, "?\?= ?\?/ ?\?' ?\?( ?\?) ?\?! ?\?< ?\?> ?\?- ?\?\?= ?") == 0 && std::strlen(TRIGRAPHS) == 42, "a Str constant holding every trigraph keeps its text");
    {
        const Box b{};
        check(b.origin.x == 0 && b.origin.y == 0, "a require field is value-initialized");
        check(b.size == 100 && b.kind == KIND_BOX && b.unit == UNIT_MM, "defaults name constants");
        check(b.label.empty() && b.outline.size() == 8, "a Str default and an alias-typed field");
        check(b.area() == 10000, "a const method declared in the struct");
    }
    {
        const Point p = Point{}.shifted(2);
        check(p.x == 2 && p.y == 0, "a defaulted method parameter is filled in");
        check(p.manhattan() == 2, "and a const method reads the fields");
    }
    check(scaled(7) == 700, "defaulted function parameters are filled in");
    check(scaled(7, 10, UNIT_IN) == 1750, "and can be given");
    created = 3;
    check(created == 3, "inline state in a header-only module");
    {
        const panel::Cell c{};
        check(c.at.x == 0 && c.level == panel::LEVEL_LOW && c.weight == 0.25f && c.tag == 'S', "defaults from another module");
        check(panel::LIMIT == 100 && panel::CELLS == 8, "constants copied from another module");
        const panel::Cell placed = panel::place(c);
        check(placed.at.x == 100 && placed.level == panel::LEVEL_LOW, "defaults from another module fill a call");
        panel::drawn = 1;
        check(panel::drawn == 1, "inline state in a module with no source file");
    }
    {
        order::cursor.x = 7;
        check(order::cursor.x == 7 && order::ORIGIN.y == 0, "state and a constant of a struct type declared later");
        const order::Pt p = order::Pt{}.withX(4);
        check(p.x == 4, "a parameter named like a field is renamed");
        check(order::scaleBy(2) == 6, "a parameter named like a module declaration is renamed");
        const order::Sized sized{};
        check(sized.width == 5 && sized.LIMIT == 5 && sized.Inner.v == 1, "a field named like a module declaration takes the declaration's value");
        check(sized.grow() == 10 && sized.std_ == 2, "a default argument named like a field, and a field named std");
        const order::Cfg cfg{};
        check(!kira::isSome(cfg.limit) && !kira::isSome(order::last), "null defaults are kira::none");
        check(!kira::isSome(order::find("k")) && kira::isSome(order::find("k", 3)), "a null default argument, filled or given");
        const std::int32_t in = 41;
        check(order::peek(&in) == 41, "Unsafe<T> is const T*");
        check(order::narrow() == 3 && order::narrow(std::int16_t{9}) == 9, "a narrow literal as a Maybe default argument");
        const order::Early early{};
        const order::Queued queued{};
        check(early.xs.empty() && queued.d.empty(), "containers of a struct declared later");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
