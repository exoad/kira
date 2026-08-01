package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `generate()` (the disk-writing path the CLI uses) must emit minified +
 * obfuscated output by default, while `emitToString()` (used by the other
 * tests) stays readable. These tests pin the minified shape and verify the
 * minified artifact still builds / runs.
 */
class MinifiedCodegenTest {
    companion object {
        private var node: String? = null
        private var cc: String? = null

        @JvmStatic
        @BeforeAll
        fun locateToolchains() {
            node = TestCompileSupport.findNode()
            cc = TestCompileSupport.findCCompiler()
        }
    }

    private fun compile(body: String, uri: String): TestCompileSupport.FrontendCompilationResult {
        return TestCompileSupport.compileSnippet(
            TestCompileSupport.wrapModule(uri, body),
            TestCompileSupport.logicalPathForModule(uri)
        )
    }

    private fun withMinify(block: () -> Unit) {
        val previous = GeneratedProvider.minifyOutput
        GeneratedProvider.minifyOutput = true
        try {
            block()
        } finally {
            GeneratedProvider.minifyOutput = previous
        }
    }

    @Test
    fun cGenerateWritesMinifiedObfuscatedOutput() {
        val c = cc ?: return
        withMinify {
            val result = compile(
                """
                fx add: (a: Int32, b: Int32) Int32 {
                    return a + b
                }

                fx main: () Void {
                    trace(add(20, 10))
                }
                """,
                "test:min.c"
            )
            val file = File.createTempFile("kira-min-c", ".c")
            try {
                KiraCCodeGenerator(result.compilationUnit).generate(file.path)
                val text = file.readText()
                // The runtime prelude stays readable and byte-identical (its
                // end marker is what regenerate.sh splits on).
                assertTrue(text.contains("#endif /* KIRA_RUNTIME_H */"), text)
                val marker = "#endif /* KIRA_RUNTIME_H */"
                val user = text.substring(text.indexOf(marker) + marker.length)
                // The user layer is minified: no comments, no indentation, and
                // the user function name `add` is gone.
                assertFalse(user.contains("/* module"), user)
                assertFalse(user.contains("\n    "), user)
                assertFalse(user.contains("Int32 add(Int32"), user)
                assertTrue(user.contains("main"), user)
                // The minified unit still compiles and behaves identically.
                val ran = TestCompileSupport.compileAndRunC(text, c)
                assertEquals(0, ran.compileResult.exitCode, ran.compileResult.stderr)
                assertEquals("30\n", ran.runResult?.stdout, ran.runResult?.stderr)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun jsGenerateWritesMinifiedObfuscatedOutput() {
        val n = node ?: return
        withMinify {
            val result = compile(
                """
                fx main: () Void {
                    trace("hello, kira")
                }
                """,
                "test:min.js"
            )
            val file = File.createTempFile("kira-min-js", ".js")
            try {
                KiraJSCodeGenerator(result.compilationUnit).generate(file.path)
                val text = file.readText()
                assertTrue(text.contains("__KIRA_JS_PRELUDE_END__"), text)
                val marker = "// __KIRA_JS_PRELUDE_END__"
                val user = text.substring(text.indexOf(marker) + marker.length)
                assertFalse(user.contains("// module"), user)
                assertFalse(user.contains("\n  "), user)
                assertTrue(user.contains("main"), user)
                val ran = TestCompileSupport.runJS(text, n)
                assertEquals(0, ran.exitCode, ran.stderr)
                assertEquals("hello, kira\n", ran.stdout, ran.stderr)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun generateHonorsMinifyOff() {
        val previous = GeneratedProvider.minifyOutput
        GeneratedProvider.minifyOutput = false
        try {
            val result = compile(
                """
                fx main: () Void {
                    trace("hi")
                }
                """,
                "test:min.off"
            )
            val file = File.createTempFile("kira-readable-c", ".c")
            try {
                KiraCCodeGenerator(result.compilationUnit).generate(file.path)
                val text = file.readText()
                assertTrue(text.contains("/* module"), text)
            } finally {
                file.delete()
            }
        } finally {
            GeneratedProvider.minifyOutput = previous
        }
    }
}
