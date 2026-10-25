package net.exoad.kira

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.compiler.CompilationUnit
import org.junit.jupiter.api.Test
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every stdlib module under `kira/` must parse with the lexer and parser as
 * they are today.
 *
 * [CompilationUnit.init] bootstraps the stdlib into every unit and swallows
 * any exception the bootstrap raises, so a stdlib file that stopped parsing
 * would vanish silently: the magic types it declares would simply be missing
 * and every later failure would blame user code. This test parses each file
 * on its own, outside that catch, and fails naming the file and line.
 */
class StdlibParseTest {
    @Test
    fun everyStdlibModuleParsesWithTodaysFrontend() {
        val root = File("kira")
        assertTrue(root.isDirectory, "stdlib kira/ dir must be at cwd")

        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kira" }
            .sortedBy { it.path }
            .toList()
        assertTrue(sources.isNotEmpty(), "no stdlib sources found under kira/")

        val failures = sources.mapNotNull { file -> parseFailureOf(file) }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString("\n"))
        }
    }

    /**
     * Lexes and parses [file] with the front end and reports the first
     * problem as `<file>:<line>: <message>`, or null when it parsed clean.
     * A logged warning or error counts as a problem too: a diagnostic the
     * parser only logs still means the file is not what the parser expects.
     */
    private fun parseFailureOf(file: File): String? {
        val logged = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) {
                    logged.add(record.message)
                }
            }

            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("net.exoad.kira")
        logger.addHandler(handler)
        try {
            val processed = KiraPreprocessor(file.readText()).process()
            val unit = CompilationUnit()
            val path = file.canonicalPath
            val ctx = unit.addSource(path, processed.processedContent, emptyList())
            val tokens = KiraLexer(ctx).tokenize()
            interpolationIn(tokens)?.let { token ->
                // Today's lexer keeps `${...}` as literal text, so a parse
                // alone cannot see it; once W1.1's interpolation lands the
                // same characters mean something else. RULE 1 forbids it.
                return "${file.path}:${token.canonicalLocation.lineNumber}: string literal holds " +
                    "an interpolation, which the stdlib may not use: ${token.content}"
            }
            val withTokens = unit.addSource(path, ctx.content, tokens)
            KiraSourceParsers.from(withTokens).parse()
        } catch (e: DiagnosticsException) {
            val line = e.location?.lineNumber?.toString() ?: "?"
            return "${file.path}:$line: ${e.tag}: ${e.message}"
        } catch (e: Exception) {
            return "${file.path}:?: ${e::class.simpleName}: ${e.message}"
        } finally {
            logger.removeHandler(handler)
        }
        if (logged.isNotEmpty()) {
            return "${file.path}:?: parser logged ${logged.size} diagnostic(s): ${logged.joinToString(" | ")}"
        }
        return null
    }

    /** The first string literal token that spells `${`, or null. */
    private fun interpolationIn(tokens: List<Token>): Token? {
        return tokens.firstOrNull { it.type == Token.Type.L_STRING && it.content.contains("\${") }
    }
}
