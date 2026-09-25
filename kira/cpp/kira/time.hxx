// kira/time.hxx - the hosted runtime of kira:time: clocks and sleep.
//
// monoNowNs / monoNowMs read std::chrono::steady_clock, which never jumps;
// wallNowNs reads std::chrono::system_clock, for a stamp another process
// compares. Each is an Int64 so a Kira program subtracts them without a cast.
#pragma once

#include "kira/rt.hxx"

#include <chrono>
#include <cstdint>
#include <thread>

namespace kira::time
{
  [[nodiscard]] inline std::int64_t monoNowNs()
  {
      const auto since = std::chrono::steady_clock::now().time_since_epoch();
      return static_cast<std::int64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(since).count());
  }

  [[nodiscard]] inline std::int64_t monoNowMs()
  {
      const auto since = std::chrono::steady_clock::now().time_since_epoch();
      return static_cast<std::int64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(since).count());
  }

  // Nanoseconds since the Unix epoch. C++20 fixes system_clock's epoch to it.
  [[nodiscard]] inline std::int64_t wallNowNs()
  {
      const auto since = std::chrono::system_clock::now().time_since_epoch();
      return static_cast<std::int64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(since).count());
  }

  // Blocks for at least ms milliseconds; zero or less returns at once.
  inline void sleepMs(std::int64_t ms)
  {
      if(ms > 0)
      {
          std::this_thread::sleep_for(std::chrono::milliseconds(ms));
      }
  }
}
