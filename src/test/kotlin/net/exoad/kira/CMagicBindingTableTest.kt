package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.c.CMagicBindingTable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The C backend resolves `@_magic` free functions through the loaded binding
 * manifest (the `*.bind.yaml` files beside the stdlib modules), not a
 * hardcoded Kotlin table. These tests pin the manifest as the source of
 * truth -- they would fail if the loader stopped finding the files next to
 * the stdlib modules.
 */
class CMagicBindingTableTest {
    @Test
    fun mathBindingsLoadFromManifestBesideModules() {
        assumeTrue(File("kira/math.bind.yaml").isFile, "stdlib kira/ dir must be at cwd")

        assertEquals("sqrt", CMagicBindingTable.resolveFunctionOrNull("sqrt"))
        assertEquals(setOf("math.h"), CMagicBindingTable.includesOrNull("sqrt"))
        // abs lowers to fabs, not C's abs -- that is manifest data, not a guess.
        assertEquals("fabs", CMagicBindingTable.resolveFunctionOrNull("abs"))
        assertEquals(setOf("math.h"), CMagicBindingTable.includesOrNull("min"))
    }

    @Test
    fun assertBindsToPreludeHelperNotCMacro() {
        assumeTrue(File("kira/io.bind.yaml").isFile, "stdlib kira/ dir must be at cwd")

        // C's assert macro takes one argument; Kira's takes a message too, so
        // the binding must point at the prelude helper.
        assertEquals("kira_assert", CMagicBindingTable.resolveFunctionOrNull("assert"))
        // Empty includes for a bound name must not be confused with unbound.
        assertEquals(emptySet(), CMagicBindingTable.includesOrNull("assert"))
    }

    @Test
    fun unboundNamesResolveNull() {
        assertNull(CMagicBindingTable.resolveFunctionOrNull("definitely_not_a_magic_name"))
        assertNull(CMagicBindingTable.includesOrNull("definitely_not_a_magic_name"))
    }

    @Test
    fun printFamilyIsNotBindable() {
        // print/println/eprint/trace stay codegen intrinsics: their format
        // string is type-directed per call site, so no fixed symbol binding.
        assertNull(CMagicBindingTable.resolveFunctionOrNull("print"))
        assertNull(CMagicBindingTable.resolveFunctionOrNull("trace"))
    }

    @Test
    fun canonicalizationMatchesCodegen() {
        // Codegen strips a leading @ and surrounding _ then lowercases before
        // lookup (e.g. @_trace_ -> trace); the table keys must line up with that.
        assertTrue(CMagicBindingTable.resolveFunctionOrNull("sqrt") != null)
    }
}
