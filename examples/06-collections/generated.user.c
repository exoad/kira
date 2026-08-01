
#include <math.h>

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
Int32 first(Arr values);
Int32 main(Void);
Bool hasAny(Map values);

/* module kira:math */
Float64 clamp(Float64 value, Float64 lo, Float64 hi)
{
    return fmax(lo, fmin(value, hi));
}
Float64 lerp(Float64 a, Float64 b, Float64 t)
{
    return (a + ((b - a) * t));
}
/* module app:arrays */
Int32 first(Arr values)
{
    return Arr_get_i32(values, 0);
}
/* module app:main */
/* use app:arrays */
/* use app:maps */
Int32 main(Void)
{
    Arr numbers = Arr_lit((KiraSlot[]){ 10, 20, 30 }, 3);
    Int32 head = first(numbers);
    Map entries = Map_new_s();
    Bool present = hasAny(entries);
    if(present)
    {
        print("%s\n", "map has values");
    } else
    {
        print("%d\n", head);
    }
    Map_dispose(&entries);
    return 0;
}
/* module app:maps */
Bool hasAny(Map values)
{
    return !Map_isEmpty(&values);
}
