
// module "kira:math"
function clamp(value, lo, hi) {
    return Math.max(lo, Math.min(value, hi));
}

function lerp(a, b, t) {
    return (a + ((b - a) * t));
}

// module "app:arrays"
function first(values) {
    return values[0];
}

// module "app:main"
// use "app:arrays"
// use "app:maps"
function main() {
    const numbers = [10, 20, 30];
    const head = first(numbers);
    const entries = kira_map_new();
    const present = hasAny(entries);
    if (present) {
        kira_trace("map has values");
    } else {
        kira_trace(head);
    }
}

// module "app:maps"
function hasAny(values) {
    return !values.isEmpty();
}

main();
