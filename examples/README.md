# Kira examples

A short ladder of projects that the C backend can emit and run today.
Each directory is a full project (`kira.yaml` + `src/`) depending on the
repo stdlib at `../../kira`.

## Run

```bash
# once, from the repo root
./gradlew installDist

# one example
./examples/run.sh 01-hello

# every example in order
./examples/run.sh
```

Or by hand:

```bash
export KIRA="$(pwd)/build/install/kira/bin/kira"
cd examples/01-hello
"$KIRA"
cc -std=c17 -O2 -o app out.kira.c && ./app
```

## What is checked in

`out.kira.c` itself is gitignored, but its two halves are committed so the
emitted C is reviewable without running the compiler:

| File | Contents |
|------|----------|
| `0N-name/generated.user.c` | That example's C lowering (post-prelude) |
| `0N-name/generated.user.js` | That example's JS lowering (post-prelude) |
| `0N-name/expected.txt` | Exact stdout of the built binary (both backends agree) |
| `prelude.reference.c` | The C runtime prelude — byte-identical for every example, stored once |
| `prelude.reference.js` | The JS runtime prelude — byte-identical, stored once |

`out.kira.c` = `prelude.reference.c` + `generated.user.c` (same split for JS).

The committed `generated.user.*` files are **minified and obfuscated** by
default (user identifiers renamed, comments stripped; `main` and foreign
externs preserved). The prelude halves stay readable and byte-identical, which
is what makes the single reference copy honest. To inspect a readable user
layer, emit with `kira --readable` (or set `build.minify: false`).

Refresh them after a backend change, and verify nothing drifted:

```bash
./examples/regenerate.sh          # rewrite snapshots + build + run each example
./examples/regenerate.sh --check  # CI mode: fail if any snapshot is stale
./examples/regenerate.sh 04-classes
```

`--check` also re-runs each binary and diffs against `expected.txt`, so it
catches behaviour changes, not just changes to the text of the C.

## Ladder

| # | Project | Shows | Expected output |
|---|---------|-------|-----------------|
| 01 | [`01-hello`](01-hello/) | single file, `main`, `trace` | `hello, kira` |
| 02 | [`02-functions`](02-functions/) | multi-file modules + `use` | `hello from functions` |
| 03 | [`03-control-flow`](03-control-flow/) | `if` / `while` / `for` ranges | `odd` |
| 04 | [`04-classes`](04-classes/) | classes, fields, methods, ARC | `4` / `Mochi` / `meow` |
| 05 | [`05-enums-generics`](05-enums-generics/) | enums + monomorphized generics | `7` |
| 06 | [`06-collections`](06-collections/) | Arr literal/index, Map put/get, List add | `10` |
| 07 | [`07-conway`](07-conway/) | Conway's Game of Life (grid, step, ARC class) | 5 generations of a glider |
| 08 | [`08-traits`](08-traits/) | traits, trait inheritance, vtables, dispatch | `Rex` / `woof` / `Luna` / `meow` / `8` |
| 09 | [`09-stdlib`](09-stdlib/) | Str / Num helpers, Set, Stack, Queue, Map + `Maybe` | see `expected.txt` |

Each row's exact output lives in that project's `expected.txt`.

## C-as-IR walkthrough

**[c-as-ir/](c-as-ir/)** — annotated tour of the committed `generated.user.c`
files: what each Kira construct lowers to, and which gaps are still visible in
the output.

## Foreign C edge

**[ffi-mini/](ffi-mini/)** — pure OOP Kira calling a tiny C "surface" lib
(`@_opaque` + `@_extern` + `build.cSources`). Manual foreign free; not ARC.

```bash
./examples/ffi-mini/run.sh
```

## Layout convention

```
examples/0N-name/
  kira.yaml          # build.target: c, stdlib -> ../../kira
  src/app/*.kira     # module URIs: "app:..."
  generated.user.c   # committed snapshot of the emitted C (minified)
  generated.user.js  # committed snapshot of the emitted JS (minified)
  expected.txt       # committed stdout (both backends agree)
```

Module package is always `app` so files stay easy to copy between steps.
