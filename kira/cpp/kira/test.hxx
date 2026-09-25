// kira/test.hxx - the hosted runtime of kira:test: bibo's check format.
//
// The reference is bibo's firmware/pilot/tests/test_proto.cxx: a header
// ("\n<title>\n\n"), "  ok    <what>" / "  FAIL  <what>", a failed checkStr's
// "        got  \"<got>\"" and "        want \"<want>\"", and the summary
// "\n<N> checks, <M> failed\n\n". kira/cpp/tests/sys.sh diffs this class's
// output against that printf code, byte for byte.
#pragma once

#include "kira/rt.hxx"

#include <cstdint>
#include <cstdio>

namespace kira::test
{
  class Suite final
  {
  public:
      explicit Suite(const Str& title)
      {
          std::printf("\n%s\n\n", title.c_str());
      }
      Suite(const Suite&) = delete;
      Suite& operator=(const Suite&) = delete;

      void check(bool ok, const Str& what)
      {
          ++checks_;
          if(ok)
          {
              std::printf("  ok    %s\n", what.c_str());
          }
          else
          {
              std::printf("  FAIL  %s\n", what.c_str());
              ++failures_;
          }
      }

      void checkStr(const Str& got, const Str& want, const Str& what)
      {
          const bool ok = got == want;
          check(ok, what);
          if(!ok)
          {
              std::printf("        got  \"%s\"\n        want \"%s\"\n", got.c_str(), want.c_str());
          }
      }

      // 1 when any check failed, else 0: the process exit code.
      [[nodiscard]] std::int32_t finish() const
      {
          std::printf("\n%d checks, %d failed\n\n", checks_, failures_);
          return failures_ == 0 ? 0 : 1;
      }

  private:
      int checks_ = 0;
      int failures_ = 0;
  };
}
