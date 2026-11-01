package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The migration for inclusive ranges (owner, 2026-11-01): each `for i: T in a..b` written when a loop's
 * range left its end out becomes `xs.indices()` (a = 0, b = `xs.size()` or `xs.length()`, T = Size, and
 * the body indexes xs) or `a.until(b)`, which count exactly the old steps. Nothing else in a file changes.
 */
object RangeLoopFix {
    /** What one file's rewrite did: loops made `indices()`, made `until()`, and each one left alone with why. */
    data class Result(val text: String, val indices: Int, val until: Int, val skipped: List<String>)

    private val CHAIN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun rewrite(source: String, file: String = "fix.kira"): Result {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val ctx = SourceContext(processed, file, tokens)
        KiraParser.parse(ctx)
        val loops = ArrayList<ForIterationStatement>()
        AstTree.walk(ctx.ast) { if (it is ForIterationStatement && it.forIterationExpr.target is RangeExpr) loops.add(it) }
        if (loops.isEmpty()) return Result(source, 0, 0, emptyList())
        val raw = source.split(Regex("(?<=\n)")).toMutableList()
        val seen = processed.split(Regex("(?<=\n)"))
        var indices = 0
        var until = 0
        val skipped = ArrayList<String>()
        val edits = ArrayList<Triple<Int, IntRange, String>>()
        for (loop in loops) {
            val range = loop.forIterationExpr.target as RangeExpr
            val at = ctx.astOrigins[range] ?: continue
            val line = at.lineNumber
            val where = "$file:$line"
            val text = seen.getOrNull(line - 1)?.trimEnd('\n', '\r') ?: continue
            val start = at.column - 1
            val stop = headEnd(text, start)
            if (stop == null) {
                skipped += "$where: the loop's head does not end on its line"
                continue
            }
            val head = text.substring(start, stop).trimEnd()
            val rawLine = raw[line - 1]
            if (rawLine.length < start + head.length || rawLine.substring(start, start + head.length) != head) {
                skipped += "$where: a comment or macro moves the head's columns"
                continue
            }
            val dots = topLevelDots(head)
            if (dots == null) {
                skipped += "$where: no `..` outside parentheses in `$head`"
                continue
            }
            val b = unwrap(head.substring(0, dots).trim())
            val e = unwrap(head.substring(dots + 2).trim())
            val declared = loop.forIterationExpr.declaredType?.let { KiraUnparser.type(it) }
            val receiver = Regex("(.+)\\.(size|length)\\(\\)").matchEntire(e)?.groupValues?.get(1)
            val replacement = if (b == "0" && declared == "Size" && receiver != null && CHAIN.matches(receiver) && indexes(loop, receiver)) {
                indices++
                "$receiver.indices()"
            } else {
                until++
                "${if (postfix(b)) b else "($b)"}.until($e)"
            }
            edits += Triple(line - 1, start until start + head.length, replacement)
        }
        for ((i, span, replacement) in edits.sortedWith(compareByDescending<Triple<Int, IntRange, String>> { it.first }.thenByDescending { it.second.first })) {
            raw[i] = raw[i].substring(0, span.first) + replacement + raw[i].substring(span.last + 1)
        }
        return Result(raw.joinToString(""), indices, until, skipped)
    }

    /** Where the head that starts at [from] ends: the body's `{` (or a parenthesized head's `)`) outside brackets and strings. */
    private fun headEnd(line: String, from: Int): Int? {
        var depth = 0
        var i = from
        while (i < line.length) {
            when (val ch = line[i]) {
                '"', '\'' -> i = skipQuoted(line, i, ch) ?: return null
                '(', '[' -> depth++
                ')', ']' -> if (depth == 0) return i else depth--
                '{' -> if (depth == 0) return i else depth++
                '}' -> depth--
            }
            i++
        }
        return null
    }

    private fun skipQuoted(line: String, open: Int, q: Char): Int? {
        var i = open + 1
        while (i < line.length) {
            if (line[i] == '\\') i++ else if (line[i] == q) return i
            i++
        }
        return null
    }

    private fun topLevelDots(head: String): Int? {
        var depth = 0
        var i = 0
        while (i < head.length - 1) {
            when (head[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '.' -> if (depth == 0 && head[i + 1] == '.') return i
            }
            i++
        }
        return null
    }

    /** Whether [s] is a name, literal, call or member chain, which `.until` may follow unparenthesized. */
    private fun postfix(s: String): Boolean {
        if (s.isEmpty() || !(s[0].isLetterOrDigit() || s[0] == '_')) return false
        var depth = 0
        for (ch in s) {
            when {
                ch == '(' || ch == '[' -> depth++
                ch == ')' || ch == ']' -> depth--
                depth == 0 && !(ch.isLetterOrDigit() || ch == '_' || ch == '.') -> return false
            }
        }
        return true
    }

    /** [s] without one pair of parentheses that holds all of it: `0..(n + 1)` gives `n + 1`. */
    private fun unwrap(s: String): String {
        if (!s.startsWith("(") || !s.endsWith(")")) return s
        var depth = 0
        for ((i, ch) in s.withIndex()) {
            if (ch == '(') depth++ else if (ch == ')') depth--
            if (depth == 0 && i < s.length - 1) return s
        }
        return s.substring(1, s.length - 1)
    }

    /** Whether [loop]'s body indexes [receiver] (`receiver[...]`). */
    private fun indexes(loop: ForIterationStatement, receiver: String): Boolean {
        var found = false
        loop.body.forEach { st -> AstTree.walk(st) { n -> if (n is ArrayIndexExpr && KiraUnparser.text(n.originExpr) == receiver) found = true } }
        return found
    }

    /** Rewrites [file] in place (written beside, synced, renamed over). */
    fun rewriteFile(file: File): Result {
        val r = rewrite(file.readText(), file.path)
        if (r.indices + r.until == 0) return r
        val beside = File(file.path + ".fix")
        Files.newByteChannel(beside.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            (ch as java.nio.channels.FileChannel).write(java.nio.ByteBuffer.wrap(r.text.toByteArray())); ch.force(true)
        }
        Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return r
    }
}
