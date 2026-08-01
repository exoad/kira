
// module "app:animals"
// trait Speaker
// trait Noisy
class Dog {
    constructor(label) {
        this.label = label;
    }
    speak() {
        return "woof";
    }
    name() {
        return this.label;
    }
    loudness() {
        return 8;
    }
}

class Cat {
    constructor(label) {
        this.label = label;
    }
    speak() {
        return "meow";
    }
    name() {
        return this.label;
    }
}

function announce(s) {
    kira_trace(s.name());
    kira_trace(s.speak());
}

function noiseLevel(s) {
    return s.loudness();
}

// module "app:main"
// use "app:animals"
function makeDog() {
    const dog = new Dog("Rex");
    return dog;
}

function makeSpeaker() {
    const cat = new Cat("Luna");
    return cat;
}

function main() {
    const dog = makeDog();
    const cat = new Cat("Luna");
    announce(dog);
    announce(cat);
    const dogLoud = noiseLevel(dog);
    kira_trace(dogLoud);
    const s = dog;
    kira_trace(s.name());
    const bolt = new Dog("Bolt");
    kira_trace(bolt.speak());
    kira_trace(makeSpeaker().name());
}

main();
