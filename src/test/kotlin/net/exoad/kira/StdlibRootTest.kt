package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class StdlibRootTest {
    private var saved: Array<String> = emptyArray()

    @BeforeEach
    fun save() {
        saved = Public.Builtin.intrinsicalStandardLibrarySources
    }

    @AfterEach
    fun restore() {
        Public.Builtin.intrinsicalStandardLibrarySources = saved
    }

    @Test
    fun theStdlibIsTheDirectoryHoldingCoreNotTheFirstDependency() {
        val root = Files.createTempDirectory("stdlib-root")
        val lib = Files.createDirectories(root.resolve("esp32/src")).resolve("lines.kira")
        val stdlib = Files.createDirectories(root.resolve("kira"))
        Files.writeString(lib, "")
        Files.writeString(stdlib.resolve("core.kira"), "")
        Files.writeString(stdlib.resolve("os.kira"), "")
        // The compiler hands over every dependency's sources sorted, so the library comes first.
        Public.Builtin.intrinsicalStandardLibrarySources =
            listOf(lib, stdlib.resolve("core.kira"), stdlib.resolve("os.kira")).map { it.toString() }.sorted().toTypedArray()
        assertEquals(stdlib, StdlibLayout.stdlibRoot())
    }

    @Test
    fun withoutACoreTheFirstSourceStillNamesTheRoot() {
        val dir = Files.createTempDirectory("stdlib-root-core-less")
        val only = dir.resolve("a.kira")
        Files.writeString(only, "")
        Public.Builtin.intrinsicalStandardLibrarySources = arrayOf(only.toString())
        assertEquals(dir, StdlibLayout.stdlibRoot())
    }
}
