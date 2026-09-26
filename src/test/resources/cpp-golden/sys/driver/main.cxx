// sys: a Kira program using spawn, Mutex.lock (D24's Mutex.with) and kira:test. The program has
// its own main (sys.kira.cxx, through kira::rt::runMain) and prints bibo's
// check format itself, so this driver defines no main: it only includes the
// generated header on its own, which proves the header is self-contained, and
// pins the constant the program counts to. The include is relative, as every
// other case's is: kira/cpp/tests/goldens.sh adds no include path for expected/.
#include "../expected/src/app/sys.kira.hxx"

static_assert(sys::N == 1000, "the golden's expected.txt counts to 1000");
