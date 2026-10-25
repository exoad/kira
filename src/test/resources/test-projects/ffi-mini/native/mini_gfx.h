#ifndef MINI_GFX_H
#define MINI_GFX_H

typedef struct MiniSurface MiniSurface;

MiniSurface* miniCreate(int w, int h, const char* title);
int miniClosed(MiniSurface* s);
void miniClear(MiniSurface* s, int color);
void miniPresent(MiniSurface* s);
void miniDestroy(MiniSurface* s);

#endif
