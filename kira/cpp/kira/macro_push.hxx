// kira/macro_push.hxx - opens the macro guard around a generated module.
//
// <windows.h>, <math.h> and POSIX headers define these names as object-like
// macros, and a Kira module may declare any of them (an enum entry ERROR, a
// constant IN, a function min). A generated header includes this after its own
// includes and kira/macro_pop.hxx at its end, so its declarations compile after
// <windows.h> and the caller gets every macro back afterwards. No include guard:
// each inclusion pushes once, and the matching macro_pop.hxx pops once.
//
// A C++ caller that includes <windows.h> still cannot spell ns::Level::ERROR,
// because the macro expands at the use site; that is why a pub name on this
// list is a cpp.macro-name diagnostic (D35).
#pragma push_macro("ERROR")
#undef ERROR
#pragma push_macro("IN")
#undef IN
#pragma push_macro("OUT")
#undef OUT
#pragma push_macro("OPTIONAL")
#undef OPTIONAL
#pragma push_macro("DELETE")
#undef DELETE
#pragma push_macro("TRUE")
#undef TRUE
#pragma push_macro("FALSE")
#undef FALSE
#pragma push_macro("INFINITE")
#undef INFINITE
#pragma push_macro("IGNORE")
#undef IGNORE
#pragma push_macro("NEAR")
#undef NEAR
#pragma push_macro("FAR")
#undef FAR
#pragma push_macro("CONST")
#undef CONST
#pragma push_macro("VOID")
#undef VOID
#pragma push_macro("CALLBACK")
#undef CALLBACK
#pragma push_macro("ABSOLUTE")
#undef ABSOLUTE
#pragma push_macro("RELATIVE")
#undef RELATIVE
#pragma push_macro("TRANSPARENT")
#undef TRANSPARENT
#pragma push_macro("OPAQUE")
#undef OPAQUE
#pragma push_macro("ALTERNATE")
#undef ALTERNATE
#pragma push_macro("WINDING")
#undef WINDING
#pragma push_macro("DIFFERENCE")
#undef DIFFERENCE
#pragma push_macro("NO_ERROR")
#undef NO_ERROR
#pragma push_macro("PASCAL")
#undef PASCAL
#pragma push_macro("WINAPI")
#undef WINAPI
#pragma push_macro("APIENTRY")
#undef APIENTRY
#pragma push_macro("CDECL")
#undef CDECL
#pragma push_macro("STRICT")
#undef STRICT
#pragma push_macro("MAX_PATH")
#undef MAX_PATH
#pragma push_macro("WAIT_TIMEOUT")
#undef WAIT_TIMEOUT
#pragma push_macro("WAIT_FAILED")
#undef WAIT_FAILED
#pragma push_macro("SYNCHRONIZE")
#undef SYNCHRONIZE
#pragma push_macro("interface")
#undef interface
#pragma push_macro("small")
#undef small
#pragma push_macro("hyper")
#undef hyper
#pragma push_macro("near")
#undef near
#pragma push_macro("far")
#undef far
#pragma push_macro("pascal")
#undef pascal
#pragma push_macro("cdecl")
#undef cdecl
#pragma push_macro("EOF")
#undef EOF
#pragma push_macro("INFINITY")
#undef INFINITY
#pragma push_macro("NAN")
#undef NAN
#pragma push_macro("DOMAIN")
#undef DOMAIN
#pragma push_macro("OVERFLOW")
#undef OVERFLOW
#pragma push_macro("UNDERFLOW")
#undef UNDERFLOW
#pragma push_macro("min")
#undef min
#pragma push_macro("max")
#undef max
