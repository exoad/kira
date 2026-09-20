package net.exoad.kira.lsp

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspPathsTest {
    @Test
    fun roundTripsFileUri() {
        val path = File("examples/01-hello/src/main.kira").canonicalPath
        val uri = LspPaths.pathToUri(path)
        assertTrue(uri.startsWith("file:"))
        val back = LspPaths.uriToPath(uri).toString()
        assertEquals(path, back)
    }
}
