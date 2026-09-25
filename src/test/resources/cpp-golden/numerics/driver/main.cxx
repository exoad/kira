// numerics: the integer and float rules the lowering depends on, on the paths
// that do not fail. The failing ones (x / 0, a shift of 32) are the runtime's,
// and kira/cpp/tests/run.sh drives them.
#include "../expected/src/lang/numerics.kira.hxx"

#include <cstdio>
#include <limits>

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

  void checkStr(const kira::Str& got, const char* want, const char* what)
  {
      const bool ok = got == want;
      check(ok, what);
      if(!ok)
      {
          std::printf("        got  \"%s\"\n        want \"%s\"\n", got.c_str(), want);
      }
  }

  // What a C++ emitter that typed fixed3's literals as double would compute.
  std::int32_t scaledWithDoubleLiterals(float v)
  {
      return kira::as<std::int32_t>(v * 1000.0 + 0.5);
  }
}

int main()
{
    std::printf("\nnumerics - wrapping, literal types, saturating as\n\n");
    check(numerics::wrapAdd(200, 100) == 44, "UInt8 200 + 100 wraps to 44");
    check(!numerics::overLimit(200, 100), "so 200 + 100 > 250 is false, where C++'s int says true");
    check(numerics::overLimit(200, 51), "and 200 + 51 > 250 is true");
    check(numerics::negate(-128) == -128 && numerics::negate(5) == -5, "Int8 -(-128) wraps to -128");
    check(numerics::flip(0) == 0xFFFF && numerics::flip(0x00FF) == 0xFF00, "UInt16 ~ stays 16 bits");
    check(numerics::lowByte(0x12345678u) == 0x78, "(v & 0xFF) as UInt8");
    check(numerics::micros(3) == 3000000 && numerics::micros(numerics::BIG) == 5000000000000000, "Int64 arithmetic");
    check(numerics::trillion() == 1000000000000, "a literal-only product is typed Int64, not Int32");
    check(numerics::ticksIn(3) == 3001, "Size arithmetic");
    check(numerics::BIG == 5000000000 && numerics::MASK == 0xFFFFFFFFu && numerics::HALF == 0.5f &&
              numerics::TICK == 1000 && numerics::BYTE == 200,
          "constants keep their types and values");

    checkStr(numerics::fixed3(0.25f), "0.250", "fixed3(0.25)");
    checkStr(numerics::fixed3(-0.5f), "-0.500", "fixed3(-0.5)");
    checkStr(numerics::fixed3(1.0f), "1.000", "fixed3(1.0)");
    checkStr(numerics::fixed3(0.2499f), "0.250", "fixed3 rounds the third decimal");
    checkStr(numerics::fixed3(0.000499999966f), "0.001", "fixed3(0.000499999966f): Float32 arithmetic rounds up");
    check(scaledWithDoubleLiterals(0.000499999966f) == 0, "where double literals would have printed 0.000");
    check(numerics::scaled(0.25f) == 250.5f, "v * 1000.0 + 0.5 is Float32 arithmetic");

    const double nan = std::numeric_limits<double>::quiet_NaN();
    check(numerics::toInt(1e10) == (std::numeric_limits<std::int32_t>::max)(), "as Int32 saturates 1e10");
    check(numerics::toInt(-1e10) == std::numeric_limits<std::int32_t>::lowest(), "and -1e10");
    check(numerics::toInt(nan) == 0, "and takes NaN to 0");
    check(numerics::toInt(-3.9) == -3 && numerics::toInt(3.9) == 3, "and truncates toward zero");
    check(numerics::toByte(300.0f) == 255 && numerics::toByte(-1.0f) == 0, "as UInt8 saturates both ways");
    check(numerics::toByte(std::numeric_limits<float>::quiet_NaN()) == 0, "and NaN is 0 there too");
    check(numerics::narrow(0x100000005) == 5 && numerics::narrow(-1) == -1, "Int64 as Int32 wraps");
    check(numerics::widen(7) == 7.0 && numerics::half(0.1) == 0.1f, "int to float, and float to float");
    check(numerics::code('A') == 65 && numerics::code('\xFF') == 255, "Char as UInt8 is the unsigned code unit");
    check(numerics::letter(66) == 'B', "UInt8 as Char");
    check(numerics::before('a', '\xE9') && !numerics::before('\xE9', 'a'), "Char < compares unsigned");
    check(numerics::ratio(7, 2) == 3 && numerics::ratio(-7, 2) == -3, "Int32 / truncates toward zero");
    check(numerics::rem(-7, 2) == -1 && numerics::rem(7, -2) == 1, "and % keeps the dividend's sign");
    check(numerics::tenth(-25) == -2, "a constant divisor");
    check(numerics::shift(1u, 31u) == 0x80000000u && numerics::eighth(64u) == 8u, "shifts");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
