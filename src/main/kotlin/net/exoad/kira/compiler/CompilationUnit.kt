package net.exoad.kira.compiler

import net.exoad.kira.compiler.analysis.semantic.SemanticSymbol
import net.exoad.kira.compiler.analysis.semantic.SymbolTable
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.source.SourceContext

class CompilationUnit
{
    private val sources = mutableMapOf<String, SourceContext>()
    val symbolTable = SymbolTable()

    fun addSource(file: String, content: String, tokens: List<Token>): SourceContext
    {
        val ctx = SourceContext(content, file, tokens)
        sources[file] = ctx
        return ctx
    }

    fun getSource(file: String): SourceContext?
    {
        return sources[file]
    }

    fun getSourcesLength(): Int
    {
        return sources.size
    }

    fun allSources(): Collection<SourceContext>
    {
        return sources.values
    }

    fun resolveSymbol(name: String): SemanticSymbol?
    {
        return symbolTable.resolve(name)
    }
}
