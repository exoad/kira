
#include <math.h>

Float64 clamp(Float64 value, Float64 lo, Float64 hi);
Float64 lerp(Float64 a, Float64 b, Float64 t);
Str greeting(Void);
Int32 main(Void);
Str normalize(Str text);

/* module kira:math */
Float64 clamp(Float64 value, Float64 lo, Float64 hi)
{
    return fmax(lo, fmin(value, hi));
}
Float64 lerp(Float64 a, Float64 b, Float64 t)
{
    return (a + ((b - a) * t));
}
/* module app:greetings */
/* use app:utils */
Str greeting(Void)
{
    return normalize("hello from functions");
}
/* module app:main */
/* use app:greetings */
Int32 main(Void)
{
    Str message = greeting();
    print("%s\n", message);
    return 0;
}
/* module app:utils */
Str normalize(Str text)
{
    return text;
}
