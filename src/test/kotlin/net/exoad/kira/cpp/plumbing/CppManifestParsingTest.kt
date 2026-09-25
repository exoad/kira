package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.kim.TypeCheckMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CppManifestParsingTest {
    /** bibo's kira.yaml: design 8.3 verbatim, its three inline comments included. */
    private val biboYaml = """
        project: { name: bibo }
        srcDir: .
        srcExclude: [build, third_party, private, out, "**/build", viewer/assets]
        build:
          target: cpp
          cpp:
            layout: beside
            runtimeDir: firmware/lib           # the runtime lands in firmware/lib/kira/
            lineDirectives: true
            namespaces:
              "firmware:lib.text": bibo::text
              "firmware:lib.pulses": bibo::pulses
              "firmware:lib.pins": bibo::pins
              "firmware:lib.chassis.chassis": bibo::drive
              "firmware:pilot.src.scan": bibo
              "firmware:pilot.src.imu": bibo::imu
              "firmware:pilot.src.car": bibo
              "firmware:pilot.src.bibowire.*": bibowire
            headerOnly: ["firmware:lib.**", "firmware:pilot.src.{scan,speed,unilidar,imu,band,tag36h11_codes}"]   # URIs are strings: snake_case module names are fine
            freestanding: ["firmware:lib.**", "firmware:app.**", "firmware:encoder.**"]
        compiler: { types: strict }
        dependencies:
          kira_stdlib: { path: build/kira-toolchain/kira }   # kira_gen.py checks out tools/kira.lock here
    """.trimIndent()

    @Test
    fun parsesEveryNewKey() {
        val m: ProjectManifest = ManifestLoader.parse(biboYaml)
        assertEquals(".", m.srcDir)
        assertEquals(listOf("build", "third_party", "private", "out", "**/build", "viewer/assets"), m.srcExclude)
        assertEquals("cpp", m.build.target)
        assertEquals(TypeCheckMode.STRICT, m.compiler.types)
        assertEquals("build/kira-toolchain/kira", m.dependencies.getValue("kira_stdlib").path)

        val cpp = m.build.cpp
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
            cpp.namespaces
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
    fun defaultsWhenTheCppBlockIsAbsent() {
        val m = ManifestLoader.parse("project: { name: demo }\n")
        assertEquals(CppOptions(), m.build.cpp)
        assertEquals(emptyList(), m.srcExclude)
        assertEquals(TypeCheckMode.OFF, m.compiler.types)
        assertEquals(CppLayout.BESIDE, m.build.cpp.layout)
        assertEquals("gen/kira", m.build.cpp.outDir)
        assertEquals(null, m.build.cpp.runtimeDir)
        assertEquals("gen/kira", m.build.cpp.effectiveRuntimeDir)
        assertTrue(m.build.cpp.lineDirectives)
    }

    @Test
    fun treeLayoutOutDirAndExtensionsAndSnakeCaseSpellings() {
        val m = ManifestLoader.parse(
            """
            project: { name: demo }
            src_exclude: [out]
            build:
              target: c++
              cpp:
                layout: tree
                out_dir: generated/cpp
                runtime_dir: generated/rt
                line_directives: false
                header_only: ["demo:lib.*"]
                header_ext: .hpp
                source_ext: .cpp
            compiler: { types: lenient }
            """.trimIndent()
        )
        assertEquals(listOf("out"), m.srcExclude)
        assertEquals(CppLayout.TREE, m.build.cpp.layout)
        assertEquals("generated/cpp", m.build.cpp.outDir)
        assertEquals("generated/rt", m.build.cpp.runtimeDir)
        assertFalse(m.build.cpp.lineDirectives)
        assertEquals(listOf("demo:lib.*"), m.build.cpp.headerOnly)
        assertEquals(".hpp", m.build.cpp.headerExt)
        assertEquals(".cpp", m.build.cpp.sourceExt)
        assertEquals(TypeCheckMode.LENIENT, m.compiler.types)
    }

    @Test
    fun badValuesAreRejected() {
        assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { layout: sideways } }\n")
        }
        assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\ncompiler: { types: loose }\n")
        }
        assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { namespaces: { \"a:b\": 3 } } }\n")
        }
        assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { lineDirectives: maybe } }\n")
        }
    }

    @Test
    fun aMistypedCppKeyIsAnErrorNotASilentDefault() {
        val typo = assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { lineDirective: false } }\n")
        }
        assertTrue(typo.message!!.contains("'lineDirective'"), typo.message)
        assertTrue(typo.message!!.contains("lineDirectives"), typo.message)
        val two = assertThrows<IllegalArgumentException> {
            ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { heaederOnly: [\"a:b\"], layout: tree } }\n")
        }
        assertTrue(two.message!!.contains("'heaederOnly'"), two.message)
        // every spelling the loader accepts is accepted together
        val all = ManifestLoader.parse(
            """
            project: { name: d }
            build:
              cpp:
                layout: tree
                outDir: a
                runtimeDir: b
                lineDirectives: false
                namespaces: {}
                headerOnly: []
                freestanding: []
                headerExt: .h
                sourceExt: .cc
            """.trimIndent()
        )
        assertEquals("a", all.build.cpp.outDir)
    }

    @Test
    fun validatorAcceptsCppAndJsTargets() {
        val root = PlumbingTestSupport.tempProject("manifest-validate")
        Files.createDirectories(root.resolve("src"))
        listOf("cpp", "c++", "js", "javascript", "c").forEach { target ->
            val m = ManifestLoader.parse("project: { name: d }\nbuild: { target: $target }\n")
            val issues = ManifestValidator.validate(m, root)
            assertTrue(issues.none { it.field == "build.target" }, "target $target: $issues")
        }
        val bad = ManifestLoader.parse("project: { name: d }\nbuild: { target: rust }\n")
        assertTrue(ManifestValidator.validate(bad, root).any { it.field == "build.target" })
    }

    @Test
    fun validatorChecksTheCppBlock() {
        val root = PlumbingTestSupport.tempProject("manifest-validate-cpp")
        Files.createDirectories(root.resolve("src"))
        val m = ManifestLoader.parse(
            """
            project: { name: d }
            build:
              target: cpp
              cpp:
                headerExt: hxx
                sourceExt: .x
                namespaces: { "a:b": "not a namespace" }
            """.trimIndent()
        )
        val fields = ManifestValidator.validate(m, root).map { it.field }
        assertTrue("build.cpp.headerExt" in fields, fields.toString())
        assertTrue("build.cpp.namespaces.a:b" in fields, fields.toString())
        val same = ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { headerExt: .h, sourceExt: .h } }\n")
        assertTrue(ManifestValidator.validate(same, root).any { it.field == "build.cpp.sourceExt" })
    }

    @Test
    fun validatorRefusesAnExtensionThatNamesAKiraSource() {
        val root = PlumbingTestSupport.tempProject("manifest-validate-kira-ext")
        Files.createDirectories(root.resolve("src"))
        listOf(".kira" to "headerExt", ".kira" to "sourceExt", ".gen.kira" to "headerExt", "." to "headerExt").forEach { (ext, key) ->
            val m = ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { $key: \"$ext\" } }\n")
            val issue = ManifestValidator.validate(m, root).firstOrNull { it.field == "build.cpp.$key" }
            assertTrue(issue != null, "$key '$ext' must be refused")
            if (ext != ".") {
                assertTrue(issue.message.contains(".kira"), issue.message)
            }
        }
        val fine = ManifestLoader.parse("project: { name: d }\nbuild: { cpp: { headerExt: .kira.hpp, sourceExt: .kira.cpp } }\n")
        assertTrue(ManifestValidator.validate(fine, root).none { it.field.startsWith("build.cpp.") })
    }
}
