// mathplus: kira:math's hypot, atan2, exp and floorMod in a program with its own main (mathplus.kira.cxx, through
// kira::rt::runMain), its header included here on its own; floorMod serves a Float32 as well.
#include "../expected/src/app/mathplus.kira.hxx"

#include "kira/math.hxx"

#include <type_traits>

static_assert(std::is_same_v<decltype(kira::floorMod(1.0f, 2.0f)), float>, "floorMod of a Float32 is one");
