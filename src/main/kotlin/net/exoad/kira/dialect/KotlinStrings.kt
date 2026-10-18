package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import java.io.File

/**
 * The Kira inside Kotlin string literals (the compiler's tests), converted in place. Each literal
 * is decoded (escapes resolved, each `${...}` a placeholder name), converted and proved as Kira
 * when the old parser reads it (as a module, or as a module's body), and each edit is written
 * back into the literal in its own spelling. A literal that is no Kira is left as it is.
 */
object KotlinStrings {
    /** One string literal: its body's span in the file, and whether it is a raw `"""` string. */
    private class Literal(val bodyStart: Int, val bodyEnd: Int, val raw: Boolean)

    /** A literal's text as Kira sees it, each char's offset in the file, and the holes. */
    private class Decoded(val text: String, val offsets: IntArray, val end: Int, val holes: List<String>)

    private const val HOLE = "zzKtHole"
    private const val SNIPPET = "module \"kt:snippet\"\n"

    /** [stage2]: stage 1's Kira to stage 2's ([Stage2]) instead of the old dialect to stage 1's. */
    fun convertAll(paths: List<String>, check: Boolean, stage2: Boolean = false): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.distinct().sorted()
        var changedFiles = 0
        var converted = 0
        var refused = 0
        for (f in files) {
            val text = f.readText()
            val (out, n, bad) = convertFile(text, f.path, stage2)
            converted += n
            refused += bad
            if (out != text) {
                changedFiles++
                if (!check) f.writeText(out)
            }
        }
        println("kira-dialect kt${if (stage2) " --stage2" else ""}: ${files.size} files, $converted literals converted in $changedFiles files, $refused left for a hand fix, $roleChanges with a role to check")
        return if (check && changedFiles > 0) 1 else 0
    }

    /** The file with each Kira literal converted; how many were; how many look like Kira and were not. */
    fun convertFile(text: String, path: String, stage2: Boolean = false): Triple<String, Int, Int> {
        val literals = literals(text)
        val sb = StringBuilder()
        var at = 0
        var converted = 0
        var bad = 0
        for (lit in literals) {
            val decoded = decode(text, lit)
            val source = text.substring(lit.bodyStart, lit.bodyEnd)
            when (val result = if (stage2) stage2Kira(decoded.text, path, source) else convertKira(decoded.text, path, source)) {
                null -> {}
                is Outcome.Refused -> {
                    bad++
                    println("KT $path:${lineOf(text, lit.bodyStart)}: ${result.why}")
                }
                is Outcome.Converted -> {
                    sb.append(text, at, lit.bodyStart)
                    sb.append(applyEdits(text, lit, decoded, result.edits))
                    at = lit.bodyEnd
                    converted++
                }
            }
        }
        sb.append(text, at, text.length)
        return Triple(sb.toString(), converted, bad)
    }

    private sealed class Outcome {
        class Converted(val edits: List<DialectConverter.Edit>) : Outcome()
        class Refused(val why: String) : Outcome()
    }

    /**
     * Stage 1's Kira in a literal to stage 2's: read as a module (its `[...]` literals too) and
     * typed alone beside the stdlib, rewritten and proved as [Stage2] does a file. A literal that
     * does not parse, holds a bare value at the top level, or has nothing to rewrite is left.
     */
    private fun stage2Kira(kira: String, path: String, source: String): Outcome? {
        if (!kira.contains('[') && !kira.contains(".copy(")) return null
        val file = "$path#literal"
        val unit = CompilationUnit(bootstrap = false)
        stdlibTexts.forEach { (f, uri, text) -> ProjectUnits.read(unit, f, text, uri, literals = true) }
        val (old, _) = ProjectUnits.read(unit, file, kira, "kt:snippet", literals = true)
        if (old == null) return null
        if (notKira(old, kira) != null) return null
        val first = source.lines().first { it.isNotBlank() }.trim().take(70)
        val program = KiraTyper.run(unit, TyperMode.STRICT)
        val run = Stage2.Rewrite(kira, old, program)
        run.collect()
        if (run.problems.isNotEmpty()) return Outcome.Refused(run.problems.joinToString("; ") + ": $first")
        if (run.edits.isEmpty()) return null
        val out = run.apply()
        val again = CompilationUnit(bootstrap = false)
        stdlibTexts.forEach { (f, uri, text) -> ProjectUnits.read(again, f, text, uri, literals = true) }
        val (nc, err) = ProjectUnits.read(again, file, out, "kt:snippet", literals = false)
        if (nc == null) return Outcome.Refused("the new parser refuses the conversion ($err): $first")
        // Typed (and so linked) as the old one was, before the trees are compared.
        val program2 = KiraTyper.run(again, TyperMode.STRICT)
        Stage2.Proof(old, nc, run.rewritten).compare()?.let { return Outcome.Refused("tree: $it: $first") }
        Stage2.typed(old, program, nc, program2)?.let { return Outcome.Refused("type: $it: $first") }
        return Outcome.Converted(run.edits.sortedBy { it.start })
    }

    /** The stdlib under `kira/` (path, module, text), each literal's unit reads it afresh. */
    private val stdlibTexts: List<Triple<String, String, String>> by lazy {
        val root = java.nio.file.Path.of("kira").toAbsolutePath().normalize()
        if (!java.nio.file.Files.isRegularFile(root.resolve("core.kira"))) return@lazy emptyList()
        root.toFile().walkTopDown().filter { it.isFile && it.extension == "kira" }.sortedBy { it.path }.map { f ->
            Triple(ProjectUnits.canonical(f.path), net.exoad.kira.compiler.ModuleNames.dependency(root, f.toPath()), f.readText())
        }.toList()
    }

    /** The old dialect's markers: a literal holding none of them needs nothing. */
    private val oldWords = Regex("""(^|[^A-Za-z0-9_@])(module\s+"|use\s+"|class\s+[A-Z]|struct\s+[A-Z]|trait\s+[A-Z]|enum\s+[A-Z]|alias\s+[A-Z]|fx\s+[A-Za-z@]|fx\s*\(|require\s+[a-z]|variant\s|do\s*\{|finally\s*\{)""")

    private fun convertKira(kira: String, path: String, source: String): Outcome? {
        if (!oldWords.containsMatchIn(kira)) return null
        val whole = DialectConverter.parseOld(kira, path).first != null
        val text = if (whole) kira else SNIPPET + kira
        val (old, err) = DialectConverter.parseOld(text, path)
        if (old == null) {
            if (!looksLikeKira(kira)) return null
            // Kira a test means to be wrong: the forms are rewritten by their words, with no tree to prove.
            if (forceWrong && !cppMarkers.containsMatchIn(kira)) {
                val edits = forcedEdits(kira)
                if (edits.isNotEmpty()) {
                    println("FORCED $path: ${source.lines().first { it.isNotBlank() }.trim().take(70)}")
                    return Outcome.Converted(edits)
                }
            }
            return Outcome.Refused("old Kira the old parser refuses ($err): ${source.lines().first { it.isNotBlank() }.trim().take(70)}")
        }
        notKira(old, kira)?.let { why -> return Outcome.Refused("left as it is ($why): ${source.lines().first { it.isNotBlank() }.trim().take(70)}") }
        val r = DialectConverter.convert(text, path, zerolessRequired = true)
        if (r.problems.isNotEmpty()) return Outcome.Refused(r.problems.joinToString("; "))
        if (r.edits.none { whole || it.start >= SNIPPET.length }) return null
        val (nc, nerr) = TreeProof.parseNew(r.text, path, if (whole) moduleOf(old) else "kt:snippet")
        if (nc == null) return Outcome.Refused("the new parser refuses the conversion: $nerr")
        TreeProof.compare(r.old ?: old, nc, DialectConverter.Renames(), r.madeRequired)?.let { return Outcome.Refused("tree: $it") }
        roleChange(r.old ?: old, nc, path)?.let {
            // Read alone beside the stdlib: a type another literal constructs or lists can read otherwise.
            roleChanges++
            println("ROLE $path: $it: ${source.lines().first { l -> l.isNotBlank() }.trim().take(70)}")
        }
        if (r.madeRequired.isNotEmpty()) {
            println("REQUIRED $path: ${r.madeRequired.joinToString { it.name.value }} given at construction now")
        }
        if (whole) return Outcome.Converted(r.edits)
        // The snippet's own header goes away again: keep only edits after it, shifted back.
        val shift = SNIPPET.length
        val edits = r.edits.filter { it.start >= shift }.map { DialectConverter.Edit(it.start - shift, it.end - shift, it.text) }
        return Outcome.Converted(edits)
    }

    /** How many converted literals give a type a role its old keyword did not. */
    private var roleChanges = 0

    /** The stdlib under `kira/`, read once in the clean dialect, for each literal's role check. */
    private val stdlib: List<Triple<String, String, net.exoad.kira.source.SourceContext>> by lazy {
        val root = java.nio.file.Path.of("kira").toAbsolutePath().normalize()
        if (!java.nio.file.Files.isRegularFile(root.resolve("core.kira"))) return@lazy emptyList()
        root.toFile().walkTopDown().filter { it.isFile && it.extension == "kira" }.mapNotNull { f ->
            val uri = net.exoad.kira.compiler.ModuleNames.dependency(root, f.toPath())
            DialectMain.readNew(f.path, uri)?.let { Triple(f.path, uri, it) }
        }.toList()
    }

    /** Each type's role in [nc] (a literal, read beside the stdlib) against [old]'s keywords: what differs, or null. */
    private fun roleChange(old: net.exoad.kira.source.SourceContext, nc: net.exoad.kira.source.SourceContext, path: String): String? {
        val cu = net.exoad.kira.compiler.CompilationUnit()
        val held = cu.allSources().mapNotNull { it.moduleName }.toSet()
        stdlib.forEach { (f, uri, ctx) -> if (uri !in held) DialectMain.adopt(cu, f, ctx, uri) }
        val file = "$path#literal"
        DialectMain.adopt(cu, file, nc, nc.moduleName ?: "kt:snippet")
        net.exoad.kira.compiler.DialectLink.link(cu)
        val newKinds = DialectMain.kinds(cu.getSource(file)!!)
        val oldKinds = DialectMain.kinds(old)
        if (newKinds == oldKinds) return null
        return oldKinds.filter { newKinds[it.key] != it.value }.map { "${it.key} was ${it.value}, is ${newKinds[it.key]}" }.joinToString()
    }

    /** Set by `kt --force-wrong`: old Kira the old parser refuses (a test's wrong program) is rewritten by its words. */
    var forceWrong = false

    /** C and C++ text, which also holds the words `class` and `struct`. */
    private val cppMarkers = Regex("""::|#include|\bstd\b|public:|virtual |typedef |template<""")

    /**
     * The old forms in text the old parser refuses, rewritten word by word: `fx`, `require` and
     * the module line go, `use "a:b"` is `a.b as b`, and `class N`, `trait N`, `struct N` are
     * `N: Any`, `N: Struct` (a parent list kept).
     */
    private fun forcedEdits(kira: String): List<DialectConverter.Edit> {
        val out = mutableListOf<DialectConverter.Edit>()
        Regex("""\bmodule\s+"[^"\n]*"[ \t]*;?[ \t]*\n?""").findAll(kira).forEach { out += DialectConverter.Edit(it.range.first, it.range.last + 1, "") }
        Regex("""\buse\s+"([^"\n]*)"""").findAll(kira).forEach {
            val dotted = it.groupValues[1].replace(':', '.')
            out += DialectConverter.Edit(it.range.first, it.range.last + 1, "$dotted as ${DialectConverter.aliasOf(dotted)}")
        }
        Regex("""\bfx\s+(?=[A-Za-z@])|\bfx\s*(?=\()|\brequire\s+""").findAll(kira).forEach { out += DialectConverter.Edit(it.range.first, it.range.last + 1, "") }
        Regex("""\b(class|trait|struct)\s+([A-Z]\w*(?:<[^>{]*>)?)(\s*:\s*)?""").findAll(kira).forEach { m ->
            val name = m.groupValues[2]
            val root = if (m.groupValues[1] == "struct") "Struct" else "Any"
            val rest = if (m.groupValues[3].isNotEmpty()) (if (root == "Struct") ": Struct, " else ": ") else ": $root"
            out += DialectConverter.Edit(m.range.first, m.range.last + 1, name + rest)
        }
        val sorted = out.sortedBy { it.start }
        val kept = mutableListOf<DialectConverter.Edit>()
        sorted.forEach { e -> if (kept.isEmpty() || e.start >= kept.last().end) kept += e }
        return kept
    }

    /**
     * Why a literal the old parser reads is still no Kira, or null: C and C++ expectations parse
     * too (`class B : public A` is a class, `struct S;` a body-less struct). A bare value at the
     * top level, or a body-less type with no intrinsic in a literal with no brace, is refused.
     */
    private fun notKira(old: net.exoad.kira.source.SourceContext, kira: String): String? {
        for (st in old.ast.statements) {
            val e = (st as? net.exoad.kira.compiler.frontend.parser.ast.statements.Statement)?.expr ?: continue
            if (st is net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement) continue
            when (e) {
                is net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl,
                is net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr -> {}
                // A call is a statement a snippet may hold; a template hole stands for Kotlin's text.
                is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr -> {}
                is net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier -> if (!e.value.startsWith(HOLE)) return "a value at the top level"
                else -> return "a value at the top level"
            }
            val bodyless = (e is net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl && e.members.isEmpty()) ||
                (e is net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl && e.members.isEmpty())
            if (bodyless && old.astIntrinsicMarked[e].isNullOrEmpty() && !kira.contains('{')) return "a body-less type in no braces"
        }
        return null
    }

    private fun moduleOf(ctx: net.exoad.kira.source.SourceContext): String =
        ctx.ast.statements.firstNotNullOfOrNull {
            ((it as? net.exoad.kira.compiler.frontend.parser.ast.statements.Statement)?.expr as? net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl)?.uri?.value
        } ?: "kt:snippet"

    private fun looksLikeKira(s: String): Boolean =
        Regex("""\bfx\s+[a-z@][A-Za-z0-9_]*\s*[:<]|\bclass\s+[A-Z]\w*\s*[{:<]|\bstruct\s+[A-Z]\w*\s*[{:]|\bmodule\s+"|\bfx\s*\(""").containsMatchIn(s)

    private fun applyEdits(text: String, lit: Literal, d: Decoded, edits: List<DialectConverter.Edit>): String {
        val sb = StringBuilder()
        var at = lit.bodyStart
        for (e in edits) {
            val s = sourceOf(d, e.start)
            val t = sourceOf(d, e.end)
            if (s < at) continue
            sb.append(text, at, s)
            sb.append(encode(e.text, lit.raw, d.holes))
            at = t
        }
        sb.append(text, at, lit.bodyEnd)
        return sb.toString()
    }

    private fun sourceOf(d: Decoded, i: Int): Int = if (i >= d.offsets.size) d.end else d.offsets[i]

    /** [s] as the literal spells it, each placeholder its template again. */
    private fun encode(s: String, raw: Boolean, holes: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s.startsWith(HOLE, i)) {
                var j = i + HOLE.length
                while (j < s.length && s[j].isDigit()) j++
                sb.append(holes[s.substring(i + HOLE.length, j).toInt()])
                i = j
                continue
            }
            val c = s[i]
            val next = s.getOrNull(i + 1)
            if (raw) {
                if (c == '$' && next != null && (next == '{' || next.isLetter() || next == '_')) sb.append("\${'$'}") else sb.append(c)
            } else {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '$' -> sb.append("\\$")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    // ---- reading Kotlin ------------------------------------------------------------------------

    /** Every string literal of a Kotlin file, outermost only (a literal inside a template is the template's). */
    private fun literals(t: String): List<Literal> {
        val out = mutableListOf<Literal>()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                t.startsWith("//", i) -> i = t.indexOf('\n', i).let { if (it < 0) t.length else it }
                t.startsWith("/*", i) -> i = skipBlockComment(t, i)
                t.startsWith("\"\"\"", i) -> {
                    val end = rawEnd(t, i + 3)
                    out += Literal(i + 3, end, raw = true)
                    i = end + 3
                }
                c == '"' -> {
                    val end = stringEnd(t, i + 1)
                    out += Literal(i + 1, end, raw = false)
                    i = end + 1
                }
                c == '\'' -> i = charEnd(t, i)
                else -> i++
            }
        }
        return out
    }

    private fun skipBlockComment(t: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < t.length) {
            if (t.startsWith("/*", i)) { depth++; i += 2; continue }
            if (t.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) return i; continue }
            i++
        }
        return t.length
    }

    private fun charEnd(t: String, start: Int): Int {
        var i = start + 1
        if (i < t.length && t[i] == '\\') i += 2 else i++
        while (i < t.length && t[i] != '\'') i++
        return i + 1
    }

    /** The offset of the closing `"""` of a raw string whose body starts at [from]. */
    private fun rawEnd(t: String, from: Int): Int {
        var i = from
        while (i < t.length) {
            if (t[i] == '$' && t.getOrNull(i + 1) == '{') { i = templateEnd(t, i + 2); continue }
            if (t.startsWith("\"\"\"", i)) {
                var j = i
                while (t.startsWith("\"\"\"\"", j)) j++
                return j
            }
            i++
        }
        return t.length
    }

    /** The offset of the closing `"` of a string whose body starts at [from]. */
    private fun stringEnd(t: String, from: Int): Int {
        var i = from
        while (i < t.length) {
            when {
                t[i] == '\\' -> i += 2
                t[i] == '$' && t.getOrNull(i + 1) == '{' -> i = templateEnd(t, i + 2)
                t[i] == '"' -> return i
                t[i] == '\n' -> return i
                else -> i++
            }
        }
        return t.length
    }

    /** The offset just past the `}` of a `${` template whose expression starts at [from]. */
    private fun templateEnd(t: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < t.length) {
            when {
                t.startsWith("\"\"\"", i) -> { i = rawEnd(t, i + 3) + 3; continue }
                t[i] == '"' -> { i = stringEnd(t, i + 1) + 1; continue }
                t[i] == '\'' -> { i = charEnd(t, i); continue }
                t[i] == '{' -> depth++
                t[i] == '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return t.length
    }

    private fun decode(t: String, lit: Literal): Decoded {
        val sb = StringBuilder()
        val offsets = mutableListOf<Int>()
        val holes = mutableListOf<String>()
        fun add(s: String, at: Int) {
            s.forEach { sb.append(it); offsets.add(at) }
        }
        var i = lit.bodyStart
        while (i < lit.bodyEnd) {
            val c = t[i]
            if (c == '$' && t.getOrNull(i + 1) == '{') {
                val end = templateEnd(t, i + 2)
                val tmpl = t.substring(i, end)
                if (tmpl == "\${'$'}") add("$", i) else {
                    add(HOLE + holes.size, i)
                    holes += tmpl
                    // A hole glued to a word (`${mods}fx f`) is kept apart from it, so the word still reads.
                    if (end < lit.bodyEnd && (t[end].isLetterOrDigit() || t[end] == '_')) add(" ", end)
                }
                i = end
                continue
            }
            if (c == '$' && (t.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true)) {
                var j = i + 1
                while (j < lit.bodyEnd && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                add(HOLE + holes.size, i)
                holes += t.substring(i, j)
                i = j
                continue
            }
            if (!lit.raw && c == '\\') {
                val e = t.getOrNull(i + 1)
                val decoded = when (e) {
                    'n' -> "\n"
                    't' -> "\t"
                    'r' -> "\r"
                    'b' -> "\b"
                    '"' -> "\""
                    '\'' -> "'"
                    '\\' -> "\\"
                    '$' -> "$"
                    'u' -> t.substring(i + 2, minOf(i + 6, t.length)).toIntOrNull(16)?.toChar()?.toString() ?: "?"
                    else -> e?.toString() ?: ""
                }
                add(decoded, i)
                i += if (e == 'u') 6 else 2
                continue
            }
            add(c.toString(), i)
            i++
        }
        return Decoded(sb.toString(), offsets.toIntArray(), lit.bodyEnd, holes)
    }

    private fun lineOf(t: String, offset: Int): Int = t.substring(0, offset).count { it == '\n' } + 1
}
