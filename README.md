# Kira

<p align="center">
  <img src="./public/display_logo.png" width="96" alt="Kira"/><br/>
  <strong>Kira</strong><br/>
  <em>A small object-oriented language that compiles to C.</em>
</p>

Private and immutable by default (`pub` / `mut` to opt in). The compiler is a
JVM CLI: read `kira.yaml`, typecheck, lower to **ISO C17** (`out.kira.c` --
C-as-IR), then your `cc` builds a native binary. A stdio language server
(`kira-lsp`) covers editor diagnostics. Not a JIT and not NekoVM on the
default path.

**Docs:** [tutorial](docs/tutorial/) ·
[C-as-IR backend](docs/backend-c.md) ·
[language reference](specifications/LanguageSpecifications.md) ·
[examples](examples/)

---

## Requirements

- JDK 17+
- C17 compiler on `PATH` (`cc`, `clang`, or `gcc`)

## Install

```bash
./gradlew installDist
# build/install/kira/bin/kira
# build/install/kira/bin/kira-lsp
```

```bash
export PATH="$(pwd)/build/install/kira/bin:$PATH"
./gradlew test    # optional
```

## Quick start

```bash
./examples/run.sh 01-hello
# hello, kira

./examples/run.sh          # full ladder 01..08
```

By hand:

```bash
cd examples/01-hello
kira
cc -std=c17 -O2 -o app out.kira.c && ./app
```

`kira` always loads `kira.yaml` from the **current directory** -- `cd` into the
project first.

## Project shape

```text
my-project/
  kira.yaml
  src/app/main.kira
```

```yaml
project:
  name: demo
srcDir: src
build:
  target: c
dependencies:
  kira_stdlib:
    path: ../path/to/this-repo/kira
```

```kira
module "app:main"

fx main(): Void {
    trace("hello, kira")
}
```

Module URI `"app:main"` maps to `src/app/main.kira`.

## Language server

```bash
kira-lsp    # LSP over stdio
```

Baseline: full doc sync for `*.kira`, `publishDiagnostics` (parse + semantic).
Point any LSP client at `build/install/kira/bin/kira-lsp` with root marker
`kira.yaml`. Editor snippets: [tutorial ch.7](docs/tutorial/07-projects-and-tooling.md).

## What Kira can do today

- **Modules & functions** -- multi-file projects, `use`, typed params/returns
- **Classes & OOP** -- fields (`require`), methods, construction; heap-allocated
  with ARC (strong refcount, scope-end release)
- **Generics** -- user `class Box<T>` / `fx id<T>` monomorphized at compile time
- **Enums** -- tagged C enums
- **Traits** -- interface structs + vtables; inheritance between traits;
  polymorphic dispatch; class values coerce to trait slots at call sites
- **Collections** -- `Arr` literals/indexing, owning `List`, real `Map`
  (open-addressing hash: put/get/remove/containsKey/clear)
- **Control flow** -- `if` / `else` / `while` / `for` ranges
- **Foreign edge** -- `@_opaque` types and `@_extern` stubs link real C libs
  (see `examples/ffi-mini`)

## Repo layout

| Path | Role |
|------|------|
| `src/` | Compiler + LSP (Kotlin/JVM) |
| `kira/` | Stdlib (`stl.kira`) |
| `examples/` | Ladder `01-hello` ... `08-traits` (+ `c-as-ir`, `ffi-mini`) |
| `docs/` | Doctrine, ARC, backend status, roadmap |
| `specifications/` | Language reference |
| `test_kira/` | Smoke project for `./gradlew run` |

## Status

C-as-IR runs the full example ladder: modules, functions, classes/methods with
ARC, enums, monomorphized generics, real Map/List/Arr, traits with vtables,
and Conway's Game of Life (`examples/07-conway`) end-to-end. Known gaps:
no weak refs (cycles leak), no variant lowering, generic traits stay
prelude-side, LSP is diagnostics-only. Detail: [docs/backend-c.md](docs/backend-c.md).
