// ffi_test - kira/ffi.hxx (design 7.2): the argument proxies an extern call
// passes and the drift check an extern module states. bash kira/cpp/tests/run.sh
// builds and runs it on every toolchain found; kira\cpp\tests\msvc.bat does the
// same with MSVC.
//
// One file, three builds:
//   hosted            the proxies and the checks; prints bibo's check format
//   freestanding      -DKIRA_PROFILE_FREESTANDING=1 -c: Out and the macro only
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
}

// ---- the checks an extern module would state (design 7.2, literally) --------------------
KIRA_EXTERN_CHECK(std::declval<fake::Car&>().arm(), bool, "Car.arm");
KIRA_EXTERN_CHECK(std::declval<const fake::Car&>().ok(), bool, "Car.ok");
KIRA_EXTERN_CHECK((std::declval<fake::Car&>().drive(std::declval<float>(), std::declval<float>()), 0), int, "Car.drive");
KIRA_EXTERN_CHECK(std::declval<fake::Car&>().finish(), std::int32_t, "Car.finish");
KIRA_EXTERN_CHECK(fake::checkbox(std::declval<const char*>(), kira::ffi::out(std::declval<bool&>())), bool, "checkbox");
KIRA_EXTERN_CHECK(fake::checkbox(std::declval<const char*>(), kira::ffi::out(std::declval<std::int32_t&>()), std::declval<std::int32_t>()), bool, "checkboxFlags");
#if KIRA_PROFILE_HOSTED
KIRA_EXTERN_CHECK(fake::button(kira::ffi::in(std::declval<const kira::Str&>())), bool, "button");
KIRA_EXTERN_CHECK(fake::sliderFloat(kira::ffi::in(std::declval<const kira::Str&>()), kira::ffi::out(std::declval<float&>()), std::declval<float>(), std::declval<float>()), bool, "sliderFloat");
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
    check(true, "out(x) never converts to a T by value (static_assert)");

    // ---- in(s): const char*, std::string_view, const std::string& ------------------
    const kira::Str s = "kira-ffi";
    check(lenCStr(kira::ffi::in(s)) == 8, "in(s) binds to const char*");
    check(lenView(kira::ffi::in(s)) == 8, "in(s) binds to std::string_view");
    check(lenRef(kira::ffi::in(s)) == 8, "in(s) binds to const std::string&");
    check(dataOf(kira::ffi::in(s)) == s.data(), "in(s) passes the Str itself, not a copy");
    check(lenCStr(kira::ffi::in("literal")) == 7, "in(\"literal\") holds the temporary Str for the call");

    // ---- CStrBuf: a CStr over a computed Str, alive for the full-expression --------
    check(lenCStr(kira::ffi::CStrBuf(s + "-x").c_str()) == 10, "CStrBuf(expr).c_str() lives to the end of the full-expression");

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

    // ---- the checks above compiled: overloads, defaults, const receivers --------------
    check(true, "KIRA_EXTERN_CHECK states overloads, default arguments and const receivers");

    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures;
}
#endif
