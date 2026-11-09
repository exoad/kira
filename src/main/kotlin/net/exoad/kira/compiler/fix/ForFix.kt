package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The migration for the removed `for`: `for NAME: TYPE in EXPR {` is `while NAME: TYPE in EXPR {`, so `kira --fix-for FILE...`
 * swaps the keyword and touches nothing else (spacing, tabs, comments and line ends stay). It reads the file's tokens, so a
 * `for` in a string or a comment is never one. A `for` whose head is not that form (the old parenthesised head, or no
 * `NAME: TYPE`) is left and listed with why. A second run finds nothing.
 */
object ForFix {
    /** What one file's rewrite did: loops rewritten, and each one left alone with why. */
    data class Result(val text: String, val fixed: Int, val skipped: List<String>)

    private val HEAD = Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*.+?\\s+in\\s+.+$", RegexOption.DOT_MATCHES_ALL)

    /** The diagnostic's text for a `for` whose head (what stands between `for` and the body's `{`) is [text]: the `while` it becomes. */
    fun message(text: String): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        val tail = " `kira --fix-for FILE...` rewrites a file."
        if (!HEAD.matches(one)) {
            return "`for` is removed: a loop over a range or a collection is `while NAME: TYPE in EXPR { }`, every other loop `while <condition> { }`.$tail"
        }
        return "`for` is removed: for '$one' write 'while $one { }'.$tail"
    }

    /** [source] with each `for NAME: TYPE in EXPR {` made `while NAME: TYPE in EXPR {`. */
    fun rewrite(source: String, file: String = "fix.kira"): Result {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val fors = ArrayList<Token>()
        val skipped = ArrayList<String>()
        for ((i, t) in tokens.withIndex()) {
            if (t.type != Token.Type.K_FOR) continue
            val where = "line ${t.canonicalLocation.lineNumber}"
            val a = tokens.getOrNull(i + 1)
            val b = tokens.getOrNull(i + 2)
            when {
                a?.type == Token.Type.S_OPEN_PARENTHESIS -> skipped += "$where: a parenthesised head, 'for (x: T in e) { }'; write 'while x: T in e { }'"
                a?.type != Token.Type.IDENTIFIER || b?.type != Token.Type.S_COLON -> skipped += "$where: the head is not 'NAME: TYPE in EXPR'"
                !hasIn(tokens, i + 3) -> skipped += "$where: the head has no 'in'"
                else -> fors += t
            }
        }
        if (fors.isEmpty()) return Result(source, 0, skipped)
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        var fixed = 0
        for (t in fors.sortedWith(compareByDescending<Token> { it.canonicalLocation.lineNumber }.thenByDescending { it.canonicalLocation.column })) {
            val ln = t.canonicalLocation.lineNumber
            val col = t.canonicalLocation.column - 1
            val raw = lines.getOrNull(ln - 1)
            if (raw == null || !raw.startsWith("for", col)) {
                skipped += "line $ln: the 'for' is not where the lexer put it"
                continue
            }
            lines[ln - 1] = raw.substring(0, col) + "while" + raw.substring(col + 3)
            fixed++
        }
        return Result(lines.joinToString(""), fixed, skipped)
    }

    /** Whether an `in` comes before the body's `{` (the first brace outside parentheses and brackets), from token [from]. */
    private fun hasIn(tokens: List<Token>, from: Int): Boolean {
        var depth = 0
        for (k in from until tokens.size) {
            when (tokens[k].type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth--
                Token.Type.S_OPEN_BRACE -> if (depth <= 0) return false
                Token.Type.K_IN -> if (depth <= 0) return true
                else -> {}
            }
        }
        return false
    }

    /** Rewrites [file] in place (written beside, synced, renamed over). */
    fun rewriteFile(file: File): Result {
        val r = rewrite(file.readText(), file.path)
        if (r.fixed != 0) writeFile(file, r.text)
        return r
    }

    /** [text] over [file]: written beside, synced, renamed over. */
    fun writeFile(file: File, text: String) {
        val beside = File(file.path + ".fix")
        Files.newByteChannel(beside.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            (ch as java.nio.channels.FileChannel).write(java.nio.ByteBuffer.wrap(text.toByteArray())); ch.force(true)
        }
        Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
