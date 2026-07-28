/*
 * Kira C runtime prelude.
 * Types and helpers follow Jack's C style guide (shared-type naming).
 */

#ifndef KIRA_RUNTIME_H
#define KIRA_RUNTIME_H

#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>

typedef int32_t Int32;
typedef int64_t Int64;
typedef int16_t Int16;
typedef int8_t Int8;
typedef uint32_t UInt32;
typedef uint64_t UInt64;
typedef uint16_t UInt16;
typedef uint8_t UInt8;
typedef float Float32;
typedef double Float64;
typedef void Void;
typedef bool Bool;
typedef char Utf8;
typedef Void* Any;

#define CharSeq const Utf8*
typedef CharSeq Str;

#define null NULL
#define simple static inline

#define print(...) fprintf(stdout, __VA_ARGS__)
#define println(...) do { print(__VA_ARGS__); print("\n"); } while(0)

#endif /* KIRA_RUNTIME_H */
