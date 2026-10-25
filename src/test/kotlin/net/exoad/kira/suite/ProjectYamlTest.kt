package net.exoad.kira.suite

import net.exoad.kira.dialect.ProjectYaml
import net.exoad.kira.dialect.Stage5
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ProjectKira
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 5: kira-dialect stage5 writes each kira.yaml as the project.kira that replaces it, proved field by field. */
class ProjectYamlTest {
    // bibo's root kira.yaml at f1874f4, its comment block shortened.
    private val root = """
        project: { name: bibo }
        srcDir: .
        # vendor holds clones; a .kira in one would be read as this tree's module.
        srcExclude: [build, third_party, "**/build", viewer/assets, tools/dbw]
        build:
          target: cpp
          cpp:
            layout: beside
            runtimeDir: firmware/lib           # the runtime lands in firmware/lib/kira/
            lineDirectives: true
            # A module's namespace is the last segment of its URI.
            #   "firmware:lib.text": bibo::text
            namespaces: {}
            headerOnly: ["firmware:lib.**", "firmware:pilot.src.{scan,speed}"]   # URIs are strings
            freestanding: ["firmware:lib.**"]
        compiler: { types: strict }
        dependencies:
          kira_stdlib: { path: build/kira-toolchain/kira }   # kira_gen.py checks out tools/kira.lock here
    """.trimIndent() + "\n"

    @Test
    fun bibosRootKiraYamlAsProjectKira() {
        val r = ProjectYaml.convert(root)
        assertNull(r.problem, r.problem)
        assertEquals(
            """
            PROJECT: Project = Project { } with {
                name = "bibo"
                srcDir = "."
                // vendor holds clones; a .kira in one would be read as this tree's module.
                srcExclude = @listOf("build", "third_party", "**/build", "viewer/assets", "tools/dbw")
                target = "cpp"
                cpp = Cpp {
                    layout = "beside",
                    runtimeDir = "firmware/lib", // the runtime lands in firmware/lib/kira/
                    lineDirectives = true,
                    // A module's namespace is the last segment of its URI.
                    //   "firmware:lib.text": bibo::text
                    namespaces = Map<Str, Str> { },
                    headerOnly = @listOf("firmware:lib.**", "firmware:pilot.src.{scan,speed}"), // URIs are strings
                    freestanding = @listOf("firmware:lib.**")
                }
                types = "strict"
                dependencies = @mapOf("kira_stdlib", "build/kira-toolchain/kira") // kira_gen.py checks out tools/kira.lock here
            }
            """.trimIndent() + "\n",
            r.text,
        )
        assertEquals(ManifestLoader.parse(root), ProjectKira.parse(r.text!!))
        // It types as any code: a `with` inside the constant's value would not, so the cpp block is one construction.
        val stdlib = File("kira").absoluteFile.listFiles()!!.filter { it.extension == "kira" }.map { it.path }
        assertEquals(emptyList(), ProjectKira.check(Files.createTempDirectory("stage5_root").resolve(ProjectKira.FILE), stdlib, r.text))
    }

    @Test
    fun aHeadCommentStaysAtTheTopAndSnakeCaseKeysRead() {
        val yaml = "# The tool's Kira.\nproject:\n  name: tool\nbuild:\n  target: c\n  c_sources:\n    - native/a.c\n  link_flags: []\ndependencies:\n  kira_stdlib:\n    path: ../kira\n"
        val r = ProjectYaml.convert(yaml)
        assertEquals(
            "// The tool's Kira.\nPROJECT: Project = Project { } with {\n    name = \"tool\"\n    target = \"c\"\n    cSources = @listOf(\"native/a.c\")\n" +
                "    linkFlags = List<Str> { }\n    dependencies = @mapOf(\"kira_stdlib\", \"../kira\")\n}\n",
            r.text,
        )
    }

    @Test
    fun aYamlTheKiraCannotSayIsNotConverted() {
        // An empty runtimeDir is a value kira.yaml keeps and project.kira reads as none: not the same.
        val r = ProjectYaml.convert("project:\n  name: p\nbuild:\n  cpp:\n    runtimeDir: \"\"\n")
        assertNull(r.text)
        assertTrue(r.problem!!.contains("the configurations differ"), r.problem)
        // A string holding `${` and a backslash is escaped.
        assertEquals("\"a\\\\b \\\${x}\"", ProjectYaml.str("a\\b \${x}"))
        assertEquals("a\\b \${x}", ProjectKira.parse("PROJECT: Project = Project { } with {\n    name = ${ProjectYaml.str("a\\b \${x}")}\n}\n").project.name)
    }

    private fun <T> captured(block: () -> T): Pair<T, String> {
        val saved = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, Charsets.UTF_8))
        try {
            return block() to buf.toString(Charsets.UTF_8)
        } finally {
            System.setOut(saved)
        }
    }

    @Test
    fun theVerbWritesProjectKiraAndRetiresKiraYaml() {
        val dir = Files.createTempDirectory("stage5_yaml").toFile()
        File(dir, "kira.yaml").writeText(root)
        File(dir, "build").mkdirs()
        File(dir, "build/kira.yaml").writeText("project: { name: skipped }\n")
        val (checked, out) = captured { Stage5.convertAll(listOf(dir.path), check = true, keepYaml = false) }
        assertEquals(1, checked, out)
        assertTrue(File(dir, "kira.yaml").isFile && !File(dir, "project.kira").exists(), out)
        val (kept, _) = captured { Stage5.convertAll(listOf(dir.path), check = false, keepYaml = true) }
        assertEquals(0, kept)
        assertTrue(File(dir, "kira.yaml").isFile && File(dir, "project.kira").isFile)
        // With project.kira beside it, a second run leaves both as they are.
        File(dir, "project.kira").delete()
        val (done, out2) = captured { Stage5.convertAll(listOf(dir.path), check = false, keepYaml = false) }
        assertEquals(0, done, out2)
        assertTrue(!File(dir, "kira.yaml").exists() && File(dir, "project.kira").readText().startsWith("PROJECT: Project = Project { } with {"))
        assertTrue(File(dir, "build/kira.yaml").isFile, "a build directory is never converted")
    }
}
