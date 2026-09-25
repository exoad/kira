// Harness self-test stand-in for kira/cpp/kira/core.hxx (W1.3 writes the real
// one). It exists so the C++ harness can be tested in a checkout that has no
// runtime and no golden corpus yet; it mirrors only the profile switch and a
// few scalar aliases.
#pragma once
#include <cstddef>
#include <cstdint>

#if !defined(KIRA_PROFILE_FREESTANDING)
#define KIRA_PROFILE_HOSTED 1
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

  [[nodiscard]] constexpr Int32 wrapAdd(Int32 a, Int32 b) noexcept
  {
    return static_cast<Int32>(static_cast<UInt32>(a) + static_cast<UInt32>(b));
  }
}
