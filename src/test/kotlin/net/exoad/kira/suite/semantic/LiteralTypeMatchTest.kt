package net.exoad.kira.suite.semantic

import net.exoad.kira.suite.semantic.SemanticTestSupport.assertHealthy
import net.exoad.kira.suite.semantic.SemanticTestSupport.assertUnhealthy
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Semantic analysis: a literal must match the type it initializes. */
class LiteralTypeMatchTest {
    @Test
    fun rejectsIntegerLiteralForStringType() {
        val msgs = assertUnhealthy(
            """
            s: Str { 42 }
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun rejectsStringLiteralForIntType() {
        val msgs = assertUnhealthy(
            """
            n: Int32 { "hello" }
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun rejectsFloatLiteralForIntType() {
        val msgs = assertUnhealthy(
            """
            n: Int32 { 1.5 }
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun acceptsIntegerLiteralForEveryStdlibIntegerType() {
        // The stdlib declares the unsigned integers and Size (kira/core.kira); the golden
        // corpus initialises them from literals (`MAX_PAYLOAD: Size = 1024`).
        assertHealthy(
            """
            a: Int8 { 1 }
            b: Int16 { 2 }
            c: Int32 { 3 }
            d: Int64 { 4 }
            e: UInt8 { 5 }
            f: UInt16 { 6 }
            g: UInt32 { 7 }
            h: UInt64 { 8 }
            i: Size { 9 }
            """
        )
    }

    @Test
    fun rejectsIntegerLiteralForFloatAndCharTypes() {
        for (type in listOf("Float32", "Float64", "Char", "Bool")) {
            val msgs = assertUnhealthy(
                """
                x: $type { 1 }
                """
            )
            assertTrue(msgs.any { it.contains("Type mismatch") }, "$type: $msgs")
        }
    }
}
