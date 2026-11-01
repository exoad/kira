package net.exoad.kira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The C and JS backends refuse a call to a member written in Kira on a magic class: the index names those members. */
class BodiedMagicMemberIndexTest {

    @Test
    fun aBodiedMemberOfAMagicClassIsIndexedAndABodilessOneIsNot() {
        val result = TestCompileSupport.compileSnippet(
            """
            pub @_magic Probe: Any {
                pub twice: (x: Int32) Int32 {
                    x * 2
                }
                pub size: (x: Int32) Int32
            }
            """.trimIndent() + "\n",
            TestCompileSupport.logicalPathForModule("test:magic.index"),
            true,
        )
        assertTrue(result.compilationUnit.isBodiedMagicMember("Probe", "twice"))
        assertFalse(result.compilationUnit.isBodiedMagicMember("Probe", "size"))
    }
}
