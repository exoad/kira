package net.exoad.kira

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A call to a member written in Kira on a magic class the program declares is refused on every target: no runtime
 * backs such a type, so there is no representation to lower the body over. The refusal exits the process, so each
 * run is a child JVM on this test's classpath.
 */
class BodiedMagicMemberRefusalTest {

    private fun project(): File {
        val dir = Files.createTempDirectory("kira-magic-refusal").toFile()
        val stdlib = File("kira").absolutePath.replace('\\', '/')
        File(dir, "project.kira").writeText(
            """
            PROJECT: Project { } with {
                name = "magicrefusal"
                srcDir = "src"
                target = "c"
                types = "strict"
                dependencies = @mapOf("kira_stdlib", "$stdlib")
            }
            """.trimIndent() + "\n",
        )
        File(dir, "src").mkdirs()
        File(dir, "src/main.kira").writeText(
            """
            pub @_magic Probe: Any {
                pub twice: (x: Int32) Int32 {
                    x * 2
                }
            }

            main: () Void {
                p: Probe { }
                n: Int32 { p.twice(3) }
            }
            """.trimIndent() + "\n",
        )
        return dir
    }

    private fun run(dir: File, target: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "net.exoad.kira.cli.MainKt",
            "--target", target, "--out", File(dir, "out-$target").absolutePath,
        ).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun cRefusesACallToABodiedMemberOfAProgramsMagicType() {
        val (code, output) = run(project(), "c")
        assertEquals(1, code, output)
        assertTrue(output.contains("a method written in Kira on the magic type 'Probe' ('twice') is not supported by the C backend"), output)
    }

    @Test
    fun jsRefusesACallToABodiedMemberOfAProgramsMagicType() {
        val (code, output) = run(project(), "js")
        assertEquals(1, code, output)
        assertTrue(output.contains("a method written in Kira on the magic type 'Probe' ('twice'), a type the program declares, which no runtime backs"), output)
    }

    @Test
    fun pyRefusesACallToABodiedMemberOfAProgramsMagicType() {
        val (code, output) = run(project(), "py")
        assertEquals(1, code, output)
        assertTrue(output.contains("a method written in Kira on the magic type 'Probe' ('twice'), a type the program declares, which no runtime backs"), output)
    }

    @Test
    fun cppRefusesACallToABodiedMemberOfAProgramsMagicType() {
        val (code, output) = run(project(), "cpp")
        assertEquals(1, code, output)
        assertTrue(output.contains("a method written in Kira on the magic type 'Probe' ('twice'), a type the program declares, which no runtime backs"), output)
    }
}
