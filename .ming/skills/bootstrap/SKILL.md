---
name: bootstrap
description: Build the Kira compiler from a fresh checkout, install the CLI, and run a first sanity test.
---

# Kira local dev bootstrap

Triggers: "how do I build kira", "fresh clone setup", "install the compiler",
"KIRA_BIN not found", "where is the kira binary".

## Prerequisites

- JDK 17 toolchain (Gradle resolves it; CI uses Temurin 17).
- A C17 compiler on PATH (`cc`, `clang`, or `gcc`).

## Steps

```bash
./gradlew installDist          # builds CLI + LSP
export PATH="$(pwd)/build/install/kira/bin:$PATH"
kira                          # run inside a project dir with kira.yaml
kira-lsp                      # stdio language server
```

Sanity check:

```bash
./gradlew test                # full unit test suite
./examples/run.sh 01-hello    # needs the CLI installed (or KIRA=/path/to/kira)
```

## CLI facts

- `kira` always loads `kira.yaml` from the current working directory - cd
  into the project first.
- Rebuild with `./gradlew installDist` after any codegen change, or the CLI
  keeps using the stale compiler.
- `./gradlew run` compiles the `test_kira/` smoke project.
- Legacy `kira.toml` is removed; a project with one panics with a migration
  message. Use `kira.yaml`.
