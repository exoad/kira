#ifndef KIRA_DIAGNOSTICS_H
#define KIRA_DIAGNOSTICS_H

#include <stdio.h>
#include <stdlib.h>

#define _PANIC(msg) do { \
    fprintf(stderr, "PANIC: %s at %s:%d in %s()\n", \
            msg, __FILE__, __LINE__, __func__); \
    exit(1); \
} while(0)

#define _PANICF(fmt, ...) do  \
{ \
    fprintf(stderr, "PANIC: " fmt " at %s:%d in %s()\n", \
            __VA_ARGS__, __FILE__, __LINE__, __func__); \
    exit(1); \
} while(0)

#define _ASSERT(condition, msg) do {                          \
    if (!(condition))                                         \
    {                                                         \
        fprintf(stderr, "ASSERTION FAILED: %s at %s:%d in %s()\n", msg, __FILE__, __LINE__, __func__); \
        exit(1);                                              \
    }                                                         \
} while(0)

#define _CHECK(condition, msg) do { \
    if (!(condition)) {             \
        _PANIC(msg);                 \
    }                               \
} while(0)

#endif