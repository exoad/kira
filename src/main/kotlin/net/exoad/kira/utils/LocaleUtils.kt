package net.exoad.kira.utils

object LocaleUtils {
    fun getPluralSuffix(count: Int, uppercase: Boolean = false): String {
        return if (count > 1) {
            if (uppercase) "S" else "s"
        } else ""
    }

    fun getPossessivePluralSuffix(count: Int, uppercase: Boolean = false, useSmartQuotes: Boolean = false): String {
        return if (count > 1) {
            val quote = if (useSmartQuotes) "’" else "'"
            if (uppercase) "${quote}S" else "${quote}s"
        } else ""
    }

    private val vowelCodes = setOf(65, 69, 73, 79, 85, 97, 101, 105, 111, 117)
    fun isVowel(char: Char): Boolean {
        return char.code in vowelCodes
    }

    fun prependIndefiniteArticle(next: String, uppercase: Boolean = false): String {
        require(next.isNotEmpty()) { "affixIndefiniteArticle requires at least one character trailing!" }
        return if (isVowel(next.first())) "${if (uppercase) "An" else "an"} $next" else "${if (uppercase) "A" else "a"} $next"
    }
}