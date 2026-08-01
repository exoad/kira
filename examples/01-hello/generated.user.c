
#include <math.h>

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
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
/* module app:main */
Int32 main(Void)
{
    print("%s\n", "hello, kira");
    return 0;
}
