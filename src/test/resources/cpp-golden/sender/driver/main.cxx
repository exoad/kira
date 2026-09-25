// sender: carrules' Sender, constructed exactly as bibo's test_carrules builds it -
// brace-initialised in a fake port from two lambdas, each capturing `this`. The
// Fx fields are stored, so they are std::function, and the write lambda's
// parameters (const Str&, Int32) are the Fx's parameter column.
#include "../expected/src/pilot/carrules.kira.hxx"

#include <cstdio>

namespace
{
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

  // A port whose writes take scripted time: each write moves the clock by stepMs
  // and answers `answer`.
  struct FakePort
  {
      std::int64_t nowMs = 60000;
      std::int64_t stepMs = 1;
      carlink::LinkResult answer = carlink::LinkResult::LINK_OK;
      kira::List<kira::Str> lines;
      kira::List<std::int32_t> waits;

      carrules::Sender sender{
          [this]() { return nowMs; },
          [this](const kira::Str& line, std::int32_t waitMs) { return write(line, waitMs); }
      };

      carlink::LinkResult write(const kira::Str& line, std::int32_t waitMs)
      {
          lines.push_back(line);
          waits.push_back(waitMs);
          nowMs += stepMs;
          return answer;
      }
  };
}

int main()
{
    std::printf("\nsender - a class with stored Fx fields\n\n");
    {
        FakePort p;
        check(p.sender.sentMs() == -1, "nothing sent yet: -1");
        check(p.sender.send("STOP") == carlink::LinkResult::LINK_OK, "a line goes out");
        check(p.lines.size() == 1 && p.lines[0] == "STOP", "through the write lambda");
        check(p.waits[0] == carlink::WRITE_WAIT_MS, "with the default wait, WRITE_WAIT_MS");
        check(p.sender.sentMs() == 60001, "and counts as sent when its write returns");
        check(p.sender.send("ESC 1541", 12) == carlink::LinkResult::LINK_OK && p.waits[1] == 12, "a given wait is passed on");
    }
    {
        FakePort p;
        static_cast<void>(p.sender.send("STEER 0.000"));
        p.nowMs += 40;
        static_cast<void>(p.sender.send("ESC 1600"));
        p.nowMs += 5;
        static_cast<void>(p.sender.send("ESC 1600"));
        check(p.sender.worstGapMs() == 41, "worstGapMs is the longest gap between accepted lines");
    }
    {
        FakePort p;
        static_cast<void>(p.sender.send("STOP"));
        p.answer = carlink::LinkResult::LINK_CLOSED;
        check(p.sender.send("ESC 1600") == carlink::LinkResult::LINK_CLOSED, "a failed write's result comes back");
        check(p.sender.sentMs() == 60001 && p.lines.size() == 2, "and does not count as sent");
    }
    {
        std::int64_t clock = 5;
        kira::Str last;
        carrules::Sender direct([&clock]() { return clock; },
                                [&last](const kira::Str& line, std::int32_t) {
                                    last = line;
                                    return carlink::LinkResult::LINK_OK;
                                },
                                100, 7);
        static_cast<void>(direct.send("PING"));
        check(last == "PING" && direct.sentMs() == 5 && direct.worstGapMs() == 7, "every field is a constructor parameter");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
