
// module "kira:math"
function clamp(value, lo, hi) {
    return Math.max(lo, Math.min(value, hi));
}

function lerp(a, b, t) {
    return (a + ((b - a) * t));
}

// module "app:checks"
function parityLabel(value) {
    if (((value % 2) == 0)) {
        return "even";
    } else {
        return "odd";
    }
}

// module "app:main"
// use "app:ranges"
// use "app:checks"
function main() {
    let i = 0;
    while ((i < 2)) {
        i = (i + 1);
    }
    const value = sumTo(5);
    const label = parityLabel(value);
    kira_trace(label);
}

// module "app:ranges"
function sumTo(limit) {
    let total = 0;
    for (let i = 0; i <= limit; ++i) {
        total = (total + i);
    }
    return total;
}

main();
