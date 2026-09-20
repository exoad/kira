---
name: examples-snapshots
description: Run the example ladder, regenerate committed C snapshots, and add a new ladder example.
---

# Example ladder and snapshots

Triggers: "run the examples", "regenerate snapshots", "snapshots stale",
"add an example", "how do examples work".

## Run

```bash
./gradlew installDist                      # once; CLI must exist
./examples/run.sh                          # all examples in order
./examples/run.sh 07-conway                # one example
./examples/run.sh --keep 04-classes        # leave out.kira.c + app behind
```

`out.kira.c` and the native `app` are gitignored and removed unless `--keep`.

## Regenerate (after a backend change)

```bash
./examples/regenerate.sh                   # refresh + build + run everything
./examples/regenerate.sh --check           # verify only; fail on stale
./examples/regenerate.sh 04-classes        # one example
```

What is committed per example:

- `generated.user.c` - the user lowering (post-prelude)
- `expected.txt` - exact stdout
- `examples/prelude.reference.c` - the shared prelude (once, not per example)

## Add a new ladder example

1. Create `examples/0N-name/` with:

   ```yaml
   # kira.yaml
   project:
     name: name
   srcDir: src
   build:
     target: c
   dependencies:
     kira_stdlib:
       path: ../../kira
   ```

2. Sources under `src/app/*.kira`, module URIs `"app:..."`. Keep the module
   package `app` (files stay easy to copy between steps).
3. Build and run by hand to confirm output:

   ```bash
   ./gradlew installDist
   cd examples/0N-name
   ../../build/install/kira/bin/kira
   cc -std=c17 -O2 -o app out.kira.c && ./app
   ```

4. Generate the committed snapshots:

   ```bash
   cd ../.. && ./examples/regenerate.sh 0N-name
   ```

5. Add the row to `examples/README.md` ladder table (number, project, shows,
   expected output) and bump the `01..NN` range in README.md if it names one.
6. Commit sources + `generated.user.c` + `expected.txt` together.

## Foreign edge and tour (not in the numbered ladder)

- `examples/ffi-mini/` - `@_opaque` + `@_extern` + `build.cSources`; run with
  `./examples/ffi-mini/run.sh`; has its own committed app binary rule in
  .gitignore.
- `examples/c-as-ir/` - annotated walkthrough of the committed snapshots.
