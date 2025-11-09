package net.exoad.kira.compiler

import net.exoad.kira.compiler.analysis.semantic.KiraSymbolTable
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbol
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File

class CompilationUnit {
    private val sources = mutableMapOf<String, SourceContext>()
    val symbolTable = KiraSymbolTable()

    init {
        try {
            val typesFile = File("kira/builtin/types.kira")
            if (typesFile.exists()) {
                val pre = KiraPreprocessor(typesFile.readText())
                val processed = pre.process()
                val ctx = addSource(typesFile.canonicalPath, processed.processedContent, emptyList())
                val lexer = KiraLexer(ctx)
                val tokens = lexer.tokenize()
                // replace the source with tokens
                addSource(typesFile.canonicalPath, ctx.content, tokens)
                // parse the types file - semantic analyzer will handle registration based on @global
                val parser = KiraParser(getSource(typesFile.canonicalPath)!!)
                parser.parse()
            }
        } catch (_: Exception) {
            // silently ignore any bootstrap errors here
        }
    }

    fun addSource(file: String, content: String, tokens: List<Token>): SourceContext {
        val ctx = SourceContext(content, file, tokens)
        sources[file] = ctx
        return ctx
    }

    fun getSource(file: String): SourceContext? {
        return sources[file]
    }

    fun getSourcesLength(): Int {
        return sources.size
    }

    fun allSources(): Collection<SourceContext> {
        return sources.values
    }

    fun resolveSymbol(name: String): SemanticSymbol? {
        return symbolTable.resolve(name)
    }
}
