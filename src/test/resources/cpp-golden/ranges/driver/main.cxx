// ranges: a program with its own main (ranges.kira.cxx, through kira::rt::runMain); its header and
// kira:range's stand alone here, and a Range is a plain value.
#include "../expected/src/app/ranges.kira.hxx"

#include <type_traits>

static_assert(std::is_trivially_copyable_v<kira::range::Range<std::int32_t>>, "a Range is a plain value");
