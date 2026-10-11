// kira/math.hxx - kira:math's functions that are no one <cmath> call, for both profiles.
#pragma once

#include "kira/core.hxx"

#include <cmath>

namespace kira
{
  // Python's a % b (CPython's float_rem): the remainder takes b's sign, a zero too; NaN where b is 0.
  template<class T>
  [[nodiscard]] inline T floorMod(T a, T b) noexcept
  {
      static_assert(std::is_floating_point_v<T>, "floorMod is a Float32's or a Float64's");
      T mod = std::fmod(a, b);
      if(mod != T(0))
      {
          if((b < T(0)) != (mod < T(0)))
          {
              mod += b;
          }
      }
      else
      {
          mod = std::copysign(T(0), b);
      }
      return mod;
  }
}
