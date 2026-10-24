// testblocks: test blocks on classes and structs, run. The identity test comes first, so no
// member of a Square or an empty Maybe is ever read; each check is ANDed.
#include "../expected/src/lang/testblocks.kira.hxx"

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
}

int main()
{
    std::printf("\ntestblocks - test blocks on classes and structs\n\n");
    const kira::Rc<testblocks::Shape> c3 = testblocks::make(3);
    const kira::Rc<testblocks::Shape> c12 = testblocks::make(12);
    const kira::Rc<testblocks::Shape> s4 = testblocks::make(-4);
    check(testblocks::big(c12) && !testblocks::big(c3) && !testblocks::big(s4), "a class's test, then its checks");
    check(testblocks::describe(c3) == "circle 3" && testblocks::describe(s4) == "other", "after an if on != that leaves, the rest sees the block passed");
    check(testblocks::describe(testblocks::make(2)) == "other", "a Circle whose check fails is no match");
    check(testblocks::held(testblocks::hold(3, 1)) && !testblocks::held(testblocks::hold(3, 2)), "a nested block on a class field and a Maybe field");
    check(!testblocks::held(testblocks::hold(-3, 1)) && !testblocks::held(testblocks::hold(3, -1)), "a Square field and an empty Maybe field are no match");
    const testblocks::Line line{testblocks::Pt{0, 0}, testblocks::Pt{2, 5}};
    const testblocks::Line off{testblocks::Pt{1, 0}, testblocks::Pt{2, 5}};
    const kira::Maybe<testblocks::Pt> one = testblocks::Pt{4, 1};
    const kira::Maybe<testblocks::Pt> none;
    check(testblocks::onLine(line, one) && !testblocks::onLine(off, one) && !testblocks::onLine(line, none), "a struct's nested fields and a Maybe");
    check(testblocks::sideOf(s4) == 4 && testblocks::sideOf(c3) == -1, "the body sees a Square");
    check(testblocks::Pt{0, 0}.atOrigin() && !testblocks::Pt{0, 1}.atOrigin(), "the receiver as the subject");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
