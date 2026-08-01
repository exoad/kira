
// module "app:main"
// use "app:model"
function main() {
    const rect = new Rectangle(new Point(0, 1), new Point(1, 0));
    const friend = new Pet("Mochi", "meow");
    kira_trace(rect.perimeter());
    kira_trace(friend.name);
    kira_trace(friend.speak());
}

// module "app:model"
class Point {
    constructor(x, y) {
        this.x = x;
        this.y = y;
    }
}

class Rectangle {
    constructor(topLeft, bottomRight) {
        this.topLeft = topLeft;
        this.bottomRight = bottomRight;
    }
    perimeter() {
        const width = (this.bottomRight.x - this.topLeft.x);
        const height = (this.topLeft.y - this.bottomRight.y);
        return ((width + height) * 2);
    }
}

class Pet {
    constructor(name, sound) {
        this.name = name;
        this.sound = sound;
    }
    speak() {
        return this.sound;
    }
}

main();
