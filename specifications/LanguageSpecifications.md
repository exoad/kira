# Kira Language Specification

**Version:** 1.0
**Last Updated:** November 14, 2025

## Table of Contents

1. [Introduction](#introduction)
2. [Lexical Structure](#lexical-structure)
3. [Syntax Grammar (BNF)](#syntax-grammar-bnf)
4. [Expressions and Operators](#expressions-and-operators)
5. [Type System](#type-system)
6. [Variables and Declarations](#variables-and-declarations)
7. [Control Flow](#control-flow)
8. [Functions](#functions)
9. [Module System](#module-system)
10. [Object-Oriented Programming](#object-oriented-programming)
11. [Generics and Type Parameters](#generics-and-type-parameters)
12. [Memory Model](#memory-model)
13. [Error Handling](#error-handling)
14. [Standard Library](#standard-library)

---

## Introduction

Kira is a statically typed, object-oriented programming language designed with emphasis on safety, performance, and developer ergonomics. The language combines modern type system features including null safety, automatic memory management through reference counting, and compile-time optimizations while maintaining source-level clarity and predictability.

### Design Principles

-   **Safety First:** Sound null safety, statically typed expressions, and memory safety through automatic reference counting
-   **Explicit Over Implicit:** Clear syntax with minimal operator overloading and explicit type annotations
-   **Performance:** Compile-time optimizations, zero-cost abstractions, and efficient runtime model
-   **Simplicity:** Minimal feature set with orthogonal language constructs

### Example Programs

**Hello World**

```kira
@trace("Hello World!")
```

**FizzBuzz Implementation**

```kira
for i in 0..101 {
   if i % 15 == 0 {
      @trace("FizzBuzz")
   } else if i % 3 == 0 {
      @trace("Fizz")
   } else if i % 5 == 0 {
      @trace("Buzz")
   } else {
      @trace(i)
   }
}
```

---

## Lexical Structure

### Whitespace and Formatting

Kira treats whitespace (spaces, tabs, and newlines) as token separators. The language uses significant whitespace for readability but does not enforce indentation-based scoping like Python.

**Line Breaks:**

Line breaks are significant in Kira and serve as statement terminators in most contexts. A newline character indicates the end of a statement unless the statement is clearly incomplete (e.g., unclosed parentheses, operators at line end).

```kira
x: Int32 = 10
y: Int32 = 20
z: Int32 = x + y
```

### Semicolons

**Semicolons are optional in Kira.** Statements are automatically terminated at line breaks when the statement is syntactically complete. However, semicolons can be explicitly used to separate multiple statements on a single line.

**Automatic Semicolon Insertion:**

The parser inserts implicit semicolons at newlines when:

-   The line contains a complete statement
-   The next token cannot continue the current statement
-   The line does not end with an operator or open delimiter

**Examples:**

```kira
x: Int32 = 10
y: Int32 = 20
z: Int32 = x + y

x: Int32 = 10; y: Int32 = 20; z: Int32 = x + y

result: Int32 = compute(
    x,
    y,
    z
)

total: Int32 = x +
    y +
    z
```

**When Semicolons Are Required:**

Semicolons are mandatory when placing multiple statements on the same line:

```kira
x: Int32 = 10; y: Int32 = 20
```

### Comments

Kira supports single-line comments using the `//` delimiter. All text following `//` until the end of the line is treated as a comment and removed during preprocessing.

```kira
@trace("Hello World!")
```

Multi-line or block comments are not supported to maintain parsing simplicity and avoid nested comment ambiguities.

### Identifiers and Naming Conventions

Identifiers in Kira must follow strict naming conventions that are enforced by the compiler. These rules ensure consistency across codebases and provide visual distinction between different language constructs.

**Grammar:**

```
Identifier          ::= Letter (Letter | Digit)*
ConstantIdentifier  ::= UpperLetter (UpperLetter | Digit | '_')*
IntrinsicIdentifier ::= '@' (Letter | '_') (Letter | Digit | '_')*
Letter              ::= [a-zA-Z]
UpperLetter         ::= [A-Z]
Digit               ::= [0-9]
```

**General Rules:**

-   All identifiers are case-sensitive
-   Identifiers must start with a letter (except intrinsics which start with `@`)
-   No length limit (but should be reasonably sized for readability)
-   Keywords cannot be used as identifiers

### Compiler-Enforced Naming Conventions

The Kira compiler enforces specific naming patterns based on the declaration type. Violating these conventions results in compilation errors.

#### Variables and Function Names: camelCase

Variables and function names must use camelCase: start with a lowercase letter, with subsequent words capitalized.

**Pattern:** `^[a-z][a-zA-Z0-9]*$`

**Valid Examples:**

```kira
userName: Str = "Alice"
totalCount: Int32 = 42
isValid: Bool = true
x: Int32 = 10

fx calculateTotal(items: List<Int32>): Int32 { }
fx getUserById(id: Int64): User { }
fx process(): Void { }
```

**Invalid Examples:**

```kira
UserName: Str = "Alice"
user_name: Str = "Alice"
TOTAL: Int32 = 42
```

#### Type and Class Names: PascalCase

Type names, class names, trait names, and generic type parameters must use PascalCase: start with an uppercase letter, with subsequent words capitalized.

**Pattern:** `^[A-Z][a-zA-Z0-9]*$`

**Valid Examples:**

```kira
pub class User { }
pub class HttpClient { }
pub class DatabaseConnection { }

pub trait Comparable { }
pub trait Serializable { }

type UserId = Int64
type Callback = Fx<Tuple1<Str>, Void>

class Box<T> { }
class Pair<A, B> { }
```

**Invalid Examples:**

```kira
class user { }
class User_Account { }
class USER { }
```

#### Constants: UPPER_SNAKE_CASE

Constants (compile-time values) and global constant declarations must use UPPER_SNAKE_CASE: all uppercase letters with underscores separating words.

**Pattern:** `^[A-Z][A-Z0-9_]*$`

**Valid Examples:**

```kira
MAX_SIZE: Int32 = 1000
DEFAULT_TIMEOUT: Int64 = 30000
PI: Float64 = 3.141592653589793
CONNECTION_STRING: Str = "localhost:8080"
MAX_RETRY_COUNT: Int32 = 5
DEFAULT_BUFFER_SIZE: Int32 = 4096
```

**Constant Declaration Syntax:**

Constants must be declared at module level (not inside functions or classes) and must be initialized with compile-time constant expressions:

```kira
MAX_CONNECTIONS: Int32 = 100

fx someFunction(): Void {
    LOCAL_CONSTANT: Int32 = 10
    mut localValue: Int32 = 10
}
```

**Invalid Examples:**

```kira
maxSize: Int32 = 1000
max_size: Int32 = 1000
Max_Size: Int32 = 1000
```

#### Special Identifiers: Intrinsics (snake_case with @ prefix)

Compiler intrinsics are prefixed with `@` and use snake_case to visually distinguish them from user-defined code.

**Pattern:** `^@[a-z_][a-z0-9_]*$`

**Examples:**

```kira
@trace("Debug output")
@type_of(myValue)
@json_decode("{}")
@system_time()
@trace
@get
@trace_value
@compute_hash
```

**Important Notes:**

-   Intrinsics are compiler-provided and cannot be defined by users
-   The `@` prefix is part of the identifier and distinguishes intrinsics from regular identifiers
-   Underscores are allowed only in intrinsics, not in ordinary identifiers

#### Module Names: snake_case

Module and source file names may use snake_case for compatibility with file systems and conventional naming practices.

**Examples:**

```kira
module "myorg:networking.http_client"
module "myorg:data.database_connection"
```

**Note:** This is the only context where snake_case is permitted for non-intrinsic identifiers.

### Naming Convention Enforcement

The compiler enforces these naming conventions at compile time:

**Compile-Time Errors:**

```kira
// Each of these produces a specific compiler error

User_Name: Str = "Alice"
// Error: Variable 'User_Name' does not conform to camelCase naming convention.
// Expected pattern: ^[a-z][a-zA-Z0-9]*$

class myClass { }
// Error: Class 'myClass' does not conform to PascalCase naming convention.
// Expected pattern: ^[A-Z][a-zA-Z0-9]*$

max_size: Int32 = 100
// Error: Module-level binding 'max_size' appears to be a constant but does not
// conform to UPPER_SNAKE_CASE naming convention. Did you mean 'MAX_SIZE'?

MaxSize: Int32 = 100
// Error: If 'MaxSize' is intended as a constant, it must use UPPER_SNAKE_CASE.
// Use 'MAX_SIZE' instead.
```

### Summary Table

| Declaration Type | Convention       | Pattern                | Example                   |
| ---------------- | ---------------- | ---------------------- | ------------------------- |
| Variables        | camelCase        | `^[a-z][a-zA-Z0-9]*$`  | `userName`, `totalCount`  |
| Functions        | camelCase        | `^[a-z][a-zA-Z0-9]*$`  | `calculateSum`, `getData` |
| Classes          | PascalCase       | `^[A-Z][a-zA-Z0-9]*$`  | `User`, `HttpClient`      |
| Traits           | PascalCase       | `^[A-Z][a-zA-Z0-9]*$`  | `Comparable`, `Iterator`  |
| Type Parameters  | PascalCase       | `^[A-Z][a-zA-Z0-9]*$`  | `T`, `Key`, `Value`       |
| Constants        | UPPER_SNAKE_CASE | `^[A-Z][A-Z0-9_]*$`    | `MAX_SIZE`, `PI`          |
| Intrinsics       | @snake_case      | `^@[a-z_][a-z0-9_]*$`  | `@trace`, `@type_of`      |
| Module Names     | snake_case       | File system compatible | `http_client.kira`        |

### Rationale

**Enforced Naming Conventions:**

-   Provides immediate visual distinction between constants, types, and runtime values
-   Eliminates ambiguity about the nature of an identifier
-   Enables better tooling support (auto-complete, refactoring)
-   Prevents common mistakes (e.g., treating variables as types)
-   Maintains consistency across codebases and teams

**Underscore Restrictions:**

-   Reserving underscores for special cases (constants and intrinsics) keeps the syntax clean
-   Prevents naming conflicts between different naming styles
-   Makes intrinsics visually distinct from regular code
-   Reduces cognitive load when reading code

### Keywords

Reserved keywords cannot be used as identifiers:

```
class   trait   module  use     pub     mut     require
if      else    for     while   do      return  throw
try     on      fx      initially  finally  override
true    false   null    is      as      in
```

---

## Syntax Grammar (BNF)

This section provides the complete syntactic grammar for Kira using Backus-Naur Form (BNF) notation with extensions.

### Notation Conventions

- `::=` means "is defined as"
- `|` represents alternatives (OR)
- `()` groups elements
- `[]` indicates optional elements (zero or one occurrence)
- `{}` indicates repetition (zero or more occurrences)
- `*` suffix indicates zero or more repetitions
- `+` suffix indicates one or more repetitions
- `?` suffix indicates optional (zero or one)
- Terminal symbols are in quotes `'keyword'` or described in CAPS

### Lexical Grammar

```bnf
Identifier          ::= Letter (Letter | Digit)*
ConstantIdentifier  ::= UpperLetter (UpperLetter | Digit | '_')*
IntrinsicIdentifier ::= '@' (Letter | '_') (Letter | Digit | '_')*
Letter              ::= [a-zA-Z]
UpperLetter         ::= [A-Z]
LowerLetter         ::= [a-z]
Digit               ::= [0-9]
IntegerLiteral      ::= Digit+
FloatLiteral        ::= Digit+ '.' Digit+ [Exponent]
Exponent            ::= ('e' | 'E') ['+' | '-'] Digit+
StringLiteral       ::= '"' StringChar* '"'
StringChar          ::= EscapeSequence | [^"\\\n]
EscapeSequence      ::= '\\' ('n' | 't' | 'r' | '\\' | '"' | '$')
BooleanLiteral      ::= 'true' | 'false'
NullLiteral         ::= 'null'
LineComment         ::= '//' [^\n]* '\n'
Operator            ::= '+' | '-' | '*' | '/' | '%'
                      | '==' | '!=' | '<' | '>' | '<=' | '>='
                      | '&&' | '||' | '!'
                      | '=' | '..' | '.' | '::' | '->'
Delimiter           ::= '(' | ')' | '{' | '}' | '[' | ']'
                      | ',' | ':' | ';'
```

### Program Structure

```bnf
Program             ::= ModuleDeclaration ImportDeclaration* TopLevelDeclaration*
ModuleDeclaration   ::= 'module' StringLiteral
ImportDeclaration   ::= 'use' StringLiteral
TopLevelDeclaration ::= ClassDeclaration
                      | TraitDeclaration
                      | FunctionDeclaration
                      | ConstantDeclaration
```

### Declarations

```bnf
ClassDeclaration    ::= [Visibility] 'class' TypeIdentifier [TypeParameters]
                        [':' TypeIdentifier] ClassBody
ClassBody           ::= '{' ClassMember* '}'
ClassMember         ::= FieldDeclaration
                      | MethodDeclaration
                      | InitiallyBlock
                      | FinallyBlock
FieldDeclaration    ::= ['require'] [Visibility] ['mut'] Identifier ':' Type ['=' Expression]
MethodDeclaration   ::= ['override'] [Visibility] ['mut'] 'fx' Identifier
                        '(' ParameterList? ')' ':' Type (Block | ';')
InitiallyBlock      ::= 'initially' Block
FinallyBlock        ::= 'finally' Block
TraitDeclaration    ::= [Visibility] 'trait' TypeIdentifier [TypeParameters] TraitBody
TraitBody           ::= '{' TraitMember* '}'
TraitMember         ::= MethodSignature
                      | MethodDeclaration
MethodSignature     ::= [Visibility] 'fx' Identifier '(' ParameterList? ')' ':' Type
FunctionDeclaration ::= [Visibility] 'fx' Identifier [TypeParameters]
                        '(' ParameterList? ')' ':' Type Block
ParameterList       ::= Parameter (',' Parameter)*
Parameter           ::= Identifier ':' Type ['=' Expression]
ConstantDeclaration ::= ConstantIdentifier ':' Type '=' Expression
Visibility          ::= 'pub'
TypeParameters      ::= '<' TypeParameter (',' TypeParameter)* '>'
TypeParameter       ::= TypeIdentifier [':' TypeBound]
TypeBound           ::= TypeIdentifier
```

### Types

```bnf
Type                ::= PrimitiveType
                      | ReferenceType
                      | GenericType
                      | FunctionType
                      | TupleType
PrimitiveType       ::= 'Int8' | 'Int16' | 'Int32' | 'Int64'
                      | 'Float32' | 'Float64'
                      | 'Bool' | 'Void' | 'Never'
ReferenceType       ::= TypeIdentifier
GenericType         ::= TypeIdentifier '<' TypeArgumentList '>'
TypeArgumentList    ::= Type (',' Type)*
FunctionType        ::= 'Fx' '<' TupleType ',' Type '>'
TupleType           ::= 'Tuple' [IntegerLiteral] ['<' TypeArgumentList '>']
TypeIdentifier      ::= UpperLetter (Letter | Digit)*
```

### Statements

```bnf
Statement           ::= VariableDeclaration
                      | Assignment
                      | ExpressionStatement
                      | IfStatement
                      | WhileStatement
                      | DoWhileStatement
                      | ForStatement
                      | ReturnStatement
                      | ThrowStatement
                      | TryStatement
                      | Block
                      | BreakStatement
                      | ContinueStatement
VariableDeclaration ::= ['mut'] Identifier ':' Type '=' Expression
Assignment          ::= Identifier '=' Expression
ExpressionStatement ::= Expression
Block               ::= '{' Statement* '}'
IfStatement         ::= 'if' Expression Block
                        ('else' 'if' Expression Block)*
                        ['else' Block]
WhileStatement      ::= 'while' Expression Block
DoWhileStatement    ::= 'do' Block 'while' Expression
ForStatement        ::= 'for' Identifier ':' Type 'in' Expression Block
ReturnStatement     ::= 'return' [Expression]
BreakStatement      ::= 'break'
ContinueStatement   ::= 'continue'
ThrowStatement      ::= 'throw' Expression
TryStatement        ::= 'try' Block 'on' Identifier ':' Type Block
```

### Expressions

```bnf
Expression          ::= AssignmentExpression
AssignmentExpression ::= LogicalOrExpression ['=' AssignmentExpression]
LogicalOrExpression ::= LogicalAndExpression ('||' LogicalAndExpression)*
LogicalAndExpression ::= EqualityExpression ('&&' EqualityExpression)*
EqualityExpression  ::= RelationalExpression (('==' | '!=') RelationalExpression)*
RelationalExpression ::= RangeExpression (('<' | '>' | '<=' | '>=') RangeExpression)*
RangeExpression     ::= AdditiveExpression ('..' AdditiveExpression)*
AdditiveExpression  ::= MultiplicativeExpression (('+' | '-') MultiplicativeExpression)*
MultiplicativeExpression ::= UnaryExpression (('*' | '/' | '%') UnaryExpression)*
UnaryExpression     ::= ('-' | '!' | '+') UnaryExpression
                      | PostfixExpression
PostfixExpression   ::= PrimaryExpression (
                          '.' Identifier
                        | '[' Expression ']'
                        | '(' ArgumentList? ')'
                        | 'as' Type
                        | 'as?' Type
                        | 'is' Type
                        )*
PrimaryExpression   ::= Identifier
                      | Literal
                      | LambdaExpression
                      | ParenthesizedExpression
                      | ObjectCreation
                      | IntrinsicCall
Literal             ::= IntegerLiteral
                      | FloatLiteral
                      | StringLiteral
                      | BooleanLiteral
                      | NullLiteral
                      | ArrayLiteral
ArrayLiteral        ::= '[' [Expression (',' Expression)*] ']'
LambdaExpression    ::= 'fx' '(' ParameterList? ')' ':' Type Block
ParenthesizedExpression ::= '(' Expression ')'
ObjectCreation      ::= TypeIdentifier [GenericArguments]
                        '{' [ConstructorArguments] '}'
ConstructorArguments ::= ConstructorArgument (',' ConstructorArgument)*
ConstructorArgument ::= [Identifier '='] Expression
ArgumentList        ::= Expression (',' Expression)*
GenericArguments    ::= '<' TypeArgumentList '>'
IntrinsicCall       ::= IntrinsicIdentifier '(' ArgumentList? ')'
```

### String Interpolation

```bnf
StringLiteral       ::= '"' StringContent* '"'
StringContent       ::= TextContent
                      | Interpolation
TextContent         ::= (EscapeSequence | [^"\\$])+
Interpolation       ::= '${' Expression '}'
```

### Grammar Notes

**Precedence and Associativity:**

Expression precedence from lowest to highest:
1. Assignment (`=`) - Right associative
2. Logical OR (`||`) - Left associative
3. Logical AND (`&&`) - Left associative
4. Equality (`==`, `!=`) - Left associative
5. Relational (`<`, `>`, `<=`, `>=`) - Left associative
6. Range (`..`) - Left associative
7. Additive (`+`, `-`) - Left associative
8. Multiplicative (`*`, `/`, `%`) - Left associative
9. Unary (`-`, `!`, `+`) - Right associative
10. Postfix (`.`, `[]`, `()`, `as`, `is`) - Left associative

**Statement Termination:**

- Statements are terminated by newlines when syntactically complete
- Semicolons can be used to separate multiple statements on one line
- Line continuation is implicit when parentheses, brackets, or braces are unclosed
- Line continuation is implicit when a line ends with a binary operator

**Whitespace:**

- Whitespace (spaces, tabs, newlines) separates tokens
- Indentation is not syntactically significant
- Multiple consecutive whitespace characters are treated as one separator

**Comments:**

- Single-line comments start with `//` and extend to end of line
- Comments are treated as whitespace by the parser
- No multi-line or nested comments

---

## Expressions and Operators

Kira employs a nominal type system where types are identified by their declared names rather than structural compatibility. All values in Kira are objects with reference semantics, eliminating the need for explicit boxing operations.

### Primitive Types

**Integer Types:**

-   `Int8`: 8-bit signed integer (range: -128 to 127)
-   `Int16`: 16-bit signed integer (range: -32,768 to 32,767)
-   `Int32`: 32-bit signed integer (range: -2,147,483,648 to 2,147,483,647)
-   `Int64`: 64-bit signed integer (range: -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807)

**Floating Point Types:**

-   `Float32`: IEEE 754 single-precision floating point
-   `Float64`: IEEE 754 double-precision floating point

**Boolean Type:**

-   `Bool`: Logical type with values `true` or `false`, stored as 8-bit value

**Special Types:**

-   `Void`: Represents absence of a return value in function signatures
-   `Never`: Indicates a function that never returns (diverging execution)

**Type Availability Guarantee:**

All primitive types listed above are **guaranteed to exist and be accessible** across all compilation targets and platforms. However, the actual runtime representation may vary:

-   The compiler may map larger types to smaller types on platforms with limited support
-   Type identity is not guaranteed: `@type_of(Int32)` may equal `@type_of(Int16)` on some targets
-   Semantic behavior (overflow, precision) matches the declared type even if internally aliased
-   Programs should not rely on distinct runtime representations for type checking

**Examples:**

```kira
x: Int32 = 100
y: Int16 = 50

xType: Type = @type_of(x)
yType: Type = @type_of(y)

if xType == yType {
    @trace("Int32 and Int16 may be same type on this target")
}
```

On platforms where `Int32` and `Int16` are aliased, both types are still available in source code, but runtime type comparison may show them as equal. The compiler ensures overflow and range semantics match the declared type regardless of the internal representation.

### Reference Types

Kira provides several core reference types for advanced memory management and type manipulation:

**`Any`**

The universal supertype of all classes. Used for dynamic typing scenarios where the specific type is determined at runtime.

```kira
value: Any = 42
value = "string"  // Valid reassignment
```

**`Type`**

Runtime representation of type information. Used for reflection and metaprogramming.

```kira
t: Type = @type_of(Int32)
```

**`Ref<A>`**

Explicit reference wrapper for type `A`. Provides shared ownership semantics with reference counting.

```kira
value: Ref<Int32> = Ref<Int32> { 42 }
```

**`Weak<T>`**

Non-owning reference that does not increment the reference count. Used to break circular reference cycles. Accessing a weak reference requires upgrading to a strong reference, which may fail if the object has been deallocated.

```kira
weak: Weak<MyClass> = Weak<MyClass> { strongRef }
maybeValue: Maybe<MyClass> = weak.upgrade()
```

**`Unsafe<T>`**

Raw pointer-like reference without reference counting or lifetime tracking. Intended only for performance-critical FFI code or unsafe optimizations. Using `Unsafe<T>` bypasses Kira's safety guarantees.

```kira
ptr: Unsafe<Int32> = Unsafe<Int32> { rawPointer }
```

### Collection Types

**`Str`**

Immutable Unicode string type. Strings are value types that support interpolation and common string operations.

```kira
greeting: Str = "Hello, ${name}!"
```

**`Arr<A>`**

Fixed-size immutable array. Size is determined at initialization and cannot change. The compiler may optimize array operations using static size information.

```kira
numbers: Arr<Int32> = [1, 2, 3, 4, 5]
```

**`List<A>`**

Dynamic resizable array implemented in the standard library. Supports insertion, deletion, and mutation operations.

```kira
mut items: List<Int32> = mut []
items.add(42)
```

**`Map<K, V>`**

Hash-based associative container mapping keys of type `K` to values of type `V`.

```kira
mut scores: Map<Str, Int32> = {}
scores.put("Alice", 95)
```

**`Set<A>`**

Unordered collection of unique values.

```kira
mut uniqueIds: Set<Int32> = {}
uniqueIds.add(42)
```

---

## Variables and Declarations

### Variable Declaration

All variables in Kira must be explicitly typed. Type inference is not supported to maintain code clarity and enable better tooling support.

**Syntax:**

```kira
identifier: Type = expression
```

**Examples:**

```kira
name: Str = "Alice"
age: Int32 = 30
isActive: Bool = true
```

### Immutability by Default

Variables are immutable by default. This prevents accidental mutations and encourages functional programming patterns. To create a mutable variable, use the `mut` modifier:

```kira
value: Int32 = 10
value = 20  // Error: cannot assign to immutable variable

mut counter: Int32 = 0
counter = counter + 1  // Valid
```

### Constant Expressions

Constants are compile-time evaluated expressions that must be initialized with literal values or other constant expressions:

```kira
MAX_SIZE: Int32 = 1000
PI: Float64 = 3.141592653589793
```

---

## Expressions and Operators

Expressions in Kira evaluate to values and can be composed using various operators. The language provides a comprehensive set of operators with well-defined precedence and associativity rules.

### Operator Precedence and Associativity

Operators are listed from highest to lowest precedence:

| Precedence | Operator            | Description                      | Associativity |
| ---------- | ------------------- | -------------------------------- | ------------- | ---------- | ------------- |
| 1          | `.` `[]` `()`       | Member access, indexing, call    | Left to right |
| 2          | `!` `-` `+` (unary) | Logical NOT, unary minus/plus    | Right to left |
| 3          | `*` `/` `%`         | Multiplication, division, modulo | Left to right |
| 4          | `+` `-`             | Addition, subtraction            | Left to right |
| 5          | `..`                | Range operator                   | Left to right |
| 6          | `<` `>` `<=` `>=`   | Relational comparison            | Left to right |
| 7          | `==` `!=`           | Equality comparison              | Left to right |
| 8          | `&&`                | Logical AND                      | Left to right |
| 9          | `                   |                                  | `             | Logical OR | Left to right |
| 10         | `=`                 | Assignment                       | Right to left |

### Arithmetic Operators

**Binary Arithmetic:**

```kira
a: Int32 = 10 + 5   // Addition: 15
b: Int32 = 10 - 5   // Subtraction: 5
c: Int32 = 10 * 5   // Multiplication: 50
d: Int32 = 10 / 5   // Division: 2
e: Int32 = 10 % 3   // Modulo: 1
```

**Unary Arithmetic:**

```kira
x: Int32 = -10      // Unary minus (negation)
y: Int32 = +10      // Unary plus (no operation)
```

**Type Rules:**

-   Operands must have compatible numeric types
-   No implicit type coercion; explicit casting required for mixed-type operations
-   Integer division truncates toward zero
-   Division by zero results in runtime error

```kira
// Error: type mismatch
result: Int32 = 10 + 5.5  // Cannot mix Int32 and Float32

// Correct: explicit conversion
result: Float32 = Float32(10) + 5.5
```

### Comparison Operators

**Relational Operators:**

```kira
a: Bool = 5 < 10     // Less than
b: Bool = 5 > 10     // Greater than
c: Bool = 5 <= 10    // Less than or equal
d: Bool = 5 >= 10    // Greater than or equal
```

**Equality Operators:**

```kira
x: Bool = 10 == 10   // Equality
y: Bool = 10 != 5    // Inequality
```

**Type Rules:**

-   Operands must have comparable types
-   Reference types compare by reference identity (same object)
-   Value types compare by value equality
-   Returns `Bool` type

```kira
// Value comparison
x: Int32 = 10
y: Int32 = 10
result: Bool = x == y  // true

// Reference comparison
obj1: MyClass = MyClass { }
obj2: MyClass = MyClass { }
same: Bool = obj1 == obj2  // false (different objects)
same: Bool = obj1 == obj1  // true (same object)
```

### Logical Operators

**Binary Logical:**

```kira
x: Bool = true && false
y: Bool = true || false
z: Bool = !true
```

**Short-Circuit Evaluation:**

Logical operators use short-circuit evaluation:

```kira
result: Bool = false && expensiveCheck()
result: Bool = true || expensiveCheck()
```

**Type Rules:**

-   Operands must be of type `Bool`
-   No implicit conversion from other types to `Bool`
-   Always returns `Bool`

```kira
result: Bool = 5 && 10

result: Bool = (5 > 0) && (10 > 0)
```

### Range Operator

The range operator `..` creates a range value representing a sequence of integers:

```kira
range: Range = 0..10
```

**Usage:**

```kira
for i: Int32 in 0..10 {
}

start: Int32 = 1
end: Int32 = 100
for i: Int32 in start..end {
}
```

**Semantics:**

-   Lower bound is inclusive
-   Upper bound is exclusive
-   Both bounds must be of integer types
-   Ranges are lazy and do not allocate arrays

### Assignment Operator

Simple assignment uses `=`:

```kira
mut x: Int32 = 10
x = 20
```

**Rules:**

-   Left-hand side must be a mutable variable
-   Right-hand side must have compatible type
-   Assignment is a statement, not an expression (no chained assignment)

```kira
value: Int32 = 10
value = 20

mut a: Int32 = 0
mut b: Int32 = 0
a = b = 10
```

**Compound Assignment Operators:**

Compound assignment operators are not supported. Use explicit operations:

```kira
x += 10

x = x + 10
```

### Member Access Operator

The dot operator `.` accesses members of objects:

```kira
object.field
object.method()
```

**Method Chaining:**

```kira
result: Str = "hello"
    .toUpperCase()
    .trim()
    .substring(0, 3)
```

### Indexing Operator

The bracket operator `[]` accesses elements by index:

```kira
array: Arr<Int32> = [1, 2, 3, 4, 5]
element: Int32 = array[0]

map: Map<Str, Int32> = Map<Str, Int32> {}
map["key"] = 42
value: Int32 = map["key"]
```

**Index Bounds:**

-   Array indices are zero-based
-   Out-of-bounds access results in runtime error
-   Negative indices are not supported

### Function Call Operator

Parentheses `()` invoke functions:

```kira
result: Int32 = add(5, 10)
time: Int64 = getCurrentTime()
list.add(42)
```

**Parentheses are mandatory:**

```kira
result: Int32 = add
```

### String Interpolation

Kira provides string interpolation similar to JavaScript template literals, but uses regular double quotes `"` instead of backticks. Interpolation is performed using the `${}` syntax exclusively.

**Basic Syntax:**

```kira
name: Str = "Alice"
age: Int32 = 30
message: Str = "Hello, ${name}! You are ${age} years old."
// Result: "Hello, Alice! You are 30 years old."
```

**Key Differences from JavaScript:**

-   Uses regular double quotes `"..."` (not backticks `` `...` ``)
-   Must use `${}` syntax (no shorthand like `$variable`)
-   No multiline string literals (use `\n` for newlines)

**Mandatory `${}` Syntax:**

Unlike some languages that allow `$variable` as shorthand, Kira **always requires braces** around interpolated expressions:

```kira
name: Str = "Alice"
message: Str = "Hello, ${name}!"

message: Str = "Hello, $name!"
```

This makes the interpolation boundaries explicit and unambiguous, preventing parsing errors with adjacent text.

**Expression Interpolation:**

Any valid expression can be interpolated, not just variables:

```kira
x: Int32 = 10
y: Int32 = 20
result: Str = "Sum: ${x + y}, Product: ${x * y}"

time: Int64 = getCurrentTime()
message: Str = "Current time: ${time}"

text: Str = "hello"
output: Str = "Uppercase: ${text.toUpperCase()}"

age: Int32 = 25
status: Str = "You are ${age >= 18} years old"
```

**Complex Expression Examples:**

```kira
total: Int32 = 100
tax: Float32 = 0.15
message: Str = "Total with tax: ${Float32(total) * (1.0 + tax)}"

value: Float32 = 3.14159
precise: Str = "Pi is approximately ${value}"

user: User = getUser()
info: Str = "User ${user.name} has ${user.posts.size()} posts"

x: Int32 = 5
y: Int32 = 3
equation: Str = "${x} + ${y} = ${x + y}"
```

**String Conversion:**

Values are automatically converted to strings using their `toString()` method or compiler-provided conversion:

```kira
// Primitive types
number: Int32 = 42
float: Float32 = 3.14
bool: Bool = true
output: Str = "Values: ${number}, ${float}, ${bool}"
// Result: "Values: 42, 3.14, true"

// Objects with toString()
pub class Point {
    require pub x: Int32
    require pub y: Int32

    pub fx toString(): Str {
        return "(${x}, ${y})"
    }
}

point: Point = Point { 10, 20 }
message: Str = "Point is at ${point}"
// Result: "Point is at (10, 20)"
```

**Escaping:**

To include literal `${` in a string without interpolation, use backslash escape:

```kira
// Escaped interpolation syntax
literal: Str = "The syntax is \${expression}"
// Result: "The syntax is ${expression}"

// Multiple escapes
template: Str = "Use \${name} and \${age} as placeholders"
// Result: "Use ${name} and ${age} as placeholders"

// Mixing escaped and real interpolation
name: Str = "Alice"
example: Str = "Replace \${user} with ${name}"
// Result: "Replace ${user} with Alice"
```

**Interpolation Rules and Restrictions:**

1. **Braces are mandatory:** `${expression}` is the only valid syntax
2. **No nested interpolation:** Cannot interpolate strings inside interpolated expressions
3. **Evaluation order:** Left to right, expressions evaluated when string is constructed
4. **Type safety:** Expression must have a string representation (implement `toString()` or have built-in conversion)
5. **No side effects:** Interpolated expressions are evaluated exactly once during string construction

```kira
// Valid: simple expression
x: Int32 = 10
msg: Str = "Value: ${x}"

// Valid: complex expression
msg: Str = "Result: ${compute(x, y, z)}"

// Invalid: nested interpolation not supported
inner: Str = "world"
outer: Str = "Hello, ${"${inner}"}"  // Error: nested interpolation

// Invalid: missing braces
msg: Str = "Hello, $name"  // Error: treated as literal "$name"
```

**Multiline Strings:**

Since Kira uses regular quotes (not backticks), multiline strings require explicit newline escapes:

```kira
// Single line with \n
message: Str = "Line 1\nLine 2\nLine 3"

// Interpolation with newlines
name: Str = "Alice"
greeting: Str = "Hello, ${name}!\nWelcome to Kira."

// Concatenation alternative for readability
longMessage: Str = "First part " +
    "second part " +
    "third part with ${variable}"
```

**Performance Considerations:**

String interpolation is resolved at runtime:

-   Each interpolation allocates a new string
-   For repeated concatenation in loops, prefer `StringBuilder`
-   Compile-time constant interpolation may be optimized by the compiler

```kira
// Runtime interpolation (multiple allocations)
for i: Int32 in 0..1000 {
    message: Str = "Iteration ${i}"  // Creates new string each time
}

// Better: use StringBuilder for loops
mut builder: StringBuilder = StringBuilder {}
for i: Int32 in 0..1000 {
    builder.append("Iteration ")
    builder.append(i)
    builder.append("\n")
}
result: Str = builder.toString()
```

**Comparison with Other Languages:**

| Language   | Syntax          | Quotes    | Shorthand      |
| ---------- | --------------- | --------- | -------------- |
| JavaScript | `` `${expr}` `` | Backticks | No             |
| Python     | `f"{expr}"`     | Any       | No             |
| Kotlin     | `"${expr}"`     | Double    | `$variable` ok |
| Kira       | `"${expr}"`     | Double    | **Never**      |

Kira's design choice to require braces for all interpolations provides:
- Explicit visual boundary between text and code
- Unambiguous parsing in all contexts
- Consistency across all interpolation sites
- Prevention of accidental literal text interpretation

### Type Casting

Explicit type conversion uses constructor-like syntax:

```kira
// Numeric conversions
x: Int32 = 10
y: Float32 = Float32(x)
z: Int64 = Int64(x)

// String conversion
number: Int32 = 42
text: Str = Str(number)
```

**Type Checking:**

The `is` operator checks if a value is of a specific type:

```kira
value: Any = 42
if value is Int32 {
    @trace("value is an integer")
}
```

**Type Assertion:**

The `as` operator casts a value to a specific type:

```kira
value: Any = 42
number: Int32 = value as Int32  // Throws if not Int32
```

**Safe Casting:**

For nullable results, use `as?`:

```kira
value: Any = "not a number"
maybeNumber: Maybe<Int32> = value as? Int32  // Returns null if cast fails
```

### Expression Statements

Most expressions can be used as statements:

```kira
// Function call as statement
@trace("message")

// Assignment as statement
mut x: Int32 = 10
x = 20

// Method call as statement
list.clear()
```

**Expression-Bodied Members:**

Single-expression functions can omit braces and return keyword:

```kira
// Future feature (not yet implemented)
fx double(x: Int32): Int32 = x * 2
```

---

## Control Flow

Control flow statements in Kira determine the execution path of programs through conditional branching and iteration constructs.

### Syntax Rules for Control Flow

**Parentheses:**

Kira does not require parentheses around control flow conditions. This makes the syntax cleaner and more readable while maintaining clarity.

**Braces:**

Block braces `{}` are mandatory for all control flow bodies, even for single-statement blocks. This prevents common errors and maintains consistency.

**Grammar Overview:**

```
IfStatement       ::= 'if' Expression Block ('else' 'if' Expression Block)* ('else' Block)?
WhileStatement    ::= 'while' Expression Block
DoWhileStatement  ::= 'do' Block 'while' Expression
ForStatement      ::= 'for' Identifier ':' Type 'in' Expression Block
```

### Conditional Statements

**If-Else Statements**

**Syntax:**

```kira
if condition {
    // executed when condition is true
} else if alternativeCondition {
    // executed when alternativeCondition is true
} else {
    // executed when all conditions are false
}
```

**Key Points:**

-   No parentheses required around conditions (but allowed if desired for clarity)
-   Braces are mandatory for all branches
-   Condition expression must evaluate to type `Bool`
-   No implicit truthiness conversion for non-boolean types
-   `else if` can be chained indefinitely
-   Final `else` clause is optional

**Examples:**

```kira
// Without parentheses (preferred)
if x > 10 {
    @trace("x is greater than 10")
}

// With parentheses (allowed but not idiomatic)
if (x > 10) {
    @trace("x is greater than 10")
}

// Complex conditions
if x > 0 && y < 100 {
    @trace("Condition met")
}

// Chained conditions
if score >= 90 {
    grade: Str = "A"
} else if score >= 80 {
    grade: Str = "B"
} else if score >= 70 {
    grade: Str = "C"
} else {
    grade: Str = "F"
}
```

**Single-Expression Bodies:**

Even single expressions require braces:

```kira
// Correct
if condition {
    doSomething()
}

// Error: braces required
if condition
    doSomething()  // Syntax error
```

### Expression Context

If expressions can be used as expressions when all branches return values:

```kira
result: Str = if x > 0 {
    "positive"
} else {
    "non-positive"
}
```

**Pattern Matching** (Future Feature)

Pattern matching on enums and types is planned for a future version:

```kira
// Future syntax (not yet implemented)
match value {
    0 => @trace("zero")
    1 => @trace("one")
    _ => @trace("other")
}
```

### Loops

**While Loop**

Executes the body repeatedly while the condition remains true. The condition is evaluated before each iteration.

**Syntax:**

```kira
while condition {
    // loop body
}
```

**Examples:**

```kira
// Without parentheses (preferred)
mut i: Int32 = 0
while i < 10 {
    @trace(i)
    i = i + 1
}

// With parentheses (allowed)
while (i < 10) {
    @trace(i)
    i = i + 1
}

// Infinite loop
while true {
    // runs forever
}
```

**Do-While Loop**

Executes the body at least once, then continues while the condition remains true. The condition is evaluated after each iteration.

**Syntax:**

```kira
do {
    // loop body (guaranteed to execute at least once)
} while condition
```

**Examples:**

```kira
// Without parentheses (preferred)
mut attempts: Int32 = 0
do {
    attempts = attempts + 1
    tryOperation()
} while attempts < 3

// With parentheses (allowed)
do {
    processItem()
} while (hasMore)
```

**Key Difference from While:**

The do-while loop guarantees at least one execution of the body, regardless of the initial condition value.

```kira
mut flag: Bool = false

// Body never executes
while flag {
    @trace("not printed")
}

// Body executes once
do {
    @trace("printed once")
} while flag
```

**For Loop**

Iterates over a range or collection. The range operator `..` creates an exclusive upper bound range.

**Syntax:**

```kira
for identifier: Type in expression {
    // loop body
}
```

**Range Iteration:**

```kira
// Range from 0 to 9 (inclusive start, exclusive end)
for i: Int32 in 0..10 {
    @trace(i)  // prints 0 through 9
}

// Descending ranges (future feature)
for i: Int32 in 10..0 step -1 {
    @trace(i)
}
```

**Collection Iteration:**

```kira
items: List<Str> = mut ["apple", "banana", "cherry"]
for item: Str in items {
    @trace(item)
}
```

**Type Annotation:**

The loop variable must have an explicit type annotation. Type inference is not supported for loop variables to maintain clarity.

```kira
// Correct
for i: Int32 in 0..10 {
    // i is Int32
}

// Error: type annotation required
for i in 0..10 {  // Syntax error
    // type must be specified
}
```

**Iteration Protocol:**

Collections must implement the `Iterable<T>` trait to be used in for loops:

```kira
pub trait Iterable<T> {
    fx iterator(): Iterator<T>
}

pub trait Iterator<T> {
    fx hasNext(): Bool
    fx next(): T
}
```

### Control Flow Keywords

-   `break`: Immediately exits the innermost enclosing loop
-   `continue`: Skips the remainder of the current iteration and proceeds to the next iteration
-   `return`: Exits the current function, optionally with a return value

**Examples:**

```kira
// Break statement
for i: Int32 in 0..100 {
    if i == 50 {
        break  // Exit loop when i reaches 50
    }
    @trace(i)
}

// Continue statement
for i: Int32 in 0..10 {
    if i % 2 == 0 {
        continue  // Skip even numbers
    }
    @trace(i)  // Only prints odd numbers
}

// Return from function
fx findFirst(items: List<Int32>, target: Int32): Maybe<Int32> {
    for item: Int32 in items {
        if item == target {
            return item  // Early return
        }
    }
    return null  // Not found
}
```

**Labeled Breaks and Continues** (Future Feature)

Labeled loop control for breaking out of nested loops is planned for a future version.

---

## Functions

Functions in Kira are first-class values that can be passed as arguments, returned from other functions, and assigned to variables. The language supports both named function declarations and anonymous function literals.

### Function Declaration Syntax

**Grammar:**

```
FunctionDeclaration ::= 'fx' Identifier '(' ParameterList? ')' ':' Type Block
ParameterList       ::= Parameter (',' Parameter)*
Parameter           ::= Identifier ':' Type ('=' Expression)?
Block               ::= '{' Statement* '}'
```

**Basic Syntax:**

```kira
fx functionName(param1: Type1, param2: Type2): ReturnType {
    // function body
    return value
}
```

**Key Syntax Rules:**

-   Function declarations use the `fx` keyword
-   Parameter list is enclosed in parentheses (required even for zero parameters)
-   Each parameter must have an explicit type annotation
-   Return type is specified after a colon following the parameter list
-   Function body is enclosed in braces (mandatory)
-   Functions returning non-Void must have a `return` statement on all code paths

**Examples:**

```kira
// Simple function
fx add(a: Int32, b: Int32): Int32 {
    return a + b
}

// No parameters
fx getCurrentTime(): Int64 {
    return @systemTime()
}

// Multiple statements
fx calculateTax(amount: Float32, rate: Float32): Float32 {
    taxAmount: Float32 = amount * rate
    return taxAmount
}
```

### Parameter Passing

**Positional Parameters:**

Arguments are matched to parameters by position:

```kira
fx divide(numerator: Int32, denominator: Int32): Float32 {
    return numerator / denominator
}

result: Float32 = divide(10, 3)  // numerator=10, denominator=3
```

**Named Parameters:**

Arguments can be explicitly named at the call site for clarity:

```kira
result: Float32 = divide(numerator: 10, denominator: 3)
```

Named and positional arguments can be mixed, but named arguments cannot precede positional ones:

```kira
// Valid
result: Float32 = divide(10, denominator: 3)

// Error: named argument before positional
result: Float32 = divide(numerator: 10, 3)  // Syntax error
```

### Default Parameters

Parameters can have default values, making them optional at the call site:

**Syntax:**

```kira
fx functionName(required: Type, optional: Type = defaultValue): ReturnType {
    // implementation
}
```

**Examples:**

```kira
fx greet(name: Str, greeting: Str = "Hello"): Str {
    return "${greeting}, ${name}!"
}

// Use default greeting
message1: Str = greet("Alice")  // "Hello, Alice!"

// Override default
message2: Str = greet("Bob", "Hi")  // "Hi, Bob!"

// Named argument for default parameter
message3: Str = greet("Charlie", greeting: "Hey")  // "Hey, Charlie!"
```

**Rules for Default Parameters:**

-   Default parameters must appear after required parameters
-   Default values must be compile-time constant expressions
-   Callers can skip default parameters or override them explicitly

```kira
// Valid: defaults after required
fx configure(host: Str, port: Int32 = 8080, ssl: Bool = true): Void { }

// Invalid: required after default
fx invalid(port: Int32 = 8080, host: Str): Void { }  // Error
```

### Function Types

Functions have explicit types that can be used for variables, parameters, and return types.

**Function Type Syntax:**

```kira
Fx<ParameterTuple, ReturnType>
```

Where `ParameterTuple` is a tuple type representing the parameter list.

**Examples:**

```kira
// Function taking two Int32s and returning Bool
comparator: Fx<Tuple2<Int32, Int32>, Bool> = fx(a: Int32, b: Int32): Bool {
    return a > b
}

// Function taking no parameters and returning Str
generator: Fx<Tuple0, Str> = fx(): Str {
    return "generated"
}

// Assigning named function to variable
adder: Fx<Tuple2<Int32, Int32>, Int32> = add
```

### Anonymous Functions (Lambdas)

Anonymous functions are function literals without a declared name:

**Syntax:**

```kira
fx(parameters): ReturnType {
    // body
}
```

**Examples:**

```kira
// Assigned to variable
double: Fx<Tuple1<Int32>, Int32> = fx(x: Int32): Int32 {
    return x * 2
}

// Passed as argument
items: List<Int32> = mut [1, 2, 3, 4, 5]
filtered: List<Int32> = items.filter(fx(x: Int32): Bool {
    return x > 2
})

// Single expression (return implicit)
squared: List<Int32> = items.map(fx(x: Int32): Int32 {
    return x * x
})
```

### Higher-Order Functions

Functions can accept other functions as parameters and return functions:

**Functions as Parameters:**

```kira
fx applyOperation(a: Int32, b: Int32, op: Fx<Tuple2<Int32, Int32>, Int32>): Int32 {
    return op(a, b)
}

result: Int32 = applyOperation(5, 3, fx(x: Int32, y: Int32): Int32 {
    return x + y
})
```

**Functions as Return Values:**

```kira
fx makeMultiplier(factor: Int32): Fx<Tuple1<Int32>, Int32> {
    return fx(x: Int32): Int32 {
        return x * factor
    }
}

double: Fx<Tuple1<Int32>, Int32> = makeMultiplier(2)
result: Int32 = double(5)  // result = 10
```

**Function Composition:**

```kira
fx compose<A, B, C>(
    f: Fx<Tuple1<B>, C>,
    g: Fx<Tuple1<A>, B>
): Fx<Tuple1<A>, C> {
    return fx(x: A): C {
        return f(g(x))
    }
}
```

### Void and Never Returns

**Void Functions:**

Functions that perform side effects without returning a meaningful value use `Void`:

```kira
fx logMessage(message: Str): Void {
    @trace(message)
}

// Void functions can omit return statement
fx printHeader(): Void {
    @trace("=== Header ===")
    // implicit return
}

// Explicit return with no value
fx earlyExit(condition: Bool): Void {
    if condition {
        return  // early exit
    }
    doWork()
}
```

**Never Functions:**

Functions that never return normally (infinite loops, program termination, always throw) use `Never`:

```kira
fx runForever(): Never {
    while true {
        processEvents()
    }
}

fx abort(message: Str): Never {
    @trace("Fatal error: ${message}")
    throw message
    // No code after throw in Never function
}
```

**Usage in Control Flow:**

```kira
fx getValue(useDefault: Bool): Int32 {
    if useDefault {
        return 42
    } else {
        abort("No default available")  // Never returns
    }
    // No unreachable code error
}
```

### Recursion

Kira supports recursive function calls:

```kira
fx factorial(n: Int32): Int32 {
    if n <= 1 {
        return 1
    }
    return n * factorial(n - 1)
}

// Tail recursion
fx factorialTail(n: Int32, accumulator: Int32 = 1): Int32 {
    if n <= 1 {
        return accumulator
    }
    return factorialTail(n - 1, n * accumulator)
}
```

**Tail Call Optimization:**

The compiler may optimize tail-recursive calls to avoid stack overflow, but this is not guaranteed. For large recursion depths, prefer iteration.

### Function Overloading

Kira does not support function overloading. Each function name must be unique within its scope. Use different names or default parameters instead:

```kira
// Not allowed: overloading
fx process(value: Int32): Void { }
fx process(value: Str): Void { }  // Error: duplicate function name

// Alternative: different names
fx processInt(value: Int32): Void { }
fx processStr(value: Str): Void { }

// Alternative: generic function
fx process<T>(value: T): Void { }
```

---

## Module System

The module system in Kira provides namespacing and code organization capabilities. Each source file represents a submodule within a larger module.

### Module Declaration

Every Kira source file must begin with a module declaration specifying its fully qualified name:

```kira
module "author:project.component"
```

**Components:**

-   `author`: Organization or individual identifier
-   `project`: Project or library name
-   `component`: Submodule or source file name

**Example:**

```kira
module "acme:webserver.routing"
```

### Importing Modules

Use the `use` keyword to import submodules or entire modules:

**Import Specific Submodule:**

```kira
use "acme:webserver.routing"
```

**Import All Submodules from a Module:**

```kira
use "acme:webserver"
```

### Standard Library

The Kira standard library is accessible through the `kira` namespace:

```kira
use "kira:lib.types"     // Core types
use "kira:lib.collections"  // Collection types
use "kira:lib.io"        // Input/output utilities
```

### Visibility

Modules enforce encapsulation through visibility modifiers:

-   `pub`: Visible to external modules
-   Internal (default): Visible only within the same module

```kira
pub class PublicApi {
    // accessible from other modules
}

class InternalHelper {
    // only accessible within this module
}
```

---

## Object-Oriented Programming

Kira supports object-oriented programming through classes with single inheritance and trait composition.

### Class Declaration

**Syntax:**

```kira
pub class ClassName {
    require pub field1: Type1
    require mut field2: Type2

    pub fx method(): ReturnType {
        // method implementation
    }
}
```

**Example:**

```kira
pub class Vector2 {
    require pub mut x: Float32
    require pub mut y: Float32

    pub fx magnitude(): Float32 {
        return @sqrt(x * x + y * y)
    }

    pub fx dot(other: Vector2): Float32 {
        return (other.x * x) + (other.y * y)
    }
}
```

### Constructor

Classes use a declarative constructor syntax. The `require` keyword specifies fields that must be provided during construction:

```kira
vec: Vector2 = Vector2 { x: 3.0, y: 4.0 }
```

Fields can be initialized positionally or by name:

```kira
vec1: Vector2 = Vector2 { 3.0, 4.0 }  // positional
vec2: Vector2 = Vector2 { y: 4.0, x: 3.0 }  // named
```

### Inheritance

Classes support single inheritance using the colon syntax:

```kira
pub class Shape {
    require pub color: Str

    pub fx area(): Float32
}

pub class Circle: Shape {
    require pub radius: Float32

    override pub fx area(): Float32 {
        return 3.14159 * radius * radius
    }
}
```

**Inheritance Rules:**

-   Only single inheritance is permitted
-   Abstract methods must be overridden in concrete subclasses
-   Use `override` keyword when overriding methods
-   Final classes cannot be inherited (use `final` modifier)

### Abstract Classes

Abstract classes contain unimplemented methods that must be provided by subclasses or during construction:

```kira
pub class Animal {
    pub fx makeSound(): Void  // abstract method

    pub fx sleep(): Void {
        @trace("Sleeping...")
    }
}

pub class Dog: Animal {
    override pub fx makeSound(): Void {
        @trace("Woof!")
    }
}
```

### Mutable Methods

Methods that modify instance state must be marked with `mut`:

```kira
pub class Counter {
    require mut count: Int32

    pub mut fx increment(): Void {
        count = count + 1
    }
}
```

### Access Modifiers

Class members support two visibility levels:

**Public (`pub`):**

Accessible from outside the class and module.

```kira
pub class Example {
    pub field: Int32 = 0

    pub fx publicMethod(): Void {
        // accessible everywhere
    }
}
```

**Internal (default):**

Accessible only within the class and its methods.

```kira
pub class Example {
    privateField: Int32 = 0  // internal field

    fx internalMethod(): Void {
        // accessible only within the class
    }
}
```

### Initializers and Finalizers

**Initializer Block (`initially`):**

Executed immediately after object construction for validation or setup:

```kira
pub class Person {
    require pub age: Int32

    initially {
        if age < 0 {
            throw "Age cannot be negative"
        }
    }
}
```

**Finalizer Block (`finally`):**

Executed before object deallocation for cleanup of external resources:

```kira
pub class FileHandle {
    require handle: Int32

    finally {
        closeFile(handle)
    }
}
```

---

## Generics and Type Parameters

Generics enable type-safe code reuse through parametric polymorphism. Type parameters are specified in angle brackets and can have constraints.

### Generic Classes

```kira
pub class Box<T> {
    require pub value: T

    pub fx unwrap(): T {
        return value
    }
}

intBox: Box<Int32> = Box<Int32> { 42 }
strBox: Box<Str> = Box<Str> { "hello" }
```

### Multiple Type Parameters

```kira
pub class Pair<A, B> {
    require pub first: A
    require pub second: B

    pub fx swap(): Pair<B, A> {
        return Pair<B, A> { second, first }
    }
}
```

### Generic Functions

```kira
fx identity<T>(value: T): T {
    return value
}

result: Int32 = identity<Int32>(42)
```

### Type Parameter Constraints

Constrain type parameters to ensure they implement specific traits:

```kira
fx sort<T: Comparable>(items: List<T>): List<T> {
    // implementation using T's comparison methods
}
```

### Variadic Type Parameters and Tuples

Kira uses tuple types to represent variable-length type parameter lists:

**Tuple Interface:**

```kira
pub class Tuple {
    pub fx size(): Int32
    pub fx @get(index: Int32): Any
}
```

**Concrete Tuple Types:**

```kira
pub class Tuple2<A, B>: Tuple {
    require pub first: A
    require pub second: B

    override pub fx size(): Int32 {
        return 2
    }

    override pub fx @get(index: Int32): Any {
        if index == 0 {
            return first
        } else if index == 1 {
            return second
        }
        throw "Index out of bounds"
    }
}
```

**Usage:**

```kira
coordinates: Tuple2<Float32, Float32> = Tuple2<Float32, Float32> { 3.0, 4.0 }
x: Float32 = coordinates.first
y: Float32 = coordinates.second
```

### Function Types with Tuples

Function types use tuples to represent parameter lists:

```kira
callback: Fx<Tuple2<Int32, Str>, Bool> = fx(num: Int32, text: Str): Bool {
    return num > 0
}
```

---

## Memory Model

Kira employs Automatic Reference Counting (ARC) for deterministic memory management. Every object maintains a reference count that tracks the number of strong references to it.

### Reference Counting Semantics

**Ownership:**

When a variable holds a reference to an object, it owns that reference and increments the reference count.

```kira
obj1: MyClass = MyClass {}  // refCount = 1
obj2: MyClass = obj1  // refCount = 2
```

**Deallocation:**

When the reference count reaches zero, the object is immediately deallocated.

```kira
{
    temp: MyClass = MyClass {}  // refCount = 1
}  // temp goes out of scope, refCount = 0, object deallocated
```

### Weak References

Weak references do not increment the reference count, preventing retain cycles:

```kira
pub class Node {
    require pub mut value: Int32
    require pub mut next: Maybe<Weak<Node>>
}

first: Node = Node { 1, null }
second: Node = Node { 2, Weak<Node> { first } }
```

Accessing a weak reference requires upgrading:

```kira
weak: Weak<MyClass> = Weak<MyClass> { strong }
maybeStrong: Maybe<MyClass> = weak.upgrade()

if maybeStrong.isSome() {
    obj: MyClass = maybeStrong.value
    // use obj
}
```

### Circular Reference Prevention

Circular references occur when two objects reference each other, preventing deallocation:

```kira
// Problematic code without weak references
pub class Parent {
    require pub mut child: Maybe<Child>
}

pub class Child {
    require pub mut parent: Maybe<Parent>  // Creates cycle
}
```

**Solution using weak references:**

```kira
pub class Parent {
    require pub mut child: Maybe<Child>
}

pub class Child {
    require pub mut parent: Maybe<Weak<Parent>>  // Breaks cycle
}
```

### Unsafe References

`Unsafe<T>` provides raw pointer semantics without reference counting. Use only when necessary for performance or FFI:

```kira
ptr: Unsafe<Int32> = Unsafe<Int32> { someObject }
// ptr does not affect reference count
// Accessing ptr after deallocation is undefined behavior
```

---

## Error Handling

### Null Safety

Kira enforces null safety through the `Maybe<T>` type. Values of non-Maybe types cannot be null:

```kira
value: Int32 = null  // Compile error

maybeValue: Maybe<Int32> = null  // Valid
```

### Maybe Type

**Construction:**

```kira
present: Maybe<Int32> = 42  // Auto-boxing
absent: Maybe<Int32> = null
```

**Checking for Null:**

```kira
if value.isNull() {
    @trace("Value is absent")
}

if value.isSome() {
    @trace("Value is present")
}
```

**Safe Unwrapping:**

```kira
result: Int32 = maybeValue.unwrapOr(0)  // Returns 0 if null
```

**Forced Unwrapping:**

```kira
value: Int32 = maybeValue.value  // Throws if null
```

### Exception Handling

**Throwing Exceptions:**

```kira
if invalidInput {
    throw "Invalid input provided"
}
```

**Catching Exceptions:**

```kira
try {
    riskyOperation()
} on error: Str {
    @trace("Error occurred: ${error}")
}
```

### Result Type

For recoverable errors, prefer the `Result<T, E>` type over exceptions:

```kira
fx divide(a: Int32, b: Int32): Result<Int32, Str> {
    if b == 0 {
        return Result.error("Division by zero")
    }
    return Result.success(a / b)
}

result: Result<Int32, Str> = divide(10, 2)
if result.isSuccess() {
    value: Int32 = result.unwrap()
    @trace("Result: ${value}")
} else {
    error: Str = result.error()
    @trace("Error: ${error}")
}
```

---

## Standard Library

### Traits

Traits enable code sharing across unrelated classes without inheritance. They are similar to interfaces but are resolved at compile time.

**Defining a Trait:**

```kira
pub trait Drawable {
    fx draw(): Void

    pub fx describe(): Str {
        return "A drawable object"
    }
}
```

**Implementing a Trait:**

```kira
pub class Circle: Drawable {
    require pub radius: Float32

    override fx draw(): Void {
        @trace("Drawing circle with radius ${radius}")
    }
}
```

**Multiple Traits:**

A class can implement multiple traits:

```kira
pub class Button: Drawable, Clickable {
    override fx draw(): Void {
        @trace("Drawing button")
    }

    override fx onClick(): Void {
        @trace("Button clicked")
    }
}
```

### Compile-Time Intrinsics

Intrinsics are compiler-integrated functions that execute during compilation or provide special runtime behavior. They are prefixed with `@`.

**Common Intrinsics:**

| Intrinsic           | Description                                 | Example                                     |
| ------------------- | ------------------------------------------- | ------------------------------------------- |
| `@trace(...)`       | Outputs values to debug console             | `@trace("Debug message")`                   |
| `@type_of(...)`     | Returns runtime type representation         | `t: Type = @type_of(Int32)`                 |
| `@global`           | Makes declaration available at global scope | `@global value: Int32 = 42`                 |
| `@json_decode(...)` | Parses JSON at compile time                 | `data: Map<Str, Any> = @json_decode("...")` |
| `@magic`            | Marks compiler-intrinsic types              | `@magic class Tuple { }`                    |

**Usage Example:**

```kira
@trace("Starting application")

config: Map<Str, Any> = @json_decode(`{
    "version": "1.0",
    "debug": true
}`)

versionType: Type = @type_of(config["version"])
```

### Standard Library Organization

The standard library is organized into the following modules:

-   `kira:lib.types`: Core types and primitives
-   `kira:lib.collections`: Collection types (List, Map, Set)
-   `kira:lib.io`: Input/output operations
-   `kira:lib.math`: Mathematical functions
-   `kira:lib.string`: String manipulation utilities
-   `kira:lib.async`: Asynchronous programming primitives

**Importing Standard Library:**

```kira
use "kira:lib.types"
use "kira:lib.collections"
```

---

## Appendix

### Reserved Future Keywords

The following keywords are reserved for potential future features:

```
async   await   match   enum    namespace   unsafe
static  const   macro   yield   defer
```

### Operator Precedence

| Priority | Operator             | Description                    | Associativity |
| -------- | -------------------- | ------------------------------ | ------------- | ---------- | ------------- |
| 1        | `.`, `[]`, `()`      | Member access, indexing, calls | Left to right |
| 2        | `!`, `-`, `+`        | Logical NOT, unary minus/plus  | Right to left |
| 3        | `*`, `/`, `%`        | Multiplication, division, mod  | Left to right |
| 4        | `+`, `-`             | Addition, subtraction          | Left to right |
| 5        | `<`, `>`, `<=`, `>=` | Comparison                     | Left to right |
| 6        | `==`, `!=`           | Equality                       | Left to right |
| 7        | `&&`                 | Logical AND                    | Left to right |
| 8        | `                    |                                | `             | Logical OR | Left to right |
| 9        | `=`                  | Assignment                     | Right to left |

### Naming Conventions Summary

-   **Variables and functions:** camelCase (`myVariable`, `calculateTotal`)
-   **Types and classes:** PascalCase (`Int32`, `HttpClient`, `UserAccount`)
-   **Constants:** UPPER_SNAKE_CASE (`MAX_SIZE`, `DEFAULT_TIMEOUT`)
-   **Module names:** snake_case for file names (`http_client.kira`)
-   **Intrinsics:** snake_case with `@` prefix (`@json_decode`, `@type_of`)

---

**End of Specification**
