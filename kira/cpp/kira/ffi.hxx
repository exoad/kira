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
// The proxies (7.2, A):
//   kira::ffi::in(s)    a Str argument: converts to const char*, std::string_view
//                       or const std::string&, whichever the callee declares
//   kira::ffi::out(x)   a mut argument: converts to T& or T*; never to a T by
//                       value, which would copy and write nothing (a deleted
//                       conversion makes that ambiguous, so it does not compile)
//   kira::ffi::CStrBuf  a CStr made from a Str that is neither a literal nor a
//                       name; its c_str() lives to the end of the full-expression
//
// Freestanding: Out and the macro only. There is no Str on the Pico, so a
// freestanding extern module passes CStr, scalars and Unsafe<T>.
#pragma once

#include "kira/core.hxx"

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
