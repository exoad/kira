/* cshape.h - C functions Kira calls through @_extern. Compiled as C
 * (cshape.c); the C++ build sees them through the extern "C" guard, as any C
 * library does. Fixed-width types only (design 7.2): on arm-none-eabi
 * int32_t is long, and Kira binds by the typedef. */
#ifndef CSHAPE_H
#define CSHAPE_H

#include <stddef.h>
#include <stdint.h>

/* A C constant is a macro; Kira reads it by this name, with no scope in front. */
#define CSHAPE_LIMIT 42

#ifdef __cplusplus
extern "C" {
#endif

int32_t cshape_hypot_sq(int32_t a, int32_t b);
size_t cshape_len(const char* s);
void cshape_count(int32_t* out, int32_t n);

#ifdef __cplusplus
}
#endif

#endif
