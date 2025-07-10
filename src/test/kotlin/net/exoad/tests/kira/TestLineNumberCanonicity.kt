package net.exoad.tests.kira

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.KiraPreprocessor
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestLineNumberCanonicity
{
    @Test
    fun test1()
    {
        val src = """
        module "kira:main";
        // hello world!
        //Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu 
        pub mut h: Int32 = 123 //fugiat nulla pariatur. Excepteur sint occ
        //aecat 
        x: Int32 = 123
        //cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
        """.trimIndent()
        val preprocessor = KiraPreprocessor(src)
        val result = assertDoesNotThrow { preprocessor.process() }
        assertTrue { result.processedContent.split("\n")[1].isEmpty() }
    }
}