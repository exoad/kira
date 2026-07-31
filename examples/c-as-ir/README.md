# C-as-IR: what Kira actually emits

Kira lowers to **ISO C17** source (`out.kira.c`), which your `cc` then builds.
Not Neko bytecode, not a JIT.

Every example in the ladder checks in its own lowering, so this page is a
**tour of real committed output** rather than a set of parallel demo projects:

| File | Contents |
|------|----------|
| `examples/0N-*/src/**/*.kira` | Input |
| `examples/0N-*/generated.user.c` | The user lowering (post-prelude) |
| `examples/0N-*/expected.txt` | Exact stdout of the built binary |
| `examples/prelude.reference.c` | The runtime prelude, byte-identical for every example |

`out.kira.c` = `prelude.reference.c` + that example's `generated.user.c`.

Refresh all of it after a backend change, and verify nothing drifted:

```bash
./gradlew installDist
./examples/regenerate.sh          # rewrite snapshots + run every example
./examples/regenerate.sh --check  # CI mode: fail if a snapshot is stale
```

Background: [docs/backend-c.md](../../docs/backend-c.md).

---

## Pipeline

```text
*.kira  --kira-->  out.kira.c  --cc -std=c17-->  native app
              │
              ├─ 0. compiler bundle (c_bundle.h) -- substrate / mangle hooks
              ├─ 1. language facade (c_generator.c) -- Int32, print, Arr/Map
              └─ 2. user lowering (generated.user.c, per example)
```

Layers 0+1 are `prelude.reference.c`. Bundle layout is cupup-inspired so
further generation (and default name mangling later) retargets names, not
structure.

---

## 1. Hello — [`01-hello`](../01-hello/generated.user.c)

```kira
fx main: () Void {
    trace("hello, kira")
}
```

```c
Int32 main(Void)
{
    print("%s\n", "hello, kira");
    return 0;
}
```

| Lowering | Rule |
|----------|------|
| `fx main: () Void` | `Int32 main(Void)` + `return 0` |
| `trace("...")` | `print("%s\n", "...")` (prelude macro → `fprintf`) |

---

## 2. Modules — [`02-functions`](../02-functions/generated.user.c)

Modules are flattened into one translation unit; the `/* module ... */` and
`/* use ... */` comments mark where each file's declarations landed. Prototypes
are hoisted so definition order does not matter.

---

## 3. Control flow — [`03-control-flow`](../03-control-flow/generated.user.c)

`if` / `while` / `for` ranges map onto their C equivalents directly; a `for`
over a range becomes a counted `for` loop.

---

## 4. Classes — structs, methods, ARC — [`04-classes`](../04-classes/generated.user.c)

```kira
pub class Pet {
    require pub name: Str
    require pub sound: Str

    pub fx speak: () Str {
        return sound
    }
}

friend: Pet = Pet { "Mochi", "meow" }
```

```c
struct Pet
{
    Str name;
    Str sound;
};

Str Pet_speak(Pet* this)
{
    return this->sound;
}

simple Pet* Pet_new(Str name, Str sound)
{
    Pet* self = (Pet*)kira_rc_alloc(sizeof(Pet));
    self->name = name;
    self->sound = sound;
    return self;
}

Int32 main(Void)
{
    Rectangle* rect = Rectangle_new(Point_new(0, 1), Point_new(1, 0));
    Pet* friend = Pet_new("Mochi", "meow");
    ...
    kira_rc_release(rect);
    kira_rc_release(friend);
    return 0;
}
```

| Lowering | Rule |
|----------|------|
| `class` | `typedef struct` + field layout |
| `require` fields | Constructor `Type_new(...)` over `kira_rc_alloc` |
| method | Free function `Type_method(Type* this, ...)` |
| `friend.speak()` | `Pet_speak(friend)` |
| scope end | `kira_rc_release(...)` per owned local |

Objects are **heap-allocated and refcounted**, not stack structs.

> **Known gap, visible in this snapshot:** the two `Point_new(...)` temporaries
> passed into `Rectangle_new` never get a matching `kira_rc_release` — only
> named locals are tracked. See [docs/backend-c.md](../../docs/backend-c.md).

---

## 5. Generics + enums — [`05-enums-generics`](../05-enums-generics/generated.user.c)

```kira
pub class Box<T> {
    require pub value: T
}

fx id<T>: (value: T) T { return value }
```

```c
struct Box_Int32
{
    Int32 value;
};

typedef enum BuildStatus
{
    BUILD_STATUS_READY,
    BUILD_STATUS_RUNNING,
    BUILD_STATUS_DONE
} BuildStatus;

Int32 id_Int32(Int32 value)
{
    return value;
}
```

| Lowering | Rule |
|----------|------|
| `Box<Int32>` | Distinct type `Box_Int32` (monomorphized) |
| `id<Int32>` | Function `id_Int32` |
| `enum BuildStatus` | C enum, members `BUILD_STATUS_*` |

Stdlib `Arr`/`Map` stay **erased** at the C boundary — only *user* generics get
per-instantiation names today.

---

## 6. Collections — prelude helpers — [`06-collections`](../06-collections/generated.user.c)

```c
Int32 main(Void)
{
    Arr numbers = Arr_i32((Int32[]){ 10, 20, 30 }, 3);
    Int32 head = first(numbers);
    Map entries = Map_new();
    ...
}
```

| Lowering | Rule |
|----------|------|
| `[10, 20, 30]` | Compound literal + `Arr_i32(ptr, len)` |
| `numbers[0]` | `Arr_get_i32(numbers, 0)` |
| `Map<...> { }` | `Map_new()` (open-addressing hash map) |
| `.isEmpty()` | `Map_isEmpty(&entries)` |

Helpers live in `prelude.reference.c` (the file the compiler ships as
`src/main/resources/c_generator.c`).

---

## 7. Conway — [`07-conway`](../07-conway/generated.user.c)

The largest end-to-end lowering: an ARC-managed grid class, nested loops,
neighbour counting, and generation stepping — 125 lines of C.

---

## 8. Traits — vtables — [`08-traits`](../08-traits/generated.user.c)

```kira
s: Speaker = dog
trace(s.name())
```

```c
struct SpeakerVTable
{
    Str (*speak)(void* self);
    Str (*name)(void* self);
};

struct Speaker
{
    void* data;
    SpeakerVTable* vtable;
};

static Str Speaker_speak_tramp_Dog(void* self) { return Dog_speak((Dog*)self); }
static Str Speaker_name_tramp_Dog(void* self) { return Dog_name((Dog*)self); }
static SpeakerVTable Speaker_vtable_Dog = { Speaker_speak_tramp_Dog, Speaker_name_tramp_Dog };

Speaker s = ((Speaker){ .data = dog, .vtable = &Speaker_vtable_Dog });
print("%s\n", s.vtable->name(s.data));
```

| Lowering | Rule |
|----------|------|
| `trait T` | `struct T { void* data; TVTable* vtable; }` |
| impl per class | `static` trampolines + one `static TVTable T_vtable_C` |
| class → trait slot | Compound literal binding `data` + `&T_vtable_C` |
| `s.name()` | `s.vtable->name(s.data)` |

> **Known gaps, visible in this snapshot:** `kira_rc_release(cat)` in
> `makeSpeaker` is emitted *after* the `return` (dead code — `cc
> -Wunreachable-code` flags it), and `trace(makeSpeaker().name())` lowers the
> receiver twice, calling `makeSpeaker()` once for `.vtable` and again for
> `.data`. Both are tracked in [docs/backend-c.md](../../docs/backend-c.md).

---

## What these snapshots are for

- **Show the IR contract** — Kira in, readable C17 out, `cc` finishes.
- **Review surface** — if lowering changes, the diff on `generated.user.c` is
  what reviewers read.
- **Regression net** — `regenerate.sh --check` fails when output drifts, and
  `expected.txt` pins observable behaviour, not just the text of the C.
- **Multi-backend later** — the same frontend could grow another target; these
  files document the **C** style we optimize for now.
