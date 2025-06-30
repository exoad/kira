package net.exoad.kira

import kotlin.properties.Delegates

object Public
{
    object Flags
    {
        var useDiagnosticsUnicode by Delegates.notNull<Boolean>()
        var beVerbose by Delegates.notNull<Boolean>()
    }
}