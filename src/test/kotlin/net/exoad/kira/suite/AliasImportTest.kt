package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.dialect.Stage5
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 5: an import reaches its module only through its alias or a member alias, and kira-dialect stage5 adds the aliases old text needs. */
class AliasImportTest {
    private val units = module(
        "app:units",
        """
        pub SCALE: Int32 = 100
        pub Meter: Any {
            pub n: Int32 = 0
        }
        pub bump: (v: Int32) Int32 {
            v + 1
        }
        """,
    )

    private fun main(body: String) = module("app:main", body)

    @Test
    fun aBareNameOfAnImportIsRefusedNamingBothFixes() {
        val p = TyperTestSupport.type(units, main("app.units as units\n\nf: () Int32 {\n    m: Meter = Meter { }\n    bump(SCALE) + m.n\n}"))
        val bare = p.diagnostics.filter { it.code == "types.use.bare" }
        // The type (twice), the call and the value.
        assertEquals(4, bare.size, TyperTestSupport.render(p))
        assertTrue(bare.any { it.message == "'SCALE' is not in scope: add `app.units.SCALE as SCALE` or write `units.SCALE`." }, TyperTestSupport.render(p))
        assertTrue(bare.any { it.message.startsWith("'bump' is not in scope") } && bare.any { it.message.startsWith("'Meter' is not in scope") })
    }

    @Test
    fun aMemberAliasOrTheQualifiedNameReachesIt() {
        expectNoErrors(TyperTestSupport.type(units, main("app.units as units\napp.units.bump as bump\napp.units.Meter as Meter\n\nf: () Int32 {\n    m: Meter = Meter { }\n    bump(units.SCALE) + m.n\n}")))
        // A member alias may name the thing anew.
        expectNoErrors(TyperTestSupport.type(units, main("app.units.bump as next\n\nf: () Int32 {\n    next(1)\n}")))
    }

    @Test
    fun theStdlibIsInScopeBareImportedOrNot() {
        expectNoErrors(TyperTestSupport.snippet("kira.math as math\n\nf: () Float64 {\n    sqrt(4.0) + math.sqrt(9.0)\n}"))
        expectNoErrors(TyperTestSupport.snippet("f: () Float64 {\n    sqrt(4.0)\n}"))
    }

    /** The rule's own test: old text's `use` saw the bare names, and does with the gate off. */
    @Test
    fun oldTextSeesImportsBareOnlyWithTheGateOff() {
        val old = main("app.units as units\n\nf: () Int32 {\n    bump(SCALE)\n}")
        expectNoErrors(DialectGates.aliasOnlyImports(false) { TyperTestSupport.type(units, old) })
        expectDiagnostic(TyperTestSupport.type(units, old), "types.use.bare")
    }

    @Test
    fun anOwnNameIsNeverAnImports() {
        val p = TyperTestSupport.type(units, main("app.units as units\napp.units.SCALE as SCALE\n\nbump: (v: Int32) Int32 {\n    v + SCALE\n}\n\nf: () Int32 {\n    bump(1)\n}"))
        expectNoErrors(p)
        val m = p.module("app:main")!!
        assertEquals("app:main", (p.graph.lookup(m, "bump") as ModuleGraph.Lookup.Found).from.uri)
        assertEquals("app:units", (p.graph.lookup(m, "SCALE") as ModuleGraph.Lookup.Found).from.uri)
    }

    private fun project(root: File, files: Map<String, String>) {
        val stdlib = File("kira").absoluteFile.path.replace('\\', '/')
        root.mkdirs()
        File(root, "project.kira").writeText(
            "PROJECT: Project = Project { } with {\n    name = \"app\"\n    srcDir = \"src\"\n    target = \"py\"\n    types = \"strict\"\n" +
                "    dependencies = @mapOf(\"kira_stdlib\", \"$stdlib\")\n}\n",
        )
        files.forEach { (path, text) -> File(root, "src/$path").apply { parentFile.mkdirs() }.writeText(text) }
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
    fun theConverterAddsAMemberAliasForEachBareName() {
        val root = Files.createTempDirectory("stage5_aliases").toFile()
        val lib = "pub SCALE: Int32 = 100\n\npub bump: (v: Int32) Int32 {\n    v + 1\n}\n\npub Meter: Any {\n    pub n: Int32 = 0\n}\n"
        // A local and a parameter of an import's name stay theirs: the typer's lookups decide, never the spelling.
        val app = "kira.math as math\napp.lib as lib\n\npub f: (bump: Int32) Int32 {\n    m: Meter = Meter { }\n    SCALE + bump + m.n\n}\n\n" +
            "pub g: () Float64 {\n    sqrt(lib.bump(1) as Float64)\n}\n"
        project(root, mapOf("lib.kira" to lib, "main.kira" to app))
        val (code, out) = captured { Stage5.convertAll(listOf(File(root, "src").path), check = false, keepYaml = false) }
        assertEquals(0, code, out)
        assertEquals(
            "kira.math as math\napp.lib as lib\napp.lib.Meter as Meter\napp.lib.SCALE as SCALE\n\npub f: (bump: Int32) Int32 {\n",
            File(root, "src/main.kira").readText().substringBefore("    m: Meter"),
        )
        assertTrue(out.contains("1 changed (2 member aliases, 0 uses written qualified)"), out)
        // The converted text types alias-only; a second run finds nothing to add.
        val (again, out2) = captured { Stage5.convertAll(listOf(File(root, "src").path), check = true, keepYaml = false) }
        assertEquals(0, again, out2)
    }

    @Test
    fun aNameThatIsAlsoAModuleAliasIsWrittenQualified() {
        val root = Files.createTempDirectory("stage5_clash").toFile()
        project(
            root,
            mapOf(
                "judge.kira" to "pub judge: (v: Int32) Int32 {\n    v\n}\n\npub LIMIT: Int32 = 3\n",
                // `judge` names the module and, bare, its function: the alias cannot be added, so each use is qualified.
                "main.kira" to "app.judge as judge\n\npub f: () Int32 {\n    judge(1) + judge(LIMIT)\n}\n",
                // A file the old rule already refuses is typed by no target: left as it is.
                "wrong.kira" to "app.judge as judge\n\npub g: () Int32 {\n    judge(1) + nowhere(1)\n}\n",
            ),
        )
        val wrong = File(root, "src/wrong.kira").readText()
        val (code, out) = captured { Stage5.convertAll(listOf(File(root, "src").path), check = false, keepYaml = false) }
        assertEquals(0, code, out)
        assertEquals("app.judge as judge\napp.judge.LIMIT as LIMIT\n\npub f: () Int32 {\n    judge.judge(1) + judge.judge(LIMIT)\n}\n", File(root, "src/main.kira").readText())
        assertTrue(out.contains("main.kira: 1 member alias, 2 uses written qualified") && out.contains("wrong.kira: it does not type under the old rule either"), out)
        assertEquals(wrong, File(root, "src/wrong.kira").readText())
    }

    /** A file whose lines end CRLF and LF both (an earlier converter's lines): rows are counted as the lexer counts them. */
    @Test
    fun aFileOfMixedLineEndsIsEditedWhereTheLexerSaysItIs() {
        val root = Files.createTempDirectory("stage5_mixed").toFile()
        project(
            root,
            mapOf(
                "judge.kira" to "pub judge: (v: Int32) Int32 {\n    v\n}\n\npub LIMIT: Int32 = 3\n",
                "main.kira" to "app.judge as judge\r\n\r\npub a: () Int32 {\n    1\n}\n\r\npub f: () Int32 {\r\n    judge(LIMIT)\r\n}\r\n",
            ),
        )
        val (code, out) = captured { Stage5.convertAll(listOf(File(root, "src").path), check = false, keepYaml = false) }
        assertEquals(0, code, out)
        // The new line takes the file's CR; each LF line stays as it was.
        assertEquals(
            "app.judge as judge\r\napp.judge.LIMIT as LIMIT\r\n\r\npub a: () Int32 {\n    1\n}\n\r\npub f: () Int32 {\r\n    judge.judge(LIMIT)\r\n}\r\n",
            File(root, "src/main.kira").readText(),
        )
    }
}
