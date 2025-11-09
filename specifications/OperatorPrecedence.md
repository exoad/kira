# Operator precedence & associativity

This document records the operator precedence and associativity rules for Kira. It mirrors the precedence levels used in
the language specification and provides guidance for parser implementers and library authors.

> Note: intrinsics (identifiers prefixed with `@`) are treated as primary expressions by the parser (like calls) unless
> the grammar explicitly recognizes them as operator tokens. Intrinsics may be lowered to operator-like behaviors during
> later compilation phases, but user-level addition of new infix tokens is not supported without grammar changes.

|   Precedence (high → low) | Operators / tokens                                                 |   Associativity | Notes / examples                                                                                                                                                                       |
|--------------------------:|--------------------------------------------------------------------|----------------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|     1 — Primary (postfix) | `.`, `()`, `[]`, object/tuple constructors `{ ... }`, `@name(...)` |   left-to-right | Field access, calls, indexing, object/tuple construction and compile-time intrinsic invocation bind most tightly. Example: `obj.fn(arg)[i].field` binds as `((obj.fn(arg))[i]).field`. |
|                 2 — Unary | unary `+`, unary `-`, `!` (if present)                             |   right-to-left | Unary operators bind tighter than binary arithmetic. Example: `-a * b` → `(-a) * b`.                                                                                                   |
|        3 — Multiplicative | `*`, `/`, `%`                                                      |   left-to-right | Multiplicative operators bind tighter than additive. `a * b / c` → `(a * b) / c`.                                                                                                      |
|              4 — Additive | `+`, `-`                                                           |   left-to-right | Addition/subtraction and `+`-based concatenation if overloaded. `a + b - c` → `(a + b) - c`.                                                                                           |
|                 5 — Range | `..`                                                               | non-associative | Range expressions are non-associative. `a .. b .. c` should be a parse error; use parentheses to disambiguate. `+` binds tighter than `..` (so `a .. b + c` → `a .. (b + c)`).         |
|    6 — Type / Cast / Test | `as`, `is`                                                         |   left-to-right | Type casts and type tests. Example: `x as T`.                                                                                                                                          |
| 7 — Equality / Relational | `==`, `!=`, `<`, `>`, `<=`, `>=`                                   | non-associative | Chaining without logical connectors is rejected (e.g. `a < b < c` is invalid); use `a < b && b < c` instead.                                                                           |
|            8 — Membership | `in`                                                               | non-associative | Membership/containment checks. Example: `x in 0..10`.                                                                                                                                  |
|   9 — Assignment (lowest) | `=`, compound assigns like `+=`, `-=`                              |   right-to-left | Assignment binds weakest; compound assignments desugar to corresponding binary ops. `a = b = c` → `a = (b = c)`.                                                                       |

---

If you'd like, I can insert a short cross-reference line into `specifications/LanguageSpecifications.md` pointing to
this file (e.g. "See: Operator precedence & associativity (specifications/OperatorPrecedence.md)"), or attempt the
in-place insertion again.
