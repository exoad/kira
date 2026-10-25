package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.rules.Rules
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * R-A (40-round3 2): a lent result is a place. An accessor whose binding returns a reference into
 * its receiver (`xs.get(i)`, `m.unwrap()`, `r.unwrapErr()`) is the place the other spelling
 * (`xs[i]`, `m.value`, `r.error`) names, so every rule that asks where storage lives gives both
 * spellings one verdict. Here, the accessors' set against the bindings that return such a reference.
 */
class LentPlacesTest {
    @Test
    fun theAccessorsAreExactlyTheBindingsThatReturnAReferenceIntoTheReceiver() {
        // The two halves pinned against each other (the lesson of comments that claim other files): every binding whose
        // C++ returns a reference into its receiver is in Rules.ACCESSORS, and nothing else is. W2.3 pins the same set
        // from its side; kira/cpp/tests/rt_test.cxx pins the helpers' lvalue results.
        val found = sortedSetOf<String>()
        for (f in File("kira").listFiles { x -> x.name.endsWith(".bind.yaml") }.orEmpty()) {
            for (line in f.readLines()) {
                val key = line.substringBefore(':').trim()
                val expr = Regex("""expr:\s*"([^"]*)"""").find(line)?.groupValues?.get(1) ?: continue
                val returnsRef = expr.startsWith("kira::at({self}") && !expr.contains("=") ||
                    expr.startsWith("kira::unwrap({self}") || expr == "{self}[{0}]" || expr == "{self}.unwrap()" || expr == "{self}.unwrapErr()"
                if (returnsRef) {
                    found.add(key)
                }
            }
        }
        assertEquals(Rules.ACCESSORS.toSortedSet(), found)
    }
}
