# 4 -- Classes

**Example:** [`examples/04-classes`](../../examples/04-classes)

## Two small types

**`model.kira`** -- data + behavior:

```kira
module "app:model"

pub class Point {
    require pub x: Int32
    require pub y: Int32
}

pub class Rectangle {
    require pub topLeft: Point
    require pub bottomRight: Point

    pub fx perimeter(): Int32 {
        width: Int32 = bottomRight.x - topLeft.x
        height: Int32 = topLeft.y - bottomRight.y
        return (width + height) * 2
    }
}

pub class Pet {
    require pub name: Str
    require pub sound: Str

    pub fx speak(): Str {
        return sound
    }
}
```

**`main.kira`** -- construct and call:

```kira
module "app:main"

use "app:model"

fx main(): Void {
    rect: Rectangle = Rectangle { Point { 0, 1 }, Point { 1, 0 } }
    friend: Pet = Pet { "Mochi", "meow" }

    trace(rect.perimeter())
    trace(friend.name)
    trace(friend.speak())
}
```

```bash
./examples/run.sh 04-classes
# 4
# Mochi
# meow
```

## Anatomy

### `pub class`

Types meant to be used from other modules are `pub`. Name them `PascalCase`.

### `require` fields

```kira
require pub x: Int32
```

- `require` fields are constructor parameters -- they must be supplied at init.
- `pub` on a field makes it readable from outside the class.
- Field names are `camelCase`.

### Methods

```kira
pub fx perimeter(): Int32 {
    ...
}
```

- Declared inside the class body with `fx`.
- Inside a method, fields are in scope (`bottomRight.x`).
- The C backend lowers methods to free functions:
  `Rectangle_perimeter(Rectangle* this, ...)`.

### Object initialization

```kira
Point { 0, 1 }
Pet { "Mochi", "meow" }
```

Positional args match `require` field order. Nested inits work:

```kira
Rectangle { Point { 0, 1 }, Point { 1, 0 } }
```

## What is not in the baseline backend yet

The language reference describes inheritance, traits, and richer constructors.
The C backend today focuses on:

- flat classes with `require` fields
- instance methods
- field reads and method calls

Stay inside that shape for programs you want to run end-to-end.

## Try this

Add `pub fx area(): Int32` on `Rectangle` and print it from `main`.

## Next

[5 -- Enums and generics](05-enums-generics.md)
