package net.exoad.kira.utils

object LocaleUtils
{
    fun getPluralSuffix(count: Int, uppercase: Boolean = false): String
    {
        return if(count > 1)
        {
            if(uppercase) "S" else "s"
        }
        else ""
    }

    fun getPossessivePluralSuffix(count: Int, uppercase: Boolean = false, useSmartQuotes: Boolean = false): String
    {
        return if(count > 1)
        {
            val quote = if(useSmartQuotes) "’" else "'"
            if(uppercase) "${quote}S" else "${quote}s"
        }
        else ""
    }
}