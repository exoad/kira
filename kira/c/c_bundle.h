/*
 * Kira C-as-IR -- compiler bundle substrate (cupup-inspired).
 *
 * Layer 0 of every translation unit. Fixed-width C types and a small set of
 * named hooks (true/false/static/inline/const/null) so later layers and a
 * future mangler only rewrite *names*, not structure.
 *
 * Do not put Arr/Map/user helpers here -- that is c_generator.c (layer 1).
 * ISO C17. Keep this header self-contained and order-stable.
 */

#ifndef KIRA_COMPILER_BUNDLE_H
#define KIRA_COMPILER_BUNDLE_H

#include <stdint.h>
#include <stddef.h>

/* ---- boolean / linkage / qualifiers (mangle targets) -------------------- */
#define KIRA_TRUE 1
#define KIRA_FALSE 0
#define KIRA_PERSISTENT static
#define KIRA_INLINE static inline
#define KIRA_IMMUTABLE const
#define KIRA_NULL ((void*)0)

#ifdef __GNUC__
#define KIRA_UNUSED __attribute__((unused))
#else
#define KIRA_UNUSED
#endif

/* ---- fixed-width machine types (mangle targets) ------------------------- */
typedef int32_t  kira_i32;
typedef int64_t  kira_i64;
typedef int16_t  kira_i16;
typedef int8_t   kira_i8;
typedef uint32_t kira_u32;
typedef uint64_t kira_u64;
typedef uint16_t kira_u16;
typedef uint8_t  kira_u8;
typedef float    kira_f32;
typedef double   kira_f64;
typedef void     kira_unit;
typedef uint8_t  kira_bool;   /* 0 / 1 -- not C _Bool, so mangling stays simple */
typedef char     kira_utf8;

#endif /* KIRA_COMPILER_BUNDLE_H */
