#include "mini_gfx.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct MiniSurface {
    int w;
    int h;
    char title[64];
    int closed;
};

MiniSurface* miniCreate(int w, int h, const char* title) {
    MiniSurface* s = (MiniSurface*)malloc(sizeof(MiniSurface));
    if (!s) return NULL;
    s->w = w;
    s->h = h;
    s->closed = 0;
    if (title) {
        strncpy(s->title, title, sizeof(s->title) - 1);
        s->title[sizeof(s->title) - 1] = '\0';
    } else {
        s->title[0] = '\0';
    }
    return s;
}

int miniClosed(MiniSurface* s) {
    return s ? s->closed : 1;
}

void miniClear(MiniSurface* s, int color) {
    (void)s;
    (void)color;
    /* no-op for headless stub */
}

void miniPresent(MiniSurface* s) {
    if (!s) return;
    /* one-shot demo: mark closed after first present */
    printf("mini: %dx%d \"%s\"\n", s->w, s->h, s->title);
    s->closed = 1;
}

void miniDestroy(MiniSurface* s) {
    free(s);
}
