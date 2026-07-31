
typedef struct Point Point;
typedef struct Rectangle Rectangle;
typedef struct Pet Pet;

struct Point
{
    Int32 x;
    Int32 y;
};

simple Point* Point_new(Int32 x, Int32 y)
{
    Point* self = (Point*)kira_rc_alloc(sizeof(Point));
    self->x = x;
    self->y = y;
    return self;
}

struct Rectangle
{
    Point* topLeft;
    Point* bottomRight;
};

Int32 Rectangle_perimeter(Rectangle* this)
{
    Int32 width = (this->bottomRight->x - this->topLeft->x);
    Int32 height = (this->topLeft->y - this->bottomRight->y);
    return ((width + height) * 2);
}

simple Rectangle* Rectangle_new(Point* topLeft, Point* bottomRight)
{
    Rectangle* self = (Rectangle*)kira_rc_alloc(sizeof(Rectangle));
    self->topLeft = topLeft;
    self->bottomRight = bottomRight;
    return self;
}

struct Pet
{
    Str name;
    Str sound;
};

Str Pet_speak(Pet* this)
{
    return this->sound;
}

simple Pet* Pet_new(Str name, Str sound)
{
    Pet* self = (Pet*)kira_rc_alloc(sizeof(Pet));
    self->name = name;
    self->sound = sound;
    return self;
}

Int32 main(Void);
Int32 Rectangle_perimeter(Rectangle* this);
Str Pet_speak(Pet* this);

/* module app:main */
/* use app:model */
Int32 main(Void)
{
    Rectangle* rect = Rectangle_new(Point_new(0, 1), Point_new(1, 0));
    Pet* friend = Pet_new("Mochi", "meow");
    print("%d\n", Rectangle_perimeter(rect));
    print("%s\n", friend->name);
    print("%s\n", Pet_speak(friend));
    kira_rc_release(rect);
    kira_rc_release(friend);
    return 0;
}
/* module app:model */
