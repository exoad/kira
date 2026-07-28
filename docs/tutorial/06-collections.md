# 6 -- Collections

**Example:** [`examples/06-collections`](../../examples/06-collections)

## What works today

The stdlib declares rich APIs on `Arr`, `List`, `Map`, and friends. The **C
backend** currently lowers a thin baseline:

| Feature | Status |
|---------|--------|
| `Arr` literal `[10, 20, 30]` | yes (Int32 elements in practice) |
| Index `values[0]` | yes |
| `Arr` / `Map` `isEmpty()` / `size()` | yes |
| Empty `Map<K,V> { }` | yes (count-only map) |
| `Map.put` / `get` hashing | not yet |
| Growing `List.add` | not yet |

Enough to write real control flow around collections; not enough to pretend
they are a full collections library.

## Arrays

```kira
module "app:arrays"

fx first(values: Arr<Int32>): Int32 {
    return values[0]
}
```

```kira
numbers: Arr<Int32> = [10, 20, 30]
head: Int32 = first(numbers)
```

Out-of-range index aborts in the C runtime helper (`Arr_get_i32`).

## Maps

```kira
module "app:maps"

fx hasAny(values: Map<Str, Int32>): Bool {
    return !values.isEmpty()
}
```

```kira
entries: Map<Str, Int32> = Map<Str, Int32> { }
present: Bool = hasAny(entries)
```

Empty maps are useful as placeholders and for `isEmpty` checks. Putting entries
needs the fuller runtime (still ahead).

## Putting it together

```kira
fx main(): Void {
    numbers: Arr<Int32> = [10, 20, 30]
    head: Int32 = first(numbers)

    entries: Map<Str, Int32> = Map<Str, Int32> { }
    present: Bool = hasAny(entries)

    if present {
        trace("map has values")
    } else {
        trace(head)
    }
}
```

```bash
./examples/run.sh 06-collections
# 10
```

## Stdlib home

Types live in [`kira/stl.kira`](../../kira/stl.kira) (`module "kira:stl"`).
Your `kira.yaml` dependency on `../../kira` pulls that file in automatically.
You do not `use "kira:stl"` in every file for magic types -- they are ambient
once the stdlib is on the compile set.

## Try this

Print `numbers` length if you wire `size()` (method form `values.size()` on
`Arr` in the baseline backend).

## Next

[7 -- Projects and tooling](07-projects-and-tooling.md)
