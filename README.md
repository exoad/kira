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

### Quick example

```kira
module "example:main"

fx main(): Void {
    @trace("Hello, Kira!")
}
```
