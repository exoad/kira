# 5 -- Enums and generics

**Example:** [`examples/05-enums-generics`](../../examples/05-enums-generics)

## Enums

```kira
module "app:status"

pub enum BuildStatus {
    READY,
    RUNNING,
    DONE
}
```

Use members with a dot:

```kira
state: BuildStatus = BuildStatus.READY

if state == BuildStatus.READY {
    trace(value)
}
```

In C this becomes a tagged enum (`BUILD_STATUS_READY`, ...). Member names in
source are `UPPER_SNAKE` / simple tokens as declared.

## Generic class

```kira
module "app:box"

pub class Box<T> {
    require pub value: T
}

fx id<T>(value: T): T {
    return value
}
```

- Type parameters are PascalCase single letters by convention (`T`, `K`, `V`).
- At the call / init site you pass concrete types:

```kira
wrapped: Box<Int32> = Box<Int32> { 7 }
value: Int32 = id<Int32>(wrapped.value)
```

## How the C backend treats generics

User generics are **monomorphized**:

| Kira | Emitted C (sketch) |
|------|--------------------|
| `Box<Int32>` | `struct Box_Int32 { Int32 value; }` |
| `id<Int32>(x)` | `id_Int32(x)` |

So each concrete instantiation gets its own type/function. Magic stdlib
generics (`Arr`, `Map`, ...) stay **erased** at the C boundary (see next
chapter).

## Full example `main`

```kira
module "app:main"

use "app:status"
use "app:box"

fx main(): Void {
    state: BuildStatus = BuildStatus.READY
    wrapped: Box<Int32> = Box<Int32> { 7 }
    value: Int32 = id<Int32>(wrapped.value)

    if state == BuildStatus.READY {
        trace(value)
    }
}
```

```bash
./examples/run.sh 05-enums-generics
# 7
```

## Try this

Add `Box<Str>` with a string value and a second `id<Str>` call; print both.

## Next

[6 -- Collections](06-collections.md)
