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
 * The migration for the removed `for` and `until`: `for NAME: TYPE in EXPR {` is `while NAME: TYPE in EXPR {`, and a head over
 * `a.until(b)` is `a..<b`, over `xs.indices()` is `0..<xs.size()` (`0..<s.length()` for a Str the file writes the type of), so
 * `kira --fix-for FILE...` swaps the keyword, rewrites those heads and touches nothing else (spacing, tabs, comments and line ends
 * stay). It reads the file's tokens, so a `for` in a string or a comment is never one. A `for` whose head is not that form (the
 * old parenthesised head, or no `NAME: TYPE`) and an `indices()` whose receiver's kind the file does not write are left and listed
 * with why. A second run finds nothing.
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

    /** One text edit on a line: [length] characters at [col] (0-based) become [text]. */
    private class Edit(val line: Int, val col: Int, val length: Int, val text: String)

    /** [source] with each `for NAME: TYPE in EXPR {` made `while NAME: TYPE in EXPR {`, and each head over `a.until(b)` or `xs.indices()` made `a..<b` or `0..<xs.size()`. */
    fun rewrite(source: String, file: String = "fix.kira"): Result {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val kinds = declaredKinds(tokens)
        val edits = ArrayList<Edit>()
        val skipped = ArrayList<String>()
        var fixed = 0
        for ((i, t) in tokens.withIndex()) {
            val isFor = t.type == Token.Type.K_FOR
            if (!isFor && t.type != Token.Type.K_WHILE) continue
            val where = "line ${t.canonicalLocation.lineNumber}"
            val a = tokens.getOrNull(i + 1)
            val b = tokens.getOrNull(i + 2)
            if (!isFor && (a?.type != Token.Type.IDENTIFIER || b?.type != Token.Type.S_COLON)) continue
            if (isFor && a?.type == Token.Type.S_OPEN_PARENTHESIS) {
                skipped += "$where: a parenthesised head, 'for (x: T in e) { }'; write 'while x: T in e { }'"
                continue
            }
            if (isFor && (a?.type != Token.Type.IDENTIFIER || b?.type != Token.Type.S_COLON)) {
                skipped += "$where: the head is not 'NAME: TYPE in EXPR'"
                continue
            }
            val inAt = inIndex(tokens, i + 3)
            if (inAt < 0) {
                if (isFor) skipped += "$where: the head has no 'in'"
                continue
            }
            var changed = false
            if (isFor) {
                edits += Edit(t.canonicalLocation.lineNumber, t.canonicalLocation.column - 1, 3, "while")
                changed = true
            }
            val head = headEdits(tokens, inAt, kinds, where, skipped)
            if (head != null) {
                edits += head
                changed = true
            }
            if (changed) fixed++
        }
        if (edits.isEmpty()) return Result(source, 0, skipped)
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        // Last first, so an edit moves no position still to be read.
        for (e in edits.sortedWith(compareByDescending<Edit> { it.line }.thenByDescending { it.col })) {
            val raw = lines.getOrNull(e.line - 1)
            if (raw == null || e.col + e.length > raw.length) {
                skipped += "line ${e.line}: an edit is not where the lexer put it"
                continue
            }
            lines[e.line - 1] = raw.substring(0, e.col) + e.text + raw.substring(e.col + e.length)
        }
        return Result(lines.joinToString(""), fixed, skipped)
    }

    /** The index of the head's `in` (outside parentheses and brackets, before the body's `{`), from token [from]; -1 when there is none. */
    private fun inIndex(tokens: List<Token>, from: Int): Int {
        var depth = 0
        for (k in from until tokens.size) {
            when (tokens[k].type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth--
                Token.Type.S_OPEN_BRACE -> if (depth <= 0) return -1
                Token.Type.K_IN -> if (depth <= 0) return k
                else -> {}
            }
        }
        return -1
    }

    /** The index of the body's `{` after the head that begins at token [from]. */
    private fun bodyIndex(tokens: List<Token>, from: Int): Int {
        var depth = 0
        for (k in from until tokens.size) {
            when (tokens[k].type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth--
                Token.Type.S_OPEN_BRACE -> if (depth <= 0) return k
                else -> {}
            }
        }
        return -1
    }

    /** The edits that make the iterable after the `in` at [inAt] a `..<` range, or null when it is neither `a.until(b)` nor `xs.indices()` (or is one left for a person, listed in [skipped]). */
    private fun headEdits(tokens: List<Token>, inAt: Int, kinds: Map<String, Set<String>>, where: String, skipped: MutableList<String>): List<Edit>? {
        val open = bodyIndex(tokens, inAt + 1)
        if (open < 0 || open - 1 <= inAt + 1) return null
        val last = open - 1
        // `... . name ( ... )` closing at the head's end.
        if (tokens[last].type != Token.Type.S_CLOSE_PARENTHESIS) return null
        var depth = 0
        var callOpen = -1
        for (k in last downTo inAt + 1) {
            when (tokens[k].type) {
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth++
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth--
                else -> {}
            }
            if (depth == 0) {
                callOpen = k
                break
            }
        }
        if (callOpen < inAt + 3) return null
        val name = tokens[callOpen - 1]
        val dot = tokens[callOpen - 2]
        if (name.type != Token.Type.IDENTIFIER || dot.type != Token.Type.S_DOT) return null
        val dotAt = dot.canonicalLocation
        val oneLine = tokens[inAt + 1].canonicalLocation.lineNumber == dotAt.lineNumber && dotAt.lineNumber == tokens[last].canonicalLocation.lineNumber
        when (name.content) {
            "until" -> {
                if (callOpen == last) return null
                if (!oneLine) {
                    skipped += "$where: a head over lines, `a.until(b)`; write a..<b"
                    return null
                }
                val simple = simpleOperand(tokens.subList(callOpen + 1, last))
                val argFirst = tokens[callOpen + 1].canonicalLocation
                val lastAt = tokens[last].canonicalLocation
                // `.until(` becomes `..<`, the `)` goes; a compound end gets parentheses, as `..<` binds tighter than `+`.
                return listOf(
                    Edit(dotAt.lineNumber, dotAt.column - 1, argFirst.column - dotAt.column, if (simple) "..<" else "..<("),
                    Edit(lastAt.lineNumber, lastAt.column - 1, 1, if (simple) "" else ")"),
                )
            }
            "indices" -> {
                if (callOpen != last - 1) return null
                if (!oneLine) {
                    skipped += "$where: a head over lines, `xs.indices()`; write 0..<xs.size()"
                    return null
                }
                val recv = tokens.subList(inAt + 1, callOpen - 2)
                if (recv.isEmpty()) return null
                val text = recv.joinToString("") { it.content }
                val kind = kindOf(recv, kinds)
                if (kind == null || !simpleOperand(recv)) {
                    skipped += "$where: `$text.indices()` of a receiver whose kind the file does not write; write 0..<xs.size(), or 0..<s.length() for a Str"
                    return null
                }
                val first = recv.first().canonicalLocation
                val closeAt = tokens[last].canonicalLocation
                val sz = if (kind == "Str") "length" else "size"
                return listOf(
                    Edit(first.lineNumber, first.column - 1, 0, "0..<"),
                    Edit(dotAt.lineNumber, dotAt.column - 1, closeAt.column - dotAt.column + 1, ".$sz()"),
                )
            }
        }
        return null
    }

    /** Whether [t] reads as one operand where `..<` binds tighter than any operator: names, member chains, literals, and any parenthesised or called group. */
    internal fun simpleOperand(t: List<Token>): Boolean {
        if (t.isEmpty()) return false
        var depth = 0
        for (k in t) {
            when (k.type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth--
                Token.Type.IDENTIFIER, Token.Type.S_DOT, Token.Type.K_THIS, Token.Type.L_INTEGER, Token.Type.L_STRING, Token.Type.L_FLOAT -> {}
                else -> if (depth <= 0) return false
            }
        }
        return depth == 0
    }

    /** The kind (`Str`, or `Seq` for a List, Arr, View or MutView) of an `indices()` receiver the file writes the type of; null when it does not, or writes more than one. */
    private fun kindOf(recv: List<Token>, kinds: Map<String, Set<String>>): String? {
        if (recv.size == 1 && recv[0].type == Token.Type.L_STRING) return "Str"
        if (recv.size == 1 && recv[0].type == Token.Type.IDENTIFIER) return kinds[recv[0].content]?.singleOrNull()?.takeIf { it != "Other" }
        return null
    }

    /** Every `NAME: TYPE` the file writes, by name: `Str`, `Seq` (a List, Arr, View or MutView, a Mutable of one too), or `Other`. */
    private fun declaredKinds(tokens: List<Token>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (i in 0 until tokens.size - 2) {
            if (tokens[i].type != Token.Type.IDENTIFIER || tokens[i + 1].type != Token.Type.S_COLON) continue
            var k = i + 2
            // `Mutable<T>` is T's kind.
            while (tokens.getOrNull(k)?.content == "Mutable" && tokens.getOrNull(k + 1)?.type == Token.Type.S_OPEN_ANGLE) k += 2
            val n = tokens.getOrNull(k)
            val generic = tokens.getOrNull(k + 1)?.type == Token.Type.S_OPEN_ANGLE
            val kind = when {
                n?.type != Token.Type.IDENTIFIER -> "Other"
                n.content == "Str" && !generic -> "Str"
                n.content in setOf("List", "Arr", "View", "MutView") && generic -> "Seq"
                else -> "Other"
            }
            out.getOrPut(tokens[i].content) { HashSet() } += kind
        }
        return out
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
