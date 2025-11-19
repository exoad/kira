# Kira Language Specification
```kira
pub class User {
    require pub name: Str
    require pub email: Str
    pub role: Str = "user"
    pub active: Bool = true
}

user1: User = User { name = "Alice", email = "alice@example.com" }
user2: User = User { name = "Bob", email = "bob@example.com", role = "admin" }
user3: User = User {
    name = "Charlie",
    email = "charlie@example.com",
    role = "moderator",
    active = false
}
```
@_trace_("Hello World!")
```

**FizzBuzz Implementation**

```kira
for i in 0..101 {
   if i % 15 == 0 {
      @_trace_("FizzBuzz")
   } else if i % 3 == 0 {
      @_trace_("Fizz")
   } else if i % 5 == 0 {
      @_trace_("Buzz")
   } else {
      @_trace_(i)
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
@_trace_("Hello World!")
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

    alias UserId as Int64
    alias Callback as Fx<Tuple1<Str>, Void>

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
@_trace_("Debug output")
@_type_of_(myValue)
@_json_decode_("{}")
@system_time()
@_trace_
@get
@trace_value
@compute_hash
```

**Intrinsic Limitations:**

-   Intrinsics are **compiler-provided and cannot be defined by users**
-   Users cannot create custom intrinsics - only the compiler can define them
-   The complete list of available intrinsics is **currently in development** and compiler-specific
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
| Intrinsics       | @snake_case      | `^@[a-z_][a-z0-9_]*$`  | `@_trace_`, `@_type_of_`      |
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

Reserved keywords cannot be used as identifiers. The following keywords are reserved by the language:

`alias`, `as`, `class`, `do`, `else`, `for`, `fx`, `finally`, `if`, `in`, `initially`, `is`, `module`, `mut`, `on`, `override`, `pub`, `require`, `return`, `this`, `throw`, `trait`, `try`, `use`, `variant`, `while`


---

## Syntax Grammar (BNF)

This section provides the complete syntactic grammar for Kira using Backus-Naur Form (BNF) notation with extensions.

### Notation Conventions

-   `::=` means "is defined as"
-   `|` represents alternatives (OR)
-   `()` groups elements
-   `[]` indicates optional elements (zero or one occurrence)
-   `{}` indicates repetition (zero or more occurrences)
-   `*` suffix indicates zero or more repetitions
-   `+` suffix indicates one or more repetitions
-   `?` suffix indicates optional (zero or one)
-   Terminal symbols are in quotes `'keyword'` or described in CAPS

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
                      | EnumDeclaration
                      | TypeAliasDeclaration
```

### Declarations

```bnf
ClassDeclaration    ::= [Visibility] 'class' TypeIdentifier [TypeParameters]
                        [':' TypeIdentifier (',' TypeIdentifier)*] ClassBody
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
EnumDeclaration     ::= [Visibility] 'enum' TypeIdentifier ':' PrimitiveType '{' EnumVariant (',' EnumVariant)* '}'
EnumVariant         ::= ConstantIdentifier '=' Literal
TypeAliasDeclaration ::= [Visibility] 'alias' TypeIdentifier [TypeParameters] 'as' Type
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

-   Statements are terminated by newlines when syntactically complete
-   Semicolons can be used to separate multiple statements on one line
-   Line continuation is implicit when parentheses, brackets, or braces are unclosed
-   Line continuation is implicit when a line ends with a binary operator

**Whitespace:**

-   Whitespace (spaces, tabs, newlines) separates tokens
-   Indentation is not syntactically significant
-   Multiple consecutive whitespace characters are treated as one separator

**Comments:**

-   Single-line comments start with `//` and extend to end of line
-   Comments are treated as whitespace by the parser
-   No multi-line or nested comments

---

## Standard Types

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

**Bool Sentinels:**

-   The `Bool` type exposes two sentinel literals: `true` and `false`. These are language-level sentinel values declared and used similarly to the `null` literal (that is, they are built-in keywords representing the two possible boolean values). Use `true` and `false` directly in expressions and declarations (for example, `flag: Bool = true`).


**Special Types:**

-   `Void`: Represents absence of a return value in function signatures
-   `Never`: Indicates a function that never returns (diverging execution)

**Type Availability Guarantee:**

All primitive types listed above are **guaranteed to exist and be accessible** across all compilation targets and platforms. However, the actual runtime representation may vary:

-   The compiler may map larger types to smaller types on platforms with limited support
-   Type identity is not guaranteed: `@_type_of_(Int32)` may equal `@_type_of_(Int16)` on some targets
-   Semantic behavior (overflow, precision) matches the declared type even if internally aliased
-   Programs should not rely on distinct runtime representations for type checking

**Examples:**

```kira
x: Int32 = 100
y: Int16 = 50

xType: Type = @_type_of_(x)
yType: Type = @_type_of_(y)

if xType == yType {
    @_trace_("Int32 and Int16 may be same type on this target")
}
```

On platforms where `Int32` and `Int16` are aliased, both types are still available in source code, but runtime type comparison may show them as equal. The compiler ensures overflow and range semantics match the declared type regardless of the internal representation.

### Type Aliases

Type aliases allow creating alternative names for existing types. They are inlined at compile time.

**Syntax:**

```kira
[Visibility] alias AliasName as ExistingType
```

**Examples:**

```kira
pub alias PublicUserId as Int64
alias UserId as Int64
alias Callback as Fx<Tuple1<Str>, Void>
alias StringMap as Map<Str, Str>
alias Point as Tuple2<Float32, Float32>

userId: UserId = 12345
callback: Callback = fx(msg: Str): Void {
    @_trace_(msg)
}
config: StringMap = Map<Str, Str> {}
position: Point = Tuple2<Float32, Float32> { 10.0, 20.0 }
```

**Semantics:**

-   Type aliases are purely compile-time constructs
-   No runtime overhead; aliases are replaced with their underlying types
-   Aliases and their underlying types are completely interchangeable
-   Helps document code intent and reduce repetition of complex generic types

**Visibility:**

-   Aliases may be declared with the `pub` visibility modifier at module scope. When prefixed with `pub`, the alias is exported from the module and can be used by other modules in the same way as `pub` functions and variables.
-   `pub alias` follows the same module-level export rules as `pub` declarations for values and functions.

```kira
// In module 'mylib.kira'
pub alias PublicUserId as Int64

// In another module that uses 'mylib.kira'
userId: PublicUserId = 10
```

```kira
alias Matrix as Arr<Arr<Float64>>

mat: Matrix = [[1.0, 2.0], [3.0, 4.0]]
```

**Generic Type Aliases:**

Type aliases can include generic parameters:

```kira
alias Result<T> as Maybe<T>
alias Pair<A, B> as Tuple2<A, B>

outcome: Result<Int32> = 42
coords: Pair<Float32, Float32> = Tuple2<Float32, Float32> { 5.0, 10.0 }
```

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
t: Type = @_type_of_(Int32)
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

**Array Literals:**

Square brackets `[]` are the **only** syntax allowed for creating array literals in Kira. This provides consistency and clarity in the language.

```kira
numbers: Arr<Int32> = [1, 2, 3, 4, 5]
empty: Arr<Str> = []
mixed: Arr<Float32> = [1.0, 2.5, 3.14]
```

**Array Type Inference:**

Array element types must be explicitly declared. The compiler does not infer array types:

```kira
values: Arr<Int32> = [1, 2, 3]

arr := [1, 2, 3]
```

**Array Size:**

Array size is fixed at creation and cannot be modified. For dynamic arrays, use `List<A>`:

```kira
fixed: Arr<Int32> = [1, 2, 3]
mut dynamic: List<Int32> = List<Int32> {}
dynamic.add(1)
dynamic.add(2)
```

**`List<A>`**

Dynamic resizable array implemented in the standard library. Supports insertion, deletion, and mutation operations.

```kira
mut items: List<Int32> = List<Int32> { [ 1, 3, 4 ] }
items.add(42)
```

**`Map<K, V>`**

Hash-based associative container mapping keys of type `K` to values of type `V`.

**Initialization:**

Maps do not have literal syntax like arrays. They must be initialized using constructor syntax:

```kira
mut scores: Map<Str, Int32> = Map<Str, Int32> {}
```

**Adding/Accessing Values:**

Maps support the `[]` indexing operator and `[]=` assignment operator for convenient value access and modification:

```kira
mut config: Map<Str, Int32> = Map<Str, Int32> {}

config["timeout"] = 30
config["maxRetries"] = 5
config["bufferSize"] = 4096

timeout: Int32 = config["timeout"]
```

This is similar to Java's Map interface but with operator overloading for cleaner syntax. The indexing operators are syntactic sugar for `get()` and `put()` methods.

**`Set<A>`**

Unordered collection of unique values.

```kira
mut uniqueIds: Set<Int32> = Set<Int32> { }
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
| ---------- | ------------------- | -------------------------------- | ------------- |
| 1          | `.` `[]` `()`       | Member access, indexing, call    | Left to right |
| 2          | `!` `-` `+` (unary) | Logical NOT, unary minus/plus    | Right to left |
| 3          | `*` `/` `%`         | Multiplication, division, modulo | Left to right |
| 4          | `+` `-`             | Addition, subtraction            | Left to right |
| 5          | `..`                | Range operator                   | Left to right |
| 6          | `<` `>` `<=` `>=`   | Relational comparison            | Left to right |
| 7          | `==` `!=`           | Equality comparison              | Left to right |
| 8          | `&&`                | Logical AND                      | Left to right |
| 9          | `||`                | Logical OR                       | Left to right |
| 10         | `=`                 | Assignment                       | Right to left |

### Arithmetic Operators

**Binary Arithmetic:**

```kira
a: Int32 = 10 + 5
b: Int32 = 10 - 5
c: Int32 = 10 * 5
d: Int32 = 10 / 5
e: Int32 = 10 % 3
```

**Unary Arithmetic:**

```kira
x: Int32 = -10
y: Int32 = +10
```

**Type Rules:**

-   Operands must have compatible numeric types
-   No implicit type coercion; explicit casting required for mixed-type operations
-   Integer division truncates toward zero
-   Division by zero results in runtime error

### Operator Overloading

Kira supports operator overloading through intrinsic markers. Classes can define methods with specific intrinsic names that map to operators, allowing custom types to work with standard operators.

**Operator Intrinsic Mapping:**

| Operator | Intrinsic Name | Signature Example                          |
|----------|----------------|--------------------------------------------|
| `+`      | `@_op_add_`    | `fx @_op_add_(other: T): T`                |
| `-`      | `@_op_sub_`    | `fx @_op_sub_(other: T): T`                |
| `*`      | `@_op_mul_`    | `fx @_op_mul_(other: T): T`                |
| `/`      | `@_op_div_`    | `fx @_op_div_(other: T): T`                |
| `%`      | `@_op_mod_`    | `fx @_op_mod_(other: T): T`                |
| `==`     | `@_op_eq_`     | `fx @_op_eq_(other: T): Bool`              |
| `!=`     | `@_op_neq_`    | `fx @_op_neq_(other: T): Bool`             |
| `<`      | `@_op_lt_`     | `fx @_op_lt_(other: T): Bool`              |
| `>`      | `@_op_gt_`     | `fx @_op_gt_(other: T): Bool`              |
| `<=`     | `@_op_lte_`    | `fx @_op_lte_(other: T): Bool`             |
| `>=`     | `@_op_gte_`    | `fx @_op_gte_(other: T): Bool`             |
| `-` (un) | `@_op_neg_`    | `fx @_op_neg_(): T`                        |
| `[]`     | `@_op_get_`    | `fx @_op_get_(get: Int32): T`              |
| `[]=`    | `@_op_set_`    | `fx @_op_set_(index: Int32, val: T): Void` |

> Note: Function Signatures can vary, but using a different signature means you must explicitly invoke the intrinsic as a function instead.

**Example:**

```kira
pub class Vector2 {
    require pub x: Float32
    require pub y: Float32

    pub fx @_op_add_(other: Vector2): Vector2 {
        return Vector2 { x + other.x, y + other.y }
    }

    pub fx @_op_mul_(scalar: Float32): Vector2 {
        return Vector2 { x * scalar, y * scalar }
    }

    pub fx @_op_eq_(other: Vector2): Bool {
        return x == other.x && y == other.y
    }

    pub fx @_op_neg_(): Vector2 {
        return Vector2 { -x, -y }
    }
}

v1: Vector2 = Vector2 { 1.0, 2.0 }
v2: Vector2 = Vector2 { 3.0, 4.0 }

sum: Vector2 = v1 + v2
scaled: Vector2 = v1 * 2.0
negated: Vector2 = -v1
equal: Bool = v1 == v2
```

**Notes:**

-   Intrinsic markers are just symbol placeholders for the parser
-   Function signatures can differ (return types, parameter types can vary)
-   Not all operators need to be overloaded
-   The compiler transforms operator usage into method calls at compile time

```kira
// Error: type mismatch
result: Int32 = 10 + 5.5  // Cannot mix Int32 and Float32

// Correct: explicit conversion
result: Float32 = 10.0 + 5.5
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
-   **Ranges allocate an array** containing all values in the range

**Memory Allocation:**

When a range is created, it allocates an array containing an integer values from the start (inclusive) to end (
exclusive):

```kira
range: Range = 0..10
```

This creates an array `[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]` in memory.

**Performance Implications:**

```kira
for i: Int32 in 0..1000000 {
}
```

This allocates an array with 1,000,000 elements. For large ranges, consider the memory cost. Alternative approaches for large iterations may be needed depending on the use case.

**Range Type:**

The `Range` type implements the `Iterable<Int32>` interface and provides array-like access:

```kira
range: Range = 5..10
length: Int32 = range.length()
element: Int32 = range[0]
```

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

message: Str = "Hello, $name"
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
message: Str = "Total with tax: ${(total as Float32) * (1.0 + tax)}"

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

-   Explicit visual boundary between text and code
-   Unambiguous parsing in all contexts
-   Consistency across all interpolation sites
-   Prevention of accidental literal text interpretation

### Type Casting

Explicit type conversion uses constructor-like syntax:

```kira
// Numeric conversions
x: Int32 = 10
y: Float32 = x as Float32
z: Int64 = x as Int64

// String conversion
number: Int32 = 42
text: Str = number as Str // automatically calls the intrinsic marker function to_str
```

**Type Checking:**

The `is` operator checks if a value is of a specific type:

```kira
value: Any = 42
if value is Int32 {
    @_trace_("value is an integer")
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
@_trace_("message")

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
    @_trace_("x is greater than 10")
}

// With parentheses (allowed but not idiomatic)
if (x > 10) {
    @_trace_("x is greater than 10")
}

// Complex conditions
if x > 0 && y < 100 {
    @_trace_("Condition met")
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
    0 => @_trace_("zero")
    1 => @_trace_("one")
    _ => @_trace_("other")
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
    @_trace_(i)
    i = i + 1
}

// With parentheses (allowed)
while (i < 10) {
    @_trace_(i)
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
    @_trace_("not printed")
}

// Body executes once
do {
    @_trace_("printed once")
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
    @_trace_(i)  // prints 0 through 9
}

// Descending ranges (future feature)
for i: Int32 in 10..0 step -1 {
    @_trace_(i)
}
```

**Collection Iteration:**

```kira
items: List<Str> = mut ["apple", "banana", "cherry"]
for item: Str in items {
    @_trace_(item)
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
    @_trace_(i)
}

// Continue statement
for i: Int32 in 0..10 {
    if i % 2 == 0 {
        continue  // Skip even numbers
    }
    @_trace_(i)  // Only prints odd numbers
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

Arguments can be explicitly named at the call site for clarity. Named parameters use `=` (not `:`) to separate the parameter name from its value:

```kira
result: Float32 = divide(numerator = 10, denominator = 3)
```

Named and positional arguments can be mixed, but named arguments cannot precede positional ones:

```kira
result: Float32 = divide(10, denominator = 3)

result: Float32 = divide(numerator = 10, 3)
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

message1: Str = greet("Alice")
message2: Str = greet("Bob", "Hi")
message3: Str = greet("Charlie", greeting = "Hey")
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
}
```

**Examples:**

```kira
double: Fx<Tuple1<Int32>, Int32> = fx(x: Int32): Int32 {
    return x * 2
}

items: List<Int32> = [1, 2, 3, 4, 5]
filtered: List<Int32> = items.filter(fx(x: Int32): Bool {
    return x > 2
})

squared: List<Int32> = items.map(fx(x: Int32): Int32 {
    return x * x
})
```

**Closure Capture Semantics:**

Lambdas capture variables from their enclosing scope **by value** and all captures are **immutable**:

```kira
multiplier: Int32 = 10

createMultiplier: Fx<Tuple1<Int32>, Int32> = fx (x: Int32): Int32 {
    return x * multiplier
}

multiplier = 20
result: Int32 = createMultiplier(5)
```

**Capture Rules:**

-   Variables are captured by value at the time of lambda creation
-   Captured variables cannot be mutated inside the lambda
-   Changes to the original variable after lambda creation do not affect captured value
-   To share mutable state, use reference types like `Ref<T>`

**Recursion Limitation:**

Lambda/closure expressions **cannot be recursive** because there is no way to reference the nameless function from within itself. If you need recursion, use a named function instead:

```kira
// Cannot write recursive lambda (no self-reference mechanism)
factorial: Fx<Tuple1<Int32>, Int32> = fx(n: Int32): Int32 {
    return n <= 1 ? 1 : n * ??? // No way to reference self
}

// ✓ Use named function instead
pub fx factorial(n: Int32): Int32 {
    return n <= 1 ? 1 : n * factorial(n - 1)
}
```

```kira
counter: Ref<Int32> = Ref<Int32> { 0 }

increment: Fx<Tuple0, Void> = fx(): Void {
    counter.value = counter.value + 1
}

increment()
increment()
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
    @_trace_(message)
}

// Void functions can omit return statement
fx printHeader(): Void {
    @_trace_("=== Header ===")
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
    @_trace_("Fatal error: ${message}")
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

### Function Reference

Since functions are treated as first class citizens, there is no special operator like `::` that is used to get the direct value of a function under a certain container.

Instead directly reference it as is or use the member access operator `.`

```kira
fx callFx(func: Fx<Tuple0, Void>): Void {
    func()
}

fx supplier(): Void {
    @_trace_("Supplier Function!")
}

callFx(supplier)
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

Note: The compiler discovers the standard library from your project manifest (see KIM below). Declare a dependency with
`registry = "kira"` (optionally with a local `path`) in `kira.toml` to make the standard library available. If no
dependency is declared, the compiler falls back to scanning a local `./kira/` folder.

---

## Project Manifest (KIM)

KIM (KIra Manifest) is the manifest format and tooling used by the Kira compiler to locate sources, resolve the standard
library, and apply build options. The manifest file is named `kira.toml` and must reside at the project root.

Only `kira.toml` is recognized (there is no `kim.toml` or `ki.toml`).

### Goals

- Standardize how projects declare workspace source files and entry points
- Describe build options (target, output) in a reproducible way
- Declare dependencies, including the Kira standard library

### File name

- Required: `kira.toml` at the project root

### Minimal example

```toml
version = "1"

[package]
name = "hello_kira"
version = "0.1.0"
authors = ["you@example.com"]

[workspace]
src = ["src", "main.kira"]

[build]
outDir = "build"
target = "c"
debug = true
emitIR = false

[dependencies.kira_std]
registry = "kira"
```

### Sections

- version
    - Manifest format version. Current: `"1"`.

- [package]
    - name: Required package name (string)
    - version: Semantic version (string), default `"1.0.0"`
    - authors: List of authors (string array)
    - description: Optional description (string)

- [workspace]
    - src: List of source paths. Each entry can be a file or directory. Directories are scanned recursively for `.kira`
      files.
    - entry: Optional entry-point file (string), relative to project root.

- [build]
    - outDir: Output directory for artifacts
    - target: "c" (or "native") or "neko"
    - debug: Enable debug builds (bool)
    - emitIR: Emit intermediate representation (bool)

- [dependencies]
    - Arbitrary keys are dependency names (e.g., `kira_std`)
    - Each dependency supports:
        - path: Optional local path to the dependency root
        - version: Optional version (for registry-backed dependencies)
        - registry: Registry name. For the Kira standard library, use `"kira"`.

### Standard library resolution

When the compiler runs in a project with `kira.toml`:

1. It loads the manifest and validates it.
2. It discovers the standard library by scanning dependencies with `registry = "kira"`:
    - If a dependency also has `path`, all `.kira` files under that path (relative to project root) are included as
      standard library sources.
    - Otherwise, it attempts to scan a local `./kira/` directory for `.kira` files.
3. If no manifest or matching dependency is found, the compiler falls back to scanning `./kira/`.

All discovered stdlib files are merged with the workspace sources before parsing.

### Workspace source discovery

- Each entry in `workspace.src` can be a file or directory
- Directories are scanned recursively and all `.kira` files are added
- If `workspace.entry` exists, the compiler prefers it as the program entry point

### CLI integration

- Running the compiler inside a directory with `kira.toml` uses that manifest automatically.
- Alternatively, you may pass a project directory via a `--project` flag.

Examples (PowerShell):

```powershell
./gradlew run --args "--project=."
./gradlew run --args "--project=path/to/project"
```

### Validation

During validation, the compiler reports issues such as:

- Missing `[package]` section
- Blank `package.name`
- `workspace.entry` points to a non-existent file
- Unsupported `version` of the manifest format

Diagnostics are printed with file/line context when available.

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
vec: Vector2 = Vector2 { x = 3.0, y = 4.0 }
```

Fields can be initialized positionally or by name. When using named initialization, use `=` (not `:`) to assign values:

```kira
vec1: Vector2 = Vector2 { 3.0, 4.0 }
vec2: Vector2 = Vector2 { y = 4.0, x = 3.0 }
```

**Constructor Design:**

The entire field body of a class serves as its constructor. There is no separate constructor method or overloading:

-   All fields declared in the class body define the constructor signature
-   Fields marked with `require` must be provided during instantiation
-   Fields with default values (`field: Type = value`) are optional
-   **Constructor overloading is not supported** - each class has exactly one constructor shape

**Default Field Value Evaluation:**

When default field values are evaluated is **currently undefined behavior** and depends on the transpilation/compilation target:

-   Some targets may evaluate defaults at class definition time
-   Others may evaluate them at instance construction time
-   The exact timing is implementation-specific

Programs should not rely on specific evaluation timing for default values. Avoid side effects in default value expressions.

```kira
pub class User {
    require pub name: Str
    require pub email: Str
    pub role: Str = "user"
    pub active: Bool = true
}
```

    pub active: Bool = true

}

user1: User = User { name = "Alice", email = "alice@example.com" }
user2: User = User { name = "Bob", email = "bob@example.com", role = "admin" }
user3: User = User {
name = "Charlie",
email = "charlie@example.com",
role = "moderator",
active = false
}

````

**Method Implementation During Instantiation:**

Classes can accept method implementations at instantiation time by supplying lambda functions. This eliminates the need for abstract classes since functions are first-class citizens:

```kira
pub class Handler {
    pub fx process(data: Str): Void
}

handler: Handler = Handler {
    process = fx(data: Str): Void {
        @_trace_("Processing: ${data}")
    }
}

handler.process("test data")
````

Since functions can be provided at runtime, there is **no concept of abstract classes** in Kira. Any class with
unimplemented methods can receive implementations during instantiation, allowing for flexible object construction where
behavior can be customized without requiring subclassing.

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
-   Methods can be overridden in subclasses
-   Use `override` keyword when overriding methods
-   Final classes cannot be inherited (use `final` modifier)

### Trait Implementation

Traits are implemented using the same colon syntax as inheritance. A class can inherit from one parent class and implement multiple traits by listing them in the extension parameter list:

**Syntax:**

```kira
pub class ClassName: ParentClass, Trait1, Trait2, Trait3 {
}
```

**Examples:**

```kira
pub trait Drawable {
    fx draw(): Void
}

pub trait Clickable {
    fx onClick(): Void
}

pub class Button: Drawable, Clickable {
    override fx draw(): Void {
        @_trace_("Drawing button")
    }

    override fx onClick(): Void {
        @_trace_("Button clicked")
    }
}

pub class ImageButton: Button, Serializable {
    override fx serialize(): Str {
        return "ImageButton data"
    }
}
```

**Rules:**

-   First type after `:` can be a parent class (if inheriting) or a trait
-   All subsequent types must be traits
-   Only one class inheritance allowed, but unlimited trait implementations
-   All trait methods must be implemented with `override` keyword
-   Order matters: `ClassName: ParentClass, Trait1, Trait2`

**Traits vs Inheritance - When to Use Which:**

**Use Traits when:**

-   You need to share logic across unrelated classes
-   You want to compose behavior from multiple sources
-   You're defining capabilities or interfaces (e.g., `Drawable`, `Serializable`)
-   You need multiple inheritance of behavior

**Use Inheritance when:**

-   There's a clear "is-a" relationship (e.g., `Dog` is an `Animal`)
-   You need a single inheritable container for shared state and behavior
-   You want to enforce a type hierarchy

**Key Distinction:**

Since Kira only supports **single inheritance**, you can only inherit from one class. Classes serve as inheritable containers for both data and behavior. Traits, on the other hand, allow unlimited composition of shared logic without the restriction of single inheritance.

### Trait Default Implementations

Traits **can provide default implementations** for their methods. Classes implementing the trait can use the default or override it:

```kira
pub trait Loggable {
    fx log(message: Str): Void {
        @_trace_("[LOG] ${message}")  // Default implementation
    }
}

pub class Service: Loggable {
    // Uses default log() implementation
    pub fx run(): Void {
        log("Service started")
    }
}

pub class CustomService: Loggable {
    // Overrides with custom implementation
    override fx log(message: Str): Void {
        @_trace_("[CUSTOM] ${message}")
    }
}
```

### Trait Inheritance

Traits **can inherit from other traits**, creating trait hierarchies:

```kira
pub trait Serializable {
    fx serialize(): Str
}

pub trait JsonSerializable: Serializable {
    // Inherits serialize() from Serializable
    fx toJson(): Str {
        return "{\"data\": \"" + serialize() + "\"}"  // Default implementation
    }
}

pub class User: JsonSerializable {
    require pub name: Str

    override fx serialize(): Str {
        return name
    }
    // Gets toJson() from JsonSerializable (can override if needed)
}
```

Classes implementing a derived trait must implement all methods from the entire trait hierarchy.

```kira
pub trait Loggable {
    fx log(message: Str): Void {
        @_trace_("[LOG] ${message}")
    }
}

pub trait Validatable {
    fx validate(): Bool
}

pub class Entity {
    require pub id: Int64
}

pub class User: Entity, Loggable, Validatable {
    require pub name: Str

    override fx validate(): Bool {
        return name.length() > 0
    }
}
```

### No Abstract Classes

Kira does not have a formal concept of abstract classes. Since functions are first-class citizens and can be provided at runtime during instantiation, any class with unimplemented methods can receive implementations when constructed:

```kira
pub class Processor {
    pub fx process(data: Str): Str

    pub fx preProcess(data: Str): Str {
        return data.trim()
    }
}

processor: Processor = Processor {
    process = fx(data: Str): Str {
        return data.toUpperCase()
    }
}

result: Str = processor.process("hello")
```

This design eliminates the need for abstract class declarations while providing the same flexibility. If a subclass wants to provide implementation through inheritance, it can override the method normally:

```kira
pub class UpperCaseProcessor: Processor {
    override pub fx process(data: Str): Str {
        return data.toUpperCase()
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

> Note: At this visibility, the inheritors can see this member.

```kira
pub class Example {
    pub field: Int32 = 0

    pub fx publicMethod(): Void {
    }
}
```

**Internal (default):**

Accessible only within the class and its methods.

> Note: At this visibility, the inheritors cannot see this member.

```kira
pub class Example {
    privateField: Int32 = 0

    fx internalMethod(): Void {
    }
}
```

**Why No Protected Modifier?**

In other languages like Java, the `protected` keyword is another visibility layer that allows only the members of the class and children of that class to view that field.

Kira does not utilize this layer simply because it is extra overhead and increases the learning curve. Creating a binary system where the field is either visible or not makes it not only easier, but gives more freedom to the programmer in designing their APIs.

If a member needs to be accessible to subclasses, make it `pub`. If it should be hidden, use internal visibility (default).

### Static Members

Kira does not support static (class-level) fields or methods. All members must be instance-based. For shared state or utility functions, use module-level constants and functions instead:

```kira
MAX_CONNECTIONS: Int32 = 100

fx calculateHash(data: Str): Int64 {
    return @hash(data)
}

pub class Connection {
    require id: Int32

    pub fx isValid(): Bool {
        return id < MAX_CONNECTIONS
    }
}
```

### Enumerations

Enumerations define a type with a fixed set of named constants. Kira's enum syntax follows C-style syntax but with type restrictions.

**Syntax:**

```kira
pub enum EnumName: BaseType {
    VARIANT1 = value1,
    VARIANT2 = value2,
    VARIANT3 = value3
}
```

**Allowed Base Types:**

Enums can only use the following base types, and all variants must use the same type:

-   Integer types: `Int8`, `Int16`, `Int32`, `Int64`
-   Floating point types: `Float32`, `Float64`
-   String type: `Str`

**Examples:**

```kira
pub enum Status: Int32 {
    PENDING = 0,
    ACTIVE = 1,
    COMPLETED = 2,
    FAILED = 3
}

pub enum Priority: Str {
    LOW = "low",
    MEDIUM = "medium",
    HIGH = "high",
    CRITICAL = "critical"
}

pub enum Threshold: Float32 {
    MIN = 0.0,
    MAX = 100.0,
    DEFAULT = 50.0
}
```

**Usage:**

```kira
currentStatus: Status = Status.ACTIVE

if currentStatus == Status.COMPLETED {
    @_trace_("Task completed")
}

priority: Priority = Priority.HIGH
message: Str = "Priority: ${priority}"
```

**Rules:**

-   All enum variants must be explicitly assigned values
-   All values must be of the same type as the declared base type
-   Enum values are compile-time constants
-   Enums cannot have methods or additional fields
-   Each variant name must follow UPPER_SNAKE_CASE naming convention

**Invalid Examples:**

```kira
pub enum MixedTypes: Int32 {
    VALUE1 = 1,
    VALUE2 = "string"
}

pub enum WithoutType {
    VARIANT1,
    VARIANT2
}
```

### Initializers and Finalizers

**Initializer Block (`initially`):**

Executed immediately after object construction for validation or setup. The `initially` block behaves like Kotlin's `init` block:

-   Runs after all field initialization (both required and default values)
-   Has access to all instance fields
-   Can throw exceptions to abort construction
-   **Only one `initially` block allowed per class** (compiler error if multiple)

**Execution Order:**

1. Default field values are evaluated
2. Required fields are set from constructor arguments
3. `initially` block executes

> Note: Inheritors cannot modify nor override the initializer blocks. Supplying another initializer block in a child will just mean that initializer block is ran after the parent's.

```kira
pub class Person {
    require pub age: Int32
    pub ageCategory: Str = "unknown"

    initially {
        if age < 0 {
            throw "Age cannot be negative"
        }

        ageCategory = if age < 18 {
            "minor"
        } else {
            "adult"
        }
    }
}
```

**Finalizer Block (`finally`):**

Executed before object deallocation for cleanup of external resources. This is not a destructor but a cleanup hook:

-   Runs during garbage collection or when reference count reaches zero
-   Used for releasing external resources (files, network connections, etc.)
-   **Only one `finally` block allowed per class** (compiler error if multiple)
-   Should not throw exceptions

> Note: Inheritors cannot modify nor override the finalizer blocks. Supplying another finalizer block in a child will just mean that finalizer block is ran after the parent's.

```kira
pub class FileHandle {
    require handle: Int32

    finally {
        closeFile(handle)
    }
}
```

---

## Variant Classes

Variant classes limit their inheritance pattern to only internally defined inheritors/children. In this case, the closed type serves as more of a container type rather than a type itself.

```kira
pub variant Result<A, E> {
    class Success<A> : Result<A, Void> {
        require pub A data
    }

    class Error<E> : Result<Void, E> {
        require pub E error
    }

    pub @to_string(): Str { // this is also passed to the variants
        return "Result Type"
    }
}
```

Here `Result` behaves normally like an abstract class (i.e. it cannot be instantiated); however, as compared to a normal class, the internal body allows for declaring additional classes. These classes are the only permitted predefined inheritors allowed (i.e. they must all provide an extension to the variant type).

> Specifically here, `Success` and `Error` are known as variances of `Result`.

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

**Multiple Trait Bounds:**

When a type parameter must satisfy multiple traits, use **comma-separated** syntax:

```kira
// ✓ Correct: comma-separated bounds
fx processItem<T: Comparable, Serializable>(item: T): Str {
    // T must implement both Comparable and Serializable
}

// Incorrect: not T: Comparable + Serializable
```

The type parameter `T` must implement all specified traits.

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

### Builtin Concrete Tuple Types

Kira's STL provides concrete tuple types from `Tuple0` to `Tuple9` by default to allow for ease of use. Going above 9 generic parameters is up to the developer to implement.

### Function Types with Tuples

Function types use tuples to represent parameter lists:

```kira
callback: Fx<Tuple2<Int32, Str>, Bool> = fx(num: Int32, text: Str): Bool {
    return num > 0
}
```

> Note: Although normal functions allow for supplying "named" parameters to functions, using `Tuple`s erases this feature due to the nature of how tuples behave. This is a known issue and is being triaged for implementation.

### Type Erasure and Reification

**Platform-Dependent Behavior:**

Type erasure and reification in Kira are purely dependent on the transpilation or compilation target. The behavior varies significantly:

-   **Runtime Type Information:** Not guaranteed to be available on all targets
-   **Type Identity:** May not be preserved at runtime depending on the target
-   **Generic Type Information:** May be erased or reified based on target capabilities

**Target Examples:**

```kira
box: Box<Int32> = Box<Int32> { 42 }
typeInfo: Type = @_type_of_(box)
```

| Target         | Type Info Available | Notes                               |
| -------------- | ------------------- | ----------------------------------- |
| Native (C/C++) | Partial             | Depends on compilation flags        |
| JVM            | Full                | Full reification through reflection |
| JavaScript     | Erased              | No runtime type information         |
| .NET           | Full                | Full reification through reflection |
| WebAssembly    | Erased              | Limited type information            |

**Workarounds (In Development):**

Intrinsics are being developed to allow checking and working with type information when the target supports it:

```kira
if @has_runtime_type_info() {
    genericType: Type = @get_generic_parameter(box, 0)
    @_trace_("Box contains type: ${genericType}")
}
```

**Best Practices:**

-   Do not rely on runtime type identity for generic types
-   Use type constraints and compile-time checks where possible
-   For target-specific behavior, use conditional compilation (planned feature)
-   Prefer explicit type parameters over reflection when possible

```kira
fx processValue<T>(value: Box<T>, handler: Fx<Tuple1<T>, Void>): Void {
    handler(value.unwrap())
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
ptr: Unsafe<Int32> = Unsafe<Int32> { rawPointer }
```

**Type Conversion:**

There is **no way to convert between safe and unsafe types** directly. However, you can dereference an `Unsafe<T>` pointer to get the underlying value:

```kira
ptr: Unsafe<Int32> = Unsafe<Int32> { rawPointer }
value: Int32 = @dereference(ptr)  // Get value from unsafe pointer

// Cannot convert safe to unsafe or vice versa directly
safeRef: Ref<Int32> = ptr  // Not allowed
unsafePtr: Unsafe<Int32> = safeRef  // Not allowed
```

Additionally, unsafe references only exist in certain transpilation targets that supports direct memory access such as compiling to machine code or transpiling to C/C++. Thus manipulation of the `Unsafe` type requires the usage of intrinsics:

```kira
@_trace_(ptr.@acquire_value()) // returns an Int32 representing the real memory location

array: List<Int32> = mut []

// <Type>, <Dest>, <Location>
ptr.@read_offset(@_type_of_(array), array, ptr.@aquire_value() + 10)

// Used to directly write memory information
ptr.@store_offset(@_type_of_(array), array, ptr.@acquire_value() + 10)

```

> Note: Modifying unsafe references are not yet implemented and are still in triage.

---

## Error Handling

### Null Safety

Kira enforces null safety through the `Maybe<T>` sealed type. Values of non-Maybe types cannot be null, providing compile-time guarantees against null pointer errors.

**Type Safety:**

```kira
value: Int32 = null

maybeValue: Maybe<Int32> = null
```

**Global Variant Values:**

`Maybe<T>` is a sealed type, meaning it can only have specific, predefined variants. There are no user-definable subtypes. The type has two global variant values:

-   A value is present (containing data of type `T`)
-   A value is absent (`null`)

This is similar to Rust's `Option<T>` or Kotlin's nullable types, but implemented as a sealed type hierarchy.

### Maybe Type API

The `Maybe<T>` type provides a complete API for safe nullable value handling:

**Construction:**

```kira
present: Maybe<Int32> = 42
absent: Maybe<Int32> = null
```

**Auto-Boxing:**

Non-null values are automatically boxed into `Maybe<T>`:

```kira
fx findUser(id: Int64): Maybe<User> {
    user: User = lookupUser(id)
    return user
}
```

**Checking for Presence:**

```kira
maybeValue: Maybe<Int32> = getValue()

if maybeValue.isNull() {
    @_trace_("Value is absent")
}

if maybeValue.isSome() {
    @_trace_("Value is present")
}
```

**Safe Unwrapping:**

The `unwrapOr` method provides a default value if the `Maybe` is null:

```kira
maybeValue: Maybe<Int32> = null
result: Int32 = maybeValue.unwrapOr(0)

maybeStr: Maybe<Str> = getName()
name: Str = maybeStr.unwrapOr("Unknown")
```

**Forced Unwrapping:**

The `.value` property throws a runtime error if the `Maybe` is null:

```kira
maybeValue: Maybe<Int32> = 42
value: Int32 = maybeValue.value

nullValue: Maybe<Int32> = null
willThrow: Int32 = nullValue.value
```

**Complete API:**

```kira
pub sealed class Maybe<T> {
    pub fx isNull(): Bool
    pub fx isSome(): Bool
    pub fx unwrapOr(default: T): T
    pub value: T
}
```

**Usage Examples:**

```kira
fx divide(a: Int32, b: Int32): Maybe<Float32> {
    if b == 0 {
        return null
    }
    return (a as Float32) / (b as Float32)
}

result: Maybe<Float32> = divide(10, 2)

if result.isSome() {
    @_trace_("Result: ${result.value}")
} else {
    @_trace_("Division by zero")
}

safeResult: Float32 = divide(10, 0).unwrapOr(0.0)
```

### Exception Handling

**Throwable Types:**

Kira only allows **`Str` types** to be thrown. Custom exception classes are not supported:

```kira
// ✓ Correct: throwing Str
if invalidInput {
    throw "Invalid input provided"
}

// Incorrect: cannot throw custom exception types
pub class MyException {
    require pub message: Str
}
throw MyException { message = "Error" }  // Not allowed
```

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
    @_trace_("Error occurred: ${error}")
}
```

### Result Type

For recoverable errors, prefer the `Result<T, E>` type over exceptions. This provides an explicit error handling mechanism in function signatures:

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
    @_trace_("Result: ${value}")
} else {
    error: Str = result.error()
    @_trace_("Error: ${error}")
}
```

---

## Standard Library

The Kira standard library is accessible through the `kira` namespace:

```kira
use "kira:lib.types"     // Core types
use "kira:lib.collections"  // Collection types
use "kira:lib.io"        // Input/output utilities
```

Note: The compiler discovers the standard library from your project manifest (see KIM below). Declare a dependency with
`registry = "kira"` (optionally with a local `path`) in `kira.toml` to make the standard library available. If no
dependency is declared, the compiler falls back to scanning a local `./kira/` folder.

Kira's standard library provides essential types, collection classes, and utility functions to complement the core
language features. It is organized into several modules:

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

## Project Manifest (KIM)

KIM (KIra Manifest) is the manifest format and tooling used by the Kira compiler to locate sources, resolve the standard
library, and apply build options. The manifest file is named `kira.toml` and must reside at the project root.

Only `kira.toml` is recognized (there is no `kim.toml` or `ki.toml`).

### Goals

- Standardize how projects declare workspace source files and entry points
- Describe build options (target, output) in a reproducible way
- Declare dependencies, including the Kira standard library

### File name

- Required: `kira.toml` at the project root

### Minimal example

```toml
version = "1"

[package]
name = "hello_kira"
version = "0.1.0"
authors = ["you@example.com"]

[workspace]
src = ["src", "main.kira"]
entry = "main.kira"

[build]
outDir = "build"
target = "native"
debug = true
emitIR = false

[dependencies.kira_std]
registry = "kira"
```

### Sections

- version
    - Manifest format version. Current: `"1"`.

- [package]
    - name: Required package name (string)
    - version: Semantic version (string), default `"1.0.0"`
    - authors: List of authors (string array)
    - description: Optional description (string)

- [workspace]
    - src: List of source paths. Each entry can be a file or directory. Directories are scanned recursively for `.kira`
      files.
    - entry: Optional entry-point file (string), relative to project root.

- [build]
    - outDir: Output directory for artifacts
    - target: "c" (or "native") or "neko"
    - debug: Enable debug builds (bool)
    - emitIR: Emit intermediate representation (bool)

- [dependencies]
    - Arbitrary keys are dependency names (e.g., `kira_std`)
    - Each dependency supports:
        - path: Optional local path to the dependency root
        - version: Optional version (for registry-backed dependencies)
        - registry: Registry name. For the Kira standard library, use `"kira"`.

### Standard library resolution

When the compiler runs in a project with `kira.toml`:

1. It loads the manifest and validates it.
2. It discovers the standard library by scanning dependencies with `registry = "kira"`:
    - If a dependency also has `path`, all `.kira` files under that path (relative to project root) are included as
      standard library sources.
    - Otherwise, it attempts to scan a local `./kira/` directory for `.kira` files.
3. If no manifest or matching dependency is found, the compiler falls back to scanning `./kira/`.

All discovered stdlib files are merged with the workspace sources before parsing.

### Workspace source discovery

- Each entry in `workspace.src` can be a file or directory
- Directories are scanned recursively and all `.kira` files are added
- If `workspace.entry` exists, the compiler prefers it as the program entry point

### CLI integration

- Running the compiler inside a directory with `kira.toml` uses that manifest automatically.
- Alternatively, you may pass a project directory via a `--project` flag.

Examples (PowerShell):

```powershell
./gradlew run --args "--project=."

./gradlew run --args "--project=path/to/project"
```

### Validation

During validation, the compiler reports issues such as:

- Missing `[package]` section
- Blank `package.name`
- `workspace.entry` points to a non-existent file
- Unsupported `version` of the manifest format

Diagnostics are printed with file/line context when available.

---

