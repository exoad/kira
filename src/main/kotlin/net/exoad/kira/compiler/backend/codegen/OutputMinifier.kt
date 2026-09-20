package net.exoad.kira.compiler.backend.codegen

/**
 * Deterministic, token-safe minifier and identifier obfuscator for Kira's
 * generated user layers (C and JS).
 *
 * The pass is applied to the *user* portion of an emitted artifact (everything
 * after the runtime prelude). The prelude is the shared runtime and stays
 * readable; the user's module surface is what gets compressed and renamed.
 *
 * Guarantees:
 *  - comments and insignificant whitespace are removed
 *  - tokens are only ever *separated*, never merged: the output lexes to the
 *    same token stream as the input (modulo comments and identifier renames),
 *    so semantics are preserved
 *  - identifiers listed in the rename map are replaced consistently -- every
 *    declaration and every use -- with short generated names
 *  - prelude symbols, language keywords, entry points (`main`), and foreign
 *    edges (extern / opaque names) are never renamed: build the map with
 *    [buildRenameMap] and pass the prelude identifiers + keywords as reserved
 *  - same input + same maps => byte-identical output, so example snapshots
 *    and `regenerate.sh --check` stay stable
 */
enum class MinifyLanguage { C, JS }

object OutputMinifier {

    private enum class Kind { IDENT, NUMBER, STRING, TEMPLATE, COMMENT, PREPROC, PUNCT }

    private class Token(val kind: Kind, val text: String)

    /** Single-char punctuation that participates in operator merging checks. */
    private const val SINGLE_PUNCT = "()[]{}.,;:?!&|*+-/<=>%^~#@$"

    private val C_MULTI = setOf(
        "<<=", ">>=", "->", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
        "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "...",
    )
    private val JS_MULTI = setOf(
        ">>>=", "**=", "===", "!==", "&&=", "||=", "??=", "<<=", ">>=", ">>>",
        "=>", "...", "?.", "??", "**", "++", "--", "&&", "||", "==", "!=",
        "<=", ">=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
    )

    private val OPERATORS: Map<MinifyLanguage, Set<String>> = mapOf(
        MinifyLanguage.C to (SINGLE_PUNCT.map { it.toString() } + C_MULTI + setOf("//", "/*")).toSet(),
        MinifyLanguage.JS to (SINGLE_PUNCT.map { it.toString() } + JS_MULTI + setOf("//", "/*")).toSet(),
    )

    private val IDENT_RE = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    private fun isIdentStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()
    private fun isIdentPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()

    /**
     * Minify [source] (and optionally rename identifiers via [rename]).
     * The output ends with exactly one newline.
     */
    fun minify(language: MinifyLanguage, source: String, rename: Map<String, String> = emptyMap()): String {
        val tokens = tokenize(language, source)
        val ops = OPERATORS.getValue(language)
        val out = StringBuilder()
        var prev: Token? = null
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.kind == Kind.COMMENT) {
                if (prev != null && i + 1 < tokens.size && needsSpace(prev, tokens[i + 1], ops)) {
                    out.append(' ')
                }
                i++
                continue
            }
            if (prev != null && needsSpace(prev, tok, ops)) {
                out.append(' ')
            }
            when (tok.kind) {
                Kind.IDENT -> out.append(rename[tok.text] ?: tok.text)
                Kind.PREPROC -> {
                    out.append(tok.text)
                    out.append('\n')
                }
                else -> out.append(tok.text)
            }
            prev = tok
            i++
        }
        return out.toString().trimEnd() + "\n"
    }

    /**
     * Every identifier-looking token in [text] -- used to build the reserved
     * set from the runtime prelude. Over-reserving is safe: a reserved name is
     * simply never chosen as a rename target or a generated name.
     */
    fun extractIdentifiers(text: String): Set<String> = IDENT_RE.findAll(text).map { it.value }.toSet()

    /**
     * Build a deterministic rename map: each usable symbol (valid identifier,
     * not reserved, not `main`) gets the next short name from a generated
     * pool, skipping reserved names. Symbols are sorted alphabetically so the
     * mapping is stable across runs.
     */
    fun buildRenameMap(symbols: Collection<String>, reserved: Set<String>): Map<String, String> {
        val usable = symbols.asSequence()
            .filter { it.isNotEmpty() }
            .filter { isIdentStart(it[0]) && it.all(::isIdentPart) }
            .filter { it !in reserved && it != "main" }
            .distinct()
            .sorted()
            .toList()
        val pool = namePool().filterNot { it in reserved }.take(usable.size).toList()
        return usable.zip(pool).toMap()
    }

    private fun namePool(): Sequence<String> = sequence {
        for (c in 'a'..'z') yield(c.toString())
        for (a in 'a'..'z') {
            for (b in 'a'..'z') yield("$a$b")
        }
        var i = 0
        while (true) {
            yield("m$i")
            i++
        }
    }

    private fun needsSpace(prev: Token, next: Token, ops: Set<String>): Boolean {
        // Preprocessor directives must stay on their own line: newline before
        // a directive; the directive already emitted its own newline after.
        if (next.kind == Kind.PREPROC) return true
        if (prev.kind == Kind.PREPROC) return false

        val prevWord = prev.kind == Kind.IDENT || prev.kind == Kind.NUMBER ||
            prev.kind == Kind.STRING || prev.kind == Kind.TEMPLATE
        val nextWord = next.kind == Kind.IDENT || next.kind == Kind.NUMBER ||
            next.kind == Kind.STRING || next.kind == Kind.TEMPLATE
        if (prevWord && nextWord) {
            // Adjacent string literals concatenate in C but are two expressions
            // in JS; the space is required for JS and harmless for C.
            return true
        }
        // A number directly before a dot would lex as a fraction / pp-number
        // (1.x). Generated code never intends that, so keep the space.
        if (prev.kind == Kind.NUMBER && next.kind == Kind.PUNCT && next.text == ".") return true

        if (prev.kind == Kind.PUNCT && next.kind == Kind.PUNCT) {
            // Would the concatenation lex as one (longer) token? Max-munch:
            // space only when the longest operator prefix of prev+next is not
            // exactly prev (e.g. "- -" -> "--", "/ *" -> "/*", "+ ++" -> "+++").
            val combined = prev.text + next.text
            val prefix = longestOperatorPrefix(combined, ops)
            return prefix != prev.text
        }
        return false
    }

    private fun longestOperatorPrefix(s: String, ops: Set<String>): String {
        for (len in 3 downTo 1) {
            if (len <= s.length) {
                val sub = s.substring(0, len)
                if (sub in ops) return sub
            }
        }
        return ""
    }

    private fun tokenize(language: MinifyLanguage, source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val s = source
        val n = s.length
        var i = 0
        var atLineStart = true

        while (i < n) {
            val c = s[i]
            if (c.isWhitespace()) {
                if (c == '\n') atLineStart = true
                i++
                continue
            }
            // C preprocessor directive: the whole line is opaque (e.g. the
            // `#include <math.h>` lines the backend inserts for intrinsics).
            if (language == MinifyLanguage.C && atLineStart && c == '#') {
                var end = i
                while (end < n && s[end] != '\n') end++
                tokens.add(Token(Kind.PREPROC, s.substring(i, end).trimEnd()))
                i = end
                continue
            }
            atLineStart = false

            // Comments.
            if (c == '/' && i + 1 < n) {
                val d = s[i + 1]
                if (d == '/') {
                    var end = i + 2
                    while (end < n && s[end] != '\n') end++
                    tokens.add(Token(Kind.COMMENT, s.substring(i, end)))
                    i = end
                    continue
                }
                if (d == '*') {
                    var end = i + 2
                    while (end + 1 < n && !(s[end] == '*' && s[end + 1] == '/')) end++
                    end = minOf(end + 2, n)
                    tokens.add(Token(Kind.COMMENT, s.substring(i, end)))
                    i = end
                    continue
                }
            }

            // Strings and char literals (both quote styles).
            if (c == '"' || c == '\'') {
                val quote = c
                var end = i + 1
                while (end < n) {
                    if (s[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (s[end] == quote) {
                        end++
                        break
                    }
                    end++
                }
                tokens.add(Token(Kind.STRING, s.substring(i, minOf(end, n))))
                i = minOf(end, n)
                continue
            }

            // JS template literals -- kept opaque. Codegen does not emit these
            // today (interpolation lowers to concatenation), but an escaped
            // backtick must not derail the scan if one ever appears.
            if (language == MinifyLanguage.JS && c == '`') {
                var end = i + 1
                while (end < n) {
                    if (s[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (s[end] == '`') {
                        end++
                        break
                    }
                    end++
                }
                tokens.add(Token(Kind.TEMPLATE, s.substring(i, minOf(end, n))))
                i = minOf(end, n)
                continue
            }

            // Numbers (C and JS shapes overlap; suffixes ride along so `1U`
            // stays one token and does not get split as `1 U`).
            if (c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit())) {
                val start = i
                if (c == '0' && i + 1 < n && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
                    i += 2
                    while (i < n && (s[i].isDigit() || s[i] in 'a'..'f' || s[i] in 'A'..'F' || s[i] == '_')) i++
                    if (i < n && s[i] == '.') {
                        i++
                        while (i < n && (s[i].isDigit() || s[i] in 'a'..'f' || s[i] in 'A'..'F' || s[i] == '_')) i++
                    }
                    if (i < n && (s[i] == 'p' || s[i] == 'P')) {
                        i++
                        if (i < n && (s[i] == '+' || s[i] == '-')) i++
                        while (i < n && (s[i].isDigit() || s[i] == '_')) i++
                    }
                } else if (c == '0' && i + 1 < n && (s[i + 1] == 'b' || s[i + 1] == 'B' || s[i + 1] == 'o' || s[i + 1] == 'O')) {
                    i += 2
                    while (i < n && (s[i].isDigit() || s[i] in 'a'..'f' || s[i] in 'A'..'F' || s[i] == '_')) i++
                } else {
                    while (i < n && (s[i].isDigit() || s[i] == '_')) i++
                    if (i < n && s[i] == '.') {
                        i++
                        while (i < n && (s[i].isDigit() || s[i] == '_')) i++
                    }
                    if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                        i++
                        if (i < n && (s[i] == '+' || s[i] == '-')) i++
                        while (i < n && (s[i].isDigit() || s[i] == '_')) i++
                    }
                    while (i < n && (s[i] in "uUlLfFn")) i++
                }
                tokens.add(Token(Kind.NUMBER, s.substring(start, i)))
                continue
            }

            // Identifiers (and keywords -- they are identifier-shaped tokens
            // that simply never appear in the rename map).
            if (isIdentStart(c)) {
                val start = i
                while (i < n && isIdentPart(s[i])) i++
                tokens.add(Token(Kind.IDENT, s.substring(start, i)))
                continue
            }

            // Multi-char operators, longest match first.
            val multi = if (language == MinifyLanguage.C) C_MULTI else JS_MULTI
            var matched: String? = null
            for (len in 4 downTo 1) {
                if (i + len <= n) {
                    val cand = s.substring(i, i + len)
                    if (cand in multi) {
                        matched = cand
                        break
                    }
                }
            }
            if (matched != null) {
                tokens.add(Token(Kind.PUNCT, matched))
                i += matched.length
                continue
            }

            tokens.add(Token(Kind.PUNCT, c.toString()))
            i++
        }
        return tokens
    }
}
