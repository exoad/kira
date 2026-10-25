// json_corpus IN OUT: json_corpus.py's values built through the Json API in its five forms, and its
// documents parsed, as <length>:<bytes> answers that json_corpus.py check holds to the py target's.
#include "kira/json.hxx"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>

namespace
{
  using kira::Rc;
  using kira::Size;
  using kira::Str;
  using kira::json::Json;

  struct Input
  {
      Str s;
      Size i = 0;

      char take()
      {
          if(i >= s.size())
          {
              std::fprintf(stderr, "json_corpus: the corpus ends early\n");
              std::exit(2);
          }
          return s[i++];
      }
      std::int64_t integer(char end)
      {
          const Size from = i;
          while(take() != end)
          {
          }
          std::int64_t v = 0;
          static_cast<void>(std::from_chars(s.data() + from, s.data() + i - 1, v));
          return v;
      }
      Str bytes()
      {
          const auto n = static_cast<Size>(integer(':'));
          Str out = s.substr(i, n);
          i += n;
          return out;
      }
  };

  Rc<Json> value(Input& in)
  {
      switch(in.take())
      {
          case 'n':
              return kira::json::null();
          case 't':
              return kira::json::ofBool(true);
          case 'f':
              return kira::json::ofBool(false);
          case 'i':
              return kira::json::ofInt(in.integer(';'));
          case 'd':
          {
              std::uint64_t bits = 0;
              static_cast<void>(std::from_chars(in.s.data() + in.i, in.s.data() + in.i + 16, bits, 16));
              in.i += 16;
              return kira::json::ofFloat(kira::bitCast<double>(bits));
          }
          case 's':
              return kira::json::ofStr(in.bytes());
          case 'a':
          {
              Rc<Json> a = kira::json::arr();
              for(std::int64_t n = in.integer(';'); n > 0; --n)
              {
                  a->add(value(in));
              }
              return a;
          }
          case 'o':
          {
              Rc<Json> o = kira::json::obj();
              for(std::int64_t n = in.integer(';'); n > 0; --n)
              {
                  const Str key = in.bytes();
                  o->put(key, value(in));
              }
              return o;
          }
          default:
              std::fprintf(stderr, "json_corpus: no value at byte %zu\n", in.i - 1);
              std::exit(2);
      }
  }

  void answer(std::ofstream& out, const Str& s)
  {
      out << s.size() << ':' << s << '\n';
  }
}

int main(int argc, char** argv)
{
    if(argc != 3)
    {
        std::fprintf(stderr, "usage: json_corpus IN OUT\n");
        return 2;
    }
    std::ifstream f(argv[1], std::ios::binary);
    std::ofstream out(argv[2], std::ios::binary);
    if(!f || !out)
    {
        std::fprintf(stderr, "json_corpus: cannot open %s or %s\n", argv[1], argv[2]);
        return 2;
    }
    Input in;
    in.s.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    Size values = 0;
    Size documents = 0;
    while(in.i < in.s.size())
    {
        const char kind = in.take();
        if(kind == 'D')
        {
            const Rc<Json> v = value(in);
            answer(out, v->dump());
            answer(out, v->pretty(0, true));
            answer(out, v->pretty(1, false));
            answer(out, v->pretty(2, true));
            answer(out, v->pretty(4, false));
            ++values;
        }
        else if(kind == 'P')
        {
            const Rc<Json> v = kira::json::parse(in.bytes());
            const Str why = kira::json::error();
            answer(out, why.empty() ? "ok " + v->dump() + "\n" + v->pretty(1, false) : "error " + why);
            ++documents;
        }
        else if(kind != '\n')
        {
            std::fprintf(stderr, "json_corpus: no record at byte %zu\n", in.i - 1);
            return 2;
        }
    }
    out.close();
    std::printf("json_corpus: %zu values, %zu documents\n", values, documents);
    return 0;
}
