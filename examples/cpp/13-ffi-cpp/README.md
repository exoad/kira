# 13-ffi-cpp -- a C++ class and C functions through `@_extern`

`src/app/shape.kira` is Kira's view of `native/shape.hxx` (a C++ class, `shape::Box`,
and the 3-line seam `makeBox` that hands Kira a `kira::Rc<Box>`) and of `native/cshape.h`
(three C functions). It declares, it never defines: the generated `shape.kira.hxx`
includes the real headers and states one `KIRA_EXTERN_CHECK` per member, so a C++
signature that drifts from the Kira one fails the C++ build with
`Kira's Box.area no longer matches its C++ header` (design 7.2).

At a call, a `Str` goes in as `kira::ffi::in(s)` (which converts to `const char*`,
`std::string_view` or `const std::string&`, whichever the callee takes) and a `mut`
argument goes out as `kira::ffi::out(x)` (`T&` or `T*`). `main.kira` calls a method on
the class, a C function taking a string, and one writing through a pointer.

```bash
./gradlew installDist
bash examples/cpp/13-ffi-cpp/run.sh
```

The program's stdout is `expected.txt`. The run needs the C++ expression part (W2.3)
to lower `main`'s body; until it lands, `kira --target cpp` reports
`cpp.unsupported: the body of 'main' is not lowered yet`, and `shape.kira.hxx` (the
extern module) is the part this example already exercises.
