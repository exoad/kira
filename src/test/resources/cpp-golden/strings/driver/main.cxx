// strings: Str's text and parsing, run under a comma-decimal locale when the
// machine has one. The output is the same either way: that is the point.
#include "../expected/src/lang/strings.kira.hxx"

#include <clocale>
#include <cstdio>
#include <limits>

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

  bool isInt(const kira::Maybe<std::int64_t>& m, std::int64_t want)
  {
      return kira::isSome(m) && kira::unwrap(m) == want;
  }

  bool isFloat(const kira::Maybe<double>& m, double want)
  {
      return kira::isSome(m) && kira::unwrap(m) == want;
  }
}

int main()
{
    // As a GUI toolkit or a library may: printf and strtod now follow de_DE.
    const char* const names[] = {"de_DE.UTF-8", "de_DE.utf8", "de-DE", "German_Germany.1252"};
    for(const char* name : names)
    {
        if(std::setlocale(LC_ALL, name) != nullptr)
        {
            break;
        }
    }
    std::printf("\nstrings - text, interpolation and strict parsing\n\n");
    checkStr(strings::greet("mochi", 3), "hello mochi, you are 3", "interpolation");
    checkStr(strings::describe(true, 0.1, 0.1f, 'x', 200, strings::Mood::MOOD_BUSY),
             "b=true f=0.1 g=0.1 c=x u=200 m=MOOD_BUSY", "every piece kind");
    checkStr(strings::describe(false, 1e21, 3.0e10f, '-', 0, strings::Mood::MOOD_CALM),
             "b=false f=1e+21 g=3e+10 c=- u=0 m=MOOD_CALM", "and exponents");
    checkStr(strings::show(2.5), "2.5", "as Str: 2.5, not 2,5");
    checkStr(strings::show(100.0), "100", "as Str: 100");
    checkStr(strings::show(0.1 + 0.2), "0.30000000000000004", "as Str is the shortest text that reads back");
    checkStr(strings::flag(true), "true", "text(Bool) is true");
    checkStr(strings::flag(false), "false", "and false");
    checkStr(strings::padded(42), "00042", "padStart");
    checkStr(strings::padded(123456), "123456", "padStart never cuts");
    check(isInt(strings::parseInt("1541"), 1541) && isInt(strings::parseInt("+7"), 7) && isInt(strings::parseInt("-0"), 0),
          "toInt64: digits and an optional sign");
    check(isInt(strings::parseInt("-9223372036854775808"), std::numeric_limits<std::int64_t>::lowest()), "toInt64: Int64 min");
    check(!kira::isSome(strings::parseInt("9223372036854775808")), "toInt64: overflow is none");
    check(!kira::isSome(strings::parseInt("1541abc")) && !kira::isSome(strings::parseInt(" 5")) &&
              !kira::isSome(strings::parseInt("")) && !kira::isSome(strings::parseInt("5.0")),
          "toInt64 refuses a tail, a space, nothing and a decimal");
    check(isFloat(strings::parseFloat("2.50"), 2.5) && isFloat(strings::parseFloat("+2.5"), 2.5), "toFloat64, and a leading +");
    check(isFloat(strings::parseFloat("1e3"), 1000.0) && isFloat(strings::parseFloat("-0.125"), -0.125), "exponent and sign");
    check(!kira::isSome(strings::parseFloat("2,5")) && !kira::isSome(strings::parseFloat("2.5 ")) &&
              !kira::isSome(strings::parseFloat("")) && !kira::isSome(strings::parseFloat("+-1")),
          "toFloat64 refuses a comma, a tail, nothing and two signs");
    checkStr(strings::joined("a", "b"), "a b", "Str + Str");
    checkStr(strings::bracketed("x"), "<x>", "a literal on the left of +");
    check(strings::initial("Kira") == 'K', "s[0] is a Char");
    check(strings::same("abc", kira::Str("ab") + "c") && !strings::same("a", "b"), "== compares content");
    checkStr(strings::middle("[abc]"), "abc", "substring");
    checkStr(strings::middle("x"), "", "and a guard");
    std::setlocale(LC_ALL, "C");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
