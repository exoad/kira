package net.exoad.tests.kira

import net.exoad.kira.compiler.KiraImmediateCompiler
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

class TestBroken {
    @Test
    fun testVariadicsAreNotAllowed() {
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                module "test:mod/submod"

                class A<[T]>
            """.trimIndent()
            )
        }
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                module "test:mod/submod"

                class A<[T: A]>
                """.trimIndent()
            )
        }
    }


    @Test
    fun testUnderscoresInNormalIdentifiersNotAllowed() {
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                module "test:mod/submod"

                class A_T {}

                a_b: Int32 = 123
            """.trimIndent()
            )
        }
    }

    @Test
    fun testUnderscoresAllowedInIntrinsincs() {
        assertThrows<Throwable> {
            KiraImmediateCompiler.formAST(
                """
                module "test:mod/submod"
                
                @__dummy__("Hello World")
                """.trimIndent()
            )
        }
    }
}