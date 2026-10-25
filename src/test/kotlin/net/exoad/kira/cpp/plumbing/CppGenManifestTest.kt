package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppGenManifest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CppGenManifestTest {
    private val files = listOf(
        "src/pilot/proto.kira.hxx" to "// proto\n".toByteArray(),
        "firmware/lib/kira/rt.hxx" to "// rt\n".toByteArray(),
        "firmware/lib/text.kira.hxx" to "// text\n".toByteArray(),
    )

    @Test
    fun sha256IsTheStandardDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CppGenManifest.sha256(ByteArray(0))
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            CppGenManifest.sha256("hello".toByteArray())
        )
    }

    @Test
    fun rendersSortedEntriesWithVersionAndStdlibHash() {
        val stdlib = CppGenManifest.stdlibHash(files.filter { it.first.contains("/kira/") })
        val text = CppGenManifest.render("abc123", stdlib, files)
        val expected = """
            |kira.gen.manifest 1
            |compiler abc123
            |stdlib $stdlib
            |${CppGenManifest.sha256("// rt\n".toByteArray())}  firmware/lib/kira/rt.hxx
            |${CppGenManifest.sha256("// text\n".toByteArray())}  firmware/lib/text.kira.hxx
            |${CppGenManifest.sha256("// proto\n".toByteArray())}  src/pilot/proto.kira.hxx
            |""".trimMargin()
        assertEquals(expected, text)
    }

    @Test
    fun orderOfInputDoesNotChangeTheOutput() {
        val a = CppGenManifest.render("v", "s", files)
        val b = CppGenManifest.render("v", "s", files.reversed())
        assertEquals(a, b)
    }

    @Test
    fun stdlibHashChangesWhenARuntimeFileChanges() {
        val one = CppGenManifest.stdlibHash(listOf("kira/rt.hxx" to "a".toByteArray()))
        val two = CppGenManifest.stdlibHash(listOf("kira/rt.hxx" to "b".toByteArray()))
        val renamed = CppGenManifest.stdlibHash(listOf("kira/rt2.hxx" to "a".toByteArray()))
        assertNotEquals(one, two)
        assertNotEquals(one, renamed)
    }

    @Test
    fun parseRoundTrips() {
        val text = CppGenManifest.render("abc123", "deadbeef", files)
        val parsed = CppGenManifest.parse(text)!!
        assertEquals("abc123", parsed.compiler)
        assertEquals("deadbeef", parsed.stdlib)
        assertEquals(files.map { it.first }.sorted(), parsed.entries.map { it.path })
        assertNull(CppGenManifest.parse("not a manifest\n"))
    }
}
