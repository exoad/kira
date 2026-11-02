# Syntax status by target

Measured 2026-11-01 with probe programs compiled on each target (`kira --target X`).
OK means it compiled. REFUSED means the backend stopped with an unsupported-construct
diagnostic. ERROR means a type or rule error, which is the intended result for that program.

| construct | c | cpp | js | py |
|---|---|---|---|---|
| ternary `c ? a : b` as a value | REFUSED | OK | REFUSED | OK |
| `if` as a value, statement branches | REFUSED | OK | REFUSED | OK |
| `if` as a value, single-expression arms | ERROR (use `?:`) | ERROR | ERROR | ERROR |
| lambda literal `() Int32 { 2 }` | REFUSED | OK | REFUSED | OK |
| struct declaration `P: Struct` | REFUSED | OK | REFUSED | OK |
| member assignment `p.x = 3` | REFUSED | OK | REFUSED | OK |
| `this` | REFUSED | OK | REFUSED | OK |
| char literal `'a'` | REFUSED | OK | REFUSED | OK |
| interpolated string `"${n}"` | REFUSED | OK | REFUSED | OK |
| `with` builder | REFUSED | REFUSED | REFUSED | OK |
| named construction `P { x = 7 }` | REFUSED | OK | REFUSED | OK |
| `initially` block | REFUSED | OK | REFUSED | OK |
| `try` as a value | REFUSED | REFUSED | REFUSED | OK |
| `@_static_assert` | REFUSED | OK | REFUSED | OK |
| trait declaration | OK | OK | OK | REFUSED |
| `Maybe`, `List`, `Map`, `Float64`, `for` range, `break` | OK | OK | OK | OK |

## Gaps

- The C and JS backends refuse the same set of constructs. C is the target that matters for
  the Pico, so this set is the C backend's work list.
- `with` lowers only on py, and only at module level on cpp. A `with` inside a function is
  refused by cpp.
- `try` as a value lowers only on py.
- Traits do not lower on py.
- A `with` builder needs every field declared `Mutable`, or the rule error follows.

## Known from the libraries

- An extern enum parameter (for example `gpio_num_t`) cannot be passed from Kira as `Int32`.
- An extern class with a default constructor is built with `T { }` only when it is declared
  `@_constructible @_extern(...) T: Struct`, on cpp only. Kira never copies such an object, so
  it cannot be passed to a function, returned or captured.
- An `@_extern` struct passes by pointer on cpp only, and only for the call. An extern
  parameter `p: Unsafe<T>` takes a `T` value as `const T*`. A method marked
  `@_extern(c = "f")` calls the C function `f` with the receiver's address first: `T*` when the
  method is `@_mutates` (the binding must be Mutable), else `const T*`.
- An `@_extern` struct that declares fields is built with `T { field = v }`. Its
  `Unsafe<T>` field takes only a module-level constant `Arr`. A C field whose name has an
  underscore cannot be declared, so leave it out when the struct's size still matches; it
  starts at zero.
- `mut` out-parameters of scalars on externs are not usable from the binding side.
- `on` is a reserved word. A py field named `bytes` is refused.
- `Maybe.value` on a null value stops the program at run time. Nothing warns at compile time.

## Tests

- `./gradlew test` runs 1942 tests. One fails on Windows and also fails on main:
  `WithBuilderTest.theWithsPyGoldenIsConvertedText`.
