
// module "kira:math"
function clamp(value, lo, hi) {
    return Math.max(lo, Math.min(value, hi));
}

function lerp(a, b, t) {
    return (a + ((b - a) * t));
}

// module "app:grid"
class Grid {
    constructor(width, height, cells) {
        this.width = width;
        this.height = height;
        this.cells = cells;
    }
    countNeighbors(row, col) {
        let count = 0;
        let r = -1;
        while ((r <= 1)) {
            let c = -1;
            while ((c <= 1)) {
                if (((r == 0) && (c == 0))) {
                    c = (c + 1);
                    continue;
                }
                let nr = (row + r);
                let nc = (col + c);
                if (((((nr >= 0) && (nr < this.height)) && (nc >= 0)) && (nc < this.width))) {
                    let idx = ((nr * this.width) + nc);
                    let val = this.cells[idx];
                    count = (count + val);
                }
                c = (c + 1);
            }
            r = (r + 1);
        }
        return count;
    }
    step() {
        let next = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        let i = 0;
        while ((i < (this.width * this.height))) {
            let row = Math.trunc((i / this.width));
            let col = (i % this.width);
            let alive = this.cells[i];
            let n = this.countNeighbors(row, col);
            if (((alive == 1) && ((n == 2) || (n == 3)))) {
                (next[i] = 1);
            } else if (((alive == 0) && (n == 3))) {
                (next[i] = 1);
            }
 else {
                (next[i] = 0);
            }
            i = (i + 1);
        }
        i = 0;
        while ((i < (this.width * this.height))) {
            let srcVal = next[i];
            (this.cells[i] = srcVal);
            i = (i + 1);
        }
    }
    printGrid() {
        let r = 0;
        while ((r < this.height)) {
            let c = 0;
            while ((c < this.width)) {
                let idx = ((r * this.width) + c);
                if ((this.cells[idx] == 1)) {
                    kira_trace("#");
                } else {
                    kira_trace(".");
                }
                c = (c + 1);
            }
            kira_trace("");
            r = (r + 1);
        }
    }
}

// module "app:main"
// use "app:grid"
function main() {
    const g = new Grid(5, 5, [0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    let gen = 0;
    while ((gen < 5)) {
        g.printGrid();
        kira_trace("");
        g.step();
        gen = (gen + 1);
    }
}

main();
