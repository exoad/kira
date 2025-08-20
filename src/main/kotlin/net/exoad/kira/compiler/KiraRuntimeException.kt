package net.exoad.kira.compiler

class KiraRuntimeException(override val message: String, override val cause: Throwable? = null) :
    RuntimeException(message, cause)
{
}