package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.ProjectFile
import net.exoad.kira.compiler.TypeCheckMode
import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** project.kira's `cpp` as the C++ backend reads it, and what Project.issues says of it. */
class CppProjectParsingTest {
    private fun project(body: String) = "PROJECT: Project = Project { } with {\n${body.trimIndent().prependIndent("    ")}\n}\n"

    /** bibo's old kira.yaml (design 8.3) as project.kira text: its excludes, eight namespaces and globs, comments shortened.
     * bibo's own project.kira (e5951c3) names its globs by dotted module name and lists no namespace. */
    private val bibo = project(
        """
        name = "bibo"
        srcDir = "."
        srcExclude = @listOf("build", "third_party", "private", "out", "**/build", "viewer/assets")
        target = "cpp"
        cpp = Cpp {
            layout = "beside",
            runtimeDir = "firmware/lib", // the runtime lands in firmware/lib/kira/
            lineDirectives = true,
            namespaces = @mapOf(
                "firmware:lib.text", "bibo::text",
                "firmware:lib.pulses", "bibo::pulses",
                "firmware:lib.pins", "bibo::pins",
                "firmware:lib.chassis.chassis", "bibo::drive",
                "firmware:pilot.src.scan", "bibo",
                "firmware:pilot.src.imu", "bibo::imu",
                "firmware:pilot.src.car", "bibo",
                "firmware:pilot.src.bibowire.*", "bibowire"
            ),
            headerOnly = @listOf("firmware:lib.**", "firmware:pilot.src.{scan,speed,unilidar,imu,band,tag36h11_codes}"), // snake_case module names are fine
            freestanding = @listOf("firmware:lib.**", "firmware:app.**", "firmware:encoder.**")
        }
        types = "strict"
        dependencies = @mapOf("kira_stdlib", "build/kira-toolchain/kira") // kira_gen.py checks out tools/kira.lock here
        """,
    )

    @Test
    fun parsesEveryCppField() {
        val m = ProjectFile.parse(bibo)
        assertEquals(".", m.srcDir)
        assertEquals(listOf("build", "third_party", "private", "out", "**/build", "viewer/assets"), m.srcExclude)
        assertEquals("cpp", m.target)
        assertEquals(TypeCheckMode.STRICT, m.types)
        assertEquals("build/kira-toolchain/kira", m.dependencies.getValue("kira_stdlib"))

        val cpp = m.cpp
        assertEquals(CppLayout.BESIDE, cpp.layout)
        assertEquals("gen/kira", cpp.outDir)
        assertEquals("firmware/lib", cpp.runtimeDir)
        assertEquals("firmware/lib", cpp.effectiveRuntimeDir)
        assertTrue(cpp.lineDirectives)
        assertEquals(
            linkedMapOf(
                "firmware:lib.text" to "bibo::text",
                "firmware:lib.pulses" to "bibo::pulses",
                "firmware:lib.pins" to "bibo::pins",
                "firmware:lib.chassis.chassis" to "bibo::drive",
                "firmware:pilot.src.scan" to "bibo",
                "firmware:pilot.src.imu" to "bibo::imu",
                "firmware:pilot.src.car" to "bibo",
                "firmware:pilot.src.bibowire.*" to "bibowire",
            ),
            cpp.namespaces,
        )
        assertEquals(8, cpp.namespaces.size)
        assertEquals(listOf("firmware:lib.**", "firmware:pilot.src.{scan,speed,unilidar,imu,band,tag36h11_codes}"), cpp.headerOnly)
        assertEquals(listOf("firmware:lib.**", "firmware:app.**", "firmware:encoder.**"), cpp.freestanding)
        assertEquals(".kira.hxx", cpp.headerExt)
        assertEquals(".kira.cxx", cpp.sourceExt)

        assertTrue(cpp.isHeaderOnly("firmware:lib.chassis.cal"))
        assertTrue(cpp.isHeaderOnly("firmware:pilot.src.tag36h11_codes"))
        assertFalse(cpp.isHeaderOnly("firmware:pilot.src.proto"))
        assertTrue(cpp.isFreestanding("firmware:app.command"))
        assertFalse(cpp.isFreestanding("firmware:pilot.src.proto"))
    }

    @Test
    fun defaultsWhenTheCppFieldIsAbsent() {
        val m = ProjectFile.parse(project("name = \"demo\""))
        assertEquals(CppOptions(), m.cpp)
        assertEquals(emptyList(), m.srcExclude)
        assertEquals(TypeCheckMode.OFF, m.types)
        assertEquals(CppLayout.BESIDE, m.cpp.layout)
        assertEquals("gen/kira", m.cpp.outDir)
        assertEquals(null, m.cpp.runtimeDir)
        assertEquals("gen/kira", m.cpp.effectiveRuntimeDir)
        assertTrue(m.cpp.lineDirectives)
    }

    @Test
    fun treeLayoutOutDirAndExtensions() {
        val m = ProjectFile.parse(
            project(
                """
                name = "demo"
                srcExclude = @listOf("out")
                target = "c++"
                cpp = Cpp { } with {
                    layout = "tree"
                    outDir = "generated/cpp"
                    runtimeDir = "generated/rt"
                    lineDirectives = false
                    headerOnly = @listOf("demo:lib.*")
                    headerExt = ".hpp"
                    sourceExt = ".cpp"
                }
                types = "lenient"
                """,
            ),
        )
        assertEquals(listOf("out"), m.srcExclude)
        assertEquals(CppLayout.TREE, m.cpp.layout)
        assertEquals("generated/cpp", m.cpp.outDir)
        assertEquals("generated/rt", m.cpp.runtimeDir)
        assertFalse(m.cpp.lineDirectives)
        assertEquals(listOf("demo:lib.*"), m.cpp.headerOnly)
        assertEquals(".hpp", m.cpp.headerExt)
        assertEquals(".cpp", m.cpp.sourceExt)
        assertEquals(TypeCheckMode.LENIENT, m.types)
    }

    @Test
    fun badValuesAreRejected() {
        for (body in listOf(
            "name = \"d\"\ncpp = Cpp { layout = \"sideways\" }",
            "name = \"d\"\ntypes = \"loose\"",
            "name = \"d\"\ncpp = Cpp { namespaces = @mapOf(\"a:b\", 3) }",
            "name = \"d\"\ncpp = Cpp { lineDirectives = \"maybe\" }",
        )) {
            assertThrows<IllegalArgumentException>(body) { ProjectFile.parse(project(body)) }
        }
    }

    @Test
    fun aMistypedCppFieldIsAnErrorNotASilentDefault() {
        val typo = assertThrows<IllegalArgumentException> { ProjectFile.parse(project("name = \"d\"\ncpp = Cpp { lineDirective = false }")) }
        assertTrue(typo.message!!.contains("'lineDirective'"), typo.message)
        assertTrue(typo.message!!.contains("lineDirectives"), typo.message)
        val two = assertThrows<IllegalArgumentException> { ProjectFile.parse(project("name = \"d\"\ncpp = Cpp { heaederOnly = @listOf(\"a:b\"), layout = \"tree\" }")) }
        assertTrue(two.message!!.contains("'heaederOnly'"), two.message)
        // Every field the reader knows, together.
        val all = ProjectFile.parse(
            project(
                """
                name = "d"
                cpp = Cpp { } with {
                    layout = "tree"
                    outDir = "a"
                    runtimeDir = "b"
                    lineDirectives = false
                    namespaces = Map<Str, Str> { }
                    headerOnly = List<Str> { }
                    freestanding = List<Str> { }
                    headerExt = ".h"
                    sourceExt = ".cc"
                }
                """,
            ),
        )
        assertEquals("a", all.cpp.outDir)
    }

    @Test
    fun aProjectAcceptsCppAndJsTargets() {
        val root = PlumbingTestSupport.tempProject("project-issues")
        Files.createDirectories(root.resolve("src"))
        listOf("cpp", "c++", "js", "javascript", "c").forEach { target ->
            val issues = Project("d", target = target).issues(root)
            assertTrue(issues.none { it.field == "target" }, "target $target: $issues")
        }
        assertTrue(Project("d", target = "rust").issues(root).any { it.field == "target" })
    }

    @Test
    fun aProjectsCppIsChecked() {
        val root = PlumbingTestSupport.tempProject("project-issues-cpp")
        Files.createDirectories(root.resolve("src"))
        val m = Project("d", target = "cpp", cpp = CppOptions(headerExt = "hxx", sourceExt = ".x", namespaces = mapOf("a:b" to "not a namespace")))
        val fields = m.issues(root).map { it.field }
        assertTrue("cpp.headerExt" in fields, fields.toString())
        assertTrue("cpp.namespaces['a:b']" in fields, fields.toString())
        assertTrue(Project("d", cpp = CppOptions(headerExt = ".h", sourceExt = ".h")).issues(root).any { it.field == "cpp.sourceExt" })
    }

    @Test
    fun anExtensionThatNamesAKiraSourceIsRefused() {
        val root = PlumbingTestSupport.tempProject("project-issues-kira-ext")
        Files.createDirectories(root.resolve("src"))
        listOf(".kira" to "headerExt", ".kira" to "sourceExt", ".gen.kira" to "headerExt", "." to "headerExt").forEach { (ext, key) ->
            val cpp = if (key == "headerExt") CppOptions(headerExt = ext) else CppOptions(sourceExt = ext)
            val issue = Project("d", cpp = cpp).issues(root).firstOrNull { it.field == "cpp.$key" }
            assertTrue(issue != null, "$key '$ext' must be refused")
            if (ext != ".") {
                assertTrue(issue.message.contains(".kira"), issue.message)
            }
        }
        val fine = Project("d", cpp = CppOptions(headerExt = ".kira.hpp", sourceExt = ".kira.cpp"))
        assertTrue(fine.issues(root).none { it.field.startsWith("cpp.") })
    }
}
