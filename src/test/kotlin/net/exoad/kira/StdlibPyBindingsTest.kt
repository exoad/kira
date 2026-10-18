package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.py.PyBindingTable
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The py target's stdlib bindings (D83): every `@_magic` callable has a `py:` entry in its manifest, a binding or a
 * `py: { unsupported: "<why>" }` decision, so a gap is a choice the refusal names, never one nobody made.
 */
class StdlibPyBindingsTest {
    /** Lowered by the emitter itself, past any template: none now. */
    private val emitterLowered = emptySet<String>()

    private val allowedPyKeys = setOf("expr", "place", "statement", "unsupported", "float")

    @Test
    fun everyMagicMethodAndFunctionHasAPyEntry() {
        val entries = StdlibManifests.manifestEntries()
        val missing = StdlibManifests.magicCallableNames()
            .filter { it !in emitterLowered && StdlibManifests.targetMapOf(entries[it], "py") == null }
            .sorted()
        assertEquals(emptyList(), missing, "@_magic callables without a py binding or a py: { unsupported } in kira/*.bind.yaml")
    }

    @Test
    fun pyEntriesNameOnlyDeclaredCallables() {
        val declared = StdlibManifests.magicCallableNames()
        val stray = StdlibManifests.manifestEntries()
            .filter { (key, value) -> StdlibManifests.targetMapOf(value, "py") != null && key !in declared && key !in StdlibManifests.compilerKnown }
            .keys.sorted()
        assertEquals(emptyList(), stray, "py entries for names no stdlib module declares @_magic")
        assertEquals(emptyList(), emitterLowered.filter { it !in declared }, "an emitter-lowered name nothing declares")
    }

    @Test
    fun eachPyEntryIsABindingOrADecisionAlone() {
        val problems = mutableListOf<String>()
        StdlibManifests.manifestFiles().forEach { file ->
            StdlibManifests.parseManifest(file).forEach { (key, value) ->
                val py = (value as? Map<*, *>)?.get("py") ?: return@forEach
                val label = "${file.name}: $key"
                if (py !is Map<*, *>) {
                    problems += "$label: py must be a map"
                    return@forEach
                }
                py.keys.map { it.toString() }.filterNot { it in allowedPyKeys }.forEach { problems += "$label: unknown py key '$it'" }
                val why = py["unsupported"]
                when {
                    why != null -> if (why !is String || why.isBlank() || py.size != 1) problems += "$label: py.unsupported must be a non-empty string, alone in its map"
                    (py["expr"] as? String).isNullOrBlank() -> problems += "$label: py.expr must be a non-empty string"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail(problems.joinToString("\n"))
        }
    }

    @Test
    fun theTableReadsADecisionWithItsReason() {
        val table = PyBindingTable().apply { loadDir(File("kira").toPath()) }
        assertEquals("Float32 (Float64 is Python's float)", table.lookup("readF32Le")?.unsupported)
        assertNull(table.lookup("readF64Le")?.unsupported)
        assertEquals("_k_rdf64({0}, {1})", table.lookup("readF64Le")?.expr)
    }
}
