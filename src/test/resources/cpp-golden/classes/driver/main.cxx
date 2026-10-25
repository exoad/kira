// classes: the class model run. The getter hands out copies of one reference
// and the count comes back; a C++ class implements a Kira trait and gets its
// default body; a virtual call reaches the subclass; Box<T> is a template; Ref
// is shared state; Weak goes empty when its object does.
#include "../expected/src/lang/classes.kira.hxx"

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

  // A C++ implementor of the Kira trait, as bibo's tests write their doubles.
  class Tripler final : public classes::Scaler
  {
  public:
      [[nodiscard]] std::int32_t scale(std::int32_t v) const override
      {
          return v * 3;
      }
      [[nodiscard]] std::int32_t factor() const override
      {
          return 3;
      }
  };
}

int main()
{
    std::printf("\nclasses - references, traits, inheritance, generics\n\n");
    {
        const kira::Rc<classes::Pet> pet = std::make_shared<classes::Pet>("mochi");
        const long before = pet.use_count();
        const kira::Rc<classes::Owner> owner = std::make_shared<classes::Owner>(pet);
        kira::Str names;
        for(int i = 0; i < 3; ++i)
        {
            const kira::Rc<classes::Pet> p = owner->getPet();
            names += p->name;
        }
        check(names == "mochimochimochi", "a getter returns the field, three times over");
        check(pet.use_count() == before + 1, "and every copy it handed out was released");
    }
    {
        const kira::Rc<classes::Doubler> d = std::make_shared<classes::Doubler>(2);
        check(classes::apply(d, 21) == 42, "a trait method with a parameter, through a trait reference");
        check(d->describe() == "scales by 2", "a trait default body calls the implementor");
        check(classes::apply(std::make_shared<Tripler>(), 5) == 15, "a C++ class implements the Kira trait");
        check(Tripler().describe() == "scales by 3", "and inherits the default body");
    }
    {
        const kira::Rc<classes::Dog> dog = std::make_shared<classes::Dog>("rex");
        const kira::Rc<classes::Animal> asAnimal = dog;
        check(asAnimal->describe() == "rex says woof", "the base method reaches the override");
        check(std::make_shared<classes::Animal>("cat")->describe() == "cat says ...", "and the base's own body");
        check(dog->learn() == 1 && dog->learn() == 2, "a mut method on the subclass");
        check(dog->name == "rex", "the base's field is the subclass's first constructor argument");
    }
    {
        check(classes::boxed(7)->get() == 7, "Box<Int32>");
        const kira::Rc<classes::Box<kira::Str>> s = std::make_shared<classes::Box<kira::Str>>("text");
        check(s->get() == "text" && s->value == "text", "and Box<Str>, instantiated by C++");
    }
    {
        const kira::Rc<kira::Box<std::int32_t>> c = classes::makeCounter();
        const kira::Rc<kira::Box<std::int32_t>> alias = c;
        static_cast<void>(classes::bump(c));
        check(classes::bump(alias) == 2 && c->value == 2, "Ref<Int32> is shared, mutable state");
    }
    {
        kira::Weak<classes::Pet> w;
        {
            const kira::Rc<classes::Pet> pet = std::make_shared<classes::Pet>("mochi");
            w = pet;
            check(classes::petName(w) == "mochi", "Weak.upgrade while the pet lives");
        }
        check(classes::petName(w) == "gone", "and none once it is gone");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
