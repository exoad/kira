// rt_test - the Kira C++ runtime (kira/cpp/kira) against the semantics the
// design fixes for it. bash kira/cpp/tests/run.sh builds and runs it on every
// toolchain found; kira\cpp\tests\msvc.bat does the same with MSVC.
//
// One file, three builds:
//   hosted            the whole runtime; prints bibo's check format
//   freestanding      -DKIRA_PROFILE_FREESTANDING=1 -c: core.hxx only, with a
//                     stub panic, for the no-heap/EH/RTTI symbol check
//   KIRA_RT_NEGATIVE  -DKIRA_RT_NEGATIVE=n: a failing check inside a
//                     static_assert, which must NOT compile
//
// Hosted modes, for the checks a passing run cannot make on itself:
//   rt_test panic <what>   hits one runtime check; must abort with "kira: ..."
//   rt_test throw          an uncaught kira::Error under runMain; must exit 70
//
// On Windows <windows.h> comes first, as it does in the viewer's glue: the
// runtime must compile under its macros, and a module inside the macro guard
// may declare ERROR, IN, OUT and min.
#if defined(_WIN32) && !defined(KIRA_PROFILE_FREESTANDING)
#include <windows.h>
#define KIRA_RT_WINDOWS_H 1
#if defined(min)
#define KIRA_RT_HAD_MIN 1
#endif
#if defined(small)
#define KIRA_RT_HAD_SMALL 1
#endif
#if defined(near)
#define KIRA_RT_HAD_NEAR 1
#endif
#else
#define KIRA_RT_WINDOWS_H 0
#endif

#include "kira/core.hxx"

#if KIRA_PROFILE_HOSTED
#include "kira/main.hxx"
#include "kira/rt.hxx"

#include <clocale>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <locale>

// ---- R-A's LEND (40-round3 2): which accessors lend their receiver's storage --
// The rules make the result of each of these a place of its receiver
// (Rules.ACCESSORS), because C++ hands back a reference into it; the emitter
// pins the same set against the *.bind.yaml manifests (CppBindingTableTest).
// Each half is checked against the other: the lenders return an lvalue
// reference, and the accessors the set leaves out return a fresh value.
static_assert(std::is_lvalue_reference_v<decltype(kira::at(std::declval<const kira::List<int>&>(), 0))>, "List.get lends");
static_assert(std::is_lvalue_reference_v<decltype(kira::at(std::declval<const std::array<int, 2>&>(), 0))>, "Arr.get lends");
static_assert(std::is_lvalue_reference_v<decltype(std::declval<const kira::View<int>&>()[0])>, "View.get lends");
static_assert(std::is_lvalue_reference_v<decltype(std::declval<const kira::MutView<int>&>()[0])>, "MutView.get lends");
static_assert(std::is_lvalue_reference_v<decltype(kira::unwrap(std::declval<const std::optional<kira::List<int>>&>()))>, "Maybe.unwrap lends");
static_assert(std::is_lvalue_reference_v<decltype(std::declval<const kira::Result<kira::Str, kira::Str>&>().unwrap())>, "Result.unwrap lends");
static_assert(std::is_lvalue_reference_v<decltype(std::declval<const kira::Result<kira::Str, kira::Str>&>().unwrapErr())>, "Result.unwrapErr lends");
static_assert(!std::is_reference_v<decltype(std::declval<const kira::Stack<int>&>().peek())>, "Stack.peek copies");
static_assert(!std::is_reference_v<decltype(std::declval<const kira::Queue<int>&>().peek())>, "Queue.peek copies");
static_assert(!std::is_reference_v<decltype(std::declval<const kira::Map<int, int>&>().get(0))>, "Map.get copies");
#endif

// ---- a module inside the macro guard, as the emitter writes one --------------
#include "kira/macro_push.hxx"
namespace level
{
  enum class Level : std::int32_t
  {
      ERROR = 1,
      IN = 2,
      OUT = 3,
      DELETE = 4,
      TRUE = 5,
      OPAQUE = 6,
  };
  inline constexpr std::int32_t INFINITE = 7;
  inline constexpr std::int32_t small = 8;

  [[nodiscard]] constexpr std::int32_t min(std::int32_t a, std::int32_t b)
  {
      return a < b ? a : b;
  }
  [[nodiscard]] constexpr std::int32_t max(std::int32_t a, std::int32_t b)
  {
      return a > b ? a : b;
  }
  [[nodiscard]] constexpr std::int32_t pick(Level l)
  {
      return l == Level::ERROR ? INFINITE : (min)(small, static_cast<std::int32_t>(l));
  }
  [[nodiscard]] constexpr std::int32_t fromInside()
  {
      return pick(Level::ERROR) + pick(Level::DELETE) + (max)(1, 2);
  }
}
#include "kira/macro_pop.hxx"

#if KIRA_RT_WINDOWS_H
// The guard gave <windows.h>'s macros back: every one it had before.
#if !defined(ERROR) || !defined(DELETE) || !defined(IN)
#error "kira/macro_pop.hxx did not restore <windows.h>'s macros"
#endif
#if (defined(KIRA_RT_HAD_MIN) && !defined(min)) || (defined(KIRA_RT_HAD_SMALL) && !defined(small))
#error "kira/macro_pop.hxx did not restore min or small"
#endif
#if (defined(KIRA_RT_HAD_NEAR) && !defined(near))
#error "kira/macro_pop.hxx did not restore near"
#endif
static_assert(ERROR == 0, "ERROR is windows.h's again");
#endif

// ---- compile-time: every check is constexpr --------------------------------
namespace
{
  enum class Mood : std::int16_t
  {
      MOOD_CALM = 0,
      MOOD_BUSY = 5,
      MOOD_LOST = -2,
  };
  // What the emitter writes beside an enum used with enumOf, and with text.
  [[nodiscard]] constexpr std::array<Mood, 3> enum_values(Mood)
  {
      return {Mood::MOOD_CALM, Mood::MOOD_BUSY, Mood::MOOD_LOST};
  }
  [[nodiscard]] constexpr const char* nameOf(Mood v)
  {
      switch(v)
      {
          case Mood::MOOD_CALM:
              return "MOOD_CALM";
          case Mood::MOOD_BUSY:
              return "MOOD_BUSY";
          case Mood::MOOD_LOST:
              return "MOOD_LOST";
      }
      return "";
  }

  inline constexpr std::array<std::uint8_t, 9> CHECK_TEXT = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};

  [[nodiscard]] constexpr std::uint32_t crc32(kira::View<std::uint8_t> buf)
  {
      std::uint32_t crc{0xFFFFFFFFu};
      for(const std::uint8_t b : buf)
      {
          crc ^= static_cast<std::uint32_t>(b);
          for(std::int32_t bit = 0; bit < 8; ++bit)
          {
              crc = (crc & 1u) != 0u ? (crc >> 1) ^ 0xEDB88320u : crc >> 1;
          }
      }
      return ~crc;
  }

  [[nodiscard]] constexpr std::array<std::uint8_t, 8> written()
  {
      std::array<std::uint8_t, 8> b{};
      kira::writeU32Le(kira::mutView(b), 0, 0x04030201u);
      kira::writeU16Le(kira::mutView(b), 4, std::uint16_t{0x0605});
      kira::at(b, 6) = 7;
      kira::mutView(b)[7] = 8;
      return b;
  }

  // The big-endian twins (D53): written most significant byte first.
  [[nodiscard]] constexpr std::array<std::uint8_t, 22> writtenBe()
  {
      std::array<std::uint8_t, 22> b{};
      kira::writeU32Be(kira::mutView(b), 0, 0x01020304u);
      kira::writeU16Be(kira::mutView(b), 4, std::uint16_t{0x0506});
      kira::writeU64Be(kira::mutView(b), 6, 0x0708090A0B0C0D0Eu);
      kira::writeF64Be(kira::mutView(b), 14, 1.5);
      return b;
  }

  template<kira::Size N>
  [[nodiscard]] constexpr bool same(kira::View<kira::Char> v, const kira::Char (&want)[N])
  {
      return v == kira::lit(want);
  }

  [[nodiscard]] constexpr bool strBufWorks()
  {
      kira::StrBuf<16> b;
      b.add(kira::lit("OK servo="));
      b.addInt(-1541);
      const bool first = same(b.view(), "OK servo=-1541") && !b.truncated();
      b.clear();
      b.addFixed(2.5, 3);
      b.addChar(' ');
      b.addUInt(18446744073709551615u);
      return first && same(b.view(), "2.500 1844674407") && b.truncated() && b.size() == 16;
  }

  // addFixed rounds as printf's %.*f (D50), in a constant expression too.
  template<kira::Size N>
  [[nodiscard]] constexpr bool fixedIs(double v, std::int32_t places, const kira::Char (&want)[N])
  {
      kira::StrBuf<40> b;
      b.addFixed(v, places);
      return same(b.view(), want);
  }

  static_assert(kira::as<std::int32_t>(3.99f) == 3);
  static_assert(kira::as<std::int32_t>(-3.99) == -3);
  static_assert(kira::as<std::int32_t>(1e20f) == (std::numeric_limits<std::int32_t>::max)());
  static_assert(kira::as<std::int32_t>(-1e20) == std::numeric_limits<std::int32_t>::lowest());
  static_assert(kira::as<std::uint8_t>(-5.0f) == 0);
  static_assert(kira::as<std::uint8_t>(300.0) == 255);
  static_assert(kira::as<std::int32_t>(std::numeric_limits<double>::quiet_NaN()) == 0);
  static_assert(kira::as<std::uint64_t>(std::numeric_limits<float>::infinity()) == (std::numeric_limits<std::uint64_t>::max)());
  static_assert(kira::as<std::uint8_t>(300) == 44);
  static_assert(kira::as<std::int8_t>(200) == -56);
  static_assert(kira::as<std::int32_t>(std::uint32_t{0xFFFFFFFFu}) == -1);
  static_assert(kira::as<float>(16777217) == 16777216.0f);
  static_assert(kira::bitCast<std::uint32_t>(1.0f) == 0x3F800000u);
  static_assert(kira::div(7, 2) == 3 && kira::div(-7, 2) == -3 && kira::mod(-7, 2) == -1);
  static_assert(kira::mod(std::numeric_limits<std::int32_t>::lowest(), std::int32_t{-1}) == 0);
  static_assert(kira::div(std::uint8_t{200}, std::uint8_t{7}) == 28);
  static_assert(kira::shl(1u, 31) == 0x80000000u && kira::shr(0x80000000u, 31) == 1u);
  static_assert(kira::shl(std::uint8_t{0x81}, 1) == 0x02);
  static_assert(kira::shr(-8, 1) == -4);
  static_assert(kira::shl(std::int64_t{1}, kira::Size{40}) == std::int64_t{1099511627776});
  static_assert(kira::ord('\xFF') == 255);
  static_assert(kira::abs(std::int32_t{-5}) == 5 && kira::abs(std::int32_t{5}) == 5);
  static_assert(kira::abs(std::numeric_limits<std::int32_t>::lowest()) == std::numeric_limits<std::int32_t>::lowest());
  static_assert(kira::abs(std::int8_t{-128}) == std::int8_t{-128} && kira::abs(std::int16_t{-300}) == std::int16_t{300});
  static_assert(std::is_same_v<decltype(kira::abs(std::int8_t{-1})), std::int8_t>);
  static_assert(std::is_same_v<decltype(kira::abs(std::uint16_t{1})), std::uint16_t>);
  static_assert(kira::abs(std::uint32_t{0xFFFFFFFFu}) == 0xFFFFFFFFu && kira::abs(kira::Size{7}) == kira::Size{7});
  static_assert(kira::abs(std::uint64_t{18446744073709551615u}) == std::uint64_t{18446744073709551615u});
  static_assert(kira::abs(-2.5f) == 2.5f && kira::abs(-2.5) == 2.5);
  static_assert(kira::bitCast<std::uint64_t>(kira::abs(-0.0)) == 0u, "abs(-0.0) is +0.0");
  static_assert(kira::bitCast<std::uint32_t>(kira::abs(-0.0f)) == 0u, "abs(-0.0f) is +0.0f");
  static_assert(kira::hashCode(std::int32_t{-7}) == -7 && kira::hashCode(std::uint8_t{200}) == 200);
  static_assert(kira::hashCode(std::uint64_t{18446744073709551615u}) == -1, "a UInt64 above INT64_MAX wraps");
  static_assert(kira::hashCode(true) == 1 && kira::hashCode(false) == 0);
  static_assert(kira::hashCode('\xFF') == 255, "a Char hashes by its unsigned code unit");
  static_assert(kira::hashCode(1.0) == std::int64_t{0x3FF0000000000000} && kira::hashCode(1.0f) == kira::hashCode(1.0));
  static_assert(crc32(CHECK_TEXT) == 0xCBF43926u, "the CRC-32 check value, through View and constexpr");
  static_assert(kira::view(CHECK_TEXT).from(7).size() == 2 && kira::view(CHECK_TEXT).slice(2, 3)[0] == '3');
  static_assert(kira::lit("abc").size() == 3 && kira::lit("abc") == kira::lit("abc") && kira::lit("abc") != kira::lit("abd"));
  static_assert(kira::readU32Le(written(), 0) == 0x04030201u && kira::readU16Le(written(), 4) == 0x0605);
  static_assert(written()[6] == 7 && written()[7] == 8);
  static_assert(kira::readU32Be(written(), 0) == 0x01020304u && kira::readU16Be(written(), 4) == 0x0506 &&
                    kira::readU64Be(written(), 0) == 0x0102030405060708u,
                "big-endian reads: the same bytes, the other order");
  static_assert(writtenBe()[0] == 1 && writtenBe()[3] == 4 && writtenBe()[4] == 5 && writtenBe()[5] == 6 &&
                    writtenBe()[6] == 7 && writtenBe()[13] == 0x0E && writtenBe()[14] == 0x3F && writtenBe()[15] == 0xF8,
                "big-endian writes, the most significant byte first");
  static_assert(kira::readU64Be(writtenBe(), 6) == 0x0708090A0B0C0D0Eu && kira::readF64Be(writtenBe(), 14) == 1.5 &&
                    kira::readU32Le(writtenBe(), 0) == 0x04030201u && kira::readF32Be(writtenBe(), 14) == 1.9375f,
                "big-endian round trips");
  static_assert(kira::parseInt64(kira::lit("-9223372036854775808")).value() == std::numeric_limits<std::int64_t>::lowest());
  static_assert(!kira::parseInt64(kira::lit("9223372036854775808")).has_value());
  static_assert(strBufWorks());
  static_assert(fixedIs(0.25, 1, "0.2") && fixedIs(-0.25, 1, "-0.2") && fixedIs(2.5, 0, "2") && fixedIs(3.5, 0, "4"),
                "addFixed: an exact half goes to even");
  static_assert(fixedIs(0.15, 1, "0.1") && fixedIs(0.35, 1, "0.3") && fixedIs(2.675, 2, "2.67") && fixedIs(0.45, 1, "0.5"),
                "addFixed: a decimal half is the double's exact value");
  static_assert(fixedIs(-0.0, 1, "-0.0") && fixedIs(5e-324, 9, "0.000000000") && fixedIs(0.0000000005, 9, "0.000000001"),
                "addFixed: a negative zero, the least subnormal, and 5e-10 just above its half");
  static_assert(fixedIs(4503599627370497.0, 0, "4503599627370497") && fixedIs(1.8e18, 1, "1800000000000000000.0"),
                "addFixed: the integers a double holds, below the saturation");
  static_assert(kira::enumOf<Mood>(5).value() == Mood::MOOD_BUSY && kira::enumOf<Mood>(-2).value() == Mood::MOOD_LOST);
  static_assert(!kira::enumOf<Mood>(std::uint64_t{65535}).has_value() && !kira::enumOf<Mood>(1).has_value());
  static_assert(kira::unwrapOr(kira::Maybe<std::int64_t>(kira::none), 4) == 4);
  static_assert(kira::unwrap(kira::Maybe<std::int32_t>(9)) == 9);
  static_assert(kira::Tuple2<std::int32_t, bool>{.first = 1, .second = true} == kira::Tuple2<std::int32_t, bool>{1, true});
  static_assert(std::is_same_v<kira::Maybe<std::int32_t>, std::optional<std::int32_t>>);
  static_assert(std::is_same_v<kira::Maybe<kira::Maybe<std::int32_t>>, kira::Nested<std::optional<std::int32_t>>>);
  static_assert(level::fromInside() == 7 + 4 + 2);
  static_assert(kira::Callable<std::int32_t (*)(std::int32_t), std::int32_t, std::int32_t>);
  static_assert(!kira::Callable<std::int32_t (*)(std::int32_t), std::int32_t, kira::View<std::uint8_t>>);
  static_assert(kira::Callable<void (*)(std::int32_t), void, std::int16_t>);
  static_assert(kira::checked, "the checks are on unless KIRA_UNCHECKED is set");
}

// ---- must not compile: a failing check under static_assert -----------------
#if defined(KIRA_RT_NEGATIVE)
#if KIRA_RT_NEGATIVE == 1
static_assert(kira::div(1, 0) == 0, "division by zero in a constant expression");
#elif KIRA_RT_NEGATIVE == 2
static_assert(kira::View<std::int32_t>().from(1).size() == 0, "a slice past the end");
#elif KIRA_RT_NEGATIVE == 3
static_assert(kira::at(std::array<std::int32_t, 2>{1, 2}, 2) == 0, "an index past the end");
#elif KIRA_RT_NEGATIVE == 4
static_assert(kira::unwrap(std::optional<std::int32_t>()) == 0, "unwrap of none");
#elif KIRA_RT_NEGATIVE == 5
static_assert(kira::shl(1, 32) == 0, "a shift count of the width");
#elif KIRA_RT_NEGATIVE == 6
static_assert(kira::div(std::numeric_limits<std::int32_t>::lowest(), std::int32_t{-1}) == 0, "lowest / -1");
#endif
#endif

// ---- run time -----------------------------------------------------------------
namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(!ok)
      {
          ++failures;
      }
#if KIRA_PROFILE_HOSTED
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
      }
#else
      static_cast<void>(what);
#endif
  }

  // Not constant-folded: the checks below run the runtime's code, not the
  // compiler's.
  template<class T>
  [[nodiscard]] T opaque(T v)
  {
      volatile T keep = v;
      return keep;
  }

  void testCore()
  {
      const float nan = opaque(std::numeric_limits<float>::quiet_NaN());
      check(kira::as<std::int32_t>(nan) == 0, "as: NaN to Int32 is 0");
      check(kira::as<std::int64_t>(opaque(1e30)) == (std::numeric_limits<std::int64_t>::max)(), "as: 1e30 saturates Int64");
      check(kira::as<std::int16_t>(opaque(-1e9f)) == std::numeric_limits<std::int16_t>::lowest(), "as: -1e9 saturates Int16");
      check(kira::as<std::uint32_t>(opaque(-0.5)) == 0u, "as: -0.5 to UInt32 is 0");
      check(kira::as<std::int32_t>(opaque(2147483520.0f)) == 2147483520, "as: the largest float below 2^31 converts exactly");
      check(kira::as<std::uint8_t>(opaque(std::int32_t{-1})) == 255, "as: Int32 -1 wraps to UInt8 255");
      check(kira::as<std::int32_t>(opaque(std::int64_t{0x100000005})) == 5, "as: Int64 wraps to Int32");

      check(kira::div(opaque(-7), 2) == -3 && kira::mod(opaque(-7), 2) == -1, "div and mod truncate toward zero");
      check(kira::mod(opaque(std::numeric_limits<std::int32_t>::lowest()), opaque(std::int32_t{-1})) == 0, "lowest % -1 is 0");
      check(kira::shl(opaque(std::uint16_t{0x8001}), 1) == 0x0002, "shl wraps a UInt16");
      check(kira::shr(opaque(std::int32_t{-16}), std::uint8_t{2}) == -4, "shr keeps the sign");
      check(kira::abs(opaque(std::int64_t{-9})) == 9 && kira::abs(opaque(std::numeric_limits<std::int64_t>::lowest())) == std::numeric_limits<std::int64_t>::lowest(),
            "abs: Int64, and the lowest wraps to itself");
      check(kira::abs(opaque(-0.0)) == 0.0 && !(kira::bitCast<std::uint64_t>(kira::abs(opaque(-0.0))) >> 63),
            "abs: -0.0 is +0.0");
      const double dnan = opaque(std::numeric_limits<double>::quiet_NaN());
      check(!(kira::abs(-dnan) == kira::abs(-dnan)), "abs: a NaN stays a NaN");
      check(kira::hashCode(opaque(std::int16_t{-2})) == -2 && kira::hashCode(opaque('A')) == 65, "hashCode: Int16 by value, Char by code unit");

      std::array<std::uint8_t, 6> bytes{1, 2, 3, 4, 5, 6};
      const kira::View<std::uint8_t> all = bytes;
      check(all.size() == 6 && all[5] == 6 && !all.isEmpty(), "View over an Arr<T, N>");
      check(all.from(2).size() == 4 && all.from(2)[0] == 3 && all.from(6).isEmpty(), "View.from");
      check(all.slice(1, 3) == kira::View<std::uint8_t>(bytes.data() + 1, 3), "View.slice and ==");
      const kira::MutView<std::uint8_t> m = kira::mutView(bytes);
      m.from(4)[1] = 60;
      kira::at(bytes, 0) = 10;
      check(bytes[5] == 60 && bytes[0] == 10 && kira::at(all, 5) == 60, "MutView and at write through");
      kira::Size sum = 0;
      for(const std::uint8_t b : all)
      {
          sum += b;
      }
      check(sum == 10 + 2 + 3 + 4 + 5 + 60, "a View iterates");
      check(kira::MutView<std::uint8_t>(m) == m && kira::View<std::uint8_t>(m) == all, "MutView converts to View");

      std::array<std::uint8_t, 16> raw{};
      kira::writeU64Le(kira::mutView(raw), 0, 0x0102030405060708u);
      kira::writeF32Le(kira::mutView(raw), 8, -2.5f);
      kira::writeF64Le(kira::mutView(raw), 8, 0.1);
      check(raw[0] == 8 && raw[7] == 1 && kira::readU64Le(raw, 0) == 0x0102030405060708u, "readU64Le / writeU64Le");
      check(kira::readF64Le(raw, 8) == 0.1, "readF64Le / writeF64Le round-trip");
      kira::writeF32Le(kira::mutView(raw), 12, -2.5f);
      check(kira::readF32Le(raw, 12) == -2.5f, "readF32Le / writeF32Le round-trip");

      check(kira::unwrap(kira::parseInt64(kira::lit("+42"))) == 42, "parseInt64: a leading +");
      check(!kira::parseInt64(kira::lit("12abc")).has_value(), "parseInt64: a trailing tail is refused");
      check(!kira::parseInt64(kira::lit(" 12")).has_value(), "parseInt64: leading space is refused");
      check(!kira::parseInt64(kira::lit("")).has_value() && !kira::parseInt64(kira::lit("-")).has_value(),
            "parseInt64: nothing, or a sign alone, is refused");
      check(kira::unwrap(kira::parseInt64(kira::lit("9223372036854775807"))) == (std::numeric_limits<std::int64_t>::max)(),
            "parseInt64: Int64 max");
      check(!kira::parseInt64(kira::lit("-9223372036854775809")).has_value(), "parseInt64: past Int64 min is none");

      kira::StrBuf<8> b;
      b.add(kira::lit("ESC "));
      b.addInt(opaque(std::int64_t{1541}));
      check(b.view() == kira::lit("ESC 1541") && !b.truncated() && b.c_str()[8] == '\0', "StrBuf fills to capacity");
      b.addChar('!');
      check(b.size() == 8 && b.truncated(), "StrBuf truncates, and says so");
      b.clear();
      b.addFixed(opaque(-0.0005), 3);
      check(b.view() == kira::lit("-0.001"), "StrBuf.addFixed: -0.0005 is a little past its half");
      b.clear();
      b.addFixed(opaque(0.125), 2);
      check(b.view() == kira::lit("0.12"), "StrBuf.addFixed: an exact half goes to even");
      b.clear();
      b.addFixed(opaque(-0.0), 1);
      check(b.view() == kira::lit("-0.0"), "StrBuf.addFixed: -0.0 keeps its sign");
      b.clear();
      b.addFixed(opaque(static_cast<double>(nan)), 2);
      check(b.view() == kira::lit("nan"), "StrBuf.addFixed of NaN");
      b.clear();
      b.addFixed(-opaque(kira::bitCast<double>(std::uint64_t{0x7FF8000000000000u})), 2);
      check(b.view() == kira::lit("nan"), "StrBuf.addFixed of a negative NaN");
      b.clear();
      b.addInt(std::numeric_limits<std::int64_t>::lowest());
      check(b.view() == kira::lit("-9223372") && b.truncated(), "StrBuf.addInt of Int64 min, truncated");

      const kira::Maybe<std::int32_t> none = kira::none;
      const kira::Maybe<std::int32_t> some = opaque(3);
      check(!kira::isSome(none) && kira::isSome(some), "Maybe: none and some");
      check(kira::unwrap(some) == 3 && kira::unwrapOr(none, 8) == 8, "unwrap and unwrapOr");
      // A Maybe of a Maybe keeps both levels, and kira::none is the outer empty one, however
      // it arrives (round 6, W2.4): a plain optional<optional<T>> took none as Some(None).
      kira::Maybe<kira::Maybe<std::int32_t>> nested = none;
      const bool someNone = kira::isSome(nested) && !kira::isSome(kira::unwrap(nested));
      nested = kira::none;
      const kira::Maybe<kira::Maybe<std::int32_t>> outer = kira::none;
      check(someNone && !kira::isSome(nested) && !kira::isSome(outer) && nested == outer, "Maybe<Maybe<T>>: Some(None) is not None");
      nested = some;
      check(kira::unwrap(kira::unwrap(nested)) == 3 && nested != outer, "Maybe<Maybe<T>>: Some(Some)");
      check(kira::unwrap(kira::enumOf<Mood>(opaque(std::int32_t{0}))) == Mood::MOOD_CALM, "enumOf finds an entry");
      check(!kira::enumOf<Mood>(opaque(std::int64_t{70000})).has_value(), "enumOf: no entry is none");
      const kira::Tuple3<std::int32_t, float, bool> t{.first = 1, .second = 2.5f, .third = true};
      check(t.second == 2.5f && t == kira::Tuple3<std::int32_t, float, bool>{1, 2.5f, true}, "Tuple3");
  }
}

#if KIRA_PROFILE_HOSTED
namespace
{
  [[nodiscard]] bool same(const kira::Str& got, const char* want)
  {
      if(got == want)
      {
          return true;
      }
      std::printf("        got  \"%s\"\n        want \"%s\"\n", got.c_str(), want);
      return false;
  }

  // What trace(x) writes, without writing it.
  template<class T>
  [[nodiscard]] kira::Str traced(const T& v)
  {
      kira::impl_::Piece p;
      kira::impl_::traced(p, v);
      return kira::Str(p.ptr, p.len);
  }

  // trace's float format against the C library's own %g, in the C locale.
  [[nodiscard]] bool tracesLikePrintf(double v)
  {
      char want[64];
      std::snprintf(want, sizeof(want), "%g", v);
      return traced(v) == want;
  }

  struct Pet
  {
      kira::Str name;
      [[nodiscard]] std::int32_t legs() const
      {
          return 4;
      }
  };

  class Behaviour
  {
  public:
      virtual ~Behaviour() = default;
      [[nodiscard]] virtual kira::Str id() const = 0;
  };
  class Stop final : public Behaviour
  {
  public:
      [[nodiscard]] kira::Str id() const override
      {
          return "stop";
      }
  };

  template<class T>
  [[nodiscard]] std::int32_t legsOf(const T& v)
  {
      return kira::deref(v).legs();
  }
  template<class T>
  [[nodiscard]] std::int32_t legsOfMut(T& v)
  {
      return kira::deref(v).legs();
  }

  void testText()
  {
      check(same(kira::text(opaque(-1541)), "-1541"), "text(Int32)");
      check(same(kira::text(std::numeric_limits<std::int64_t>::lowest()), "-9223372036854775808"), "text(Int64 min)");
      check(same(kira::text((std::numeric_limits<std::uint64_t>::max)()), "18446744073709551615"), "text(UInt64 max)");
      check(same(kira::text(std::uint8_t{200}), "200") && same(kira::text(std::int8_t{-5}), "-5"),
            "text(UInt8) and text(Int8) are numbers, not characters");
      check(same(kira::text('x'), "x"), "text(Char) is the character");
      check(same(kira::text(true), "true") && same(kira::text(false), "false"), "text(Bool) is true/false");
      check(same(kira::text(opaque(0.1f)), "0.1") && same(kira::text(opaque(0.1)), "0.1"), "text: shortest round-trip");
      check(same(kira::text(opaque(1.0f / 3.0f)), "0.33333334"), "text(Float32) is the float's own shortest");
      check(same(kira::text(opaque(100.0)), "100") && same(kira::text(opaque(1e21)), "1e+21"), "text: 100 and 1e+21");
      check(same(kira::text(opaque(-0.0)), "-0"), "text(-0.0)");
      check(same(kira::text(Mood::MOOD_LOST), "MOOD_LOST"), "text(enum) is the entry's name");
      check(same(kira::text(kira::lit("view")), "view"), "text(View<Char>)");
      check(same(kira::cat("STEER ", 0.25f, " n=", 3, ' ', true, " ", Mood::MOOD_BUSY, kira::Str(" s"), std::uint8_t{7}),
                 "STEER 0.25 n=3 true MOOD_BUSY s7"),
            "cat of every piece kind");
      check(same(kira::cat(), ""), "cat of nothing");
      const double negNan = -opaque(kira::bitCast<double>(std::uint64_t{0x7FF8000000000000u}));
      check(same(kira::text(negNan), "nan") && same(kira::text(static_cast<float>(negNan)), "nan") && same(kira::cat(negNan), "nan"),
            "text: any NaN is nan (D50)");
      check(same(kira::text(opaque(1e5)), "1e+05") && same(kira::text(opaque(0.001)), "0.001") &&
                same(kira::text(opaque(1.2345678901234568e20)), "123456789012345683968"),
            "text: scientific when shorter, fixed on a tie, a large integer's exact digits");

      check(same(kira::fixed(opaque(0.25), 1), "0.2") && same(kira::fixed(opaque(2.5), 0), "2") && same(kira::fixed(opaque(3.5), 0), "4"),
            "fixed: an exact half goes to even");
      check(same(kira::fixed(opaque(0.15), 1), "0.1") && same(kira::fixed(opaque(0.35), 1), "0.3") &&
                same(kira::fixed(opaque(2.675), 2), "2.67") && same(kira::fixed(opaque(0.45), 1), "0.5"),
            "fixed: a decimal half is the double's exact value");
      check(same(kira::fixed(opaque(-0.0), 1), "-0.0") && same(kira::fixed(negNan, 3), "nan") &&
                same(kira::fixed(-negNan, 3), "nan"),
            "fixed: -0.0 keeps its sign, and any NaN is nan");
      check(same(kira::fixed(opaque(std::numeric_limits<double>::infinity()), 2), "inf") &&
                same(kira::fixed(-opaque(std::numeric_limits<double>::infinity()), 2), "-inf"),
            "fixed: the infinities");
      check(same(kira::fixed(opaque(1e21), 1), "1000000000000000000000.0") && same(kira::fixed(opaque(5e-324), 9), "0.000000000"),
            "fixed: 1e21 in full, and the least subnormal");
      check(same(kira::fixed(opaque(1.5), -3), "2") && same(kira::fixed(opaque(0.1), 17), "0.100000000"),
            "fixed: places clamped to 0..9, as addFixed's");
      check(same(kira::fixed(opaque(0.1f), 9), "0.100000001"), "fixed of a Float32 is the float's exact value");

      // addFixed and fixed are one rounding: every kind of value, every places, below the saturation.
      std::uint64_t seed = 0x9E3779B97F4A7C15u;
      bool oneRounding = true;
      for(int i = 0; i < 20000; ++i)
      {
          seed ^= seed << 13;
          seed ^= seed >> 7;
          seed ^= seed << 17;
          double scale = 1.0;
          for(std::uint64_t j = (seed >> 40) % 10u; j > 0; --j)
          {
              scale *= i % 2 == 0 ? 10.0 : 2.0;
          }
          const double v = i % 3 == 0 ? kira::bitCast<double>(seed) : static_cast<double>(seed % 100000000u) / scale;
          for(std::int32_t p = 0; p <= 9; ++p)
          {
              kira::StrBuf<48> fb;
              fb.addFixed(v, p);
              const double mag = v < 0.0 ? -v : v;
              if(mag == mag && mag < 1e9)
              {
                  oneRounding = oneRounding && kira::Str(fb.view().ptr, fb.view().len) == kira::fixed(v, p);
              }
          }
      }
      check(oneRounding, "StrBuf.addFixed and fixed agree (D50)");

      check(same(kira::hex(opaque(255)), "ff") && same(kira::hex(opaque(-255)), "-ff") && same(kira::hex(opaque(0)), "0"),
            "hex: lowercase, no prefix, a negative one signed (D51)");
      check(same(kira::hex(std::numeric_limits<std::int8_t>::lowest()), "-80") && same(kira::hex(std::uint8_t{255}), "ff") &&
                same(kira::hex(std::numeric_limits<std::int64_t>::lowest()), "-8000000000000000"),
            "hex: Int8 and Int64 min, UInt8");
      check(same(kira::hex((std::numeric_limits<std::uint64_t>::max)()), "ffffffffffffffff") &&
                same(kira::hex(kira::as<std::uint16_t>(opaque(std::int16_t{-1}))), "ffff"),
            "hex: UInt64 max, and an Int16's bits through UInt16");

      check(same(traced(2.5f), "2.5") && same(traced(1e20), "1e+20") && same(traced(0.1f), "0.1"), "trace: %g floats");
      check(same(traced(1234567.0), "1.23457e+06") && same(traced(0.0001234), "0.0001234"), "trace: %g's six digits");
      check(same(traced(true), "1") && same(traced(false), "0"), "trace: Bool as 1/0");
      check(same(traced(std::int64_t{-9000000000}), "-9000000000"), "trace: Int64 in decimal");
      check(same(traced(Mood::MOOD_BUSY), "5"), "trace: an enum as its base value");
      bool likePrintf = true;
      const double samples[] = {0.0, 1.0, -2.5, 0.1, 1e-5, 123456.0, 1234567.0, 3.14159265358979, 1e300, -7.25e-12, 99999.95};
      for(const double v : samples)
      {
          likePrintf = likePrintf && tracesLikePrintf(v);
      }
      check(likePrintf, "trace's floats are the C library's %g, in the C locale");
  }

  void testStr()
  {
      const kira::Str s = "OK drive servo=1500";
      check(kira::str::length(s) == 19 && kira::str::size(s) == 19 && !kira::str::isEmpty(s), "length and size");
      check(kira::str::at(s, 3) == 'd' && same(kira::str::charAt(s, 0), "O"), "at and charAt");
      check(same(kira::str::substring(s, 3, 8), "drive") && same(kira::str::substring(s, 19, 19), ""), "substring");
      check(kira::str::contains(s, "servo") && kira::str::startsWith(s, "OK ") && kira::str::endsWith(s, "1500"),
            "contains, startsWith, endsWith");
      check(!kira::str::startsWith("O", "OK") && !kira::str::endsWith("0", "00"), "a prefix longer than the text");
      check(kira::unwrap(kira::str::find(s, "=")) == 14 && !kira::isSome(kira::str::find(s, "#")), "find");
      const kira::List<kira::Str> parts = kira::str::split("a,,b", ",");
      check(parts.size() == 3 && parts[1].empty() && parts[2] == "b", "split keeps empty pieces");
      check(kira::str::split("abc", "").size() == 1, "split on an empty delimiter");
      check(same(kira::str::trim(" \t x y\r\n"), "x y") && same(kira::str::trim("   "), ""), "trim");
      check(same(kira::str::toLower("MiXeD 1"), "mixed 1") && same(kira::str::toUpper("MiXeD"), "MIXED"), "toLower, toUpper");
      check(same(kira::str::padStart("7", 3, '0'), "007") && same(kira::str::padStart("1234", 3, '0'), "1234"), "padStart");
      check(same(kira::str::padEnd("7", 3, '.'), "7..") && same(kira::str::padEnd("1234", 3, '.'), "1234"), "padEnd");
      check(same(kira::str::trimStart(" \t x y\r\n"), "x y\r\n") && same(kira::str::trimEnd(" \t x y\r\n"), " \t x y") &&
                same(kira::str::trimStart(" \n"), "") && same(kira::str::trimEnd(""), "") && same(kira::str::trimEnd("\vx\f"), "\vx\f"),
            "trimStart and trimEnd take trim's four");
      const kira::List<kira::Str> words = kira::str::splitWhitespace("\t a  bc\r\n\v\fd \x1c");
      check(words.size() == 4 && words[0] == "a" && words[1] == "bc" && words[2] == "d" && words[3] == "\x1c" &&
                kira::str::splitWhitespace(" \t\n").empty() && kira::str::splitWhitespace("").empty(),
            "splitWhitespace: runs of C's six, no empty pieces");
      check(same(kira::str::replace("a.b.c", ".", "--"), "a--b--c") && same(kira::str::replace("aaa", "aa", "b"), "ba") &&
                same(kira::str::replace("abc", "x", "y"), "abc") && same(kira::str::replace("abc", "", "-"), "-a-b-c-") &&
                same(kira::str::replace("", "", "-"), "-") && same(kira::str::replace("h\xc3\xa9", "", "|"), "|h|\xc3\xa9|"),
            "replace: every occurrence, and an empty one between code points");
      check(kira::unwrap(kira::str::toInt64("ff", 16)) == 255 && kira::unwrap(kira::str::toInt64("-Zz", 36)) == -1295 &&
                kira::unwrap(kira::str::toInt64("+101", 2)) == 5 &&
                kira::unwrap(kira::str::toInt64("-1000000000000000000000000000000000000000000000000000000000000000", 2)) ==
                    std::numeric_limits<std::int64_t>::lowest() &&
                kira::unwrap(kira::str::toInt64("7fffffffffffffff", 16)) == (std::numeric_limits<std::int64_t>::max)(),
            "toInt64 with a radix");
      check(!kira::isSome(kira::str::toInt64("8000000000000000", 16)) && !kira::isSome(kira::str::toInt64("12", 2)) &&
                !kira::isSome(kira::str::toInt64("0x1f", 16)) && !kira::isSome(kira::str::toInt64("-", 10)) &&
                !kira::isSome(kira::str::toInt64("", 10)) && !kira::isSome(kira::str::toInt64("1_0", 10)) &&
                !kira::isSome(kira::str::toInt64(" 1", 10)),
            "toInt64 with a radix: none on overflow and on any other character");
      check(same(kira::list::joinToString(kira::List<kira::Str>{"a", "", "b"}, ", "), "a, , b") &&
                same(kira::list::joinToString(kira::List<kira::Str>{"x"}, "-"), "x") &&
                same(kira::list::joinToString(kira::List<kira::Str>{}, "-"), ""),
            "joinToString");
      check(kira::str::hashCode("ab") == (5381 * 33 + 'a') * 33 + 'b' && kira::str::equals("a", "a"), "hashCode is djb2");
      check(kira::str::view(s).from(3).slice(0, 5) == kira::lit("drive"), "view");
      check(kira::str::bytes("h\xc3\xa9 \xe2\x9c\x93") == kira::List<std::uint8_t>{0x68, 0xC3, 0xA9, 0x20, 0xE2, 0x9C, 0x93} &&
                kira::str::bytes("").empty() && kira::str::bytes(s).size() == kira::str::length(s),
            "bytes: the UTF-8 a Str holds");
      const auto ofText = [](const char* t) {
          return kira::str::of(kira::View<std::uint8_t>(reinterpret_cast<const std::uint8_t*>(t), std::strlen(t)));
      };
      check(ofText("ok h\xc3\xa9 \xe2\x9c\x93 \xf0\x9f\x9a\x97") == "ok h\xc3\xa9 \xe2\x9c\x93 \xf0\x9f\x9a\x97" && ofText("").empty() &&
                kira::str::of(kira::str::bytes(s)) == s,
            "Str.of keeps well-formed UTF-8");
      check(ofText("a\x80" "b\xff") == "a\xef\xbf\xbd" "b\xef\xbf\xbd" && ofText("\xe2\x82") == "\xef\xbf\xbd" &&
                ofText("\xf0\x9f\x9a" "x") == "\xef\xbf\xbd" "x",
            "Str.of: a stray byte and a cut sequence are one U+FFFD each");
      check(ofText("\xc0\xaf") == "\xef\xbf\xbd\xef\xbf\xbd" && ofText("\xe0\x80\xaf") == "\xef\xbf\xbd\xef\xbf\xbd\xef\xbf\xbd" &&
                ofText("\xed\xa0\x80") == "\xef\xbf\xbd\xef\xbf\xbd\xef\xbf\xbd" &&
                ofText("\xf4\x90\x80\x80") == "\xef\xbf\xbd\xef\xbf\xbd\xef\xbf\xbd\xef\xbf\xbd",
            "Str.of: an overlong form, a surrogate and a code point past U+10FFFF, each byte one U+FFFD");

      check(kira::unwrap(kira::str::toInt64("-2859")) == -2859, "toInt64");
      check(!kira::isSome(kira::str::toInt64("1541abc")) && !kira::isSome(kira::str::toInt64("")) &&
                !kira::isSome(kira::str::toInt64("1.5")) && !kira::isSome(kira::str::toInt64("0x10")),
            "toInt64 is strict decimal");
      check(!kira::isSome(kira::str::toInt64("99999999999999999999")), "toInt64: overflow is none");
      check(kira::unwrap(kira::str::toFloat64("2.50")) == 2.5 && kira::unwrap(kira::str::toFloat64("+2.5")) == 2.5,
            "toFloat64, and a leading +");
      check(kira::unwrap(kira::str::toFloat64("-0.125")) == -0.125 && kira::unwrap(kira::str::toFloat64("1e3")) == 1000.0,
            "toFloat64: sign and exponent");
      check(!kira::isSome(kira::str::toFloat64("")) && !kira::isSome(kira::str::toFloat64("+")) &&
                !kira::isSome(kira::str::toFloat64("2.5x")) && !kira::isSome(kira::str::toFloat64(" 2.5")) &&
                !kira::isSome(kira::str::toFloat64("+-2")) && !kira::isSome(kira::str::toFloat64("2,5")),
            "toFloat64 refuses empty text, trailing garbage and a comma");
      check(!kira::isSome(kira::str::toFloat64("1e999")), "toFloat64: out of range is none");
      constexpr const char* LIDAR_IP = "192.168.1.62";
      check(kira::str::startsWith(LIDAR_IP, "192.") && kira::str::length(LIDAR_IP) == 12,
            "a literal Str constant converts to const Str&");
  }

  void testContainers()
  {
      kira::Map<kira::Str, std::int32_t> m;
      m.put("b", 2);
      m.put("a", 1);
      m.put("c", 3);
      m.put("b", 20);
      check(same(kira::cat(m.keys()[0], m.keys()[1], m.keys()[2]), "bac"), "Map keeps insertion order");
      check(kira::unwrap(m.get("b")) == 20 && !kira::isSome(m.get("z")) && m.size() == 3, "Map.put replaces in place");
      check(kira::unwrap(m.remove("b")) == 20 && !kira::isSome(m.remove("b")), "Map.remove gives the value once");
      m.put("b", 4);
      kira::Str order;
      for(const kira::Tuple2<kira::Str, std::int32_t>& e : m)
      {
          order += kira::cat(e.first, e.second);
      }
      check(same(order, "a1c3b4"), "a removed key comes back last");
      m["d"] += 5;
      m["a"] = 9;
      check(m.at("d") == 5 && m.at("a") == 9 && m.containsKey("d") && m.containsValue(9), "Map [] as a place");
      check(m.valuesArr().size() == 4 && m.entries()[3].first == "d", "valuesArr and entries");
      kira::Map<kira::Str, std::int32_t> n{{"d", 5}, {"b", 4}, {"a", 9}, {"c", 3}};
      check(m == n, "Map == ignores order");
      m.clear();
      check(m.isEmpty() && !(m == n), "Map.clear");

      kira::Set<Mood> s{Mood::MOOD_LOST, Mood::MOOD_CALM};
      check(s.add(Mood::MOOD_BUSY) && !s.add(Mood::MOOD_CALM) && s.size() == 3, "Set.add says whether it added");
      check(s.remove(Mood::MOOD_LOST) && !s.remove(Mood::MOOD_LOST) && s.toArr()[0] == Mood::MOOD_CALM, "Set.remove");
      check(s.contains(Mood::MOOD_BUSY) && !s.contains(Mood::MOOD_LOST), "Set.contains");

      kira::Stack<std::int32_t> st;
      st.push(1);
      st.push(2);
      check(kira::unwrap(st.peek()) == 2 && kira::unwrap(st.pop()) == 2 && kira::unwrap(st.pop()) == 1, "Stack is LIFO");
      check(!kira::isSome(st.pop()) && st.isEmpty(), "Stack.pop of an empty stack is none");
      kira::Queue<kira::Str> q;
      q.enqueue("x");
      q.enqueue("y");
      check(kira::unwrap(q.dequeue()) == "x" && q.size() == 1 && kira::unwrap(q.peek()) == "y", "Queue is FIFO");
      check(kira::isSome(q.dequeue()) && !kira::isSome(q.dequeue()), "Queue.dequeue of an empty queue is none");
      q.push("z");
      check(kira::unwrap(q.pop()) == "z" && !kira::isSome(q.pop()), "Queue.push and pop are enqueue and dequeue");
      kira::Deque<std::int32_t> d{1, 2, 3};
      check(kira::unwrap(kira::popFront(d)) == 1 && kira::unwrap(kira::popBack(d)) == 3 && kira::at(d, 0) == 2,
            "Deque popFront and popBack");
      d.clear();
      check(!kira::isSome(kira::popFront(d)) && !kira::isSome(kira::popBack(d)), "and none when empty");

      kira::List<std::int32_t> xs{4, 5};
      kira::list::add(xs, 6);
      kira::list::addAll(xs, kira::List<std::int32_t>{7});
      kira::list::set(xs, 0, 40);
      check(kira::list::size(xs) == 4 && kira::list::get(xs, 0) == 40 && kira::at(xs, 3) == 7, "List add, set, get");
      check(kira::list::removeAt(xs, 1) == 5 && xs.size() == 3 && kira::list::contains(xs, 6), "List.removeAt");
      kira::at(xs, 2) = 70;
      check(kira::list::clone(xs) == kira::List<std::int32_t>{40, 6, 70} && kira::view(xs).size() == 3, "List place and view");
      const kira::View<std::int32_t> fromList = xs;
      check(fromList[2] == 70 && kira::mutView(xs).size() == 3, "View from a List");
      const kira::List<std::int32_t> copied = kira::list::clone(kira::view(xs).from(1));
      kira::at(xs, 1) = 60;
      check(copied == kira::List<std::int32_t>{6, 70} && kira::list::clone(kira::View<std::int32_t>{}).empty() &&
                kira::list::clone(kira::view(CHECK_TEXT).slice(2, 3)) == kira::List<std::uint8_t>{'3', '4', '5'} &&
                kira::list::clone(kira::mutView(xs).slice(0, 2)) == kira::List<std::int32_t>{40, 60},
            "View.toList and MutView.toList copy the elements");
      kira::at(xs, 1) = 6;
      const std::array<std::int32_t, 3> fixed{1, 2, 3};
      static_assert(kira::list::contains(std::array<std::int32_t, 2>{1, 2}, 2), "Arr<T, N>.contains is constexpr");
      check(kira::list::contains(fixed, 2) && !kira::list::contains(fixed, 4), "Arr<T, N>.contains");
      check(kira::list::clone(fixed) == kira::List<std::int32_t>{1, 2, 3}, "Arr<T, N>.clone is a List");
      kira::List<bool> flags{true, false};
      kira::at(flags, 1) = true;
      check(kira::at(flags, 1) && kira::list::get(flags, 0), "List<Bool> places");
  }

  void testClasses()
  {
      kira::Maybe<kira::Rc<Behaviour>> none = kira::none;
      const kira::Maybe<kira::Rc<Behaviour>> stop = std::make_shared<Stop>();
      static_assert(std::is_same_v<kira::Maybe<kira::Rc<Behaviour>>, kira::MaybeRc<Behaviour>>);
      static_assert(std::is_base_of_v<std::shared_ptr<Behaviour>, kira::MaybeRc<Behaviour>>);
      static_assert(sizeof(kira::MaybeRc<Behaviour>) == sizeof(std::shared_ptr<Behaviour>));
      check(none == nullptr && !kira::isSome(none) && kira::isSome(stop), "Maybe<class> is a nullable Rc");
      check(kira::unwrap(stop)->id() == "stop" && kira::unwrapOr(none, stop)->id() == "stop", "unwrap, unwrapOr");
      check(kira::unwrapOr(none, std::make_shared<Stop>()) != nullptr, "unwrapOr takes a derived default");
      std::unique_ptr<Behaviour> owned = std::make_unique<Stop>();
      none = std::move(owned);
      check(none != nullptr && none->id() == "stop", "an Rc adopts a unique_ptr");

      // A Maybe of a Maybe<C> keeps both levels, spelled as the emitter spells it (round 6, W2.4,
      // the verifier's b10: Map<Str, Maybe<C>>.get of a key mapped to null was None, one shared_ptr).
      using MaybeB = kira::Maybe<kira::Rc<Behaviour>>;
      static_assert(std::is_same_v<kira::Maybe<MaybeB>, kira::Nested<kira::MaybeRc<Behaviour>>>);
      static_assert(std::is_same_v<kira::Maybe<kira::Maybe<MaybeB>>, kira::Nested<kira::Nested<kira::MaybeRc<Behaviour>>>>);
      const MaybeB nul = kira::none;
      kira::Map<kira::Str, MaybeB> byName{};
      byName["gone"] = kira::none;
      byName["stop"] = std::make_shared<Stop>();
      const kira::Maybe<MaybeB> mappedNull = byName.get("gone");
      const kira::Maybe<MaybeB> absent = byName.get("absent");
      check(kira::isSome(mappedNull) && !kira::isSome(kira::unwrap(mappedNull)) && !kira::isSome(absent),
            "Map<K, Maybe<C>>.get: a key mapped to null is Some(None), a missing key None");
      check(kira::unwrap(kira::unwrap(byName.get("stop")))->id() == "stop" && byName.containsValue(nul),
            "and Some(Some) reads through as the class");
      check(kira::isSome(byName.remove("gone")) && !kira::isSome(byName.remove("gone")), "Map<K, Maybe<C>>.remove of a null is Some(None)");
      kira::Stack<MaybeB> stack;
      stack.push(nul);
      check(kira::isSome(stack.peek()) && kira::isSome(stack.pop()) && !kira::isSome(stack.pop()), "Stack<Maybe<C>>.pop of a pushed null is Some(None)");
      kira::Queue<MaybeB> queue;
      queue.enqueue(nul);
      check(kira::isSome(queue.dequeue()) && !kira::isSome(queue.dequeue()), "Queue<Maybe<C>>.dequeue of a null is Some(None)");
      kira::Deque<MaybeB> deque{nul, stop};
      check(kira::isSome(kira::popFront(deque)) && kira::isSome(kira::popBack(deque)) && !kira::isSome(kira::popBack(deque)),
            "Deque<Maybe<C>> popFront of a null is Some(None)");
      kira::Maybe<MaybeB> slot = kira::unwrap(mappedNull);
      slot = kira::none;
      const kira::Maybe<MaybeB> empty = kira::none;
      check(!kira::isSome(slot) && slot == empty && mappedNull != empty, "Maybe<Maybe<C>> = none is the outer None");
      slot = nul;
      check(kira::isSome(slot) && slot == mappedNull, "Maybe<Maybe<C>> = a null Maybe<C> is Some(None)");
      slot = stop;
      check(kira::unwrap(kira::unwrap(slot))->id() == "stop", "Maybe<Maybe<C>> = a Maybe<C> is Some(Some)");
      slot = std::make_shared<Stop>();
      check(kira::isSome(slot) && kira::isSome(kira::unwrap(slot)), "Maybe<Maybe<C>> = a class value is Some(Some)");
      kira::List<MaybeB> items{nul};
      items.push_back(std::make_shared<Stop>());
      const kira::Rc<Behaviour> second = kira::unwrap(kira::at(items, 1));
      check(!kira::isSome(kira::at(items, 0)) && second == kira::at(items, 1) && second->id() == "stop",
            "a MaybeRc is the nullable Rc: it reads, compares and converts as one");
      const kira::Maybe<kira::Rc<Stop>> derived = std::make_shared<Stop>();
      const MaybeB upcast = derived;
      const kira::Weak<Behaviour> weakB = upcast;
      check(upcast == derived && kira::upgrade(weakB) == upcast && std::dynamic_pointer_cast<Stop>(upcast) == derived,
            "Maybe<Derived> upcasts to Maybe<Base>, and a Weak and a pointer cast take a MaybeRc");

      const Pet pet{.name = "mochi"};
      const kira::Rc<Pet> rc = std::make_shared<Pet>(Pet{.name = "rex"});
      kira::Rc<Pet> mutRc = rc;
      Pet mutPet = pet;
      check(legsOf(pet) == 4 && legsOf(rc) == 4 && legsOfMut(mutRc) == 4 && legsOfMut(mutPet) == 4,
            "deref is . for a value and -> for a class, const or not");

      kira::Weak<Pet> weak = rc;
      check(kira::isSome(kira::upgrade(weak)) && weak.lock()->name == "rex", "Weak.upgrade while it lives");
      {
          kira::Weak<Pet> gone;
          {
              const kira::Rc<Pet> brief = std::make_shared<Pet>(Pet{.name = "brief"});
              gone = brief;
          }
          check(!kira::isSome(kira::upgrade(gone)), "and none after");
      }

      const kira::Rc<kira::Box<std::int32_t>> counter = std::make_shared<kira::Box<std::int32_t>>(0);
      const kira::Fn<std::int32_t()> bump = [counter]() -> std::int32_t
      {
          counter->value = counter->value + 1;
          return counter->value;
      };
      static_cast<void>(bump());
      check(bump() == 2 && counter->value == 2, "Ref<T> is shared, mutable state");

      const kira::Result<std::int32_t, kira::Str> ok = kira::Result<std::int32_t, kira::Str>::success(5);
      const kira::Result<std::int32_t, kira::Str> bad = kira::Result<std::int32_t, kira::Str>::error("no");
      check(ok.isOk() && !ok.isErr() && ok.unwrap() == 5, "Result.success");
      check(bad.isErr() && kira::isErr(bad) && bad.unwrapErr() == "no" && kira::unwrap(ok) == 5, "Result.error");
      const kira::Result<kira::Str, kira::Str> blank;
      check(blank.isErr() && blank.unwrapErr().empty(), "a default Result is an error");
  }

  // kira::replace: a write over an old value whose drop runs a Kira `finally` stores first
  // and drops after (round 5b's w2-3 finding, the verifier's f2 and f3, as the emitter writes
  // them). Dropped's destructor stands in for the IMPURE finally: it reads the place it was
  // replaced from, then frees or rewrites it. A plain operator= runs it after `v` and before
  // `name` is written: it would read the old name, and f2 would write into the freed buffer.
  struct Dropped;
  struct Held
  {
      std::int32_t v = 0;
      kira::Rc<Dropped> r;
      kira::Str name;
      kira::List<std::int32_t> kids;
  };
  kira::List<Held> heldList;
  Held heldOne{.v = 0, .r = nullptr, .name = "zero", .kids = {}};
  kira::Map<std::int32_t, Held> heldMap;
  kira::Str seenName;
  struct Dropped
  {
      std::int32_t mode = 0;
      Dropped() = default;
      Dropped(const Dropped&) = delete;
      Dropped& operator=(const Dropped&) = delete;
      ~Dropped()
      {
          if(mode == 1)
          {
              seenName = kira::at(heldList, 0).name;
              heldList = {Held{.v = 99, .r = nullptr, .name = "fin-long-name-that-lives-on-the-heap-00000", .kids = {}}};
          }
          else if(mode == 2)
          {
              seenName = heldOne.name;
              heldOne = Held{.v = 99, .r = nullptr, .name = "fin", .kids = {}};
          }
          else if(mode == 3)
          {
              seenName = heldMap.at(1).name;
              heldMap = kira::Map<std::int32_t, Held>{};
          }
      }
  };
  [[nodiscard]] kira::Rc<Dropped> dropping(std::int32_t mode)
  {
      kira::Rc<Dropped> d = std::make_shared<Dropped>();
      d->mode = mode;
      return d;
  }

  void testReplace()
  {
      heldList = {Held{.v = 1, .r = dropping(1), .name = "one-long-name-that-lives-on-the-heap-000000", .kids = {1, 2, 3}}};
      kira::replace(kira::at(heldList, 0)) = Held{.v = 2, .r = nullptr, .name = "two-long-name-that-lives-on-the-heap-000000", .kids = {4, 5}};
      check(same(seenName, "two-long-name-that-lives-on-the-heap-000000") && heldList.size() == 1 && kira::at(heldList, 0).v == 99,
            "f2: xs[i] = v stores the whole value, then the finally replaces xs");

      heldOne = Held{.v = 1, .r = dropping(2), .name = "one-long-name-that-lives-on-the-heap-000000", .kids = {1, 2, 3}};
      kira::replace(heldOne) = Held{.v = 2, .r = nullptr, .name = "two-long-name-that-lives-on-the-heap-000000", .kids = {4, 5}};
      check(same(seenName, "two-long-name-that-lives-on-the-heap-000000") && heldOne.v == 99 && same(heldOne.name, "fin") && heldOne.kids.empty(),
            "f3: x = v stores the whole value, then the finally's write is last, never a mix");

      heldMap.put(1, Held{.v = 1, .r = dropping(3), .name = "one-long-name-that-lives-on-the-heap-000000", .kids = {}});
      heldMap.put(1, Held{.v = 3, .r = nullptr, .name = "three-long-name-that-lives-on-the-heap-0000", .kids = {}});
      check(same(seenName, "three-long-name-that-lives-on-the-heap-0000") && heldMap.isEmpty(), "Map.put over a key stores, then drops the old value");

      kira::List<bool> flags{false, false};
      kira::replace(kira::at(flags, 1)) = true;
      std::optional<Held> maybe = Held{.v = 1, .r = nullptr, .name = "m", .kids = {}};
      kira::replace(maybe) = kira::none;
      check(kira::at(flags, 1) && !kira::at(flags, 0) && !maybe.has_value(), "a proxy place (List<Bool>) and a Maybe");
  }

  // Round 6's w2-3 finding (v23r6x/atk hx1, hx2, k_c1): a write over a class handle is a
  // std::shared_ptr assignment, whose order is the library's. libstdc++'s copy-assignment
  // releases the old count before it stores the new one, so a finally that re-entered the
  // handle met the new pointer beside the dying count (hx1: Res 1 destroyed twice; hx2: a
  // control block stored into a freed buffer). kira::replace stores whole, then drops.
  struct HandleRes;
  kira::Rc<HandleRes> handleSlot;
  kira::List<kira::Rc<HandleRes>> handleList;
  kira::List<std::int64_t> handleAfter;
  std::int32_t handleDrops = 0;
  std::int32_t handleSeen = 0;
  struct HandleRes
  {
      std::int32_t n = 0;
      std::int32_t mode = 0;
      HandleRes(std::int32_t n_, std::int32_t mode_) : n(n_), mode(mode_)
      {
      }
      HandleRes(const HandleRes&) = delete;
      HandleRes& operator=(const HandleRes&) = delete;
      ~HandleRes()
      {
          if(mode == 1)
          {
              // hx1: copy the handle the slot holds into a local, and read it.
              ++handleDrops;
              const kira::Rc<HandleRes> x = handleSlot;
              handleSeen = x ? x->n : -1;
          }
          else if(mode == 2)
          {
              // hx2: replace the list, then allocate a list of the same byte size.
              ++handleDrops;
              handleList = {};
              handleAfter = {11, 22};
          }
          else if(mode == 3)
          {
              // k_c1: read the slot, then write it again.
              ++handleDrops;
              handleSeen = handleSlot ? handleSlot->n : -1;
              kira::replace(handleSlot) = std::make_shared<HandleRes>(3, 0);
          }
      }
  };

  void testReplaceHandle()
  {
      handleSlot = std::make_shared<HandleRes>(1, 1);
      {
          const kira::Rc<HandleRes> b = std::make_shared<HandleRes>(2, 0);
          kira::replace(handleSlot) = b;
          check(handleDrops == 1 && handleSeen == 2 && handleSlot == b && b.use_count() == 2,
                "hx1: gm = b stores b, then Res 1 drops once and sees Res 2");
      }
      kira::replace(handleSlot) = kira::none;

      handleDrops = 0;
      handleList = {std::make_shared<HandleRes>(1, 2)};
      {
          const kira::Rc<HandleRes> b = std::make_shared<HandleRes>(2, 0);
          kira::replace(kira::at(handleList, 0)) = b;
          check(handleDrops == 1 && handleList.empty() && handleAfter.size() == 2 && kira::at(handleAfter, 0) == 11 &&
                    kira::at(handleAfter, 1) == 22 && b.use_count() == 1,
                "hx2: xs[0] = b stores whole, then the finally replaces xs and its writes are last");
      }

      handleDrops = 0;
      handleSeen = 0;
      handleSlot = std::make_shared<HandleRes>(1, 3);
      {
          const kira::Rc<HandleRes> b = std::make_shared<HandleRes>(2, 0);
          kira::replace(handleSlot) = b;
          check(handleDrops == 1 && handleSeen == 2 && handleSlot && handleSlot->n == 3 && b.use_count() == 1,
                "k_c1: the finally sees b, and its own write over the same handle is last");
      }
      kira::replace(handleSlot) = kira::none;
  }

  std::int32_t argsSeen = 0;
  [[nodiscard]] std::int32_t mainWithArgs(const kira::List<kira::Str>& args)
  {
      argsSeen = static_cast<std::int32_t>(args.size());
      return args.size() == 2 && args[1] == "two" ? 12 : 13;
  }
  [[nodiscard]] std::int32_t mainThatThrows()
  {
      throw kira::Error{"boom"};
  }
  void voidMain()
  {
  }

  void testMain()
  {
      char program[] = "rt_test";
      char second[] = "two";
      char* args[] = {program, second, nullptr};
      check(kira::rt::runMain(2, args, &mainWithArgs) == 12 && argsSeen == 2, "runMain hands main its args");
      check(kira::rt::argc() == 2 && std::strcmp(kira::rt::argv()[1], "two") == 0, "and keeps argc and argv for seams");
      check(kira::rt::runMain(1, args, &voidMain) == 0, "a Void main exits 0");
      std::fprintf(stderr, "rt_test: the next line is runMain reporting an uncaught error on purpose\n");
      check(kira::rt::runMain(1, args, &mainThatThrows) == 70, "an uncaught kira::Error exits 70");
  }

  // The locale trap: a process that calls setlocale (a GUI toolkit, a library)
  // changes what printf and strtod do. Kira's text must not move.
  void testLocale()
  {
      const char* const names[] = {"de_DE.UTF-8", "de_DE.utf8", "de-DE", "de_DE", "German_Germany.1252", ""};
      const char* chosen = nullptr;
      for(const char* name : names)
      {
          chosen = std::setlocale(LC_ALL, name);
          if(chosen != nullptr && std::localeconv()->decimal_point[0] == ',')
          {
              break;
          }
      }
      const bool armed = std::localeconv()->decimal_point[0] == ',';
      std::fprintf(stderr, "rt_test: locale %s, decimal point '%s': the trap is %s\n",
                   chosen != nullptr ? chosen : "(none)", std::localeconv()->decimal_point, armed ? "ARMED" : "not armed");
      check(same(kira::text(opaque(0.25)), "0.25") && same(kira::cat(opaque(2.5f)), "2.5"), "text under the locale");
      check(kira::unwrap(kira::str::toFloat64("2.50")) == 2.5, "toFloat64 under the locale");
      check(!kira::isSome(kira::str::toFloat64("2,50")), "and still refuses a comma");
      check(same(traced(opaque(0.5)), "0.5"), "trace under the locale");
      std::setlocale(LC_ALL, "C");
  }

  // One runtime check, hit on purpose: must abort with "kira: ...".
  int panicMode(const char* what)
  {
#if defined(_WIN32)
      _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
#endif
      std::array<std::int32_t, 2> arr{1, 2};
      const kira::List<std::int32_t> list{1};
      const std::int32_t zero = opaque(0);
      std::int32_t sink = 0;
      if(std::strcmp(what, "div") == 0)
      {
          sink = kira::div(std::int32_t{1}, zero);
      }
      else if(std::strcmp(what, "mod") == 0)
      {
          sink = kira::mod(std::int32_t{1}, zero);
      }
      else if(std::strcmp(what, "overflow") == 0)
      {
          sink = kira::div(std::numeric_limits<std::int32_t>::lowest(), opaque(std::int32_t{-1}));
      }
      else if(std::strcmp(what, "shl") == 0)
      {
          sink = kira::shl(1, opaque(32));
      }
      else if(std::strcmp(what, "shr") == 0)
      {
          sink = kira::shr(1, opaque(-1));
      }
      else if(std::strcmp(what, "index") == 0)
      {
          sink = kira::at(arr, opaque(kira::Size{2}));
      }
      else if(std::strcmp(what, "view") == 0)
      {
          sink = kira::view(arr)[opaque(kira::Size{2})];
      }
      else if(std::strcmp(what, "slice") == 0)
      {
          sink = static_cast<std::int32_t>(kira::view(arr).slice(1, opaque(kira::Size{2})).size());
      }
      else if(std::strcmp(what, "bytesbe") == 0)
      {
          sink = static_cast<std::int32_t>(kira::readU32Be(kira::view(CHECK_TEXT), opaque(kira::Size{6})));
      }
      else if(std::strcmp(what, "list") == 0)
      {
          sink = kira::at(list, opaque(kira::Size{1}));
      }
      else if(std::strcmp(what, "unwrap") == 0)
      {
          sink = kira::unwrap(kira::Maybe<std::int32_t>(kira::none));
      }
      else if(std::strcmp(what, "rc") == 0)
      {
          sink = static_cast<std::int32_t>(kira::unwrap(kira::Maybe<kira::Rc<Pet>>(kira::none))->name.size());
      }
      else if(std::strcmp(what, "substring") == 0)
      {
          sink = static_cast<std::int32_t>(kira::str::substring("abc", 2, opaque(kira::Size{4})).size());
      }
      else if(std::strcmp(what, "strat") == 0)
      {
          sink = kira::str::at("abc", opaque(kira::Size{3}));
      }
      else if(std::strcmp(what, "radix") == 0)
      {
          sink = static_cast<std::int32_t>(kira::isSome(kira::str::toInt64("1", opaque(std::int32_t{37}))));
      }
      else if(std::strcmp(what, "result") == 0)
      {
          sink = kira::Result<std::int32_t, kira::Str>::error("no").unwrap();
      }
      else if(std::strcmp(what, "assert") == 0)
      {
          kira::assert_(opaque(false), "the assertion's own message");
      }
      std::printf("panic mode %s did not panic (%d)\n", what, sink);
      return 3;
  }
}

int main(int argc, char** argv)
{
    if(argc >= 3 && std::strcmp(argv[1], "panic") == 0)
    {
        return panicMode(argv[2]);
    }
    if(argc >= 2 && std::strcmp(argv[1], "throw") == 0)
    {
        return kira::rt::runMain(argc, argv, &mainThatThrows);
    }
    std::printf("\nrt_test - the Kira C++ runtime\n\n");
    testCore();
    testText();
    testStr();
    testContainers();
    testClasses();
    testReplace();
    testReplaceHandle();
    testMain();
    testLocale();
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
#else
// The board's panic, stubbed: spin, with no library call behind it
// (__builtin_trap is a call to abort on Cortex-M).
[[noreturn]] void kira::panic(const char* what) noexcept
{
    static_cast<void>(what);
    for(;;)
    {
        __asm__ volatile("");
    }
}

// What the Pico would call. Returns the failures, so nothing is optimised away.
extern "C" int kira_rt_probe()
{
    testCore();
    return failures * 1000 + checks;
}
#endif
