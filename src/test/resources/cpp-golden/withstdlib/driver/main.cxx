// withstdlib: `with` on a stdlib value run. The block's bare calls are the receiver's methods, the
// List, Map, Set or StrBuf the caller holds is edited in place (no copy), and a parameter named like
// a method stays the parameter.
#include "../expected/src/lang/withstdlib.kira.hxx"

#include <cstdio>
#include <memory>
#include <string_view>

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
    std::printf("withstdlib - with on a stdlib value edits it in place\n\n");

    auto xs = withstdlib::filled();
    check(xs.size() == 3 && xs[0] == 1 && xs[2] == 3, "a declaration with a block: three adds into the local");

    withstdlib::grow(xs, 9);
    check(xs.size() == 5, "a block on a parameter grows the caller's List");
    check(xs[3] == 9, "a parameter named like a method is the parameter");
    check(xs[4] == 4, "and the method is called through the receiver");

    auto m = withstdlib::table(4);
    check(m.size() == 2, "three puts on two keys");
    check(m.get("n") == 5 && m.get("twice") == 8, "a key put again keeps the later value");

    const auto bag = std::make_shared<withstdlib::Bag>();
    bag->fill(3);
    check(bag->sum() == 33 && bag->count == 2, "a field as the receiver is edited, not a copy of it");
    bag->fill(1);
    check(bag->sum() == 17 && bag->count == 4, "and again, with the class's own field read in the block");

    kira::List<kira::Str> words{};
    words.push_back("a");
    words.push_back("bc");
    words.push_back("stop");
    words.push_back("d");
    kira::StrBuf<32> out{};
    withstdlib::reply(words, 7, out);
    const auto view = out.view();
    check(std::string_view(view.data(), view.size()) == "n=7 4;a;bc", "a caller's buffer, an interpolation set piece by piece, and break out of the loop");

    check(withstdlib::evens(9) == 4, "a loop inside the block adds through the receiver");

    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
