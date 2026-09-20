package net.exoad.kira.compiler.analysis.semantic

data class KiraScopeFrame(
    val kind: SemanticScope,
    val symbols: MutableMap<String, SemanticSymbol> = mutableMapOf(),
)