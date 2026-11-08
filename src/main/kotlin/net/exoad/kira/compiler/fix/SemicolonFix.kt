package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File

/**
 * The migration for the removed `;`: a statement ends at its newline, so `kira --fix-semicolons FILE...` drops each `;` that ends
 * a statement. It reads the file's tokens, so a `;` in a string, a char or a comment is never one; a `;` inside parentheses or
 * brackets ends no statement and is left, and so is a file the lexer refuses. A `;` with code after it on the line becomes a
 * line break there, at the line's indentation. Line ends (LF or CRLF), tabs and comments are kept, and a second run finds nothing.
 */
object SemicolonFix {
    /** What one file's fix did: the new [text], the `;` dropped, and each one left alone with why. */
    class Result(val text: String, val dropped: Int, val left: List<String>)

    /** [source] with each statement-ending `;` dropped. */
    fun rewrite(source: String, file: String = "fix.kira"): Result {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val semis = ArrayList<Token>()
        val left = ArrayList<String>()
        var depth = 0
        for (t in tokens) {
            when (t.type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> if (depth > 0) depth--
                Token.Type.S_SEMICOLON -> if (t.content == ";") {
                    if (depth > 0) left += "line ${t.canonicalLocation.lineNumber}: a ';' inside parentheses or brackets ends no statement" else semis += t
                }
                else -> {}
            }
        }
        if (semis.isEmpty()) return Result(source, 0, left)
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        var dropped = 0
        // Last first, so a line break added on one line moves no position still to be read.
        for (t in semis.sortedWith(compareByDescending<Token> { it.canonicalLocation.lineNumber }.thenByDescending { it.canonicalLocation.column })) {
            val ln = t.canonicalLocation.lineNumber
            val col = t.canonicalLocation.column - 1
            val raw = lines.getOrNull(ln - 1)
            if (raw == null || col !in raw.indices || raw[col] != ';') {
                left += "line $ln: the ';' is not where the lexer put it"
                continue
            }
            val eol = raw.takeLastWhile { it == '\n' || it == '\r' }
            val body = raw.substring(0, raw.length - eol.length)
            val before = body.substring(0, col)
            val after = body.substring(col + 1)
            val rest = after.trimStart()
            lines[ln - 1] = when {
                // Nothing after it, or only a comment: the `;` goes, and trailing space with it when no comment follows.
                rest.isEmpty() -> before.trimEnd() + eol
                rest.startsWith("//") || rest.startsWith("/*") -> before + after + eol
                // Code after it: a new line at this line's indentation.
                else -> {
                    val indent = body.takeWhile { it == ' ' || it == '\t' }
                    val lineEnd = if (eol.isEmpty()) "\n" else eol
                    before.trimEnd() + lineEnd + indent + rest + eol
                }
            }
            dropped++
        }
        return Result(lines.joinToString(""), dropped, left.sorted())
    }

    /** Rewrites [file] in place (written beside, synced, renamed over): its result, or null with why the file was left. */
    fun rewriteFile(file: File): Pair<Result?, String?> {
        val result = try {
            rewrite(file.readText(), file.path)
        } catch (e: Throwable) {
            return null to "refused (${e.message?.lineSequence()?.firstOrNull()})"
        }
        if (result.dropped != 0) ForFix.writeFile(file, result.text)
        return result to null
    }
}
