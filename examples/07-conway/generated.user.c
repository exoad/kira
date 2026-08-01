
#include <math.h>

typedef struct Grid Grid;

struct Grid
{
    Int32 width;
    Int32 height;
    Arr cells;
};

Int32 Grid_countNeighbors(Grid* this, Int32 row, Int32 col)
{
    Int32 count = 0;
    Int32 r = -1;
    while((r <= 1))
    {
        Int32 c = -1;
        while((c <= 1))
        {
            if(((r == 0) && (c == 0)))
            {
                c = (c + 1);
                continue;
            }
            Int32 nr = (row + r);
            Int32 nc = (col + c);
            if(((((nr >= 0) && (nr < this->height)) && (nc >= 0)) && (nc < this->width)))
            {
                Int32 idx = ((nr * this->width) + nc);
                Int32 val = Arr_get_i32(this->cells, idx);
                count = (count + val);
            }
            c = (c + 1);
        }
        r = (r + 1);
    }
    return count;
}

Void Grid_step(Grid* this)
{
    Arr next = Arr_lit((KiraSlot[]){ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, 25);
    Int32 i = 0;
    while((i < (this->width * this->height)))
    {
        Int32 row = (i / this->width);
        Int32 col = (i % this->width);
        Int32 alive = Arr_get_i32(this->cells, i);
        Int32 n = Grid_countNeighbors(this, row, col);
        if(((alive == 1) && ((n == 2) || (n == 3))))
        {
            Arr_set(next, i, KIRA_SLOT(1));
        } else if(((alive == 0) && (n == 3)))
        {
            Arr_set(next, i, KIRA_SLOT(1));
        }
 else
        {
            Arr_set(next, i, KIRA_SLOT(0));
        }
        i = (i + 1);
    }
    i = 0;
    while((i < (this->width * this->height)))
    {
        Int32 srcVal = Arr_get_i32(next, i);
        Arr_set(this->cells, i, KIRA_SLOT(srcVal));
        i = (i + 1);
    }
}

Void Grid_printGrid(Grid* this)
{
    Int32 r = 0;
    while((r < this->height))
    {
        Int32 c = 0;
        while((c < this->width))
        {
            Int32 idx = ((r * this->width) + c);
            if((Arr_get_i32(this->cells, idx) == 1))
            {
                print("%s\n", "#");
            } else
            {
                print("%s\n", ".");
            }
            c = (c + 1);
        }
        print("%s\n", "");
        r = (r + 1);
    }
}

simple Grid* Grid_new(Int32 width, Int32 height, Arr cells)
{
    Grid* self = (Grid*)kira_rc_alloc_with(sizeof(Grid), null);
    self->width = width;
    self->height = height;
    self->cells = cells;
    return self;
}

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
Int32 Grid_countNeighbors(Grid* this, Int32 row, Int32 col);
Void Grid_step(Grid* this);
Void Grid_printGrid(Grid* this);
Int32 main(Void);

/* module kira:math */
Float64 clamp(Float64 value, Float64 lo, Float64 hi)
{
    return fmax(lo, fmin(value, hi));
}
Float64 lerp(Float64 a, Float64 b, Float64 t)
{
    return (a + ((b - a) * t));
}
/* module app:grid */
/* module app:main */
/* use app:grid */
Int32 main(Void)
{
    Grid* g = Grid_new(5, 5, Arr_lit((KiraSlot[]){ 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, 25));
    Int32 gen = 0;
    while((gen < 5))
    {
        Grid_printGrid(g);
        print("%s\n", "");
        Grid_step(g);
        gen = (gen + 1);
    }
    kira_rc_release(g);
    return 0;
}
