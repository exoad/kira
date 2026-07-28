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

### Requirements

- JDK 17+
- C17 compiler on `PATH` (`cc`, `clang`, or `gcc`)

### Install

```bash
./gradlew installDist
# build/install/kira/bin/kira
# build/install/kira/bin/kira-lsp
```

```bash
export PATH="$(pwd)/build/install/kira/bin:$PATH"
./gradlew test    # optional
```

### Quick start

```bash
./examples/run.sh 01-hello
# hello, kira

./examples/run.sh          # full ladder 01..06
```

By hand:

```bash
cd examples/01-hello
kira
cc -std=c17 -O2 -o app out.kira.c && ./app
```

`kira` always loads `kira.yaml` from the **current directory** -- `cd` into the
project first.

### Project shape

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

### Language server

```bash
kira-lsp    # LSP over stdio
```

Baseline: full doc sync for `*.kira`, `publishDiagnostics` (parse + semantic).
Point any LSP client at `build/install/kira/bin/kira-lsp` with root marker
`kira.yaml`. Editor snippets: [tutorial ch.7](docs/tutorial/07-projects-and-tooling.md).

### Repo layout

| Path | Role |
|------|------|
| `src/` | Compiler + LSP (Kotlin/JVM) |
| `kira/` | Stdlib (`stl.kira`) |
| `examples/` | Ladder `01-hello` ... `06-collections` |
| `docs/tutorial/` | Hands-on guide |
| `specifications/` | Language reference |
| `test_kira/` | Smoke project for `./gradlew run` |

### Status

C-as-IR runs the example ladder: modules, functions, classes/methods, enums,
monomorphized generics, thin Arr/Map (literals, index, `isEmpty` / `size`).
Map put/get, growing lists, and ARC are not implemented yet. LSP: diagnostics
only (no hover / completion / go-to-def). Detail: [docs/backend-c.md](docs/backend-c.md).
