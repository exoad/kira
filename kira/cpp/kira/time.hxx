// kira/time.hxx - the hosted runtime of kira:time: clocks, sleep and the UTC stamp.
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

  // unixNs as UTC "YYYY-MM-DDTHH:MM:SS.mmmZ", the milliseconds floored; the date is Hinnant's civil_from_days.
  [[nodiscard]] inline Str utcStamp(std::int64_t unixNs)
  {
      std::int64_t ms = unixNs / 1000000 - (unixNs % 1000000 < 0 ? 1 : 0);
      std::int64_t days = ms / 86400000;
      ms %= 86400000;
      if(ms < 0)
      {
          ms += 86400000;
          days -= 1;
      }
      const std::int64_t z = days + 719468;
      const std::int64_t era = (z >= 0 ? z : z - 146096) / 146097;
      const std::int64_t doe = z - era * 146097;
      const std::int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
      const std::int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
      const std::int64_t mp = (5 * doy + 2) / 153;
      const std::int64_t month = mp < 10 ? mp + 3 : mp - 9;
      Str out;
      const auto put = [&out](std::int64_t v, int width, char after) {
          char digits[20] = {};
          int n = 0;
          do
          {
              digits[n++] = static_cast<char>('0' + v % 10);
              v /= 10;
          } while(v > 0);
          while(n < width)
          {
              digits[n++] = '0';
          }
          while(n > 0)
          {
              out.push_back(digits[--n]);
          }
          out.push_back(after);
      };
      put(yoe + era * 400 + (month <= 2 ? 1 : 0), 4, '-');
      put(month, 2, '-');
      put(doy - (153 * mp + 2) / 5 + 1, 2, 'T');
      put(ms / 3600000, 2, ':');
      put(ms / 60000 % 60, 2, ':');
      put(ms / 1000 % 60, 2, '.');
      put(ms % 1000, 3, 'Z');
      return out;
  }
}
