package net.exoad.tests.kira

import net.exoad.kira.compiler.KiraImmediateCompiler
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertNotNull

class TestBroken {
    @Test
    fun testVariadicsAreNotAllowed() {
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                
                A<[T]>: Any
            """.trimIndent()
            )
        }
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                
                A<[T: A]>: Any
                """.trimIndent()
            )
        }
    }


    @Test
    fun testUnderscoresInNormalIdentifiersNotAllowed() {
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                
                A_T: Any {}

                a_b: Int32 = 123
            """.trimIndent()
            )
        }
    }

    @Test
    fun testUnderscoresAllowedInIntrinsincs() {
        val ast = KiraImmediateCompiler.formAST(
            """
            @op_add(1, 2)
            """.trimIndent()
        )
        assertNotNull(ast)
    }
}
