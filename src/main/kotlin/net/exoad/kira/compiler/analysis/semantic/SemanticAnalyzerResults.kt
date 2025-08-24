package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException

data class SemanticAnalyzerResults(
    val diagnostics: List<DiagnosticsException>,
    val symbolTable: SymbolTable,
    val isHealthy: Boolean,
)