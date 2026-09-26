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
    fun aSystemHeaderIncludeIsHandedOnAsItsQuotedName() {
        assertEquals("cmath", CppBindingTable.includeName("<cmath>"))
        assertEquals("kira/os.hxx", CppBindingTable.includeName("kira/os.hxx"))
    }

    @Test
    fun everyMagicMethodKeyResolvesThroughTheReceiversParents() {
        // Num.abs serves every scalar (the manifests key Num's methods once).
        assertNotNull(table.lookup("Num.abs"))
        assertEquals(null, table.lookup("Int32.abs"))
    }
}
