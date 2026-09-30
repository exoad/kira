// kira/ffi.hxx - Kira calls C++ (design 7.2): the argument proxies an extern
// call passes, and the drift check an extern module states against the real
// C++ header.
//
// An extern module (`@_extern(cpp = "bibo::Car", header = "car.hxx")`) emits
// no declaration of its own: the C++ header already holds one, and a second
// copy would drift. Instead its generated header includes the real one and
// states, per declared member, an unevaluated call whose result must match
// what Kira declared:
//
//     KIRA_EXTERN_CHECK(std::declval<const bibo::Car&>().ok(), bool, "Car.ok");
//     KIRA_EXTERN_CHECK(ImGui::SliderFloat(kira::ffi::in(std::declval<const kira::Str&>()),
//                                          kira::ffi::out(std::declval<float&>()),
//                                          kira::ffi::arg<float>(), kira::ffi::arg<float>()),
//                       bool, "sliderFloat");
//
// A call resolves overloads, applies the C++ default arguments and takes the
// proxies, which C's `decltype(&f)` form cannot; a wrong Kira signature fails
// with "Kira's Car.ok no longer matches its C++ header" on g++, clang and MSVC
// (probes-S/externcheck.cxx, measured).
//
// WHAT "MATCHES" MEANS (the rule W3.2's car programs and W5.x's ImGui binding
// declare against). Section 7.2 wrote the check as is_convertible; that let a
// C++ `std::uint32_t count()` pass as Kira's `count: () Int32`, and the call
// site keeps the C++ type, so `count() - 1` was computed unsigned and printed
// 4294967295 where Kira means -1 (measured, g++ 13 with -Wconversion
// -Wsign-conversion -Werror silent). So a scalar is matched exactly, at the
// return, at every by-value parameter and at every struct field:
//   - the same type, or two arithmetic types of the same size, the same
//     signedness and the same kind (integral or floating). That is the one
//     tolerance the fixed-width typedefs need: on arm-none-eabi std::int32_t
//     is long, and a C header's `int` is the same value there, while `unsigned`,
//     `float` or `bool` against Int32 is a drift. bool is only bool (a 1-byte
//     unsigned integer is not it, and a C `int is_ok()` is declared Int32), and
//     char is only Char: it is Kira's Char and no Int8 or UInt8, though it has
//     their size and one of their signednesses (which one depends on the
//     target: signed on x86, unsigned on ARM). Left as a scalar of its size, a
//     C++ `char letter()` declared `() Int8` passed, and `trace(letter())`
//     printed A where the same value held in an Int8 printed 65 (measured);
//     wchar_t, char8_t, char16_t and char32_t are characters the same way, each
//     only itself;
//   - a struct field or a returned value may also be an unscoped enum of the
//     integer's size (a C `enum Mode mode;`, declared `mode: Int32`), since an
//     extern enum is not a Kira declaration; the size is the target ABI's
//     (4 bytes on the hosts, 1 on arm-none-eabi with its short enums, measured).
// A Maybe<T> (std::optional) matches an optional whose value matches by this
// same rule, and a Fn (std::function) a std::function, a function pointer or a
// function of the same arity whose return and parameters match by it, each in
// the direction the value flows: the return as a result does (the callee's
// return reaches the caller's declared type), a parameter the other way round
// (the caller's argument reaches the callee's parameter: Kira calls a C++
// callable with its own values, and C++ calls a Kira Fn with C++ values), so a
// C++ `std::function<void(const std::string&)>` is a Kira `Fn<(CStr) Void>`
// and a `std::function<void(const char*)>` is no `Fn<(Str) Void>`, which is
// exactly what std::function's own constructor accepts and refuses (matched
// the wrong way round, the second passed and the conversion then failed in
// libstdc++'s constructor, measured). A parameter also agrees on being a
// mutable reference or not; a callable of any other kind, a lambda say, is a
// seam. The two are stated on their own because their converting constructors
// are the is_convertible hole one level down: std::optional converts from any
// optional whose value converts, so a C++ `std::optional<std::uint32_t> find()`
// passed as `find: () Maybe<Int32>` and `find().unwrap() - 1` printed
// 4294967295, and a `Maybe<Int32>` reached a `std::optional<std::uint8_t>`
// parameter as 44 for 300; std::function takes any callable with a compatible
// signature (measured, g++ 13, zig clang 20 and MSVC /W4 /WX, every one under
// its strict warnings, silent).
// Anything else (a class handle, a Str, a pointer, a struct) matches when it
// converts: a `std::unique_ptr<Car>` to `kira::Rc<Car>` (a raw `Car*` does
// not: shared_ptr's constructor from it is explicit, and adopting one is a
// seam's decision), a `const char*` to `kira::Str`, a `const std::string&` to
// `kira::Str`.
//
// WHAT KIRA THEN READS. The check proves a C++ value may stand for the declared
// type; the generated code then makes it that type, since Kira's own operations
// (kira::div, kira::cat, trace, min and max) are written for the declared type
// and no other. Every extern result and every extern constant is
// `kira::ffi::declared<T>(...)`, and every field read of an extern struct
// `kira::ffi::field<T>(...)`, unless T is Void or a pointer (an `Unsafe<T>`, a
// `CStr`, an opaque handle: the check lets only a qualification differ, and a
// pointer's operations are the same either way). Left as the C++ type, a
// `const char*` result declared Str compared as a pointer where Kira compares
// a Str by value (measured: `name() == ABC` printed 0 for Kira's 1, with
// -Werror silent); a `long wide()` declared Int32 on Windows, and a C `int
// cnt_read(void)` declared Int32 on arm-none-eabi (the declaration this file
// tells W3.x to write), both passed the check and `wide() / d` then failed to
// compile in kira::div(long, int32_t&) (measured, g++ 13.2, zig clang 20 and
// arm-none-eabi-g++ 13.3); an unscoped enum result declared Int32 failed the
// same way inside kira::cat. declared<T> is static_cast<T>, which the check
// proved loses nothing, except that a null pointer declared Str is the empty
// Str (ImGui's GetClipboardText returns NULL for an empty clipboard, and
// std::string's constructor from null is a logic_error on libstdc++ and a
// segfault on libc++, measured) and that a Maybe is converted value by value.
// field<T>(m) is the member itself, an lvalue, when its C++ type is T and the
// struct is an lvalue (so `r.x = v` and `out(r.x)` still name it), and the value
// otherwise: moved out of a temporary's member (`avail().x`, the ImGui shape) or
// converted from a same-size twin; such a twin field (a C `int` on
// arm-none-eabi, an enum) is written through an extern function, as C++ would
// not convert an integer back to an enum. declared<T> takes a scalar by value,
// so that reading an in-class `static const int N = 4;` with no definition is
// no odr-use (by reference it failed to link at -O0, measured).
// A returned or field drift fails the static_assert with Kira's message. A
// by-value scalar, Maybe or Fn parameter is stated as `kira::ffi::arg<T>()`, a
// proxy that converts to what matches T and to nothing else, so a Kira `v:
// Int32` against a C++ `std::uint8_t v` (which is_convertible passed, and a
// call then narrowed silently) is no viable call: it fails as "no matching
// function" naming kira::ffi::Arg<T>, not with Kira's message, since the
// expression is ill-formed before the assertion looks at it (a wrong parameter
// count or a Str against an int failed that way already). A parameter that C++
// declares as `double` is declared Float64 in Kira, never Float32 (the
// promotion that is_convertible allowed is a declared-type drift under this
// rule).
//
// An extern struct declared with fields gets a layout twin (the same fields, in
// Kira's order, under ns::ffi_) and, per field, KIRA_EXTERN_FIELD: the C++ member
// matches the type Kira declared as above (a same-size drift such as int against
// float converts silently, and is refused) and sits at the twin's offset (so a
// reordered pair of same-typed fields is caught too); sizeof is compared once.
// A scoped enum matches nothing and is refused. Kira writes such a field through
// an extern function, as C++ would not convert the integer back.
//
// The proxies (7.2, A):
//   kira::ffi::in(s)    a Str argument: converts to const char*, std::string_view
//                       or const std::string&, whichever the callee declares
//   kira::ffi::out(x)   a mut argument: converts to T& or T*; never to a T by
//                       value, which would copy and write nothing (a deleted
//                       conversion makes that ambiguous, so it does not compile)
//   kira::ffi::arg<T>() a by-value scalar, Maybe or Fn in a check only (never in
//                       a call, which passes the Kira value itself): converts to
//                       what matches T, so the check resolves the overload the
//                       value would and no other. A struct, a class handle or a
//                       pointer is stated as std::declval<const T&>() instead,
//                       the lvalue the call passes, so that an overload taking T
//                       itself outranks one taking a type T converts to, as it
//                       does at the call; a proxy would make the two ambiguous.
//                       A value Kira converts to the parameter's type (a T to a
//                       Maybe, a class to its base or a trait, null, a named
//                       function) is passed as that type, T(e), never left for
//                       C++ to convert: a template or an overload set would take
//                       the unconverted value where the check stated a T. A
//                       literal is passed as T too, T{5} (a Str literal at a
//                       CStr as a const char*), since C++ gives it its own type:
//                       5 is an int, and even an Int32 is a long on arm-none-eabi.
//                       So is a lambda at an Fx: kira::Fn<...>(lambda), never
//                       its closure type, which a template binds as itself
//   kira::ffi::CStrBuf  a CStr made from a Str that is neither a literal nor a
//                       Str constant nor a named Str; its c_str() lives to the
//                       end of the full-expression
//
// A `mut p: Unsafe<T>` parameter takes no proxy: it is the writable T* itself,
// passed by value (table 5.1: Unsafe<T> is const T* unless mut), which is how a
// fread-style buffer or ImGui's InputText(char* buf, ...) is reached. A pointer
// converts as C++ converts it (a uint8_t* reaches a void* parameter).
//
// Freestanding: Out, arg, declared, field and the macros only. There is no Str
// on the Pico, so a freestanding extern module passes CStr, scalars and Unsafe<T>.
#pragma once

#include "kira/core.hxx"

#include <cstddef>
#include <optional>
#include <type_traits>
#include <utility>

#if KIRA_PROFILE_HOSTED
#include <functional>
#include <string>
#include <string_view>
#endif

namespace kira::ffi
{
  // The traits are built from std::conjunction and std::conditional_t rather than
  // && and ?:, so that a clause is instantiated only when the ones before it hold:
  // sizeof(void) is ill-formed wherever it is spelled, short-circuit or not, and a
  // void reaches these through a Fn's return (`Fn<void(std::int32_t)>`) and through
  // a `void f()` declared with a result.
  template<class A, class B>
  struct SameSize : std::bool_constant<sizeof(A) == sizeof(B)>
  {
  };

  // A character type: only itself (the head of this file). char is Kira's Char
  // and no Int8 or UInt8, though it has their size and, per target, one of their
  // signednesses; signed char (std::int8_t) and unsigned char (std::uint8_t) are
  // the integers, and distinct types from char on every target.
  template<class T>
  inline constexpr bool is_character_v = std::is_same_v<T, char> || std::is_same_v<T, wchar_t> || std::is_same_v<T, char16_t> || std::is_same_v<T, char32_t>
#if defined(__cpp_char8_t)
                                         || std::is_same_v<T, char8_t>
#endif
      ;

  // A and B are one scalar on this target: the same type, or two arithmetic types
  // of the same size, signedness and kind (see the head of this file). bool is
  // only bool, and a character type only itself.
  template<class A, class B>
  inline constexpr bool same_scalar_v = std::disjunction<
      std::is_same<A, B>,
      std::conjunction<std::is_arithmetic<A>,
                       std::is_arithmetic<B>,
                       std::bool_constant<!is_character_v<A> && !is_character_v<B>>,
                       std::bool_constant<std::is_same_v<A, bool> == std::is_same_v<B, bool>>,
                       std::bool_constant<std::is_integral_v<A> == std::is_integral_v<B>>,
                       std::bool_constant<std::is_signed_v<A> == std::is_signed_v<B>>,
                       SameSize<A, B>>>::value;

  // The C++ member type M is what Kira declared as T when the two are one scalar,
  // when M is an unscoped enum of T's size that converts to the integer T (a C
  // enum field read as Int32), or when M is T. Anything else is a drift.
  template<class M, class T>
  inline constexpr bool field_matches_v = std::disjunction<
      std::bool_constant<same_scalar_v<M, T>>,
      std::conjunction<std::is_enum<M>, std::is_integral<T>, std::is_convertible<M, T>, SameSize<M, T>>>::value;
  template<class M, class T>
  struct FieldMatches : std::bool_constant<field_matches_v<M, T>>
  {
  };

  // What a C++ value of type From may be when Kira declared To (the head of this
  // file): a scalar or an enum exactly, as field_matches_v says; an optional and
  // a std::function part by part, below; anything else when it converts. The
  // same trait serves both directions, a C++ result reaching Kira's declared
  // type and a Kira value reaching a C++ parameter, since the scalar rule is
  // symmetric and the rest is is_convertible in the direction asked.
  template<class From, class To>
  struct Matches
      : std::conditional_t<std::is_arithmetic_v<To> || std::is_arithmetic_v<From> || std::is_enum_v<From>,
                           FieldMatches<From, To>,
                           std::is_convertible<From, To>>
  {
  };
  template<class From, class To>
  inline constexpr bool matches_v = Matches<From, To>::value;

  // Maybe<T>: only an optional, and one whose value matches (an int is no
  // Maybe<Int32>, though std::optional converts from it).
  template<class From, class T>
  struct Matches<From, std::optional<T>> : std::false_type
  {
  };
  template<class F, class T>
  struct Matches<std::optional<F>, std::optional<T>> : Matches<F, T>
  {
  };

#if KIRA_PROFILE_HOSTED
  // Fn: a std::function, a function pointer or a function type of the same arity
  // whose return and parameters match. A return matches as a result does (From's
  // return reaches To's), and void only void. A parameter flows the other way:
  // whoever holds a To calls it with To's parameter types, and the call reaches
  // From's callable, so To's parameter must match From's (Matches<B, A> below,
  // with A From's parameter and B To's), which is the argument std::function's
  // own constructor requires. Matched as Matches<A, B>, a C++
  // `std::function<void(const char*)>` passed as a `Fn<void(const kira::Str&)>`
  // and the conversion the emitter writes then failed inside libstdc++'s
  // constructor, while a `std::function<void(const std::string&)>` was refused
  // as a `Fn<void(const char*)>` though it converts (measured, g++ 13.2). A
  // parameter's reference and const are stripped before matching, and the two
  // agree on being a mutable lvalue reference (a `mut` parameter) or not.
  template<class A>
  inline constexpr bool is_mutable_ref_v = std::is_lvalue_reference_v<A> && !std::is_const_v<std::remove_reference_t<A>>;
  template<class A, class B>
  struct FnParamMatches
      : std::conjunction<std::bool_constant<is_mutable_ref_v<A> == is_mutable_ref_v<B>>,
                         Matches<std::remove_cv_t<std::remove_reference_t<B>>, std::remove_cv_t<std::remove_reference_t<A>>>>
  {
  };
  template<class A, class B>
  struct FnReturnMatches : Matches<A, B>
  {
  };
  template<class B>
  struct FnReturnMatches<void, B> : std::false_type
  {
  };
  template<class A>
  struct FnReturnMatches<A, void> : std::false_type
  {
  };
  template<>
  struct FnReturnMatches<void, void> : std::true_type
  {
  };

  template<class... T>
  struct TypeList
  {
  };
  template<class A, class B>
  struct ParamsMatch : std::false_type
  {
  };
  template<>
  struct ParamsMatch<TypeList<>, TypeList<>> : std::true_type
  {
  };
  template<class A, class... As, class B, class... Bs>
  struct ParamsMatch<TypeList<A, As...>, TypeList<B, Bs...>>
      : std::conjunction<FnParamMatches<A, B>, ParamsMatch<TypeList<As...>, TypeList<Bs...>>>
  {
  };
  template<class S2, class S>
  struct SigMatches : std::false_type
  {
  };
  template<class R2, class... A2, class R, class... A>
  struct SigMatches<R2(A2...), R(A...)>
      : std::conjunction<FnReturnMatches<R2, R>, ParamsMatch<TypeList<A2...>, TypeList<A...>>>
  {
  };

  template<class From, class Sig>
  struct Matches<From, std::function<Sig>> : std::false_type
  {
  };
  template<class S2, class Sig>
  struct Matches<std::function<S2>, std::function<Sig>> : SigMatches<S2, Sig>
  {
  };
  template<class R2, class... A2, class Sig>
  struct Matches<R2 (*)(A2...), std::function<Sig>> : SigMatches<R2(A2...), Sig>
  {
  };
  template<class R2, class... A2, class Sig>
  struct Matches<R2(A2...), std::function<Sig>> : SigMatches<R2(A2...), Sig>
  {
  };
#endif

  // What an extern call (or an extern constant) of type Got is when Kira declared
  // R. decltype gives a reference for an lvalue call and a const-qualified type
  // for a constant; the value's type is what is matched.
  template<class Got, class R>
  inline constexpr bool result_matches_v = matches_v<std::remove_cv_t<std::remove_reference_t<Got>>, R>;

  // A by-value scalar, Maybe or Fn argument of a check: converts to what matches T
  // and to nothing else, so the check is viable only against the parameter the Kira
  // value would reach unchanged. Never evaluated, so the conversion is declared only.
  template<class T>
  struct Arg
  {
      template<class U, std::enable_if_t<matches_v<T, U>, int> = 0>
      operator U() const noexcept;
  };
  template<class T>
  [[nodiscard]] constexpr Arg<T> arg() noexcept
  {
      return Arg<T>{};
  }

  // What an extern result or an extern constant reaches Kira as (the head of this
  // file): the declared type T, by static_cast, which the check proved loses
  // nothing; a Maybe value by value, so that its null-pointer case is the Str's;
  // and (hosted) a Str from a null pointer as the empty Str, where std::string's
  // constructor is a logic_error on libstdc++ and a segfault on libc++ (measured).
  template<class T>
  struct Declared
  {
      template<class V>
      [[nodiscard]] static constexpr T from(V&& v)
      {
          return static_cast<T>(static_cast<V&&>(v));
      }
  };
  template<class U>
  struct Declared<std::optional<U>>
  {
      template<class V>
      [[nodiscard]] static constexpr std::optional<U> from(V&& v)
      {
          if(!v)
          {
              return std::nullopt;
          }
          return std::optional<U>(Declared<U>::from(*static_cast<V&&>(v)));
      }
  };
#if KIRA_PROFILE_HOSTED
  template<>
  struct Declared<std::string>
  {
      template<class V>
      [[nodiscard]] static std::string from(V&& v)
      {
          if constexpr(std::is_pointer_v<std::remove_cv_t<std::remove_reference_t<V>>>)
          {
              return v != nullptr ? std::string(v) : std::string();
          }
          else
          {
              return static_cast<std::string>(static_cast<V&&>(v));
          }
      }
  };
#endif
  // A scalar (an arithmetic type, an enum, a pointer) is taken by value: the
  // lvalue-to-rvalue conversion happens at the call, which is the one read of a
  // constant that is no odr-use. Taken by reference, an extern constant declared
  // in its class as `static const int N = 4;` with no definition outside it (the
  // pre-C++17 idiom, still everywhere in C++ headers) linked at -O2 and failed at
  // -O0 with 'undefined reference to Holder::N' on g++ and lld (measured).
  // The two overloads are told apart by V *after* the array-to-pointer and
  // function-to-pointer decay a by-value parameter already applies: an array
  // (`static const char NAME[] = "kira"`, a C string macro's type) or a function
  // (an extern C++ function read as a Fn constant) is not itself a scalar, but
  // decays to one, so guarding the forwarding overload on the un-decayed type
  // made both overloads viable and every such read ambiguous on g++, clang and
  // MSVC (measured: `declared<Str>("1.91.0")`, `declared<Fn<...>>(v7::twice)`).
  // Deciding on the decayed type keeps every such read on the by-value overload.
  template<class T, class V, std::enable_if_t<std::is_scalar_v<std::decay_t<V>>, int> = 0>
  [[nodiscard]] constexpr T declared(V v)
  {
      return Declared<T>::from(static_cast<V&&>(v));
  }
  template<class T, class V, std::enable_if_t<!std::is_scalar_v<std::decay_t<V>>, int> = 0>
  [[nodiscard]] constexpr T declared(V&& v)
  {
      return Declared<T>::from(static_cast<V&&>(v));
  }

  // A field read of an extern struct as the type Kira declared: the member itself,
  // an lvalue, when its C++ type is T (cv aside) and the struct is an lvalue, so
  // that `r.x = v` and `out(r.x)` still name it; the value otherwise: moved out of
  // a member of a temporary (`declared<Vec2>(avail()).x`, the everyday ImGui shape
  // GetContentRegionAvail().x, whose member is an xvalue; taken as `M&` the read did
  // not compile on g++, clang or MSVC, measured), and converted where the C++ type
  // is a same-size twin (a C `int` field read as Int32 on arm-none-eabi, an unscoped
  // enum field read as its integer), which the field check proved loses nothing.
  // Such a twin field is written through an extern function.
  template<class T, class M>
  [[nodiscard]] constexpr decltype(auto) field(M&& m) noexcept(std::is_lvalue_reference_v<M> || std::is_nothrow_constructible_v<T, M&&>)
  {
      if constexpr(std::is_same_v<std::remove_cv_t<std::remove_reference_t<M>>, T> && std::is_lvalue_reference_v<M>)
      {
          return (m);
      }
      else
      {
          return static_cast<T>(static_cast<M&&>(m));
      }
  }
}

#define KIRA_EXTERN_CHECK(expr, R, what) \
    static_assert(kira::ffi::result_matches_v<decltype(expr), R>, "Kira's " what " no longer matches its C++ header")

// S is the C++ struct, Twin Kira's layout twin, field the member both declare, T
// the type Kira declared. decltype of an unparenthesized member access is the
// member's declared type, so field_matches_v compares the declaration, not a value.
#define KIRA_EXTERN_FIELD(S, Twin, field, T, what)                                                          \
    static_assert(kira::ffi::field_matches_v<decltype(std::declval<S&>().field), T>, "Kira's " what " no longer matches its C++ header"); \
    static_assert(offsetof(S, field) == offsetof(Twin, field), "Kira's " what " no longer matches its C++ header (it is not at that offset)")

namespace kira::ffi
{
  // A `mut` argument (D4): binds to T& and to T*, whichever the callee declares.
  template<class T>
  struct Out
  {
      T& r;

      operator T&() const noexcept
      {
          return r;
      }
      operator T*() const noexcept
      {
          return &r;
      }
      // A by-value T would copy: the deleted conversion makes it ambiguous instead.
      operator T() const = delete;
  };
  template<class T>
  [[nodiscard]] constexpr Out<T> out(T& r) noexcept
  {
      return Out<T>{r};
  }

#if KIRA_PROFILE_HOSTED
  // A Str argument: the callee reads it as const char*, std::string_view or
  // const std::string&. The proxy refers to the argument, which outlives the call.
  struct In
  {
      const std::string& s;

      operator const char*() const noexcept
      {
          return s.c_str();
      }
      operator std::string_view() const noexcept
      {
          return std::string_view(s);
      }
      operator const std::string&() const noexcept
      {
          return s;
      }
  };
  [[nodiscard]] inline In in(const std::string& s) noexcept
  {
      return In{s};
  }

  // `CStrBuf(expr).c_str()`: a const char* over a Str the call computes.
  class CStrBuf
  {
  public:
      explicit CStrBuf(std::string s) : s_(std::move(s))
      {
      }
      [[nodiscard]] const char* c_str() const noexcept
      {
          return s_.c_str();
      }

  private:
      std::string s_;
  };
#endif
}
