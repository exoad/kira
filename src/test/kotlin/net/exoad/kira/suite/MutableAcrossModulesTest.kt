package net.exoad.kira.suite

import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.dialect.Stage4
import net.exoad.kira.dialect.Stage5
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stages 4 and 5 together: a Mutable type reached through a member alias, a Mutable nested type named Outer.Inner from another module, and stage 5's converter on stage 4's text. */
class MutableAcrossModulesTest {
    private val counter = """
        pub Counter: Mutable<Any> {
            pub n: Mutable<Int32> = 0

            pub bump: () Void {
                n += 1
            }

            pub read: () Int32 {
                n
            }
        }

        pub fill: (xs: List<Int32>) Void {
            xs.add(1)
        }
    """.trimIndent()

    private val tools = "pub Tool: Any {\n    pub cost: () Int32;\n}\n"

    private val changesMain = "app.shapes.Shape as Shape\napp.tools.Tool as Tool\n\npub changes: (t: Tool) Bool {\n    t == Mutable<Tool>\n}\n"

    // A Mutable type and a value inside Shape, both Tools: Shape holds neither, so it stays a value.
    private fun shapes(tool: String) = tool + "\n\n" + """
        pub Shape: Any {
            pub Pen: Mutable<Tool> {
                pub ink: Mutable<Int32> = 100

                pub draw: (cost: Int32) Void {
                    ink -= cost
                }

                override pub cost: () Int32 {
                    ink
                }
            }

            pub Ruler: Tool {
                override pub cost: () Int32 {
                    1
                }
            }

            pub name: Str = "shape"
        }
    """.trimIndent()

    // The same Pen with no trait, as py lowers no trait yet.
    private val plainShapes = """
        pub Shape: Any {
            pub Pen: Mutable<Any> {
                pub ink: Mutable<Int32> = 100

                pub draw: (cost: Int32) Void {
                    ink -= cost
                }

                pub left: () Int32 {
                    ink
                }
            }

            pub name: Str = "shape"
        }
    """.trimIndent() + "\n"

    @Test
    fun aMutableTypeThroughAMemberAliasIsOneWhoseHoldersAndSubtypesSaySo() {
        val lib = module("app:counter", counter)
        val held = TyperTestSupport.type(lib, module("app:main", "app.counter.Counter as Counter\n\nHolder: Any {\n    pub c: Counter = Counter { }\n}\n"))
        assertContains(expectDiagnostic(held, "types.mutable.contagion").message, "'Holder' holds 'c', a Counter whose value changes")
        val sub = TyperTestSupport.type(lib, module("app:main", "app.counter.Counter as Counter\n\nFancy: Counter {\n    pub tag: Str = \"x\"\n}\n"))
        assertContains(expectDiagnostic(sub, "types.mutable.restate").message, "'Fancy: Mutable<Counter>'")
        // Said so, both type; a function from the member alias changes the shared value it is given.
        val ok = TyperTestSupport.type(
            lib,
            module(
                "app:main",
                "app.counter.Counter as Counter\napp.counter.fill as fill\n\nHolder: Mutable<Any> {\n    pub c: Counter = Counter { }\n}\n\n" +
                    "Fancy: Mutable<Counter> {\n    pub tag: Str = \"x\"\n}\n\nf: () Int32 {\n    h: Holder = Holder { }\n    h.c.bump()\n" +
                    "    xs: List<Int32> = List<Int32> { }\n    fill(xs)\n    h.c.read() + (xs.size() as Int32)\n}\n",
            ),
        )
        expectNoErrors(ok)
        val fill = ok.module("app:counter")!!.members["fill"] as net.exoad.kira.compiler.analysis.types.FnSymbol
        assertTrue(fill.params.single().byRef, "fill changes its List: by reference, from the module the member alias names")
    }

    @Test
    fun aMutableNestedTypeIsNamedOuterInnerInEveryMessage() {
        val lib = module("app:shapes", shapes(tools))
        // From another module: a holder, a subtype and a value type of it.
        val p = TyperTestSupport.type(
            lib,
            module(
                "app:main",
                "app.shapes.Shape as Shape\n\nKit: Any {\n    pub pen: Shape.Pen = Shape.Pen { }\n}\n\nFine: Shape.Pen {\n    pub tip: Int32 = 1\n}\n",
            ),
        )
        assertContains(expectDiagnostic(p, "types.mutable.contagion").message, "'Kit' holds 'pen', a Shape.Pen whose value changes")
        assertContains(expectDiagnostic(p, "types.mutable.restate").message, "'Fine: Mutable<Shape.Pen>'")
        // Inside its outer type: the nested type by its program name, its declaration by its own.
        val inner = TyperTestSupport.snippet("pub Shape: Any {\n    pub Bag: Any {\n        pub items: List<Int32> = List<Int32> { }\n    }\n}\n")
        val bag = expectDiagnostic(inner, "types.mutable.contagion").message
        assertContains(bag, "'Shape.Bag' holds 'items'")
        assertContains(bag, "declare 'Bag: Mutable<Any>'")
        assertFalse("Shape_" in bag, bag)
        val sub = TyperTestSupport.snippet(shapes(tools) + "\n\npub Box: Any {\n    pub Wide: Shape.Pen {\n        pub w: Int32 = 2\n    }\n}\n")
        val wide = expectDiagnostic(sub, "types.mutable.restate").message
        assertContains(wide, "'Box.Wide' is a subtype of the Mutable Shape.Pen")
        assertContains(wide, "'Wide: Mutable<Shape.Pen>'")
        expectNoErrors(TyperTestSupport.type(lib, module("app:main", "app.shapes.Shape as Shape\n\nKit: Mutable<Any> {\n    pub pen: Shape.Pen = Shape.Pen { }\n}\n")))
        // A run-time test of Mutable<Tool> in a module that reaches Shape by a member alias: the nested Pen is what it asks.
        val asked = TyperTestSupport.type(module("app:tools", tools), module("app:shapes", shapes("app.tools.Tool as Tool")), module("app:main", changesMain))
        expectNoErrors(asked)
        assertEquals(listOf("Shape_Pen"), asked.model.conformances.values.single().mutables?.map { it.name })
    }

    @Test
    fun bothRunOnPyAsOneProgramShared() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val main = """
            app.lib.counter.Counter as Counter
            app.lib.counter.fill as fill
            app.lib.shapes as shapes
            app.lib.shapes.Shape as Shape

            pub main: () Void {
                c: Counter = Counter { }
                d: Counter = c
                d.bump()
                xs: List<Int32> = List<Int32> { }
                fill(xs)
                println("${'$'}{c.read()} ${'$'}{xs.size()}")
                p: Shape.Pen = Shape.Pen { }
                q: shapes.Shape.Pen = p
                q.draw(30)
                r: Shape.Pen = p with {
                    ink = ink - 5
                }
                println("${'$'}{p.ink} ${'$'}{r.left()}")
            }
        """.trimIndent() + "\n"
        val root = project(
            mapOf(
                "lib/counter.kira" to counter + "\n",
                "lib/shapes.kira" to plainShapes,
                "app/main.kira" to main,
            ),
        )
        assertEquals("1 1\n65 65\n", runPy(root))
    }

    @Test
    fun cppRefusesWhatSharingChangesThroughAMemberAliasAndLowersTheNestedType() {
        val lib = DeclTestSupport.module("test:lib", counter)
        val refused = { body: String ->
            val e = DeclTestSupport.emit(lib, DeclTestSupport.module("test:main", "test.lib.fill as fill\n\npub f: () Int32 {\n$body\n}\n"))
            e.diagnostics("test:main").filter { it.code == "cpp.unsupported" }.map { it.message }
        }
        val plain = refused("    a: List<Int32> = @listOf(1, 2)\n    fill(a)\n    a.size() as Int32")
        assertTrue(plain.any { it.contains("'a' passed to 'fill', which changes it, from a binding that is not Mutable") }, plain.toString())
        val copied = refused("    a: Mutable<List<Int32>> = @listOf(1, 2)\n    b: Mutable<List<Int32>> = a\n    fill(b)\n    total: Size = a.size() + b.size()\n    total as Int32")
        assertTrue(copied.any { it.contains("copied from 'a' into 'b'") }, copied.toString())
        assertEquals(emptyList(), refused("    a: Mutable<List<Int32>> = @listOf(1, 2)\n    fill(a)\n    a.size() as Int32"))
        // The nested Mutable type is a top-level class whose changing function is not const and whose reader is;
        // a run-time `== Mutable<Tool>` is lowered on py only, and C++ says so.
        val nested = DeclTestSupport.emit(
            DeclTestSupport.module("test:tools", tools),
            DeclTestSupport.module("test:shapes", shapes("test.tools.Tool as Tool")),
            DeclTestSupport.module("test:main", changesMain.replace("app.", "test.")),
        )
        val text = nested.header("test:shapes") + (nested.source("test:shapes") ?: "")
        assertTrue(nested.diagnostics("test:main").any { it.code == "cpp.unsupported" && it.message.contains("run-time test of `Mutable<..>`") }, nested.render(nested.diagnostics("test:main")))
        assertContains(text, "class Shape_Pen")
        assertContains(text, "draw(")
        assertFalse(Regex("""draw\(std::int32_t cost\) const""").containsMatchIn(text), text)
        assertTrue(Regex("""cost\(\) const""").containsMatchIn(text), text)
    }

    /** bibo's order: stage 3's text through stage 4 (`mut` to Mutable<>), then stage 5 (member aliases, project.kira). */
    @Test
    fun stage4ThenStage5ConvertBibosWay() {
        val root = Files.createTempDirectory("stage45").toFile()
        try {
            File(root, "kira.yaml").writeText(
                "project:\n  name: app\nsrcDir: src\nbuild:\n  target: py\ncompiler:\n  types: strict\n" +
                    "dependencies:\n  kira_stdlib:\n    path: ${File("kira").absoluteFile.path.replace('\\', '/')}\n",
            )
            write(
                root,
                mapOf(
                    "lib/counter.kira" to "pub Counter: Any {\n    pub mut n: Int32 = 0\n\n    pub mut bump: () Void {\n        n += 1\n    }\n\n    pub mut log: List<Int32> = List<Int32> { }\n}\n",
                    // A copy of a List read off a bare import's type, then changed: stage 4 lists it only if it types the old text.
                    "app/main.kira" to "app.lib.counter as counter\n\npub main: () Void {\n    mut c: Counter = Counter { }\n    c.bump()\n" +
                        "    mut total: Int32 = 0\n    total += c.n\n    println(\"${'$'}{total}\")\n}\n\n" +
                        "pub tally: (c: Counter) Int32 {\n    mut copy: List<Int32> = c.log\n    copy.add(2)\n    c.log.size() as Int32\n}\n",
                ),
            )
            val (four, log4) = captured { Stage4.convertAll(listOf(root.path), check = false) }
            assertEquals(1, four, log4)
            assertContains(log4, "into 'copy', then changed")
            assertContains(File(root, "src/lib/counter.kira").readText(), "pub Counter: Mutable<Any> {\n    pub n: Mutable<Int32> = 0\n\n    pub bump: () Void {")
            val (five, log5) = captured { Stage5.convertAll(listOf(root.path), check = false, keepYaml = false) }
            assertEquals(0, five, log5)
            assertContains(log5, "1 changed (1 member aliases, 0 uses written qualified), 1 kira.yaml became project.kira")
            val main = File(root, "src/app/main.kira").readText()
            assertTrue(main.startsWith("app.lib.counter as counter\napp.lib.counter.Counter as Counter\n\npub main: () Void {\n    c: Mutable<Counter> = Counter { }\n"), main)
            assertFalse(File(root, "kira.yaml").exists())
            // Each converter's fixed point, and the program runs with every gate on.
            assertEquals(0, captured { Stage5.convertAll(listOf(root.path), check = true, keepYaml = false) }.first)
            val (again, log4again) = captured { Stage4.convertAll(listOf(root.path), check = true) }
            assertEquals(0, again, log4again)
            // project.kira is no module of stage 4's.
            assertContains(log4again, "kira-dialect stage4: 2 files")
            if (PyTestSupport.python != null) assertEquals("1\n", runPy(root))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Stage 5 on stage 4's text: a bare Mutable type and a Mutable nested type's outer get their member aliases, proved. */
    @Test
    fun stage5AddsMemberAliasesToStage4sText() {
        val root = project(
            mapOf(
                "lib/counter.kira" to counter + "\n",
                "lib/shapes.kira" to plainShapes,
                "app/main.kira" to "app.lib.counter as counter\napp.lib.shapes as shapes\n\npub main: () Void {\n    c: Counter = Counter { }\n    c.bump()\n" +
                    "    p: Shape.Pen = Shape.Pen { }\n    p.draw(c.read())\n    total: Mutable<Int32> = p.ink\n    total += 1\n    println(\"${'$'}{total}\")\n}\n",
            ),
        )
        try {
            val (code, log) = captured { Stage5.convertAll(listOf(File(root, "src").path), check = false, keepYaml = false) }
            assertEquals(0, code, log)
            assertContains(log, "(2 member aliases, 0 uses written qualified)")
            assertTrue(
                File(root, "src/app/main.kira").readText().startsWith(
                    "app.lib.counter as counter\napp.lib.counter.Counter as Counter\napp.lib.shapes as shapes\napp.lib.shapes.Shape as Shape\n\n",
                ),
                File(root, "src/app/main.kira").readText(),
            )
            assertEquals(0, captured { Stage5.convertAll(listOf(File(root, "src").path), check = true, keepYaml = false) }.first)
            if (PyTestSupport.python != null) assertEquals("100\n", runPy(root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun write(root: File, files: Map<String, String>) =
        files.forEach { (path, text) -> File(root, "src/$path").apply { parentFile.mkdirs() }.writeText(text) }

    /** A py project `app` (strict types) holding [files] under src/. */
    private fun project(files: Map<String, String>): File {
        val root = Files.createTempDirectory("mutable_modules").toFile()
        File(root, "project.kira").writeText(
            "PROJECT: Project = Project { } with {\n    name = \"app\"\n    srcDir = \"src\"\n    target = \"py\"\n    types = \"strict\"\n" +
                "    dependencies = @mapOf(\"kira_stdlib\", \"${File("kira").absoluteFile.path.replace('\\', '/')}\")\n}\n",
        )
        write(root, files)
        return root
    }

    /** The project at [root] compiled for py, its main module's stdout. */
    private fun runPy(root: File): String {
        val out = File(root, "out")
        val build = PyTestSupport.cli(root, "--target", "py", "--out", out.path)
        assertEquals(0, build.exitCode, build.all)
        val mains = out.walkTopDown().filter { it.isFile && it.extension == "py" && it.readText().contains("\nif __name__ == \"__main__\":\n") }.toList()
        assertEquals(1, mains.size, mains.toString())
        val r = PyTestSupport.run(listOf(PyTestSupport.python!!, mains.single().absolutePath), root)
        assertEquals(0, r.exitCode, r.all)
        return r.stdout.replace("\r", "")
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
}
