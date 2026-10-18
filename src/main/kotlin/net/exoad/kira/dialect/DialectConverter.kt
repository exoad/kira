package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.core.Keywords
import net.exoad.kira.dialect.legacy.LegacyKiraParser
import net.exoad.kira.source.SourceContext

/**
 * Old-dialect Kira text to the clean dialect's, by token-level edits on the original text, so
 * every comment and line stays where it was. The old text is read with the frozen legacy parser.
 */
object DialectConverter {
    /** Old module URI to the new dotted module name; an unknown URI keeps its spelling, ':' as '.'. */
    class Renames(private val map: Map<String, String> = emptyMap()) {
        fun of(uri: String): String = map[uri] ?: uri.replace(':', '.')
    }

    class Problem(val line: Int, val column: Int, val message: String) {
        override fun toString() = "$line:$column: $message"
    }

    /** The new text, what could not be converted, the old parse, and the edits (offsets into the old text). */
    class Result(
        val text: String,
        val problems: List<Problem>,
        val old: SourceContext?,
        val edits: List<Edit> = emptyList(),
        val madeRequired: List<VariableDecl> = emptyList(),
    )

    /** A legacy parse of [text]: its context, or the parse error's message. */
    fun parseOld(text: String, file: String): Pair<SourceContext?, String?> {
        return try {
            val cu = CompilationUnit()
            val processed = KiraPreprocessor(text).process().processedContent
            val bare = cu.addSource(file, processed, emptyList())
            val ctx = cu.addSource(file, processed, KiraLexer(bare, Keywords.legacy).tokenize())
            LegacyKiraParser(ctx).parse()
            ctx to null
        } catch (e: DiagnosticsException) {
            null to (e.message.lines().firstOrNull { it.isNotBlank() } ?: e.toString())
        } catch (e: Exception) {
            null to (e.message ?: e.toString())
        }
    }

    /**
     * [zerolessRequired]: a field the old dialect value-initialized whose type has no literal
     * zero (a type parameter, a class, Fx, Ref, View) is left with no value, so it becomes one
     * the construction gives, a semantic change each use must allow; otherwise it is a problem.
     */
    fun convert(text: String, file: String, renames: Renames = Renames(), zerolessRequired: Boolean = false): Result {
        val (ctx, error) = parseOld(text, file)
        if (ctx == null) {
            return Result(text, listOf(Problem(1, 1, "the old parser refuses it: $error")), null)
        }
        val run = Run(text, ctx, renames, zerolessRequired)
        run.collect()
        val out = run.apply()
        return Result(out, run.problems, ctx, run.sortedEdits(), run.madeRequired)
    }

    /** [start, end) of the old text replaced by [text]. */
    class Edit(val start: Int, val end: Int, val text: String)

    private class Run(val text: String, val ctx: SourceContext, val renames: Renames, val zerolessRequired: Boolean) {
        /** The fields left with no value under [zerolessRequired]: given at construction now. */
        val madeRequired = mutableListOf<VariableDecl>()
        val problems = mutableListOf<Problem>()
        private val edits = mutableListOf<Edit>()
        private val toks: List<Token> = ctx.tokens
        private val lineStarts: IntArray = lineStartsOf(text)
        private val indexAt = HashMap<Pair<Int, Int>, Int>().also { m ->
            toks.forEachIndexed { i, t -> m.putIfAbsent(t.canonicalLocation.lineNumber to t.canonicalLocation.column, i) }
        }

        fun collect() {
            val nodes = AstWalk.all(ctx.ast)
            val typeOwners = java.util.IdentityHashMap<ASTNode, ASTNode>()
            nodes.forEach { n ->
                val members = when (n) {
                    is ClassDecl -> n.members
                    is StructDecl -> n.members
                    else -> null
                }
                members?.forEach { typeOwners[it] = n }
            }
            val topLevel = java.util.IdentityHashMap<ASTNode, Boolean>()
            ctx.ast.statements.forEach { s -> ((s as? Statement)?.expr)?.let { topLevel[it] = true } }
            for (n in nodes) {
                when (n) {
                    is ModuleDecl -> moduleHeader(n)
                    is UseStatement -> use(n)
                    is TypeAliasDecl -> alias(n)
                    is StructDecl -> typeDecl(n, keywordBeforeName = true, root = "Struct", hasParents = n.traits.isNotEmpty())
                    is ClassDecl -> {
                        if (n.finally != null) problem(n, "a 'finally' block, which the clean dialect removes")
                        typeDecl(n, keywordBeforeName = true, root = "Any", hasParents = n.parents.isNotEmpty())
                    }
                    is TraitDecl -> typeDecl(n, keywordBeforeName = false, root = "Any", hasParents = n.parents.isNotEmpty())
                    is EnumDecl -> problem(n, "an enum, which the clean dialect removes")
                    is VariantDecl -> problem(n, "a variant, which the clean dialect removes")
                    is DoWhileIterationStatement -> problem(n, "a do-while loop, which the clean dialect removes")
                    is FunctionDecl -> function(n)
                    is LambdaExpr -> lambda(n)
                    is VariableDecl -> variable(n, typeOwners[n], topLevel.containsKey(n))
                    is TypeCastExpr -> {}
                    else -> {}
                }
            }
            castMut()
        }

        // ---- positions ---------------------------------------------------------------------

        fun offsetOf(i: Int): Int {
            val p = toks[i].canonicalLocation
            // An intrinsic's token sits just past its '@'.
            val at = if (toks[i].type == Token.Type.INTRINSIC_IDENTIFIER) 1 else 0
            return lineStarts[p.lineNumber - 1] + p.column - 1 - at
        }

        /** The end offset of token [i] in the original text. */
        fun endOf(i: Int): Int {
            val t = toks[i]
            val start = offsetOf(i)
            return when (t.type) {
                Token.Type.L_STRING -> {
                    var j = start + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    j + 1
                }
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_OPEN_ANGLE -> start + 1
                Token.Type.INTRINSIC_IDENTIFIER -> start + 1 + t.content.length
                else -> start + t.content.length
            }
        }

        fun indexOf(node: ASTNode): Int? {
            val p = ctx.astOrigins[node] ?: return null
            return indexAt[p.lineNumber to p.column]
        }

        /** The last token of the type that starts at [i]: a name and its `<...>`. */
        fun typeEnd(i: Int): Int {
            if (i + 1 >= toks.size || toks[i + 1].type != Token.Type.S_OPEN_ANGLE) return i
            var depth = 0
            var j = i + 1
            while (j < toks.size) {
                when (toks[j].type) {
                    Token.Type.S_OPEN_ANGLE -> depth++
                    Token.Type.S_CLOSE_ANGLE -> if (--depth == 0) return j
                    else -> {}
                }
                j++
            }
            return i
        }

        /** [start, end) widened over the blanks that follow it on the same line. */
        fun withSpaces(end: Int): Int {
            var e = end
            while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
            return e
        }

        fun problem(node: ASTNode, what: String) {
            val p = ctx.astOrigins[node]
            problems += Problem(p?.lineNumber ?: 1, p?.column ?: 1, "cannot convert $what")
        }

        fun edit(start: Int, end: Int, replacement: String) {
            edits += Edit(start, end, replacement)
        }

        fun sortedEdits(): List<Edit> = edits.sortedWith(compareBy({ it.start }, { it.end }))

        fun apply(): String {
            val sorted = edits.sortedWith(compareBy({ it.start }, { it.end }))
            val sb = StringBuilder()
            var at = 0
            for (e in sorted) {
                if (e.start < at) {
                    problems += Problem(1, 1, "overlapping edits at offset ${e.start}")
                    continue
                }
                sb.append(text, at, e.start).append(e.text)
                at = e.end
            }
            sb.append(text, at, text.length)
            return sb.toString()
        }

        // ---- the forms ---------------------------------------------------------------------

        fun moduleHeader(n: ModuleDecl) {
            val i = indexOf(n) ?: return
            var start = offsetOf(i)
            val lineStart = lineStartBefore(start)
            if (text.substring(lineStart, start).isBlank()) start = lineStart
            var end = withSpaces(endOf(i + 1))
            if (end < text.length && text[end] == '\r') end++
            if (end < text.length && text[end] == '\n') {
                end++
                // One blank line after the header goes with it.
                var k = end
                while (k < text.length && (text[k] == ' ' || text[k] == '\t' || text[k] == '\r')) k++
                if (k < text.length && text[k] == '\n') end = k + 1
            }
            edit(start, end, "")
        }

        fun use(n: UseStatement) {
            val i = indexOf(n) ?: return
            // The alias is the old URI's last segment: a qualified call names the module by it.
            val name = renames.of(n.uri.value)
            edit(offsetOf(i), endOf(i + 1), "$name as ${aliasOf(n.uri.value.replace(':', '.'))}")
        }

        fun alias(n: TypeAliasDecl) {
            val a = indexOf(n) ?: return
            val e1 = typeEnd(a + 1)
            if (toks[e1 + 1].type != Token.Type.K_AS) {
                problem(n, "an alias whose 'as' is not where it should be")
                return
            }
            val e2 = typeEnd(e1 + 2)
            val aliased = text.substring(offsetOf(a + 1), endOf(e1))
            val target = text.substring(offsetOf(e1 + 2), endOf(e2))
            edit(offsetOf(a), endOf(e2), "$target as $aliased")
        }

        /** `class N`, `struct N`, `trait N`: the keyword goes, and a root parent comes in. */
        fun typeDecl(n: ASTNode, keywordBeforeName: Boolean, root: String, hasParents: Boolean) {
            val at = indexOf(n) ?: return
            val (kw, name) = if (keywordBeforeName) (at - 1) to at else at to (at + 1)
            edit(offsetOf(kw), offsetOf(name), "")
            val ne = typeEnd(name)
            if (root == "Struct" && hasParents) {
                edit(offsetOf(ne + 2), offsetOf(ne + 2), "Struct, ")
            } else if (!hasParents) {
                edit(endOf(ne), endOf(ne), ": $root")
            }
        }

        fun function(n: FunctionDecl) {
            val f = indexOf(n) ?: return
            if (toks[f].type != Token.Type.K_FX) {
                problem(n, "a function whose 'fx' is not at its origin")
                return
            }
            if (n.isAnonymous()) {
                problem(n, "an anonymous trait function")
                return
            }
            edit(offsetOf(f), offsetOf(f + 1), "")
        }

        fun lambda(n: LambdaExpr) {
            val f = indexOf(n) ?: return
            if (toks[f].type != Token.Type.K_FX) return
            edit(offsetOf(f), offsetOf(f + 1), "")
        }

        fun variable(n: VariableDecl, owner: ASTNode?, isTopLevel: Boolean) {
            val i = indexOf(n) ?: return
            val required = Modifier.REQUIRE in n.modifiers
            if (required) {
                val r = modifierBefore(i, Token.Type.K_MODIFIER_REQUIRE)
                if (r == null) problem(n, "a 'require' that is not in its declaration's head")
                else edit(offsetOf(r), offsetOf(r + 1), "")
            }
            if (n.value != null || required) return
            // A field always takes its zero; a global unless the host supplies it; a local keeps its meaning.
            if (owner == null && (!isTopLevel || marked(n))) return
            if (toks[i + 1].type != Token.Type.S_COLON) return
            val te = typeEnd(i + 2)
            val zero = zeroOf(n.type)
            if (zero == null) {
                if (zerolessRequired && owner != null) {
                    madeRequired += n
                    return
                }
                problem(n, "a field or global without a value of type ${typeText(i + 2, te)}, which has no literal zero")
                return
            }
            edit(endOf(te), endOf(te), " = ${zero(typeText(i + 2, te))}")
        }

        /** `x as mut T` has no clean-dialect form: each one is a hand fix. */
        fun castMut() {
            for (i in 0 until toks.size - 1) {
                if (toks[i].type == Token.Type.K_AS && toks[i + 1].type == Token.Type.K_MODIFIER_MUTABLE) {
                    val p = toks[i].canonicalLocation
                    problems += Problem(p.lineNumber, p.column, "cannot convert 'as mut', which the clean dialect removes")
                }
            }
        }

        fun typeText(from: Int, to: Int): String = text.substring(offsetOf(from), endOf(to))

        /** Whether a declaration carries a marker that makes its valueless fields the host's. */
        fun marked(node: ASTNode): Boolean {
            val marks = ctx.astIntrinsicMarked[node]?.map { it.name } ?: return false
            return marks.any { it == "_extern" || it == "_magic" || it == "magic" || it == "_opaque" || it == "_global" }
        }

        /** The keyword modifier [type] in the head before the name at [name], skipping intrinsics. */
        fun modifierBefore(name: Int, type: Token.Type): Int? {
            var j = name - 1
            while (j >= 0) {
                val t = toks[j]
                when {
                    t.type == type -> return j
                    t.type in Token.Type.modifiers -> j--
                    t.type == Token.Type.INTRINSIC_IDENTIFIER -> j--
                    t.type == Token.Type.S_CLOSE_PARENTHESIS -> {
                        var depth = 0
                        while (j >= 0) {
                            if (toks[j].type == Token.Type.S_CLOSE_PARENTHESIS) depth++
                            if (toks[j].type == Token.Type.S_OPEN_PARENTHESIS && --depth == 0) break
                            j--
                        }
                        j--
                    }
                    else -> return null
                }
            }
            return null
        }

        fun lineStartBefore(offset: Int): Int {
            var k = offset
            while (k > 0 && text[k - 1] != '\n') k--
            return k
        }
    }

    /** The zero D38 value-initializes a defaultless field of [type] to, as a literal; null when it has none. */
    fun zeroOf(type: Type): ((String) -> String)? {
        val name = (type.identifier as? Identifier)?.value ?: return null
        return when (name) {
            "Int8", "Int16", "Int32", "Int64", "UInt8", "UInt16", "UInt32", "UInt64", "Size", "Int" -> { _ -> "0" }
            "Float32", "Float64", "Float" -> { _ -> "0.0" }
            "Bool" -> { _ -> "false" }
            "Str" -> { _ -> "\"\"" }
            "Char" -> { _ -> "'\\0'" }
            "Maybe" -> { _ -> "null" }
            "List", "Map", "Set", "StrBuf", "Stack", "Queue" -> { t -> "$t { }" }
            else -> null
        }
    }

    /** The name a converted `use` binds: the module's last segment, in camelCase. */
    fun aliasOf(dotted: String): String {
        val last = dotted.substringAfterLast('.')
        if (!last.contains('_')) return last
        val parts = last.split('_').filter { it.isNotEmpty() }
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /** Offsets of each line start, with the line breaks String.lines() knows (\r\n, \n, \r). */
    fun lineStartsOf(text: String): IntArray {
        val starts = mutableListOf(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') i++
                starts += i + 1
            } else if (c == '\n') {
                starts += i + 1
            }
            i++
        }
        return starts.toIntArray()
    }
}
