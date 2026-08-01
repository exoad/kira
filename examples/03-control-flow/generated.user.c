
#include <math.h>

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
Str parityLabel(Int32 value);
Int32 main(Void);
Int32 sumTo(Int32 limit);

/* module kira:math */
Float64 clamp(Float64 value, Float64 lo, Float64 hi)
{
    return fmax(lo, fmin(value, hi));
}
Float64 lerp(Float64 a, Float64 b, Float64 t)
{
    return (a + ((b - a) * t));
}
/* module app:checks */
Str parityLabel(Int32 value)
{
    if(((value % 2) == 0))
    {
        return "even";
    } else
    {
        return "odd";
    }
}
/* module app:main */
/* use app:ranges */
/* use app:checks */
Int32 main(Void)
{
    Int32 i = 0;
    while((i < 2))
    {
        i = (i + 1);
    }
    Int32 value = sumTo(5);
    Str label = parityLabel(value);
    print("%s\n", label);
    return 0;
}
/* module app:ranges */
Int32 sumTo(Int32 limit)
{
    Int32 total = 0;
    for(Int32 i = 0; i <= limit; ++i)
    {
        total = (total + i);
    }
    return total;
}
