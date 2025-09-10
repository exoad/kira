package net.exoad.kira

import kotlin.properties.Delegates

object Public {

    val flagsDefault = mapOf(
        "useDiagnosticsUnicode" to true,
        "beVerbose" to false,
        "enableVisualView" to false,
    )

    var flags by Delegates.notNull<Map<String, Boolean>>()

    object Builtin {
        val intrinsicalStandardLibrarySources = arrayOf("kira/types.kira")
    }
}