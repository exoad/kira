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
#define eprint(...)  fprintf(stderr, __VA_ARGS__)

/* -------------------------------------------------------------------------- */
/* Kira ARC hooks (class heap only -- never foreign/opaque pointers)          */
/* Strong RC v1 foundation; codegen wiring lands incrementally.               */
/* -------------------------------------------------------------------------- */

/*
 * Finalizer for one class: releases whatever that class's fields own. Codegen
 * emits one per class with class-typed fields and hands it to kira_rc_alloc_with,
 * so releasing an owner transitively releases what it holds.
 */
typedef Void (*KiraFinalizer)(Void*);

typedef struct KiraRcHeader
{
    Int32         strong;
    KiraFinalizer finalize;   /* null when the class owns no references */
} KiraRcHeader;

simple Void* kira_rc_alloc_with(Int32 nbytes, KiraFinalizer finalize)
{
    /* header + payload; payload begins immediately after header */
    KiraRcHeader* h = (KiraRcHeader*)malloc((size_t)nbytes + sizeof(KiraRcHeader));
    if (h == null)
    {
        abort();
    }
    h->strong   = 1;
    h->finalize = finalize;
    return (Void*)(h + 1);
}

simple Void* kira_rc_alloc(Int32 nbytes)
{
    return kira_rc_alloc_with(nbytes, null);
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
        if (h->finalize != null)
        {
            h->finalize(obj);
        }
        free(h);
    }
}

/* Retain and hand back, so a borrowed argument can be passed where a +1 is expected. */
simple Void* kira_rc_retained(Void* obj)
{
    kira_rc_retain(obj);
    return obj;
}

/*
 * Store a *borrowed* reference into an owning slot: retain the incoming value
 * before releasing the outgoing one, so self-assignment (`a = a`) and aliased
 * stores cannot free the object mid-swap.
 */
simple Void kira_rc_store(Void** slot, Void* value)
{
    if (*slot == value)
    {
        return;
    }
    kira_rc_retain(value);
    kira_rc_release(*slot);
    *slot = value;
}

/*
 * Store an *owned* reference (a fresh allocation, or one handed over by a
 * callee) into an owning slot. The +1 transfers, so no retain -- only the
 * previous occupant is released.
 */
simple Void kira_rc_store_owned(Void** slot, Void* value)
{
    if (*slot == value)
    {
        return;
    }
    kira_rc_release(*slot);
    *slot = value;
}

/* -------------------------------------------------------------------------- */
/* KiraSlot -- uniform erased element                                          */
/*                                                                            */
/* Containers are generic in Kira but erased in C. One 64-bit slot holds any   */
/* element the backend can currently represent: an integer, a Bool, or a       */
/* pointer (Str, class instance) cast through intptr_t. Codegen casts back to  */
/* the declared element type at each use site.                                 */
/*                                                                            */
/* Limit, on purpose: Float32/Float64 elements are NOT representable this way  */
/* and are rejected in the frontend rather than silently reinterpreted.        */
/* -------------------------------------------------------------------------- */

typedef Int64 KiraSlot;

#define KIRA_SLOT(x)      ((KiraSlot)(x))
#define KIRA_SLOT_PTR(p)  ((KiraSlot)(intptr_t)(p))
#define KIRA_UNSLOT(T, s) ((T)(s))
#define KIRA_UNSLOT_PTR(T, s) ((T)(intptr_t)(s))

/* -------------------------------------------------------------------------- */
/* KiraVec -- owning dynamic array of slots                                    */
/* Backing store for Set / Stack / Queue / Deque and the Map view helpers.     */
/* -------------------------------------------------------------------------- */

typedef struct KiraVec
{
    KiraSlot* data;
    Int32     length;
    Int32     head;      /* Deque front offset; 0 for every other container */
    Int32     capacity;
} KiraVec;

simple KiraVec KiraVec_new(Void)
{
    KiraVec v;
    v.data     = null;
    v.length   = 0;
    v.head     = 0;
    v.capacity = 0;
    return v;
}

simple Void KiraVec_reserve(KiraVec* v, Int32 want)
{
    if (v->head + want <= v->capacity) return;
    Int32 newCap = v->capacity == 0 ? 4 : v->capacity * 2;
    while (newCap < v->head + want) newCap *= 2;
    KiraSlot* nd = (KiraSlot*)realloc(v->data, (size_t)newCap * sizeof(KiraSlot));
    if (nd == null) abort();
    v->data     = nd;
    v->capacity = newCap;
}

simple Void KiraVec_push(KiraVec* v, KiraSlot value)
{
    KiraVec_reserve(v, v->length + 1);
    v->data[v->head + v->length] = value;
    v->length++;
}

simple KiraSlot KiraVec_get(KiraVec* v, Int32 index)
{
    if (v->data == null || index < 0 || index >= v->length) abort();
    return v->data[v->head + index];
}

simple Void KiraVec_set(KiraVec* v, Int32 index, KiraSlot value)
{
    if (v->data == null || index < 0 || index >= v->length) abort();
    v->data[v->head + index] = value;
}

simple KiraSlot KiraVec_removeAt(KiraVec* v, Int32 index)
{
    if (v->data == null || index < 0 || index >= v->length) abort();
    KiraSlot val = v->data[v->head + index];
    Int32 i;
    for (i = index; i < v->length - 1; i++)
    {
        v->data[v->head + i] = v->data[v->head + i + 1];
    }
    v->length--;
    return val;
}

simple Int32 KiraVec_indexOf(KiraVec* v, KiraSlot value)
{
    Int32 i;
    for (i = 0; i < v->length; i++)
    {
        if (v->data[v->head + i] == value) return i;
    }
    return -1;
}

simple Int32 KiraVec_size(KiraVec* v)    { return v->length; }
simple Bool  KiraVec_isEmpty(KiraVec* v) { return v->length == 0; }

simple Void KiraVec_clear(KiraVec* v)
{
    if (v->data != null) free(v->data);
    v->data     = null;
    v->length   = 0;
    v->head     = 0;
    v->capacity = 0;
}

/* -------------------------------------------------------------------------- */
/* Maybe / Result                                                              */
/* -------------------------------------------------------------------------- */

typedef struct Maybe
{
    KiraSlot value;
    Bool     present;
} Maybe;

simple Maybe Maybe_some(KiraSlot v) { Maybe m; m.value = v; m.present = true;  return m; }
simple Maybe Maybe_none(Void)       { Maybe m; m.value = 0; m.present = false; return m; }

simple Bool     Maybe_isSome(Maybe* m) { return m->present; }
simple Bool     Maybe_isNone(Maybe* m) { return !m->present; }
simple KiraSlot Maybe_unwrap(Maybe* m)
{
    if (!m->present)
    {
        fprintf(stderr, "kira: unwrap() on a None Maybe\n");
        abort();
    }
    return m->value;
}
simple KiraSlot Maybe_unwrapOr(Maybe* m, KiraSlot fallback)
{
    return m->present ? m->value : fallback;
}

typedef struct Result
{
    KiraSlot value;
    KiraSlot error;
    Bool     ok;
} Result;

simple Result Result_ok(KiraSlot v)  { Result r; r.value = v; r.error = 0; r.ok = true;  return r; }
simple Result Result_err(KiraSlot e) { Result r; r.value = 0; r.error = e; r.ok = false; return r; }

simple Bool     Result_isOk(Result* r)  { return r->ok; }
simple Bool     Result_isErr(Result* r) { return !r->ok; }
simple KiraSlot Result_unwrap(Result* r)
{
    if (!r->ok)
    {
        fprintf(stderr, "kira: unwrap() on an Err Result\n");
        abort();
    }
    return r->value;
}
simple KiraSlot Result_unwrapErr(Result* r)
{
    if (r->ok)
    {
        fprintf(stderr, "kira: unwrapErr() on an Ok Result\n");
        abort();
    }
    return r->error;
}

/* -------------------------------------------------------------------------- */
/* Str -- immutable UTF-8-ish byte strings                                     */
/*                                                                            */
/* Every producer here returns freshly malloc'd storage. Kira has no Str       */
/* ownership model yet, so these are not freed -- the same documented limit as */
/* unowned ARC temporaries (docs/backend-c.md).                                */
/* -------------------------------------------------------------------------- */

simple Utf8* kira_str_alloc(Int32 nbytes)
{
    Utf8* p = (Utf8*)malloc((size_t)nbytes + 1);
    if (p == null) abort();
    p[nbytes] = '\0';
    return p;
}

simple Int32 Str_length(Str s)
{
    return s == null ? 0 : (Int32)strlen(s);
}

simple Bool Str_isEmpty(Str s)
{
    return s == null || s[0] == '\0';
}

simple Str Str_substring(Str s, Int32 start, Int32 end)
{
    Int32 n = Str_length(s);
    if (start < 0 || end > n || start > end) abort();
    Int32 len = end - start;
    Utf8* out = kira_str_alloc(len);
    memcpy(out, s + start, (size_t)len);
    return (Str)out;
}

simple Str Str_charAt(Str s, Int32 index)
{
    if (index < 0 || index >= Str_length(s)) abort();
    Utf8* out = kira_str_alloc(1);
    out[0] = s[index];
    return (Str)out;
}

simple Bool Str_contains(Str s, Str needle)
{
    if (s == null || needle == null) return false;
    return strstr(s, needle) != null;
}

simple Bool Str_startsWith(Str s, Str prefix)
{
    if (s == null || prefix == null) return false;
    size_t n = strlen(prefix);
    return strncmp(s, prefix, n) == 0;
}

simple Bool Str_endsWith(Str s, Str suffix)
{
    if (s == null || suffix == null) return false;
    size_t sn = strlen(s);
    size_t xn = strlen(suffix);
    if (xn > sn) return false;
    return memcmp(s + (sn - xn), suffix, xn) == 0;
}

simple Str Str_trim(Str s)
{
    if (s == null) return s;
    Int32 n = Str_length(s);
    Int32 a = 0;
    Int32 b = n;
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\n' || s[a] == '\r')) a++;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\n' || s[b - 1] == '\r')) b--;
    return Str_substring(s, a, b);
}

simple Str Str_toLower(Str s)
{
    Int32 n = Str_length(s);
    Utf8* out = kira_str_alloc(n);
    Int32 i;
    for (i = 0; i < n; i++)
    {
        Utf8 c = s[i];
        out[i] = (c >= 'A' && c <= 'Z') ? (Utf8)(c - 'A' + 'a') : c;
    }
    return (Str)out;
}

simple Str Str_toUpper(Str s)
{
    Int32 n = Str_length(s);
    Utf8* out = kira_str_alloc(n);
    Int32 i;
    for (i = 0; i < n; i++)
    {
        Utf8 c = s[i];
        out[i] = (c >= 'a' && c <= 'z') ? (Utf8)(c - 'a' + 'A') : c;
    }
    return (Str)out;
}

simple Bool Str_equals(Str a, Str b)
{
    if (a == b) return true;
    if (a == null || b == null) return false;
    return strcmp(a, b) == 0;
}

simple Int64 Str_hashCode(Str s)
{
    if (s == null) return 0;
    UInt64 h = 5381;
    while (*s)
    {
        h = h * 33 + (UInt64)(unsigned char)(*s);
        s++;
    }
    return (Int64)h;
}

/* -------------------------------------------------------------------------- */
/* assert -- Kira's two-argument form (C's assert takes one)                   */
/* -------------------------------------------------------------------------- */

simple Void kira_assert(Bool condition, Str message)
{
    if (!condition)
    {
        fprintf(stderr, "kira: assertion failed: %s\n", message == null ? "" : message);
        abort();
    }
}

/* -------------------------------------------------------------------------- */
/* Arr -- fixed-length view over slots                                        */
/*                                                                            */
/* Elements are KiraSlot, so one Arr serves Arr<Int32>, Arr<Str>, Arr<Bool>   */
/* and Arr of class references. The typed macros below keep the emitted C     */
/* tight for the common cases -- codegen picks one by element type.           */
/* -------------------------------------------------------------------------- */

typedef struct Arr
{
    KiraSlot* data;
    Int32     length;
} Arr;

simple Arr Arr_lit(KiraSlot* data, Int32 length)
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

simple KiraSlot Arr_get(Arr a, Int32 index)
{
    if (a.data == null || index < 0 || index >= a.length)
    {
        abort();
    }
    return a.data[index];
}

simple Void Arr_set(Arr a, Int32 index, KiraSlot value)
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

simple Bool Arr_contains(Arr* a, KiraSlot value)
{
    Int32 i;
    for (i = 0; i < a->length; i++)
    {
        if (a->data[i] == value) return true;
    }
    return false;
}

/* Fresh backing store; the copy is independent of the source. */
simple Arr Arr_clone(Arr* a)
{
    if (a->data == null || a->length == 0) return Arr_empty();
    KiraSlot* copy = (KiraSlot*)malloc((size_t)a->length * sizeof(KiraSlot));
    if (copy == null) abort();
    memcpy(copy, a->data, (size_t)a->length * sizeof(KiraSlot));
    return Arr_lit(copy, a->length);
}

/* Typed element accessors -- pure sugar over the slot form. */
#define Arr_get_i32(a, i)      ((Int32)Arr_get((a), (i)))
#define Arr_get_i64(a, i)      ((Int64)Arr_get((a), (i)))
#define Arr_get_bool(a, i)     ((Bool)Arr_get((a), (i)))
#define Arr_get_str(a, i)      ((Str)(intptr_t)Arr_get((a), (i)))
#define Arr_get_ref(T, a, i)   ((T)(intptr_t)Arr_get((a), (i)))
#define Arr_set_i32(a, i, v)   Arr_set((a), (i), KIRA_SLOT(v))
#define Arr_set_i64(a, i, v)   Arr_set((a), (i), KIRA_SLOT(v))
#define Arr_set_bool(a, i, v)  Arr_set((a), (i), KIRA_SLOT(v))
#define Arr_set_str(a, i, v)   Arr_set((a), (i), KIRA_SLOT_PTR(v))
#define Arr_set_ref(a, i, v)   Arr_set((a), (i), KIRA_SLOT_PTR(v))

/* -------------------------------------------------------------------------- */
/* List -- owning dynamic list of slots                                       */
/* Grows by doubling capacity on overflow; starts at 4.                       */
/* -------------------------------------------------------------------------- */

typedef struct List
{
    KiraSlot* data;
    Int32     length;
    Int32     capacity;
} List;

simple List List_new(Void)
{
    List l;
    l.data     = null;
    l.length   = 0;
    l.capacity = 0;
    return l;
}

/* Legacy spelling kept so older emit keeps building. */
simple List List_empty(Void) { return List_new(); }

simple Void List_grow(List* l)
{
    Int32 newCap = l->capacity == 0 ? 4 : l->capacity * 2;
    KiraSlot* newData = (KiraSlot*)realloc(l->data, (size_t)newCap * sizeof(KiraSlot));
    if (newData == null) abort();
    l->data     = newData;
    l->capacity = newCap;
}

simple Void List_add(List* l, KiraSlot value)
{
    if (l->length >= l->capacity)
    {
        List_grow(l);
    }
    l->data[l->length] = value;
    l->length++;
}

simple KiraSlot List_get(List* l, Int32 index)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    return l->data[index];
}

simple Void List_set(List* l, Int32 index, KiraSlot value)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    l->data[index] = value;
}

simple KiraSlot List_removeAt(List* l, Int32 index)
{
    if (l->data == null || index < 0 || index >= l->length) abort();
    KiraSlot val = l->data[index];
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

simple Bool List_contains(List* l, KiraSlot value)
{
    Int32 i;
    for (i = 0; i < l->length; i++)
    {
        if (l->data[i] == value) return true;
    }
    return false;
}

simple Void List_addAll(List* l, Arr values)
{
    Int32 i;
    for (i = 0; i < values.length; i++)
    {
        List_add(l, values.data[i]);
    }
}

simple Arr List_toArr(List* l)
{
    if (l->data == null || l->length == 0) return Arr_empty();
    /* Copy into a fresh array so List can still mutate */
    KiraSlot* copy = (KiraSlot*)malloc((size_t)l->length * sizeof(KiraSlot));
    if (copy == null) abort();
    memcpy(copy, l->data, (size_t)l->length * sizeof(KiraSlot));
    return Arr_lit(copy, l->length);
}

/* Typed element accessors -- pure sugar over the slot form. */
#define List_get_i32(l, i)     ((Int32)List_get((l), (i)))
#define List_get_i64(l, i)     ((Int64)List_get((l), (i)))
#define List_get_bool(l, i)    ((Bool)List_get((l), (i)))
#define List_get_str(l, i)     ((Str)(intptr_t)List_get((l), (i)))
#define List_get_ref(T, l, i)  ((T)(intptr_t)List_get((l), (i)))
#define List_add_i32(l, v)     List_add((l), KIRA_SLOT(v))
#define List_add_str(l, v)     List_add((l), KIRA_SLOT_PTR(v))
#define List_set_i32(l, i, v)  List_set((l), (i), KIRA_SLOT(v))
#define List_set_str(l, i, v)  List_set((l), (i), KIRA_SLOT_PTR(v))
#define List_removeAt_i32(l,i) ((Int32)List_removeAt((l), (i)))
#define List_removeAt_str(l,i) ((Str)(intptr_t)List_removeAt((l), (i)))

/* Split on a delimiter into List<Str>; each piece is freshly allocated. */
simple List Str_split(Str s, Str delimiter)
{
    List out = List_new();
    if (s == null || delimiter == null || delimiter[0] == '\0')
    {
        List_add(&out, KIRA_SLOT_PTR(s));
        return out;
    }
    size_t dn = strlen(delimiter);
    Str cursor = s;
    for (;;)
    {
        Str hit = strstr(cursor, delimiter);
        if (hit == null)
        {
            List_add(&out, KIRA_SLOT_PTR(Str_substring(cursor, 0, (Int32)strlen(cursor))));
            break;
        }
        List_add(&out, KIRA_SLOT_PTR(Str_substring(cursor, 0, (Int32)(hit - cursor))));
        cursor = hit + dn;
    }
    return out;
}

/* -------------------------------------------------------------------------- */
/* Map -- open-addressing hash table (linear probing)                         */
/*                                                                            */
/* Keys and values are KiraSlot, so one table serves Str keys (pointer cast    */
/* into the slot) and integer keys/values alike. `kind` records how to hash    */
/* and compare keys; codegen picks Map_new_s / Map_new_i at construction.      */
/* -------------------------------------------------------------------------- */

#define KIRA_MAP_KEY_INT 0
#define KIRA_MAP_KEY_STR 1

typedef struct Map
{
    KiraSlot* keys;
    KiraSlot* values;
    Bool*     occupied;  /* true = live entry, false = empty/tombstone */
    Int32     length;
    Int32     capacity;
    Int32     kind;      /* KIRA_MAP_KEY_* */
} Map;

/* Forward references (put/resize are mutually recursive). */
simple Void Map_put(Map* m, KiraSlot key, KiraSlot value);
simple Void Map_resize(Map* m, Int32 newCap);

simple UInt64 Map_hash(Map* m, KiraSlot key)
{
    if (m->kind == KIRA_MAP_KEY_STR)
    {
        Str s = (Str)(intptr_t)key;
        if (s == null) return 0;
        UInt64 h = 5381;
        while (*s)
        {
            h = h * 33 + (UInt64)(unsigned char)(*s);
            s++;
        }
        return h;
    }
    /* Integer keys: mix so sequential keys do not cluster under linear probing. */
    UInt64 h = (UInt64)key;
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33;
    return h;
}

simple Bool Map_keyEquals(Map* m, KiraSlot a, KiraSlot b)
{
    if (m->kind == KIRA_MAP_KEY_STR)
    {
        Str x = (Str)(intptr_t)a;
        Str y = (Str)(intptr_t)b;
        if (x == y) return true;
        if (x == null || y == null) return false;
        return strcmp(x, y) == 0;
    }
    return a == b;
}

simple Map Map_new_kind(Int32 kind)
{
    Map m;
    m.capacity = 8;
    m.length   = 0;
    m.kind     = kind;
    m.keys     = (KiraSlot*)calloc((size_t)m.capacity, sizeof(KiraSlot));
    m.values   = (KiraSlot*)calloc((size_t)m.capacity, sizeof(KiraSlot));
    m.occupied = (Bool*)calloc((size_t)m.capacity, sizeof(Bool));
    if (m.keys == null || m.values == null || m.occupied == null) abort();
    return m;
}

simple Map Map_new_s(Void) { return Map_new_kind(KIRA_MAP_KEY_STR); }
simple Map Map_new_i(Void) { return Map_new_kind(KIRA_MAP_KEY_INT); }
/* Legacy spelling: string-keyed maps were the only shape before slots. */
simple Map Map_new(Void)   { return Map_new_kind(KIRA_MAP_KEY_STR); }

simple Void Map_resize(Map* m, Int32 newCap)
{
    KiraSlot* oldKeys     = m->keys;
    KiraSlot* oldValues   = m->values;
    Bool*     oldOccupied = m->occupied;
    Int32     oldCap      = m->capacity;

    m->keys     = (KiraSlot*)calloc((size_t)newCap, sizeof(KiraSlot));
    m->values   = (KiraSlot*)calloc((size_t)newCap, sizeof(KiraSlot));
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

simple Void Map_put(Map* m, KiraSlot key, KiraSlot value)
{
    if (m->keys == null)
    {
        Map fresh = Map_new_kind(m->kind);
        *m = fresh;
    }

    /* Grow if load factor > 0.75 */
    if (m->length >= m->capacity * 3 / 4)
    {
        Map_resize(m, m->capacity * 2);
    }

    UInt64 h   = Map_hash(m, key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;

    for (;;)
    {
        if (!m->occupied[idx])
        {
            m->keys[idx]     = key;
            m->values[idx]   = value;
            m->occupied[idx] = true;
            m->length++;
            return;
        }
        if (Map_keyEquals(m, m->keys[idx], key))
        {
            m->values[idx] = value;
            return;
        }
        idx = (idx + 1) % m->capacity;
        if (idx == start) break; /* unreachable while we grow at 0.75 */
    }
}

/* Slot index of `key`, or -1. */
simple Int32 Map_indexOf(Map* m, KiraSlot key)
{
    if (m->keys == null || m->length == 0) return -1;
    UInt64 h   = Map_hash(m, key);
    Int32  idx = (Int32)(h % (UInt64)m->capacity);
    Int32  start = idx;
    for (;;)
    {
        if (!m->occupied[idx]) return -1;
        if (Map_keyEquals(m, m->keys[idx], key)) return idx;
        idx = (idx + 1) % m->capacity;
        if (idx == start) return -1;
    }
}

simple Bool Map_containsKey(Map* m, KiraSlot key)
{
    return Map_indexOf(m, key) >= 0;
}

simple Maybe Map_get(Map* m, KiraSlot key)
{
    Int32 i = Map_indexOf(m, key);
    return i < 0 ? Maybe_none() : Maybe_some(m->values[i]);
}

simple Maybe Map_remove(Map* m, KiraSlot key)
{
    Int32 i = Map_indexOf(m, key);
    if (i < 0) return Maybe_none();
    KiraSlot val = m->values[i];
    m->occupied[i] = false; /* tombstone */
    m->length--;
    return Maybe_some(val);
}

simple Bool Map_containsValue(Map* m, KiraSlot value)
{
    if (m->occupied == null) return false;
    Int32 i;
    for (i = 0; i < m->capacity; i++)
    {
        if (m->occupied[i] && m->values[i] == value) return true;
    }
    return false;
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

/* Live keys / values as owning Arr views, in slot order. */
simple Arr Map_collect(Map* m, Bool wantKeys, Bool wantBoth)
{
    if (m->occupied == null || m->length == 0) return Arr_empty();
    Int32 per = wantBoth ? 2 : 1;
    KiraSlot* out = (KiraSlot*)malloc((size_t)(m->length * per) * sizeof(KiraSlot));
    if (out == null) abort();
    Int32 i;
    Int32 n = 0;
    for (i = 0; i < m->capacity; i++)
    {
        if (!m->occupied[i]) continue;
        if (wantBoth)
        {
            out[n++] = m->keys[i];
            out[n++] = m->values[i];
        }
        else
        {
            out[n++] = wantKeys ? m->keys[i] : m->values[i];
        }
    }
    return Arr_lit(out, n);
}

simple Arr Map_keys(Map* m)      { return Map_collect(m, true,  false); }
simple Arr Map_valuesArr(Map* m) { return Map_collect(m, false, false); }
/* Entries flattened as key, value, key, value, ... (Tuple2 has no C shape yet). */
simple Arr Map_entries(Map* m)   { return Map_collect(m, true,  true); }

/* -------------------------------------------------------------------------- */
/* Scope-end disposal                                                          */
/*                                                                            */
/* `clear()` is the user-facing "empty me but stay usable" operation. `dispose`*/
/* releases the backing storage and is emitted by codegen when a container     */
/* local goes out of scope.                                                    */
/* -------------------------------------------------------------------------- */

simple Void List_dispose(List* l)
{
    if (l->data != null) free(l->data);
    l->data     = null;
    l->length   = 0;
    l->capacity = 0;
}

simple Void Map_dispose(Map* m)
{
    if (m->keys != null)     free(m->keys);
    if (m->values != null)   free(m->values);
    if (m->occupied != null) free(m->occupied);
    m->keys     = null;
    m->values   = null;
    m->occupied = null;
    m->length   = 0;
    m->capacity = 0;
}

/* -------------------------------------------------------------------------- */
/* Set -- unique slots, linear membership over KiraVec                         */
/* Baseline: O(n) contains. Swap for a hash probe when profiles justify it.    */
/* -------------------------------------------------------------------------- */

typedef struct Set { KiraVec items; } Set;

simple Set  Set_new(Void)        { Set s; s.items = KiraVec_new(); return s; }
simple Int32 Set_size(Set* s)    { return KiraVec_size(&s->items); }
simple Bool  Set_isEmpty(Set* s) { return KiraVec_isEmpty(&s->items); }
simple Void  Set_clear(Set* s)   { KiraVec_clear(&s->items); }

simple Bool Set_contains(Set* s, KiraSlot value)
{
    return KiraVec_indexOf(&s->items, value) >= 0;
}

/* true when the value was newly inserted. */
simple Bool Set_add(Set* s, KiraSlot value)
{
    if (Set_contains(s, value)) return false;
    KiraVec_push(&s->items, value);
    return true;
}

/* true when the value was present and removed. */
simple Bool Set_remove(Set* s, KiraSlot value)
{
    Int32 i = KiraVec_indexOf(&s->items, value);
    if (i < 0) return false;
    KiraVec_removeAt(&s->items, i);
    return true;
}

simple KiraVec Set_toArr(Set* s)
{
    KiraVec out = KiraVec_new();
    Int32 i;
    for (i = 0; i < s->items.length; i++) KiraVec_push(&out, KiraVec_get(&s->items, i));
    return out;
}

/* -------------------------------------------------------------------------- */
/* Stack -- LIFO over KiraVec                                                  */
/* -------------------------------------------------------------------------- */

typedef struct Stack { KiraVec items; } Stack;

simple Stack Stack_new(Void)        { Stack s; s.items = KiraVec_new(); return s; }
simple Int32 Stack_size(Stack* s)   { return KiraVec_size(&s->items); }
simple Bool  Stack_isEmpty(Stack* s){ return KiraVec_isEmpty(&s->items); }
simple Void  Stack_clear(Stack* s)  { KiraVec_clear(&s->items); }
simple Void  Stack_push(Stack* s, KiraSlot v) { KiraVec_push(&s->items, v); }

simple Maybe Stack_pop(Stack* s)
{
    if (s->items.length == 0) return Maybe_none();
    return Maybe_some(KiraVec_removeAt(&s->items, s->items.length - 1));
}

simple Maybe Stack_peek(Stack* s)
{
    if (s->items.length == 0) return Maybe_none();
    return Maybe_some(KiraVec_get(&s->items, s->items.length - 1));
}

/* -------------------------------------------------------------------------- */
/* Queue -- FIFO over KiraVec (head offset keeps dequeue O(1) amortized)       */
/* -------------------------------------------------------------------------- */

typedef struct Queue { KiraVec items; } Queue;

simple Queue Queue_new(Void)         { Queue q; q.items = KiraVec_new(); return q; }
simple Int32 Queue_size(Queue* q)    { return KiraVec_size(&q->items); }
simple Bool  Queue_isEmpty(Queue* q) { return KiraVec_isEmpty(&q->items); }
simple Void  Queue_clear(Queue* q)   { KiraVec_clear(&q->items); }
simple Void  Queue_enqueue(Queue* q, KiraSlot v) { KiraVec_push(&q->items, v); }

simple Maybe Queue_dequeue(Queue* q)
{
    if (q->items.length == 0) return Maybe_none();
    KiraSlot v = q->items.data[q->items.head];
    q->items.head++;
    q->items.length--;
    if (q->items.length == 0) q->items.head = 0;
    return Maybe_some(v);
}

simple Maybe Queue_peek(Queue* q)
{
    if (q->items.length == 0) return Maybe_none();
    return Maybe_some(q->items.data[q->items.head]);
}

/* -------------------------------------------------------------------------- */
/* Deque -- double-ended over KiraVec                                          */
/* -------------------------------------------------------------------------- */

typedef struct Deque { KiraVec items; } Deque;

simple Deque Deque_new(Void)         { Deque d; d.items = KiraVec_new(); return d; }
simple Int32 Deque_size(Deque* d)    { return KiraVec_size(&d->items); }
simple Bool  Deque_isEmpty(Deque* d) { return KiraVec_isEmpty(&d->items); }
simple Void  Deque_clear(Deque* d)   { KiraVec_clear(&d->items); }
simple Void  Deque_pushBack(Deque* d, KiraSlot v) { KiraVec_push(&d->items, v); }

simple Void Deque_pushFront(Deque* d, KiraSlot v)
{
    if (d->items.head > 0)
    {
        d->items.head--;
        d->items.data[d->items.head] = v;
        d->items.length++;
        return;
    }
    /* No room in front: shift the window right, then fill slot 0. */
    KiraVec_reserve(&d->items, d->items.length + 1);
    Int32 i;
    for (i = d->items.length; i > 0; i--)
    {
        d->items.data[i] = d->items.data[i - 1];
    }
    d->items.data[0] = v;
    d->items.length++;
}

simple Maybe Deque_popFront(Deque* d)
{
    if (d->items.length == 0) return Maybe_none();
    KiraSlot v = d->items.data[d->items.head];
    d->items.head++;
    d->items.length--;
    if (d->items.length == 0) d->items.head = 0;
    return Maybe_some(v);
}

simple Maybe Deque_popBack(Deque* d)
{
    if (d->items.length == 0) return Maybe_none();
    return Maybe_some(KiraVec_removeAt(&d->items, d->items.length - 1));
}

simple Void Set_dispose(Set* s)     { KiraVec_clear(&s->items); }
simple Void Stack_dispose(Stack* s) { KiraVec_clear(&s->items); }
simple Void Queue_dispose(Queue* q) { KiraVec_clear(&q->items); }
simple Void Deque_dispose(Deque* d) { KiraVec_clear(&d->items); }

#endif /* KIRA_RUNTIME_H */
