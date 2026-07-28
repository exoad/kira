# 7 -- Projects and tooling

## Manifest (`kira.yaml`)

KIM (Kira Manifest) is **YAML**, file name **`kira.yaml`**, at the project root.
(Older docs mentioned `kira.toml`; that format was removed.)

```yaml
project:
  name: demo

srcDir: src

build:
  target: c          # c | native | neko | none

compiler:
  emitIr: dump.txt   # optional IR / symbol dump path

dependencies:
  kira_stdlib:
    path: ../../kira # directory of .kira stdlib sources
```

| Field | Role |
|-------|------|
| `project.name` | Human name |
| `srcDir` | Root scanned recursively for `.kira` |
| `build.target` | `c` / `native` → emit `out.kira.c`; `none` → frontend only |
| `compiler.emitIr` | Optional dump file for debugging the pipeline |
| `dependencies.*.path` | Local path dependency (stdlib is the usual one) |

The CLI loads `kira.yaml` from the **current working directory** only.

## Compile → C → run

```bash
kira
cc -std=c17 -O2 -o app out.kira.c
./app
```

Or the helper:

```bash
./examples/run.sh 04-classes
```

On success the compiler prints a one-line `cc` hint.

## Language server

```bash
./gradlew installDist
# binary:
build/install/kira/bin/kira-lsp
```

Baseline LSP surface:

- full document sync for `*.kira`
- `textDocument/publishDiagnostics` (parse + semantic, including unsaved buffers)

Point your editor's LSP client at that binary; use `kira.yaml` as a root marker.
Hover, completion, and go-to-definition are not implemented yet.

## Editor tip

Open the **project folder** (the one with `kira.yaml`), not only a single file,
so stdlib resolution and multi-file `use` see the whole graph.

## Where to read next

| Doc | Use when |
|-----|----------|
| [C-as-IR backend](../backend-c.md) | How programs run; ISO C17 lowering status |
| [Language specifications](../../specifications/LanguageSpecifications.md) | Full syntax and semantics |
| [Grammar notes](../../specifications/Grammar.md) | Compact grammar / style |
| [examples/README](../../examples/README.md) | Ladder catalog |
| [Root README](../../README.md) | Install + LSP client snippets |

## Status snapshot (C backend)

Works end-to-end on the tutorial ladder:

- modules + `use`
- functions, locals, `mut`
- `if` / `while` / `for` ranges
- classes, fields, methods, object init
- enums
- monomorphized user generics (`Box<T>`, `id<T>`)
- thin `Arr` / `Map` runtime

Still baseline / not lowered: full Map put/get, growing lists, traits,
inheritance, and most of the richer stdlib surface.

## You made it

You can scaffold a new project by copying `examples/01-hello`, pointing
`kira_stdlib.path` at this repo's `kira/` folder, and growing from there.
