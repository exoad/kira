package net.exoad.kira.utils

object EnglishUtils {
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

    private val cardinalWords = mapOf(
        0 to "zero",
        1 to "one",
        2 to "two",
        3 to "three",
        4 to "four",
        5 to "five",
        6 to "six",
        7 to "seven",
        8 to "eight",
        9 to "nine"
    )

    /**
     * If [lazy] is set to `true` (default `false`), it will mean that this function will make its decision using only the first character. Otherwise,
     * it will scan until it finds an alphanumeric character. If it finds a digit character, it will map it to its cardinal word/representation.
     */
    fun prependIndefiniteArticle(next: String, uppercase: Boolean = false, lazy: Boolean = false): String {
        require(next.isNotEmpty()) { "affixIndefiniteArticle requires at least one character trailing!" }
        fun lazy(target: Char = next.first()): String {
            return if (isVowel(target)) "${if (uppercase) "An" else "an"} $next" else "${if (uppercase) "A" else "a"} $next"
        }
        if (lazy || isVowel(next.first())) {
            return lazy()
        }
        for (char in next) {
            if (char.isDigit()) {
                return lazy(cardinalWords[char.digitToInt()]!!.first())
            } else if (char.isLetter()) {
                return lazy(char)
            }
        }
        return if (uppercase) "A $next" else "a $next"
    }
}