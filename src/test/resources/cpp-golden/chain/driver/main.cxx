// chain: bibo's test_chain idioms against the generated class hierarchy. A C++
// test double subclasses the Kira trait, a unique_ptr is adopted by the chain,
// the Chain lives on the stack, and Maybe<Behaviour> is a nullable Rc, so
// `at(0) != nullptr && at(0)->id()` and `at(2) == nullptr` still compile.
#include "../expected/src/pilot/chain.kira.hxx"

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

  int greedyLoads = 0;

  // A driver that always asks for everything. Was `CharSeq id() const override`.
  class Greedy final : public chain::Behaviour
  {
  public:
      [[nodiscard]] kira::Str id() const override
      {
          return "greedy";
      }
      [[nodiscard]] kira::Str name() const override
      {
          return "greedy driver";
      }
      [[nodiscard]] bool mayDrive() const override
      {
          return true;
      }
      void onLoad() override
      {
          ++greedyLoads;
      }
      [[nodiscard]] chain::Reply step(const chain::Pass& p) override
      {
          return chain::Reply{.act = chain::Act::ACT_PROPOSE, .throttle = p.haveHolder ? 1.0f : 0.5f, .steer = -1.0f};
      }
  };

  // No id: a behaviour the chain must refuse.
  class Nameless final : public chain::Behaviour
  {
  public:
      [[nodiscard]] kira::Str id() const override
      {
          return "";
      }
      [[nodiscard]] kira::Str name() const override
      {
          return "nameless";
      }
      [[nodiscard]] chain::Reply step(const chain::Pass&) override
      {
          return chain::Reply{};
      }
  };

  template<class T>
  std::unique_ptr<T> makeUniq()
  {
      return std::make_unique<T>();
  }

  bool add(chain::Chain& c, std::unique_ptr<chain::Behaviour> b)
  {
      kira::Str why;
      return c.load(std::move(b), why);
  }
}

int main()
{
    std::printf("\nchain - the behaviour chain and Maybe<Behaviour>\n\n");
    chain::Chain c;
    kira::Str why;
    check(c.size() == 0 && c.at(0) == nullptr, "a stack-constructed Chain starts empty");
    check(c.load(chain::make(chain::ID_STOP), why), "make(ID_STOP) loads");
    check(add(c, makeUniq<Greedy>()), "a C++ test double loads through a unique_ptr");
    check(greedyLoads == 1, "and its onLoad ran once");
    check(c.at(0) != nullptr && c.at(0)->id() == chain::ID_STOP, "at(0) != nullptr && at(0)->id()");
    check(c.at(1) != nullptr && c.at(1)->mayDrive() && !c.at(0)->mayDrive(), "a default body, and its override");
    check(c.at(2) == nullptr, "at(2) == nullptr past the end");
    check(kira::isSome(c.at(1)) && kira::unwrap(c.at(1))->name() == "greedy driver", "the Kira spelling reads the same");
    check(!c.load(chain::make("nope"), why) && why == "no behaviour", "an unknown id makes none, which is refused");
    check(!add(c, makeUniq<Greedy>()), "a second Greedy is refused");
    kira::Str whyTwice;
    static_cast<void>(c.load(std::make_shared<Greedy>(), whyTwice));
    check(whyTwice == "already loaded: greedy" && greedyLoads == 1, "with its reason, and without loading it");
    check(!c.load(std::make_shared<Nameless>(), why) && why == "a behaviour with no id", "an empty id is refused");
    check(c.size() == 2 && c.has("greedy") && !c.has(chain::ID_WASD), "size and has");
    const chain::Reply r = kira::unwrap(c.at(0))->step(chain::Pass{.dtMs = 50});
    check(r.act == chain::Act::ACT_CLAMP && r.throttle == 0.0f, "Stop clamps");
    const chain::Reply g = c.at(1)->step(chain::Pass{.haveHolder = true});
    check(g.act == chain::Act::ACT_PROPOSE && g.throttle == 1.0f && g.steer == -1.0f, "and the double proposes");
    const kira::Rc<chain::Chain> shared = std::make_shared<chain::Chain>();
    check(shared->load(chain::make(chain::ID_STOP), why) && shared->size() == 1, "Kira code holds a Chain by Rc");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
