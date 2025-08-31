# Kira Language Specifications

Version 1.0

August 30, 2025

## Hello World

```
@trace("Hello World!")
```

## Comments

Kira supports comments via `//`. Anything after will be stripped out by the preprocessor.

```
// I won't appear in the generated output!

```

## Variables

Kira is a statically-typed language meaning that all variables must declare a type.

```
name: Str = "John"
age: Int32 = 34
isOk: Bool = true
```

## Control Flow Structures

These structures help determine which pieces of your code will run. Kira
has the following control structures:

1. `if-else` selection statements
2. `while` loops
3. `do-while` loops
4. `for` loops

```
// If-Else Selection Statement
if name == "John" {
    @trace("Hi John")
} else if name == "Jamie" {
    @trace("Hi Jamie")
} else {
    @trace("Who are you?")
}

// While Loop
while true {
    @trace("Looping forever...")
}

// Do-While Loop
do {
    @trace("I will be printed once!")
} while false

// For Loop
for i: Int32 in 1..10 {
    @trace(i)
}
```

## Functions

Kira treats functions as first-class citizens, meaning you can use them as values and pass them around.

To return a value, Kira uses the `return` keyword. If a function has no return, it must
specify its return type as `Void` or `Nothing`.

```
fx sumOf(a: Int32, b: Int32 = 3): Int32 {
    return a + b
}
```

Function parameters can either be specified using their name or using position.

## Module System

Kira uses a module system for you to organize your source structure. Each piece of Kira source file
starts off with a module declaration:

```
module "author:module_name.submodule_name"
```

-   `author` represents the organization or individual that this belongs to.
-   `module_name` represents what project this source file falls under.
-   `submodule_name` represents this individual source file's name

### Using a submodule

Often times, you would include or import submodules, not entire modules themselves per submodule. This is
to save on compile time and also potential bloating. To include another submodule:

```
use "author:module_name.submodule_name"

use "author:module_name" // makes all submodules available to the current context
```

For example, to use all builtin types from Kira (e.g. `Int32`, `Str`), you need to use the submodule `kira:lib.types`.

## Classes

Kira supports classes, an object-oriented principle. Class in Kira are very easy and malleable to understand and
lightweight;
however, they do have some limitations:

1. No multi-inheritance (Diamond Inheritance Problem)
2. Only one default constructor
3. No nested structures (classes, enums, namespaces)
4. No companion or static members
5. All fields that are virtual can be provided through the constructor.
6. No direct abstract classes or interfaces

```
pub class Vector2
{
    require pub mut x: Float32
    require pub mut y: Float32

    pub fx dot(other: Vector2): Float32 {
        return (other.x * x) + (other.y * y)
    }

    pub mut fx toStr(): Str {
        return "( ${x}, ${y} )"
    }
}

a: Vector2 = Vector2 { 3, 3 }
b: Vector2 = Vector2 { 3, 3, toStr = fx() { return "< ${x}, ${y} >" } }
@trace(a.dot(b))
```

## Immutability By Default

Everything in Kira is immutable or closed by default. This means variables cannot be reassigned/mutated, classes
cannot be inherited from, methods in classes cannot be overridden by default.

In order to allow for mutability, specify the `mut` modifier before the element you want to make mutable.

## Visibility Modifiers

There are only 2 visibility levels allowed:

1. public - `pub`
2. internal - implicit (i.e. no keyword used)

### Public Modifier

The `pub` modifier specifies that anything outside can look inside. For example, a submodule which has a
class:

```
class A {
}

pub class B {
}
```

An external submodule that uses this cannot see `class A`, but can see `class B`. Additionally, `class B` can see
`class A` and vice versa.

Within classes themselves, the modifier only serves as encapsulation purposes (i.e. hiding data and fields). If
a member of a class does not have the `pub` modifier, that field can only be access through the setter during
construction
or within a neighboring method that can expose it (getter). Additionally, if the class is inheritable, it means that
field
is also private from the child and the child cannot access it.

> **Why no `protected`?**
>
> In other languages like Java, the `protected` keyword is another visibility layer that allows for only the members of
> the class
> and children of that class to view that field.
>
> Kira does not utilize this layer simply because it is extra overhead and increases the learning curve. Creating a
> binary system where
> it is either the field is visible or not makes it not only easier, but gives more freedom to the programmer in
> designing their APIs.

## Null Safety

Kira incorporates sound null-safety as one of its core pillars. It does so using a core type: `Maybe< A >`.

Without boxing a type in `Maybe`, you are not allowed to assign the literal `null` to anything:

```
a: Int32 = null // error!

b: Maybe<Int32> = null // ok!
```

`Maybe` also enables for field-valuation meaning you can achieve the following without using a getter for the internally
held value:

```
mut a: Maybe<Int32> = null

@trace(a) // null
@trace(a.value) // null

a = 32

@trace(a) // 32
@trace(a.value) // 32
```

### Null Safety Operators

**Null Safe Get**

`Maybe< A >::unwrapOr(option: A)`

If the underlying value is `null`, return `option` provided as `A` else return the underlying value.

```
mut a: Maybe<Int32> = null

if a == null {
    @trace("Null")
} else {
    @trace(a)
}

// is akin to:

@trace(a.unwrapOr(0))
```

**Sanity Checks**

If you do not like using `==` to check if the value is equal to `null`, there are 2 methods to help you:

1. `Maybe< A >::isNull(): Bool` - returns `true` if the underlying value is `null` else `false`
2. `Maybe< A >::isSome(): Bool` - returns `true` if the underlying value is not `null` else `false`

## Exceptions

Kira only has unchecked exceptions meaning that you do not need to catch everything. Exceptions are only strings that
denote what went wrong:

```
throw "Something went wrong."
```

### Exception Handling

Exceptions can be caught by using a `try-on` expression:

```
try {
    // ...
} on e: Str {
    @trace(e)
}
```

> **Note:** The variable required after `on` is always guaranteed to be of `Str`

## Memory Model

Kira uses an Automatic Reference Counting (ARC) memory model similar to Swift. Every object has a reference counter and
when this counter drops
to zero, the object is freed from memory. However, in certain scenarios like a circular reference where A and B
reference each other, it can cause
them to never be deallocated leading to undefined behavior and/or memory faults.

To mitigate this, Kira has 2 types that are intrinsified by the compiler:

1. `Weak< T >` - Used to denote a non-strong reference to mitigate circular references and allow for deallocation.
2. `Unsafe< T >` - Signifies that the reference count should not be increased as the object will outlive the reference.
   _This is a strictly performance oriented feature._

## Finalizers & Initializers Blocks

These are special functions that are only callable by the runtime and not from Kira itself. They are meant to be
executed right at the
time of object allocation and deallocation and are only permitted within classes.

### Initializers

Initializers are ran right at the moment of object construction. It is accomplished by introducing the `initially`
block:

```
class Person {
    require pub age: Int32

    initially {
        if age < 0 {
            @trace("Age must be >= 0!")
        }
    }
}
```

### Finalizers

Finalizers are used to clean up external resources from things like File IO. It is accomplished by introducing the
`finally` block:

```
class FileIO {

    finally {
        doCleanup()
    }
}
```

All code in the finally block is called and ran when the object is about to be deallocated.

## Core Types

Core types are intrinsified by the compiler, meaning the compiler both recognizes them and also will optimize
specifically for them.

In other languages, such as Java, they are known as primitive types and boxed types. Kira does not perform boxing,
everything is an object.

### Integer Like

1. `Int32` - 32-bit integer
2. `Int64` - 64-bit integer
3. `Int16` - 16-bit integer
4. `Int8` - 8-bit integer

### Floating Point

1. `Float32` - 32-bit floating point
2. `Float64` - 64-bit floating point

### Logical

1. `Bool` - Represents a boolean which can either be `true` or `false`. Backed by an 8-bit integer.

### Unit Types

1. `Void` - Specifies no return type
2. `Never` - Specifies a function will never complete (i.e. all code after is dead code).

### Reference Types

1. `Weak< A >` - Weak Reference
2. `Unsafe< A >` - Unsafe Reference
3. `Maybe< A >` - Null Safety. Allows for assigning the `null` literal.
4. `Any` - Root of all classes
5. `Type` - Runtime type representation
6. `Fx< R, [ T: Any ] >` - Function type representation
7. `Module` - Module metadata, recursive for submodules.
8. `Ref< A >` - Holds a reference to an object directly (i.e. boxing `A`)
9. `Result< A, B >` - A different approach to null safety where it allows for a reason if `A` is null via `B`
10. `Val< A >` - A trait that is used to denote that it can be referenced directly as `A`. Used by `Maybe`

### Sequence Types

1. `Str` - String/Character Sequence
2. `Arr< A >` - Static immutable array structure
3. `List< A >` - Dynamic mutable array structure (not-compiler-optimized)
4. `Map< K, V >` - Dynamic mutable hash table structure (not-compiler-optimized)
5. `Set< A >` - Dynamic mutable hash set structure (not-compiler-optimized)

## Compile-Time Intrinsics

Kira supports compiler-integrated intrinsics for compile-time execution. These are not user-definable and are designed
to simplify expressions, enable metaprogramming, and support DSL construction.

**Properties:**

-   Prefixed with `@`
-   Treated as standard identifiers
-   Function-like or directive-like
-   Executed during any compiler phase

```zig
a: Map<String, Any> = @json_decode(`
  {
    "hello": 1,
    "world": 2
  }
`)

@trace(a["hello"]) // Outputs 1 to debugger
```

---
