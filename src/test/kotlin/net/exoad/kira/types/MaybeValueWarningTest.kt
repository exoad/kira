package net.exoad.kira.types

import kotlin.test.Test
import kotlin.test.assertEquals

/** `value` of a Maybe is a warning unless a test before it showed the Maybe holds a value. */
class MaybeValueWarningTest {

    private fun codes(body: String): List<String> {
        val p = TyperTestSupport.snippet("pub f: (m: Maybe<Int32>) Int32 {\n$body\n}\n")
        return p.diagnostics.map { it.code }
    }

    @Test
    fun aBareValueWarns() {
        assertEquals(listOf("types.maybe.unchecked-value"), codes("    m.value"))
    }

    @Test
    fun aValueUnderIsSomeIsQuiet() {
        assertEquals(emptyList(), codes("    if m.isSome() {\n        m.value\n    }\n    0"))
    }

    @Test
    fun aValueUnderNotIsNullIsQuiet() {
        assertEquals(emptyList(), codes("    if !m.isNull() {\n        m.value\n    }\n    0"))
    }

    @Test
    fun aValueAfterAnIsNullTestWithTheBodyNegatedIsQuiet() {
        assertEquals(emptyList(), codes("    if m.isNull() {\n        0\n    } else {\n        m.value\n    }\n    0"))
    }
}
