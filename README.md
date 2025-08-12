![Kira](./public/kira_header_small.png)

> [!NOTE]
> This project is currently under active development. Documentation may be incomplete.

**Kira** is a modern, pure object-oriented programming language with expressive syntax inspired by Swift, Kotlin, and Dart. It functions as a flexible toolkit—similar to Haxe—supporting transpilation and ahead-of-time (AOT) compilation to multiple targets, including source code, bytecode, bitcode, and machine code.

Kira enforces three core principles: **privacy**, **immutability**, and **static behavior**. All declarations are private and immutable by default. To enable mutability or public access, use the `mut` or `pub` modifiers respectively. Classes contain only instance-level data; static and companion members are managed via namespaces.

---

## Code Snippets

> [!WARNING]
> Syntax and semantics are subject to change.

<!-- Syntax highlighting uses Zig for approximation -->

### Hello, World

```zig
@trace("Hello World!")
```

### Variables, Functions, and Types

Kira is statically typed. All types are declared after a colon `:`. Functions are first-class values.

```zig
a: Int32 = 9999
b: Int32 = 0x10

@trace(a + b)

sumOf(a1: Int32, b1: Int32): Int32 
{
  return a1 + b1
}
```

> Shadowing is disallowed.

#### All Built-in Types

- `Int8`, `Int16`, `Int32`, `Int64` – Signed integers
- `Float32`, `Float64` – Floating-point numbers
- `Bool` – Boolean backed by 8-bit integer
- `Str` – Immutable string
- `Void` – Absence of value
- `Any` – Root type
- `Num` – Parent type of numeric types
- `Array<T>` – Static mutable container
- `List<T>` – Dynamic mutable container
- `Map<K, V>` – Key-value store
- `Set<T>` – Unique value store
- `Maybe<T>` – Nullable wrapper (*In-Triage*)
- `Var<A>` – Source-declared variable
- `Class<A>` - Source declared class
- `Func<A, @_Many_(Any)>` – Function type with compile-time generic expansion

---

### Conditionals and Loops

```zig
someCondition: Bool = 1 + 1 == 2;
if(someCondition)
{
    @trace("Is true!");
}
else
{
    @trace(":(");
}

i: Int32 = 32;
while(i-- > 0)
{
    @trace("Counting down at: ${i}");
}
```

---

### Memory Model

> WIP

Kira follows similar suite to Swift by using **ARC (Automatic Reference Counting)** which provides deterministic memory management without the complexity on the runtime side of a garbage collector. 

Kira provides the following in terms of developer experience and memory safety management to avoid Cycle detections:

**1. Weak References**

Using the `weak` modifier signifies that Kira should not increase the ref count. When the reference object is deallocated, this weak reference
will automatically become `null`.

```zig
weak friend: Person
```

This is ideal for breaking retain cycles in bidirectional relationships, such as parent-child or delegate patterns.

**2. Unsafe References**

Using the `unsafe` modifier signifies that Kira should not increase the ref count and assumes that the object will outlive the reference.

```zig
unsafe buffer: Array<Int8>
```

This is a performance-oriented feature and should be used with caution. If the object is deallocated before the reference is used, it can lead to undefined behavior or runtime crashes.

> Unsafe references are like raw pointers in C/C++—powerful but dangerous.

**3. Finalizers**

Classes are able to have the intrinsic `@finalize` on ONE of its methods to signify cleanup logic for external resources.

```zig
class A
{
    ...
    @finalize close(): Void
    {
        ...
    }
}
```

**4. Pass By Value Opt-In**

Kira allows explicit pass-by-value semantics using the `val` modifier on function or constructor parameters. This instructs the compiler to create a copy of the argument rather than passing a reference.

```zig
sum(val a: Int32, b: Int32): Int32 { ... }
@trace(sum(3, 4)) 
```

In this example:

- `a` is passed by value, meaning a copy of `3` is created.

- `b` is passed by reference, meaning the original `4`

---


### Compile-Time Intrinsics

Kira supports compiler-integrated intrinsics for compile-time execution. These are not user-definable and are designed to simplify expressions, enable metaprogramming, and support DSL construction.

**Properties:**

- Prefixed with `@`
- Treated as standard identifiers
- Function-like or directive-like
- Executed during any compiler phase

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

### Classes

```zig
class Employee
{
    require pub name: String
    require id: Int32
    require pub clockedInTimes: Int64 = 0
    require pub accessDatabase(): Void

    pub transferTo(otherEmployee: Int32): User
    {
        return Employee(
            name = .name
            id = otherEmployee
            clockedInTimes = .clockedInTimes
        )
    }

    pub @__greater_than__(otherEmployee: Employee): Bool
    {
        return .clockedInTimes > otherEmployee.clockedInTimes
    }
}

@main(): Void
{
    @trace(Employee(name = "John Doe", id = 123, accessDatabase = (): Void {
        @trace("I am in!")
    }))
}
```

**Class Constraints:**

1. Single implicit constructor
2. No nested constructs
3. Immutable and private by default
4. No static/companion members (*subject to change*)
5. Anonymous instantiation supported
6. No `protected` keyword
7. No multiple inheritance ([Diamond Problem](https://github.com/exoad/kira/edit/main/README.md))
8. Methods are final unless marked `mut`

---

### Namespaces

Namespaces are scoping structures for helping keep things tidy within a single module, additionally this also means they use the scope-value operator `.`

An example would be having the same functions in 2 different namespaces:

```zig
pub namespace Utils 
{
    pub computeSum(a: Int32, b: Int32): Int32
    {
        return a + b
    }

    pub class Vector2
    {
        require pub x: Float32
        require pub y: Float32
    }
}

pub namespace LinAlg
{
    pub computeDot(a: Utils.Vector2, b: Utils.Vector2): Float64
    {
        return (a.x * b.x) + (a.y + b.y)
    }
}
```

Namespaces support nesting of variables, classes, functions, and other namespaces. Usage should be limited to 1–2 per file.

---

## Chores

### Language Features

- [X] Parser support for escaped string characters
- [ ] String interpolation without `+`
- [X] For loop (*In Triage*)
- [X] Anonymous function literal parsing
- [X] Module declarations
- [ ] Semantical Level Intrinsics
- [ ] Intrinsics Expression Production Rule

### Internal Workings

- [ ] Static analyzer for AST validation
- [X] AST XML output support
- [ ] NekoVM transpilation (*In Progress*)

### General Maintenance

- [X] Verbose logging toggle via `--verbose` (*In Progress*)
