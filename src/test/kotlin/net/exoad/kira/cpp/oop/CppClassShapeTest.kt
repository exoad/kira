package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppGenericsEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import org.junit.jupiter.api.Test
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
                    pub fx keep: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                        return k
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
            // an escaping one (no EscapePass verdict) is a kira::Fn
            "      [[nodiscard]] std::int32_t keep([[maybe_unused]] const kira::Fn<std::int32_t(std::int32_t)>& f) const;\n".replace("[[maybe_unused]] ", ""),
            "  template<typename F_f>\n    requires kira::Callable<F_f, std::int32_t, std::int32_t>\n  std::int32_t Counter::apply(F_f&& f, std::int32_t x) const\n  {\n",
        )
        assertLacks(s, "Counter::apply")
        assertContains(s, "  std::int32_t Counter::keep([[maybe_unused]] const kira::Fn<std::int32_t(std::int32_t)>& f) const\n")
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
        assertContains(s, "      return [&]() -> kira::Rc<Pair> { const std::int32_t t0_ = second(); const std::int32_t t1_ = first(); return std::make_shared<Pair>(t1_, t0_); }();")
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
        // the default generics part (Plain) and classes part (Unsupported) fail loudly
        CppEmitParts().generics.typeArguments(ctx, node, listOf(KType.INT32))
        CppEmitParts().classes.thisValue(ctx, net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr())
        assertEquals(2, ctx.diagnostics.count { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }, ctx.diagnostics.toString())
    }
}
