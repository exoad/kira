# 1 -- Hello

**Example:** [`examples/01-hello`](../../examples/01-hello)

## The smallest program

```kira
module "app:main"

fx main: () Void {
    trace("hello, kira")
}
```

File path: `src/app/main.kira`.

Run it:

```bash
./examples/run.sh 01-hello
# hello, kira
```

## What each piece means

### Module declaration

Every source file starts with:

```kira
module "app:main"
```

- The string is a **module URI**: `"package:path.segments"`.
- The compiler expects the file path to end with the package/path segments:
  `.../app/main.kira` for `"app:main"`.
- The first declaration in a file must be the module line.

### Functions

```kira
fx main: () Void {
    ...
}
```

- `fx` introduces a function.
- Parameters (if any) are typed: `name: Type`.
- The return type comes after `): Type`.
- Braces are required for the body.
- `main(): Void` is the program entry the C backend looks for. The emitted C
  entry is `Int32 main(Void)` and returns `0`.

### Printing

```kira
trace("hello, kira")
```

`trace` is a print intrinsic. In the C backend it becomes a `printf` with a
trailing newline. You will also see `@_trace_(...)` in older docs -- same idea;
examples use the short form.

### Defaults: private and immutable

Nothing in this file is marked `pub` or `mut`. That is intentional:

- Declarations are **private** unless `pub`.
- Bindings are **immutable** unless `mut`.

Chapter 3 introduces `mut`; chapter 4 introduces `pub` on types and methods.

## Project file

`examples/01-hello/kira.yaml` points at the repo stdlib and asks for C:

```yaml
project:
  name: hello

srcDir: src

build:
  target: c

dependencies:
  kira_stdlib:
    path: ../../kira
```

## Try this

Change the string, re-run `./examples/run.sh 01-hello`, confirm the new text.

## Next

[2 -- Functions and modules](02-functions-modules.md)
