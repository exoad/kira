package net.exoad.kira.bootstrap.lang

import net.exoad.kira.bootstrap.KiraClass

@KiraClass("Any")
open class KAny
{
    override fun toString(): String
    {
        return "KAny#${hashCode()}"
    }
}