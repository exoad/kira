#ifndef KIRA_SHARED_H
#define KIRA_SHARED_H

#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>

typedef int32_t Int32;
typedef long Long;
typedef int64_t Int64;
typedef int I32;
typedef uint32_t UInt32;
typedef uint64_t UInt64;
typedef char Int8;
typedef uint8_t UInt8;
typedef uintptr_t UPointer;
typedef size_t Size;
typedef bool Bool;
typedef float Float32;
typedef double Float64;
typedef int16_t Int16;
typedef uint16_t UInt16;
typedef void Void;
typedef const Int8* String;
typedef FILE CFile;
typedef Void* Any;

typedef Int32 Word;
typedef Int8 Byte;

#define null NULL
#define _PRINT(format, ...) printf(format "\n", ##__VA_ARGS__)

#include <string.h>
#include <stdlib.h>
static inline Int8* strdup(String string)
{
    Int8* dup = malloc(strlen(string) + 1);
    if(dup != null)
    {
        dup = strcpy(dup, string);
    }
    return dup;
}
#endif
