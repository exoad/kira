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
any C11 toolchain.

Language reference: [`specifications/LanguageSpecifications.md`](specifications/LanguageSpecifications.md).
Runnable samples: [`examples/`](examples/).

---

### Requirements

- JDK 17+
- A C11 compiler on `PATH` (`cc`, `clang`, or `gcc`) for running programs

### Build the compiler

```bash
./gradlew test          # unit + pipeline tests
./gradlew installDist   # -> build/install/kira/bin/kira
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
# from this repo, after installDist:
export KIRA="$(pwd)/build/install/kira/bin/kira"
export KIRA_STDLIB="$(pwd)/kira"

cd examples/intro
# kira.yaml already points at ../../kira
"$KIRA"
cc -std=c11 -O2 -o app out.kira.c && ./app
```

The compiler always uses the process working directory for `kira.yaml`.
There is no project-path flag yet -- `cd` into the project first.

Optional parser backend (default is the hand-written legacy parser):

```bash
KIRA_PARSER=antlr "$KIRA"
# or: -Dkira.parser=antlr when launching via Gradle
```

### Quick language sketch

```kira
module "example:main"

fx main(): Void {
    @trace("Hello, Kira!")
}
```

### Layout

| Path | Role |
|------|------|
| `src/` | Compiler (Kotlin/JVM) |
| `kira/` | Standard library (`stl.kira`) |
| `examples/` | Multi-file projects that emit and run |
| `test_kira/` | Smoke project for `./gradlew run` and a few tests |
| `specifications/` | Language + grammar notes |
| `public/` | Logo asset for this README |

### Status (C backend)

Works end-to-end on the in-repo examples: modules, functions, classes/methods,
enums, monomorphized generics (`Box<T>`, `id<T>`), and a thin Arr/Map runtime
(literals, index, `isEmpty` / `size`). Map put/get hashing and growing lists
are still baseline stubs.
