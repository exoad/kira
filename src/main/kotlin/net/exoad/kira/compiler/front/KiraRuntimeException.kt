package net.exoad.kira.compiler.front

import java.lang.RuntimeException

class KiraRuntimeException(override val message: String, override val cause: Throwable? = null) :
    RuntimeException(message, cause)
{
}