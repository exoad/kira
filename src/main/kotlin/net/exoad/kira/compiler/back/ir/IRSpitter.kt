package net.exoad.kira.compiler.back.ir

// TODO: in triage
class IRSpitter

fun max(l: List<Any>): List<Int>
{
    @Suppress("UNCHECKED_CAST") return l.filter { it is Int } as List<Int>
}