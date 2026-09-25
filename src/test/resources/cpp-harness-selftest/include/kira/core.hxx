// Harness self-test stand-in for kira/cpp/kira/core.hxx (W1.3 writes the real
// one). It exists so the C++ harness can be tested in a checkout that has no
// runtime and no golden corpus yet; it mirrors the profile switch, the panic
// split and a few scalar aliases, nothing more.
//
// The panic split is the part that matters to the harness (design 8.2, 10):
// a hosted build DEFINES kira::panic here, a freestanding build only DECLARES
// it and the board provides it. So a freestanding module that reaches a check
// (div, mod, View::at) links in a host suite only when the host toolchain
// compiles it hosted. A harness that set KIRA_PROFILE_FREESTANDING=1 for a
// host toolchain fails to link the pico self-test case on kira::panic, which
// is exactly what W1.3's hall and text cases would do at the merge.
#pragma once
#include <cstddef>
#include <cstdint>
#include <type_traits>

#if defined(KIRA_PROFILE_FREESTANDING) && KIRA_PROFILE_FREESTANDING
#define KIRA_PROFILE_HOSTED 0
#else
#define KIRA_PROFILE_HOSTED 1
#include <cstdio>
#include <cstdlib>
#endif

namespace kira
{
  using Int8 = std::int8_t;
  using Int16 = std::int16_t;
  using Int32 = std::int32_t;
  using Int64 = std::int64_t;
  using UInt8 = std::uint8_t;
  using UInt16 = std::uint16_t;
  using UInt32 = std::uint32_t;
  using UInt64 = std::uint64_t;
  using Size = std::size_t;
  using Bool = bool;

#if KIRA_PROFILE_HOSTED
  [[noreturn]] inline void panic(const char* what) noexcept
  {
    std::fflush(stdout);
    std::fprintf(stderr, "kira: %s\n", what != nullptr ? what : "panic");
    std::fflush(stderr);
    std::abort();
  }
#else
  // The board defines it: it parks the outputs and resets. Declared, never
  // defined here.
  [[noreturn]] void panic(const char* what) noexcept;
#endif

  [[nodiscard]] constexpr Int32 wrapAdd(Int32 a, Int32 b) noexcept
  {
    return static_cast<Int32>(static_cast<UInt32>(a) + static_cast<UInt32>(b));
  }

  // Checked division (D10): x / 0 panics, and so does lowest / -1.
  template<class T>
  [[nodiscard]] constexpr T div(T a, T b)
  {
    static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::div is integer division");
    if(b == 0)
    {
      panic("integer division by zero");
    }
    if constexpr(std::is_signed_v<T>)
    {
      if(a == static_cast<T>(static_cast<T>(1) << (sizeof(T) * 8 - 1)) && b == static_cast<T>(-1))
      {
        panic("integer division overflows");
      }
    }
    return static_cast<T>(a / b);
  }

  template<class T>
  [[nodiscard]] constexpr T mod(T a, T b)
  {
    static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::mod is an integer remainder");
    if(b == 0)
    {
      panic("integer remainder by zero");
    }
    if constexpr(std::is_signed_v<T>)
    {
      if(b == static_cast<T>(-1))
      {
        return T{0};
      }
    }
    return static_cast<T>(a % b);
  }
}
