# ffi-mini -- foreign C edge (pure OOP surface)

Kira stays OOP: modules, an opaque class handle, and functions.
The **bytes** live in C (`native/mini_gfx.c`); lifetime is manual
(`miniDestroy`), never Kira ARC.

```bash
./gradlew installDist
export PATH="$(pwd)/build/install/kira/bin:$PATH"
cd examples/ffi-mini
kira
cc -std=c17 -O2 -o app out.kira.c native/mini_gfx.c
./app
# mini: 320x240 "kira-ffi"
# ffi-mini ok
```

`kira.yaml` lists `build.cSources` so the compiler prints the full link line.
