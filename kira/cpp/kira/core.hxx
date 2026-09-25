// kira/core.hxx - the Kira C++ runtime core, for both profiles.
//
// Generated code includes kira/rt.hxx (a hosted module) or this file (a
// freestanding module). The build chooses the profile, never generated code:
//
//     hosted (the default)             C++20 and its standard library
//     KIRA_PROFILE_FREESTANDING=1      no heap, no exceptions, no RTTI (the Pico)
//
// A freestanding build defines kira::panic itself (the board parks its outputs
// and resets). A hosted build gets the definition below, so a freestanding
// module's header also links in a host suite that never includes rt.hxx.
//
// The floor is gcc 11.4: no <format>, no std::expected, no constexpr
// std::string, no <span> (View is ours). Every check reaches kira::panic, which
// is not constexpr: a failing check inside a static_assert is a compile error.
// KIRA_UNCHECKED=1 turns the bounds, division and shift checks off for a
// measured hot path; unwrap stays checked.
//
// A generated header includes the runtime before its macro guard opens, so
// this code must survive <windows.h>: every min/max is spelled (f)(...).
#pragma once

#include <array>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <type_traits>
#include <utility>

#if defined(KIRA_PROFILE_FREESTANDING) && KIRA_PROFILE_FREESTANDING
#define KIRA_PROFILE_HOSTED 0
#else
#define KIRA_PROFILE_HOSTED 1
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>
#endif

namespace kira
{
  using Size = std::size_t;
  using Char = char;

  // ---- failure ---------------------------------------------------------------
#if KIRA_PROFILE_HOSTED
  // Flushes stdout so the program's own output comes first, writes
  // "kira: <what>" to stderr, and aborts.
  [[noreturn]] inline void panic(const char* what) noexcept
  {
      std::fflush(stdout);
      std::fprintf(stderr, "kira: %s\n", what != nullptr ? what : "panic");
      std::fflush(stderr);
      std::abort();
  }
#else
  // The board defines it: it parks the outputs and resets. Declared, never
  // defined here.
  [[noreturn]] void panic(const char* what) noexcept;
#endif

#if defined(KIRA_UNCHECKED) && KIRA_UNCHECKED
  inline constexpr bool checked = false;
#else
  inline constexpr bool checked = true;
#endif

  // ---- conversions: `as` and bitCast -----------------------------------------
  // Integer to integer wraps (two's complement, as C++20 defines it). Float to
  // integer SATURATES and takes NaN to 0: C++'s static_cast is undefined there,
  // and Kira defines every `as`. Everything else is a static_cast.
  template<class To, class From>
  [[nodiscard]] constexpr To as(From v) noexcept
  {
      static_assert(std::is_arithmetic_v<To> && std::is_arithmetic_v<From>, "kira::as converts one number to another");
      if constexpr(std::is_floating_point_v<From> && std::is_integral_v<To>)
      {
          if(!(v == v))
          {
              return To{0};
          }
          if(v <= static_cast<From>(std::numeric_limits<To>::lowest()))
          {
              return std::numeric_limits<To>::lowest();
          }
          if(v >= static_cast<From>((std::numeric_limits<To>::max)()))
          {
              return (std::numeric_limits<To>::max)();
          }
          return static_cast<To>(v);
      }
      else
      {
          return static_cast<To>(v);
      }
  }

  // The bits of v read as a To (std::bit_cast without <bit>).
  template<class To, class From>
  [[nodiscard]] constexpr To bitCast(const From& v) noexcept
  {
      static_assert(sizeof(To) == sizeof(From), "kira::bitCast needs two types of one size");
      static_assert(std::is_trivially_copyable_v<To> && std::is_trivially_copyable_v<From>,
                    "kira::bitCast needs trivially copyable types");
      return __builtin_bit_cast(To, v);
  }

  // ---- checked integer arithmetic (D10) -------------------------------------
  // x / 0 and x % 0 panic, and so does the one signed quotient that overflows
  // (lowest / -1). x % -1 is 0, never the undefined lowest % -1.
  template<class T>
  [[nodiscard]] constexpr T div(T a, T b)
  {
      static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::div is integer division");
      if constexpr(checked)
      {
          if(b == 0)
          {
              panic("integer division by zero");
          }
          if constexpr(std::is_signed_v<T>)
          {
              if(a == std::numeric_limits<T>::lowest() && b == static_cast<T>(-1))
              {
                  panic("integer division overflows");
              }
          }
      }
      return static_cast<T>(a / b);
  }

  template<class T>
  [[nodiscard]] constexpr T mod(T a, T b)
  {
      static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::mod is an integer remainder");
      if constexpr(checked)
      {
          if(b == 0)
          {
              panic("integer remainder by zero");
          }
      }
      if constexpr(std::is_signed_v<T>)
      {
          if(b == static_cast<T>(-1))
          {
              return T{0};
          }
      }
      return static_cast<T>(a % b);
  }

  // The count must be in [0, width of T). The count may be any integer type.
  template<class T, class N>
  [[nodiscard]] constexpr T shl(T v, N n)
  {
      static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::shl shifts an integer");
      static_assert(std::is_integral_v<N> && !std::is_same_v<N, bool>, "a shift count is an integer");
      if constexpr(checked)
      {
          if(std::cmp_less(n, 0) || std::cmp_greater_equal(n, std::numeric_limits<std::make_unsigned_t<T>>::digits))
          {
              panic("shift count out of range");
          }
      }
      return static_cast<T>(v << n);
  }

  template<class T, class N>
  [[nodiscard]] constexpr T shr(T v, N n)
  {
      static_assert(std::is_integral_v<T> && !std::is_same_v<T, bool>, "kira::shr shifts an integer");
      static_assert(std::is_integral_v<N> && !std::is_same_v<N, bool>, "a shift count is an integer");
      if constexpr(checked)
      {
          if(std::cmp_less(n, 0) || std::cmp_greater_equal(n, std::numeric_limits<std::make_unsigned_t<T>>::digits))
          {
              panic("shift count out of range");
          }
      }
      return static_cast<T>(v >> n);
  }

  // A Char's code unit, unsigned on every target: `char` is signed on x86 and
  // unsigned on ARM, so Char ordering goes through this (D3).
  [[nodiscard]] constexpr unsigned char ord(Char c) noexcept
  {
      return static_cast<unsigned char>(c);
  }

  // A non-escaping Fx parameter is a template parameter F constrained by this.
  template<class F, class R, class... A>
  concept Callable = std::invocable<F&, A...>
                     && (std::is_void_v<R> || std::convertible_to<std::invoke_result_t<F&, A...>, R>);

  // ---- views -----------------------------------------------------------------
  // View<T> is Kira's borrowed, read-only span; MutView<T> the writable one.
  // Both are constexpr and freestanding, made implicitly from a std::array (and,
  // hosted, a std::vector), and from a pointer and a length only by name.
  template<class T>
  struct View
  {
      const T* ptr = nullptr;
      Size len = 0;

      constexpr View() = default;
      constexpr View(const T* p, Size n) noexcept : ptr(p), len(n)
      {
      }
      template<Size N>
      constexpr View(const std::array<T, N>& a) noexcept : ptr(a.data()), len(N)
      {
      }
#if KIRA_PROFILE_HOSTED
      template<class A>
      View(const std::vector<T, A>& v) noexcept : ptr(v.data()), len(v.size())
      {
      }
#endif

      [[nodiscard]] constexpr Size size() const noexcept
      {
          return len;
      }
      [[nodiscard]] constexpr bool isEmpty() const noexcept
      {
          return len == 0;
      }
      [[nodiscard]] constexpr const T* data() const noexcept
      {
          return ptr;
      }
      [[nodiscard]] constexpr const T* begin() const noexcept
      {
          return ptr;
      }
      [[nodiscard]] constexpr const T* end() const noexcept
      {
          return ptr + len;
      }
      [[nodiscard]] constexpr const T& operator[](Size i) const
      {
          if constexpr(checked)
          {
              if(i >= len)
              {
                  panic("index out of range");
              }
          }
          return ptr[i];
      }
      // The tail from `at`, and `n` elements from `at`.
      [[nodiscard]] constexpr View from(Size at) const
      {
          if constexpr(checked)
          {
              if(at > len)
              {
                  panic("slice out of range");
              }
          }
          return View(ptr + at, len - at);
      }
      [[nodiscard]] constexpr View slice(Size at, Size n) const
      {
          if constexpr(checked)
          {
              if(at > len || n > len - at)
              {
                  panic("slice out of range");
              }
          }
          return View(ptr + at, n);
      }

      // Element-wise. A hidden friend, so a std::array or a MutView converts.
      [[nodiscard]] friend constexpr bool operator==(View a, View b)
      {
          if(a.len != b.len)
          {
              return false;
          }
          for(Size i = 0; i < a.len; ++i)
          {
              if(!(a.ptr[i] == b.ptr[i]))
              {
                  return false;
              }
          }
          return true;
      }
  };

  template<class T>
  struct MutView
  {
      T* ptr = nullptr;
      Size len = 0;

      constexpr MutView() = default;
      constexpr MutView(T* p, Size n) noexcept : ptr(p), len(n)
      {
      }
      template<Size N>
      constexpr MutView(std::array<T, N>& a) noexcept : ptr(a.data()), len(N)
      {
      }
#if KIRA_PROFILE_HOSTED
      template<class A>
      MutView(std::vector<T, A>& v) noexcept : ptr(v.data()), len(v.size())
      {
      }
#endif

      [[nodiscard]] constexpr Size size() const noexcept
      {
          return len;
      }
      [[nodiscard]] constexpr bool isEmpty() const noexcept
      {
          return len == 0;
      }
      [[nodiscard]] constexpr T* data() const noexcept
      {
          return ptr;
      }
      [[nodiscard]] constexpr T* begin() const noexcept
      {
          return ptr;
      }
      [[nodiscard]] constexpr T* end() const noexcept
      {
          return ptr + len;
      }
      [[nodiscard]] constexpr T& operator[](Size i) const
      {
          if constexpr(checked)
          {
              if(i >= len)
              {
                  panic("index out of range");
              }
          }
          return ptr[i];
      }
      [[nodiscard]] constexpr MutView from(Size at) const
      {
          if constexpr(checked)
          {
              if(at > len)
              {
                  panic("slice out of range");
              }
          }
          return MutView(ptr + at, len - at);
      }
      [[nodiscard]] constexpr MutView slice(Size at, Size n) const
      {
          if constexpr(checked)
          {
              if(at > len || n > len - at)
              {
                  panic("slice out of range");
              }
          }
          return MutView(ptr + at, n);
      }
      constexpr operator View<T>() const noexcept
      {
          return View<T>(ptr, len);
      }

      [[nodiscard]] friend constexpr bool operator==(MutView a, MutView b)
      {
          return View<T>(a) == View<T>(b);
      }
  };

  // An Arr<T, N> place seen as a span: view for a read, mutView for a `mut` place.
  template<class T, Size N>
  [[nodiscard]] constexpr View<T> view(const std::array<T, N>& a) noexcept
  {
      return View<T>(a.data(), N);
  }
  template<class T, Size N>
  [[nodiscard]] constexpr MutView<T> mutView(std::array<T, N>& a) noexcept
  {
      return MutView<T>(a.data(), N);
  }

  // a[i]: checked. The mutable overloads return T&, so `kira::at(a, i) = v` works.
  template<class T, Size N>
  [[nodiscard]] constexpr const T& at(const std::array<T, N>& a, Size i)
  {
      if constexpr(checked)
      {
          if(i >= N)
          {
              panic("index out of range");
          }
      }
      return a[i];
  }
  template<class T, Size N>
  [[nodiscard]] constexpr T& at(std::array<T, N>& a, Size i)
  {
      if constexpr(checked)
      {
          if(i >= N)
          {
              panic("index out of range");
          }
      }
      return a[i];
  }
  template<class T>
  [[nodiscard]] constexpr const T& at(View<T> v, Size i)
  {
      return v[i];
  }
  template<class T>
  [[nodiscard]] constexpr T& at(MutView<T> v, Size i)
  {
      return v[i];
  }

  // A string literal as a static View<Char>: "abc" is 3 chars, no terminator.
  template<Size N>
  [[nodiscard]] constexpr View<Char> lit(const Char (&s)[N]) noexcept
  {
      return View<Char>(s, N - 1);
  }

  // ---- Maybe -------------------------------------------------------------------
  // One C++ spelling for generic code: Maybe<T> is std::optional<T> for a value
  // and, hosted, a nullable std::shared_ptr for a class (rt.hxx specialises it).
  // kira::none converts to both; isSome, unwrap and unwrapOr are overloaded for
  // both.
  template<class T>
  struct MaybeOf
  {
      using type = std::optional<T>;
  };
  template<class T>
  using Maybe = typename MaybeOf<T>::type;

  struct None
  {
      template<class T>
      constexpr operator std::optional<T>() const noexcept
      {
          return std::nullopt;
      }
#if KIRA_PROFILE_HOSTED
      template<class U>
      operator std::shared_ptr<U>() const noexcept
      {
          return nullptr;
      }
#endif
  };
  inline constexpr None none{};

  template<class T>
  [[nodiscard]] constexpr bool isSome(const std::optional<T>& m) noexcept
  {
      return m.has_value();
  }
  // Panics when absent. An rvalue Maybe hands its value out, so nothing
  // refers into a temporary that is already gone.
  template<class T>
  [[nodiscard]] constexpr const T& unwrap(const std::optional<T>& m)
  {
      if(!m.has_value())
      {
          panic("unwrap of an empty Maybe");
      }
      return *m;
  }
  template<class T>
  [[nodiscard]] constexpr T unwrap(std::optional<T>&& m)
  {
      if(!m.has_value())
      {
          panic("unwrap of an empty Maybe");
      }
      return std::move(*m);
  }
  template<class T>
  [[nodiscard]] constexpr T unwrapOr(const std::optional<T>& m, const std::type_identity_t<T>& d)
  {
      return m.has_value() ? *m : d;
  }

  // Member access on a generic T: the value itself. rt.hxx adds *p for a class
  // handle, so `kira::deref(x).m()` is `.` or `->` as T needs.
  template<class T>
  [[nodiscard]] constexpr T& deref(T& v) noexcept
  {
      return v;
  }
  template<class T>
  [[nodiscard]] constexpr const T& deref(const T& v) noexcept
  {
      return v;
  }

  // ---- little-endian bytes (kira:bytes) -------------------------------------
  // Checked: each reads or writes b.slice(at, width).
  [[nodiscard]] constexpr std::uint16_t readU16Le(View<std::uint8_t> b, Size at)
  {
      const View<std::uint8_t> p = b.slice(at, 2);
      return static_cast<std::uint16_t>(static_cast<std::uint32_t>(p[0]) | (static_cast<std::uint32_t>(p[1]) << 8));
  }
  [[nodiscard]] constexpr std::uint32_t readU32Le(View<std::uint8_t> b, Size at)
  {
      const View<std::uint8_t> p = b.slice(at, 4);
      return static_cast<std::uint32_t>(p[0]) | (static_cast<std::uint32_t>(p[1]) << 8)
             | (static_cast<std::uint32_t>(p[2]) << 16) | (static_cast<std::uint32_t>(p[3]) << 24);
  }
  [[nodiscard]] constexpr std::uint64_t readU64Le(View<std::uint8_t> b, Size at)
  {
      const View<std::uint8_t> p = b.slice(at, 8);
      std::uint64_t v = 0;
      for(Size i = 8; i > 0; --i)
      {
          v = (v << 8) | static_cast<std::uint64_t>(p[i - 1]);
      }
      return v;
  }
  [[nodiscard]] constexpr float readF32Le(View<std::uint8_t> b, Size at)
  {
      return bitCast<float>(readU32Le(b, at));
  }
  [[nodiscard]] constexpr double readF64Le(View<std::uint8_t> b, Size at)
  {
      return bitCast<double>(readU64Le(b, at));
  }
  constexpr void writeU16Le(MutView<std::uint8_t> b, Size at, std::uint16_t v)
  {
      const MutView<std::uint8_t> p = b.slice(at, 2);
      p[0] = static_cast<std::uint8_t>(v & 0xFFu);
      p[1] = static_cast<std::uint8_t>((v >> 8) & 0xFFu);
  }
  constexpr void writeU32Le(MutView<std::uint8_t> b, Size at, std::uint32_t v)
  {
      const MutView<std::uint8_t> p = b.slice(at, 4);
      for(Size i = 0; i < 4; ++i)
      {
          p[i] = static_cast<std::uint8_t>((v >> (8 * i)) & 0xFFu);
      }
  }
  constexpr void writeU64Le(MutView<std::uint8_t> b, Size at, std::uint64_t v)
  {
      const MutView<std::uint8_t> p = b.slice(at, 8);
      for(Size i = 0; i < 8; ++i)
      {
          p[i] = static_cast<std::uint8_t>((v >> (8 * i)) & 0xFFu);
      }
  }
  constexpr void writeF32Le(MutView<std::uint8_t> b, Size at, float v)
  {
      writeU32Le(b, at, bitCast<std::uint32_t>(v));
  }
  constexpr void writeF64Le(MutView<std::uint8_t> b, Size at, double v)
  {
      writeU64Le(b, at, bitCast<std::uint64_t>(v));
  }

  // ---- text without a heap ---------------------------------------------------
  // STRICT decimal: an optional sign, then digits, and nothing else; none on
  // overflow. Locale-free and heap-free: the Pico's parser and the pilot's
  // Str.toInt64 are this one function.
  [[nodiscard]] constexpr std::optional<std::int64_t> parseInt64(View<Char> s) noexcept
  {
      Size i = 0;
      bool neg = false;
      if(i < s.size() && (s.ptr[i] == '+' || s.ptr[i] == '-'))
      {
          neg = s.ptr[i] == '-';
          ++i;
      }
      if(i == s.size())
      {
          return std::nullopt;
      }
      const std::uint64_t limit = neg ? std::uint64_t{1} << 63 : (std::uint64_t{1} << 63) - 1;
      std::uint64_t v = 0;
      for(; i < s.size(); ++i)
      {
          const Char c = s.ptr[i];
          if(c < '0' || c > '9')
          {
              return std::nullopt;
          }
          const std::uint64_t d = static_cast<std::uint64_t>(c - '0');
          if(v > (limit - d) / 10)
          {
              return std::nullopt;
          }
          v = v * 10 + d;
      }
      return neg ? static_cast<std::int64_t>(std::uint64_t{0} - v) : static_cast<std::int64_t>(v);
  }

  // A fixed-capacity text buffer: N chars and a terminator, no heap. Appends
  // past N are dropped, and truncated() says so. Interpolation into a StrBuf
  // lowers to clear() and appends.
  template<Size N>
  class StrBuf
  {
  public:
      constexpr StrBuf() = default;

      [[nodiscard]] static constexpr Size capacity() noexcept
      {
          return N;
      }
      [[nodiscard]] constexpr Size size() const noexcept
      {
          return len_;
      }
      [[nodiscard]] constexpr bool isEmpty() const noexcept
      {
          return len_ == 0;
      }
      [[nodiscard]] constexpr bool truncated() const noexcept
      {
          return cut_;
      }
      [[nodiscard]] constexpr View<Char> view() const noexcept
      {
          return View<Char>(buf_.data(), len_);
      }
      [[nodiscard]] constexpr const Char* data() const noexcept
      {
          return buf_.data();
      }
      [[nodiscard]] constexpr const Char* c_str() const noexcept
      {
          return buf_.data();
      }

      constexpr void clear() noexcept
      {
          len_ = 0;
          cut_ = false;
          buf_[0] = '\0';
      }
      constexpr void addChar(Char c) noexcept
      {
          if(len_ < N)
          {
              buf_[len_] = c;
              ++len_;
              buf_[len_] = '\0';
          }
          else
          {
              cut_ = true;
          }
      }
      constexpr void add(View<Char> s) noexcept
      {
          for(Size i = 0; i < s.size(); ++i)
          {
              addChar(s.ptr[i]);
          }
      }
      constexpr void addUInt(std::uint64_t v) noexcept
      {
          Char digits[20] = {};
          Size n = 0;
          do
          {
              digits[n] = static_cast<Char>('0' + static_cast<int>(v % 10u));
              ++n;
              v /= 10u;
          }
          while(v != 0u);
          while(n > 0)
          {
              --n;
              addChar(digits[n]);
          }
      }
      constexpr void addInt(std::int64_t v) noexcept
      {
          if(v < 0)
          {
              addChar('-');
              addUInt(std::uint64_t{0} - static_cast<std::uint64_t>(v));
          }
          else
          {
              addUInt(static_cast<std::uint64_t>(v));
          }
      }
      // `places` decimals (0..9), rounded half up, locale-free: 1.5 with 3 is
      // "1.500". A magnitude past 1.8e19 / 10^places saturates.
      constexpr void addFixed(double v, std::int32_t places) noexcept
      {
          if(!(v == v))
          {
              add(lit("nan"));
              return;
          }
          if(v < 0.0)
          {
              addChar('-');
              v = -v;
          }
          if(v > (std::numeric_limits<double>::max)())
          {
              add(lit("inf"));
              return;
          }
          const std::int32_t p = places < 0 ? 0 : (places > 9 ? 9 : places);
          std::uint64_t scale = 1;
          for(std::int32_t i = 0; i < p; ++i)
          {
              scale *= 10u;
          }
          const std::uint64_t scaled = as<std::uint64_t>(v * static_cast<double>(scale) + 0.5);
          addUInt(scaled / scale);
          if(p > 0)
          {
              addChar('.');
              const std::uint64_t frac = scaled % scale;
              for(std::uint64_t d = scale / 10u; d > 0u; d /= 10u)
              {
                  addChar(static_cast<Char>('0' + static_cast<int>((frac / d) % 10u)));
              }
          }
      }

  private:
      std::array<Char, N + 1> buf_{};
      Size len_ = 0;
      bool cut_ = false;
  };

  // ---- tuples ----------------------------------------------------------------
  // Aggregates: Tuple2<A, B>{.first = a, .second = b}.
  struct Tuple0
  {
      bool operator==(const Tuple0&) const = default;
  };
  template<class A>
  struct Tuple1
  {
      A first;
      bool operator==(const Tuple1&) const = default;
  };
  template<class A, class B>
  struct Tuple2
  {
      A first;
      B second;
      bool operator==(const Tuple2&) const = default;
  };
  template<class A, class B, class C>
  struct Tuple3
  {
      A first;
      B second;
      C third;
      bool operator==(const Tuple3&) const = default;
  };
  template<class A, class B, class C, class D>
  struct Tuple4
  {
      A first;
      B second;
      C third;
      D fourth;
      bool operator==(const Tuple4&) const = default;
  };
  template<class A, class B, class C, class D, class E>
  struct Tuple5
  {
      A first;
      B second;
      C third;
      D fourth;
      E fifth;
      bool operator==(const Tuple5&) const = default;
  };
  template<class A, class B, class C, class D, class E, class F>
  struct Tuple6
  {
      A first;
      B second;
      C third;
      D fourth;
      E fifth;
      F sixth;
      bool operator==(const Tuple6&) const = default;
  };
  template<class A, class B, class C, class D, class E, class F, class G>
  struct Tuple7
  {
      A first;
      B second;
      C third;
      D fourth;
      E fifth;
      F sixth;
      G seventh;
      bool operator==(const Tuple7&) const = default;
  };
  template<class A, class B, class C, class D, class E, class F, class G, class H>
  struct Tuple8
  {
      A first;
      B second;
      C third;
      D fourth;
      E fifth;
      F sixth;
      G seventh;
      H eighth;
      bool operator==(const Tuple8&) const = default;
  };
  template<class A, class B, class C, class D, class E, class F, class G, class H, class I>
  struct Tuple9
  {
      A first;
      B second;
      C third;
      D fourth;
      E fifth;
      F sixth;
      G seventh;
      H eighth;
      I ninth;
      bool operator==(const Tuple9&) const = default;
  };

  // ---- enums -----------------------------------------------------------------
  // enumOf<E>(raw) is the entry of E whose value is raw, or none (D25). It reads
  // EnumTraits<E>::values, every entry in declaration order. The primary
  // template takes them from `enum_values(E)`, a constexpr function found by
  // argument-dependent lookup, which the emitter writes beside the enum, inside
  // its namespace; C++ code may specialise EnumTraits instead.
  template<class E>
  struct EnumTraits
  {
      static_assert(std::is_enum_v<E>, "EnumTraits is for enums");
      static constexpr auto values = enum_values(E{});
  };

  template<class E, class R>
  [[nodiscard]] constexpr std::optional<E> enumOf(R raw) noexcept
  {
      static_assert(std::is_enum_v<E>, "enumOf<E> needs an enum");
      static_assert(std::is_integral_v<R> && !std::is_same_v<R, bool>, "enumOf takes an integer");
      for(const E e : EnumTraits<E>::values)
      {
          if(std::cmp_equal(static_cast<std::underlying_type_t<E>>(e), raw))
          {
              return e;
          }
      }
      return std::nullopt;
  }
}
