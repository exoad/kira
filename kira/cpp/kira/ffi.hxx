// kira/ffi.hxx - Kira calls C++ (design 7.2): the argument proxies an extern
// call passes, and the drift check an extern module states against the real
// C++ header.
//
// An extern module (`@_extern(cpp = "bibo::Car", header = "car.hxx")`) emits
// no declaration of its own: the C++ header already holds one, and a second
// copy would drift. Instead its generated header includes the real one and
// states, per declared member, an unevaluated call whose result must convert
// to what Kira declared:
//
//     KIRA_EXTERN_CHECK(std::declval<const bibo::Car&>().ok(), bool, "Car.ok");
//     KIRA_EXTERN_CHECK(ImGui::SliderFloat(kira::ffi::in(std::declval<const kira::Str&>()),
//                                          kira::ffi::out(std::declval<float&>()), 0.0f, 1.0f),
//                       bool, "sliderFloat");
//
// A call resolves overloads, applies the C++ default arguments and takes the
// proxies, which C's `decltype(&f)` form cannot; a wrong Kira signature fails
// with "Kira's Car.ok no longer matches its C++ header" on g++, clang and MSVC
// (probes-S/externcheck.cxx, measured).
//
// An extern struct declared with fields gets a layout twin (the same fields, in
// Kira's order, under ns::ffi_) and, per field, KIRA_EXTERN_FIELD: the C++ member
// has exactly the type Kira declared (is_same, since a same-size drift such as
// int against float converts silently) and sits at the twin's offset (so a
// reordered pair of same-typed fields is caught too); sizeof is compared once.
// One exception to is_same: a C struct's enum field (`enum Mode mode;`), which
// Kira declares as the integer of the enum's size (`mode: Int32`), since an
// extern enum is not a Kira declaration. An unscoped enum converts to that
// integer, so a read is exact; a size gate keeps a 1-byte or 8-byte enum from
// passing as Int32 (that would be the layout drift the twin exists to catch).
// The size is the target ABI's: a C enum with no fixed type is 4 bytes on the
// hosts and 1 byte on arm-none-eabi (AAPCS short enums; measured, ffi_test), so
// the Pico's declaration of the same header says UInt8 where the host's says
// Int32. A scoped enum converts to nothing and is refused. Kira writes such a
// field through an extern function, as C++ would not convert the integer back.
//
// The proxies (7.2, A):
//   kira::ffi::in(s)    a Str argument: converts to const char*, std::string_view
//                       or const std::string&, whichever the callee declares
//   kira::ffi::out(x)   a mut argument: converts to T& or T*; never to a T by
//                       value, which would copy and write nothing (a deleted
//                       conversion makes that ambiguous, so it does not compile)
//   kira::ffi::CStrBuf  a CStr made from a Str that is neither a literal nor a
//                       Str constant nor a named Str; its c_str() lives to the
//                       end of the full-expression
//
// A `mut p: Unsafe<T>` parameter takes no proxy: it is the writable T* itself,
// passed by value (table 5.1: Unsafe<T> is const T* unless mut), which is how a
// fread-style buffer or ImGui's InputText(char* buf, ...) is reached.
//
// Freestanding: Out and the macros only. There is no Str on the Pico, so a
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

#define KIRA_EXTERN_CHECK(expr, R, what) \
    static_assert(std::is_convertible_v<decltype(expr), R>, "Kira's " what " no longer matches its C++ header")

namespace kira::ffi
{
  // The C++ member type M is what Kira declared as T when it is T, or when it is
  // an unscoped enum of T's size that converts to the integer T (a C enum field
  // read as Int32; see the head of this file). Anything else is a drift.
  template<class M, class T>
  inline constexpr bool field_matches_v =
      std::is_same_v<M, T>
      || (std::is_enum_v<M> && std::is_integral_v<T> && std::is_convertible_v<M, T> && sizeof(M) == sizeof(T));
}

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
