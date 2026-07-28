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

`out.kira.c` is gitignored.

## Ladder

| # | Project | Shows | Expected output |
|---|---------|-------|-----------------|
| 01 | `01-hello` | single file, `main`, `trace` | `hello, kira` |
| 02 | `02-functions` | multi-file modules + `use` | `hello from functions` |
| 03 | `03-control-flow` | `if` / `while` / `for` ranges | `odd` |
| 04 | `04-classes` | classes, fields, methods | `4` / `Mochi` / `meow` |
| 05 | `05-enums-generics` | enums + monomorphized generics | `7` |
| 06 | `06-collections` | Arr literal/index, Map `isEmpty` | `10` |

## C-as-IR showcase

Side-by-side Kira → real emitted C17 (checked-in snapshots):

**[c-as-ir/](c-as-ir/)** -- hello, classes, generics, collections.

```bash
./examples/c-as-ir/regenerate.sh
```

## Layout convention

```
examples/0N-name/
  kira.yaml          # build.target: c, stdlib -> ../../kira
  src/app/*.kira     # module URIs: "app:..."
```

Module package is always `app` so files stay easy to copy between steps.
