# Kira Language Specifications

Version 1.1

October 1, 2025

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

> Note: These are statements and not expressions (e.g. you cannot return using a control flow structure).

## Functions

Kira treats functions as first-class citizens, meaning you can use them as values and pass them around.

To return a value, Kira uses the `return` keyword. If a function has no return, it must
specify its return type as `Void` or `Nothing`.

```
fx sumOf(a: Int32, b: Int32 = 3): Int32 {Inte
    return a + b
}
```

Function parameters can either be specified using their name or using position.

## String Interpolation

Using the `${}` syntax, you can embed expressions directly into strings.

```
name: Str = "John"
age: Int32 = 34
@trace("My name is ${name} and I am ${age} years old.")
```

> Please note that there is no `$var` syntax without the curly braces. This is to avoid ambiguity and confusion.
> 
> Additionally, you can escape the dollar sign by using `\$`.

## Module System

Kira uses a module system for you to
