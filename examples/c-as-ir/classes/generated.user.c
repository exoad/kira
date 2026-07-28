
typedef struct Point Point;
typedef struct Pet Pet;

struct Point
{
    Int32 x;
    Int32 y;
};

struct Pet
{
    Str name;
    Str sound;
};

Str Pet_speak(Pet* this)
{
    return this->sound;
}

Int32 main(Void);
Str Pet_speak(Pet* this);

/* module app:main */
/* use app:model */
Int32 main(Void)
{
    Point origin = (Point) { 0, 0 };
    Pet friend = (Pet) { "Mochi", "meow" };
    print("%s\n", friend.name);
    print("%s\n", Pet_speak(&friend));
    return 0;
}
/* module app:model */
