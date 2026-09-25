// kira/main.hxx - the process entry a Kira `fx main` runs under (hosted).
//
// A module with `fx main` ends with
//
//     int main(int argc, char** argv)
//     {
//         return kira::rt::runMain(argc, argv, &ns::main);
//     }
//
// runMain keeps argc and argv for C++ seams (bibo::openCar hands them to the
// real Car), runs the Kira main, and turns an uncaught kira::Error into its
// message on stderr and exit status 70 (EX_SOFTWARE). kira::panic is not an
// exception: it aborts, and runMain never sees it.
#pragma once

#include "kira/rt.hxx"

namespace kira::rt
{
  namespace impl_
  {
    inline int argCount = 0;
    inline char** argValues = nullptr;

    inline void keep(int count, char** values) noexcept
    {
        argCount = count;
        argValues = values;
    }

    [[nodiscard]] inline int uncaught(const Error& e) noexcept
    {
        std::fflush(stdout);
        std::fprintf(stderr, "kira: %s\n", e.message.c_str());
        std::fflush(stderr);
        return 70;
    }
  }

  // The process's own argc and argv, for a C++ seam that must hand a C++ API
  // the originals.
  [[nodiscard]] inline int argc() noexcept
  {
      return impl_::argCount;
  }
  [[nodiscard]] inline char** argv() noexcept
  {
      return impl_::argValues;
  }

  // fx main: () Void
  inline int runMain(int count, char** values, void (*body)())
  {
      impl_::keep(count, values);
      try
      {
          body();
          return 0;
      }
      catch(const Error& e)
      {
          return impl_::uncaught(e);
      }
  }

  // fx main: () Int32
  inline int runMain(int count, char** values, std::int32_t (*body)())
  {
      impl_::keep(count, values);
      try
      {
          return static_cast<int>(body());
      }
      catch(const Error& e)
      {
          return impl_::uncaught(e);
      }
  }

  // fx main: (args: List<Str>) Int32, where args[0] is the program.
  inline int runMain(int count, char** values, std::int32_t (*body)(const List<Str>&))
  {
      impl_::keep(count, values);
      try
      {
          List<Str> args;
          args.reserve(count > 0 ? static_cast<Size>(count) : 0u);
          for(int i = 0; i < count; ++i)
          {
              args.emplace_back(values[i] != nullptr ? values[i] : "");
          }
          return static_cast<int>(body(args));
      }
      catch(const Error& e)
      {
          return impl_::uncaught(e);
      }
  }
}
