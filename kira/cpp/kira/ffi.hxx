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
//     `float` or `bool` against Int32 is a drift (bool is only bool: a 1-byte
//     unsigned integer is not it, and a C `int is_ok()` is declared Int32);
//   - a struct field or a returned value may also be an unscoped enum of the
//     integer's size (a C `enum Mode mode;`, declared `mode: Int32`), since an
//     extern enum is not a Kira declaration; the size is the target ABI's
//     (4 bytes on the hosts, 1 on arm-none-eabi with its short enums, measured).
// Anything else (a class handle, a Str, a pointer, a struct) matches when it
// converts: a `Car*` or a `std::unique_ptr<Car>` to `kira::Rc<Car>`, a
// `const char*` to `kira::Str`, a `const std::string&` to `kira::Str`.
// A returned or field drift fails the static_assert with Kira's message. A
// by-value scalar parameter is stated as `kira::ffi::arg<T>()`, a proxy that
// converts to a scalar matching T and to nothing else, so a Kira `v: Int32`
// against a C++ `std::uint8_t v` (which is_convertible passed, and a call then
// narrowed silently) is no viable call: it fails as "no matching function"
// naming kira::ffi::Arg<T>, not with Kira's message, since the expression is
// ill-formed before the assertion looks at it (a wrong parameter count or a
// Str against an int failed that way already). A parameter that C++ declares
// as `double` is declared Float64 in Kira, never Float32 (the promotion that
// is_convertible allowed is a declared-type drift under this rule).
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
//   kira::ffi::arg<T>() a by-value scalar in a check only (never in a call, which
//                       passes the Kira value itself): converts to a scalar that
//                       matches T, so the check resolves the overload the value
//                       would and no other
//   kira::ffi::CStrBuf  a CStr made from a Str that is neither a literal nor a
//                       Str constant nor a named Str; its c_str() lives to the
//                       end of the full-expression
//
// A `mut p: Unsafe<T>` parameter takes no proxy: it is the writable T* itself,
// passed by value (table 5.1: Unsafe<T> is const T* unless mut), which is how a
// fread-style buffer or ImGui's InputText(char* buf, ...) is reached. A pointer
// converts as C++ converts it (a uint8_t* reaches a void* parameter).
//
// Freestanding: Out, arg and the macros only. There is no Str on the Pico, so a
// freestanding extern module passes CStr, scalars and Unsafe<T>.
#pragma once

#include "kira/core.hxx"

#include <cstddef>
#include <type_traits>
#include <utility>

#if KIRA_PROFILE_HOSTED
#include <string>
#include <string_view>
#endif

namespace kira::ffi
{
  // A and B are one scalar on this target: the same type, or two arithmetic types
  // of the same size, signedness and kind (see the head of this file). bool is
  // only bool.
  template<class A, class B>
  inline constexpr bool same_scalar_v =
      std::is_same_v<A, B>
      || (std::is_arithmetic_v<A> && std::is_arithmetic_v<B>
          && std::is_same_v<A, bool> == std::is_same_v<B, bool>
          && std::is_integral_v<A> == std::is_integral_v<B>
          && std::is_signed_v<A> == std::is_signed_v<B>
          && sizeof(A) == sizeof(B));

  // The C++ member type M is what Kira declared as T when the two are one scalar,
  // when M is an unscoped enum of T's size that converts to the integer T (a C
  // enum field read as Int32), or when M is T. Anything else is a drift.
  template<class M, class T>
  inline constexpr bool field_matches_v =
      same_scalar_v<M, T>
      || (std::is_enum_v<M> && std::is_integral_v<T> && std::is_convertible_v<M, T> && sizeof(M) == sizeof(T));

  // What an extern call (or an extern constant) of type Got is when Kira declared
  // R: a scalar or an enum exactly, as field_matches_v says, and anything else
  // when it converts. decltype gives a reference for an lvalue call and a
  // const-qualified type for a constant; the value's type is what is matched.
  template<class Got, class R>
  inline constexpr bool result_matches_v =
      (std::is_arithmetic_v<R> || std::is_arithmetic_v<std::remove_cv_t<std::remove_reference_t<Got>>>
       || std::is_enum_v<std::remove_cv_t<std::remove_reference_t<Got>>>)
          ? field_matches_v<std::remove_cv_t<std::remove_reference_t<Got>>, R>
          : std::is_convertible_v<Got, R>;

  // A by-value scalar argument of a check: converts to a scalar that matches T and
  // to nothing else, so the check is viable only against the parameter the Kira
  // value would reach unchanged. Never evaluated, so the conversion is declared only.
  template<class T>
  struct Arg
  {
      template<class U, std::enable_if_t<same_scalar_v<U, T>, int> = 0>
      operator U() const noexcept;
  };
  template<class T>
  [[nodiscard]] constexpr Arg<T> arg() noexcept
  {
      return Arg<T>{};
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
