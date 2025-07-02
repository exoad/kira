# Kira Language Design

## Syntax

### Comments

Line Comments will suffice and are stripped during the preprocessor phase.

```
//
```

### Variable Declaration

Variables are immutable by default and thus are specified without modifiers. However, in order to support
immutability, the `mut` keyword is appended as a modifier to the variable to signify clearly.

```bnf
<declaration> ::= [ "mut" ] <identifier> ":" <type> "=" <rvalue> ";"
```

#### Examples

```
immutableVariable: Int32 = 123;

mut mutableVariable: Int32 = 12309;
```

### Builtin Types

Everything in Kira is an object, meaning even low level types like `Int32`, `Int64` are defined at the source level.

Types are preferred to be named in PascalCase which is familiar to many modern programmers whom have experience
with Kotlin, C#, Dart, and Java to name a few.

Additionally, most builtin types will have syntactic sugar built-in to facilitate ease of use and simpler syntax.

**Numerical**

1. `Number` - root of all numerical types

**Integers**

1. `Int32` - 32bit integer
2. `Int64` - 64bit integer
3. `Int16` - 16bit integer
4. `Int8` - 8bit integer

**Floating Point**

1. `Float32` - 32bit float
2. `Float64` - 64bit float

**Unit**

1. `Void` - return type / absence of return

**String**

1. `String` - iterable string

**Aggregate**

1. `List<T>` - dynamically resizing array
2. `Array<T, Number>` - immutable, non-resizable array
3. `Set<T>`

### Arithmetic, Logical, Bitwise Unary & Binary Operations

Kira supports all common operations found of which are:

```
OP_ADD("'+' (Plus)"),
OP_SUB("'-' (Minus)"),
OP_MUL("'*' (Multiply)"),
OP_DIV("'/' (Divide)"),
OP_MOD("'%' (Modulo)"),
OP_ASSIGN("'=' (Assignment)"),
OP_ASSIGN_ADD("'+=' (Compound Addition Assignment)"),
OP_ASSIGN_SUB("'-=' (Compound Subtraction Assignment)"),
OP_ASSIGN_MUL("'*=' (Compound Multiplication Assignment)"),
OP_ASSIGN_DIV("'/=' (Compound Division Assignment)"),
OP_ASSIGN_MOD("'%=' (Compound Modulus Assignment)"),
OP_ASSIGN_BIT_OR("'|=' (Compound Bitwise OR Assignment)"),
OP_ASSIGN_BIT_AND("'&=' (Compound Bitwise AND Assignment)"),
OP_ASSIGN_BITSHL("'<<=' (Compound Bitwise Left Shift Assignment)"),
OP_ASSIGN_BIT_SHR("'>>=' (Compound Bitwise Right Shift Assignment)"),
OP_ASSIGN_BIT_USHR("'>>>=' (Compound Bitwise Unsigned Right Shift Assignment)"),
OP_ASSIGN_BIT_XOR("'^=' (Compound Bitwise XOR Assignment)"),
OP_CMP_LEQ("'<=' (Less Than Or Equal To)"),
OP_CMP_GEQ("'>=' (Greater Than Or Equal To)"),
OP_CMP_EQL("'==' (Equals To)"),
OP_CMP_NEQ("'!=' (Not Equals To)"),
OP_CMP_AND("'&&' (Logical AND)"),
OP_CMP_OR("'||' (Logical OR)"),
OP_BIT_SHL("'<<' (Bitwise Shift Left)"),
OP_BIT_SHR("'>>' (Bitwise Shift Right"),
OP_BIT_USHR("'>>>' (Bitwise Unsigned Shift Right"),
OP_BIT_XOR("'^' (Bitwise XOR"),
```

> Note that for any `OP_ASSIGN` operations, they can only be performed on [mutable variables](#variable-declaration).
>
> **An integer division operator is proposed.**

#### Examples

```
math: Int32 = 123 + 123;
```

### Execution Flow

There are various constructs in programming languages on iteration and selection statements that allow the developer
to specify logic flow.

Kira features these common constructs:

1. `while` iteration
2. `do-while` iteration
3. `if-else if-else` selection
4. `for` iteration