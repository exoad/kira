package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
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
        runSemantic: Boolean = false
    ): FrontendCompilationResult {
        val pre = KiraPreprocessor(source)
        val preprocessed = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource(logicalPath, preprocessed.processedContent, emptyList())
        val tokens = KiraLexer(src).tokenize()
        val srcWithTokens = cu.addSource(logicalPath, src.content, tokens)

        KiraSourceParsers.from(srcWithTokens).parse()

        val semantics = if (runSemantic) {
            KiraSemanticAnalyzer(cu).validateAST()
        } else {
            null
        }

        return FrontendCompilationResult(cu, srcWithTokens, semantics)
    }

    fun compileFile(
        filePath: String,
        runSemantic: Boolean = false
    ): FrontendCompilationResult {
        val file = File(filePath)
        return compileSnippet(file.readText(), file.canonicalPath, runSemantic)
    }

    fun transpileSnippetToC(
        source: String,
        logicalPath: String,
        runSemantic: Boolean = false
    ): String {
        val result = compileSnippet(source, logicalPath, runSemantic)
        return KiraCCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileFileToC(
        filePath: String,
        runSemantic: Boolean = false
    ): String {
        val result = compileFile(filePath, runSemantic)
        return KiraCCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileSnippetToJS(
        source: String,
        logicalPath: String,
        runSemantic: Boolean = false
    ): String {
        val result = compileSnippet(source, logicalPath, runSemantic)
        return KiraJSCodeGenerator(result.compilationUnit).emitToString()
    }

    fun transpileFileToJS(
        filePath: String,
        runSemantic: Boolean = false
    ): String {
        val result = compileFile(filePath, runSemantic)
        return KiraJSCodeGenerator(result.compilationUnit).emitToString()
    }

    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * The C compiler the runtime tests drive: `$CC` when set, otherwise the
     * first of clang / cc / gcc found on PATH. Returns a path
     * [ProcessBuilder] can run on the host OS -- on Windows that is the
     * `.exe`, never the MSYS `/c/...` spelling `which` prints.
     */
    fun findCCompiler(): String? {
        return findTool("CC", listOf("clang", "cc", "gcc"))
    }

    /** `$NODE` when set, otherwise node / nodejs on PATH. */
    fun findNode(): String? {
        return findTool("NODE", listOf("node", "nodejs"))
    }

    /**
     * The installed CLI launcher from `installDist`: the POSIX script on
     * Linux/macOS, the `.bat` on Windows.
     */
    fun installedKiraLauncher(): File {
        val bin = File("build/install/kira/bin")
        return if (isWindows) File(bin, "kira.bat") else File(bin, "kira")
    }

    private fun findTool(envOverride: String, candidates: List<String>): String? {
        val override = System.getenv(envOverride)?.trim()
        if (!override.isNullOrEmpty()) {
            val asFile = File(override)
            if (asFile.isFile) {
                return asFile.absolutePath
            }
            return findOnPath(override) ?: override
        }
        for (candidate in candidates) {
            val found = findOnPath(candidate)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /** Search PATH for [name]; on Windows also try the PATHEXT spellings. */
    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val extensions = if (isWindows) {
            listOf("") + (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD")
                .split(';')
                .filter { it.isNotBlank() }
                .map { it.lowercase() }
        } else {
            listOf("")
        }
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (ext in extensions) {
                val candidate = File(dir, name + ext)
                if (candidate.isFile && (isWindows || candidate.canExecute())) {
                    return candidate.absolutePath
                }
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

        // Drain stderr on its own thread so a chatty compiler cannot block on
        // a full pipe while we wait on stdout.
        val stderrHolder = StringBuilder()
        val stderrReader = Thread { stderrHolder.append(process.errorStream.bufferedReader().readText()) }
        stderrReader.start()
        val stdout = process.inputStream.bufferedReader().readText()
        stderrReader.join()
        val code = process.waitFor()

        // A Windows C runtime writes "\r\n" for every "\n" on a text-mode
        // pipe; the tests assert the program's text, not the host's line
        // ending. No-op on Linux/macOS.
        return ProcessResult(code, stdout.replace("\r", ""), stderrHolder.toString())
    }
}
