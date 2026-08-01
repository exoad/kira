
#include <math.h>

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
    Point* self = (Point*)kira_rc_alloc_with(sizeof(Point), null);
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

static Void Rectangle_finalize(Void* p)
{
    Rectangle* self = (Rectangle*)p;
    kira_rc_release(self->topLeft);
    kira_rc_release(self->bottomRight);
}

simple Rectangle* Rectangle_new(Point* topLeft, Point* bottomRight)
{
    Rectangle* self = (Rectangle*)kira_rc_alloc_with(sizeof(Rectangle), Rectangle_finalize);
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
    Pet* self = (Pet*)kira_rc_alloc_with(sizeof(Pet), null);
    self->name = name;
    self->sound = sound;
    return self;
}

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
Int32 main(Void);
Int32 Rectangle_perimeter(Rectangle* this);
Str Pet_speak(Pet* this);

/* module kira:math */
Float64 clamp(Float64 value, Float64 lo, Float64 hi)
{
    return fmax(lo, fmin(value, hi));
}
Float64 lerp(Float64 a, Float64 b, Float64 t)
{
    return (a + ((b - a) * t));
}
/* module app:main */
/* use app:model */
Int32 main(Void)
{
    Rectangle* rect = Rectangle_new(Point_new(0, 1), Point_new(1, 0));
    Pet* friend = Pet_new("Mochi", "meow");
    print("%d\n", Rectangle_perimeter(rect));
    print("%s\n", friend->name);
    print("%s\n", Pet_speak(friend));
    kira_rc_release(friend);
    kira_rc_release(rect);
    return 0;
}
/* module app:model */
