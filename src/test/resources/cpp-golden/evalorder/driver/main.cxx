// evalorder: the program is the check. Its own main (evalorder.kira.cxx, through
// kira::rt::runMain) traces what Kira's left-to-right order computes, and
// expected.txt holds those values: every toolchain must print exactly them (D33,
// R19). This file defines no main; it proves the generated header stands alone.
#include "src/app/evalorder.kira.hxx"
