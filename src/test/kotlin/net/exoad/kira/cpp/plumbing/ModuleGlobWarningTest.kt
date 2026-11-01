package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A project's module glob that names no module is warned about with the dotted spelling it meant. */
class ModuleGlobWarningTest {
    @Test
    fun aGlobThatNamesNoModuleIsWarnedWithTheDottedSpellingItMeant() {
        val root = Path.of("build/tmp/dialect-globs").toAbsolutePath().normalize()
        val text = CppModuleRef("bibo:firmware.lib.text", root.resolve("firmware/lib/text.kira"))
        val options = CppOptions(headerOnly = listOf("firmware:lib.**"), freestanding = listOf("firmware:lib.**", "bibo.firmware.lib.**"))
        val warnings = CppModuleLayout(options, root, listOf(text)).checkCollisions().filter { it.code == "cpp.glob-unused" }
        assertEquals(2, warnings.size, warnings.joinToString("\n") { it.message })
        assertTrue(warnings.all { !it.isError && it.message.contains("'bibo.firmware.lib.**'") }, warnings.joinToString("\n") { it.message })
        assertTrue(warnings.any { it.message.startsWith("cpp.headerOnly['firmware:lib.**'] matches no module") })
    }
}
