# Kira tutorial

A short path through the language using the in-repo example ladder.
Each chapter ends with a project under `examples/` you can compile and run.

## Chapters

| # | Chapter | Example | You learn |
|---|---------|---------|-----------|
| 0 | [Setup](00-setup.md) | -- | install the compiler, project layout |
| 1 | [Hello](01-hello.md) | `01-hello` | modules, `main`, `trace` |
| 2 | [Functions and modules](02-functions-modules.md) | `02-functions` | `fx`, `use`, multi-file |
| 3 | [Control flow](03-control-flow.md) | `03-control-flow` | `if`, `while`, `for` + ranges |
| 4 | [Classes](04-classes.md) | `04-classes` | fields, methods, object init |
| 5 | [Enums and generics](05-enums-generics.md) | `05-enums-generics` | enums, `Box<T>`, `id<T>` |
| 6 | [Collections](06-collections.md) | `06-collections` | `Arr`, `Map` (hash), `List` (owning) |
| 7 | [Projects and tooling](07-projects-and-tooling.md) | -- | `kira.yaml`, C emit, LSP |

Beyond the ladder, `examples/07-conway` runs Conway's Game of Life end-to-end
and `examples/08-traits` demonstrates traits + vtables (see the
[examples README](../../examples/README.md)).

## How to run any example

From the repo root, after `./gradlew installDist`:

```bash
./examples/run.sh 01-hello   # one
./examples/run.sh            # all, in order
```

Or by hand:

```bash
export KIRA="$(pwd)/build/install/kira/bin/kira"
cd examples/01-hello
"$KIRA"
cc -std=c17 -O2 -o app out.kira.c && ./app
```

## Conventions used here

- Module URIs look like `"app:main"` and the file lives at `src/app/main.kira`.
- Everything is private and immutable unless marked `pub` or `mut`.
- Printing uses `trace(...)` (line-oriented). The older `@_trace_(...)` form
  is the same intrinsic family; the language reference still shows both.
- The supported backend today is **C** (`build.target: c` in `kira.yaml`).
