package net.exoad.kira.kim

import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 5's project.kira: what it holds, what it refuses, its types, and the compiler reading it. */
class ProjectKiraTest {
    private val stdlib = File("kira").absoluteFile

    private fun project(body: String) = "PROJECT: Project = Project { } with {\n${body.trimIndent().prependIndent("    ")}\n}\n"

    private fun refused(text: String): String = assertThrows<IllegalArgumentException> { ProjectKira.parse(text) }.message!!

    @Test
    fun everyFieldLoads() {
        val text = project(
                """
                name = "net.exoad.app"
                author = "exoad"
                version = "0.5"
                srcDir = "."
                srcExclude = @listOf("build", "**/build", " vendor ")
                target = "cpp"
                cSources = @listOf("native/a.c")
                linkFlags = List<Str> { }
                minify = false
                cpp = Cpp {
                    layout = "tree",
                    outDir = "gen",
                    runtimeDir = "firmware/lib",
                    lineDirectives = false,
                    namespaces = @mapOf("net.exoad.app.lib.text", "app::text"),
                    headerOnly = @listOf("net.exoad.app.lib.**"),
                    freestanding = @listOf("net.exoad.app.pico.**"),
                    headerExt = ".hh",
                    sourceExt = ".cc"
                }
                types = "strict"
                emitIr = "dump.txt"
                dependencies = @mapOf("kira_stdlib", "../kira", "other", "../other")
                """,
            )
        val m = ProjectKira.parse(text)
        // Every field set, the file types too.
        val sources = stdlib.listFiles()!!.filter { it.extension == "kira" }.map { it.path }
        assertEquals(emptyList(), ProjectKira.check(Files.createTempDirectory("projectkira_full").resolve(ProjectKira.FILE), sources, text))
        val expected = ProjectManifest(
            project = ProjectSpec("net.exoad.app", "exoad", "0.5"),
            srcDir = ".",
            srcExclude = listOf("build", "**/build", "vendor"),
            build = BuildOptions(
                target = "cpp", cSources = listOf("native/a.c"), linkFlags = emptyList(), minify = false,
                cpp = CppOptions(
                    CppLayout.TREE, "gen", "firmware/lib", false, mapOf("net.exoad.app.lib.text" to "app::text"),
                    listOf("net.exoad.app.lib.**"), listOf("net.exoad.app.pico.**"), ".hh", ".cc",
                ),
            ),
            compiler = CompilerOptions(emitIr = "dump.txt", types = TypeCheckMode.STRICT),
            dependencies = mapOf("kira_stdlib" to DependencySpec("../kira"), "other" to DependencySpec("../other")),
        )
        assertEquals(expected, m)
    }

    @Test
    fun aFieldNotGivenIsKiraYamlsDefault() {
        assertEquals(ManifestLoader.parse("project:\n  name: a.b\n"), ProjectKira.parse(project("name = \"a.b\"")))
        // A named construction says the same as a `with` block.
        assertEquals(ProjectKira.parse(project("name = \"a.b\"\nsrcDir = \".\"")), ProjectKira.parse("PROJECT: Project = Project { name = \"a.b\", srcDir = \".\" }\n"))
    }

    /** kira:config is the schema: its fields are the ones read, its defaults ProjectManifest's. */
    @Test
    fun theStdlibsProjectHasEveryFieldWithItsDefault() {
        val unit = TyperTestSupport.unitOf(TyperTestSupport.Src("kira/config.kira", File(stdlib, "config.kira").readText(), "kira:config"))
        val ast = unit.allSources().first { it.file == "kira/config.kira" }.ast
        fun fields(type: String): Map<String, String> = ast.statements.mapNotNull { (it as? Statement)?.expr as? ClassDecl }
            .first { (it.name.identifier as Identifier).value == type }.members.filterIsInstance<VariableDecl>()
            .associate { it.name.value to KiraUnparser.text(it.value!!) }
        val d = ProjectManifest(ProjectSpec(""))
        val c = CppOptions()
        val str = ProjectKira::quote
        assertEquals(
            linkedMapOf(
                "name" to str(d.project.name), "author" to str(d.project.author), "version" to str(d.project.version), "srcDir" to str(d.srcDir),
                "srcExclude" to "List<Str> { }", "target" to str(d.build.target), "cSources" to "List<Str> { }", "linkFlags" to "List<Str> { }",
                "minify" to d.build.minify.toString(), "cpp" to "Cpp { }", "types" to str(d.compiler.types.name.lowercase()),
                "emitIr" to str(d.compiler.emitIr ?: ""), "dependencies" to "Map<Str, Str> { }",
            ),
            fields("Project"),
        )
        assertEquals(ProjectKira.PROJECT_FIELDS, fields("Project").keys.toList())
        assertEquals(
            linkedMapOf(
                "layout" to str(c.layout.name.lowercase()), "outDir" to str(c.outDir), "runtimeDir" to str(c.runtimeDir ?: ""),
                "lineDirectives" to c.lineDirectives.toString(), "namespaces" to "Map<Str, Str> { }", "headerOnly" to "List<Str> { }",
                "freestanding" to "List<Str> { }", "headerExt" to str(c.headerExt), "sourceExt" to str(c.sourceExt),
            ),
            fields("Cpp"),
        )
    }

    @Test
    fun whatTheFileMayNotHold() {
        assertTrue(refused(project("name = \"a\"\nnmae = \"b\"")).contains("Project has no field 'nmae'"))
        assertTrue(refused(project("name = \"a\"\nsrcDir = true")).contains("Field 'srcDir' of Project is a Str"))
        assertTrue(refused(project("name = \"a\"\nsrcExclude = @listOf(\"x\", true)")).contains("is a List<Str>"))
        assertTrue(refused("X: Int32 = 1\n" + project("name = \"a\"")).contains("holds one declaration"))
        assertTrue(refused(project("name = \"a\" + \"b\"")).contains("plain values"))
        assertTrue(refused("PROJECT: Project = Project { \"a\" }\n").contains("Name each field"))
        assertTrue(refused(project("name = \"a\"\ntypes = \"loose\"")).contains("Field 'types' must be one of off, lenient, strict"))
        assertTrue(refused(project("name = \"a\"\ncpp = Cpp { } with {\n    layout = \"flat\"\n}")).contains("Field 'layout' must be one of beside, tree"))
        assertTrue(refused(project("name = \"a\"\ncpp = Cpp { } with {\n    lineDirectivs = false\n}")).contains("Cpp has no field 'lineDirectivs'"))
        // The refusal names the line of the field that is wrong.
        assertTrue(refused(project("name = \"a\"\n\nsrcDir = true")).contains("project.kira:4:5"), refused(project("name = \"a\"\n\nsrcDir = true")))
    }

    /** The typer reads the file as any code: a field of the wrong type is its error, in project.kira. */
    @Test
    fun theTyperChecksTheFile() {
        val dir = Files.createTempDirectory("projectkira_typed")
        val file = dir.resolve(ProjectKira.FILE)
        Files.writeString(file, project("name = 3\nminify = \"no\""))
        val sources = stdlib.listFiles()!!.filter { it.extension == "kira" }.map { it.path }
        val found = ProjectKira.check(file, sources)
        assertEquals(2, found.size, found.joinToString("\n"))
        assertTrue(found.all { it.startsWith("$file:") && ": error: types." in it }, found.joinToString("\n"))
        Files.writeString(file, project("name = \"fine\""))
        assertEquals(emptyList(), ProjectKira.check(file, sources))
        // A stdlib from before project.kira has no Project to type it with.
        assertTrue(ProjectKira.check(file, sources.filter { !it.endsWith("config.kira") }).single().contains("project.stdlib"))
    }

    @Test
    fun projectKiraIsReadBeforeKiraYamlAndIsNoModule() {
        val dir = Files.createTempDirectory("projectkira_files")
        assertNull(ProjectFiles.find(dir))
        Files.writeString(dir.resolve("kira.yaml"), "project:\n  name: from.yaml\nsrcDir: .\n")
        assertEquals("from.yaml", ProjectFiles.load(dir)!!.project.name)
        assertNull(ProjectFiles.note(dir))
        Files.writeString(dir.resolve(ProjectKira.FILE), project("name = \"from.kira\"\nsrcDir = \".\""))
        assertEquals("from.kira", ProjectFiles.load(dir)!!.project.name)
        assertTrue(ProjectFiles.note(dir)!!.contains("project.kira is read, kira.yaml is not"))
        // With srcDir ".", the root's project.kira is the configuration, never a module; one deeper is a module.
        Files.writeString(dir.resolve("app.kira"), "pub f: () Int32 {\n    1\n}\n")
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/project.kira"), "pub g: () Int32 {\n    2\n}\n")
        val sources = DependencyResolver.resolveProjectSources(ProjectFiles.load(dir), dir).map { Path.of(it).fileName.toString() }
        assertEquals(listOf("app.kira", "project.kira"), sources.sorted())
        assertTrue(DependencyResolver.resolveProjectSources(ProjectFiles.load(dir), dir).none { Path.of(it) == dir.resolve(ProjectKira.FILE) })
    }

    /** The CLI on a project.kira project: its name names each module, and the file itself emits nothing. */
    @Test
    fun theCliBuildsAProjectKiraProject() {
        val dir = Files.createTempDirectory("projectkira_cli").toFile()
        File(dir, ProjectKira.FILE).writeText(
            "// a tool\n" + project("name = \"net.exoad.tool\"\nsrcDir = \".\"\ntarget = \"py\"\ntypes = \"strict\"\ndependencies = @mapOf(\"kira_stdlib\", ${ProjectKira.quote(stdlib.path.replace('\\', '/'))})"),
        )
        File(dir, "app").mkdirs()
        File(dir, "app/main.kira").writeText("pub main: () Void {\n    println(\"hi\")\n}\n")
        val out = File(dir, "out")
        val run = PyTestSupport.cli(dir, "--out", out.path)
        assertEquals(0, run.exitCode, run.all)
        val generated = out.walkTopDown().filter { it.isFile && it.name.endsWith(".py") }.map { it.name }.toList()
        assertEquals(listOf("main_kira.py"), generated)
        assertTrue(File(out, "app/main_kira.py").readText().lineSequence().first().endsWith("module net.exoad.tool.app.main."))
        // A file the typer refuses stops the build before anything is written.
        File(dir, ProjectKira.FILE).writeText("PROJECT: Project = Project { } with {\n    name = \"net.exoad.tool\"\n    srcDir = 3\n}\n")
        val bad = PyTestSupport.cli(dir, "--target", "py", "--out", File(dir, "out2").path)
        assertTrue(bad.exitCode != 0 && bad.all.contains("Field 'srcDir' of Project is a Str"), bad.all)
    }
}
