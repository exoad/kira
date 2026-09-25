// proto: the car's line protocol, as bibo's test_proto drives it, against the
// generated C++. The cases that matter each give a plausible wrong answer under
// the obvious implementation: a key inside another key, a value that is not a
// number, a reply that is neither OK nor ERR, and a comma-decimal locale.
#include "../expected/src/pilot/proto.kira.hxx"

#include <clocale>
#include <cstdio>
#include <cstdlib>
#include <cstring>

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

  void checkStr(const kira::Str& got, const char* want, const char* what)
  {
      const bool ok = got == want;
      check(ok, what);
      if(!ok)
      {
          std::printf("        got  \"%s\"\n        want \"%s\"\n", got.c_str(), want);
      }
  }
}

int main()
{
    std::printf("\nproto - the car's line protocol\n\n");
    {
        const proto::Reply r = proto::read("OK drive servo=1500 esc=1541");
        check(r.kind == proto::Kind::KIND_OK, "OK is recognized");
        checkStr(r.topic, "drive", "and its topic split off");
        checkStr(r.rest, "servo=1500 esc=1541", "and the fields kept");
    }
    {
        const proto::Reply r = proto::read("INFO status up_ms=4210 led=on");
        check(r.kind == proto::Kind::KIND_INFO, "INFO is recognized");
        checkStr(r.topic, "status", "and its topic split off");
    }
    {
        const proto::Reply r = proto::read("ERR blink wants a rate in hz");
        check(r.kind == proto::Kind::KIND_ERR, "ERR is recognized");
        check(r.topic.empty(), "and has no topic invented for it");
        checkStr(r.rest, "blink wants a rate in hz", "the reason is kept whole");
    }
    {
        const proto::Reply r = proto::read("bibo firmware, RP2350");
        check(r.kind == proto::Kind::KIND_OTHER, "an unrecognized line is OTHER, not a fault");
        checkStr(r.rest, "bibo firmware, RP2350", "and kept whole");
    }
    check(proto::read("").kind == proto::Kind::KIND_EMPTY, "empty is empty");
    check(proto::read("   \t ").kind == proto::Kind::KIND_EMPTY, "and so is whitespace");
    checkStr(proto::read("OK led on\r\n").line, "OK led on", "CRLF is stripped from the kept line");
    {
        const kira::Str line = "servo=1500 servo_t=1480 esc=1541 esc_t=1600";
        std::int32_t v = 0;
        check(proto::fieldInt(line, "servo=", v) && v == 1500, "servo=");
        check(proto::fieldInt(line, "servo_t=", v) && v == 1480, "servo_t=");
        check(proto::fieldInt(line, "esc=", v) && v == 1541, "esc=");
        check(proto::fieldInt(line, "esc_t=", v) && v == 1600, "esc_t=");
    }
    {
        // strstr(line, "esc=") would find the "esc=" inside "desc=".
        const kira::Str line = "desc=99 esc=1541";
        std::int32_t v = 0;
        check(proto::fieldInt(line, "esc=", v) && v == 1541, "esc= is not matched inside desc=");
        std::printf("        (strstr would have returned %d here)\n", std::atoi(std::strstr(line.c_str(), "esc=") + 4));
        check(proto::fieldInt(line, "desc=", v) && v == 99, "and desc= still reads correctly");
    }
    {
        const kira::Str line = "a=1 b=2";
        std::int32_t v = 0;
        check(!proto::fieldInt(line, "c=", v), "a missing key is false");
        check(!proto::fieldInt(line, "", v), "an empty key is false");
    }
    {
        // esc=off is not a 0, which would read as neutral throttle.
        const kira::Str line = "esc=off servo=1500";
        std::int32_t v = -1;
        check(!proto::fieldInt(line, "esc=", v), "a non-numeric value is refused, not defaulted to 0");
        check(v == -1, "and the out parameter is left alone");
        kira::Str raw;
        check(proto::field(line, "esc=", raw) && raw == "off", "though it can still be read as text");
    }
    {
        std::int32_t v = 0;
        check(!proto::fieldInt("esc=1541abc", "esc=", v), "a trailing tail is refused");
        check(!proto::fieldInt("esc=", "esc=", v), "and so is an empty value");
    }
    {
        // The shape of firmware/app/main.cxx printDrive(): several keys are
        // prefixes of others on the line.
        const kira::Str line = "OK drive servo=1600 servo_t=1610 esc=1560 esc_t=1570 armed=1 "
                               "servo_on=1 servo_c=1480 steer_m=-300 steer_now=-250 slew=8 "
                               "slew_esc=12 servo_min=1230 servo_max=1660 esc_min=1541 esc_max=1600 "
                               "esc_rev=1400 stale=0 tick=-2859 tps=-420 hskip=0 hbad=1";
        const proto::Reply r = proto::read(line);
        check(r.kind == proto::Kind::KIND_OK, "the drive reply is an OK");
        checkStr(r.topic, "drive", "with topic drive");
        std::int32_t v = 0;
        check(proto::fieldInt(r.rest, "armed=", v) && v == 1, "armed= reads the arm state");
        check(proto::fieldInt(r.rest, "esc=", v) && v == 1560, "esc= is not esc_t=, esc_min= or esc_max=");
        check(proto::fieldInt(r.rest, "steer_now=", v) && v == -250, "steer_now= is negative and is not steer_m=");
        check(proto::fieldInt(r.rest, "tick=", v) && v == -2859, "tick= is the encoder's signed count");
        check(proto::fieldInt(r.rest, "tps=", v) && v == -420, "tps= its signed speed");
        check(proto::fieldInt(r.rest, "hbad=", v) && v == 1, "hbad= is its own key at the end of the line");
        check(proto::fieldInt(r.rest, "slew=", v) && v == 8, "slew= is not slew_esc=");
        v = 0;
        check(!proto::fieldInt(r.rest, "armed", v), "a key missing its = is refused, not silently empty");
        check(v == 0, "and it leaves the out parameter alone");
    }
    {
        const kira::Str line = "hz=2.50 gain=-0.125";
        float f = 0.0f;
        check(proto::fieldFloat(line, "hz=", f) && f > 2.49f && f < 2.51f, "a float field");
        check(proto::fieldFloat(line, "gain=", f) && f < -0.124f && f > -0.126f, "a negative float field");
        check(!proto::fieldFloat("hz=2,50", "hz=", f), "a comma decimal is refused");
    }
    checkStr(proto::steer(0.25f), "STEER 0.250", "a steering command");
    checkStr(proto::steer(-0.5f), "STEER -0.500", "a negative one");
    checkStr(proto::steer(0.0f), "STEER 0.000", "center");
    checkStr(proto::steer(4.0f), "STEER 1.000", "beyond full lock is clamped");
    checkStr(proto::steer(-9.0f), "STEER -1.000", "and the other way");
    checkStr(proto::steer(0.2499f), "STEER 0.250", "the third decimal rounds");
    checkStr(proto::steer(0.000499999966f), "STEER 0.001", "Float32 arithmetic, as bibo's C++ did it");
    checkStr(proto::escUs(1541), "ESC 1541", "a throttle pulse");
    checkStr(proto::stop(), "STOP", "the stop command");
    checkStr(proto::command("LED", "BLINK 2"), "LED BLINK 2", "a built command");
    checkStr(proto::command("PING"), "PING", "one with no arguments");
    checkStr(proto::command(""), "", "an empty verb makes nothing");
    checkStr(proto::command("SAY", "100%"), "SAY 100%", "a % in the arguments is a plain character");
    {
        // The locale trap: steer() avoids snprintf("%.3f"), which prints a comma
        // under a comma-decimal locale, and fieldFloat avoids strtod.
        const char* const names[] = {"de_DE.UTF-8", "de_DE.utf8", "de-DE", "German_Germany.1252"};
        for(const char* name : names)
        {
            if(std::setlocale(LC_ALL, name) != nullptr)
            {
                break;
            }
        }
        const kira::Str s = proto::steer(0.25f);
        check(s.find(',') == kira::Str::npos && s == "STEER 0.250", "no comma survives into a command");
        float f = 0.0f;
        check(proto::fieldFloat("hz=2.50", "hz=", f) && f > 2.49f && f < 2.51f, "and 2.50 still reads as 2.5");
        std::setlocale(LC_ALL, "C");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
