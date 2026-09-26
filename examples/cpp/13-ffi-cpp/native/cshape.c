#include "cshape.h"

#include <string.h>

int32_t cshape_hypot_sq(int32_t a, int32_t b)
{
    return a * a + b * b;
}

size_t cshape_len(const char* s)
{
    return s == NULL ? 0 : strlen(s);
}

void cshape_count(int32_t* out, int32_t n)
{
    int32_t i;
    *out = 0;
    for (i = 0; i < n; ++i) {
        *out += 1;
    }
}
