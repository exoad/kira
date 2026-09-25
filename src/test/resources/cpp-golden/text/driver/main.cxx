// text: the Pico's command parsing on borrowed views and a fixed buffer. The same
// file builds for the Pico (-DKIRA_PROFILE_FREESTANDING=1): no printf, a stub
// panic, and golden_main in place of main.
#include "../expected/src/lib/text.kira.hxx"

#if KIRA_PROFILE_HOSTED
#include <cstdio>
#endif

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

  void run()
  {
      const kira::Maybe<kira::View<char>> arg = bibo::text::word(kira::lit("ESC 1541"), kira::lit("ESC"));
      check(kira::isSome(arg) && kira::unwrap(arg) == kira::lit("1541"), "word splits the argument off");
      check(kira::isSome(bibo::text::word(kira::lit("PING"), kira::lit("PING"))) &&
                kira::unwrap(bibo::text::word(kira::lit("PING"), kira::lit("PING"))).isEmpty(),
            "a bare command is an empty view, not none");
      check(!kira::isSome(bibo::text::word(kira::lit("ESCAPE 1"), kira::lit("ESC"))), "a longer word does not match");
      check(!kira::isSome(bibo::text::word(kira::lit("ES"), kira::lit("ESC"))), "nor does a shorter one");
      check(kira::unwrap(bibo::text::word(kira::lit("LED    on"), kira::lit("LED"))) == kira::lit("on"),
            "every space after the word is skipped");
      check(bibo::text::isCommand(kira::lit("STOP\r"), kira::lit("STOP")) == false, "a CR is not a space");
      check(bibo::text::trimEnd(kira::lit("PING\r\n")) == 4 && bibo::text::trimEnd(kira::lit(" \t")) == 0,
            "trimEnd drops CR, LF, space and tab");

      std::int32_t us = -1;
      check(bibo::text::toInt(kira::lit(" 1541 \r\n"), us) && us == 1541, "toInt allows surrounding space");
      check(bibo::text::toInt(kira::lit("-300"), us) && us == -300, "and a sign");
      us = 7;
      check(!bibo::text::toInt(kira::lit("12abc"), us) && us == 7, "12abc is refused, and out is untouched");
      check(!bibo::text::toInt(kira::lit("   "), us) && !bibo::text::toInt(kira::lit(""), us), "blank is refused");
      check(!bibo::text::toInt(kira::lit("1 2"), us), "two numbers are refused");

      kira::StrBuf<32> reply;
      bibo::text::driveReply(1500, -12, reply);
      check(reply.view() == kira::lit("OK drive servo=1500 esc=-12") && !reply.truncated(), "driveReply fills a StrBuf");
      bibo::text::driveReply(1600, 1541, reply);
      check(reply.view() == kira::lit("OK drive servo=1600 esc=1541"), "and clears it first");
      bibo::text::driveReply(2147483647, -2147483647, reply);
      check(reply.size() == 32 && reply.truncated(), "a reply past 32 chars is truncated, and says so");
  }
}

#if KIRA_PROFILE_HOSTED
int main()
{
    std::printf("\ntext - commands on views and a fixed buffer\n\n");
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
