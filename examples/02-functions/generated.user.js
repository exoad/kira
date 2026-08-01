
// module "app:greetings"
// use "app:utils"
function greeting() {
    return normalize("hello from functions");
}

// module "app:main"
// use "app:greetings"
function main() {
    const message = greeting();
    kira_trace(message);
}

// module "app:utils"
function normalize(text) {
    return text;
}

main();
