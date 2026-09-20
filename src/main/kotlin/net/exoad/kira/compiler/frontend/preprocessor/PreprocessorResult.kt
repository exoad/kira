package net.exoad.kira.compiler.frontend.preprocessor

data class PreprocessorResult(val processedContent: String, val lineComments: List<Int>)
