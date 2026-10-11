// utcstamp: kira:time's utcStamp in a program with its own main (utcstamp.kira.cxx, through kira::rt::runMain),
// its header included here on its own.
#include "../expected/src/app/utcstamp.kira.hxx"

#include <type_traits>

static_assert(std::is_same_v<decltype(kira::time::utcStamp(0)), kira::Str>, "utcStamp is a Str");
