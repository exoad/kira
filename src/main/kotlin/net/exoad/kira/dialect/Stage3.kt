package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.lexer.Token
import java.io.File

/**
 * `kira-dialect stage3 [--check] PATH...`: stage 2's text to stage 3's, each `x is T` as `x == T`.
 * Proved by the tree: the new text, read by the clean parser, is the old text's tree as stage 2 read it.
 */
object Stage3 {
    fun convertAll(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        var changed = 0
        var proved = 0
        var bad = 0
        for (f in files) {
            val text = f.readText()
            val uri = DialectMain.newUriOf(f).first
            val (old, error) = ProjectUnits.read(CompilationUnit(bootstrap = false), f.path, text, uri, literals = true)
            if (old == null) {
                bad++
                println("NOT-READ $f: $error")
                continue
            }
            // `is` was a keyword up to stage 2, so every `is` token of its text is the operator.
            val lineStarts = DialectConverter.lineStartsOf(text)
            val at = old.tokens.filter { it.type == Token.Type.IDENTIFIER && it.content == "is" }.map {
                lineStarts[it.canonicalLocation.lineNumber - 1] + it.canonicalLocation.column - 1
            }
            if (at.isEmpty()) {
                proved++
                continue
            }
            val out = StringBuilder(text)
            at.sortedDescending().forEach { out.replace(it, it + 2, "==") }
            val next = out.toString()
            val (nc, newError) = ProjectUnits.read(CompilationUnit(bootstrap = false), f.path, next, uri, literals = false)
            val diff = if (nc == null) "the new text does not parse: $newError" else Stage2.Proof(old, nc, emptyMap()).compare()
            if (diff != null) {
                bad++
                println("NOT-PROVED $f: $diff")
                continue
            }
            proved++
            changed++
            println("${if (check) "WOULD-CHANGE" else "CHANGED"} $f: ${at.size} `is`")
            if (!check) f.writeText(next)
        }
        println("kira-dialect stage3: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $bad not converted")
        return if (bad > 0 || (check && changed > 0)) 1 else 0
    }
}
