#include <math.h>
Float64 f(Float64 value,Float64 k,Float64 g);Float64 j(Float64 a,Float64 b,Float64 t);Int32 main(Void);Float64 f(Float64 value,Float64 k,Float64 g){return fmax(k,fmin(value,g));}Float64 j(Float64 a,Float64 b,Float64 t){return(a+((b-a)*t));}Int32 main(Void){print("%s\n","hello, kira");return 0;}
