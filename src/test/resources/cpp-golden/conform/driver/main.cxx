// conform: `value == Identity` on classes and traits, run. A passing test narrows the name,
// so the narrowed reads below reach a Circle's radius or a Dog's tricks.
#include "../expected/src/lang/conform.kira.hxx"

#include <cstdio>
#include <memory>

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
    std::printf("\nconform - type tests on classes and traits, and the narrowing they give\n\n");
    const kira::Rc<conform::Shape> c3 = conform::make(3);
    const kira::Rc<conform::Shape> c12 = conform::make(12);
    const kira::Rc<conform::Shape> c200 = conform::make(200);
    const kira::Rc<conform::Shape> s4 = conform::make(-4);
    check(conform::big(c12) && !conform::big(c3) && !conform::big(c200) && !conform::big(s4), "the rest of an && sees a Circle");
    check(conform::describe(c3) == "circle of 3" && conform::describe(s4) == "not a circle", "after an if that leaves, the rest sees a Circle");
    check(conform::tagged(c3) == "circle 3" && conform::tagged(s4) == "untagged", "a trait: a Circle is Named, a Square is not");
    check(conform::sideOr(s4) == 4 && conform::sideOr(c3) == -1, "!= narrows the else branch");
    check(conform::kind(c12) == "r=12" && conform::kind(s4) == "side=4", "each else-if branch sees its own test");
    check(conform::smallOrNot(s4) && conform::smallOrNot(c3) && !conform::smallOrNot(c12), "the rest of an || sees the left side false");
    check(conform::radiusOr(c12) == 12 && conform::radiusOr(s4) == 0, "an if-expression's arms");
    kira::List<kira::Rc<conform::Shape>> all{c3, c12, s4, c200};
    check(conform::radii(all) == 215, "after a continue, the rest of the loop body sees a Circle");
    const kira::Maybe<kira::Rc<conform::Animal>> none;
    check(conform::tricks(std::make_shared<conform::Dog>("rex")) == 2, "a Maybe holding a Dog is a Dog");
    check(conform::tricks(std::make_shared<conform::Animal>("cat")) == 0, "a Maybe holding an Animal is an Animal, no Dog");
    check(conform::tricks(none) == -1, "an empty Maybe is neither");
    const std::int32_t before = conform::made;
    check(conform::madeCircle(7) && !conform::madeCircle(-7), "a test of a call");
    check(conform::made == before + 2, "evaluates the call once");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
