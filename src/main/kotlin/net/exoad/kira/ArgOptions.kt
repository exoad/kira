package net.exoad.kira

data class ArgsOptions(
    val useDiagnostics: Boolean,
    val src: List<String>,
    val dumpLexerTokens: String?,
    val dumpAST: String?,
)
{
    override fun toString(): String
    {
        return "ArgsOptions{ UseDiagnostics: $useDiagnostics,  Src: $src, DumpLexerTokens: $dumpLexerTokens, DumpAST: $dumpAST }"
    }
}
