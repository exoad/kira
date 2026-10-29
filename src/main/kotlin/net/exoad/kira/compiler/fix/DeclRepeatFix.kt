package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The fix for rules.decl.repeat: `x: T = T { .. }` becomes `x: T { .. }`, and nothing else in the file changes. */
object DeclRepeatFix {
    /** [source] with every repeat the hint names rewritten, and how many there were. */
    fun rewrite(source: String, file: String = "fix.kira"): Pair<String, Int> {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val ctx = SourceContext(processed, file, tokens)
        KiraSourceParsers.from(ctx).parse()
        if (ctx.astDeclRepeats.isEmpty()) return source to 0
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        for (d in ctx.astDeclRepeats.sortedWith(compareByDescending<net.exoad.kira.source.DeclRepeat> { it.line }.thenByDescending { it.startColumn })) {
            val raw = lines[d.line - 1]
            // The region lies before any comment, so the raw line shares the processed line's columns up to it.
            lines[d.line - 1] = raw.substring(0, d.startColumn - 1) + " " + raw.substring(d.endColumn - 1)
        }
        return lines.joinToString("") to ctx.astDeclRepeats.size
    }

    /** Rewrites [file] in place (written beside, synced, renamed over); the number of repeats fixed. */
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
