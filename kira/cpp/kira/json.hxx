// kira/json.hxx - the hosted runtime of kira:json (D60): one Json class, written as CPython's
// json.dumps writes it (D62) and read as json.loads reads it (D63).
#pragma once

#include "kira/rt.hxx"

#include <charconv>
#include <cstdint>
#include <limits>
#include <system_error>
#include <utility>
#include <variant>

namespace kira::json
{
  class Json final
  {
  public:
      using Arr = List<Rc<Json>>;
      using Obj = Map<Str, Rc<Json>>;
      using Value = std::variant<std::monostate, bool, std::int64_t, double, Str, Arr, Obj>;

      Json() = default;
      explicit Json(Value v)
          : v_(std::move(v))
      {
      }
      Json(const Json&) = delete;
      Json& operator=(const Json&) = delete;

      [[nodiscard]] bool isNull() const noexcept
      {
          return std::holds_alternative<std::monostate>(v_);
      }
      [[nodiscard]] bool isBool() const noexcept
      {
          return std::holds_alternative<bool>(v_);
      }
      [[nodiscard]] bool isInt() const noexcept
      {
          return std::holds_alternative<std::int64_t>(v_);
      }
      [[nodiscard]] bool isFloat() const noexcept
      {
          return std::holds_alternative<double>(v_);
      }
      [[nodiscard]] bool isStr() const noexcept
      {
          return std::holds_alternative<Str>(v_);
      }
      [[nodiscard]] bool isArr() const noexcept
      {
          return std::holds_alternative<Arr>(v_);
      }
      [[nodiscard]] bool isObj() const noexcept
      {
          return std::holds_alternative<Obj>(v_);
      }

      [[nodiscard]] Maybe<bool> asBool() const
      {
          if(const bool* b = std::get_if<bool>(&v_))
          {
              return *b;
          }
          return none;
      }
      [[nodiscard]] Maybe<std::int64_t> asInt64() const
      {
          if(const std::int64_t* i = std::get_if<std::int64_t>(&v_))
          {
              return *i;
          }
          return none;
      }
      [[nodiscard]] Maybe<double> asFloat64() const
      {
          if(const double* d = std::get_if<double>(&v_))
          {
              return *d;
          }
          if(const std::int64_t* i = std::get_if<std::int64_t>(&v_))
          {
              return static_cast<double>(*i);
          }
          return none;
      }
      [[nodiscard]] Maybe<Str> asStr() const
      {
          if(const Str* s = std::get_if<Str>(&v_))
          {
              return *s;
          }
          return none;
      }

      [[nodiscard]] Rc<Json> get(const Str& key) const;
      [[nodiscard]] Rc<Json> at(Size index) const;
      [[nodiscard]] bool has(const Str& key) const
      {
          const Obj* o = std::get_if<Obj>(&v_);
          return o != nullptr && o->containsKey(key);
      }
      [[nodiscard]] List<Str> keys() const
      {
          const Obj* o = std::get_if<Obj>(&v_);
          return o != nullptr ? o->keys() : List<Str>{};
      }
      [[nodiscard]] Size size() const noexcept
      {
          if(const Arr* a = std::get_if<Arr>(&v_))
          {
              return a->size();
          }
          if(const Obj* o = std::get_if<Obj>(&v_))
          {
              return o->size();
          }
          return 0;
      }

      void put(const Str& key, const Rc<Json>& value)
      {
          Obj* o = std::get_if<Obj>(&v_);
          if(o == nullptr)
          {
              panic("Json.put on a Json that is no object");
          }
          o->put(key, value);
      }
      void add(const Rc<Json>& value)
      {
          Arr* a = std::get_if<Arr>(&v_);
          if(a == nullptr)
          {
              panic("Json.add on a Json that is no array");
          }
          a->push_back(value);
      }

      [[nodiscard]] Str dump() const;
      [[nodiscard]] Str pretty(Size indent, bool ascii) const;

      [[nodiscard]] const Value& value() const noexcept
      {
          return v_;
      }

  private:
      Value v_;
  };

  // One shared JSON null: nothing can change it, as put and add refuse it.
  [[nodiscard]] inline Rc<Json> null()
  {
      static const Rc<Json> shared = std::make_shared<Json>();
      return shared;
  }
  [[nodiscard]] inline Rc<Json> obj()
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<Json::Obj>));
  }
  [[nodiscard]] inline Rc<Json> arr()
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<Json::Arr>));
  }
  [[nodiscard]] inline Rc<Json> ofBool(bool v)
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<bool>, v));
  }
  [[nodiscard]] inline Rc<Json> ofInt(std::int64_t v)
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<std::int64_t>, v));
  }
  [[nodiscard]] inline Rc<Json> ofFloat(double v)
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<double>, v));
  }
  [[nodiscard]] inline Rc<Json> ofStr(Str v)
  {
      return std::make_shared<Json>(Json::Value(std::in_place_type<Str>, std::move(v)));
  }
  [[nodiscard]] inline Rc<Json> ofMaybe(const Maybe<bool>& v)
  {
      return v.has_value() ? ofBool(*v) : null();
  }
  [[nodiscard]] inline Rc<Json> ofMaybe(const Maybe<std::int64_t>& v)
  {
      return v.has_value() ? ofInt(*v) : null();
  }
  [[nodiscard]] inline Rc<Json> ofMaybe(const Maybe<double>& v)
  {
      return v.has_value() ? ofFloat(*v) : null();
  }
  [[nodiscard]] inline Rc<Json> ofMaybe(const Maybe<Str>& v)
  {
      return v.has_value() ? ofStr(*v) : null();
  }

  inline Rc<Json> Json::get(const Str& key) const
  {
      if(const Obj* o = std::get_if<Obj>(&v_))
      {
          Maybe<Rc<Json>> hit = o->get(key);
          if(hit)
          {
              return hit;
          }
      }
      return null();
  }
  inline Rc<Json> Json::at(Size index) const
  {
      const Arr* a = std::get_if<Arr>(&v_);
      return a != nullptr && index < a->size() ? (*a)[index] : null();
  }

  namespace impl_
  {
    inline constexpr Size MAX_DEPTH = 512;
    inline constexpr std::uint32_t ILL_FORMED = 0x110000u;

    [[nodiscard]] inline Str& lastError()
    {
        thread_local Str reason;
        return reason;
    }

    // One code point of s at i, a surrogate's three bytes read as it (they are what a lone
    // \ud800 parses to, as a Python str holds it); each maximal ill-formed subpart is ILL_FORMED.
    [[nodiscard]] inline std::uint32_t next(const Str& s, Size& i) noexcept
    {
        const auto lead = static_cast<std::uint8_t>(s[i]);
        ++i;
        if(lead < 0x80u)
        {
            return lead;
        }
        Size need = 0;
        std::uint32_t cp = 0;
        std::uint8_t lo = 0x80u;
        std::uint8_t hi = 0xBFu;
        if(lead >= 0xC2u && lead <= 0xDFu)
        {
            need = 1;
            cp = lead & 0x1Fu;
        }
        else if(lead >= 0xE0u && lead <= 0xEFu)
        {
            need = 2;
            cp = lead & 0x0Fu;
            lo = lead == 0xE0u ? std::uint8_t{0xA0u} : lo;
        }
        else if(lead >= 0xF0u && lead <= 0xF4u)
        {
            need = 3;
            cp = lead & 0x07u;
            lo = lead == 0xF0u ? std::uint8_t{0x90u} : lo;
            hi = lead == 0xF4u ? std::uint8_t{0x8Fu} : hi;
        }
        Size seen = 0;
        while(seen < need && i < s.size())
        {
            const auto b = static_cast<std::uint8_t>(s[i]);
            if(b < lo || b > hi)
            {
                break;
            }
            cp = (cp << 6) | (b & 0x3Fu);
            ++i;
            ++seen;
            lo = 0x80u;
            hi = 0xBFu;
        }
        return need != 0 && seen == need ? cp : ILL_FORMED;
    }

    inline void hex4(Str& out, std::uint32_t u)
    {
        static constexpr char digits[] = "0123456789abcdef";
        out += "\\u";
        out += digits[(u >> 12) & 0xFu];
        out += digits[(u >> 8) & 0xFu];
        out += digits[(u >> 4) & 0xFu];
        out += digits[u & 0xFu];
    }

    inline void text(Str& out, const Str& s, bool ascii)
    {
        out += '"';
        Size i = 0;
        while(i < s.size())
        {
            const Size from = i;
            const std::uint32_t cp = next(s, i);
            switch(cp)
            {
                case '"':
                    out += "\\\"";
                    continue;
                case '\\':
                    out += "\\\\";
                    continue;
                case '\n':
                    out += "\\n";
                    continue;
                case '\r':
                    out += "\\r";
                    continue;
                case '\t':
                    out += "\\t";
                    continue;
                case '\b':
                    out += "\\b";
                    continue;
                case '\f':
                    out += "\\f";
                    continue;
                default:
                    break;
            }
            if(cp < 0x20u)
            {
                hex4(out, cp);
            }
            else if(cp < 0x7Fu || (!ascii && cp != ILL_FORMED))
            {
                out.append(s, from, i - from);
            }
            else if(cp == ILL_FORMED)
            {
                if(ascii)
                {
                    hex4(out, 0xFFFDu);
                }
                else
                {
                    out += "\xEF\xBF\xBD";
                }
            }
            else if(cp < 0x10000u)
            {
                hex4(out, cp);
            }
            else
            {
                const std::uint32_t n = cp - 0x10000u;
                hex4(out, 0xD800u | (n >> 10));
                hex4(out, 0xDC00u | (n & 0x3FFu));
            }
        }
        out += '"';
    }

    // float.__repr__: the shortest digits that read back, fixed unless the decimal point is
    // more than 16 places right of the first digit or 4 or more left of it.
    inline void real(Str& out, double v)
    {
        if(v != v)
        {
            out += "NaN";
            return;
        }
        if(v == std::numeric_limits<double>::infinity())
        {
            out += "Infinity";
            return;
        }
        if(v == -std::numeric_limits<double>::infinity())
        {
            out += "-Infinity";
            return;
        }
        char buf[40];
        const std::to_chars_result r = std::to_chars(buf, buf + sizeof(buf), v, std::chars_format::scientific);
        const char* p = buf;
        if(*p == '-')
        {
            out += '-';
            ++p;
        }
        char digits[24];
        Size n = 0;
        for(; p < r.ptr && *p != 'e'; ++p)
        {
            if(*p != '.')
            {
                digits[n++] = *p;
            }
        }
        int exp = 0;
        static_cast<void>(std::from_chars(p + (p[1] == '+' ? 2 : 1), r.ptr, exp));
        const int point = exp + 1;
        if(point <= -4 || point > 16)
        {
            out += digits[0];
            if(n > 1)
            {
                out += '.';
                out.append(digits + 1, n - 1);
            }
            out += exp < 0 ? "e-" : "e+";
            const int mag = exp < 0 ? -exp : exp;
            if(mag < 10)
            {
                out += '0';
            }
            out += kira::text(mag);
        }
        else if(point <= 0)
        {
            out += "0.";
            out.append(static_cast<Size>(-point), '0');
            out.append(digits, n);
        }
        else if(static_cast<Size>(point) >= n)
        {
            out.append(digits, n);
            out.append(static_cast<Size>(point) - n, '0');
            out += ".0";
        }
        else
        {
            out.append(digits, static_cast<Size>(point));
            out += '.';
            out.append(digits + point, n - static_cast<Size>(point));
        }
    }

    class Writer
    {
    public:
        Writer(bool pretty, Size indent, bool ascii)
            : pretty_(pretty),
              indent_(indent),
              ascii_(ascii)
        {
        }

        void write(const Json& j, Size level)
        {
            const Json::Value& v = j.value();
            if(const bool* b = std::get_if<bool>(&v))
            {
                out += *b ? "true" : "false";
            }
            else if(const std::int64_t* i = std::get_if<std::int64_t>(&v))
            {
                out += kira::text(*i);
            }
            else if(const double* d = std::get_if<double>(&v))
            {
                real(out, *d);
            }
            else if(const Str* s = std::get_if<Str>(&v))
            {
                text(out, *s, ascii_);
            }
            else if(const Json::Arr* a = std::get_if<Json::Arr>(&v))
            {
                if(a->empty())
                {
                    out += "[]";
                    return;
                }
                enter(j);
                out += '[';
                for(Size k = 0; k < a->size(); ++k)
                {
                    between(k, level + 1);
                    write(*(*a)[k], level + 1);
                }
                close(level, ']');
            }
            else if(const Json::Obj* o = std::get_if<Json::Obj>(&v))
            {
                if(o->isEmpty())
                {
                    out += "{}";
                    return;
                }
                enter(j);
                out += '{';
                Size k = 0;
                for(const auto& e : *o)
                {
                    between(k++, level + 1);
                    text(out, e.first, ascii_);
                    out += ": ";
                    write(*e.second, level + 1);
                }
                close(level, '}');
            }
            else
            {
                out += "null";
            }
        }

        Str out;

    private:
        // json.dumps raises ValueError("Circular reference detected") here.
        void enter(const Json& j)
        {
            for(const Json* o : open_)
            {
                if(o == &j)
                {
                    panic("a Json that holds itself");
                }
            }
            open_.push_back(&j);
        }
        void between(Size k, Size level)
        {
            if(k > 0)
            {
                out += pretty_ ? "," : ", ";
            }
            if(pretty_)
            {
                out += '\n';
                out.append(indent_ * level, ' ');
            }
        }
        void close(Size level, char end)
        {
            open_.pop_back();
            if(pretty_)
            {
                out += '\n';
                out.append(indent_ * level, ' ');
            }
            out += end;
        }

        bool pretty_;
        Size indent_;
        bool ascii_;
        List<const Json*> open_;
    };

    [[nodiscard]] inline bool digit(char c) noexcept
    {
        return c >= '0' && c <= '9';
    }

    inline void utf8(Str& out, std::uint32_t cp)
    {
        if(cp < 0x80u)
        {
            out += static_cast<char>(cp);
        }
        else if(cp < 0x800u)
        {
            out += static_cast<char>(0xC0u | (cp >> 6));
            out += static_cast<char>(0x80u | (cp & 0x3Fu));
        }
        else if(cp < 0x10000u)
        {
            out += static_cast<char>(0xE0u | (cp >> 12));
            out += static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu));
            out += static_cast<char>(0x80u | (cp & 0x3Fu));
        }
        else
        {
            out += static_cast<char>(0xF0u | (cp >> 18));
            out += static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu));
            out += static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu));
            out += static_cast<char>(0x80u | (cp & 0x3Fu));
        }
    }

    // CPython 3.10's C scanner (Modules/_json.c) step for step, over bytes: every branch it takes
    // reads an ASCII character, and a position it reports is counted in code points.
    class Reader
    {
    public:
        explicit Reader(const Str& s)
            : s_(s),
              len_(s.size())
        {
        }

        [[nodiscard]] Rc<Json> document()
        {
            if(s_.compare(0, 3, "\xEF\xBB\xBF") == 0)
            {
                return fail("Unexpected UTF-8 BOM (decode using utf-8-sig)", 0);
            }
            Size next = 0;
            Rc<Json> v = value(skip(0), next);
            if(!v)
            {
                return nullptr;
            }
            const Size end = skip(next);
            if(end != len_)
            {
                return fail("Extra data", end);
            }
            return v;
        }

    private:
        [[nodiscard]] Size skip(Size i) const noexcept
        {
            while(i < len_ && (s_[i] == ' ' || s_[i] == '\t' || s_[i] == '\n' || s_[i] == '\r'))
            {
                ++i;
            }
            return i;
        }

        [[nodiscard]] bool word(Size i, const char* w) const noexcept
        {
            const Size n = std::char_traits<char>::length(w);
            return i + n <= len_ && s_.compare(i, n, w) == 0;
        }

        Rc<Json> fail(const char* msg, Size at)
        {
            Size line = 1;
            Size chars = 0;
            Size column = 0;
            bool newline = false;
            for(Size i = 0; i < at; ++i)
            {
                if((static_cast<std::uint8_t>(s_[i]) & 0xC0u) != 0x80u)
                {
                    if(s_[i] == '\n')
                    {
                        ++line;
                        column = chars;
                        newline = true;
                    }
                    ++chars;
                }
            }
            const Size col = newline ? chars - column : chars + 1;
            lastError() = Str(msg) + ": line " + kira::text(line) + " column " + kira::text(col) + " (char " + kira::text(chars) + ")";
            return nullptr;
        }

        Rc<Json> value(Size idx, Size& next)
        {
            if(idx >= len_)
            {
                return fail("Expecting value", idx);
            }
            switch(s_[idx])
            {
                case '"':
                {
                    Str t;
                    return string(idx + 1, next, t) ? ofStr(std::move(t)) : nullptr;
                }
                case '{':
                case '[':
                {
                    if(depth_ == MAX_DEPTH)
                    {
                        return fail("Nesting deeper than 512", idx);
                    }
                    ++depth_;
                    Rc<Json> r = s_[idx] == '{' ? object(idx + 1, next) : array(idx + 1, next);
                    --depth_;
                    return r;
                }
                case 'n':
                    if(word(idx, "null"))
                    {
                        next = idx + 4;
                        return null();
                    }
                    break;
                case 't':
                    if(word(idx, "true"))
                    {
                        next = idx + 4;
                        return ofBool(true);
                    }
                    break;
                case 'f':
                    if(word(idx, "false"))
                    {
                        next = idx + 5;
                        return ofBool(false);
                    }
                    break;
                case 'N':
                    if(word(idx, "NaN"))
                    {
                        next = idx + 3;
                        return ofFloat(std::numeric_limits<double>::quiet_NaN());
                    }
                    break;
                case 'I':
                    if(word(idx, "Infinity"))
                    {
                        next = idx + 8;
                        return ofFloat(std::numeric_limits<double>::infinity());
                    }
                    break;
                case '-':
                    if(word(idx, "-Infinity"))
                    {
                        next = idx + 9;
                        return ofFloat(-std::numeric_limits<double>::infinity());
                    }
                    break;
                default:
                    break;
            }
            return number(idx, next);
        }

        Rc<Json> object(Size idx, Size& next)
        {
            Rc<Json> out = obj();
            idx = skip(idx);
            if(idx >= len_ || s_[idx] != '}')
            {
                while(true)
                {
                    if(idx >= len_ || s_[idx] != '"')
                    {
                        return fail("Expecting property name enclosed in double quotes", idx);
                    }
                    Str key;
                    Size after = 0;
                    if(!string(idx + 1, after, key))
                    {
                        return nullptr;
                    }
                    idx = skip(after);
                    if(idx >= len_ || s_[idx] != ':')
                    {
                        return fail("Expecting ':' delimiter", idx);
                    }
                    idx = skip(idx + 1);
                    Rc<Json> v = value(idx, after);
                    if(!v)
                    {
                        return nullptr;
                    }
                    out->put(key, v);
                    idx = skip(after);
                    if(idx < len_ && s_[idx] == '}')
                    {
                        break;
                    }
                    if(idx >= len_ || s_[idx] != ',')
                    {
                        return fail("Expecting ',' delimiter", idx);
                    }
                    idx = skip(idx + 1);
                }
            }
            next = idx + 1;
            return out;
        }

        Rc<Json> array(Size idx, Size& next)
        {
            Rc<Json> out = arr();
            idx = skip(idx);
            if(idx >= len_ || s_[idx] != ']')
            {
                while(true)
                {
                    Size after = 0;
                    Rc<Json> v = value(idx, after);
                    if(!v)
                    {
                        return nullptr;
                    }
                    out->add(v);
                    idx = skip(after);
                    if(idx < len_ && s_[idx] == ']')
                    {
                        break;
                    }
                    if(idx >= len_ || s_[idx] != ',')
                    {
                        return fail("Expecting ',' delimiter", idx);
                    }
                    idx = skip(idx + 1);
                }
            }
            next = idx + 1;
            return out;
        }

        [[nodiscard]] bool hex(Size at, std::uint32_t& out) const noexcept
        {
            out = 0;
            for(Size i = at; i < at + 4; ++i)
            {
                const char c = s_[i];
                std::uint32_t d = 0;
                if(digit(c))
                {
                    d = static_cast<std::uint32_t>(c - '0');
                }
                else if(c >= 'a' && c <= 'f')
                {
                    d = static_cast<std::uint32_t>(c - 'a' + 10);
                }
                else if(c >= 'A' && c <= 'F')
                {
                    d = static_cast<std::uint32_t>(c - 'A' + 10);
                }
                else
                {
                    return false;
                }
                out = (out << 4) | d;
            }
            return true;
        }

        bool string(Size end, Size& next, Str& out)
        {
            const Size begin = end - 1;
            while(true)
            {
                Size n = end;
                char c = 0;
                for(; n < len_; ++n)
                {
                    c = s_[n];
                    if(c == '"' || c == '\\')
                    {
                        break;
                    }
                    if(static_cast<std::uint8_t>(c) <= 0x1Fu)
                    {
                        fail("Invalid control character at", n);
                        return false;
                    }
                }
                if(n == len_)
                {
                    fail("Unterminated string starting at", begin);
                    return false;
                }
                out.append(s_, end, n - end);
                ++n;
                if(c == '"')
                {
                    next = n;
                    return true;
                }
                if(n == len_)
                {
                    fail("Unterminated string starting at", begin);
                    return false;
                }
                c = s_[n];
                if(c != 'u')
                {
                    end = n + 1;
                    switch(c)
                    {
                        case '"':
                        case '\\':
                        case '/':
                            out += c;
                            break;
                        case 'b':
                            out += '\b';
                            break;
                        case 'f':
                            out += '\f';
                            break;
                        case 'n':
                            out += '\n';
                            break;
                        case 'r':
                            out += '\r';
                            break;
                        case 't':
                            out += '\t';
                            break;
                        default:
                            fail("Invalid \\escape", end - 2);
                            return false;
                    }
                    continue;
                }
                ++n;
                end = n + 4;
                std::uint32_t cp = 0;
                if(end >= len_ || !hex(n, cp))
                {
                    fail("Invalid \\uXXXX escape", n - 1);
                    return false;
                }
                // A high surrogate takes the escape after it when that is a low one, as the scanner
                // does only with one more character after it, and refuses bad hex in it either way.
                if(cp >= 0xD800u && cp <= 0xDBFFu && end + 6 < len_ && s_[end] == '\\' && s_[end + 1] == 'u')
                {
                    std::uint32_t low = 0;
                    if(!hex(end + 2, low))
                    {
                        fail("Invalid \\uXXXX escape", end + 1);
                        return false;
                    }
                    if(low >= 0xDC00u && low <= 0xDFFFu)
                    {
                        cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                        end += 6;
                    }
                }
                utf8(out, cp);
            }
        }

        Rc<Json> number(Size start, Size& next)
        {
            Size idx = start;
            if(s_[idx] == '-')
            {
                ++idx;
                if(idx >= len_)
                {
                    return fail("Expecting value", start);
                }
            }
            if(s_[idx] >= '1' && s_[idx] <= '9')
            {
                ++idx;
                while(idx < len_ && digit(s_[idx]))
                {
                    ++idx;
                }
            }
            else if(s_[idx] == '0')
            {
                ++idx;
            }
            else
            {
                return fail("Expecting value", start);
            }
            bool isFloat = false;
            if(idx + 1 < len_ && s_[idx] == '.' && digit(s_[idx + 1]))
            {
                isFloat = true;
                idx += 2;
                while(idx < len_ && digit(s_[idx]))
                {
                    ++idx;
                }
            }
            if(idx + 1 < len_ && (s_[idx] == 'e' || s_[idx] == 'E'))
            {
                const Size e = idx;
                ++idx;
                if(idx + 1 < len_ && (s_[idx] == '-' || s_[idx] == '+'))
                {
                    ++idx;
                }
                while(idx < len_ && digit(s_[idx]))
                {
                    ++idx;
                }
                if(digit(s_[idx - 1]))
                {
                    isFloat = true;
                }
                else
                {
                    idx = e;
                }
            }
            next = idx;
            const char* first = s_.data() + start;
            const char* last = s_.data() + idx;
            if(!isFloat)
            {
                std::int64_t i = 0;
                if(std::from_chars(first, last, i).ec != std::errc())
                {
                    return fail("Integer out of Int64 range", start);
                }
                return ofInt(i);
            }
            double d = 0.0;
            if(std::from_chars(first, last, d).ec == std::errc::result_out_of_range)
            {
                d = outOfRange(first, last);
            }
            return ofFloat(d);
        }

        // float() of digits past a double's range: an infinity or a zero, signed as the text is.
        [[nodiscard]] static double outOfRange(const char* first, const char* last) noexcept
        {
            const bool negative = *first == '-';
            const char* p = negative ? first + 1 : first;
            std::int64_t point = 0;
            bool fraction = false;
            bool seen = false;
            for(; p < last && *p != 'e' && *p != 'E'; ++p)
            {
                if(*p == '.')
                {
                    fraction = true;
                    continue;
                }
                point += fraction ? 0 : 1;
                seen = seen || *p != '0';
                point -= seen ? 0 : 1;
            }
            std::int64_t exp = 0;
            bool expNegative = false;
            if(p < last)
            {
                ++p;
                expNegative = *p == '-';
                for(p += (*p == '-' || *p == '+') ? 1 : 0; p < last; ++p)
                {
                    exp = exp < 100000000 ? exp * 10 + (*p - '0') : exp;
                }
            }
            const bool huge = (expNegative ? -exp : exp) + point > 0;
            const double m = huge ? std::numeric_limits<double>::infinity() : 0.0;
            return negative ? -m : m;
        }

        const Str& s_;
        Size len_;
        Size depth_ = 0;
    };
  }

  inline Str Json::dump() const
  {
      impl_::Writer w(false, 0, true);
      w.write(*this, 0);
      return std::move(w.out);
  }
  inline Str Json::pretty(Size indent, bool ascii) const
  {
      impl_::Writer w(true, indent, ascii);
      w.write(*this, 0);
      return std::move(w.out);
  }

  // JSON null when text is no JSON document, with the reason in error(); a valid "null" is null too.
  [[nodiscard]] inline Rc<Json> parse(const Str& text)
  {
      impl_::lastError().clear();
      Rc<Json> v = impl_::Reader(text).document();
      return v ? v : null();
  }
  // Why the last parse on this thread failed, json.loads's own words; "" after one that did not.
  [[nodiscard]] inline Str error()
  {
      return impl_::lastError();
  }
}
