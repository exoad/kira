# 2 -- Functions and modules

**Example:** [`examples/02-functions`](../../examples/02-functions)

## Split work across files

Three files under `src/app/`:

**`utils.kira`**

```kira
module "app:utils"

fx normalize: (text: Str) Str {
    return text
}
```

**`greetings.kira`**

```kira
module "app:greetings"

use "app:utils"

fx greeting: () Str {
    return normalize("hello from functions")
}
```

**`main.kira`**

```kira
module "app:main"

use "app:greetings"

fx main: () Void {
    message: Str = greeting()
    trace(message)
}
```

```bash
./examples/run.sh 02-functions
# hello from functions
```

## `use` brings another module in

```kira
use "app:utils"
```

- The URI must match the other file's `module "..."` line.
- After `use`, public names from that module are visible in this file.
- Today the examples keep helpers in the same package (`app`) without `pub`
  on free functions; cross-package visibility is stricter -- mark exports
  `pub` when you split libraries.

## Function shape (recap)

```kira
fx name: (param: Type, other: Type) ReturnType {
    return value
}
```

Rules that matter day one:

| Rule | Detail |
|------|--------|
| Keyword | Always `fx` |
| Types | Every parameter and the return type are explicit |
| Body | Braces required |
| Return | Non-`Void` functions need `return` on every path |
| Naming | Functions and locals: `camelCase` |

Returning a value:

```kira
fx add: (a: Int32, b: Int32) Int32 {
    return a + b
}
```

`Void` means "no useful value" -- `main` is the usual case.

## Local bindings

```kira
message: Str = greeting()
```

Syntax is `name: Type = expression`. Immutable by default. Types you will use
constantly: `Int32`, `Str`, `Bool`, `Void`, plus collections later.

## Module URI ↔ path

| Module URI | File |
|------------|------|
| `"app:main"` | `src/app/main.kira` |
| `"app:greetings"` | `src/app/greetings.kira` |
| `"app:utils"` | `src/app/utils.kira` |

If the path and URI disagree, the semantic checker reports an error. Keep them
aligned when you rename files.

## Try this

Add `fx shout: (text: Str) Str` in `utils.kira` that returns the same string,
call it from `greeting()`, re-run.

## Next

[3 -- Control flow](03-control-flow.md)
