![Kira](./public/kira_header_small.png)

> [!NOTE]
> Hi! This project is currently a work in progress :)
>
> Thus, a lot of things will lack documentation!

An **enjoyable** modern object-oriented programming language with modern syntax and semantics inspired from languages
such as Java, C, Dart, and Rust.

`June 28, 2025:`

Currently, the compiler and further language designs are not finished. The frontend is implemented in Kotlin with the
backend currently chosen to either be LLVM or a fork of NekoVM. Additionally, I have also been considering generating
raw x86 Assembly and using NASM to do the rest; however, we will see where this goes :).

## Code Snippets

> [!WARNING]
> Nothing here is final, so the syntax and semantics will change.

### Hello, World!

```zig
@__trace__("Hello World!");
```

### Conditionals & Loops

```zig
someCondition: Bool = 1 + 1 == 2;
if(someCondition)
{
    @__trace__("Is true!");
}
else
{
    @__trace__(":(");
}

i: Int32 = 32;
while(i-- > 0)
{
    @__trace__("Counting down at: ${i}");
}
```

### Classes

```zig
class Employee
{
    require pub name: String
    require id: Int32
    require pub clockedInTimes: Int64 = 0

    require pub accessDatabase(): Void

    pub transferTo(otherEmployee: Int32): User
    {
        return Employee(
            name = ::name
            id = otherEmployee
            clockedInTimes = ::clockedInTimes
        )
    }

    pub @__greater_than__(otherEmployee: Employee): Bool
    {
        if(::clockedInTimes > otherEmployee::clockedInTimes)
        {
            return true;
        }
        return false;
    }
}

@main(): Void
{
    @__trace__(Employee(name = "John Doe", id = 123, accessDatabase = (): Void {
        @__trace__("I am in!")
   }))
}
```

Check back later! More stuffs will come and go :)

## Chores

### Language Features

Chores relating to anything to do with the upfront semantics and syntax of the language

- [ ] Parser evaluates escaped string characters

- [ ] Interpolation in strings without using `+`

- [ ] **In Triage** For Loop

### Internal Workings

Chores relating to anything that involves the internal workings

- [ ] Static Analyzer for validating the state of the AST

- [ ] Support for outputting the AST as an XML document so there is not a necessary usage of another parser to parse the
  formatting

- [ ] **In Progress** Transpile to NekoVM Neko code with full support for all features

### General Maintenance

General upkeep.

- [ ] **In Progress** Enable more verbose logging that is currently toggleable through --verbose

