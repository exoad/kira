package net.exoad.kira.bootstrap.lang

import net.exoad.kira.bootstrap.KiraCannotInstantiate
import net.exoad.kira.bootstrap.KiraClass
import net.exoad.kira.bootstrap.KiraSugarLiteralInstantiate
import net.exoad.kira.compiler.front.elements.StringLiteral

@KiraClass("Any")
open class KAny
{
    override fun toString(): String
    {
        return "KAny#${hashCode()}"
    }
}