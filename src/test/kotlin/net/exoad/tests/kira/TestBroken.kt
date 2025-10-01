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

}