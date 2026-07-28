/*
 * Kira C runtime prelude (C-as-IR, ISO C17).
 * Types and helpers follow Jack's C style guide (shared-type naming).
 *
 * Baseline collections (Arr / Map) are intentionally thin: enough for the
 * in-repo examples and smoke tests. Generics are erased at the C boundary.
 */

#ifndef KIRA_RUNTIME_H
#define KIRA_RUNTIME_H

#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>

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

/* -------------------------------------------------------------------------- */
/* Arr -- erased dynamic/fixed view over Int32 elements (baseline)            */
/* -------------------------------------------------------------------------- */

typedef struct Arr
{
    Int32* data;
    Int32 length;
} Arr;

simple Arr Arr_i32(Int32* data, Int32 length)
{
    Arr a;
    a.data = data;
    a.length = length;
    return a;
}

simple Arr Arr_empty(Void)
{
    Arr a;
    a.data = null;
    a.length = 0;
    return a;
}

simple Int32 Arr_get_i32(Arr a, Int32 index)
{
    if(a.data == null || index < 0 || index >= a.length)
    {
        abort();
    }
    return a.data[index];
}

simple Void Arr_set_i32(Arr a, Int32 index, Int32 value)
{
    if(a.data == null || index < 0 || index >= a.length)
    {
        abort();
    }
    a.data[index] = value;
}

simple Int32 Arr_size(Arr* a)
{
    return a->length;
}

simple Bool Arr_isEmpty(Arr* a)
{
    return a->length == 0;
}

/* -------------------------------------------------------------------------- */
/* Map -- baseline empty/count-only map (enough for isEmpty / size examples)  */
/* Full put/get hashing lands later; length tracks entry count.               */
/* -------------------------------------------------------------------------- */

typedef struct Map
{
    Int32 length;
} Map;

simple Map Map_new(Void)
{
    Map m;
    m.length = 0;
    return m;
}

simple Bool Map_isEmpty(Map* m)
{
    return m->length == 0;
}

simple Int32 Map_size(Map* m)
{
    return m->length;
}

simple Void Map_clear(Map* m)
{
    m->length = 0;
}

/* List is an Arr alias at the C boundary for the baseline backend. */
typedef Arr List;

simple List List_empty(Void)
{
    return Arr_empty();
}

simple Int32 List_size(List* list)
{
    return Arr_size(list);
}

simple Bool List_isEmpty(List* list)
{
    return Arr_isEmpty(list);
}

#endif /* KIRA_RUNTIME_H */

typedef struct Box_Int32 Box_Int32;

struct Box_Int32
{
    Int32 value;
};

typedef enum Phase
{
    PHASE_READY,
    PHASE_DONE
} Phase;

Int32 id_Int32(Int32 value);
Int32 main(Void);

Int32 id_Int32(Int32 value)
{
    return value;
}

/* module app:box */
/* module app:main */
/* use app:box */
/* use app:status */
Int32 main(Void)
{
    Phase phase = PHASE_READY;
    Box_Int32 wrapped = (Box_Int32) { 42 };
    Int32 value = id_Int32(wrapped.value);
    if((phase == PHASE_READY))
    {
        print("%d\n", value);
    }
    return 0;
}
/* module app:status */
