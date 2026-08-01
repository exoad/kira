package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.ParserBackend
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File

object TestCompileSupport {
    data class FrontendCompilationResult(
        val compilationUnit: CompilationUnit,
        val sourceContext: SourceContext,
        val semanticResults: SemanticAnalyzerResults?
    )

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    data class NativeExecutionResult(
        val compileResult: ProcessResult,
        val runResult: ProcessResult?
    )

    fun logicalPathForModule(moduleUri: String): String {
        val parts = moduleUri.split(":", limit = 2)
        require(parts.size == 2) { "moduleUri must be in <package>:<dot.path> format" }
        return "${parts[0]}/${parts[1].replace('.', '/')}.kira"
    }

    fun wrapModule(moduleUri: String, body: String): String {
        return buildString {
            append("module \"")
            append(moduleUri)
            appendLine("\"")
            appendLine()
            appendLine(body.trimIndent())
        }
    }

    fun compileSnippet(
        source: String,
        logicalPath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): FrontendCompilationResult {
        val pre = KiraPreprocessor(source)
        val preprocessed = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource(logicalPath, preprocessed.processedContent, emptyList())
        val tokens = KiraLexer(src).tokenize()
        val srcWithTokens = cu.addSource(logicalPath, src.content, tokens)

        withParserBackend(parserBackend) {
            KiraSourceParsers.from(srcWithTokens).parse()
        }

        val semantics = if (runSemantic) {
            KiraSemanticAnalyzer(cu).validateAST()
        } else {
            null
        }

        return FrontendCompilationResult(cu, srcWithTokens, semantics)
    }

    fun compileFile(
        filePath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): FrontendCompilationResult {
        val file = File(filePath)
        return compileSnippet(file.readText(), file.canonicalPath, parserBackend, runSemantic)
    }

    fun transpileSnippetToC(
        source: String,
        logicalPath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): String {
        val result = compileSnippet(source, logicalPath, parserBackend, runSemantic)
        return KiraCCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileFileToC(
        filePath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): String {
        val result = compileFile(filePath, parserBackend, runSemantic)
        return KiraCCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileSnippetToJS(
        source: String,
        logicalPath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): String {
        val result = compileSnippet(source, logicalPath, parserBackend, runSemantic)
        return KiraJSCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileFileToJS(
        filePath: String,
        parserBackend: ParserBackend = ParserBackend.LEGACY,
        runSemantic: Boolean = false
    ): String {
        val result = compileFile(filePath, parserBackend, runSemantic)
        return KiraJSCodeGenerator(result.compilationUnit).emitToString()
    }

    fun findCCompiler(): String? {
        val candidates = listOf("clang", "cc", "gcc")
        for (candidate in candidates) {
            val proc = ProcessBuilder("which", candidate).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (proc.exitValue() == 0 && out.isNotBlank()) {
                return out
            }
        }
        return null
    }

    fun findNode(): String? {
        val candidates = listOf("node", "nodejs")
        for (candidate in candidates) {
            val proc = ProcessBuilder("which", candidate).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (proc.exitValue() == 0 && out.isNotBlank()) {
                return out
            }
        }
        return null
    }

    fun runJS(jsSource: String, nodePath: String): ProcessResult {
        val dir = File("build/tmp/js-run").apply { mkdirs() }
        val jsFile = File(dir, "program_${System.nanoTime()}.js")
        jsFile.writeText(jsSource)
        return runProcess(listOf(nodePath, jsFile.absolutePath), dir)
    }

    fun syntaxCheckC(cSource: String, compilerPath: String): ProcessResult {
        val dir = File("build/tmp/c-syntax").apply { mkdirs() }
        val cFile = File(dir, "sample_${System.nanoTime()}.c")
        cFile.writeText(cSource)
        return runProcess(listOf(compilerPath, "-fsyntax-only", cFile.absolutePath), dir)
    }

    fun compileAndRunC(cSource: String, compilerPath: String): NativeExecutionResult {
        val dir = File("build/tmp/c-run").apply { mkdirs() }
        val stamp = System.nanoTime().toString()
        val cFile = File(dir, "program_$stamp.c")
        val exeFile = File(dir, "program_$stamp")
        cFile.writeText(cSource)

        val compile = runProcess(
            listOf(compilerPath, cFile.absolutePath, "-o", exeFile.absolutePath),
            dir
        )

        if (compile.exitCode != 0) {
            return NativeExecutionResult(compile, null)
        }

        val run = runProcess(listOf(exeFile.absolutePath), dir)
        return NativeExecutionResult(compile, run)
    }

    private fun runProcess(command: List<String>, workingDir: File): ProcessResult {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()

        return ProcessResult(code, stdout, stderr)
    }

    private inline fun <T> withParserBackend(backend: ParserBackend, block: () -> T): T {
        val previous = System.getProperty("kira.parser")
        System.setProperty("kira.parser", backend.name.lowercase())
        try {
            return block()
        } finally {
            if (previous == null) {
                System.clearProperty("kira.parser")
            } else {
                System.setProperty("kira.parser", previous)
            }
        }
    }
}
