# C-as-IR demos

Small projects that show what Kira **actually emits** today: ISO C17 source
(C-as-IR), not Neko bytecode and not a JIT.

Each folder is a normal Kira project. Checked in next to the sources:

| File | Contents |
|------|----------|
| `src/**/*.kira` | Input |
| `generated.user.c` | Post-prelude lowering only (easy to read) |
| `generated.full.c` | Full `out.kira.c` including the runtime prelude |

Regenerate after backend changes:

```bash
./gradlew installDist
./examples/c-as-ir/regenerate.sh
```

That re-runs `kira`, refreshes both generated files, and smoke-runs each binary
with `cc -std=c17`.

Background: [docs/backend-c.md](../../docs/backend-c.md).

---

## Pipeline (every demo)

```text
*.kira  --kira-->  out.kira.c  --cc -std=c17-->  native app
              │
              ├─ prelude (types, print, Arr/Map helpers)
              └─ user lowering (this page)
```

---

## 1. Hello -- `trace` and `main`

**Kira** (`hello/src/app/main.kira`):

```kira
module "app:main"

fx main(): Void {
    trace("hello from C-as-IR")
}
```

**C-as-IR** (`hello/generated.user.c`, real emit):

```c
Int32 main(Void);

/* module app:main */
Int32 main(Void)
{
    print("%s\n", "hello from C-as-IR");
    return 0;
}
```

| Lowering | Rule |
|----------|------|
| `fx main(): Void` | `Int32 main(Void)` + `return 0` |
| `trace("...")` | `print("%s\n", "...")` (prelude macro → `fprintf`) |

```bash
./examples/c-as-ir/regenerate.sh   # includes hello
# run: hello from C-as-IR
```

---

## 2. Classes -- structs, methods, init

**Kira** (excerpt):

```kira
pub class Pet {
    require pub name: Str
    require pub sound: Str

    pub fx speak(): Str {
        return sound
    }
}

// main:
friend: Pet = Pet { "Mochi", "meow" }
trace(friend.name)
trace(friend.speak())
```

**C-as-IR** (`classes/generated.user.c`, real emit):

```c
typedef struct Point Point;
typedef struct Pet Pet;

struct Point
{
    Int32 x;
    Int32 y;
};

struct Pet
{
    Str name;
    Str sound;
};

Str Pet_speak(Pet* this)
{
    return this->sound;
}

Int32 main(Void)
{
    Point origin = (Point) { 0, 0 };
    Pet friend = (Pet) { "Mochi", "meow" };
    print("%s\n", friend.name);
    print("%s\n", Pet_speak(&friend));
    return 0;
}
```

| Lowering | Rule |
|----------|------|
| `class` | `typedef struct` + field layout |
| `require` fields | Constructor args → compound literal `(Pet) { ... }` |
| method | Free function `Type_method(Type* this, ...)` |
| `friend.speak()` | `Pet_speak(&friend)` |
| field read | `friend.name` |

No vtables, no ARC -- stack structs and pointers.

---

## 3. Generics -- monomorphize, not erase (user types)

**Kira**:

```kira
pub class Box<T> {
    require pub value: T
}

fx id<T>(value: T): T {
    return value
}

wrapped: Box<Int32> = Box<Int32> { 42 }
value: Int32 = id<Int32>(wrapped.value)
```

**C-as-IR** (`generics/generated.user.c`, real emit):

```c
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

Int32 id_Int32(Int32 value)
{
    return value;
}

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
```

| Lowering | Rule |
|----------|------|
| `Box<Int32>` | Distinct type `Box_Int32` (monomorphized) |
| `id<Int32>` | Function `id_Int32` |
| `enum Phase` | C enum, members `PHASE_*` |

Stdlib `Arr`/`Map` stay **erased** at the C boundary (next demo) -- only *user*
generics get per-instantiation names today.

---

## 4. Collections -- prelude helpers

**Kira**:

```kira
numbers: Arr<Int32> = [1, 2, 3]
head: Int32 = numbers[0]
bag: Map<Str, Int32> = Map<Str, Int32> { }
if bag.isEmpty() {
    trace(head)
}
```

**C-as-IR** (`collections/generated.user.c`, real emit):

```c
Int32 main(Void)
{
    Arr numbers = Arr_i32((Int32[]){ 1, 2, 3 }, 3);
    Int32 head = Arr_get_i32(numbers, 0);
    Map bag = Map_new();
    if(Map_isEmpty(&bag))
    {
        print("%d\n", head);
    }
    return 0;
}
```

| Lowering | Rule |
|----------|------|
| `[1, 2, 3]` | Compound literal + `Arr_i32(ptr, len)` |
| `numbers[0]` | `Arr_get_i32(numbers, 0)` |
| `Map<...> { }` | `Map_new()` (count-only baseline map) |
| `.isEmpty()` | `Map_isEmpty(&bag)` |

Helpers live in the **prelude** half of `generated.full.c` (same file the
compiler ships as `src/main/resources/c_generator.c`).

---

## What these demos are for

- **Show the IR contract** -- Kira in, readable C17 out, `cc` finishes.
- **Multi-backend later** -- same frontend could grow a Neko (or other) target;
  these folders document the **C** generation style we optimize for now.
- **Regressions** -- if lowering changes, `regenerate.sh` + diff on
  `generated.user.c` is the review surface.

## Not shown (yet)

ARC/RC heap, Map put/get, growing List, traits, inheritance -- see the status
matrix in [docs/backend-c.md](../../docs/backend-c.md).
