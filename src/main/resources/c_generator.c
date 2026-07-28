/*
 * Kira C-as-IR -- language facade + thin runtime (layer 1).
 *
 * Assumes layer 0 (c_bundle.h / KIRA_COMPILER_BUNDLE_*) is already in the TU.
 * Maps Kira/Jack-facing names onto the bundle substrate so user lowering and
 * demos stay readable while a future mangler can rename the kira_* / KIRA_*
 * hooks without rewriting this logic.
 *
 * Baseline collections (Arr / Map) are intentionally thin. ISO C17.
 */

#ifndef KIRA_RUNTIME_H
#define KIRA_RUNTIME_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

/* ---- Kira surface types (aliases over bundle substrate) ----------------- */
typedef kira_i32   Int32;
typedef kira_i64   Int64;
typedef kira_i16   Int16;
typedef kira_i8    Int8;
typedef kira_u32   UInt32;
typedef kira_u64   UInt64;
typedef kira_u16   UInt16;
typedef kira_u8    UInt8;
typedef kira_f32   Float32;
typedef kira_f64   Float64;
typedef kira_unit  Void;
typedef bool       Bool;       /* stdbool for existing emit; values 0/1 */
typedef kira_utf8  Utf8;
typedef Void*      Any;

#define CharSeq KIRA_IMMUTABLE Utf8*
typedef CharSeq Str;

#define null   KIRA_NULL
#define simple KIRA_INLINE

#define print(...)   fprintf(stdout, __VA_ARGS__)
#define println(...) do { print(__VA_ARGS__); print("\n"); } while (0)

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
    if (a.data == null || index < 0 || index >= a.length)
    {
        abort();
    }
    return a.data[index];
}

simple Void Arr_set_i32(Arr a, Int32 index, Int32 value)
{
    if (a.data == null || index < 0 || index >= a.length)
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
