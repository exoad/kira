package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.c.CMagicBindingTable
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The C++ backend's stdlib bindings are data in the `.bind.yaml` manifests
 * beside the stdlib modules, in the `cpp:` sub-map of each entry (design
 * section 7.4).
 *
 * Three things must hold at once:
 *  1. every `@_magic` method and free function has a `cpp` binding or a
 *     `cpp: { unsupported }` decision (D83), so the C++ emitter never meets a
 *     magic call it cannot lower or refuse by name (the print family is the
 *     one exception: its format is type-directed per call site);
 *  2. no C++-only entry is a bare string, because [CMagicBindingTable] reads
 *     a bare string as a C symbol;
 *  3. the C table resolves exactly what it resolved before the `cpp` maps
 *     arrived: the C backend must not gain or lose a binding by accident.
 */
class StdlibCppBindingsTest {
    /**
     * What [CMagicBindingTable] resolves: the base commit's (8d008e8) and K5's <math.h> hypot, atan2 and exp
     * (critique 16). A `cpp` map is invisible to the C loader, so this list moves only by such a decision.
     */
    private val cBindingsAtBase: Map<String, Pair<String, Set<String>>> = mapOf(
        "hypot" to ("hypot" to setOf("math.h")),
        "atan2" to ("atan2" to setOf("math.h")),
        "exp" to ("exp" to setOf("math.h")),
        "sqrt" to ("sqrt" to setOf("math.h")),
        "pow" to ("pow" to setOf("math.h")),
        "floor" to ("floor" to setOf("math.h")),
        "ceil" to ("ceil" to setOf("math.h")),
        "round" to ("round" to setOf("math.h")),
        "sin" to ("sin" to setOf("math.h")),
        "cos" to ("cos" to setOf("math.h")),
        "tan" to ("tan" to setOf("math.h")),
        "abs" to ("fabs" to setOf("math.h")),
        "min" to ("fmin" to setOf("math.h")),
        "max" to ("fmax" to setOf("math.h")),
        "assert" to ("kira_assert" to emptySet()),
    )

    private val allowedCppKeys = setOf("expr", "includes", "pure", "constexpr", "unsupported")

    private val compilerKnown = StdlibManifests.compilerKnown

    @Test
    fun everyMagicMethodAndFunctionHasACppBinding() {
        val declared = magicCallableNames()
        assertTrue(declared.isNotEmpty(), "no @_magic callables found under kira/")
        val bound = manifestEntries().filter { (_, value) -> cppMapOf(value) != null }.keys

        val missing = declared.filterNot { it in bound }.sorted()
        assertEquals(
            emptyList(),
            missing,
            "@_magic callables without a cpp binding in kira/*.bind.yaml"
        )
    }

    /**
     * A magic class's callable surface is not only its own body. `Str` and
     * `Num` take `equals` from `Equatable<T>` and `hashCode` from `Hashable`
     * without a body anywhere, so those calls are magic too, and the C
     * backend already lowers `Str.equals` / `Str.hashCode` that way.
     */
    @Test
    fun traitMethodsOfMagicClassesAreMagicCallables() {
        val declared = magicCallableNames()
        val inherited = listOf("Str.equals", "Str.hashCode", "Num.equals", "Num.hashCode")
        assertEquals(emptyList(), inherited.filterNot { it in declared }, "trait methods missing from the magic surface")
    }

    @Test
    fun cppBindingsNameOnlyDeclaredCallables() {
        // A binding for a name nothing declares is a typo that would never be
        // exercised. Keys the C backend also uses stay bound by name.
        val declared = magicCallableNames()
        val stray = manifestEntries()
            .filter { (_, value) -> cppMapOf(value) != null }
            .keys
            .filterNot { it in declared || it in compilerKnown }
            .sorted()
        assertEquals(emptyList(), stray, "cpp bindings for names no stdlib module declares @_magic")
    }

    @Test
    fun noCppEntryIsABareString() {
        val problems = mutableListOf<String>()
        manifestFiles().forEach { file ->
            val entries = parseManifest(file)
            entries.forEach { (key, value) ->
                val label = "${file.name}: $key"
                when (value) {
                    is String -> {
                        // A bare string is a C symbol by the loader's rule. It
                        // is only acceptable when that is the intent, which
                        // means it was a C binding at the base commit.
                        if (key !in cBindingsAtBase) {
                            problems.add("$label is a bare string; a C++-only entry must be a map with a cpp sub-map")
                        }
                    }

                    is Map<*, *> -> {
                        val hasSymbol = value.containsKey("symbol")
                        val cpp = value["cpp"]
                        if (!hasSymbol && cpp == null) {
                            problems.add("$label has neither symbol nor cpp")
                        }
                        if (cpp != null && cpp !is Map<*, *>) {
                            problems.add("$label: cpp must be a map, was ${cpp::class.simpleName}")
                        }
                        if (cpp is Map<*, *>) {
                            val expr = cpp["expr"]
                            val why = cpp["unsupported"]
                            when {
                                // A decision (D83): the call is refused naming why, so nothing else may sit beside it.
                                why != null -> if (why !is String || why.isBlank() || cpp.size != 1) {
                                    problems.add("$label: cpp.unsupported must be a non-empty string, alone in its map")
                                }
                                expr !is String || expr.isBlank() -> problems.add("$label: cpp.expr must be a non-empty string")
                            }
                            cpp.keys.map { it.toString() }.filterNot { it in allowedCppKeys }.forEach {
                                problems.add("$label: unknown cpp key '$it'")
                            }
                            val includes = cpp["includes"]
                            if (includes != null && (includes !is List<*> || includes.any { it !is String })) {
                                problems.add("$label: cpp.includes must be a list of strings")
                            }
                            listOf("pure", "constexpr").forEach { flag ->
                                val v = cpp[flag]
                                if (v != null && v !is Boolean) {
                                    problems.add("$label: cpp.$flag must be a boolean")
                                }
                            }
                        }
                        if (!hasSymbol && key in cBindingsAtBase) {
                            problems.add("$label lost its C symbol")
                        }
                    }

                    else -> problems.add("$label has an unexpected value type ${value?.let { it::class.simpleName }}")
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail(problems.joinToString("\n"))
        }
    }

    @Test
    fun cTableResolvesExactlyTheBindingsItResolvedAtBase() {
        assertTrue(File("kira/math.bind.yaml").isFile, "stdlib kira/ dir must be at cwd")

        // Every base binding still resolves to the same symbol and includes.
        cBindingsAtBase.forEach { (name, expected) ->
            assertEquals(expected.first, CMagicBindingTable.resolveFunctionOrNull(name), "symbol of $name")
            assertEquals(expected.second, CMagicBindingTable.includesOrNull(name), "includes of $name")
        }

        // No other manifest key resolves: a `cpp` map without `symbol` is
        // invisible to the C loader.
        manifestEntries().keys.filterNot { it in cBindingsAtBase }.forEach { name ->
            assertNull(CMagicBindingTable.resolveFunctionOrNull(name), "$name must be unbound for C")
            assertNull(CMagicBindingTable.includesOrNull(name), "$name must have no C includes")
        }
    }

    // ---- helpers ------------------------------------------------------

    private fun magicCallableNames(): Set<String> = StdlibManifests.magicCallableNames()

    private fun manifestFiles(): List<File> = StdlibManifests.manifestFiles()

    private fun parseManifest(file: File): Map<String, Any?> = StdlibManifests.parseManifest(file)

    private fun manifestEntries(): Map<String, Any?> = StdlibManifests.manifestEntries()

    private fun cppMapOf(value: Any?): Map<*, *>? = StdlibManifests.targetMapOf(value, "cpp")
}
