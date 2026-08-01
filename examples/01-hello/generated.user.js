
// module "kira:math"
function clamp(value, lo, hi) {
    return Math.max(lo, Math.min(value, hi));
}

function lerp(a, b, t) {
    return (a + ((b - a) * t));
}

// module "app:main"
function main() {
    kira_trace("hello, kira");
}

main();
