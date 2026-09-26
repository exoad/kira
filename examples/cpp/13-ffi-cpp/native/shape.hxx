// shape.hxx - a C++ class Kira calls through @_extern, and the seam that makes
// one. This is what stays C++: the Kira side (src/app/shape.kira) is a view of
// this header, checked against it at compile time.
#pragma once

#include "kira/rt.hxx"

#include <cstdint>
#include <memory>

namespace shape
{
  class Box
  {
  public:
      Box(std::int32_t w, std::int32_t h) : w_(w), h_(h)
      {
      }

      [[nodiscard]] std::int32_t width() const
      {
          return w_;
      }
      [[nodiscard]] std::int32_t height() const
      {
          return h_;
      }
      [[nodiscard]] std::int32_t area() const
      {
          return w_ * h_;
      }
      void grow(std::int32_t by)
      {
          w_ += by;
          h_ += by;
      }

  private:
      std::int32_t w_;
      std::int32_t h_;
  };

  // The seam: a Kira class value is a kira::Rc<Box>, so Kira gets one from here.
  [[nodiscard]] inline kira::Rc<Box> makeBox(std::int32_t w, std::int32_t h)
  {
      return std::make_shared<Box>(w, h);
  }
}
