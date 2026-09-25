// nm-check fixture: a freestanding object that reaches a checked op with a
// runtime divisor, so it references kira::panic (the board's, design 10) and
// nothing forbidden. CppHarnessSelfTest asserts nmCheck reports it clean.
#include "kira/core.hxx"

namespace selftest
{
  ::kira::Int32 ratio(::kira::Int32 a, ::kira::Int32 b)
  {
    return ::kira::div(a, b);
  }
}
