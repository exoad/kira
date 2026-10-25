package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The typer (ModuleSymbol.profile, isHeaderOnly, cppNamespace) and the C++ backend's
 * plumbing (CppOptions.isFreestanding, isHeaderOnly, CppModuleLayout.namespaceFor) read the
 * same build.cpp keys. They were written by two packages in parallel; this pins that, given
 * the same manifest, they give the same answer for every module, including the cases where
 * the two first disagreed: glob precedence, a `**` that matches zero segments, a keyword as
 * the last URI segment, and a nested stdlib module.
 */
class TyperLayoutAgreementTest {
    private val uris = listOf(
        "firmware:lib.text",
        "firmware:lib.chassis.cal",
        "firmware:pilot.src.proto",
        "firmware:pilot.src.scan",
        "firmware:pilot.src.bibowire.control",
        "app:a.c",
        "app:a.b.c",
        "app:new",
        "kira:core",
        "kira:agree.nested",
    )

    private val namespaces = linkedMapOf(
        // Listed before the more specific glob: key order must not decide.
        "firmware:**" to "fw",
        "firmware:lib.**" to "lib",
        "firmware:lib.text" to "bibo::text",
        "firmware:pilot.src.bibowire.*" to "bibowire",
    )
    private val headerOnly = listOf("firmware:pilot.src.{scan,speed}", "app:a.**.c")
    private val freestanding = listOf("firmware:lib.**")

    @Test
    fun theTyperAndTheLayoutAgreeOnEveryModule() {
        val program = TyperTestSupport.type(
            *uris.map { module(it, "x: Int32 = 1") }.toTypedArray(),
            options = TyperOptions(freestanding = freestanding, headerOnly = headerOnly, namespaces = namespaces),
        )
        val cpp = CppOptions(namespaces = namespaces, headerOnly = headerOnly, freestanding = freestanding)
        val layout = CppModuleLayout(cpp, Path.of("."))
        for (uri in uris) {
            val m = program.module(uri) ?: throw AssertionError("no module $uri in the typed program")
            assertEquals(layout.namespaceFor(uri), m.cppNamespace, "namespace of $uri")
            assertEquals(cpp.isHeaderOnly(uri), m.isHeaderOnly, "header-only of $uri")
            assertEquals(cpp.isFreestanding(uri), m.profile == Profile.FREESTANDING, "profile of $uri")
        }
        // The answers themselves, so an agreement on a wrong value is caught too.
        assertEquals("lib", program.module("firmware:lib.chassis.cal")!!.cppNamespace)
        assertEquals("bibo::text", program.module("firmware:lib.text")!!.cppNamespace)
        assertEquals("fw", program.module("firmware:pilot.src.proto")!!.cppNamespace)
        assertEquals("new_", program.module("app:new")!!.cppNamespace)
        assertEquals("kira::agree::nested", program.module("kira:agree.nested")!!.cppNamespace)
        assertTrue(program.module("app:a.c")!!.isHeaderOnly, "a ** segment matches zero segments")
        assertTrue(program.module("kira:core")!!.isHeaderOnly, "every stdlib module is header-only")
    }
}
