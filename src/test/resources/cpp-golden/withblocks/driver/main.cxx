// withblocks: `with` on a Mutable class run. The block edits the receiver in place (the
// receiver the caller holds sees it), a bare call goes through the receiver, and the value of
// the `with` is the receiver itself.
#include "../expected/src/lang/withblocks.kira.hxx"

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
    std::printf("withblocks - with on a Mutable class edits in place\n\n");

    const auto b = withblocks::built();
    check(b->total() == 5, "a construction with a block: two bumps");
    check(b->name() == "built", "and the assignment through the receiver");
    check(b->marks() == "bb", "and each call ran once");

    const auto t = withblocks::built();
    withblocks::touch(t);
    check(t->total() == 16, "a statement block edits the object the caller holds");
    check(t->name() == "touched", "its field assignment too");
    check(t->marks() == "bbb", "and its call");

    check(withblocks::inArgument() == 9, "a with as an argument is the edited receiver");

    const auto e = withblocks::edited(t, 4);
    check(e == t, "the value of a with is the receiver itself");
    check(t->total() == 20 && t->name() == "edited", "edited in place");

    check(withblocks::loops() == 1 + 2 + 4 + 5 + 6, "break and continue act on the loop around the block");

    const auto a = withblocks::built();
    const auto d = std::make_shared<withblocks::Dial>();
    check(withblocks::nested(a, d) == 1307, "a nested block and a local of its own");

    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
