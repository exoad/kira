package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.ClassLowering
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppGenericsEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppNames
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every row of design table 5.5, and the class rules of W2.4's brief, over small modules
 * emitted with the real classes and generics parts and W2.4's fake bodies
 * ([OopTestSupport.FakeExprEmitter], [OopTestSupport.FakeStmtEmitter]).
 */
class CppClassShapeTest {
    private val uri = "test:main"

    private fun emit(body: String, options: CppOptions = CppOptions(lineDirectives = false)) =
        OopTestSupport.emit(OopTestSupport.module(uri, body), options = options)

    private fun header(body: String): String = emit(body).header(uri)

    private fun both(body: String): Pair<String, String> {
        val e = emit(body)
        return e.header(uri) to e.source(uri)
    }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    private fun unsupported(body: String): List<String> =
        emit(body).module(uri).diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message }

    // ---- traits ---------------------------------------------------------------------------------

    @Test
    fun aTraitIsAnAbstractClassWithAVirtualDestructor() {
        val (h, s) = both(
            """
            pub trait Behaviour {
                pub fx id: () Str;
                pub fx mayDrive: () Bool {
                    return false
                }
                pub mut fx step: (p: Int32) Int32;
            }
            """
        )
        assertContains(
            h,
            "  class Behaviour\n  {\n  public:\n      virtual ~Behaviour() = default;\n" +
                "      [[nodiscard]] virtual kira::Str id() const = 0;\n" +
                "      [[nodiscard]] virtual bool mayDrive() const;\n" +
                "      [[nodiscard]] virtual std::int32_t step(std::int32_t p) = 0;\n  };",
        )
        // the default body is in the .kira.cxx, never inline in the header
        assertContains(s, "  bool Behaviour::mayDrive() const\n  {\n      return false;\n  }")
        assertLacks(h, "Behaviour::mayDrive")
    }

    @Test
    fun traitInheritanceIsPublicAndVirtualOnlyForADiamond() {
        val plain = header(
            """
            pub trait A {
                pub fx a: () Int32;
            }
            pub trait B: A {
                pub fx b: () Int32;
            }
            """
        )
        assertContains(plain, "  class B : public A\n  {\n  public:\n      [[nodiscard]] virtual std::int32_t b() const = 0;\n  };")
        assertLacks(plain, "virtual A", "~B()")

        val diamond = header(
            """
            pub trait A {
                pub fx a: () Int32;
            }
            pub trait B: A {
                pub fx b: () Int32;
            }
            pub trait C: A {
                pub fx c: () Int32;
            }
            pub class D: B, C {
                override pub fx a: () Int32 {
                    return 1
                }
                override pub fx b: () Int32 {
                    return 2
                }
                override pub fx c: () Int32 {
                    return 3
                }
            }
            """
        )
        assertContains(diamond, "  class B : public virtual A\n", "  class C : public virtual A\n", "  class D final : public B, public C\n")
    }

    @Test
    fun aMethodInheritedByDominanceIsForwarded() {
        val (h, s) = both(
            """
            pub trait Named {
                pub fx id: (n: Int32) Str;
            }
            pub trait Tagged: Named {
                pub fx tag: () Str;
            }
            pub class Animal: Named {
                override pub fx id: (n: Int32) Str {
                    return "animal"
                }
            }
            pub class Pup: Animal, Tagged {
                override pub fx tag: () Str {
                    return "pup"
                }
            }
            """
        )
        // Named is reached through Animal and through Tagged: a virtual base, and Animal's id dominates
        assertContains(h, "  class Animal : public virtual Named\n", "  class Tagged : public virtual Named\n", "  class Pup final : public Animal, public Tagged\n")
        assertContains(h, "      [[nodiscard]] kira::Str tag() const override;\n      [[nodiscard]] kira::Str id(std::int32_t a0_) const override;\n")
        assertContains(s, "  kira::Str Pup::id(std::int32_t a0_) const\n  {\n      return Animal::id(a0_);\n  }")
    }

    @Test
    fun aDiamondWithTwoOverridersIsRefused() {
        val messages = unsupported(
            """
            pub trait A {
                pub fx m: () Int32;
            }
            pub trait B: A {
                override pub fx m: () Int32 {
                    return 1
                }
            }
            pub trait C: A {
                override pub fx m: () Int32 {
                    return 2
                }
            }
            pub class D: B, C {
            }
            """
        )
        assertTrue(messages.any { it.startsWith("D inheriting m from both B and C (C++ needs one final overrider") }, messages.toString())
    }

    @Test
    fun aTraitMethodRedeclaredByAChildTraitOverrides() {
        val h = header(
            """
            pub trait A {
                pub fx name: () Str;
            }
            pub trait B: A {
                override pub fx name: () Str {
                    return "b"
                }
            }
            """
        )
        assertContains(h, "  class B : public A\n  {\n  public:\n      [[nodiscard]] kira::Str name() const override;\n  };")
    }

    @Test
    fun aTraitMethodTakesAnFxAsKiraFnSinceAVirtualCannotBeATemplate() {
        val h = header(
            """
            pub trait Visitor {
                pub fx each: (f: Fx<Tuple1<Int32>, Void>) Void;
            }
            """
        )
        assertContains(h, "      virtual void each(const kira::Fn<void(std::int32_t)>& f) const = 0;")
        assertLacks(h, "template")
    }

    // ---- classes: layout, constructor, destructor ------------------------------------------------

    @Test
    fun aClassHasAPublicSectionAndPrivateFieldsInDeclarationOrder() {
        val (h, s) = both(
            """
            pub class Governor {
                require dry: Bool
                require pub seconds: Int32
                mut secondsMs: Int64 = 0
                mut label: Str = "gov"

                pub fx ms: () Int64 {
                    return secondsMs
                }

                fx hidden: () Bool {
                    return dry
                }

                pub fx tag: () Str {
                    return label
                }
            }
            """
        )
        assertContains(
            h,
            "  class Governor final\n  {\n  public:\n" +
                "      Governor(bool dry_, std::int32_t seconds_, std::int64_t secondsMs_ = 0, kira::Str label_ = \"gov\");\n" +
                "      Governor(const Governor&) = delete;\n" +
                "      Governor& operator=(const Governor&) = delete;\n" +
                "      [[nodiscard]] std::int64_t ms() const;\n" +
                "      [[nodiscard]] kira::Str tag() const;\n" +
                "      std::int32_t seconds;\n" +
                "  private:\n" +
                "      [[nodiscard]] bool hidden() const;\n" +
                "      bool dry;\n" +
                "      std::int64_t secondsMs;\n" +
                "      kira::Str label;\n" +
                "  };",
        )
        assertContains(
            s,
            "  Governor::Governor(bool dry_, std::int32_t seconds_, std::int64_t secondsMs_, kira::Str label_)\n" +
                "      : dry(dry_), seconds(seconds_), secondsMs(secondsMs_), label(std::move(label_))\n  {\n  }",
        )
    }

    @Test
    fun theConstructorIsExplicitForExactlyOneParameterAndDefaultedForNone() {
        val h = header(
            """
            pub class One {
                require k: Int32
                pub fx get: () Int32 { return k }
            }
            pub class OneDefaulted {
                mut k: Int32 = 5
                pub fx get: () Int32 { return k }
            }
            pub class Two {
                require a: Int32
                require b: Int32
                pub fx sum: () Int32 { return a }
            }
            pub class Empty {
                pub fx get: () Int32 { return 1 }
            }
            """
        )
        assertContains(h, "      explicit One(std::int32_t k_);", "      explicit OneDefaulted(std::int32_t k_ = 5);", "      Two(std::int32_t a_, std::int32_t b_);", "      Empty() = default;")
    }

    @Test
    fun onlyTheTrailingDefaultsAreCppDefaultArguments() {
        val h = header(
            """
            pub class Base {
                mut a: Int32 = 1
                pub fx getA: () Int32 { return a }
            }
            pub class Leaf: Base {
                require b: Int32
                mut c: Int32 = 3
                pub fx getB: () Int32 { return b + c }
            }
            """
        )
        // Base's defaulted `a` comes before Leaf's required `b`: C++ cannot default it
        assertContains(h, "      Leaf(std::int32_t a_, std::int32_t b_, std::int32_t c_ = 3);")
    }

    @Test
    fun initiallyIsTheConstructorBodyAndFinallyTheDestructor() {
        val (h, s) = both(
            """
            pub class Probe {
                require pub reason: Str

                initially {
                    trace(reason)
                }

                finally {
                    trace("gone")
                }
            }
            pub class Bare {
                initially {
                    trace(1)
                }
            }
            """
        )
        assertContains(h, "      explicit Probe(kira::Str reason_);\n      Probe(const Probe&) = delete;\n      Probe& operator=(const Probe&) = delete;\n      ~Probe();\n")
        assertContains(h, "      Bare();\n")
        assertContains(s, "  Probe::Probe(kira::Str reason_)\n      : reason(std::move(reason_))\n  {\n      static_cast<void>(", "  Probe::~Probe()\n  {\n      static_cast<void>(")
        assertContains(s, "  Bare::Bare()\n  {\n      static_cast<void>(")
    }

    @Test
    fun aSubclassTakesItsSuperclassFieldsFirstAndCallsItsConstructor() {
        val (h, s) = both(
            """
            pub class Animal {
                require pub name: Str
                pub fx sound: () Str {
                    return "..."
                }
            }
            pub class Dog: Animal {
                mut tricks: Int32 = 0
                override pub fx sound: () Str {
                    return "woof"
                }
            }
            """
        )
        assertContains(
            h,
            "  class Animal\n  {\n  public:\n      explicit Animal(kira::Str name_);\n      Animal(const Animal&) = delete;\n      Animal& operator=(const Animal&) = delete;\n" +
                "      virtual ~Animal() = default;\n      [[nodiscard]] virtual kira::Str sound() const;\n      kira::Str name;\n  };",
            "  class Dog final : public Animal\n  {\n  public:\n      Dog(kira::Str name_, std::int32_t tricks_ = 0);\n",
            "      [[nodiscard]] kira::Str sound() const override;\n",
        )
        assertContains(s, "  Dog::Dog(kira::Str name_, std::int32_t tricks_)\n      : Animal(std::move(name_)), tricks(tricks_)\n  {\n  }")
    }

    @Test
    fun aVirtualDestructorComesFromTheFirstClassThatNeedsOne() {
        val h = header(
            """
            pub class A {
                pub fx f: () Int32 { return 1 }
            }
            pub class B: A {
                override pub fx f: () Int32 { return 2 }
                finally {
                    trace(2)
                }
            }
            pub class C: B {
                finally {
                    trace(3)
                }
            }
            pub trait T {
                pub fx t: () Int32;
            }
            pub class D: T {
                override pub fx t: () Int32 { return 4 }
                finally {
                    trace(4)
                }
            }
            """
        )
        // A's f is virtual: A has the virtual destructor; B's and C's then override it; D's trait has one
        assertContains(h, "      virtual ~A() = default;\n", "      ~B() override;\n", "      ~C() override;\n", "      ~D() override;\n")
        assertContains(h, "  class B : public A\n", "  class C final : public B\n")

        // a base without virtual methods stays non-polymorphic
        val plain = header(
            """
            pub class Base {
                pub fx f: () Int32 { return 1 }
            }
            pub class Leaf: Base {
                finally {
                    trace(2)
                }
            }
            """
        )
        assertContains(plain, "  class Base\n  {\n  public:\n      Base() = default;\n      Base(const Base&) = delete;\n      Base& operator=(const Base&) = delete;\n      [[nodiscard]] std::int32_t f() const;\n  };", "      ~Leaf();\n")
        assertLacks(plain, "virtual")
    }

    @Test
    fun aMethodIsVirtualOnlyWhenOverriddenAndConstUnlessMut() {
        val h = header(
            """
            pub class Base {
                mut n: Int32 = 0
                pub fx plain: () Int32 { return n }
                pub fx replaced: () Int32 { return 1 }
                pub mut fx bump: () Void { n = n + 1 }
            }
            pub class Leaf: Base {
                override pub fx replaced: () Int32 { return 2 }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] std::int32_t plain() const;\n      [[nodiscard]] virtual std::int32_t replaced() const;\n      void bump();\n",
            "      [[nodiscard]] std::int32_t replaced() const override;\n",
        )
    }

    @Test
    fun aPlainFxThatWritesItsReceiverIsRefusedSoConstFollowsTheDeclarations() {
        // Round 2 derived `const` from a class method's body, because the typer let a plain fx write its receiver
        // (D29). W2.5's MutabilityPass refuses that now (design 5.5, DECISIONS: `mut fx` marks state-changing
        // methods): those shapes are the declaration's `mut` again.
        val refused = OopTestSupport.typerErrors(
            OopTestSupport.module(
                uri,
                """
                pub fx grab: (mut v: Int32) Void {
                    v = 7
                }
                pub class Counter {
                    mut n: Int32 = 0
                    pub fx bump: () Int32 {
                        n += 1
                        return n
                    }
                    pub mut fx reset: () Void {
                        n = 0
                    }
                    pub fx again: () Int32 {
                        reset()
                        return n
                    }
                    pub fx viaThis: () Void {
                        this.n = 3
                    }
                }
                """,
            ),
        )
        assertTrue(refused.count { it.contains("[rules.mutability.this]") } == 2 && refused.any { it.contains("[rules.mutability.method]") && it.contains("again") }, refused.joinToString("\n"))
        // What still takes const away: a `mut fx`, and what the rules let a plain fx do that a const method could not
        // lower: a lambda that writes through the captured self (design 5.6, which MutabilityPass exempts), a field
        // passed `mut` (grabIt), a view of a `mut` field, which the typer lends as a MutView in any class body
        // (viewAll; allowed2 stopped compiling when this was narrowed), a trait default body calling a `mut fx` on
        // its receiver (MutabilityPass has no class to judge a trait's `this` by); and a family one of those joins.
        val (h, s) = both(
            """
            pub struct Tally {
                pub n: Int32 = 0
                pub mut fx tick: () Void {
                    n += 1
                }
            }
            pub trait Poker {
                pub mut fx poke: () Void;
                pub fx nudge: () Void {
                    poke()
                }
            }
            pub class Pet {
                pub mut fed: Int32 = 0
                pub mut fx feed: () Void {
                    fed += 1
                }
            }
            pub fx grab: (mut v: Int32) Void {
                v = 7
            }
            pub class Counter: Poker {
                mut n: Int32 = 0
                mut tally: Tally = Tally {}
                require pet: Pet
                pub mut fx bump: () Int32 {
                    n += 1
                    return n
                }
                pub mut fx reset: () Void {
                    n = 0
                }
                pub mut fx again: () Int32 {
                    reset()
                    return n
                }
                pub mut fx viaThis: () Void {
                    this.n = 3
                }
                pub mut fx tick: () Int32 {
                    tally.tick()
                    return tally.n
                }
                pub mut fx take: () Int32 {
                    grab(mut n)
                    return n
                }
                pub fx laterBump: () Fx<Tuple0, Int32> {
                    return fx() Int32 {
                        n += 1
                        return n
                    }
                }
                pub mut fx me: () Counter {
                    n += 1
                    return this
                }
                override pub mut fx poke: () Void {
                    n = 9
                }
                pub fx peek: () Int32 {
                    return n
                }
                pub fx feedPet: () Void {
                    pet.feed()
                }
                pub fx petFed: () Int32 {
                    return pet.fed
                }
                mut nums: List<Int32> = List<Int32> {}
                pub fx grabIt: () Int32 {
                    grab(mut n)
                    return n
                }
                pub fx viewAll: () Int32 {
                    return total(nums.view())
                }
            }
            pub fx total: (v: View<Int32>) Int32 {
                return 0
            }
            """
        )
        assertContains(h, "      [[nodiscard]] std::int32_t grabIt();\n      [[nodiscard]] std::int32_t viewAll();\n")
        assertContains(
            h,
            "      [[nodiscard]] std::int32_t bump();\n      void reset();\n      [[nodiscard]] std::int32_t again();\n      void viaThis();\n" +
                "      [[nodiscard]] std::int32_t tick();\n      [[nodiscard]] std::int32_t take();\n      [[nodiscard]] kira::Fn<std::int32_t()> laterBump();\n" +
                "      [[nodiscard]] kira::Rc<Counter> me();\n      void poke() override;\n      [[nodiscard]] std::int32_t peek() const;\n" +
                // a mut fx on a field that is a class runs through the pointer: this stays const
                "      void feedPet() const;\n      [[nodiscard]] std::int32_t petFed() const;\n",
            // the trait's default body calls its mut requirement on this
            "      virtual void poke() = 0;\n      virtual void nudge();\n",
        )
        assertContains(s, "  std::int32_t Counter::bump()\n", "  void Poker::nudge()\n", "  void Counter::feedPet() const\n")
        // this as a value in a non-const method needs no const_pointer_cast
        assertContains(s, "      return shared_from_this();")
    }

    @Test
    fun aClassMethodWithANonEscapingFxIsATemplateDefinedInTheHeader() {
        val e = OopTestSupport.emit(
            OopTestSupport.module(
                uri,
                """
                pub class Counter {
                    pub mut k: Int32 = 5
                    pub fx apply: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                        return f(x)
                    }
                    pub fx keep: (f: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                        return f
                    }
                }
                """,
            ),
            tweak = { program ->
                val c = program.modules.single { it.uri == uri }.members["Counter"] as ClassSymbol
                program.model.fxEscapes[c.method("apply")!!.params[0]] = false
            },
        )
        val h = e.header(uri)
        val s = e.source(uri)
        assertContains(
            h,
            "      template<typename F_f>\n        requires kira::Callable<F_f, std::int32_t, std::int32_t>\n      [[nodiscard]] std::int32_t apply(F_f&& f, std::int32_t x) const;\n",
            // an escaping one (returned, so EscapePass never clears it) is a kira::Fn
            "      [[nodiscard]] kira::Fn<std::int32_t(std::int32_t)> keep(const kira::Fn<std::int32_t(std::int32_t)>& f) const;\n",
            "  template<typename F_f>\n    requires kira::Callable<F_f, std::int32_t, std::int32_t>\n  std::int32_t Counter::apply(F_f&& f, std::int32_t x) const\n  {\n",
        )
        assertLacks(s, "Counter::apply")
        assertContains(s, "  kira::Fn<std::int32_t(std::int32_t)> Counter::keep(const kira::Fn<std::int32_t(std::int32_t)>& f) const\n")
    }

    @Test
    fun aSubclassMethodParameterNamedLikeABaseFieldIsRenamed() {
        val (h, s) = both(
            """
            pub class Base {
                mut k: Int32 = 0
                pub fx get: () Int32 { return k }
            }
            pub class Leaf: Base {
                pub fx scaled: (k: Int32) Int32 {
                    return k
                }
            }
            """
        )
        // g++ -Wshadow names a base's private field too
        assertContains(h, "      [[nodiscard]] std::int32_t scaled(std::int32_t k_p) const;\n")
        assertContains(s, "  std::int32_t Leaf::scaled(std::int32_t k_p) const\n  {\n      return k_p;\n  }")
    }

    @Test
    fun aPrivateFieldNoBodyNamesIsMaybeUnused() {
        val h = header(
            """
            pub class Holder {
                require kept: Int32
                require read: Int32
                require pub shown: Int32
                pub fx get: () Int32 { return read }
            }
            """
        )
        assertContains(h, "  private:\n      [[maybe_unused]] std::int32_t kept;\n      std::int32_t read;\n  };", "      std::int32_t shown;\n")
    }

    @Test
    fun aPrivateClassLivesInTheSourcesAnonymousNamespace() {
        val (h, s) = both(
            """
            pub trait Shape {
                pub fx area: () Int32;
            }
            class Square: Shape {
                require side: Int32
                override pub fx area: () Int32 {
                    return side
                }
            }
            pub fx make: () Shape {
                return Square { 3 }
            }
            """
        )
        assertLacks(h, "Square")
        assertContains(
            s,
            "  namespace\n  {\n    class Square final : public Shape\n    {\n    public:\n        explicit Square(std::int32_t side_);\n",
            "    std::int32_t Square::area() const\n    {\n        return side;\n    }\n  }",
            "      return std::make_shared<Square>(3);",
        )
    }

    @Test
    fun aHeaderOnlyModuleDefinesTheMembersInlineInTheHeader() {
        val e = emit(
            """
            pub class Tally {
                mut n: Int32 = 0
                pub mut fx add: (k: Int32) Void {
                    n = n + k
                }
                pub fx total: () Int32 {
                    return n
                }
            }
            """,
            options = CppOptions(lineDirectives = false, headerOnly = listOf(uri)),
        )
        val h = e.header(uri)
        assertTrue(e.module(uri).source == null, "a header-only module writes no source")
        assertContains(h, "  inline Tally::Tally(std::int32_t n_)\n      : n(n_)\n  {\n  }", "  inline std::int32_t Tally::total() const\n  {\n      return n;\n  }")
    }

    // ---- generics -------------------------------------------------------------------------------

    @Test
    fun aGenericClassIsATemplateWhoseMembersAreInTheHeader() {
        val (h, s) = both(
            """
            pub class Box<T> {
                require pub value: T
                pub fx get: () T {
                    return value
                }
            }
            pub fx boxed: (v: Int32) Box<Int32> {
                return Box<Int32> { value = v }
            }
            """
        )
        assertContains(
            h,
            "  template<typename T>\n  class Box;\n",
            "  template<typename T>\n  class Box final\n  {\n  public:\n      explicit Box(T value_);\n      Box(const Box&) = delete;\n      Box& operator=(const Box&) = delete;\n      [[nodiscard]] T get() const;\n      T value;\n  };",
            "  template<typename T>\n  Box<T>::Box(T value_)\n      : value(std::move(value_))\n  {\n  }",
            "  template<typename T>\n  T Box<T>::get() const\n  {\n      return value;\n  }",
        )
        assertContains(s, "      return std::make_shared<Box<std::int32_t>>(v);")
    }

    @Test
    fun aSubclassOfAGenericClassSpellsTheInheritedFieldsWithItsTypeArguments() {
        val (h, s) = both(
            """
            pub class Box<T> {
                require pub value: T
            }
            pub class IntBox: Box<Int32> {
                mut hits: Int32 = 0
                pub fx count: () Int32 { return hits }
            }
            """
        )
        assertContains(h, "  class IntBox final : public Box<std::int32_t>\n", "      IntBox(std::int32_t value_, std::int32_t hits_ = 0);\n")
        assertContains(s, "  IntBox::IntBox(std::int32_t value_, std::int32_t hits_)\n      : Box<std::int32_t>(value_), hits(hits_)\n")
    }

    @Test
    fun aGenericCallNamesItsTypeArgumentsAndATypeParameterReceiverIsDereferenced() {
        val s = both(
            """
            pub trait Sized {
                pub fx size: () Int32;
            }
            pub fx sizeOf<T: Sized>: (v: T) Int32 {
                return v.size()
            }
            pub class Two: Sized {
                override pub fx size: () Int32 { return 2 }
            }
            pub fx two: () Int32 {
                return sizeOf<Two>(Two {})
            }
            """
        ).first
        assertContains(s, "  template<typename T>\n  [[nodiscard]] std::int32_t sizeOf(const T& v);", "      return kira::deref(v).size();")
        val src = emit(
            """
            pub trait Sized {
                pub fx size: () Int32;
            }
            pub fx sizeOf<T: Sized>: (v: T) Int32 {
                return v.size()
            }
            pub class Two: Sized {
                override pub fx size: () Int32 { return 2 }
            }
            pub fx two: () Int32 {
                return sizeOf<Two>(Two {})
            }
            """
        ).source(uri)
        assertContains(src, "      return sizeOf<kira::Rc<Two>>(std::make_shared<Two>());")
    }

    // ---- this -----------------------------------------------------------------------------------

    @Test
    fun thisAsAValueIsSharedFromThisAndTheClassDerivesShared() {
        val (h, s) = both(
            """
            pub class Node {
                mut hits: Int32 = 0
                pub fx me: () Node {
                    return this
                }
                pub mut fx touch: () Node {
                    hits = hits + 1
                    return this
                }
            }
            """
        )
        assertContains(h, "  class Node final : public kira::Shared<Node>\n")
        // a const method sees a shared_ptr<const Node>; a class is a reference (D29)
        assertContains(s, "  kira::Rc<Node> Node::me() const\n  {\n      return std::const_pointer_cast<Node>(shared_from_this());\n  }")
        assertContains(s, "  kira::Rc<Node> Node::touch()\n  {\n      static_cast<void>(", "      return shared_from_this();\n  }")
    }

    @Test
    fun theSharedBaseIsTheRootOfTheChainAndASubclassCastsDownToItself() {
        val (h, s) = both(
            """
            pub class Base {
                pub fx id: () Int32 { return 1 }
            }
            pub class Leaf: Base {
                pub fx me: () Leaf {
                    return this
                }
                pub mut fx again: () Leaf {
                    return this
                }
            }
            """
        )
        assertContains(h, "  class Base : public kira::Shared<Base>\n", "  class Leaf final : public Base\n")
        assertContains(
            s,
            "      return std::static_pointer_cast<Leaf>(std::const_pointer_cast<Base>(shared_from_this()));",
            "      return std::static_pointer_cast<Leaf>(shared_from_this());",
        )
    }

    @Test
    fun thisInAStructMethodIsTheStructItself() {
        val s = emit(
            """
            pub struct Pt {
                pub x: Int32 = 0
                pub fx copy: () Pt {
                    return this
                }
            }
            """
        ).source(uri)
        assertContains(s, "  Pt Pt::copy() const\n  {\n      return *this;\n  }")
    }

    @Test
    fun anEscapingLambdaThatCapturesTheReceiverMakesTheClassShared() {
        val h = header(
            """
            pub class Counter {
                pub mut k: Int32 = 5
                pub fx multiplier: () Fx<Tuple1<Int32>, Int32> {
                    return fx(x: Int32) Int32 {
                        return x * k
                    }
                }
            }
            pub class Plain {
                pub mut k: Int32 = 5
                pub fx apply: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                    return f(x)
                }
            }
            """
        )
        assertContains(h, "  class Counter final : public kira::Shared<Counter>\n", "  class Plain final\n")
    }

    @Test
    fun thisInsideAnEscapingLambdaIsTheCapturedSelf() {
        val e = emit(
            """
            pub class Node {
                pub fx later: () Fx<Tuple0, Node> {
                    return fx() Node {
                        return this
                    }
                }
                pub mut fx touch: () Fx<Tuple0, Node> {
                    return fx() Node {
                        return this
                    }
                }
            }
            pub class Base {
                pub fx id: () Int32 { return 1 }
            }
            pub class Leaf: Base {
                pub fx later: () Fx<Tuple0, Leaf> {
                    return fx() Leaf {
                        return this
                    }
                }
            }
            pub class Cell<T> {
                require pub value: T
                pub mut fx later: () Fx<Tuple0, Cell<T>> {
                    return fx() Cell<T> {
                        return this
                    }
                }
            }
            """
        )
        // the lambda escapes and captures the receiver: [self = shared_from_this()] (design 5.6)
        val h = e.header(uri)
        assertContains(h, "  class Node final : public kira::Shared<Node>\n", "  class Base : public kira::Shared<Base>\n", "  class Leaf final : public Base\n", "  class Cell final : public kira::Shared<Cell<T>>\n")
        // W2.3's closure part spells the lambda; here the classes part is asked for the `this` inside it
        val m = e.program.modules.single { it.uri == uri }
        val parts = OopTestSupport.parts()
        val ctx = CppEmitContextImpl(e.program, CppOptions(lineDirectives = false), m.source, e.layout, m, "dev", parts)
        fun cls(name: String) = m.members[name] as ClassSymbol
        fun thisIn(owner: String, method: String): ThisExpr {
            var found: ThisExpr? = null
            cls(owner).method(method)!!.body!!.forEach { s -> AstTree.walk(s) { n -> if (n is ThisExpr) found = n } }
            return found ?: error("no this in $owner.$method")
        }
        fun capture(owner: String, method: String) = parts.classes.selfCapture(ctx, cls(owner), cls(owner).method(method)!!, cls(owner).decl!!)
        // `self` is shared_from_this(): a shared_ptr to the chain's root, qualified in a class template
        assertEquals("shared_from_this()", capture("Node", "later"))
        assertEquals("shared_from_this()", capture("Node", "touch"))
        assertEquals("shared_from_this()", capture("Leaf", "later"))
        assertEquals("this->shared_from_this()", capture("Cell", "later"))
        // so `this` inside the lambda is `self` cast to the class, from const in a non-mut method
        assertEquals("std::const_pointer_cast<Node>(self)", parts.classes.thisValue(ctx, thisIn("Node", "later")))
        assertEquals("self", parts.classes.thisValue(ctx, thisIn("Node", "touch")))
        assertEquals("std::static_pointer_cast<Leaf>(std::const_pointer_cast<Base>(self))", parts.classes.thisValue(ctx, thisIn("Leaf", "later")))
        assertEquals("self", parts.classes.thisValue(ctx, thisIn("Cell", "later")))
        assertTrue(ctx.diagnostics.isEmpty(), ctx.diagnostics.toString())
    }

    @Test
    fun thisAsAValueInInitiallyIsRefused() {
        val messages = unsupported(
            """
            pub class Node {
                mut me: Maybe<Node> = null
                initially {
                    keep(this)
                }
            }
            pub fx keep: (n: Node) Void {
            }
            """
        )
        assertTrue(messages.any { it.contains("this captured or used as a value in an initially or finally block of Node") }, messages.toString())
    }

    @Test
    fun aCallFromInitiallyToAMethodASubclassOverridesIsRefused() {
        // A C++ constructor runs the class's own version; the spec's init block (Kotlin) would
        // run the override. Reached through the class's own methods too.
        val program = """
            pub class Base {
                require pub name: Str
                mut seen: Int32 = 0
                initially {
                    setup()
                }
                finally {
                    greet()
                }
                pub fx greet: () Str {
                    return name
                }
                pub mut fx setup: () Void {
                    seen = 1
                    greet()
                }
            }
            """
        val messages = unsupported(
            program + """
            pub class Leaf: Base {
                override pub fx greet: () Str {
                    return "leaf"
                }
            }
            """
        )
        assertTrue(
            messages.any { it.startsWith("the call to setup in an initially or finally block of Base, which reaches Base.greet: Leaf overrides it") },
            messages.toString(),
        )
        assertTrue(messages.any { it.startsWith("the call to greet in an initially or finally block of Base: Leaf overrides it") }, messages.toString())
        // no subclass overrides greet: the calls dispatch the same on both sides
        val (h, s) = both(program)
        assertContains(h, "      Base(kira::Str name_, std::int32_t seen_ = 0);", "      ~Base();", "      void setup();")
        assertContains(s, "      static_cast<void>(setup());", "      static_cast<void>(greet());")
    }

    // ---- construction ------------------------------------------------------------------------------

    @Test
    fun aConstructionPassesEveryFieldInConstructorOrder() {
        val s = emit(
            """
            pub class Band {
                require pub lo: Int32
                pub mid: Int32 = 5
                require pub hi: Int32
                pub tail: Int32 = 9
            }
            pub fx named: () Band {
                return Band { hi = 30, lo = 10 }
            }
            pub fx all: () Band {
                return Band { 1, 2, 3, 4 }
            }
            """
        ).source(uri)
        // a skipped middle default is filled in; the trailing one is left to the C++ default
        assertContains(s, "      return std::make_shared<Band>(10, 5, 30);", "      return std::make_shared<Band>(1, 2, 3, 4);")
    }

    @Test
    fun aSkippedMiddleDefaultOfANarrowTypeCarriesItsType() {
        // make_shared deduces a bare 5 as int, and MSVC /W4 /WX stops on the narrowing inside
        // the STL (C4244): the filled-in default is typed exactly as a given literal is.
        val s = emit(
            """
            pub class Band {
                require pub lo: Int32
                pub mid: UInt8 = 5
                require pub hi: Int32
            }
            pub class Base {
                require pub name: Str
                pub level: Int8 = -1
                pub wide: Int32 = 0
            }
            pub class Leaf: Base {
                require pub small: UInt8
                pub tail: Int64 = 3
            }
            pub fx band: () Band {
                return Band { lo = 1, hi = 2 }
            }
            pub fx leaf: () Leaf {
                return Leaf { name = "x", small = 200 }
            }
            """
        ).source(uri)
        assertContains(
            s,
            "      return std::make_shared<Band>(1, std::uint8_t{5}, 2);",
            "      return std::make_shared<Leaf>(\"x\", std::int8_t{-1}, 0, std::uint8_t{200});",
        )
    }

    @Test
    fun twoImpureArgumentsAreEvaluatedAsWrittenIntoTemporaries() {
        val s = emit(
            """
            mut calls: Int32 = 0
            fx first: () Int32 {
                calls = calls + 1
                return calls
            }
            fx second: () Int32 {
                calls = calls + 10
                return calls
            }
            pub class Pair {
                require pub a: Int32
                require pub b: Int32
            }
            pub fx make: () Pair {
                return Pair { b = second(), a = first() }
            }
            """
        ).source(uri)
        // Nothing of the body's frame is named, so the lambda captures nothing (it may then stand outside a block scope too).
        assertContains(s, "      return []() -> kira::Rc<Pair> { const std::int32_t t0_Arg_ = second(); const std::int32_t t1_Arg_ = first(); return std::make_shared<Pair>(t1_Arg_, t0_Arg_); }();")
    }

    // ---- construction order: every operand that is not PURE, as W2.3's call hoister orders one (second-class round 2)

    private val renaming = """
        pub class Named {
            require pub mut name: Str

            pub mut fx rename: () Int32 {
                name = "new"
                return 7
            }
        }

        pub class Pair {
            require pub a: Str
            require pub n: Int32
        }
    """.trimIndent()

    @Test
    fun aFieldReadBeforeAnImpureSiblingIsCopiedFirst() {
        // ctorwrong2: make_shared forwarded b->name by reference and read it after c->rename() (c = b) had run:
        // `new` on g++, zig and MSVC, where Kira's left to right gives `old`.
        val s = emit(
            """
            $renaming

            pub fx pairOf: (b: Named, c: Named) Pair {
                return Pair { b.name, c.rename() }
            }
            """
        ).source(uri)
        assertContains(s, "      return [&]() -> kira::Rc<Pair> { const kira::Str t0_Arg_ = b->name; const std::int32_t t1_Arg_ = c->rename(); return std::make_shared<Pair>(t0_Arg_, t1_Arg_); }();")
    }

    @Test
    fun aConstRefParameterBeforeAnImpureSiblingIsCopiedFirst() {
        // ctororder: s, a const& bound to c.item.label, was read inside make_shared after h.reset() freed the Item
        // (MSVC ASan heap-use-after-free in the Str copy). Named fields are ordered as written.
        val s = emit(
            """
            $renaming

            pub fx later: (s: Str, h: Named) Pair {
                return Pair { n = h.rename(), a = s }
            }
            """
        ).source(uri)
        assertContains(s, "      return [&]() -> kira::Rc<Pair> { const std::int32_t t0_Arg_ = h->rename(); const kira::Str t1_Arg_ = s; return std::make_shared<Pair>(t1_Arg_, t0_Arg_); }();")
    }

    @Test
    fun anImplicitFieldBeforeAnImpureSiblingIsCopiedFirst() {
        // ctorthis: `Pair { name, tree.clear() }` in a Kid method read this->name after clear freed the Kid
        // (freed heap bytes on g++ and zig, MSVC ASan heap-use-after-free).
        val s = emit(
            """
            pub class Pair {
                require pub a: Str
                require pub n: Int32
            }

            pub class Tree {
                pub mut kid: Maybe<Kid> = null

                pub mut fx clear: () Int32 {
                    kid = null
                    return 7
                }
            }

            pub class Kid {
                require pub name: Str
                require pub tree: Tree

                pub fx leave: () Pair {
                    return Pair { name, tree.clear() }
                }
            }
            """
        ).source(uri)
        assertContains(s, "      return [&]() -> kira::Rc<Pair> { const kira::Str t0_Arg_ = name; const std::int32_t t1_Arg_ = tree->clear(); return std::make_shared<Pair>(t0_Arg_, t1_Arg_); }();")
    }

    @Test
    fun aPureOperandBesideOneImpureOneIsNotSpilled() {
        // One impure operand and PURE siblings (a literal, a local that is not mut, a by-value parameter) need no order.
        val s = emit(
            """
            $renaming

            pub class Trio {
                require pub a: Str
                require pub n: Int32
                require pub k: Int32
            }

            pub fx trio: (h: Named, k: Int32) Trio {
                label: Str = "x"
                return Trio { label, h.rename(), k }
            }
            """
        ).source(uri)
        assertContains(s, "      return std::make_shared<Trio>(label, h->rename(), k);")
    }

    // ---- field defaults run in Kira's order, inside the constructor (R-D and OQ-2, second-class round 3) -------

    private val counting = """
        pub mut counter: Int32 = 0

        pub fx next: () Int32 {
            counter += 1
            return counter
        }
    """.trimIndent()

    @Test
    fun aSkippedMiddleDefaultThatIsNotPureRunsInsideTheConstructor() {
        // Round 2 filled the skipped `b = seed` in at the call and spilled it after the given values. A default that
        // is not PURE is no operand of the call now: its parameter is an empty std::optional, and the field's own
        // mem-initializer reads seed, after every argument (the given values first, as Kira has it).
        val (h, s) = both(
            """
            pub mut seed: Int32 = 1

            pub fx bumpSeed: () Int32 {
                seed += 10
                return seed
            }

            pub class Duo {
                require pub a: Int32
                pub b: Int32 = seed
                require pub c: Int32
            }

            pub fx duo: () Duo {
                return Duo { a = 5, c = bumpSeed() }
            }
            """
        )
        assertContains(h, "      Duo(std::int32_t a_, std::optional<std::int32_t> b_, std::int32_t c_);")
        assertContains(
            s,
            "  Duo::Duo(std::int32_t a_, std::optional<std::int32_t> b_, std::int32_t c_)\n" +
                "      : a(a_), b(b_.has_value() ? std::move(*b_) : static_cast<std::int32_t>(seed)), c(c_)\n",
            "      return std::make_shared<Duo>(5, std::nullopt, bumpSeed());",
        )
    }

    @Test
    fun trailingDefaultsThatAreNotPureAreNoCppDefaultArguments() {
        // ctordefaults2: `Two {}` with a and b = next() left both to C++ default arguments, which C++ evaluates in no
        // order (3 2 1 on g++ and MSVC, 1 2 3 on zig for three). Each is now an empty optional the constructor fills
        // in declaration order; a PURE default stays a default argument. A given value fills its optional.
        val (h, s) = both(
            """
            $counting

            pub class Two {
                pub a: Int32 = next()
                pub b: Int32 = next()
                pub c: Int32 = 7
            }

            pub fx none: () Two {
                return Two {}
            }

            pub fx middle: () Two {
                return Two { b = 9 }
            }
            """
        )
        assertContains(h, "      Two(std::optional<std::int32_t> a_ = std::nullopt, std::optional<std::int32_t> b_ = std::nullopt, std::int32_t c_ = 7);")
        assertContains(
            s,
            "      : a(a_.has_value() ? std::move(*a_) : static_cast<std::int32_t>(next())), b(b_.has_value() ? std::move(*b_) : static_cast<std::int32_t>(next())), c(c_)\n",
            "      return std::make_shared<Two>();",
            "      return std::make_shared<Two>(std::nullopt, std::make_optional<std::int32_t>(9));",
        )
    }

    @Test
    fun aSubclassDefaultRunsAfterTheSuperclassInitially() {
        // OQ-2, the user's answer (Kotlin's order): Base's defaults and initially, then Kid's own defaults. As a C++
        // default argument k ran at the call, before Base's constructor: seen=5, k=4 on every compiler; Kira 4, 5.
        // Base's constructor is the first mem-initializer and C++ runs k's after it.
        val (h, s) = both(
            """
            $counting

            pub class Base {
                pub mut seen: Int32 = 0

                initially {
                    seen = next()
                }
            }

            pub class Kid: Base {
                pub k: Int32 = next()
                pub mut after: Int32 = 0

                initially {
                    after = next()
                }
            }

            pub fx kid: () Kid {
                return Kid {}
            }
            """
        )
        assertContains(h, "      Kid(std::int32_t seen_ = 0, std::optional<std::int32_t> k_ = std::nullopt, std::int32_t after_ = 0);")
        assertContains(
            s,
            "  Kid::Kid(std::int32_t seen_, std::optional<std::int32_t> k_, std::int32_t after_)\n" +
                "      : Base(seen_), k(k_.has_value() ? std::move(*k_) : static_cast<std::int32_t>(next())), after(after_)\n",
            "      return std::make_shared<Kid>();",
        )
    }

    @Test
    fun aSuperclassDefaultThatIsNotPureIsForwardedAsItsOptional() {
        // ctordefaults3: Base's a and Kid's b, both next(). Kid's constructor hands Base the optional it was given.
        val s = emit(
            """
            $counting

            pub class Low {
                pub a: Int32 = next()
            }

            pub class High: Low {
                pub b: Int32 = next()
            }

            pub fx high: () High {
                return High { a = 4 }
            }
            """
        ).source(uri)
        assertContains(
            s,
            "  High::High(std::optional<std::int32_t> a_, std::optional<std::int32_t> b_)\n      : Low(std::move(a_)), b(b_.has_value() ? std::move(*b_) : static_cast<std::int32_t>(next()))\n",
            "      return std::make_shared<High>(std::make_optional<std::int32_t>(4));",
        )
    }

    @Test
    fun aNullGivenToAMaybeFieldWhoseDefaultIsNotPureIsWrapped() {
        // `kira::none` converts to any std::optional, the parameter's own included: passed bare, it would leave the
        // field out and run the default. Given, it is wrapped explicitly.
        val s = emit(
            """
            $counting

            fx pick: () Maybe<Str> {
                counter += 100
                return "picked"
            }

            pub class Slot {
                pub mut item: Maybe<Str> = pick()
            }

            pub fx empty: () Slot {
                return Slot { item = null }
            }
            """
        ).source(uri)
        assertContains(s, "      return std::make_shared<Slot>(std::make_optional<", ">(kira::none));")
    }

    @Test
    fun noEmittedConstructorHasADefaultArgumentThatIsNotPure() {
        // 40-round3 6.4's static check, over the three OOP goldens: a constructor's default argument is a literal or
        // an empty value (no call C++ could run out of order), or std::nullopt for a default the constructor runs.
        val defaulted = Regex("""^\s+(explicit )?[A-Z]\w*\((.*_ = .*)\);$""")
        val default = Regex("""\w+_ = ([^,]+)""")
        var seen = 0
        for (name in listOf("chain", "sender", "classes")) {
            val headers = OopTestSupport.emitCase(OopTestSupport.case(name)).filter { it.relative.endsWith(".kira.hxx") }
            headers.flatMap { it.text.lines() }.mapNotNull { defaulted.find(it)?.groupValues?.get(2) }.forEach { params ->
                default.findAll(params).forEach { m ->
                    seen += 1
                    val value = m.groupValues[1].trim()
                    assertTrue(value == "std::nullopt" || !value.contains("("), "$name: the constructor default $value in ($params)")
                }
            }
        }
        assertTrue(seen >= 4, "found $seen constructor defaults")
    }

    // ---- this under construction or destruction (second-class round 2) --------------------------------------

    @Test
    fun thisAsAValueInAMethodAnInitiallyRunsIsRefused() {
        // initcall2: initially calls join, and join hands this on: shared_from_this() threw std::bad_weak_ptr
        // on g++ and zig (MSVC 0xC0000409), since no kira::Rc owns an object under construction.
        val messages = unsupported(
            """
            pub class Reg {
                pub mut n: Int32 = 0
            }

            pub fx enroll: (r: Reg, n: Node) Void {
            }

            pub class Node {
                require pub name: Str
                require pub reg: Reg

                initially {
                    join()
                }

                pub fx join: () Void {
                    enroll(reg, this)
                }
            }
            """
        )
        assertTrue(messages.any { it.startsWith("this as a value in Node.join, which the initially block of Node runs: C++ has no shared_ptr to an object under construction") }, messages.joinToString("\n"))
    }

    @Test
    fun thisAsAValueInAMethodAFinallyReachesThroughAnotherIsRefused() {
        // fincall2, one call further: finally calls bury, bury calls say, and say hands this on.
        val messages = unsupported(
            """
            pub fx show: (n: Node) Void {
            }

            pub class Node {
                require pub name: Str

                finally {
                    bury()
                }

                pub fx bury: () Void {
                    say()
                }

                pub fx say: () Void {
                    show(this)
                }
            }
            """
        )
        assertTrue(
            messages.any { it.startsWith("this as a value in Node.say, which the finally block of Node runs (through its call to bury): C++ has no shared_ptr to an object under destruction") },
            messages.joinToString("\n"),
        )
    }

    @Test
    fun anEscapingLambdaThatCapturesThisInAMethodAnInitiallyRunsIsRefused() {
        // initlam: initially calls hook, and hook stores a lambda capturing self = shared_from_this().
        val messages = unsupported(
            """
            pub class Reg {
                pub mut hook: Maybe<Fx<Tuple0, Str>> = null
            }

            pub class Node {
                require pub name: Str
                require pub reg: Reg

                initially {
                    hook()
                }

                pub fx describe: () Str {
                    return name
                }

                pub fx hook: () Void {
                    reg.hook = fx() Str {
                        return describe()
                    }
                }
            }
            """
        )
        assertTrue(messages.any { it.startsWith("a lambda that escapes and captures this (self = shared_from_this()) in Node.hook, which the initially block of Node runs") }, messages.joinToString("\n"))
    }

    @Test
    fun thisAsAValueInAnOverrideATraitDefaultCallsFromInitiallyIsRefused() {
        // initially calls a trait default, which calls a requirement on this: C++ dispatches it to the class's own override.
        val messages = unsupported(
            """
            pub fx keep: (n: Node) Str {
                return "k"
            }

            pub trait Greeter {
                pub fx id: () Str;
                pub fx greet: () Str {
                    return id()
                }
            }

            pub class Node: Greeter {
                require pub name: Str

                initially {
                    greet()
                }

                override pub fx id: () Str {
                    return keep(this)
                }
            }
            """
        )
        assertTrue(messages.any { it.startsWith("this as a value in Node.id, which the initially block of Node runs (through its call to greet)") }, messages.joinToString("\n"))
    }

    @Test
    fun thisAsAValueInAMethodNoInitiallyOrFinallyRunsIsKept() {
        // Only what those blocks may run is refused: a method reading a field from initially, and this as a value elsewhere.
        val (h, s) = both(
            """
            pub class Node {
                require pub name: Str
                pub mut size: Int32 = 0

                initially {
                    measure()
                }

                pub mut fx measure: () Void {
                    size = 1
                }

                pub fx me: () Node {
                    return this
                }
            }
            """
        )
        assertContains(h, "  class Node final : public kira::Shared<Node>\n")
        assertContains(s, "      return std::const_pointer_cast<Node>(shared_from_this());")
    }

    @Test
    fun aNarrowLiteralArgumentCarriesItsType() {
        val s = emit(
            """
            pub class Cell {
                require pub small: UInt8
                require pub big: Int64
                require pub plain: Int32
            }
            pub fx make: () Cell {
                return Cell { 200, -1, 7 }
            }
            """
        ).source(uri)
        assertContains(s, "      return std::make_shared<Cell>(std::uint8_t{200}, std::int64_t{-1}, 7);")
    }

    @Test
    fun aRefIsAnRcOfABoxAndAWeakUpgradesThroughLock() {
        val (h, s) = both(
            """
            pub class Pet {
                require pub name: Str
            }
            pub fx counter: () Ref<Int32> {
                return Ref<Int32> { value = 0 }
            }
            pub fx maybePet: (w: Weak<Pet>) Maybe<Pet> {
                return w.upgrade()
            }
            """
        )
        assertContains(h, "  [[nodiscard]] kira::Rc<kira::Box<std::int32_t>> counter();", "  [[nodiscard]] kira::Maybe<kira::Rc<Pet>> maybePet(const kira::Weak<Pet>& w);")
        assertContains(s, "      return std::make_shared<kira::Box<std::int32_t>>(0);")
    }

    @Test
    fun maybeOfAClassIsANullableRc() {
        val h = header(
            """
            pub class Pet {
                require pub name: Str
            }
            pub class Owner {
                mut pet: Maybe<Pet> = null
                pub fx get: () Maybe<Pet> {
                    return pet
                }
            }
            """
        )
        assertContains(h, "      explicit Owner(kira::Maybe<kira::Rc<Pet>> pet_ = kira::none);", "      [[nodiscard]] kira::Maybe<kira::Rc<Pet>> get() const;")
    }

    // ---- refusals ------------------------------------------------------------------------------------

    @Test
    fun aBodylessClassMethodIsRefusedUnlessASubclassOverridesIt() {
        val messages = unsupported(
            """
            pub class Handler {
                pub fx process: (data: Str) Void;
            }
            """
        )
        assertEquals(listOf("'Handler.process' without a body, a method implemented at instantiation (D43) is not lowered yet"), messages)

        val (h, _) = both(
            """
            pub class Shape {
                pub fx area: () Int32;
            }
            pub class Square: Shape {
                override pub fx area: () Int32 { return 4 }
            }
            pub fx make: () Shape {
                return Square {}
            }
            """
        )
        assertContains(h, "      [[nodiscard]] virtual std::int32_t area() const = 0;\n")
    }

    @Test
    fun constructingAClassThatLeavesAMethodWithoutABodyIsRefused() {
        val messages = unsupported(
            """
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub class Blob: Shape {
                pub fx other: () Int32 { return 1 }
            }
            pub fx make: () Blob {
                return Blob {}
            }
            """
        )
        assertTrue(messages.any { it.startsWith("constructing Blob, which leaves Shape.area without a body") }, messages.toString())
    }

    @Test
    fun aTraitMethodMetOnlyThroughAnUnrelatedSuperclassMethodIsRefused() {
        val messages = unsupported(
            """
            pub class A {
                pub fx id: () Str { return "a" }
            }
            pub trait Named {
                pub fx id: () Str;
            }
            pub class B: A, Named {
                pub fx other: () Int32 { return 1 }
            }
            """
        )
        assertTrue(messages.any { it.startsWith("B implementing Named.id through A.id, which C++ does not take as its override") }, messages.toString())
    }

    @Test
    fun aGenericVirtualMethodIsRefused() {
        val messages = unsupported(
            """
            pub trait Mapper {
                pub fx map<U>: (u: U) U;
            }
            """
        )
        assertEquals(listOf("the generic virtual method 'Mapper.map' (a C++ virtual cannot be a template) is not lowered yet"), messages)
    }

    @Test
    fun boxingAStructIntoATraitValueIsRefused() {
        // The typer refuses it first (types.assign.struct-to-trait); the part refuses it too, in case a coercion reaches it.
        val e = emit(
            """
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub struct Sq: Shape {
                pub side: Int32 = 2
                override pub fx area: () Int32 { return side }
            }
            pub fx one: () Int32 { return 1 }
            """
        )
        val m = e.program.modules.single { it.uri == uri }
        val sq = m.members["Sq"] as ClassSymbol
        val shape = m.members["Shape"] as TraitSymbol
        val ctx = CppEmitContextImpl(e.program, CppOptions(lineDirectives = false), m.source, e.layout, m, "dev", OopTestSupport.parts())
        val literal = IntegerLiteral(1)
        val out = CppClassEmitter().upcast(ctx, literal, Coercion.Upcast(KType.Nominal(sq), KType.Nominal(shape)), "s")
        assertEquals("s", out)
        assertEquals(1, ctx.diagnostics.count { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE && it.message.startsWith("boxing struct Sq into a Shape value (D43") }, ctx.diagnostics.toString())
        // a class upcast is implicit
        val pet = CppClassEmitter().upcast(ctx, literal, Coercion.Upcast(KType.Nominal(shape), KType.Nominal(shape)), "p")
        assertEquals("p", pet)
    }

    @Test
    fun theGenericsPartSpellsTypeArgumentsAndTheDefaultsRefuse() {
        val e = emit("pub fx one: () Int32 { return 1 }")
        val m = e.program.modules.single { it.uri == uri }
        val ctx = CppEmitContextImpl(e.program, CppOptions(lineDirectives = false), m.source, e.layout, m, "dev", CppEmitParts())
        val node = IntegerLiteral(1)
        assertEquals("<std::int32_t, kira::Str>", CppGenericsEmitter.typeArguments(ctx, node, listOf(KType.INT32, KType.Str)))
        assertEquals("", CppGenericsEmitter.typeArguments(ctx, node, emptyList()))
        // the contract: null for any receiver that is not a type parameter, one the model never typed included
        assertEquals(null, CppGenericsEmitter.receiver(ctx, node, "x"))
        // the default generics part (Plain) and classes part (Unsupported) fail loudly
        CppEmitParts().generics.typeArguments(ctx, node, listOf(KType.INT32))
        CppEmitParts().classes.thisValue(ctx, net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr())
        assertEquals(2, ctx.diagnostics.count { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }, ctx.diagnostics.toString())
    }

    // ---- the receiver in an escaping lambda, and the const fact behind it -----------------------------

    @Test
    fun theSelfReceiverCastsDownToTheSubclassAndFollowsTheDerivedConst() {
        // W2.3's selfPointer decides const from the modifier; the part gives it the derived fact
        // (a plain fx whose lambda writes is not const) and the receiver spelled from it.
        val e = emit(
            """
            pub class Base {
                mut n: Int32 = 0
                pub fx get: () Int32 { return n }
            }
            pub class Sub: Base {
                mut k: Int32 = 0
                pub mut fx reset: () Void { k = 100 }
                pub fx later: () Fx<Tuple0, Int32> {
                    return fx() Int32 {
                        k += 1
                        return k
                    }
                }
                pub fx laterReset: () Fx<Tuple0, Int32> {
                    return fx() Int32 {
                        reset()
                        return k
                    }
                }
                pub fx peek: () Fx<Tuple0, Int32> {
                    return fx() Int32 { return k }
                }
                pub fx me: () Sub { return this }
            }
            """
        )
        val m = e.program.modules.single { it.uri == uri }
        val base = m.members["Base"] as ClassSymbol
        val sub = m.members["Sub"] as ClassSymbol
        val ctx = CppEmitContextImpl(e.program, CppOptions(lineDirectives = false), m.source, e.layout, m, "dev", OopTestSupport.parts())
        val part = OopTestSupport.parts().classes
        fun method(c: ClassSymbol, name: String) = c.methods.single { it.name == name }
        assertEquals(false, part.isConstMethod(ctx, method(sub, "later")))
        assertEquals(false, part.isConstMethod(ctx, method(sub, "laterReset")))
        assertEquals(false, part.isConstMethod(ctx, method(sub, "reset")))
        assertEquals(true, part.isConstMethod(ctx, method(sub, "peek")))
        assertEquals(true, part.isConstMethod(ctx, method(base, "get")))
        assertEquals("std::static_pointer_cast<Sub>(self)", part.selfReceiver(ctx, sub, method(sub, "later")))
        assertEquals("std::static_pointer_cast<Sub>(self)", part.selfReceiver(ctx, sub, method(sub, "laterReset")))
        assertEquals("std::static_pointer_cast<const Sub>(self)", part.selfReceiver(ctx, sub, method(sub, "peek")))
        assertEquals("self", part.selfReceiver(ctx, base, method(base, "get")))
        // the default (no classes part) reads the modifier, as W2.3 does on its own
        val plain = CppEmitParts().classes
        assertEquals(true, plain.isConstMethod(ctx, method(sub, "later")))
        assertEquals("std::static_pointer_cast<const Sub>(self)", plain.selfReceiver(ctx, sub, method(sub, "later")))
        assertEquals("std::static_pointer_cast<Sub>(self)", plain.selfReceiver(ctx, sub, method(sub, "reset")))
        assertEquals("self", plain.selfReceiver(ctx, base, method(base, "get")))
        // the fake closure emitter spells the writes through it, as the real one is to
        val s = e.source(uri)
        assertContains(
            s,
            "  kira::Fn<std::int32_t()> Sub::later()\n  {\n      return [self = shared_from_this()]() -> std::int32_t { static_cast<void>(std::static_pointer_cast<Sub>(self)->k += 1); return std::static_pointer_cast<Sub>(self)->k; };",
            "  kira::Fn<std::int32_t()> Sub::laterReset()\n  {\n      return [self = shared_from_this()]() -> std::int32_t { static_cast<void>(std::static_pointer_cast<Sub>(self)->reset()); return std::static_pointer_cast<Sub>(self)->k; };",
            "  kira::Fn<std::int32_t()> Sub::peek() const\n  {\n      return [self = shared_from_this()]() -> std::int32_t { return std::static_pointer_cast<const Sub>(self)->k; };",
        )
    }

    // ---- const across an override family with more than one base ------------------------------------

    @Test
    fun aMethodTwoTraitsDeclareLosesConstInBothWhenTheOverrideWrites() {
        // C++ takes C::id() as the override of A::id and B::id at once, so the family is decided
        // together; the typer links only one (FnSymbol.overrides), and each says `mut` itself
        // (types.override.signature: an override is `mut` exactly when what it overrides is).
        val (h, s) = both(
            """
            pub trait A {
                pub mut fx id: () Int32;
            }
            pub trait B {
                pub mut fx id: () Int32;
            }
            pub class C: A, B {
                mut n: Int32 = 0
                override pub mut fx id: () Int32 {
                    n += 1
                    return n
                }
            }
            """
        )
        assertContains(
            h,
            "  class A\n  {\n  public:\n      virtual ~A() = default;\n      [[nodiscard]] virtual std::int32_t id() = 0;\n  };",
            "  class B\n  {\n  public:\n      virtual ~B() = default;\n      [[nodiscard]] virtual std::int32_t id() = 0;\n  };",
            "      [[nodiscard]] std::int32_t id() override;",
        )
        assertContains(s, "  std::int32_t C::id()\n  {")
        assertLacks(h, "id() const")
    }

    @Test
    fun aMethodThatOverridesASuperclassAndImplementsATraitDecidesConstForBoth() {
        // Dog.tag overrides Animal.tag (the typer's link) and implements Tagged.tag (the C++ override):
        // Tagged's pure virtual follows the family too, or Dog is abstract.
        val h = header(
            """
            pub trait Tagged {
                pub mut fx tag: () Int32;
            }
            pub class Animal {
                pub mut n: Int32 = 0
                pub mut fx tag: () Int32 {
                    return 1
                }
            }
            pub class Dog: Animal, Tagged {
                override pub mut fx tag: () Int32 {
                    n += 1
                    return n
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual std::int32_t tag() = 0;",
            "      [[nodiscard]] virtual std::int32_t tag();",
            "      [[nodiscard]] std::int32_t tag() override;",
        )
        assertLacks(h, "tag() const")
    }

    @Test
    fun aFamilyReachesThroughATraitsParentAndAnUnwritingSiblingStaysConst() {
        // Base.m implements P.m through T: P; a writing override in Sub takes the whole chain
        // with it, while an unrelated trait method of another name keeps const.
        val h = header(
            """
            pub trait P {
                pub mut fx m: () Int32;
                pub fx other: () Int32;
            }
            pub trait T: P {
            }
            pub class Base: T {
                pub mut n: Int32 = 0
                override pub mut fx m: () Int32 { return n }
                override pub fx other: () Int32 { return n }
            }
            pub class Sub: Base {
                override pub mut fx m: () Int32 {
                    n += 1
                    return n
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual std::int32_t m() = 0;",
            "      [[nodiscard]] virtual std::int32_t other() const = 0;",
            "      [[nodiscard]] std::int32_t m() override;",
            "      [[nodiscard]] std::int32_t other() const override;",
        )
        assertLacks(h, "m() const")
    }

    // ---- a struct's inherited trait defaults --------------------------------------------------------

    @Test
    fun aStructTakesATraitsDefaultBodyAsItsOwnMember() {
        // Static dispatch (D1): `q.twice()` and `kira::deref(s).twice()` under a generic bound
        // need a member of the struct, so the default body is one, const as its body allows.
        val (h, s) = both(
            """
            pub trait Shape {
                pub fx area: () Int32;
                pub fx twice: () Int32 {
                    return area()
                }
                pub mut fx grow: () Void;
                pub fx growTwice: () Void {
                    grow()
                }
                pub mut fx reset: () Void {
                    grow()
                }
            }
            pub struct Square: Shape {
                pub side: Int32 = 3
                override pub fx area: () Int32 {
                    return side
                }
                override pub mut fx grow: () Void {
                    side = 4
                }
            }
            pub fx twiceOf<T: Shape>: (s: T) Int32 {
                return s.twice()
            }
            """
        )
        assertContains(
            h,
            "  struct Square\n  {\n      std::int32_t side = 3;\n\n      [[nodiscard]] std::int32_t area() const;\n      void grow();\n      [[nodiscard]] std::int32_t twice() const;\n      void growTwice();\n      void reset();\n  };",
            "      [[nodiscard]] virtual std::int32_t twice() const;",
        )
        assertContains(
            s,
            "  std::int32_t Square::twice() const\n  {\n      return area();\n  }",
            "  void Square::growTwice()\n  {\n      static_cast<void>(grow());\n  }",
            "  void Square::reset()\n  {\n      static_cast<void>(grow());\n  }",
            "  std::int32_t Shape::twice() const\n  {\n      return area();\n  }",
        )
    }

    @Test
    fun aStructInheritsTheNearestDefaultAndNotOneANearerTraitReabstracts() {
        val (h, s) = both(
            """
            pub trait P {
                pub fx a: () Int32 { return 1 }
                pub fx b: () Int32 { return 2 }
                pub fx c: () Int32 { return 3 }
            }
            pub trait T: P {
                override pub fx a: () Int32 { return 10 }
                override pub fx b: () Int32;
            }
            pub struct S: T {
                pub x: Int32 = 0
                override pub fx b: () Int32 { return 20 }
            }
            """
        )
        assertContains(h, "  struct S\n  {\n      std::int32_t x = 0;\n\n      [[nodiscard]] std::int32_t b() const;\n      [[nodiscard]] std::int32_t a() const;\n      [[nodiscard]] std::int32_t c() const;\n  };")
        assertContains(s, "  std::int32_t S::a() const\n  {\n      return 10;\n  }", "  std::int32_t S::c() const\n  {\n      return 3;\n  }")
        assertLacks(s, "S::b() const\n  {\n      return 2;")
    }

    @Test
    fun aGenericStructDefinesItsInheritedDefaultInTheHeader() {
        val h = header(
            """
            pub trait Shape {
                pub fx area: () Int32;
                pub fx twice: () Int32 { return area() }
            }
            pub struct Box<T>: Shape {
                pub value: T
                pub side: Int32 = 1
                override pub fx area: () Int32 { return side }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] std::int32_t area() const;\n      [[nodiscard]] std::int32_t twice() const;\n  };",
            "  template<typename T>\n  std::int32_t Box<T>::twice() const\n  {\n      return area();\n  }",
        )
    }

    @Test
    fun aStructInheritingAGenericTraitsDefaultIsRefused() {
        val messages = unsupported(
            """
            pub trait Source<T> {
                pub fx get: () T;
                pub fx again: () T { return get() }
            }
            pub struct Five: Source<Int32> {
                pub v: Int32 = 5
                override pub fx get: () Int32 { return v }
            }
            """
        )
        assertEquals(listOf("struct Five inheriting the default body of Source<Int32>.again from a generic trait (override again in Five) is not lowered yet"), messages)
    }

    @Test
    fun theDefaultClassesPartRefusesAStructsInheritedDefaults() {
        val e = OopTestSupport.emit(
            OopTestSupport.module(
                uri,
                """
                pub trait Shape {
                    pub fx area: () Int32;
                    pub fx twice: () Int32 { return area() }
                }
                pub struct Square: Shape {
                    pub side: Int32 = 3
                    override pub fx area: () Int32 { return side }
                }
                """,
            ),
            parts = CppEmitParts(stmts = OopTestSupport.FakeStmtEmitter, exprs = OopTestSupport.FakeExprEmitter),
        )
        val messages = e.module(uri).diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message }
        assertTrue("the trait methods struct 'Square' inherits is not lowered yet" in messages, messages.toString())
    }

    // ---- a skipped default of a braced type ------------------------------------------------------------

    @Test
    fun aSkippedMiddleDefaultOfAnArrTypeCarriesItsType() {
        // make_shared deduces nothing from a bare braced list (no compiler takes it), so the
        // filled-in default is the typed literal a given one is; a subclass's construction
        // meets the same default in its base.
        val s = emit(
            """
            pub class Frame {
                pub mut buf: Arr<UInt8, 4> = [1, 2, 3, 4]
                require pub id: Int32
            }
            pub class Tagged: Frame {
                require pub tag: Int32
            }
            pub fx frame: () Frame {
                return Frame { id = 7 }
            }
            pub fx tagged: () Tagged {
                return Tagged { id = 7, tag = 9 }
            }
            """
        ).source(uri)
        assertContains(
            s,
            "      return std::make_shared<Frame>(std::array<std::uint8_t, 4>{1, 2, 3, 4}, 7);",
            "      return std::make_shared<Tagged>(std::array<std::uint8_t, 4>{1, 2, 3, 4}, 7, 9);",
        )
    }

    // ---- one name from two traits at once ------------------------------------------------------------

    private val siblings = """
        pub trait Abs {
            pub fx f: () Int32;
        }
        pub trait Def {
            pub fx f: () Int32 {
                return 7
            }
        }
    """

    @Test
    fun aSiblingTraitsDefaultSatisfiesAnothersRequirementInAStruct() {
        // Abs and Def are unrelated: Abs.f is a requirement, Def.f the one body of the name,
        // so the struct takes Def's as its member (the typer resolves s.f() to it).
        val (h, s) = both(
            siblings + """
            pub struct S: Abs, Def {
                pub v: Int32 = 0
            }
            pub fx viaAbs<T: Abs>: (s: T) Int32 {
                return s.f()
            }
            """
        )
        assertContains(h, "  struct S\n  {\n      std::int32_t v = 0;\n\n      [[nodiscard]] std::int32_t f() const;\n  };")
        assertContains(s, "  std::int32_t S::f() const\n  {\n      return 7;\n  }")
    }

    @Test
    fun aSiblingTraitsDefaultSatisfiesAnothersRequirementInAClassByAForwarder() {
        // C++ ties Abs::f and Def::f to nothing: C would stay abstract and c.f() ambiguous.
        // C declares f, overriding both at once, and forwards to Def's body; the two are then
        // one const family, so a mut requirement takes the const off the default it ties to.
        val (h, s) = both(
            siblings + """
            pub trait MutAbs {
                pub mut fx g: () Int32;
            }
            pub trait MutDef {
                pub fx g: () Int32 {
                    return 8
                }
            }
            pub class C: Abs, Def, MutAbs, MutDef {
            }
            pub fx make: () C {
                return C {}
            }
            """
        )
        assertContains(
            h,
            "  class C final : public Abs, public Def, public MutAbs, public MutDef\n  {\n  public:\n      C() = default;\n      C(const C&) = delete;\n      C& operator=(const C&) = delete;\n      [[nodiscard]] std::int32_t f() const override;\n      [[nodiscard]] std::int32_t g() override;\n  };",
            "  class MutDef\n  {\n  public:\n      virtual ~MutDef() = default;\n      [[nodiscard]] virtual std::int32_t g();\n  };",
        )
        assertContains(
            s,
            "  std::int32_t C::f() const\n  {\n      return Def::f();\n  }",
            "  std::int32_t C::g()\n  {\n      return MutDef::g();\n  }",
            "      return std::make_shared<C>();",
        )
    }

    @Test
    fun twoSiblingDefaultsForOneNameAreRefusedInAClassAndAStruct() {
        val messages = unsupported(
            """
            pub trait One {
                pub fx f: () Int32 { return 1 }
            }
            pub trait Two {
                pub fx f: () Int32 { return 2 }
            }
            pub class C: One, Two {
            }
            pub struct S: One, Two {
                pub v: Int32 = 0
            }
            """
        )
        assertTrue(messages.any { it.startsWith("C inheriting f from both One and Two (C++ needs one final overrider") }, messages.toString())
        assertTrue(messages.any { it.startsWith("struct S inheriting f from both One and Two (two default bodies") }, messages.toString())
    }

    @Test
    fun aStructReachingOneDefaultThroughTwoOverridingPathsIsRefused() {
        // The class of this shape is refused (aDiamondWithTwoOverridersIsRefused); a struct's
        // copy would silently take the first path's body.
        val messages = unsupported(
            """
            pub trait A {
                pub fx f: () Int32 { return 1 }
            }
            pub trait B: A {
                override pub fx f: () Int32 { return 2 }
            }
            pub trait C: A {
                override pub fx f: () Int32 { return 3 }
            }
            pub struct S: B, C {
                pub v: Int32 = 0
            }
            """
        )
        assertEquals(listOf("struct S inheriting f from both B and C (two default bodies, and the pick would be silent: override f in S) is not lowered yet"), messages)
    }

    @Test
    fun constructingAClassWhoseBaseReabstractsAnInheritedDefaultIsRefused() {
        // B redeclares A.m without a body: C++ holds C abstract, whatever A's body says.
        val messages = unsupported(
            """
            pub trait A {
                pub fx m: () Int32 { return 1 }
            }
            pub trait B: A {
                override pub fx m: () Int32;
            }
            pub class C: B {
            }
            pub fx make: () C {
                return C {}
            }
            """
        )
        assertTrue(messages.any { it.startsWith("constructing C, which leaves B.m without a body") }, messages.toString())
    }

    @Test
    fun aStructInheritingADefaultThatNamesAnotherModulesPrivateIsRefused() {
        // The copy is spelled in the struct's module, where ::shapes::helper names nothing:
        // helper and FACTOR live in shapes.kira.cxx's anonymous namespace. A default that
        // names only pub declarations of its module (bumped) is copied, qualified: the
        // refusals name only the three, and the next test reads the copy.
        val shapes = OopTestSupport.module(
            "test:shapes",
            """
            FACTOR: Int32 = 3

            alias Len as Int32

            fx helper: (v: Int32) Int32 {
                return v
            }

            pub fx pubHelper: (v: Int32) Int32 {
                return v
            }

            pub trait Shape {
                pub fx area: () Int32;
                pub fx scaled: () Int32 {
                    return helper(area())
                }
                pub fx factor: () Int32 {
                    return FACTOR
                }
                pub fx len: () Int32 {
                    return 1 as Len
                }
                pub fx bumped: () Int32 {
                    return pubHelper(area())
                }
            }

            pub struct Local: Shape {
                pub side: Int32 = 2
                override pub fx area: () Int32 {
                    return side
                }
            }
            """,
        )
        val main = OopTestSupport.module(
            uri,
            """
            use "test:shapes"

            pub struct Square: Shape {
                pub side: Int32 = 4
                override pub fx area: () Int32 {
                    return side
                }
            }
            """,
        )
        val e = OopTestSupport.emit(shapes, main)
        val messages = e.module(uri).diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message }
        assertEquals(
            listOf(
                "struct Square inheriting the default body of Shape.scaled from module test:shapes, which names 'helper', private to that module (override scaled in Square, or make it pub) is not lowered yet",
                "struct Square inheriting the default body of Shape.factor from module test:shapes, which names 'FACTOR', private to that module (override factor in Square, or make it pub) is not lowered yet",
                "struct Square inheriting the default body of Shape.len from module test:shapes, which names 'Len', private to that module (override len in Square, or make it pub) is not lowered yet",
            ),
            messages,
        )
        // The struct of the trait's own module takes all four.
        val local = e.source("test:shapes")
        assertContains(local, "  std::int32_t Local::scaled() const\n  {\n      return helper(area());\n  }", "  std::int32_t Local::factor() const\n  {\n      return FACTOR;\n  }")
        assertEquals(emptyList(), e.errors("test:shapes").map { it.message })
    }

    @Test
    fun aStructInAnotherModuleCopiesADefaultThatNamesOnlyPubDeclarationsQualified() {
        // pubHelper is exported by shapes.kira.hxx: the copy in the struct's module names it
        // through its namespace.
        val shapes = OopTestSupport.module(
            "test:shapes",
            """
            pub fx pubHelper: (v: Int32) Int32 {
                return v
            }

            pub trait Shape {
                pub fx area: () Int32;
                pub fx bumped: () Int32 {
                    return pubHelper(area())
                }
            }
            """,
        )
        val main = OopTestSupport.module(
            uri,
            """
            use "test:shapes"

            pub struct Square: Shape {
                pub side: Int32 = 4
                override pub fx area: () Int32 {
                    return side
                }
            }
            """,
        )
        val e = OopTestSupport.emit(shapes, main)
        assertEquals(emptyList(), e.errors(uri).map { it.message })
        assertContains(e.header(uri), "      [[nodiscard]] std::int32_t bumped() const;")
        assertContains(e.source(uri), "  std::int32_t Square::bumped() const\n  {\n      return ::shapes::pubHelper(area());\n  }")
    }

    @Test
    fun aDefaultBodyTheTraitsLoweringRefusesIsReportedOnceWithItsStructCopy() {
        // The struct's copy is spelled from the same body: the refusal at hello(this) is one.
        val messages = unsupported(
            """
            pub trait Named {
                pub fx name: () Str;
                pub fx greet: () Str {
                    return hello(this)
                }
            }
            pub fx hello: (n: Named) Str {
                return n.name()
            }
            pub struct Cat: Named {
                pub v: Int32 = 1
                override pub fx name: () Str {
                    return "cat"
                }
            }
            """
        )
        assertEquals(listOf("this as a value in a default body of trait Named (a trait has no shared_from_this) is not lowered yet"), messages)
    }

    @Test
    fun aDefaultBodyAnotherModulesTraitRefusesIsReportedByThatModuleAlone() {
        // The struct's copy in test:main spells the same hello(this): the refusal is placed
        // in shapes.kira, and shapes reports it; main reports nothing of it. Two modules,
        // one construct, one diagnostic.
        val shapes = OopTestSupport.module(
            "test:shapes",
            """
            pub trait Named {
                pub fx name: () Str;
                pub fx greet: () Str {
                    return hello(this)
                }
            }
            pub fx hello: (n: Named) Str {
                return n.name()
            }
            """,
        )
        val main = OopTestSupport.module(
            uri,
            """
            use "test:shapes"

            pub struct Cat: Named {
                pub v: Int32 = 1
                override pub fx name: () Str {
                    return "cat"
                }
            }
            """,
        )
        val e = OopTestSupport.emit(shapes, main)
        val refusal = "this as a value in a default body of trait Named (a trait has no shared_from_this) is not lowered yet"
        assertEquals(listOf(refusal), e.module("test:shapes").diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message })
        assertEquals(emptyList(), e.module(uri).diagnostics.map { it.message })
    }

    // ---- what an override owes what it overrides ------------------------------------------------------

    @Test
    fun anOverrideOfAGenericBasesTypeParameterParameterIsSpelledAsTheBaseSpellsIt() {
        // Source<T> spells v: T as const T&; an override at Int32 spelled std::int32_t
        // overrides nothing (gcc: "marked override, but does not override"; MSVC C3668; the
        // class stays abstract), so it says const std::int32_t&. A Bool and an enum likewise.
        val (h, s) = both(
            """
            pub enum Mode {
                A,
                B
            }
            pub trait Source<T> {
                pub fx take: (v: T) T;
                pub fx keep: (mut v: T) Void;
            }
            pub class Five: Source<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
                override pub fx keep: (mut v: Int32) Void {
                    v = 1
                }
            }
            pub class Flag: Source<Bool> {
                override pub fx take: (v: Bool) Bool {
                    return v
                }
                override pub fx keep: (mut v: Bool) Void {
                    v = true
                }
            }
            pub class Which: Source<Mode> {
                override pub fx take: (v: Mode) Mode {
                    return v
                }
                override pub fx keep: (mut v: Mode) Void {
                    v = Mode.A
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual T take(const T& v) const = 0;\n      virtual void keep(T& v) const = 0;",
            "      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;\n      void keep(std::int32_t& v) const override;",
            "      [[nodiscard]] bool take(const bool& v) const override;\n      void keep(bool& v) const override;",
            "      [[nodiscard]] Mode take(const Mode& v) const override;\n      void keep(Mode& v) const override;",
        )
        // The definition takes the reference as vRef_ and copies it into v: the body reads the
        // value the caller passed, never the argument's place (a class's plain fx may write a
        // field, D29, and f.take(g.n) with take writing n returned the written n; measured).
        assertContains(
            s,
            "  std::int32_t Five::take(const std::int32_t& vRef_) const\n  {\n      const std::int32_t v = vRef_;\n      return v;\n  }",
            "  void Five::keep(std::int32_t& v) const\n  {\n      static_cast<void>(v = 1);\n  }",
            "  bool Flag::take(const bool& vRef_) const\n  {\n      const bool v = vRef_;\n      return v;\n  }",
            "  Mode Which::take(const Mode& vRef_) const\n  {\n      const Mode v = vRef_;\n      return v;\n  }",
        )
    }

    @Test
    fun anOverrideTakenByReferenceCopiesOnlyWhatItsBodyNames() {
        // A parameter the body never names is left as the [[maybe_unused]] reference; a
        // Str is const& on both sides, so nothing is copied; an alias keeps its name in the copy.
        val (h, s) = both(
            """
            pub alias Count as Int32
            pub trait Source<T> {
                pub fx take: (v: T, w: T) T;
                pub fx name: (s: Str) Str;
            }
            pub class Five: Source<Count> {
                override pub fx take: (v: Count, w: Count) Count {
                    return v
                }
                override pub fx name: (s: Str) Str {
                    return s
                }
            }
            """
        )
        // The return keeps the alias's name: Count is std::int32_t, the type the base returns at Int32.
        assertContains(h, "      [[nodiscard]] Count take(const std::int32_t& v, const std::int32_t& w) const override;\n      [[nodiscard]] kira::Str name(const kira::Str& s) const override;")
        assertContains(
            s,
            "  Count Five::take(const std::int32_t& vRef_, [[maybe_unused]] const std::int32_t& w) const\n  {\n      const Count v = vRef_;\n      return v;\n  }",
            "  kira::Str Five::name(const kira::Str& s) const\n  {\n      return s;\n  }",
        )
    }

    @Test
    fun anOverrideOfAnOverrideOwesWhatTheRootDeclarationSpells() {
        // Leaf overrides Mid.take, written at Int32, which overrides Source<T>.take at Int32:
        // Mid's C++ take is const std::int32_t&, so Leaf's is too (gcc "marked override, but
        // does not override" on Leaf, and Mid's take hidden under -Woverloaded-virtual;
        // measured). Through a class template in the middle the arguments compose:
        // Wide<U>: Source<U> at Int32.
        val (h, s) = both(
            """
            pub trait Source<T> {
                pub fx take: (v: T) T;
            }
            pub class Mid: Source<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            pub class Leaf: Mid {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            pub class Wide<U>: Source<U> {
                override pub fx take: (v: U) U {
                    return v
                }
            }
            pub class Narrow: Wide<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            """
        )
        assertContains(
            h,
            "  class Mid : public Source<std::int32_t>\n  {\n  public:\n      Mid() = default;\n      Mid(const Mid&) = delete;\n      Mid& operator=(const Mid&) = delete;\n      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;\n  };",
            "  class Leaf final : public Mid\n  {\n  public:\n      Leaf() = default;\n      Leaf(const Leaf&) = delete;\n      Leaf& operator=(const Leaf&) = delete;\n      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;\n  };",
            "      [[nodiscard]] U take(const U& v) const override;",
            "  class Narrow final : public Wide<std::int32_t>\n  {\n  public:\n      Narrow() = default;\n      Narrow(const Narrow&) = delete;\n      Narrow& operator=(const Narrow&) = delete;\n      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;\n  };",
        )
        assertContains(
            s,
            "  std::int32_t Leaf::take(const std::int32_t& vRef_) const\n  {\n      const std::int32_t v = vRef_;\n      return v;\n  }",
            "  std::int32_t Narrow::take(const std::int32_t& vRef_) const\n  {\n      const std::int32_t v = vRef_;\n      return v;\n  }",
        )
    }

    @Test
    fun anFxParameterOrReturnOfAGenericBaseKeepsTheShapeTheBaseSpells() {
        // Each<T> spells f as const kira::Fn<std::int32_t(const T&)>&, so at Int32 the
        // override says kira::Fn<std::int32_t(const std::int32_t&)>: substituting first gave
        // kira::Fn<std::int32_t(std::int32_t)>, which overrides nothing (gcc, MSVC C3668;
        // measured). A return type follows the same rule (gcc "invalid covariant return
        // type", MSVC C2555; measured). A kira::Fn is const& on both sides: no copy.
        val (h, s) = both(
            """
            pub trait Each<T> {
                pub fx each: (f: Fx<Tuple1<T>, Int32>) Int32;
            }
            pub class Nums: Each<Int32> {
                override pub fx each: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                    return f(2)
                }
            }
            pub trait Maker<T> {
                pub fx make: () Fx<Tuple1<T>, Int32>;
            }
            pub class IntMaker: Maker<Int32> {
                override pub fx make: () Fx<Tuple1<Int32>, Int32> {
                    return fx(x: Int32) Int32 {
                        return x
                    }
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual std::int32_t each(const kira::Fn<std::int32_t(const T&)>& f) const = 0;",
            "      [[nodiscard]] std::int32_t each(const kira::Fn<std::int32_t(const std::int32_t&)>& f) const override;",
            "      [[nodiscard]] virtual kira::Fn<std::int32_t(const T&)> make() const = 0;",
            "      [[nodiscard]] kira::Fn<std::int32_t(const std::int32_t&)> make() const override;",
        )
        assertContains(
            s,
            "  std::int32_t Nums::each(const kira::Fn<std::int32_t(const std::int32_t&)>& f) const\n  {\n      return f(2);\n  }",
            "  kira::Fn<std::int32_t(const std::int32_t&)> IntMaker::make() const\n  {",
        )
    }

    @Test
    fun aFamilyIsComparedAsCppHasItNotAsKiraWroteIt() {
        // Mid.take is written at Int32 but is const std::int32_t& in C++ (it overrides
        // Source<T>.take): beside a sibling Gen<Int32>.take it is one signature, and beside
        // Abs.take written at Int32 (std::int32_t) it is not.
        val program = """
            pub trait Source<T> {
                pub fx take: (v: T) Int32;
            }
            pub trait Gen<T> {
                pub fx take: (v: T) Int32;
            }
            pub trait Abs {
                pub fx take: (v: Int32) Int32;
            }
            pub class Mid: Source<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            pub class Agreed: Mid, Gen<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
        """
        val e = emit(program)
        assertEquals(emptyList(), e.errors(uri).map { it.message })
        assertContains(e.header(uri), "  class Agreed final : public Mid, public Gen<std::int32_t>\n  {\n  public:\n      Agreed() = default;\n      Agreed(const Agreed&) = delete;\n      Agreed& operator=(const Agreed&) = delete;\n      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;\n  };")
        val refused = unsupported(
            program + """
            pub class Mixed: Mid, Abs {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            """
        )
        assertEquals(
            listOf("Mixed.take overriding both std::int32_t Mid.take(const std::int32_t&) and std::int32_t Abs.take(std::int32_t), which C++ spells differently, so no one signature overrides both is not lowered yet"),
            refused,
        )
    }

    @Test
    fun aTraitDefaultsParameterNamedAsACopyingStructsFieldIsRenamed() {
        // Square copies Shape.scaled as a member of its own, where `side` is its field: gcc
        // -Wshadow, clang and MSVC C4458 refuse the copy (measured), so the parameter is
        // side_p wherever the body is spelled, the trait's own definition included.
        val (h, s) = both(
            """
            pub trait Shape {
                pub fx area: () Int32;
                pub fx scaled: (side: Int32) Int32 {
                    return side
                }
            }
            pub struct Square: Shape {
                pub side: Int32 = 3
                override pub fx area: () Int32 {
                    return side
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual std::int32_t scaled(std::int32_t side_p) const;",
            "      [[nodiscard]] std::int32_t scaled(std::int32_t side_p) const;\n  };",
        )
        assertContains(
            s,
            "  std::int32_t Shape::scaled(std::int32_t side_p) const\n  {\n      return side_p;\n  }",
            "  std::int32_t Square::scaled(std::int32_t side_p) const\n  {\n      return side_p;\n  }",
        )
    }

    @Test
    fun anOverrideOfAGenericSuperclassMethodIsSpelledAsTheSuperclassSpellsIt() {
        // The class case fails the same way on gcc, clang and MSVC (measured): Base<T>.take
        // is `std::int32_t take(const T& v)`, and IntBase's override follows it.
        val (h, s) = both(
            """
            pub class Base<T> {
                pub fx take: (v: T) Int32 {
                    return 0
                }
            }
            pub class IntBase: Base<Int32> {
                override pub fx take: (v: Int32) Int32 {
                    return v
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] virtual std::int32_t take(const T& v) const;",
            "      [[nodiscard]] std::int32_t take(const std::int32_t& v) const override;",
        )
        assertContains(s, "  std::int32_t IntBase::take(const std::int32_t& vRef_) const\n  {\n      const std::int32_t v = vRef_;\n      return v;\n  }")
    }

    @Test
    fun anOverrideWhoseOwnSpellingAlreadyMatchesKeepsIt() {
        // A Str, a struct and a Maybe<T> are const& on both sides; a class template's own
        // T is the base's T; an override of a non-generic base is spelled as written.
        val (h, _) = both(
            """
            pub struct Pt {
                pub x: Int32 = 1
            }
            pub trait Sink<T> {
                pub fx put: (v: T) Int32;
                pub fx maybe: (v: Maybe<T>) Int32;
            }
            pub class StrSink: Sink<Str> {
                override pub fx put: (v: Str) Int32 {
                    return 1
                }
                override pub fx maybe: (v: Maybe<Str>) Int32 {
                    return 2
                }
            }
            pub class PtSink: Sink<Pt> {
                override pub fx put: (v: Pt) Int32 {
                    return 3
                }
                override pub fx maybe: (v: Maybe<Pt>) Int32 {
                    return 4
                }
            }
            pub class AnySink<T>: Sink<T> {
                override pub fx put: (v: T) Int32 {
                    return 5
                }
                override pub fx maybe: (v: Maybe<T>) Int32 {
                    return 6
                }
            }
            pub trait Plain {
                pub fx id: (v: Int32) Int32;
            }
            pub class Id: Plain {
                override pub fx id: (v: Int32) Int32 {
                    return v
                }
            }
            """
        )
        assertContains(
            h,
            "      [[nodiscard]] std::int32_t put(const kira::Str& v) const override;\n      [[nodiscard]] std::int32_t maybe(const kira::Maybe<kira::Str>& v) const override;",
            "      [[nodiscard]] std::int32_t put(const Pt& v) const override;\n      [[nodiscard]] std::int32_t maybe(const kira::Maybe<Pt>& v) const override;",
            "      [[nodiscard]] std::int32_t put(const T& v) const override;\n      [[nodiscard]] std::int32_t maybe(const kira::Maybe<T>& v) const override;",
            "      [[nodiscard]] std::int32_t id(std::int32_t v) const override;",
        )
    }

    @Test
    fun anOverrideOfTwoBaseDeclarationsCppSpellsDifferentlyIsRefused() {
        // Abs.id takes std::int32_t, Def<Int32>.id const std::int32_t&: one signature
        // overrides one and hides the other, so the class's own override is refused.
        val messages = unsupported(
            """
            pub trait Abs {
                pub fx id: (v: Int32) Int32;
            }
            pub trait Def<T> {
                pub fx id: (v: T) T {
                    return v
                }
            }
            pub class C: Abs, Def<Int32> {
                override pub fx id: (v: Int32) Int32 {
                    return v
                }
            }
            """
        )
        assertEquals(
            listOf("C.id overriding both std::int32_t Abs.id(std::int32_t) and std::int32_t Def<Int32>.id(const std::int32_t&), which C++ spells differently, so no one signature overrides both is not lowered yet"),
            messages,
        )
    }

    @Test
    fun aForwarderWhoseTargetAndRequirementCppSpellsDifferentlyIsRefused() {
        // The forwarder overrides the sibling's requirement and calls the default: with a
        // generic on either side the two are not one signature (clang stops on the hiding
        // under -Woverloaded-virtual; with the requirement generic, C stays abstract).
        val a = unsupported(
            """
            pub trait Abs {
                pub fx id: (v: Int32) Int32;
            }
            pub trait Def<T> {
                pub fx id: (v: T) T {
                    return v
                }
            }
            pub class C: Abs, Def<Int32> {
            }
            """
        )
        assertEquals(
            listOf("C inheriting id from both std::int32_t Def<Int32>.id(const std::int32_t&) and std::int32_t Abs.id(std::int32_t), which C++ spells differently, so no one override ties them (override id in C) is not lowered yet"),
            a,
        )
        val b = unsupported(
            """
            pub trait Abs<T> {
                pub fx id: (v: T) T;
            }
            pub trait Def {
                pub fx id: (v: Int32) Int32 {
                    return v
                }
            }
            pub class C: Abs<Int32>, Def {
            }
            pub struct S: Abs<Int32>, Def {
                pub k: Int32 = 0
            }
            """
        )
        // The struct derives nothing in C++: its copy of Def.id is reached statically, whatever Abs<Int32> spells.
        assertEquals(
            listOf("C inheriting id from both std::int32_t Def.id(std::int32_t) and std::int32_t Abs<Int32>.id(const std::int32_t&), which C++ spells differently, so no one override ties them (override id in C) is not lowered yet"),
            b,
        )
    }

    @Test
    fun aGenericTraitsDefaultReachedThroughAForwardingTraitIsRefusedForAStruct() {
        // Both forwards id to Def<Int32>'s body; the struct's copy would spell `T id(const T&)`
        // in a non-template struct ("T does not name a type", measured). The rule reads the
        // body's owner, not the trait the struct names.
        val messages = unsupported(
            """
            pub trait Abs {
                pub fx id: (v: Int32) Int32;
            }
            pub trait Def<T> {
                pub fx id: (v: T) T {
                    return v
                }
            }
            pub trait Both: Abs, Def<Int32> {
            }
            pub struct S: Both {
                pub k: Int32 = 0
            }
            """
        )
        assertTrue(
            messages.contains("struct S inheriting the default body of Def<Int32>.id from a generic trait (override id in S) is not lowered yet"),
            messages.toString(),
        )
    }

    @Test
    fun aRequirementRedeclaredBodylessIsStillARequirementASiblingsDefaultSatisfies() {
        // Abs re-declares Abs0's bodyless f bodyless: no base gave f a body, so nothing is
        // re-abstracted, and Def's body is the one the struct takes and the class forwards to.
        val program = """
            pub trait Abs0 {
                pub fx f: () Int32;
            }
            pub trait Abs: Abs0 {
                override pub fx f: () Int32;
            }
            pub trait Def {
                pub fx f: () Int32 {
                    return 7
                }
            }
            pub struct S: Abs, Def {
                pub v: Int32 = 0
            }
            pub class C: Abs, Def {
            }
            pub fx make: () C {
                return C {}
            }
        """
        val e = emit(program)
        assertEquals(emptyList(), e.errors(uri).map { it.message })
        val h = e.header(uri)
        val s = e.source(uri)
        assertContains(
            h,
            "  struct S\n  {\n      std::int32_t v = 0;\n\n      [[nodiscard]] std::int32_t f() const;\n  };",
            "      [[nodiscard]] std::int32_t f() const override;\n  };",
        )
        assertContains(s, "  std::int32_t S::f() const\n  {\n      return 7;\n  }", "  std::int32_t C::f() const\n  {\n      return Def::f();\n  }", "      return std::make_shared<C>();")
    }

    @Test
    fun anOverrideCopiesAnOpaqueHandleAsThePointerItTakes() {
        // Sink<T>'s v: T is const T&, which at an opaque handle is `Handle* const&` (the const
        // binds to T). The copy is the parameter as its own declaration spells it, made const as
        // a pointer is: `const Handle* v` was a handle useIt(Handle*) refused (measured). An
        // Unsafe or CStr type argument, which gave `const const std::int32_t*` here, cannot be
        // written any more: a second-class type is never a type argument (decision 4b, 1.2).
        val (h, s) = both(
            """
            pub @_opaque class Handle
            pub fx useIt: (h: Handle) Int32;
            pub trait Sink<T> {
                pub fx put: (v: T) Int32;
            }
            pub class HSink: Sink<Handle> {
                override pub fx put: (v: Handle) Int32 {
                    return useIt(v)
                }
            }
            """
        )
        assertContains(
            h,
            "  class HSink final : public Sink<Handle*>\n",
            "      [[nodiscard]] std::int32_t put(Handle* const& v) const override;",
        )
        assertContains(s, "  std::int32_t HSink::put(Handle* const& vRef_) const\n  {\n      Handle* const v = vRef_;\n      return useIt(v);\n  }")
        assertLacks(s, "const const", "const Handle*")
    }

    @Test
    fun aCopiedParametersReferenceIsANameNoOtherPartSpells() {
        // The statement part's temporaries are t0_, t1_ and its catch variable ex_, from a pool
        // that never sees the context's names: a reference named `<param>_` was t0_ for a
        // parameter t0 and shadowed the body's first D33 temporary (g++ -Werror=shadow,
        // measured). A body name has an uppercase mark, which no synthesized name has.
        val s = emit(
            """
            pub trait Src<T> {
                pub fx take: (t0: T, ex: T) Int32;
            }
            pub class C: Src<Int32> {
                override pub fx take: (t0: Int32, ex: Int32) Int32 {
                    return t0
                }
            }
            """
        ).source(uri)
        assertContains(s, "  std::int32_t C::take(const std::int32_t& t0Ref_, [[maybe_unused]] const std::int32_t& ex) const\n  {\n      const std::int32_t t0 = t0Ref_;\n      return t0;\n  }")
        assertLacks(s, "t0_", "ex_")
        listOf(ClassLowering.bodyName("v", "Ref"), ClassLowering.bodyName("t0_", "Arg"), ClassLowering.bodyName("x_p0_", "Ref")).forEach { name ->
            assertTrue(!CppNames.isSynthesized(name) && name.contains('_') && name.any { it.isLowerCase() } && !name.contains("__"), name)
        }
    }

    @Test
    fun aDeepOverrideChainIsEmittedWithoutRepeatingItsQuestions() {
        // Each override asks what the family above it spells, which asks the same of each
        // member one level up: unremembered that was exponential in the depth (a 3-parameter
        // override at each of 10 levels took 40 s to emit, 14 did not finish in 9 minutes;
        // measured). 14 levels now emit in well under a second; the ceiling is generous.
        val levels = 14
        val program = buildString {
            append("pub trait Source<T> {\n    pub fx f: (a: T, b: T, c: T) T;\n}\n")
            for (i in 1..levels) {
                val parent = if (i == 1) "Source<Int32>" else "L${i - 1}"
                append("pub class L$i: $parent {\n    override pub fx f: (a: Int32, b: Int32, c: Int32) Int32 {\n        return a\n    }\n}\n")
            }
        }
        val (h, s) = assertTimeoutPreemptively(Duration.ofSeconds(60), ThrowingSupplier { both(program) })
        assertContains(
            h,
            "  class L$levels final : public L${levels - 1}\n",
            "      [[nodiscard]] std::int32_t f(const std::int32_t& a, const std::int32_t& b, const std::int32_t& c) const override;",
        )
        assertContains(s, "  std::int32_t L$levels::f(const std::int32_t& aRef_, [[maybe_unused]] const std::int32_t& b, [[maybe_unused]] const std::int32_t& c) const\n  {\n      const std::int32_t a = aRef_;\n      return a;\n  }")
    }

    @Test
    fun aConstructionSpillsAndFillsPointersInTheirOwnColumn() {
        // D33's temporaries are const locals of the field's own column, a pointer's const on
        // the pointer (`const Handle*` is a handle nothing taking one accepts), and a skipped
        // field without a default is value-initialized as a pointer can be: `Handle*{}` is no
        // expression. The temporaries carry the classes part's mark, never a statement
        // part's t0_ (a construction spilled inside a spilled call shadowed it; measured).
        // An opaque handle is the one pointer a field may hold: an Unsafe or CStr field cannot
        // be written (decision 4b, rules.view.type).
        val s = emit(
            """
            pub @_opaque class Handle
            pub fx open: () Handle;
            pub class Holder {
                require pub g: Handle
                require pub h: Handle
            }
            pub class Loose {
                pub h: Handle
                require pub n: Int32
            }
            pub fx make: () Holder {
                return Holder { h = open(), g = open() }
            }
            pub fx loose: () Loose {
                return Loose { n = 1 }
            }
            """
        ).source(uri)
        assertContains(
            s,
            "      return []() -> kira::Rc<Holder> { Handle* const t0_Arg_ = open(); Handle* const t1_Arg_ = open(); return std::make_shared<Holder>(t1_Arg_, t0_Arg_); }();",
            "      return std::make_shared<Loose>(static_cast<Handle*>(nullptr), 1);",
        )
    }
}
