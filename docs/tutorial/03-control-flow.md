# 3 -- Control flow

**Example:** [`examples/03-control-flow`](../../examples/03-control-flow)

## What the example does

It sums a range with `for`, labels the total even/odd, and prints the label.
With the in-repo sources that total is odd:

```bash
./examples/run.sh 03-control-flow
# odd
```

## `if` / `else if` / `else`

```kira
fx parityLabel(value: Int32): Str {
    if value % 2 == 0 {
        return "even"
    } else {
        return "odd"
    }
}
```

Rules:

- No parentheses required around the condition (allowed, not idiomatic).
- **Braces are mandatory**, even for one statement.
- Condition must be a `Bool` -- no truthiness on numbers or strings.
- Chain with `else if`; final `else` is optional.

```kira
if score >= 90 {
    grade: Str = "A"
} else if score >= 80 {
    grade: Str = "B"
} else {
    grade: Str = "F"
}
```

## `while`

```kira
mut i: Int32 = 0
while i < 2 {
    i = i + 1
}
```

`mut` marks a binding you will reassign. Without it, `i = i + 1` is illegal.

## `for` and ranges

```kira
fx sumTo(limit: Int32): Int32 {
    mut total: Int32 = 0
    for mut i: 0..limit {
        total = total + i
    }
    return total
}
```

- `0..limit` is a range expression (start inclusive; see the language reference
  for end-bound details as the backend evolves).
- The loop variable is declared in the `for` header (`mut i`).
- Body braces are required.

`break` and `continue` exist in the language; keep loops simple until you need
them.

## Operators you will use here

| Kind | Examples |
|------|----------|
| Arithmetic | `+ - * / %` |
| Comparison | `== != < <= > >=` |
| Logical | `&& \|\| !` |

Comparisons produce `Bool`.

## Try this

Change `sumTo(5)` to `sumTo(4)` in `main.kira` and predict even/odd before
running.

## Next

[4 -- Classes](04-classes.md)
