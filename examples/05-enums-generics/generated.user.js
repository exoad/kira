
// module "kira:math"
function clamp(value, lo, hi) {
    return Math.max(lo, Math.min(value, hi));
}

function lerp(a, b, t) {
    return (a + ((b - a) * t));
}

// module "app:box"
class Box {
    constructor(value) {
        this.value = value;
    }
}

function id(value) {
    return value;
}

// module "app:main"
// use "app:status"
// use "app:box"
function main() {
    const state = BuildStatus.READY;
    const wrapped = new Box(7);
    const value = id(wrapped.value);
    if (op_eq(state, BuildStatus.READY)) {
        kira_trace(value);
    }
}

// module "app:status"
const BuildStatus = Object.freeze({ READY: 0, RUNNING: 1, DONE: 2 });

main();
