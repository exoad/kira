![Kira](./public/kira_header_small.png)

> [!NOTE]
> Hi! This project is currently a work in progress :)
>
> Thus, a lot of things will lack documentation!

Kira is a modern, pure object-oriented language that combines familiar expressive syntaxes in modern languages like
Swift, Kotlin, and Dart. Kira serves as a modern toolkit similar to Haxe, allowing for transpilation and AOT-compilation to various
mediums.

Kira's model focuses on private, immutable, and static. This means that without the mut keyword (for mutability or inheritance) or pub (for public access), variables, functions, and classes cannot be modified, reassigned, or inherited.
Classes in Kira only hold instance-level information, without static or companion members. Instead, namespaces handle code organization and separate static from instance data.

`July 19, 2025:`

I have decided on Kira's main workflow target as towards transpiling to other mediums (be it source, bytecode, bitcode, or machine code). This is resolution
is backed by the strong compile-time intrinsics I plan to further improve from not just simple directives but towards "metaprogramming."

`June 28, 2025:`

Currently, the compiler and further language designs are not finished. The frontend is implemented in Kotlin with the
backend currently chosen to either be LLVM or a fork of NekoVM. Additionally, I have also been considering generating
raw x86 Assembly and using NASM to do the rest; however, we will see where this goes :).

## Code Snippets

> [!WARNING]
> This is an early draft. Syntax and semantics are subject to change!

<!-- dont worry about the zig highlighting, it looks close enough -->

*Semicolons as statement delimiters are **optional** ;)*

### Hello, World!

*Please note that this is an early build where there is yet to be a standard library, so compile-time intrinsics are used.*

```zig
@trace("Hello World!")
```

### Variables, Functions, & Data

Kira is a statically-typed language (*there are plans for type inferencing*) meaning that you must specify the type you want at source. With this,
everything is an object, and there are a few builtin (i.e. supporting syntax sugar for instantiation) types:

#### Integer types

- `Int8` - 8-Bit Signed Integer
- `Int16` - 16-Bit Signed Integer
- `Int32` - 32-Bit Signed Integer
- `Int64` - 64-Bit Signed Integer

*Unsigned versions are still in-triage*

#### Floating-Point Types

- `Float32` - 32-Bit Floating-Point Number
- `Float64` - 64-Bit Floating-Point Number

#### Misc Types

- `Void` - An absence of value; used for functions.
- `@_Never_` - (*In-Triage*) Intrinsic specifying that a function never returns.
- `Any` - Root type 
- `Str` - An immutable string object
- `Bool` - A boolean value that can hold either `true` or `false` and is backed by an 8-bit integer internally
- `Array<T>` - A static (non-resizable) mutable container for storing sequential data
- `List<T>` - A dynamic (resizable) mutable container for storing sequential data
- `Map<K, V>` - A dynamic (resizable) mutable hash table for storing relational data in a key value pair
- `Set<T>` - A dynamic (resizable) mutable hash set for storing unique data
- `Num` - Represents the parent type of floating-point and integer types
- `Maybe<T>` - (*In-Triage*) Represents a potentially nullable value with type `T`
- `Func<A, @_Many_(Any)>` - Function syntax where `A` represents the return type and `@_Many_` is a compile-time intrinsic for generating generics at parse time.

> For function types, the syntax for `...` a variadic declaration is in-triage to replace or make it less verbose to use `@_Many_(T)`

#### Declaration Style

All types are declared after a colon `:` even for functions and variables as functions are first-class citizens (meaning they are values)

```zig
a: Int32 = 9999
b: Int32 = 0x10

@trace(a + b)

sumOf(a1: Int32, b1: Int32): Int32 
{
  return a1 + b1
}
```

> Shadowing is not permitted.

### Conditionals & Loops

Currently, there is only one style of selection statement available which is the `if-else` statements which all operate
on the `Bool` data type.


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

[//]: # (### Compile-Time Intrinsics &#40;Implementation&#41;)

[//]: # ()
[//]: # (This is the main design point of Kira, where I found it crucial or enjoyable for me to have something that can run at compile time like a function)

[//]: # (that could get picked up. This was akin to concepts in C/C++ with the preprocessor and `constexpr`, Jai's compile time expressions, and Rust macros.)

[//]: # ()
[//]: # (Within Kira, they are like any other function, variable, and identifier, but they are prefixed with an `@`. In order to see how they are used, it is crucial)

[//]: # (to view their documentation. Some are function-like while others are not and if they are function-like, anything passed to them is directly sent off to the )

[//]: # (intrinsic's compile-time receiver which processes the code directly and outputs something appropriately.)

[//]: # ()
[//]: # (Additionally, intrinsics are sometimes worked on at different periods of the compilation process. Some intrinsics are able to run at the parser stage, others )

[//]: # (during semantic analysis & optimization, and some even as late as final output generation.)

[//]: # ()
[//]: # (This not only allows for very possible things like DSL creations, but you could have your entire program run at compile time, but this isn't the main purpose, )

[//]: # (instead it allows for precomputation and doing metaprogramming.)

[//]: # ()
[//]: # (For example, you can have something like:)

[//]: # ()
[//]: # (```zig)

[//]: # (a: Map<String, Any> = @_json_decode_&#40;```)

[//]: # (  {)

[//]: # (    "hello": 1,)

[//]: # (    "world": 2)

[//]: # (  })

[//]: # (```&#41;)

[//]: # ()
[//]: # (@trace&#40;a["hello"])

[//]: # (```)

[//]: # ()
[//]: # (Here `_json_decode_` is a compiler library that is able to decode the JSON value and returns the appropriate Kira equivalent value.)

[//]: # ()

### Classes

**Classes perform the same as many other languages, where they serve the purpose of being "blueprints" to create Objects.** However,
in Kira there are some limitations to them:

1. Only one canonical constructor, which is _implicitly_ declared
2. Classes cannot nest other constructs (i.e. classes & namespaces). Instead, use either top-level declarations or object declarations for scoping.
3. Classes are immutable and private by default just like everything else in the language unless they are marked with `pub` and `mut`
4. Static/Companion members are not allowed. (*This is not finalized*) 
5. There are no restrictions to instantizing anonymous class instead of inheritance. 
6. There is no `protected` keyword like in Java and Kotlin
7. No multi-inheritance.
8. Methods or member functions are final or non-virtual by default unless specified with the `mut` modifier

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
            name = ::name
            id = otherEmployee
            clockedInTimes = ::clockedInTimes
        )
    }

    pub @__greater_than__(otherEmployee: Employee): Bool
    {
        if(::clockedInTimes > otherEmployee::clockedInTimes)
        {
            return true;
        }
        return false;
    }
}

@main(): Void
{
    @trace(Employee(name = "John Doe", id = 123, accessDatabase = (): Void {
        @trace("I am in!")
   }))
}
```

Check back later! More stuffs will come and go :)

### Namespaces

Namespaces are used primarily for code organizations within modules allowing for nesting:
1. Variables (promoting companion/static members)
2. Classes 
3. Functions
4. Other namespaces

They can be declared anywhere, but should not be abused where a single file should have 1 to 2 namespaces max.

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
```

> Everything in Kira is private and immutable by default!

## Chores

### Language Features

Chores relating to anything to do with the upfront semantics and syntax of the language

- [ ] Parser evaluates escaped string characters

- [ ] Interpolation in strings without using `+`

- [X] **In Triage** For Loop

- [ ] **Pending Implementation** Proper parsing of anonymous function literals using `AnonymousIdentifier`

- [ ] **In Triage** Module declaration at the start of the source file

### Internal Workings

Chores relating to anything that involves the internal workings

- [ ] Static Analyzer for validating the state of the AST

- [X] Support for outputting the AST as an XML document so there is not a necessary usage of another parser to parse the
  formatting

- [ ] **In Progress** Transpile to NekoVM Neko code with full support for all features

### General Maintenance

General upkeep.

- [ ] **In Progress** Enable more verbose logging that is currently toggleable through --verbose

