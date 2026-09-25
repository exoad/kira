// A stand-in for kira/cpp/kira/ffi.hxx, which W2.6 writes: only the drift-check
// macro the generated car.kira.hxx uses. goldens.sh puts kira/cpp ahead of this
// directory on the include path, so the real ffi.hxx wins once it exists, and
// this file can then be deleted.
#pragma once

#include <type_traits>

#ifndef KIRA_EXTERN_CHECK
#define KIRA_EXTERN_CHECK(expr, R, what) \
    static_assert(std::is_convertible_v<decltype(expr), R>, "Kira's " what " no longer matches its C++ header")
#endif
