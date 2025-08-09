# Kira Grammar Specifications

July 21, 2025

## Expressions vs. Statements

Like many other languages, Kira uses the distinction between expressions and statements to design the language. 

**Statements** are things that hold other expressions, but do not produce a value (not to be confused with nullability or void)

**Expressions** are things that must produce a value that can be semantically represented in the language.

## Ergonomic Stylistic Choices

Kira focuses a lot on the look of the language to make it as easy to use and also at the same time as easy to identify particular parts of code in just
a simple glance and crafting an understanding where something might come from. The first is making things like intrinsics easier to see through underscores to 
represent the fact that this part is *compiler magic*. On the other hand, it could just be the overall naming scheme of types vs variables.

### Naming Convention

Like many other programming languages, naming is not enforced. There are **THREE** ways of naming things in Kira using the popular:

1. camelCase
2. PascalCase
3. snake_case

#### camelCase

This naming case is used for anything that is only purely for assigning name for the programmer to easily see. This includes things that could be gone after
compilation or transpilation such as variables, function parameters, and function names. The first lowercase is to imply that this has much lower importance 
than something like PascalCase below.

```
myVar: Int32 = 100 // ok!

SumTwoNumbers(a: Int32, b: Int32): Int32 // bad!
{
    return a + b;
}
```

#### PascalCase

This naming case is used for things that are although still user defined, are much more managed at compile time and at runtime that the semantical meaning of the
type is much more important. This is important for things like class names, namespace names, and other structure names. The first uppercase implies higher important
than something like variable names and function names where the variable name only serve to hold semantical value to the programmer, not the compiler entirely.

#### snake_case

In Kira, this is case is only used for intrinsics where they are easily recognizable by the sheer amount of underscores used. This in turn j

## Variables

### Format

```
<Modifiers> <VariableName>: <TypeSpecifiers> = <Expr>
```

### Description

Variables act as named references that hold values. This means you can assign a meaningful name to a value or expression.

You can use the following characters when naming variables:

1. Underscores (_)

2. Alphanumeric characters (letters and numbers)

```kira
a: Int32 = 123
```

## Operators 

Operators are syntax sugar over functions without the functional syntax. They provide a much more common way to express operations. For example, compare the following:

In a language like Java, where operator-overloading is not allowed, having custom classes (e.g. a `Vector2D` type) that can support arithmetic operations can become verbose:

```java
Vector2D myVector = new Vector2D(4, 6);
Vector2D newVector = myVector.plus(new Vector2D(1, 2))
```

On the other hand, in a very similar language like Dart:

```dart
Vector2D myVector = Vector2D(4, 6);
Vector2D newvector = myVector + Vector2D(4, 6);
```

The latter feels much more natural and less tedious especially if the operations are to represent proper mathematical operations.

### Types of Operators


#### Arithmetic
All common arithmetic operations exist in Kira for both binary and unary operations. They are used for all numerical types that stem from the `Num` root type (e.g. `Int32`, `Int64`, `Float32`, etc.).

| Operator | Type     | Description                    |
| :------- | :------- | :----------------------------- |
| `+`      | Binary   | Sum (Addition)                 |
| `-`      | Binary   | Difference (Subtraction)       |
| `*`      | Binary   | Product (Multiplication)       |
| `/`      | Binary   | Floating Point Quotient (Floating Point Division) |
| `%`      | Binary   | Remainder (Modulus)            |
| `-`      | Unary    | Negation                       |
| `+`      | Unary    | Identity                       |

#### Bitwise
Bitwise operators operate on individual bits on only integer types (i.e. `Int8`, `Int16`, `Int32`, and `Int64`). They provide much finer control allow for concepts like bitmasking. 

| Operator | Type     | Description           |
| :------- | :------- | :-------------------- |
| `>>`     | Binary   | Right Shift           |
| `<<`     | Binary   | Left Shift            |
| `>>>`    | Binary   | Unsigned Right Shift  |
| `^`      | Binary   | XOR                   |
| `&`      | Binary   | AND                   |
| `|`      | Binary   | OR                    |
| `~`      | Unary    | NOT                   |

#### Logical
Logical operators work to evaluate boolean values (i.e. `true` & `false`) and provide operations to convert other non-boolean values into expressions that evaluate into boolean values.

| Operator | Type     | Description           |
| :------- | :------- | :-------------------- |
| `&&`     | Binary   | AND                   |
| `||`     | Binary   | OR                    |
| `!`      | Unary    | Unary NOT             |
| `==`     | Comparison | Equality (equal to)   |
| `!=`     | Comparison | Inequality (not equal to) |
| `>`      | Comparison | Greater than          |
| `<`      | Comparison | Less than             |
| `>=`     | Comparison | Greater than or equal to |
| `<=`     | Comparison | Less than or equal to |

### Assignment Operators 🍬

**Normal Assignment**


**Compound**

These are the binary operators that are conjoined with the normal assignment operator as syntax sugar to allow for the following patterns:

```
<Identifier> = <Expr> <BinOp> <Expr>
```

De-sugared would become

```
<Identifier> <BinOp>= <Expr>
```

The following are available using the aforementioned [Binary Arithmetic Operators](#binary-arithmetic-operators)

1. `+=` : Expands `x += y` to `x = x + y`

2. `-=` : Expands `x -= y` to `x = x - y`

3. `*=` : Expands `x *= y` to `x = x * y`

4. `/=` : Expands `x /= y` to `x = x / y`

5. `%=` : Expands `x %= y` to `x = x % y`



## Selection Statements

Selection statements are used to create control flow branches using boolean values. The most common one being the If-Else Selection Statement which is a very common construct.

If-Statements allow for `if` branches which start off the selection process, followed by optional `else-if` and `else` branches.

```
if(<Expr>)
{
	<Statements>
}
else if(<Expr>) // Optional else-if branch
{
	<Statements>
}
else // Optional default branch
{
	<Statements>
}
```

> **Note** There can only be ONE `else` branch per If-Statement branch.

## Immutability By Default

Kira promotes immutability by default in order to not only maximize on performance but also for safety and predictability. In the simplest form, you can see this as not being able to reassign variables who do not have the `mut` modifier:

```kira
youCannotReassignMe: Str = "This is the only value I can hold!" 

youCannotReassignMe = "I cannot hold this value :(" // ❌ Error!
```
However, this does not just span into 




- [ ] 