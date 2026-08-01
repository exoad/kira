package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.MinifyLanguage
import net.exoad.kira.compiler.backend.codegen.OutputMinifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputMinifierTest {

    @Test
    fun stripsCCommentsAndInsignificantWhitespace() {
        val src = """
            /* module app:main */
            Int32 main(Void)
            {
                print("%s\n", "hello, kira");  // tail comment
                return 0;
            }
        """.trimIndent()
        val out = OutputMinifier.minify(MinifyLanguage.C, src)
        assertFalse(out.contains("/*"), out)
        assertFalse(out.contains("//"), out)
        assertFalse(out.contains("\n    "), out)
        assertEquals("Int32 main(Void){print(\"%s\\n\",\"hello, kira\");return 0;}\n", out)
    }

    @Test
    fun renamesIdentifiersConsistently() {
        val src = """
            Int32 x = 10;
            Int32 add(Int32 a, Int32 b) { return a + b; }
            Int32 z = add(x, 1);
        """.trimIndent()
        val rename = OutputMinifier.buildRenameMap(listOf("x", "add"), setOf())
        // Sorted: add -> a (pool[0]), x -> b (pool[1]).
        assertEquals(mapOf("add" to "a", "x" to "b"), rename)
        val out = OutputMinifier.minify(MinifyLanguage.C, src, rename)
        assertEquals("Int32 b=10;Int32 a(Int32 a,Int32 b){return a+b;}Int32 z=a(b,1);\n", out)
    }

    @Test
    fun preservesCPreprocessorLines() {
        val src = """
            #include <math.h>
            Int32 main(Void) { return 0; }
        """.trimIndent()
        val out = OutputMinifier.minify(MinifyLanguage.C, src)
        assertTrue(out.contains("#include <math.h>"), out)
        assertTrue(out.contains("Int32 main(Void)"), out)
    }

    @Test
    fun neverMergesTokens() {
        // a - -b must stay two minus tokens (not --).
        assertEquals("a- -b;\n", OutputMinifier.minify(MinifyLanguage.C, "a - -b;"))
        // a / *p must not become a comment start.
        assertEquals("a/ *p;\n", OutputMinifier.minify(MinifyLanguage.C, "a / *p;"))
        // ++ on either side of a plus stays separate.
        assertEquals("a+ ++b;\n", OutputMinifier.minify(MinifyLanguage.C, "a + ++b;"))
        // Keyword + identifier must keep a space.
        assertEquals("return x;\n", OutputMinifier.minify(MinifyLanguage.C, "return x;"))
        // Number + identifier must keep a space.
        assertEquals("return 1;\n", OutputMinifier.minify(MinifyLanguage.C, "return 1;"))
        // Member access stays glued.
        assertEquals("self->field;\n", OutputMinifier.minify(MinifyLanguage.C, "self->field;"))
    }

    @Test
    fun keepsAdjacentJsStringsSeparated() {
        // Two string expressions need whitespace between them in JS.
        assertEquals("trace(\"a\" \"b\");\n", OutputMinifier.minify(MinifyLanguage.JS, "trace(\"a\" \"b\");"))
        // Template literals are kept opaque.
        assertEquals("const s=`x \${y} z`;\n", OutputMinifier.minify(MinifyLanguage.JS, "const s = `x \${y} z`;"))
    }

    @Test
    fun renamesJsIdentifiersAndKeepsGlobals() {
        val src = "function clamp(value, lo, hi) { return Math.max(lo, Math.min(value, hi)); }"
        val rename = OutputMinifier.buildRenameMap(listOf("clamp"), setOf("Math", "max", "min"))
        assertEquals(mapOf("clamp" to "a"), rename)
        val out = OutputMinifier.minify(MinifyLanguage.JS, src, rename)
        assertEquals("function a(value,lo,hi){return Math.max(lo,Math.min(value,hi));}\n", out)
    }

    @Test
    fun respectsReservedNamesAndMain() {
        val rename = OutputMinifier.buildRenameMap(
            listOf("main", "count", "x"),
            setOf("count", "Int32", "print")
        )
        // main is never renamed; count is reserved; only x is renamed.
        assertEquals(mapOf("x" to "a"), rename)
    }

    @Test
    fun isDeterministic() {
        val src = """
            fx greet: (name: Str) Str { return "hi " + name }
            fx main: () Void { trace(greet("kira")) }
        """.trimIndent()
        val rename = OutputMinifier.buildRenameMap(listOf("greet", "name"), setOf("Str", "Void", "main", "trace"))
        val first = OutputMinifier.minify(MinifyLanguage.JS, src, rename)
        val second = OutputMinifier.minify(MinifyLanguage.JS, src, rename)
        assertEquals(first, second)
    }

    @Test
    fun extractIdentifiersFindsPreludeSymbols() {
        val ids = OutputMinifier.extractIdentifiers("function kira_print(s) { process.stdout.write(s); }")
        assertTrue("kira_print" in ids)
        assertTrue("process" in ids)
        assertTrue("stdout" in ids)
    }
}
