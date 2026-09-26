// ffi_test - kira/ffi.hxx (design 7.2): the argument proxies an extern call
// passes and the drift check an extern module states. bash kira/cpp/tests/run.sh
// builds and runs it on every toolchain found; kira\cpp\tests\msvc.bat does the
// same with MSVC.
//
// One file, three builds:
//   hosted            the proxies and the checks; prints bibo's check format.
//                     Every runtime check exercises a proxy or a call; the
//                     compile-time facts (the checks at file scope, the
//                     static_asserts) have no counted line, since a compiled
//                     program is their pass
//   freestanding      -DKIRA_PROFILE_FREESTANDING=1 -c: Out and the macros only
//                     (there is no Str on the Pico), for the symbol check
//   KIRA_FFI_DRIFT    -DKIRA_FFI_DRIFT=1: a deliberately wrong Kira signature
//                     stated as a check, which must NOT compile, and must fail
//                     with "no longer matches its C++ header"
//
// The includes are the generated header's: the runtime first (kira/rt.hxx
// hosted, kira/core.hxx freestanding), then the real headers, then kira/ffi.hxx.
#include "kira/core.hxx"

#if KIRA_PROFILE_HOSTED
#include "kira/rt.hxx"
#endif

#include "kira/ffi.hxx"

#include <cstddef>
#include <cstdint>

// ---- stands in for the real headers (car.hxx, imgui.h) ---------------------------
namespace fake
{
  struct Vec2
  {
      float x = 0.0f;
      float y = 0.0f;
  };

  // The shapes ImGui has: a default argument, a varargs function, an overloaded
  // name; a class with const and non-const members (bibo::Car).
  int calls = 0;
  Vec2 lastSize{-1.0f, -1.0f};
  const char* lastFormat = nullptr;
  int lastFlags = -1;
  int overload = 0;

  bool button(const char* label, const Vec2& size = Vec2())
  {
      ++calls;
      lastSize = size;
      return label != nullptr;
  }
  bool sliderFloat(const char* label, float* v, float lo, float hi, const char* format = "%.3f", int flags = 0)
  {
      ++calls;
      lastFormat = format;
      lastFlags = flags;
      *v = (lo + hi) / 2.0f;
      return label != nullptr;
  }
  bool checkbox(const char* label, bool* v)
  {
      ++calls;
      overload = 1;
      *v = !*v;
      return label != nullptr;
  }
  // The fixed-width typedef, never int: on arm-none-eabi std::int32_t is long,
  // and an Out<long> does not convert to int* (design 7.2, the int32_t trap;
  // measured on arm-none-eabi-g++ 13 when this fake said int*).
  bool checkbox(const char* label, std::int32_t* flags, std::int32_t value)
  {
      ++calls;
      overload = 2;
      *flags |= value;
      return label != nullptr;
  }

  class Car
  {
  public:
      [[nodiscard]] bool arm()
      {
          armed = true;
          return true;
      }
      [[nodiscard]] bool ok() const
      {
          return armed;
      }
      void drive(float, float)
      {
      }
      [[nodiscard]] std::int32_t finish()
      {
          return 0;
      }

  private:
      bool armed = false;
  };

  // The three ways C++ reads a mut argument, and the three ways it reads a Str.
  void addRef(std::int32_t& v)
  {
      v += 1;
  }
  void addPtr(std::int32_t* v)
  {
      *v += 10;
  }
  void readConstRef(const std::int32_t& v, std::int32_t* seen)
  {
      *seen = v;
  }

  // A writable buffer: Kira's `mut p: Unsafe<UInt8>` is this uint8_t* itself (table 5.1).
  void fill(std::uint8_t* p, std::size_t n)
  {
      for(std::size_t i = 0; i < n; ++i)
      {
          p[i] = static_cast<std::uint8_t>(i);
      }
  }

  // A const char* result Kira declares as Str (7.2), from storage of its own, so that a
  // pointer comparison against a literal elsewhere is false by construction.
  const char* nameStatic()
  {
      static char buf[] = {'a', 'b', 'c', 0};
      return buf;
  }
}

// ---- the checks an extern module would state (design 7.2, literally) --------------------
KIRA_EXTERN_CHECK(std::declval<fake::Car&>().arm(), bool, "Car.arm");
KIRA_EXTERN_CHECK(std::declval<const fake::Car&>().ok(), bool, "Car.ok");
KIRA_EXTERN_CHECK((std::declval<fake::Car&>().drive(kira::ffi::arg<float>(), kira::ffi::arg<float>()), 0), int, "Car.drive");
KIRA_EXTERN_CHECK(std::declval<fake::Car&>().finish(), std::int32_t, "Car.finish");
KIRA_EXTERN_CHECK(fake::checkbox(std::declval<const char*>(), kira::ffi::out(std::declval<bool&>())), bool, "checkbox");
KIRA_EXTERN_CHECK(fake::checkbox(std::declval<const char*>(), kira::ffi::out(std::declval<std::int32_t&>()), kira::ffi::arg<std::int32_t>()), bool, "checkboxFlags");
KIRA_EXTERN_CHECK((fake::fill(std::declval<std::uint8_t*>(), kira::ffi::arg<kira::Size>()), 0), int, "fill");
// An extern struct with fields: the layout twin, sizeof, and the exact type and offset per field.
namespace test::ffi_
{
  struct Vec2
  {
      float x;
      float y;
  };
}
static_assert(sizeof(fake::Vec2) == sizeof(test::ffi_::Vec2), "Kira's Vec2 no longer matches its C++ header");
KIRA_EXTERN_FIELD(fake::Vec2, test::ffi_::Vec2, x, float, "Vec2.x");
KIRA_EXTERN_FIELD(fake::Vec2, test::ffi_::Vec2, y, float, "Vec2.y");
// What the field check refuses that is_convertible would not: the same size, a converting type;
// and the same types, the other order.
static_assert(!kira::ffi::field_matches_v<decltype(std::declval<fake::Vec2&>().x), int>, "a float field is not an int field");
// A C struct's enum field, declared in Kira as the integer of its size (an extern enum is
// not a Kira declaration): it passes the field check and the twin has its size. A scoped
// enum, or an enum of another size, is a drift. That size is the target ABI's, not 4:
// the hosts make Mode 4 bytes, and arm-none-eabi-g++ 13.3 makes it 1 (AAPCS short
// enums; measured: declared std::int32_t, the size gate refused it there while sizeof
// passed by padding, which is the drift the gate exists for).
namespace fake
{
  enum Mode
  {
      MODE_OFF,
      MODE_ON
  };
  using ModeInt = std::conditional_t<sizeof(Mode) == 4, std::int32_t, std::uint8_t>;
  enum class Scoped : std::int32_t
  {
      A
  };
  enum Small : std::uint8_t
  {
      SMALL
  };
  enum Wide : std::int64_t
  {
      WIDE
  };
  struct Cfg
  {
      Mode mode;
      std::int32_t n;
  };
}
namespace test::ffi_
{
  struct Cfg
  {
      fake::ModeInt mode;
      std::int32_t n;
  };
}
static_assert(sizeof(fake::Cfg) == sizeof(test::ffi_::Cfg), "Kira's Cfg no longer matches its C++ header");
KIRA_EXTERN_FIELD(fake::Cfg, test::ffi_::Cfg, mode, fake::ModeInt, "Cfg.mode");
KIRA_EXTERN_FIELD(fake::Cfg, test::ffi_::Cfg, n, std::int32_t, "Cfg.n");
static_assert(!kira::ffi::field_matches_v<fake::Scoped, std::int32_t>, "a scoped enum field is not an Int32 field");
static_assert(!kira::ffi::field_matches_v<fake::Small, std::int32_t>, "a 1-byte enum field is not an Int32 field");
static_assert(!kira::ffi::field_matches_v<fake::Wide, std::int32_t>, "an 8-byte enum field is not an Int32 field");
static_assert(!kira::ffi::field_matches_v<fake::Mode, float>, "an enum field is not a Float32 field");
namespace test::swapped_
{
  struct Vec2
  {
      float y;
      float x;
  };
}
static_assert(offsetof(fake::Vec2, y) != offsetof(test::swapped_::Vec2, y), "a swapped pair is caught by its offset");
// The scalar rule (the head of kira/ffi.hxx): one scalar is the same type, or two arithmetic
// types of the same size, signedness and kind. So a C header's `int x` is Kira's Int32 on
// every target, arm-none-eabi included, where std::int32_t is long and is_same refused it
// (measured: "Kira's CPt.x no longer matches" on arm-none-eabi-g++ 13 while host g++ passed);
// a same-size unsigned, a float, or a bool is not.
namespace fake
{
  struct CPt
  {
      int x;
      unsigned int flags;
  };
}
namespace test::ffi_
{
  struct CPt
  {
      std::int32_t x;
      std::uint32_t flags;
  };
}
static_assert(sizeof(fake::CPt) == sizeof(test::ffi_::CPt), "Kira's CPt no longer matches its C++ header");
KIRA_EXTERN_FIELD(fake::CPt, test::ffi_::CPt, x, std::int32_t, "CPt.x");
KIRA_EXTERN_FIELD(fake::CPt, test::ffi_::CPt, flags, std::uint32_t, "CPt.flags");
static_assert(!kira::ffi::same_scalar_v<unsigned int, std::int32_t>, "an unsigned int is not an Int32");
static_assert(!kira::ffi::same_scalar_v<float, std::int32_t>, "a float is not an Int32");
static_assert(!kira::ffi::same_scalar_v<double, float>, "a double is not a Float32");
static_assert(!kira::ffi::same_scalar_v<std::int64_t, std::int32_t>, "an Int64 is not an Int32");
static_assert(!kira::ffi::same_scalar_v<bool, std::uint8_t>, "a bool is not a UInt8");
static_assert(!kira::ffi::same_scalar_v<std::uint8_t, bool>, "a UInt8 is not a Bool");
// The return is matched by the same rule: a C++ std::uint32_t is no Int32 (is_convertible
// let it through, and Kira's `count() - 1` then computed unsigned: 4294967295, measured),
// an int is no Bool, and a const reference or a constant is matched by its value's type.
namespace fake
{
  std::uint32_t countU();
  int isOk();
  const std::int32_t& countRef();
  inline constexpr std::int32_t LIMIT = 9;
  void takeCRef(const std::int32_t&);
  int over(int);
  int over(float);
}
static_assert(!kira::ffi::result_matches_v<decltype(fake::countU()), std::int32_t>, "a uint32_t result is not an Int32");
static_assert(!kira::ffi::result_matches_v<decltype(fake::isOk()), bool>, "an int result is not a Bool");
static_assert(!kira::ffi::result_matches_v<decltype(std::declval<fake::Car&>().finish()), std::int64_t>, "an int32_t result is not an Int64");
static_assert(kira::ffi::result_matches_v<decltype(fake::countRef()), std::int32_t>, "a const int32_t& result is an Int32");
static_assert(kira::ffi::result_matches_v<decltype(fake::LIMIT), std::int32_t>, "a constexpr int32_t is an Int32");
static_assert(kira::ffi::result_matches_v<decltype(std::declval<fake::Cfg&>().mode), fake::ModeInt>, "an unscoped enum result is the integer of its size");
static_assert(!kira::ffi::result_matches_v<fake::Scoped, std::int32_t>, "a scoped enum result is not an Int32");
// A by-value scalar parameter is stated as arg<T>(), which converts to T's scalar and to no
// other, so the check is viable only against the parameter the Kira value reaches unchanged:
// a Kira Int32 against a C++ std::uint8_t (which declval<int32_t>() converted to, and the call
// then narrowed silently) or a Float32 against a double is no viable call.
KIRA_EXTERN_CHECK((fake::takeCRef(kira::ffi::arg<std::int32_t>()), 0), int, "takeCRef");
KIRA_EXTERN_CHECK(fake::over(kira::ffi::arg<std::int32_t>()), std::int32_t, "over");
KIRA_EXTERN_CHECK(fake::over(kira::ffi::arg<float>()), std::int32_t, "overF");
static_assert(std::is_convertible_v<kira::ffi::Arg<std::int32_t>, int>, "arg<Int32> reaches an int parameter on every target this runs on");
static_assert(std::is_convertible_v<kira::ffi::Arg<std::int32_t>, const std::int32_t&>, "arg<Int32> binds a const int32_t&");
static_assert(!std::is_convertible_v<kira::ffi::Arg<std::int32_t>, std::uint8_t>, "arg<Int32> does not reach a uint8_t parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<std::int32_t>, std::uint32_t>, "arg<Int32> does not reach a uint32_t parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<float>, double>, "arg<Float32> does not reach a double parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<bool>, int>, "arg<Bool> does not reach an int parameter");
// Maybe and Fn are matched part by part by the same rule. std::optional's converting
// constructor is the is_convertible hole one level down: `std::optional<std::uint32_t>
// find()` passed as `find: () Maybe<Int32>` and `unwrap(find()) - 1` printed 4294967295
// (measured), and a Maybe<Int32> stated as a declval reached `put(std::optional<std::uint8_t>)`,
// where 300 arrived as 44; std::function takes any callable of a compatible signature.
// A Maybe is only an optional (an int is no Maybe<Int32>, though optional converts from it),
// and a Fn a std::function, a function pointer or a function type of the same arity whose
// return and parameters match, agreeing on which are mutable references.
namespace fake
{
  std::optional<std::uint32_t> findU();
  std::optional<std::int32_t> findI();
  std::optional<int> findInt();
  std::optional<const char*> findName();
  int found();
  void putI(const std::optional<std::int32_t>&);
}
static_assert(!kira::ffi::result_matches_v<decltype(fake::findU()), kira::Maybe<std::int32_t>>, "an optional<uint32_t> result is not a Maybe<Int32>");
static_assert(kira::ffi::result_matches_v<decltype(fake::findI()), kira::Maybe<std::int32_t>>, "an optional<int32_t> result is a Maybe<Int32>");
static_assert(kira::ffi::result_matches_v<decltype(fake::findInt()), kira::Maybe<std::int32_t>>, "an optional<int> result is a Maybe<Int32> on every target this runs on");
static_assert(!kira::ffi::result_matches_v<decltype(fake::found()), kira::Maybe<std::int32_t>>, "an int result is not a Maybe<Int32>");
static_assert(!kira::ffi::result_matches_v<decltype(fake::findI()), std::int32_t>, "an optional<int32_t> result is not an Int32");
static_assert(!kira::ffi::result_matches_v<decltype(fake::findU()), kira::Maybe<std::uint64_t>>, "an optional<uint32_t> result is not a Maybe<UInt64>");
KIRA_EXTERN_CHECK((fake::putI(kira::ffi::arg<kira::Maybe<std::int32_t>>()), 0), int, "putI");
static_assert(std::is_convertible_v<kira::ffi::Arg<kira::Maybe<std::int32_t>>, std::optional<std::int32_t>>, "arg<Maybe<Int32>> reaches an optional<int32_t> parameter");
static_assert(std::is_convertible_v<kira::ffi::Arg<kira::Maybe<std::int32_t>>, const std::optional<int>&>, "arg<Maybe<Int32>> binds a const optional<int>&");
static_assert(!std::is_convertible_v<kira::ffi::Arg<kira::Maybe<std::int32_t>>, std::optional<std::uint8_t>>, "arg<Maybe<Int32>> does not reach an optional<uint8_t> parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<kira::Maybe<std::int32_t>>, std::optional<std::int64_t>>, "arg<Maybe<Int32>> does not reach an optional<int64_t> parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<kira::Maybe<std::int32_t>>, std::int32_t>, "arg<Maybe<Int32>> does not reach an int32_t parameter");
#if KIRA_PROFILE_HOSTED
static_assert(kira::ffi::result_matches_v<decltype(fake::findName()), kira::Maybe<kira::Str>>, "an optional<const char*> result is a Maybe<Str>, converted at the call");
KIRA_EXTERN_CHECK(fake::button(kira::ffi::in(std::declval<const kira::Str&>())), bool, "button");
KIRA_EXTERN_CHECK(fake::sliderFloat(kira::ffi::in(std::declval<const kira::Str&>()), kira::ffi::out(std::declval<float&>()), kira::ffi::arg<float>(), kira::ffi::arg<float>()), bool, "sliderFloat");
namespace fake
{
  std::function<int(int)> onTick();
  std::function<unsigned(int)> onTickU();
  std::function<int(unsigned)> onTickTakesU();
  std::function<int(int, int)> onTick2();
  int (*rawTick())(int);
  int tick(int);
  std::function<void(const std::string&)> onName();
  std::function<void(std::string)> onNameByValue();
  std::function<void(std::string&)> onNameMut();
  void setTick(std::function<void(std::int32_t)>);
}
using Tick = kira::Fn<std::int32_t(std::int32_t)>;
static_assert(kira::ffi::result_matches_v<decltype(fake::onTick()), Tick>, "a function<int(int)> result is a Fn<(Int32) Int32>");
static_assert(!kira::ffi::result_matches_v<decltype(fake::onTickU()), Tick>, "a function<unsigned(int)> result is not a Fn<(Int32) Int32>");
static_assert(!kira::ffi::result_matches_v<decltype(fake::onTickTakesU()), Tick>, "a function<int(unsigned)> result is not a Fn<(Int32) Int32>");
static_assert(!kira::ffi::result_matches_v<decltype(fake::onTick2()), Tick>, "a function of two parameters is not a Fn of one");
static_assert(kira::ffi::result_matches_v<decltype(fake::rawTick()), Tick>, "a function pointer result is a Fn of its signature");
static_assert(kira::ffi::result_matches_v<decltype(fake::tick), Tick>, "a function itself is a Fn of its signature");
static_assert(!kira::ffi::result_matches_v<decltype(fake::found()), Tick>, "an int result is not a Fn");
static_assert(kira::ffi::result_matches_v<decltype(fake::onName()), kira::Fn<void(const kira::Str&)>>, "a function<void(const string&)> result is a Fn<(Str) Void>");
static_assert(kira::ffi::result_matches_v<decltype(fake::onNameByValue()), kira::Fn<void(const kira::Str&)>>, "a function<void(string)> result is a Fn<(Str) Void> too: the parameter reads the same");
static_assert(!kira::ffi::result_matches_v<decltype(fake::onNameMut()), kira::Fn<void(const kira::Str&)>>, "a function<void(string&)> result is not a Fn<(Str) Void>: it writes its parameter");
static_assert(kira::ffi::result_matches_v<decltype(fake::onNameMut()), kira::Fn<void(kira::Str&)>>, "a function<void(string&)> result is a Fn<(mut Str) Void>");
KIRA_EXTERN_CHECK((fake::setTick(kira::ffi::arg<kira::Fn<void(std::int32_t)>>()), 0), int, "setTick");
static_assert(!std::is_convertible_v<kira::ffi::Arg<kira::Fn<void(std::int32_t)>>, std::function<void(unsigned)>>, "arg<Fn<(Int32) Void>> does not reach a function<void(unsigned)> parameter");
static_assert(!std::is_convertible_v<kira::ffi::Arg<kira::Fn<void(std::int32_t)>>, void (*)(std::int32_t)>, "arg<Fn<(Int32) Void>> does not reach a function pointer parameter (a std::function does not either)");
#endif
// What a result reaches Kira as, when it is not a scalar: the generated call converts it to
// the declared type (a std::unique_ptr<Car> to an Rc; a Car* is no Rc, shared_ptr's
// constructor from a raw pointer being explicit).
#if KIRA_PROFILE_HOSTED
namespace fake
{
  std::unique_ptr<Car> openUnique();
  Car* openRaw();
  const std::string& nameRef();
  const char* nameC();
}
static_assert(kira::ffi::result_matches_v<decltype(fake::openUnique()), kira::Rc<fake::Car>>, "a unique_ptr<Car> result is a Car");
static_assert(!kira::ffi::result_matches_v<decltype(fake::openRaw()), kira::Rc<fake::Car>>, "a Car* result is not a Car (adopting it is a seam's decision)");
static_assert(kira::ffi::result_matches_v<decltype(fake::nameRef()), kira::Str>, "a const string& result is a Str");
static_assert(kira::ffi::result_matches_v<decltype(fake::nameC()), kira::Str>, "a const char* result is a Str");
#endif
#if defined(KIRA_FFI_DRIFT) && KIRA_FFI_DRIFT
// A Kira `pub mut fx finish: () Str;` against C++'s `std::int32_t finish()`.
KIRA_EXTERN_CHECK(std::declval<fake::Car&>().finish(), const char*, "Car.finish");
#endif

#if KIRA_PROFILE_HOSTED
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      std::printf("  %s  %s\n", ok ? "ok  " : "FAIL", what);
      if(!ok)
      {
          ++failures;
      }
  }

  std::size_t lenCStr(const char* s)
  {
      return std::strlen(s);
  }
  std::size_t lenView(std::string_view s)
  {
      return s.size();
  }
  std::size_t lenRef(const std::string& s)
  {
      return s.size();
  }
  const char* dataOf(const std::string& s)
  {
      return s.data();
  }
}

int main()
{
    std::printf("\nffi_test - kira/ffi.hxx: the proxies and the drift check\n\n");

    // ---- out(x): T& and T* -------------------------------------------------------
    std::int32_t n = 0;
    fake::addRef(kira::ffi::out(n));
    check(n == 1, "out(x) binds to T&");
    fake::addPtr(kira::ffi::out(n));
    check(n == 11, "out(x) binds to T*");
    std::int32_t seen = 0;
    fake::readConstRef(kira::ffi::out(n), kira::ffi::out(seen));
    check(seen == 11, "out(x) binds to const T&");
    static_assert(!std::is_convertible_v<kira::ffi::Out<std::int32_t>, std::int32_t>,
                  "out(x) must not convert to a T by value, which would copy and write nothing");

    // ---- a mut Unsafe<T> is the T* itself: no proxy, and the callee writes through it ------
    std::uint8_t bytes[4] = {9, 9, 9, 9};
    std::uint8_t* p = bytes;
    fake::fill(p, 4);
    check(bytes[0] == 0 && bytes[3] == 3, "a mut Unsafe<UInt8> is the uint8_t* itself: fill wrote through it");

    // ---- in(s): const char*, std::string_view, const std::string& ------------------
    const kira::Str s = "kira-ffi";
    check(lenCStr(kira::ffi::in(s)) == 8, "in(s) binds to const char*");
    check(lenView(kira::ffi::in(s)) == 8, "in(s) binds to std::string_view");
    check(lenRef(kira::ffi::in(s)) == 8, "in(s) binds to const std::string&");
    check(dataOf(kira::ffi::in(s)) == s.data(), "in(s) passes the Str itself, not a copy");
    check(lenCStr(kira::ffi::in("literal")) == 7, "in(\"literal\") holds the temporary Str for the call");

    // ---- CStrBuf: a CStr over a computed Str, alive for the full-expression --------
    check(lenCStr(kira::ffi::CStrBuf(s + "-x").c_str()) == 10, "CStrBuf(expr).c_str() lives to the end of the full-expression");

    // ---- a const char* result declared Str: the generated call converts it -----------
    // Left as the call's own type, `name() == ABC` (ABC a Kira Str constant, D12: a
    // constexpr const char*) compared two pointers and printed 0 for Kira's 1 (measured,
    // g++, clang and MSVC, -Werror silent). The emitter writes the static_cast.
    constexpr const char* ABC = "abc";
    check(!(fake::nameStatic() == ABC), "a const char* result kept as the call's type compares as a pointer (the bug)");
    check(static_cast<kira::Str>(fake::nameStatic()) == ABC, "static_cast<kira::Str>(name()) == ABC compares the text, as Kira means");

    // ---- C++ default arguments fill what Kira left out ---------------------------
    fake::calls = 0;
    check(fake::button(kira::ffi::in(s)) && fake::lastSize.x == 0.0f && fake::lastSize.y == 0.0f,
          "button(in(s)) took the C++ default size");
    float v = 0.0f;
    check(fake::sliderFloat(kira::ffi::in(s), kira::ffi::out(v), 0.0f, 1.0f) && v == 0.5f,
          "sliderFloat(in(s), out(v), lo, hi) wrote v through float*");
    check(fake::lastFormat != nullptr && std::strcmp(fake::lastFormat, "%.3f") == 0 && fake::lastFlags == 0,
          "and the C++ defaults filled format and flags");

    // ---- overloads resolve on the proxy's target ---------------------------------
    bool on = false;
    check(fake::checkbox(kira::ffi::in(s), kira::ffi::out(on)) && on && fake::overload == 1,
          "checkbox(in(s), out(bool)) picked the bool* overload");
    std::int32_t flags = 0;
    check(fake::checkbox(kira::ffi::in(s), kira::ffi::out(flags), 4) && flags == 4 && fake::overload == 2,
          "checkbox(in(s), out(int), value) picked the int* overload");
    check(fake::calls == 4, "every call reached its C++ function once");

    // The checks at file scope are the compile-time half: KIRA_EXTERN_CHECK states overloads,
    // default arguments, const receivers and a T* parameter; KIRA_EXTERN_FIELD a struct's
    // fields. That this program compiled is their pass; a wrong one is the KIRA_FFI_DRIFT build.
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures;
}
#endif
