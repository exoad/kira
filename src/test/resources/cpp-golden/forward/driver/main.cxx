// forward: a Kira car program driving a fake bibo::Car. The program has its own
// main (forward.kira.cxx, through kira::rt::runMain), so this file defines no
// main: it is the Car, driven by a script, and Car::finish holds the program to
// what it should have done and prints bibo's check format.
#include "car.hxx"

#include <cstdio>

namespace
{
  struct Step
  {
      bool drivable;
      bool rearms;
      float aheadM;
  };

  // What the car sees, pass by pass.
  constexpr Step SCRIPT[] = {
      {true, true, 2.0f},     // clear: creep
      {true, true, 0.7f},     // between the thresholds: still creeping
      {true, true, 0.55f},    // inside STOP_AT_M: stop
      {false, false, 9.0f},   // disarmed, and arm() refuses: the pass is skipped
      {false, true, 0.7f},    // disarmed, arm() works: between the thresholds, still stopped
      {true, true, 0.81f},    // past GO_AT_M: creep again
  };
  constexpr std::size_t STEPS = sizeof(SCRIPT) / sizeof(SCRIPT[0]);
  constexpr float WANT[] = {0.10f, 0.10f, 0.0f, 0.0f, 0.10f};

  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++failures;
      }
  }
}

namespace bibo
{
  Car::Car(int argc, char** argv) : argc_(argc), haveArgv_(argv != nullptr && argc > 0 && argv[0] != nullptr)
  {
  }

  bool Car::arm()
  {
      ++arms_;
      if(arms_ == 1)
      {
          return true;
      }
      if(!SCRIPT[next_].rearms)
      {
          ++skipped_;
          ++next_;
          return false;
      }
      return true;
  }

  bool Car::ok() const
  {
      return next_ < STEPS;
  }

  bool Car::drivable() const
  {
      return SCRIPT[next_].drivable;
  }

  Scan Car::scan()
  {
      ++scans_;
      return Scan{.nearestM = SCRIPT[next_].aheadM};
  }

  void Car::drive(float throttle, float steer)
  {
      throttles_.push_back(throttle);
      steers_.push_back(steer);
      ++next_;
  }

  std::int32_t Car::finish()
  {
      std::printf("\nforward - a Kira program on the extern bibo::Car\n\n");
      check(argc_ >= 1 && haveArgv_, "openCar handed the Car the process's argc and argv");
      check(next_ == STEPS, "the program drove until ok() said stop");
      check(throttles_.size() == 5 && scans_ == 5, "one scan and one drive per drivable pass");
      bool same = throttles_.size() == 5;
      for(std::size_t i = 0; same && i < 5; ++i)
      {
          same = throttles_[i] == WANT[i];
      }
      check(same, "creep, creep, stop, stop, creep: two thresholds, no chatter");
      bool straight = true;
      for(const float s : steers_)
      {
          straight = straight && s == 0.0f;
      }
      check(straight, "and it never steered");
      check(skipped_ == 1 && arms_ == 3, "a disarmed pass that cannot re-arm is skipped");
      std::printf("\n%d checks, %d failed\n", checks, failures);
      return failures;
  }
}
