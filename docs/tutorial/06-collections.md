# 6 -- Collections

**Example:** [`examples/06-collections`](../../examples/06-collections)

## What works today

The stdlib declares rich APIs on `Arr`, `List`, `Map`, and friends. The **C
backend** lowers a real, owning baseline:

| Feature | Status |
|---------|--------|
| `Arr` literal `[10, 20, 30]` | yes (Int32 elements in practice) |
| Index `values[0]` / `values.set(i, v)` | yes |
| `Arr` / `Map` `isEmpty()` / `size()` | yes |
| `Map<K,V>` put / get / remove / containsKey / clear | yes (open-addressing hash) |
| `List.add` / `get` / `set` / `removeAt` / `clear` / `toArr` | yes (owning dynamic array) |

## Arrays

```kira
module "app:arrays"

fx first: (values: Arr<Int32>) Int32 {
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

fx hasAny: (values: Map<Str, Int32>) Bool {
    return !values.isEmpty()
}
```

```kira
entries: Map<Str, Int32> = Map<Str, Int32> { }
present: Bool = hasAny(entries)

entries.put("a", 1)
entries.put("b", 2)
count: Int32 = entries.size()
```

`Map` is an open-addressing hash table (djb2 hash, linear probing, auto-resize
at 0.75 load). `put` / `get` / `remove` / `containsKey` / `clear` all lower to
C runtime helpers.

## Lists

```kira
items: List<Int32> = List<Int32> { }
items.add(10)
items.add(20)
head: Int32 = items.get(0)
```

`List` is an owning dynamic array that doubles on overflow.

## Putting it together

```kira
fx main: () Void {
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

Container types live in
[`kira/collections.kira`](../../kira/collections.kira) (`module
"kira:collections"`); `Maybe`, the return type of `Map.get`, lives in
[`kira/result.kira`](../../kira/result.kira).

Your `kira.yaml` dependency on `../../kira` pulls in **every** `.kira` file
under that folder, so all stdlib modules arrive together. You do not `use` them
for magic types -- they are ambient once the stdlib is on the compile set.
[`kira/stl.kira`](../../kira/stl.kira) is an index of the split modules, not a
file you need to import.

## Next

[7 -- Projects and tooling](07-projects-and-tooling.md)
