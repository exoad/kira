package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.DeclEdit
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The migration for braces (D-115): `x: T = v` becomes `x: T { v }`, `NAME: T = T { .. }` becomes `NAME: T { .. }`, and
 * a variable of function type gets its type spelled `Fx<..>`. Parameter defaults, named arguments and assignments stay.
 */
object DeclEqualsFix {
    /** [source] with every `= value` declaration rewritten, and how many there were. */
    fun rewrite(source: String, file: String = "fix.kira"): Pair<String, Int> {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val ctx = SourceContext(processed, file, tokens)
        ctx.lenientDeclEquals = true
        // A file that still has a `for` is read all the same: its declarations are fixed and the loop is left for --fix-for.
        ctx.lenientFor = true
        KiraParser.parse(ctx)
        if (ctx.astDeclEquals.isEmpty()) return source to 0
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        val edits = ctx.astDeclEquals.flatMap { it.edits }.sortedWith(compareByDescending<DeclEdit> { it.line }.thenByDescending { it.startColumn })
        for (e in edits) lines[e.line - 1] = e.applyTo(lines[e.line - 1])
        return lines.joinToString("") to ctx.astDeclEquals.size
    }

    /** Rewrites [file] in place (written beside, synced, renamed over); the number of declarations fixed. */
    fun rewriteFile(file: File): Int {
        val (text, n) = rewrite(file.readText(), file.path)
        if (n == 0) return 0
        val beside = File(file.path + ".fix")
        Files.newByteChannel(beside.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            (ch as java.nio.channels.FileChannel).write(java.nio.ByteBuffer.wrap(text.toByteArray())); ch.force(true)
        }
        Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return n
    }
}
