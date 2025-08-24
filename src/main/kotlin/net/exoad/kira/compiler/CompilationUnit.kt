package net.exoad.kira.compiler

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

    fun allSources(): Collection<SourceContext>
    {
        return sources.values
    }

    fun resolveSymbol(name: String): SemanticSymbol?
    {
        return symbolTable.resolve(name)
    }
}
