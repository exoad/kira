![Kira](./public/kira_header_small.png)

> [!NOTE]
> Hi! This project is currently a work in progress :)
>
> Thus, a lot of things will lack documentation!

An **enjoyable** modern object oriented programming language with modern syntax and semantics inspired from languages
such as Java, C, Dart, and Rust.

`June 28, 2025:`

Currently the compiler and further language designs are not finished. The frontend is implemented in Kotlin with the
backend currently chosen to either be LLVM or a fork of NekoVM. Additionally, I have also been considering generating
raw x86 Assembly and using NASM to do the rest; however, we will see where this goes :).

## Code Snippets

### Hello, World!

```
io::println("Hello World!");
```

### Conditionals & Loops

```
someCondition: Bool = 1 + 1 == 2;
if(someCondition)
{
    io::println("Is true!");
}
else
{
    io::println(":(");
}

i: Int32 = 32;
while(i-- > 0)
{
    io::println("Counting down at: ${i}");
}
```

Check back later! More stuffs will come and go :)