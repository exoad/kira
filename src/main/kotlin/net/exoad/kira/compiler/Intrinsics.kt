package net.exoad.kira.compiler

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.frontend.AbsoluteFileLocation

data class Intrinsic(val intrinsicKey: Builtin.Intrinsics, val absoluteFileLocation: AbsoluteFileLocation)
