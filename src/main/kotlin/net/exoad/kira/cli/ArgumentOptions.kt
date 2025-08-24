package net.exoad.kira.cli

data class ArgumentOptions(
    val useDiagnostics: Boolean,
    val src: Array<String>,
    val dumpLexerTokens: String?,
    val dumpAST: String?,
)
{
    override fun toString(): String
    {
        return "ArgumentOptions{ UseDiagnostics: $useDiagnostics,  Src: $src, DumpLexerTokens: $dumpLexerTokens, DumpAST: $dumpAST }"
    }

    override fun equals(other: Any?): Boolean
    {
        if(this === other) return true
        if(javaClass != other?.javaClass) return false
        other as ArgumentOptions
        if(useDiagnostics != other.useDiagnostics) return false
        if(!src.contentEquals(other.src)) return false
        if(dumpLexerTokens != other.dumpLexerTokens) return false
        if(dumpAST != other.dumpAST) return false
        return true
    }

    override fun hashCode(): Int
    {
        var result = useDiagnostics.hashCode()
        result = 31 * result + src.contentHashCode()
        result = 31 * result + (dumpLexerTokens?.hashCode() ?: 0)
        result = 31 * result + (dumpAST?.hashCode() ?: 0)
        return result
    }
}
