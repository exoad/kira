# Kira documentation

| Path | What it is |
|------|------------|
| [tutorial/](tutorial/) | Hands-on path from zero to the full example ladder |
| [doctrine.md](doctrine.md) | Pure simple OOP doctrine (non-negotiable) |
| [roadmap.md](roadmap.md) | Working milestones (FFI → ARC → stdlib → tooling) |
| [ownership-arc.md](ownership-arc.md) | Kira heap ARC vs foreign handles |
| [backend-c.md](backend-c.md) | **C-as-IR** status: ISO C17 lowering, runtime, roadmap |
| [../specifications/LanguageSpecifications.md](../specifications/LanguageSpecifications.md) | Language reference (syntax, types, modules, OOP) |
| [../specifications/Grammar.md](../specifications/Grammar.md) | Compact grammar / style notes |
| [../examples/](../examples/) | Runnable projects the tutorial walks through |
| [../examples/c-as-ir/](../examples/c-as-ir/) | Annotated tour of the committed C each example emits |
| [../README.md](../README.md) | Install, CLI, and language server |

**Start here:** [tutorial/00-setup.md](tutorial/00-setup.md)

The tutorial only uses features the current C backend can emit and run.
Where the full language reference describes more than the backend lowers
today, the tutorial says so. Execution model and backend progress:
[backend-c.md](backend-c.md).
