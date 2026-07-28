# Kira examples

Each subdirectory is a self-contained project (`kira.yaml` + `src/`).
All of them target C and depend on the repo stdlib at `../../kira`.

## Run one

From the repo root, after `./gradlew installDist`:

```bash
KIRA="$(pwd)/build/install/kira/bin/kira"
cd examples/intro
"$KIRA"
cc -std=c11 -O2 -o app out.kira.c && ./app
# hello from intro
```

`out.kira.c` is gitignored; re-run the compiler after edits.

## Catalog

| Project | What it shows |
|---------|----------------|
| `intro` | Cross-file functions |
| `fun-greetings` | Simple messages |
| `fun-pet-club` | Class + method call |
| `fun-snack-quest` | Branching / small flow |
| `control-flow` | if / loops |
| `oop-basics` | Fields, methods, init |
| `enums-generics` | Enums + monomorphized generics |
| `collections` | Arr literal/index, empty Map + `isEmpty` |

## Notes

- `build.target` is `c` so a plain compiler invocation emits `out.kira.c`.
- Stdlib path is relative (`../../kira`); keep that if you move a project.
- These stay inside language features the current C backend lowers cleanly.
