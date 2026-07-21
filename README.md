<h1 align="center">
<img src="./public/display_logo.png" width=96/><br/>Kira
</h1>
<p align="center">
<strong>
A modern object-oriented programming language focused on simplicity & practicality.
</strong>
</p>

> [!NOTE]
> This project is currently under active development. Documentation may be incomplete.

**Kira** is a modern, pure object-oriented programming language with expressive syntax inspired by Swift, Kotlin, and
Dart. It functions as a flexible toolkit—similar to Haxe—supporting transpilation and ahead-of-time (AOT) compilation to
multiple targets, including source code, bytecode, bitcode, and machine code.

Kira enforces three core principles: **privacy**, **immutability**, and **static behavior**. All declarations are
private and immutable by default. To enable mutability or public access, use the `mut` or `pub` modifiers respectively.
Classes contain only instance-level data; static and companion members are managed via namespaces.

---

For the full language reference and detailed specifications, see the canonical specification document:

- `specifications/LanguageSpecifications.md`

Quick pointers

- Full language spec: `specifications/LanguageSpecifications.md`

### Project Config (Simple)

Kira now uses a single YAML project file named `kira.yaml`.

Minimal example:

```yaml
project:
    name: demo

srcDir: src

build:
    target: c

dependencies:
    kira_stdlib:
        path: ./kira
```

Notes:

- `srcDir` is a single root directory scanned recursively for `.kira` files.
- Dependencies are local path-based only (`dependencies.<name>.path`).
- Legacy `kira.toml` manifests are no longer supported.

### Quick example

```kira
module "example:main"

fx main(): Void {
    @trace("Hello, Kira!")
}
```

### Build & run

Requires JDK 17+.

```bash
./gradlew test          # unit + pipeline smoke tests
./gradlew run           # compile the in-repo sample at test_kira/
./gradlew installDist   # package a runnable distribution under build/install/
```

`./gradlew run` uses `test_kira/` as the working directory (it has a sample
`kira.yaml`). The compiler always reads `kira.yaml` from the process cwd, so to
compile your own project either point a custom Gradle run config at that
directory, or:

```bash
./gradlew installDist
cd /path/to/your/kira/project   # directory containing kira.yaml
/path/to/kira/build/install/kira_lang/bin/kira_lang
```

Optional parser backend override (default is the hand-written legacy parser):

```bash
KIRA_PARSER=antlr ./gradlew run
# or: -Dkira.parser=antlr
```
