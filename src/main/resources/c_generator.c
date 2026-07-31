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
/* Kira ARC hooks (class heap only -- never foreign/opaque pointers)          */
/* Strong RC v1 foundation; codegen wiring lands incrementally.               */
/* -------------------------------------------------------------------------- */

typedef struct KiraRcHeader
{
    Int32 strong;
} KiraRcHeader;

simple Void* kira_rc_alloc(Int32 nbytes)
{
    /* header + payload; payload begins immediately after header */
    KiraRcHeader* h = (KiraRcHeader*)malloc((size_t)nbytes + sizeof(KiraRcHeader));
    if (h == null)
    {
        abort();
    }
    h->strong = 1;
    return (Void*)(h + 1);
}

simple Void kira_rc_retain(Void* obj)
{
    if (obj == null)
    {
        return;
    }
    KiraRcHeader* h = ((KiraRcHeader*)obj) - 1;
    h->strong += 1;
}

simple Void kira_rc_release(Void* obj)
{
    if (obj == null)
    {
        return;
    }
    KiraRcHeader* h = ((KiraRcHeader*)obj) - 1;
    h->strong -= 1;
    if (h->strong <= 0)
    {
        free(h);
    }
}

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
/* Map -- open-addressing hash table (linear probing)                         */
/* Keys/values are Any pointers; occupied tracks tombstones.                  */
/* -------------------------------------------------------------------------- */

typedef struct Map
{
    Any*    keys;
    Any*    values;
    Bool*   occupied;  /* true = live entry, false = empty/tombstone */
    Int32   length;
    Int32   capacity;
} Map;

/* djb2 hash on a string key; fallback for non-string Any keys uses pointer value. */
/* Map function prototypes (forward references for mutual recursion) */
simple Void Map_put(Map* m, Any key, Any value);
simple Void Map_resize(Map* m, Int32 newCap);

simple UInt64 Map_hash(Any key)
{
    if (key == null) return 0;
    Str s = (Str)key;
    UInt64 h = 5381;
    while (*s)
    {
        h = h * 33 + (UInt64)(*s);
        s++;
    }
    return h;
}

simple Void Map_resize(Map* m, Int32 newCap)
{
    Any*    oldKeys    = m->keys;
    Any*    oldValues  = m->values;
    Bool*   oldOccupied = m->occupied;
    Int32   oldCap     = m->capacity;

    m->keys     = (Any*)calloc((size_t)newCap, sizeof(Any));
    m->values   = (Any*)calloc((size_t)newCap, sizeof(Any));
    m->occupied = (Bool*)calloc((size_t)newCap, sizeof(Bool));
    if (m->keys == null || m->values == null || m->occupied == null) abort();
    m->capacity = newCap;
    m->length   = 0;

    Int32 i;
    for (i = 0; i < oldCap; i++)
    {
        if (oldOccupied[i])
        {
            Map_put(m, oldKeys[i], oldValues[i]);
        }
    }
    free(oldKeys);
    free(oldValues);
    free(oldOccupied);
}

simple Map Map_new(Void)
{
    Map m;
    m.capacity = 8;
    m.length   = 0;
    m.keys     = (Any*)calloc((size_t)m.capacity, sizeof(Any));
    m.values   = (Any*)calloc((size_t)m.capacity, sizeof(Any));
    m.occupied = (Bool*)calloc((size_t)m.capacity, sizeof(Bool));
    if (m.keys == null || m.values == null || m.occupied == null) abort();
    return m;
}

simple Void Map_put(Map* m, Any key, Any value)
{
    if (m->keys == null)
    {
        m->capacity = 8;
        m->length   = 0;
        m->keys     = (Any*)calloc((size_t)m->capacity, sizeof(Any));
        m->values   = (Any*)calloc((size_t)m->capacity, sizeof(Any));
        m->occupied = (Bool*)calloc((size_t)m->capacity, sizeof(Bool));
        if (m->keys == null || m->values == null || m->occupied == null) abort();
    }

    /* Grow if load factor > 0.75 */
    if (m->length >= m->capacity * 3 / 4)
    {
        Map_resize(m, m->capacity * 2);
    }

    UInt64 h = Map_hash(key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;

    for (;;)
    {
        if (!m->occupied[idx])
        {
            /* Empty slot -- insert here */
            m->keys[idx]     = key;
            m->values[idx]   = value;
            m->occupied[idx] = true;
            m->length++;
            return;
        }
        if (m->keys[idx] == key)
        {
            /* Same key -- update value */
            m->values[idx] = value;
            return;
        }
        idx = (idx + 1) % m->capacity;
        if (idx == start) break; /* should not happen if we grew */
    }
}

simple Bool Map_containsKey(Map* m, Any key)
{
    if (m->keys == null || m->length == 0) return false;
    UInt64 h = Map_hash(key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;
    for (;;)
    {
        if (!m->occupied[idx]) return false;
        if (m->keys[idx] == key) return true;
        idx = (idx + 1) % m->capacity;
        if (idx == start) return false;
    }
}

simple Any Map_get(Map* m, Any key)
{
    if (m->keys == null || m->length == 0) return null;
    UInt64 h = Map_hash(key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;
    for (;;)
    {
        if (!m->occupied[idx]) return null;
        if (m->keys[idx] == key) return m->values[idx];
        idx = (idx + 1) % m->capacity;
        if (idx == start) return null;
    }
}

simple Any Map_remove(Map* m, Any key)
{
    if (m->keys == null || m->length == 0) return null;
    UInt64 h = Map_hash(key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;
    for (;;)
    {
        if (!m->occupied[idx]) return null;
        if (m->keys[idx] == key)
        {
            Any val = m->values[idx];
            m->occupied[idx] = false; /* tombstone */
            m->length--;
            return val;
        }
        idx = (idx + 1) % m->capacity;
        if (idx == start) return null;
    }
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
    if (m->occupied != null)
    {
        Int32 i;
        for (i = 0; i < m->capacity; i++)
        {
            m->occupied[i] = false;
        }
    }
    m->length = 0;
}

/* -------------------------------------------------------------------------- */
/* List -- owning dynamic list over Int32 elements                            */
/* Grows by doubling capacity on overflow; starts at 4.                       */
/* -------------------------------------------------------------------------- */

typedef struct List
{
    Int32* data;
    Int32  length;
    Int32  capacity;
} List;

simple Void List_grow(List* l)
{
    Int32 newCap = l->capacity == 0 ? 4 : l->capacity * 2;
    Int32* newData = (Int32*)realloc(l->data, (size_t)newCap * sizeof(Int32));
    if (newData == null) abort();
    l->data     = newData;
    l->capacity = newCap;
}

simple List List_new(Void)
{
    List l;
    l.data     = null;
    l.length   = 0;
    l.capacity = 0;
    return l;
}

simple Void List_add(List* l, Int32 value)
{
    if (l->length >= l->capacity)
    {
        List_grow(l);
    }
    l->data[l->length] = value;
    l->length++;
}

simple Int32 List_get(List* l, Int32 index)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    return l->data[index];
}

simple Void List_set(List* l, Int32 index, Int32 value)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    l->data[index] = value;
}

simple Int32 List_removeAt(List* l, Int32 index)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    Int32 val = l->data[index];
    /* Shift elements left */
    Int32 i;
    for (i = index; i < l->length - 1; i++)
    {
        l->data[i] = l->data[i + 1];
    }
    l->length--;
    return val;
}

simple Void List_clear(List* l)
{
    if (l->data != null)
    {
        free(l->data);
        l->data = null;
    }
    l->length   = 0;
    l->capacity = 0;
}

simple Int32 List_size(List* l)
{
    return l->length;
}

simple Bool List_isEmpty(List* l)
{
    return l->length == 0;
}

simple Arr List_toArr(List* l)
{
    if (l->data == null || l->length == 0) return Arr_empty();
    /* Copy into a fresh array so List can still mutate */
    Int32* copy = (Int32*)malloc((size_t)l->length * sizeof(Int32));
    if (copy == null) abort();
    memcpy(copy, l->data, (size_t)l->length * sizeof(Int32));
    return Arr_i32(copy, l->length);
}

#endif /* KIRA_RUNTIME_H */
