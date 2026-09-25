// kira/rt.hxx - the hosted Kira C++ runtime. Includes kira/core.hxx.
//
// Kira's builtin types ARE the standard types (Str is std::string, List is
// std::vector, a class reference is std::shared_ptr), so C++ shares them with
// no conversion. Kira's methods reach them through the free functions below,
// which the stdlib's *.bind.yaml `cpp:` entries name.
//
// Text is LOCALE-FREE: std::to_chars and std::from_chars never read the C
// locale, so a de_DE process still prints 0.25 and parses "2.50" as 2.5.
#pragma once

#include "kira/core.hxx"

#if !KIRA_PROFILE_HOSTED
#error "kira/rt.hxx is the hosted runtime; a freestanding module includes kira/core.hxx only"
#endif

#include <charconv>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <functional>
#include <initializer_list>
#include <memory>
#include <string>
#include <system_error>
#include <unordered_map>
#include <vector>

namespace kira
{
  using Str = std::string;
  template<class T>
  using List = std::vector<T>;
  template<class T>
  using Deque = std::deque<T>;

  // A class reference: atomic counts, so a Kira object may cross threads, and
  // non-intrusive, so C++ may construct a Kira class on the stack (D11).
  template<class T>
  using Rc = std::shared_ptr<T>;
  template<class T>
  using Weak = std::weak_ptr<T>;
  // Derived only by a class whose `this` escapes (shared_from_this).
  template<class C>
  using Shared = std::enable_shared_from_this<C>;
  // The spec's Ref<T> is Rc<Box<T>>, read and written through `.value`.
  template<class T>
  struct Box
  {
      T value;
  };
  template<class Sig>
  using Fn = std::function<Sig>;

  // What `throw "msg"` throws; `try ... on e: Str` catches it.
  struct Error
  {
      Str message;
  };

  // ---- Maybe<class>: a nullable Rc ------------------------------------------
  template<class U>
  struct MaybeOf<std::shared_ptr<U>>
  {
      using type = std::shared_ptr<U>;
  };

  template<class U>
  [[nodiscard]] bool isSome(const std::shared_ptr<U>& m) noexcept
  {
      return m != nullptr;
  }
  template<class U>
  [[nodiscard]] const std::shared_ptr<U>& unwrap(const std::shared_ptr<U>& m)
  {
      if(m == nullptr)
      {
          panic("unwrap of an empty Maybe");
      }
      return m;
  }
  template<class U>
  [[nodiscard]] std::shared_ptr<U> unwrap(std::shared_ptr<U>&& m)
  {
      if(m == nullptr)
      {
          panic("unwrap of an empty Maybe");
      }
      return std::move(m);
  }
  template<class U>
  [[nodiscard]] std::shared_ptr<U> unwrapOr(const std::shared_ptr<U>& m,
                                           const std::type_identity_t<std::shared_ptr<U>>& d)
  {
      return m != nullptr ? m : d;
  }
  // Member access on a generic T that is a class: *p, checked.
  template<class U>
  [[nodiscard]] U& deref(const std::shared_ptr<U>& p)
  {
      if(p == nullptr)
      {
          panic("member access through an empty reference");
      }
      return *p;
  }
  template<class U>
  [[nodiscard]] U& deref(std::shared_ptr<U>& p)
  {
      if(p == nullptr)
      {
          panic("member access through an empty reference");
      }
      return *p;
  }
  // Weak<C>.upgrade(): a Maybe<C>.
  template<class U>
  [[nodiscard]] std::shared_ptr<U> upgrade(const std::weak_ptr<U>& w) noexcept
  {
      return w.lock();
  }

  // ---- List and Deque --------------------------------------------------------
  template<class T, class A>
  [[nodiscard]] View<T> view(const std::vector<T, A>& v) noexcept
  {
      return View<T>(v.data(), v.size());
  }
  template<class T, class A>
  [[nodiscard]] MutView<T> mutView(std::vector<T, A>& v) noexcept
  {
      return MutView<T>(v.data(), v.size());
  }

  // xs[i]: checked; the mutable overloads give a place.
  template<class T, class A>
  [[nodiscard]] typename std::vector<T, A>::const_reference at(const std::vector<T, A>& v, Size i)
  {
      if constexpr(checked)
      {
          if(i >= v.size())
          {
              panic("index out of range");
          }
      }
      return v[i];
  }
  template<class T, class A>
  [[nodiscard]] typename std::vector<T, A>::reference at(std::vector<T, A>& v, Size i)
  {
      if constexpr(checked)
      {
          if(i >= v.size())
          {
              panic("index out of range");
          }
      }
      return v[i];
  }
  template<class T, class A>
  [[nodiscard]] typename std::deque<T, A>::const_reference at(const std::deque<T, A>& d, Size i)
  {
      if constexpr(checked)
      {
          if(i >= d.size())
          {
              panic("index out of range");
          }
      }
      return d[i];
  }
  template<class T, class A>
  [[nodiscard]] typename std::deque<T, A>::reference at(std::deque<T, A>& d, Size i)
  {
      if constexpr(checked)
      {
          if(i >= d.size())
          {
              panic("index out of range");
          }
      }
      return d[i];
  }

  // The List (and Arr<T>) methods that are not one std::vector member.
  namespace list
  {
    template<class T, class A>
    [[nodiscard]] Size size(const std::vector<T, A>& l) noexcept
    {
        return l.size();
    }
    template<class T, class A>
    [[nodiscard]] bool isEmpty(const std::vector<T, A>& l) noexcept
    {
        return l.empty();
    }
    template<class T, class A>
    void add(std::vector<T, A>& l, const std::type_identity_t<T>& v)
    {
        l.push_back(v);
    }
    template<class T, class A>
    void addAll(std::vector<T, A>& l, const std::vector<T, A>& more)
    {
        l.insert(l.end(), more.begin(), more.end());
    }
    template<class T, class A>
    [[nodiscard]] typename std::vector<T, A>::const_reference get(const std::vector<T, A>& l, Size i)
    {
        return ::kira::at(l, i);
    }
    template<class T, class A>
    void set(std::vector<T, A>& l, Size i, const std::type_identity_t<T>& v)
    {
        ::kira::at(l, i) = v;
    }
    template<class T, class A>
    T removeAt(std::vector<T, A>& l, Size i)
    {
        if(i >= l.size())
        {
            panic("index out of range");
        }
        T out = std::move(l[i]);
        l.erase(l.begin() + static_cast<std::ptrdiff_t>(i));
        return out;
    }
    template<class T, class A>
    void clear(std::vector<T, A>& l) noexcept
    {
        l.clear();
    }
    template<class T, class A>
    [[nodiscard]] bool contains(const std::vector<T, A>& l, const std::type_identity_t<T>& v)
    {
        for(const T& x : l)
        {
            if(x == v)
            {
                return true;
            }
        }
        return false;
    }
    template<class T, class A>
    [[nodiscard]] std::vector<T, A> clone(const std::vector<T, A>& l)
    {
        return l;
    }
  }

  // Deque.popFront() and popBack(): a Maybe, none when empty.
  template<class T, class A>
  Maybe<T> popFront(std::deque<T, A>& d)
  {
      if(d.empty())
      {
          return none;
      }
      Maybe<T> out = std::move(d.front());
      d.pop_front();
      return out;
  }
  template<class T, class A>
  Maybe<T> popBack(std::deque<T, A>& d)
  {
      if(d.empty())
      {
          return none;
      }
      Maybe<T> out = std::move(d.back());
      d.pop_back();
      return out;
  }

  // ---- Str methods (free functions over const Str&) --------------------------
  // A literal Str constant (a const char*) converts to every parameter here.
  namespace str
  {
    [[nodiscard]] inline Size length(const Str& s) noexcept
    {
        return s.size();
    }
    [[nodiscard]] inline Size size(const Str& s) noexcept
    {
        return s.size();
    }
    [[nodiscard]] inline bool isEmpty(const Str& s) noexcept
    {
        return s.empty();
    }
    // s[i]: checked.
    [[nodiscard]] inline Char at(const Str& s, Size i)
    {
        if constexpr(checked)
        {
            if(i >= s.size())
            {
                panic("index out of range");
            }
        }
        return s[i];
    }
    [[nodiscard]] inline Str charAt(const Str& s, Size i)
    {
        return Str(1, at(s, i));
    }
    // [begin, end), checked.
    [[nodiscard]] inline Str substring(const Str& s, Size begin, Size end)
    {
        if(begin > end || end > s.size())
        {
            panic("substring out of range");
        }
        return s.substr(begin, end - begin);
    }
    [[nodiscard]] inline bool contains(const Str& s, const Str& needle) noexcept
    {
        return s.find(needle) != Str::npos;
    }
    [[nodiscard]] inline bool startsWith(const Str& s, const Str& prefix) noexcept
    {
        return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
    }
    [[nodiscard]] inline bool endsWith(const Str& s, const Str& suffix) noexcept
    {
        return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
    }
    // Where needle first starts, or none.
    [[nodiscard]] inline Maybe<Size> find(const Str& s, const Str& needle) noexcept
    {
        const Size hit = s.find(needle);
        if(hit == Str::npos)
        {
            return none;
        }
        return hit;
    }
    [[nodiscard]] inline bool equals(const Str& a, const Str& b) noexcept
    {
        return a == b;
    }
    // djb2, the same number as the C and JS backends' hashCode.
    [[nodiscard]] inline std::int64_t hashCode(const Str& s) noexcept
    {
        std::uint64_t h = 5381;
        for(const Char c : s)
        {
            h = h * 33u + static_cast<std::uint64_t>(ord(c));
        }
        return static_cast<std::int64_t>(h);
    }
    // As the C backend: an empty delimiter gives the whole string back, and
    // empty pieces are kept.
    [[nodiscard]] inline List<Str> split(const Str& s, const Str& delimiter)
    {
        List<Str> out;
        if(delimiter.empty())
        {
            out.push_back(s);
            return out;
        }
        Size from = 0;
        for(;;)
        {
            const Size hit = s.find(delimiter, from);
            if(hit == Str::npos)
            {
                out.push_back(s.substr(from));
                return out;
            }
            out.push_back(s.substr(from, hit - from));
            from = hit + delimiter.size();
        }
    }
    // Space, tab, CR and LF, as the C and JS backends.
    [[nodiscard]] inline Str trim(const Str& s)
    {
        const auto blank = [](Char c) noexcept { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; };
        Size a = 0;
        Size b = s.size();
        while(a < b && blank(s[a]))
        {
            ++a;
        }
        while(b > a && blank(s[b - 1]))
        {
            --b;
        }
        return s.substr(a, b - a);
    }
    // ASCII only, as the C and JS backends.
    [[nodiscard]] inline Str toLower(const Str& s)
    {
        Str out = s;
        for(Char& c : out)
        {
            if(c >= 'A' && c <= 'Z')
            {
                c = static_cast<Char>(c - 'A' + 'a');
            }
        }
        return out;
    }
    [[nodiscard]] inline Str toUpper(const Str& s)
    {
        Str out = s;
        for(Char& c : out)
        {
            if(c >= 'a' && c <= 'z')
            {
                c = static_cast<Char>(c - 'a' + 'A');
            }
        }
        return out;
    }
    [[nodiscard]] inline View<Char> view(const Str& s) noexcept
    {
        return View<Char>(s.data(), s.size());
    }
    // Strict decimal: parseInt64 over the string's bytes.
    [[nodiscard]] inline std::optional<std::int64_t> toInt64(const Str& s) noexcept
    {
        return parseInt64(view(s));
    }
    // std::from_chars: locale-free and correctly rounded. Refuses empty text
    // and any trailing byte; allows one leading '+'.
    [[nodiscard]] inline std::optional<double> toFloat64(const Str& s) noexcept
    {
        const char* first = s.data();
        const char* const last = s.data() + s.size();
        if(first != last && *first == '+')
        {
            ++first;
            if(first != last && (*first == '+' || *first == '-'))
            {
                return none;
            }
        }
        if(first == last)
        {
            return none;
        }
        double v = 0.0;
        const std::from_chars_result r = std::from_chars(first, last, v);
        if(r.ec != std::errc() || r.ptr != last)
        {
            return none;
        }
        return v;
    }
    [[nodiscard]] inline Str padStart(const Str& s, Size width, Char fill)
    {
        if(s.size() >= width)
        {
            return s;
        }
        Str out(width - s.size(), fill);
        out += s;
        return out;
    }
  }

  // ---- text: `x as Str` and "${x}" ------------------------------------------
  namespace impl_
  {
    // One piece of text: a view of text that already exists, or number text
    // formatted into buf. Never copied, so ptr may point into buf.
    struct Piece
    {
        const char* ptr = "";
        Size len = 0;
        char buf[32] = {};
    };

    inline void put(Piece& p, const Str& s) noexcept
    {
        p.ptr = s.data();
        p.len = s.size();
    }
    inline void put(Piece& p, const char* s) noexcept
    {
        p.ptr = s != nullptr ? s : "";
        p.len = std::char_traits<char>::length(p.ptr);
    }
    inline void put(Piece& p, View<Char> s) noexcept
    {
        p.ptr = s.ptr;
        p.len = s.len;
    }
    inline void put(Piece& p, bool b) noexcept
    {
        put(p, b ? "true" : "false");
    }
    inline void put(Piece& p, Char c) noexcept
    {
        p.buf[0] = c;
        p.ptr = p.buf;
        p.len = 1;
    }
    template<class I>
      requires(std::is_integral_v<I> && !std::is_same_v<I, bool> && !std::is_same_v<I, Char>)
    void put(Piece& p, I v) noexcept
    {
        const std::to_chars_result r = std::to_chars(p.buf, p.buf + sizeof(p.buf), v);
        p.ptr = p.buf;
        p.len = static_cast<Size>(r.ptr - p.buf);
    }
    // Shortest text that reads back to the same float.
    inline void put(Piece& p, float v) noexcept
    {
        const std::to_chars_result r = std::to_chars(p.buf, p.buf + sizeof(p.buf), v);
        p.ptr = p.buf;
        p.len = static_cast<Size>(r.ptr - p.buf);
    }
    inline void put(Piece& p, double v) noexcept
    {
        const std::to_chars_result r = std::to_chars(p.buf, p.buf + sizeof(p.buf), v);
        p.ptr = p.buf;
        p.len = static_cast<Size>(r.ptr - p.buf);
    }
    // An integer enum prints its entry's name: nameOf(e), which the emitter
    // writes beside the enum and argument-dependent lookup finds.
    template<class E>
      requires std::is_enum_v<E>
    void put(Piece& p, E e) noexcept
    {
        put(p, static_cast<const char*>(nameOf(e)));
    }
  }

  [[nodiscard]] inline Str text(bool b)
  {
      return b ? Str("true") : Str("false");
  }
  [[nodiscard]] inline Str text(Char c)
  {
      return Str(1, c);
  }
  template<std::integral I>
    requires(!std::is_same_v<I, bool> && !std::is_same_v<I, Char>)
  [[nodiscard]] Str text(I v)
  {
      impl_::Piece p;
      impl_::put(p, v);
      return Str(p.ptr, p.len);
  }
  [[nodiscard]] inline Str text(float v)
  {
      impl_::Piece p;
      impl_::put(p, v);
      return Str(p.ptr, p.len);
  }
  [[nodiscard]] inline Str text(double v)
  {
      impl_::Piece p;
      impl_::put(p, v);
      return Str(p.ptr, p.len);
  }
  [[nodiscard]] inline Str text(const Str& s)
  {
      return s;
  }
  [[nodiscard]] inline Str text(const char* s)
  {
      return s != nullptr ? Str(s) : Str();
  }
  [[nodiscard]] inline Str text(View<Char> s)
  {
      return Str(s.ptr, s.len);
  }
  template<class E>
    requires std::is_enum_v<E>
  [[nodiscard]] Str text(E e)
  {
      impl_::Piece p;
      impl_::put(p, e);
      return Str(p.ptr, p.len);
  }

  // "a${x}b" lowers to cat("a", x, "b"): every piece is formatted in place, then
  // the result is allocated once.
  template<class... P>
  [[nodiscard]] Str cat(const P&... parts)
  {
      if constexpr(sizeof...(P) == 0)
      {
          return Str();
      }
      else
      {
          impl_::Piece pieces[sizeof...(P)];
          Size next = 0;
          ((impl_::put(pieces[next++], parts)), ...);
          Size total = 0;
          for(const impl_::Piece& p : pieces)
          {
              total += p.len;
          }
          Str out;
          out.reserve(total);
          for(const impl_::Piece& p : pieces)
          {
              out.append(p.ptr, p.len);
          }
          return out;
      }
  }

  // ---- trace, print: the C prelude's formats (D42) ---------------------------
  // Floats as printf's %g (six significant digits), Bool as 1 or 0, integers in
  // decimal, an enum as its base value, text as it is. Locale-free.
  namespace impl_
  {
    template<class T>
    void traced(Piece& p, const T& v) noexcept
    {
        if constexpr(std::is_same_v<T, bool>)
        {
            put(p, v ? "1" : "0");
        }
        else if constexpr(std::is_floating_point_v<T>)
        {
            const std::to_chars_result r =
                std::to_chars(p.buf, p.buf + sizeof(p.buf), static_cast<double>(v), std::chars_format::general, 6);
            p.ptr = p.buf;
            p.len = static_cast<Size>(r.ptr - p.buf);
        }
        else if constexpr(std::is_enum_v<T>)
        {
            put(p, static_cast<std::underlying_type_t<T>>(v));
        }
        else
        {
            put(p, v);
        }
    }

    template<class T>
    void emit(std::FILE* to, const T& v, bool newline) noexcept
    {
        Piece p;
        traced(p, v);
        std::fwrite(p.ptr, 1, p.len, to);
        if(newline)
        {
            std::fputc('\n', to);
        }
    }
  }

  template<class T>
  void trace(const T& v) noexcept
  {
      impl_::emit(stdout, v, true);
  }
  inline void trace() noexcept
  {
      std::fputc('\n', stdout);
  }
  template<class T>
  void print(const T& v) noexcept
  {
      impl_::emit(stdout, v, false);
  }
  template<class T>
  void println(const T& v) noexcept
  {
      impl_::emit(stdout, v, true);
  }
  template<class T>
  void eprint(const T& v) noexcept
  {
      impl_::emit(stderr, v, false);
  }

  // kira:io assert(condition, message): the C prelude's kira_assert. Named with a
  // trailing _ because <cassert> makes `assert` a macro.
  inline void assert_(bool condition, const Str& message) noexcept
  {
      if(!condition)
      {
          std::fflush(stdout);
          std::fprintf(stderr, "kira: assertion failed: %s\n", message.c_str());
          std::fflush(stderr);
          std::abort();
      }
  }

  // ---- Map and Set: insertion-ordered (D27) ---------------------------------
  // A vector in insertion order plus a hash index, so iteration is the same
  // on every backend and every standard library. Keys are scalars, Str or enums.
  namespace impl_
  {
    template<class K>
    inline constexpr bool isKey = std::is_arithmetic_v<K> || std::is_enum_v<K> || std::is_same_v<K, Str>;
  }

  template<class K, class V>
  class Map
  {
      static_assert(impl_::isKey<K>, "a Map key is a scalar, a Str or an enum");

  public:
      using Entry = Tuple2<K, V>;

      Map() = default;
      Map(std::initializer_list<Entry> entries)
      {
          for(const Entry& e : entries)
          {
              put(e.first, e.second);
          }
      }

      [[nodiscard]] Size size() const noexcept
      {
          return items_.size();
      }
      [[nodiscard]] bool isEmpty() const noexcept
      {
          return items_.empty();
      }
      // A new key goes last; an existing key keeps its place and takes the value.
      void put(const K& key, const V& value)
      {
          const auto hit = index_.find(key);
          if(hit != index_.end())
          {
              items_[hit->second].second = value;
              return;
          }
          index_.emplace(key, items_.size());
          items_.push_back(Entry{key, value});
      }
      [[nodiscard]] Maybe<V> get(const K& key) const
      {
          const auto hit = index_.find(key);
          if(hit == index_.end())
          {
              return none;
          }
          return items_[hit->second].second;
      }
      Maybe<V> remove(const K& key)
      {
          const auto hit = index_.find(key);
          if(hit == index_.end())
          {
              return none;
          }
          const Size pos = hit->second;
          Maybe<V> out = std::move(items_[pos].second);
          index_.erase(hit);
          items_.erase(items_.begin() + static_cast<std::ptrdiff_t>(pos));
          for(Size i = pos; i < items_.size(); ++i)
          {
              index_[items_[i].first] = i;
          }
          return out;
      }
      [[nodiscard]] bool containsKey(const K& key) const
      {
          return index_.find(key) != index_.end();
      }
      [[nodiscard]] bool containsValue(const V& value) const
      {
          for(const Entry& e : items_)
          {
              if(e.second == value)
              {
                  return true;
              }
          }
          return false;
      }
      [[nodiscard]] List<K> keys() const
      {
          List<K> out;
          out.reserve(items_.size());
          for(const Entry& e : items_)
          {
              out.push_back(e.first);
          }
          return out;
      }
      [[nodiscard]] List<V> valuesArr() const
      {
          List<V> out;
          out.reserve(items_.size());
          for(const Entry& e : items_)
          {
              out.push_back(e.second);
          }
          return out;
      }
      [[nodiscard]] List<Entry> entries() const
      {
          return items_;
      }
      void clear() noexcept
      {
          items_.clear();
          index_.clear();
      }
      // m[k] as a place: a missing key is added first, with V{}.
      V& operator[](const K& key)
      {
          const auto hit = index_.find(key);
          if(hit != index_.end())
          {
              return items_[hit->second].second;
          }
          index_.emplace(key, items_.size());
          items_.push_back(Entry{key, V{}});
          return items_.back().second;
      }
      // m[k] read: checked.
      [[nodiscard]] const V& at(const K& key) const
      {
          const auto hit = index_.find(key);
          if(hit == index_.end())
          {
              panic("no such key in the Map");
          }
          return items_[hit->second].second;
      }
      [[nodiscard]] typename List<Entry>::const_iterator begin() const noexcept
      {
          return items_.begin();
      }
      [[nodiscard]] typename List<Entry>::const_iterator end() const noexcept
      {
          return items_.end();
      }
      // The same keys with equal values, in any order.
      [[nodiscard]] bool operator==(const Map& other) const
      {
          if(items_.size() != other.items_.size())
          {
              return false;
          }
          for(const Entry& e : items_)
          {
              const auto hit = other.index_.find(e.first);
              if(hit == other.index_.end() || !(other.items_[hit->second].second == e.second))
              {
                  return false;
              }
          }
          return true;
      }

  private:
      List<Entry> items_;
      std::unordered_map<K, Size> index_;
  };

  template<class T>
  class Set
  {
      static_assert(impl_::isKey<T>, "a Set holds scalars, Str or enums");

  public:
      Set() = default;
      Set(std::initializer_list<T> values)
      {
          for(const T& v : values)
          {
              static_cast<void>(add(v));
          }
      }

      [[nodiscard]] Size size() const noexcept
      {
          return items_.size();
      }
      [[nodiscard]] bool isEmpty() const noexcept
      {
          return items_.empty();
      }
      // False when it was already there.
      bool add(const T& value)
      {
          if(index_.find(value) != index_.end())
          {
              return false;
          }
          index_.emplace(value, items_.size());
          items_.push_back(value);
          return true;
      }
      bool remove(const T& value)
      {
          const auto hit = index_.find(value);
          if(hit == index_.end())
          {
              return false;
          }
          const Size pos = hit->second;
          index_.erase(hit);
          items_.erase(items_.begin() + static_cast<std::ptrdiff_t>(pos));
          for(Size i = pos; i < items_.size(); ++i)
          {
              index_[items_[i]] = i;
          }
          return true;
      }
      [[nodiscard]] bool contains(const T& value) const
      {
          return index_.find(value) != index_.end();
      }
      [[nodiscard]] List<T> toArr() const
      {
          return items_;
      }
      void clear() noexcept
      {
          items_.clear();
          index_.clear();
      }
      [[nodiscard]] typename List<T>::const_iterator begin() const noexcept
      {
          return items_.begin();
      }
      [[nodiscard]] typename List<T>::const_iterator end() const noexcept
      {
          return items_.end();
      }
      [[nodiscard]] bool operator==(const Set& other) const
      {
          if(items_.size() != other.items_.size())
          {
              return false;
          }
          for(const T& v : items_)
          {
              if(!other.contains(v))
              {
                  return false;
              }
          }
          return true;
      }

  private:
      List<T> items_;
      std::unordered_map<T, Size> index_;
  };

  // ---- Stack and Queue: pop() is a Maybe --------------------------------------
  template<class T>
  class Stack
  {
  public:
      [[nodiscard]] Size size() const noexcept
      {
          return items_.size();
      }
      [[nodiscard]] bool isEmpty() const noexcept
      {
          return items_.empty();
      }
      void push(const T& value)
      {
          items_.push_back(value);
      }
      Maybe<T> pop()
      {
          if(items_.empty())
          {
              return none;
          }
          Maybe<T> out = std::move(items_.back());
          items_.pop_back();
          return out;
      }
      [[nodiscard]] Maybe<T> peek() const
      {
          if(items_.empty())
          {
              return none;
          }
          return items_.back();
      }
      void clear() noexcept
      {
          items_.clear();
      }

  private:
      List<T> items_;
  };

  template<class T>
  class Queue
  {
  public:
      [[nodiscard]] Size size() const noexcept
      {
          return items_.size();
      }
      [[nodiscard]] bool isEmpty() const noexcept
      {
          return items_.empty();
      }
      void enqueue(const T& value)
      {
          items_.push_back(value);
      }
      Maybe<T> dequeue()
      {
          if(items_.empty())
          {
              return none;
          }
          Maybe<T> out = std::move(items_.front());
          items_.pop_front();
          return out;
      }
      // push and pop are enqueue and dequeue, under the names Stack uses.
      void push(const T& value)
      {
          enqueue(value);
      }
      Maybe<T> pop()
      {
          return dequeue();
      }
      [[nodiscard]] Maybe<T> peek() const
      {
          if(items_.empty())
          {
              return none;
          }
          return items_.front();
      }
      void clear() noexcept
      {
          items_.clear();
      }

  private:
      std::deque<T> items_;
  };

  // ---- Result<T, E> ----------------------------------------------------------
  // Result<T, E>::success(v) or ::error(e). A default Result is an error holding
  // E{}: nothing has succeeded yet.
  template<class T, class E>
  class Result
  {
  public:
      Result() : error_(E{})
      {
      }
      [[nodiscard]] static Result success(T value)
      {
          return Result(OkTag{}, std::move(value));
      }
      [[nodiscard]] static Result error(E err)
      {
          return Result(ErrTag{}, std::move(err));
      }

      [[nodiscard]] bool isOk() const noexcept
      {
          return value_.has_value();
      }
      [[nodiscard]] bool isErr() const noexcept
      {
          return !value_.has_value();
      }
      [[nodiscard]] const T& unwrap() const
      {
          if(!value_.has_value())
          {
              panic("unwrap of an error Result");
          }
          return *value_;
      }
      [[nodiscard]] const E& unwrapErr() const
      {
          if(!error_.has_value())
          {
              panic("unwrapErr of a success Result");
          }
          return *error_;
      }

  private:
      struct OkTag
      {
      };
      struct ErrTag
      {
      };
      Result(OkTag, T value) : value_(std::move(value))
      {
      }
      Result(ErrTag, E err) : error_(std::move(err))
      {
      }

      std::optional<T> value_;
      std::optional<E> error_;
  };

  template<class T, class E>
  [[nodiscard]] bool isOk(const Result<T, E>& r) noexcept
  {
      return r.isOk();
  }
  template<class T, class E>
  [[nodiscard]] bool isErr(const Result<T, E>& r) noexcept
  {
      return r.isErr();
  }
  template<class T, class E>
  [[nodiscard]] const T& unwrap(const Result<T, E>& r)
  {
      return r.unwrap();
  }
  template<class T, class E>
  [[nodiscard]] const E& unwrapErr(const Result<T, E>& r)
  {
      return r.unwrapErr();
  }
}
