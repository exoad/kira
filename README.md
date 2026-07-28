<h1 align="center">
<img src="./public/display_logo.png" width=96/><br/>Kira
</h1>
<p align="center">
<strong>A small object-oriented language that compiles to C.</strong>
</p>

> Active development. The C backend is the supported target today.

Kira is private and immutable by default (`pub` / `mut` to opt in). The
compiler is a JVM CLI: it reads a `kira.yaml` from the current directory,
typechecks, and emits one C translation unit (`out.kira.c`) you compile with
any C11 toolchain. A Language Server (`kira-lsp`) speaks standard LSP over
stdio for editor diagnostics.

Language reference: [`specifications/LanguageSpecifications.md`](specifications/LanguageSpecifications.md).
Runnable samples: [`examples/`](examples/).

---

### Requirements

- JDK 17+
- A C11 compiler on `PATH` (`cc`, `clang`, or `gcc`) for running programs

### Build

```bash
./gradlew test          # unit + pipeline tests
./gradlew installDist   # -> build/install/kira/bin/{kira,kira-lsp}
```

`./gradlew run` compiles the in-repo smoke project at `test_kira/`.

### Compile a Kira project

Every project is a directory with a `kira.yaml` and a source root:

```yaml
project:
  name: demo

srcDir: src

build:
  target: c

dependencies:
  kira_stdlib:
    path: /path/to/kira/repo/kira   # the stdlib folder in this repo
```

```bash
export KIRA="$(pwd)/build/install/kira/bin/kira"

# ladder of examples
./examples/run.sh 01-hello
./examples/run.sh              # all of them

# or by hand
cd examples/01-hello
"$KIRA"
cc -std=c11 -O2 -o app out.kira.c && ./app
```

The compiler always uses the process working directory for `kira.yaml`.
There is no project-path flag yet -- `cd` into the project first.

### Language server (LSP)

`kira-lsp` is a stdio Language Server (LSP 3.x via [lsp4j](https://github.com/eclipse-lsp4j/lsp4j)).

**Baseline capabilities**

- full text document sync for `*.kira`
- `textDocument/publishDiagnostics` from the shared frontend (parse + semantic)

Point your editor at the installed binary:

```text
build/install/kira/bin/kira-lsp
```

**VS Code** (`settings.json` with any generic LSP client, e.g. [vscode-glspc](https://marketplace.visualstudio.com/items?itemName=generic-lsp.generic-lsp) or Neovim `lspconfig`):

```json
{
  "command": ["/absolute/path/to/build/install/kira/bin/kira-lsp"],
  "filetypes": ["kira"],
  "rootMarkers": ["kira.yaml"]
}
```

**Neovim** (`lspconfig` style):

```lua
vim.lsp.config("kira_ls", {
  cmd = { vim.fn.expand("~/Code/kira/build/install/kira/bin/kira-lsp") },
  filetypes = { "kira" },
  root_markers = { "kira.yaml" },
})
vim.lsp.enable("kira_ls")
```

Open a folder that contains `kira.yaml` (or a parent of one). Diagnostics
refresh on open/change with a short debounce. Hover, completion, and
go-to-definition are not wired yet.

### Quick language sketch

```kira
module "example:main"

fx main(): Void {
    trace("Hello, Kira!")
}
```

### Layout

| Path | Role |
|------|------|
| `src/` | Compiler + LSP (Kotlin/JVM) |
| `kira/` | Standard library (`stl.kira`) |
| `examples/` | Numbered ladder (`01-hello` ... `06-collections`) |
| `test_kira/` | Smoke project for `./gradlew run` and a few tests |
| `specifications/` | Language + grammar notes |
| `public/` | Logo asset for this README |

### Status (C backend)

Works end-to-end on the example ladder: modules, functions, classes/methods,
enums, monomorphized generics (`Box<T>`, `id<T>`), and a thin Arr/Map runtime
(literals, index, `isEmpty` / `size`). Map put/get hashing and growing lists
are still baseline stubs.
