package net.exoad.kira.types

import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `kira --fix-for --typed` and `kira --fix-with` as the CLI runs them, in a project of their own. */
class FixCliTest {
    private fun project(dir: File, target: String, vararg files: Pair<String, String>) {
        val stdlib = File(PyTestSupport.repoRoot, "kira").absolutePath.replace('\\', '/')
        File(dir, "project.kira").writeText(
            "PROJECT: Project { } with {\n    name = \"fix.cli\"\n    srcDir = \"src\"\n    target = \"$target\"\n    types = \"strict\"\n" +
                "    dependencies = @mapOf(\"kira_stdlib\", \"$stdlib\")\n}\n",
        )
        files.forEach { (name, text) -> File(dir, "src/$name").also { it.parentFile.mkdirs() }.writeText(text) }
    }

    private val loops = "names: () List<Str> {\n    @listOf(\"a\")\n}\n\nf: () Int32 {\n    t: Mutable<Int32> { 0 }\n    for s: Str in names() {\n        t += s.length() as Int32\n    }\n    t\n}\n"

    @Test
    fun theTypedForFixRewritesAFileOfAProjectAndASecondRunChangesNothing(@TempDir dir: File) {
        project(dir, "py", "main.kira" to loops)
        val file = File(dir, "src/main.kira")
        val ran = PyTestSupport.cli(dir, "--fix-for", "--typed", file.absolutePath)
        assertEquals(0, ran.exitCode, ran.all)
        assertTrue(ran.stdout.contains("fixed 1, left 0"), ran.all)
        val text = file.readText()
        assertTrue(text.contains("sItems: List<Str> { names() }") && text.contains("while sIndex < sItems.size() {"), text)
        assertFalse(Regex("(?m)^\\s*for ").containsMatchIn(text), text)
        val again = PyTestSupport.cli(dir, "--fix-for", "--typed", file.absolutePath)
        assertTrue(again.stdout.contains("fixed 0, left 0"), again.all)
        assertEquals(text, file.readText())
    }

    private val runs = "Counter: Mutable<Any> {\n    pub frames: Mutable<Int64> { 0 }\n\n    pub count: () Void {\n        frames += 1\n    }\n\n    pub reset: (to: Int64) Void {\n        frames = to\n    }\n}\n\n" +
        "main: () Void {\n    c: Counter { }\n    c.count()\n    c.reset(3)\n    trace(c.frames)\n}\n"

    @Test
    fun theWithFixFoldsInAPyProjectCountsOnADryRunAndRefusesAProjectOfAnotherTarget(@TempDir dir: File, @TempDir other: File) {
        project(dir, "py", "main.kira" to runs)
        val file = File(dir, "src/main.kira")
        val dry = PyTestSupport.cli(dir, "--fix-with", "--dry-run", file.absolutePath)
        assertTrue(dry.stdout.contains("would fold 1"), dry.all)
        assertEquals(runs, file.readText(), "a dry run writes nothing")
        val ran = PyTestSupport.cli(dir, "--fix-with", file.absolutePath)
        assertTrue(ran.stdout.contains("folded 1"), ran.all)
        assertTrue(file.readText().contains("    c with {\n        count()\n        reset(3)\n    }\n"), file.readText())
        assertTrue(PyTestSupport.cli(dir, "--fix-with", file.absolutePath).stdout.contains("folded 0"))

        project(other, "c", "main.kira" to runs)
        val refused = PyTestSupport.cli(other, "--fix-with", File(other, "src/main.kira").absolutePath)
        assertTrue(refused.stdout.contains("does not lower a `with` (py and cpp do)"), refused.all)
        assertEquals(runs, File(other, "src/main.kira").readText())
    }

    @Test
    fun theWithFixFoldsInACppProjectToo(@TempDir dir: File) {
        project(dir, "cpp", "main.kira" to runs)
        val file = File(dir, "src/main.kira")
        val ran = PyTestSupport.cli(dir, "--fix-with", file.absolutePath)
        assertTrue(ran.stdout.contains("folded 1"), ran.all)
        assertTrue(file.readText().contains("    c with {\n        count()\n        reset(3)\n    }\n"), file.readText())
        assertTrue(PyTestSupport.cli(dir, "--fix-with", file.absolutePath).stdout.contains("folded 0"))
    }
}
