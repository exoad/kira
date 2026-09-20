package net.exoad.kira.compiler.analysis.semantic

class KiraRuntimeException(override val message: String, override val cause: Throwable? = null) :
    RuntimeException(message, cause)