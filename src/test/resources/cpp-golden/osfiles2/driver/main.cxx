// osfiles2: a Kira program of kira:os's files with its own main (osfiles2.kira.cxx, through kira::rt::runMain), its
// header included here on its own; osrt.cxx compiles kira/os.cxx in, as the CLI's tree does.
#include "../expected/src/app/osfiles2.kira.hxx"

#include <type_traits>

static_assert(std::is_same_v<decltype(osfiles2::head("", 0)), kira::Maybe<kira::List<std::uint8_t>>>, "readFileHead is a Maybe of bytes");
static_assert(std::is_same_v<decltype(osfiles2::changed("")), kira::Maybe<double>>, "modifiedS is a Maybe of a Float64");
static_assert(std::is_same_v<decltype(kira::os::openAppend("")), kira::Rc<kira::os::FileOut>>, "openAppend's none is a null Rc");
