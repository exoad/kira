# 0 -- Setup

## What you need

- **JDK 17+** (the compiler is a JVM program)
- A **C17** toolchain on `PATH` (`cc`, `clang`, or `gcc`) to run programs
- This repository checked out

## Build the tools

From the repo root:

```bash
./gradlew test          # optional, sanity check
./gradlew installDist   # installs CLI + language server
```

That produces:

```text
build/install/kira/bin/kira       # compiler CLI
build/install/kira/bin/kira-lsp   # language server (stdio LSP)
```

Put the CLI on your shell path if you want:

```bash
export KIRA="$(pwd)/build/install/kira/bin/kira"
export PATH="$(pwd)/build/install/kira/bin:$PATH"
```

## Anatomy of a Kira project

Every project is a directory with:

```text
my-project/
  kira.yaml          # manifest (required)
  src/               # .kira sources (srcDir)
    app/
      main.kira
```

Minimal `kira.yaml`:

```yaml
project:
  name: demo

srcDir: src

build:
  target: c

dependencies:
  kira_stdlib:
    path: /absolute/or/relative/path/to/kira/repo/kira
```

Notes:

- The compiler always reads `kira.yaml` from the **process working directory**.
  `cd` into the project before running `kira`. There is no `--project` flag yet.
- `build.target: c` is the supported emit path. Without it (or with `none`),
  the frontend still runs but no `out.kira.c` is written.
- The stdlib is the `kira/` folder in this repo (`stl.kira`). Point
  `dependencies.kira_stdlib.path` at it.

## Compile once

```bash
cd examples/01-hello
kira                         # or $KIRA
cc -std=c17 -O2 -o app out.kira.c
./app
# hello, kira
```

`out.kira.c` is gitignored. Re-run `kira` after source edits.

## Language server (optional)

Editors that speak LSP can use `kira-lsp` for diagnostics on `*.kira` files.
See the root [README](../../README.md#language-server-lsp) for Neovim / generic
client snippets. Open a folder that contains (or is a parent of) a `kira.yaml`.

## Next

[1 -- Hello](01-hello.md)
