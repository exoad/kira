package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.backend.codegen.cpp.CppBinding
import net.exoad.kira.compiler.backend.codegen.cpp.CppBindingTable
import net.exoad.kira.compiler.backend.codegen.cpp.CppPrec
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The `cpp:` maps of the stdlib binding manifests (design 7.4) as CppBindingTable reads and expands them. */
class CppBindingTableTest {
    private val table = CppBindingTable().also { it.loadDir(Path.of("kira")) }

    @Test
    fun everyManifestEntryWithACppMapIsLoaded() {
        val manifests = File("kira").listFiles { f -> f.name.endsWith(".bind.yaml") }!!.sortedBy { it.name }
        var entries = 0
        manifests.forEach { m ->
            CppBindingTable.parse(m.toPath()).forEach { (key, b) ->
                entries += 1
                assertEquals(b, table.lookup(key), "$key from ${m.name}")
                assertTrue(b.expr.isNotBlank(), key)
            }
        }
        assertTrue(entries > 150, "expected the stdlib's bindings, found $entries")
        assertEquals(CppBinding("kira::str::length({self})", pure = true), table.lookup("Str.length"))
        assertEquals(listOf("<cmath>"), table.lookup("sqrt")!!.includes)
    }

    @Test
    fun placeholdersTakeTheirPositionsPrecedence() {
        val seen = mutableListOf<Pair<String, Int>>()
        val text = CppBindingTable.expand(
            CppBinding("kira::at({self}, {0}) = {1}"),
            { p -> seen += "self" to p; "xs" },
            { i, p -> seen += "$i" to p; "a$i" },
            emptyList(),
        )
        assertEquals("kira::at(xs, a0) = a1", text)
        assertEquals(listOf("self" to CppPrec.ASSIGN, "0" to CppPrec.ASSIGN, "1" to CppPrec.ASSIGN), seen)

        seen.clear()
        CppBindingTable.expand(CppBinding("({self} == {0})"), { p -> seen += "self" to p; "a" }, { i, p -> seen += "$i" to p; "b" }, emptyList())
        assertEquals(listOf("self" to CppPrec.UNARY, "0" to CppPrec.UNARY), seen, "an operand of == is parenthesized unless unary or tighter")

        seen.clear()
        CppBindingTable.expand(CppBinding("{self}.push_back({0})"), { p -> seen += "self" to p; "xs" }, { i, p -> seen += "$i" to p; "v" }, emptyList())
        assertEquals(listOf("self" to CppPrec.POSTFIX, "0" to CppPrec.ASSIGN), seen, "the object of . is a postfix expression")

        assertEquals(
            "kira::View<std::uint8_t>(mv)",
            CppBindingTable.expand(CppBinding("kira::View<{T0}>({self})"), { "mv" }, { _, _ -> "" }, listOf("std::uint8_t")),
        )
    }

    @Test
    fun aBindingsOwnPrecedenceIsItsLoosestTopLevelOperator() {
        assertEquals(CppPrec.POSTFIX, CppBindingTable.precOf("kira::str::length(s)"))
        assertEquals(CppPrec.POSTFIX, CppBindingTable.precOf("kira::View<std::uint8_t>(mv)"))
        assertEquals(CppPrec.POSTFIX, CppBindingTable.precOf("static_cast<std::int32_t>(0)"))
        assertEquals(CppPrec.EQ, CppBindingTable.precOf("v.size() == 0"))
        assertEquals(CppPrec.UNARY, CppBindingTable.precOf("!kira::isSome(m)"))
        assertEquals(CppPrec.ASSIGN, CppBindingTable.precOf("kira::at(xs, i) = v"))
        assertEquals(CppPrec.COMMA, CppBindingTable.precOf("b.clear(), b.add(v)"))
        assertEquals(CppPrec.POSTFIX, CppBindingTable.precOf("(std::min)(a, b)"))
        assertEquals(CppPrec.POSTFIX, CppBindingTable.precOf("kira::cat(\"a == b\", x)"), "an operator inside a literal is no operator")
    }

    @Test
    fun aMemberStyleBindingIsTheOneAStrConstantReceiverIsWrappedFor() {
        assertTrue(CppBindingTable.isMemberStyle(CppBinding("{self}.size()")))
        assertTrue(CppBindingTable.isMemberStyle(CppBinding("({self}.size() == 0)")))
        assertTrue(CppBindingTable.isMemberStyle(CppBinding("{self}->bind({0}, {1})")))
        assertTrue(!CppBindingTable.isMemberStyle(CppBinding("kira::str::length({self})")))
    }

    @Test
    fun aSystemHeaderIncludeKeepsItsAngleBrackets() {
        assertEquals("<cmath>", CppBindingTable.includeName("<cmath>"))
        assertEquals("kira/os.hxx", CppBindingTable.includeName(" kira/os.hxx "))
    }

    @Test
    fun everyMagicMethodKeyResolvesThroughTheReceiversParents() {
        // Num.abs serves every scalar (the manifests key Num's methods once).
        assertNotNull(table.lookup("Num.abs"))
        assertEquals(null, table.lookup("Int32.abs"))
    }

    // ---- R-A's LEND, pinned from the emitter's side (40-round3 5.4, item 4) ---------------------

    /**
     * Whether the binding's C++ is one accessor applied to its receiver, so its result is a
     * reference into the receiver's storage (`kira::at` and `kira::unwrap` return `const T&`,
     * `operator[]` of a view and `Result::unwrap`/`unwrapErr` an lvalue): the whole expansion is
     * `kira::at({self}, ...)`, `kira::unwrap({self})`, `{self}[...]`, `{self}.unwrap()` or
     * `{self}.unwrapErr()`. A write through one (`kira::at({self}, {0}) = {1}`) is no accessor.
     */
    private fun returnsIntoReceiver(expr: String): Boolean {
        val e = expr.trim()
        fun oneCall(head: String): Boolean {
            if (!e.startsWith(head)) {
                return false
            }
            var depth = 0
            // From the head's own bracket, which must close at the last character.
            for (i in head.indexOfFirst { it == '(' || it == '[' } until e.length) {
                when (e[i]) {
                    '(', '[' -> depth += 1
                    ')', ']' -> {
                        depth -= 1
                        if (depth == 0) {
                            return i == e.length - 1
                        }
                    }
                }
            }
            return false
        }
        return oneCall("kira::at({self}") || oneCall("kira::unwrap({self}") || oneCall("{self}[") ||
            e == "{self}.unwrap()" || e == "{self}.unwrapErr()"
    }

    /** The keys of [dir]'s manifests whose binding [returnsIntoReceiver]. */
    private fun lendingKeys(dir: File): Set<String> =
        dir.listFiles { f -> f.name.endsWith(CppBindingTable.MANIFEST_SUFFIX) }.orEmpty()
            .flatMap { m -> CppBindingTable.parse(m.toPath()).filter { (_, b) -> returnsIntoReceiver(b.expr) }.keys }
            .toSortedSet()

    @Test
    fun theBindingsThatReturnAReferenceIntoTheirReceiverAreExactlyRulesAccessors() {
        // The two halves of R-A pinned against each other: the rules' LEND set (Rules.ACCESSORS,
        // whose results LentPlaces makes places) and the C++ the manifests spell. A binding added
        // that returns a reference into its receiver, and is not in the set, would be a lent
        // result every analysis calls a temporary (round 2's use-after-frees); one in the set that
        // returned by value would make a copy a place. kira/cpp/tests/rt_test.cxx pins that the
        // helpers return lvalue references, and that Stack.peek, Queue.peek and Map.get do not.
        assertEquals(net.exoad.kira.compiler.analysis.types.rules.Rules.ACCESSORS.toSortedSet(), lendingKeys(File("kira")))
        listOf("Stack.peek", "Queue.peek", "Map.get", "Maybe.unwrapOr", "List.set", "Arr.set").forEach { key ->
            val b = assertNotNull(table.lookup(key), key)
            assertTrue(!returnsIntoReceiver(b.expr), "$key returns by value: ${b.expr}")
        }
    }

    @Test
    fun theAccessorPinFailsOnAnAccessorTheRulesDoNotKnow() {
        // 40-round3 6.1, "the pin bites": a scratch copy of the manifests with a fake List.first
        // spelled as an accessor is no longer the rules' set.
        val scratch = kotlin.io.path.createTempDirectory("lend-pin").toFile()
        try {
            File("kira").listFiles { f -> f.name.endsWith(CppBindingTable.MANIFEST_SUFFIX) }.orEmpty().forEach { it.copyTo(File(scratch, it.name)) }
            File(scratch, "collections.bind.yaml").appendText("\nList.first:    { cpp: { expr: \"kira::at({self}, 0)\", pure: true } }\n")
            val found = lendingKeys(scratch)
            assertTrue("List.first" in found, found.toString())
            assertTrue(found != net.exoad.kira.compiler.analysis.types.rules.Rules.ACCESSORS.toSortedSet(), found.toString())
        } finally {
            scratch.deleteRecursively()
        }
    }
}
