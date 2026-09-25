// A fake bibo::Car for the forward golden: the shape of bibo's car.hxx, with a
// scripted drive instead of a Pico and a lidar. main.cxx defines it.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace bibo
{
  struct Scan
  {
      float nearestM = 9.0f;

      [[nodiscard]] float ahead() const
      {
          return nearestM;
      }
  };

  class Car
  {
  public:
      Car(int argc, char** argv);
      Car(const Car&) = delete;
      Car& operator=(const Car&) = delete;

      [[nodiscard]] bool arm();
      [[nodiscard]] bool ok() const;
      [[nodiscard]] bool drivable() const;
      [[nodiscard]] Scan scan();
      void drive(float throttle, float steer);
      // Checks what the program did, prints bibo's check format, and returns
      // the failures, which the program returns as its exit status.
      [[nodiscard]] std::int32_t finish();

  private:
      int argc_ = 0;
      bool haveArgv_ = false;
      std::size_t next_ = 0;
      int arms_ = 0;
      int skipped_ = 0;
      int scans_ = 0;
      std::vector<float> throttles_;
      std::vector<float> steers_;
  };
}
