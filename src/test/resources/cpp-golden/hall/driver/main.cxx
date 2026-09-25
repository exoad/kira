// hall: the six-state decoder fed by hand, as bibo's test_hall does it. The same
// file builds for the Pico (-DKIRA_PROFILE_FREESTANDING=1): no printf, a stub
// panic, and golden_main in place of main.
#include "../expected/src/pico/hall.kira.hxx"

#if KIRA_PROFILE_HOSTED
#include <cstdio>
#endif

// The decoder walks at compile time too.
static_assert(hall::settle(hall::SEQUENCE, 0).ticks == 5, "one state primes, five steps forward count");
static_assert(hall::settle(hall::SEQUENCE, 0).errors() == 0 && hall::settle(hall::SEQUENCE, 0).periodUs == 1000u);
static_assert(hall::settle(std::array<std::uint8_t, 6>{1, 5, 4, 6, 2, 3}, 0).ticks == -5, "and backwards is negative");

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(!ok)
      {
          ++failures;
      }
#if KIRA_PROFILE_HOSTED
      std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
#else
      static_cast<void>(what);
#endif
  }

  void section(const char* title)
  {
#if KIRA_PROFILE_HOSTED
      std::printf("\n-- %s --\n", title);
#else
      static_cast<void>(title);
#endif
  }

  // n steps through the sequence from `from`, forward or back, 1 ms apart.
  std::uint64_t walk(hall::Decoder& d, std::int32_t from, std::int32_t steps, bool forward, std::uint64_t nowUs)
  {
      std::int32_t at = from;
      for(std::int32_t i = 0; i < steps; ++i)
      {
          at = forward ? (at + 1) % 6 : (at + 5) % 6;
          nowUs += 1000;
          d.feed(hall::SEQUENCE[static_cast<kira::Size>(at)], nowUs);
      }
      return nowUs;
  }

  void run()
  {
      section("the sequence itself");
      {
          bool oneBit = true;
          for(kira::Size i = 0; i < 6u; ++i)
          {
              const std::uint8_t a = hall::SEQUENCE[i];
              const std::uint8_t b = hall::SEQUENCE[(i + 1u) % 6u];
              const std::uint32_t diff = static_cast<std::uint32_t>(a ^ b);
              oneBit = oneBit && (diff == 1u || diff == 2u || diff == 4u);
          }
          check(oneBit, "every step of the sequence changes exactly one bit");
          bool indexed = true;
          for(kira::Size i = 0; i < 6u; ++i)
          {
              indexed = indexed && hall::INDEX_OF[hall::SEQUENCE[i]] == static_cast<std::int8_t>(i);
          }
          check(indexed, "and INDEX_OF is its inverse");
          check(hall::INDEX_OF[0] < 0 && hall::INDEX_OF[7] < 0, "0 and 7 are no state");
      }
      section("ticks and direction");
      {
          hall::Decoder d;
          check(d.ticks == 0 && !d.primed, "starts at zero, unprimed");
          d.feed(hall::SEQUENCE[0], 1000);
          check(d.primed && d.ticks == 0 && d.edges == 0u, "the first valid state primes and counts nothing");
          std::uint64_t t = walk(d, 0, 12, true, 1000);
          check(d.ticks == 12 && d.edges == 12u && d.errors() == 0u, "twelve steps forward: +12, two turns, no error");
          t = walk(d, 0, 5, false, t);
          check(d.ticks == 7 && d.lastDir == -1, "five back: 7, and the direction is remembered");
          check(d.periodUs == 1000u, "the period is the gap between the last two steps");
          d.feed(d.state, t + 500);
          check(d.ticks == 7 && d.repeats == 1u && d.errors() == 0u, "an edge that changed nothing is a repeat, not an error");
      }
      section("what is an error");
      {
          hall::Decoder d;
          d.feed(hall::SEQUENCE[0], 1000);
          d.feed(hall::SEQUENCE[2], 2000);
          check(d.ticks == 0 && d.skips == 1u && d.errors() == 1u, "two steps at once is a skip, and not a tick");
          d.feed(hall::SEQUENCE[3], 3000);
          check(d.ticks == 1 && d.errors() == 1u, "and the step after it counts again from the new state");
          d.feed(0, 4000);
          check(d.invalid == 1u && d.errors() == 2u && !d.primed, "all three low is invalid and unprimes");
          d.feed(hall::SEQUENCE[3], 5000);
          d.feed(hall::SEQUENCE[4], 6000);
          check(d.ticks == 2 && d.errors() == 2u, "after an invalid state the next valid one primes, the one after counts");
          d.feed(7, 7000);
          check(d.invalid == 2u, "all three high is invalid too");
          d.feed(0xF9, 8000);
          check(d.invalid == 2u && d.primed, "only the low three bits are read");
      }
      section("speed");
      {
          hall::Decoder d;
          d.feed(hall::SEQUENCE[0], 0);
          const std::uint64_t t = walk(d, 0, 30, true, 0);
          hall::Speed s = hall::speed(30, d, t);
          check(s.windowTps == 600, "30 ticks in a 50 ms window is 600 ticks per second");
          check(s.periodTps == 1000, "a 1 ms period is 1000 ticks per second, signed forward");
          check(!s.usePeriod, "with 30 ticks in the window, the window is trusted");
          s = hall::speed(3, d, t);
          check(s.usePeriod, "with 3, the period is");
          hall::Decoder back;
          back.feed(hall::SEQUENCE[0], 0);
          const std::uint64_t tb = walk(back, 0, 4, false, 0);
          s = hall::speed(-4, back, tb);
          check(s.periodTps == -1000 && s.windowTps == -80, "backwards is negative both ways");
          s = hall::speed(0, d, t + hall::STOPPED_US + 1);
          check(s.stopped && s.periodTps == 0 && s.windowTps == 0 && !s.usePeriod, "a quarter second without an edge is stopped");
          hall::Decoder fresh;
          s = hall::speed(0, fresh, 5000);
          check(s.stopped, "and so is a decoder that has seen nothing");
          hall::Decoder slow;
          slow.feed(hall::SEQUENCE[0], 0);
          slow.feed(hall::SEQUENCE[1], 0x1FFFFFFFFull);
          check(slow.periodUs == 0xFFFFFFFFu, "a gap past 32 bits saturates the period");
      }
      section("compile time");
      {
          constexpr hall::Decoder settled = hall::settle(hall::SEQUENCE, 100);
          check(settled.ticks == 5 && settled.lastEdgeUs == 5100u, "settle is a constant expression");
      }
  }
}

#if KIRA_PROFILE_HOSTED
int main()
{
    std::printf("\nhall - the six-state decoder\n");
    run();
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
#else
// The board's panic, stubbed for the probe build.
[[noreturn]] void kira::panic(const char* what) noexcept
{
    static_cast<void>(what);
    for(;;)
    {
        __asm__ volatile("");
    }
}

extern "C" int golden_main()
{
    run();
    return failures;
}
#endif
