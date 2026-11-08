package net.exoad.kira.cli

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.ProjectFile
import net.exoad.kira.compiler.ProjectSources
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.backend.codegen.py.KiraPyBackend
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.XMLASTVisitorKira
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.utils.Chronos
import net.exoad.kira.utils.EnglishUtils
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.measureTimedValue

private const val TARGET_CHOICES = "c, cpp, js, py, none"

private fun targetFromName(target: String): GeneratedProvider.OutputTarget? {
    return when (target.lowercase()) {
        "c", "native" -> GeneratedProvider.OutputTarget.C
        "cpp", "c++" -> GeneratedProvider.OutputTarget.CPP
        "js", "javascript" -> GeneratedProvider.OutputTarget.JS
        "py", "python" -> GeneratedProvider.OutputTarget.PY
        "none" -> GeneratedProvider.OutputTarget.NONE
        else -> null
    }
}

/** A `--target` this compiler does not know is a usage error: say so and exit 1, with no stack trace. */
private fun unknownTarget(target: String): Nothing {
    Diagnostics.Logging.warn("Kira", "Unknown target '$target' (expected $TARGET_CHOICES)")
    kotlin.system.exitProcess(1)
}

private fun applyTargetOverride(target: String) {
    GeneratedProvider.outputMode = targetFromName(target) ?: unknownTarget(target)
}

fun main(args: Array<String>) {
    // `kira --fix-decl-equals <file.kira>...` rewrites each `x: T = v` to `x: T { v }` (and `x: T = T { .. }` to `x: T { .. }`) in place.
    if (args.firstOrNull() == "--fix-decl-equals") {
        for (f in args.drop(1)) {
            // A file the parser refuses for another reason is named and left as it was.
            val n = try { net.exoad.kira.compiler.fix.DeclEqualsFix.rewriteFile(File(f)).toString() } catch (e: Throwable) { "refused (${e.message?.lineSequence()?.firstOrNull()})" }
            println("$f: $n")
        }
        return
    }
    // `kira --fix-for [--typed] <file.kira>...` rewrites each `for` to the `while` it becomes, in place, and names any loop it left alone.
    // --typed asks the typer, in the project above each file, for the type of an iterable the file does not write.
    if (args.firstOrNull() == "--fix-for") {
        val typed = "--typed" in args
        val files = args.drop(1).filter { it != "--typed" }.map { File(it) }
        if (typed) {
            for ((f, o) in net.exoad.kira.compiler.fix.TypedFix.rewrite(files)) {
                val r = o.result
                if (r == null) {
                    println("$f: ${o.refused}")
                    continue
                }
                if (r.fixed != 0) net.exoad.kira.compiler.fix.ForFix.writeFile(f, r.text)
                println("$f: fixed ${r.fixed}, left ${r.skipped.size}")
                r.skipped.forEach { println("  left: $it") }
            }
            return
        }
        for (f in files) {
            try {
                val r = net.exoad.kira.compiler.fix.ForFix.rewriteFile(f)
                println("$f: fixed ${r.fixed}, left ${r.skipped.size}")
                r.skipped.forEach { println("  left: $it") }
            } catch (e: Throwable) {
                println("$f: refused (${e.message?.lineSequence()?.firstOrNull()})")
            }
        }
        return
    }
    // Minimal CLI: `kira --target js|c|cpp|py|none` overrides project.kira's
    // target; `--readable` emits pretty (non-minified) C/JS output;
    // `--out <dir>` says where the output goes (for cpp and py: every generated
    // file under <dir>); `--check` (cpp and py) regenerates in memory and
    // exits 1 naming every file on disk that differs or is missing (and, for
    // cpp, is stale). The compiler is otherwise cwd-driven.
    var targetOverride: String? = null
    var readableOverride = false
    var checkMode = false
    var outDir: String? = null
    val requestedDumps = mutableListOf<DumpFlag>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--target", "-t" -> {
                if (i + 1 >= args.size) {
                    Diagnostics.panic("--target requires a value ($TARGET_CHOICES)")
                }
                targetOverride = args[i + 1].lowercase()
                if (targetFromName(targetOverride!!) == null) {
                    unknownTarget(targetOverride!!)
                }
                i += 2
            }
            "--readable" -> {
                readableOverride = true
                i += 1
            }
            "--check" -> {
                checkMode = true
                i += 1
            }
            "--out", "-o" -> {
                if (i + 1 >= args.size) {
                    Diagnostics.panic("--out requires a directory")
                }
                outDir = args[i + 1]
                i += 2
            }
            "--help", "-h" -> {
                println("Usage: kira [--target c|cpp|js|py|none] [--out <dir>] [--check] [--readable] ${Dumps.all.joinToString(" ") { "[${it.flag}]" }}")
                println("  --out <dir>  where generated files go (cpp: everything under <dir> in the tree layout, runtime and")
                println("               kira.gen.manifest included; py: each x_kira.py at its path under <dir>; c/js: the")
                println("               directory of out.kira.*)")
                println("  --check      cpp and py: regenerate in memory, name each file on disk that differs, is missing or")
                println("               (cpp) is stale, exit 1 on drift")
                Dumps.helpLines().forEach { println(it) }
                kotlin.system.exitProcess(0)
            }
            else -> {
                val dump = Dumps.named(args[i]) ?: Diagnostics.panic("Unknown argument '${args[i]}' (try --help)")
                if (dump !in requestedDumps) {
                    requestedDumps.add(dump)
                }
                i += 1
            }
        }
    }
    val result = measureTimedValue {
        val projectRoot: Path = Paths.get(".").toAbsolutePath().normalize()
        var project: Project? = null
        val projectFile = ProjectFile.find(projectRoot)

        if (projectFile != null) {
            try {
                project = ProjectFile.load(projectFile)
                val issues = project.issues(projectRoot)
                if (issues.isNotEmpty()) {
                    issues.forEach { issue ->
                        Diagnostics.Logging.warn("Kira", "Project issue [${issue.field}]: ${issue.message}")
                    }
                    Diagnostics.panic("${ProjectFile.FILE} has issues. See warnings above.")
                }

                Diagnostics.Logging.info("Kira", "Loaded project config from $projectFile")

                targetFromName(project.target)?.let { GeneratedProvider.outputMode = it }
                // A --target flag beats the project's target.
                if (targetOverride != null) {
                    applyTargetOverride(targetOverride!!)
                }
            } catch (e: Exception) {
                Diagnostics.panic("Failed to load project config from $projectFile: ${e.message}")
            }
        } else if (targetOverride != null) {
            // No project file: the flag is the only target source.
            applyTargetOverride(targetOverride!!)
        }
        if (checkMode && GeneratedProvider.outputMode != GeneratedProvider.OutputTarget.CPP &&
            GeneratedProvider.outputMode != GeneratedProvider.OutputTarget.PY
        ) {
            // A usage error, not a compiler failure: say so and stop, without a stack trace.
            Diagnostics.Logging.warn("Kira", "--check is only supported with --target cpp or --target py")
            kotlin.system.exitProcess(1)
        }

        // Minified + obfuscated output is the default; `minify = false`
        // in project.kira, or the --readable flag, restores pretty output.
        GeneratedProvider.minifyOutput = project?.minify ?: true
        if (readableOverride) {
            GeneratedProvider.minifyOutput = false
        }

        ProjectSources.missingDependency(project)?.let {
            Diagnostics.Logging.warn("Kira", it)
            kotlin.system.exitProcess(1)
        }
        val stdlibEntries = ProjectSources.dependencies(project, projectRoot)
        Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()
        // project.kira is typed as any code is, against the stdlib it names.
        if (project != null && projectFile != null) {
            val findings = ProjectFile.check(projectFile, Public.Builtin.intrinsicalStandardLibrarySources.toList())
            if (findings.isNotEmpty()) {
                findings.forEach { Diagnostics.Logging.warn("Kira", it) }
                Diagnostics.panic("${ProjectFile.FILE} does not type: see above.")
            }
        }
        val dumpSB = if (project?.emitIr != null) StringBuilder() else null
        val workspaceSources: Array<String> = ProjectSources.workspace(project, projectRoot).toTypedArray()
        if (workspaceSources.isEmpty() && Public.Builtin.intrinsicalStandardLibrarySources.isEmpty()) {
            Diagnostics.panic("No source files to compile. Add .kira files to 'src' or set srcDir in project.kira.")
        }
        val sources = arrayOf(*Public.Builtin.intrinsicalStandardLibrarySources, *workspaceSources)
        // The file is the module: each one's name is its project's and its path's.
        val moduleNames = ModuleNames.assign(project, projectRoot, Public.Builtin.intrinsicalStandardLibrarySources.toList(), workspaceSources.toList())
        Diagnostics.Logging.info("Kira", "Parser: kotlin-native (KiraLexer + KiraParser)")
        dumpSB?.appendLine(
            "----------- Kira Processed Symbols Dump File -----------\nGenerated: ${Chronos.formatTimestamp()}\nTotal Source Files: ${sources.size}\nSources List: \n${
                sources.joinToString(
                    "\n"
                ) { " $it" }
            }"
        )
        val dumpFile = project?.emitIr?.let { File(it) }
        if (dumpFile?.exists() ?: false) {
            dumpFile.delete()
        }
        dumpFile?.createNewFile()
        val compilationUnit = CompilationUnit()
        for (sourceFile in sources) {
            dumpSB?.appendLine("----------- '$sourceFile' / ${sources.size} -----------")
            val file = File(sourceFile)
            val preprocessor = KiraPreprocessor(file.readText())
            val preprocessingResult = preprocessor.process()
            var srcContext = compilationUnit.addSource(
                file.canonicalPath,
                preprocessingResult.processedContent,
                emptyList(),
                moduleNames[file.canonicalPath],
            )
            val (_, duration) = measureTimedValue {
                val lexer = KiraLexer(srcContext)
                val tokens = diagnosedOrExit { lexer.tokenize() }
                srcContext = compilationUnit.addSource(
                    file.canonicalPath,
                    srcContext.content,
                    tokens
                )
                if (dumpSB != null) {
                    var i = 0
                    dumpSB.appendLine("    ############### Lexer Tokens '$sourceFile' ###############")
                    dumpSB.appendLine(srcContext.tokens.joinToString("\n") { tk ->
                        "    ${
                            (++i).toString().padStart(
                                length = floor(log10(srcContext.tokens.size.toDouble())).toInt() + 1,
                                padChar = ' '
                            )
                        }: $tk"
                    })
                    dumpFile!!.appendText(dumpSB.toString())
                    dumpSB.clear() // save on memory (so not everything is in dumpSB): problematic for large projects
                }
                diagnosedOrExit { KiraParser.parse(srcContext) }

            }
            Diagnostics.Logging.info("Kira", "Parsed ${file.name} in $duration")
            if (dumpSB != null) {
                dumpSB.appendLine("    ############### AST XML '$sourceFile' ###############")
                dumpSB.appendLine(
                    XMLASTVisitorKira.build(srcContext.ast).split("\n").joinToString("\n") { "    $it" })
                dumpFile!!.appendText(dumpSB.toString())
                dumpSB.clear()
                dumpSB.appendLine("    ############### AST -> SRC MAP '$sourceFile' ###############")
                dumpSB.appendLine("\tTotal Sources: ${compilationUnit.getSourcesLength()}")
                compilationUnit.allSources().forEach {
                    it.astOrigins.entries.sortedBy { entry -> entry.value }.forEach { element ->
                        dumpSB.appendLine("        ${element.value.lineNumber}, ${element.value.column} : ${element.key}")
                    }
                }
                dumpFile.appendText(dumpSB.toString())
                dumpSB.clear()
            }
        }

        // A dump flag prints its stage for the project's own files and stops: no analysis, no backend.
        if (requestedDumps.isNotEmpty()) {
            val workspace = workspaceSources.map { File(it).canonicalPath }
            return@measureTimedValue Dumps.run(requestedDumps, DumpInput(compilationUnit, workspace, project, projectRoot))
        }

        // Semantics before any backend emit so bad programs do not produce half-written C.
        val semanticAnalyzer = KiraSemanticAnalyzer(compilationUnit)
        val semanticAnalyzerResults = semanticAnalyzer.validateAST()
        val diagnosticCount = semanticAnalyzerResults.diagnostics.size
        if (diagnosticCount > 0) {
            repeat(diagnosticCount) {
                Diagnostics.Logging.warn(
                    "Kira",
                    "\n-- Diagnostic Report #${it + 1} ${
                        Diagnostics.recordDiagnostics(
                            semanticAnalyzerResults.diagnostics[it]
                        )
                    }"
                )
            }
            Diagnostics.Logging.info(
                "Kira",
                "** Found $diagnosticCount issue${
                    EnglishUtils.getPluralSuffix(diagnosticCount)
                }. See the diagnostic${
                    EnglishUtils.getPluralSuffix(diagnosticCount)
                } above."
            )
        }

        if (dumpSB != null) {
            dumpSB.appendLine("############### CANON SYMBOL TABLE ###############")
            dumpSB.appendLine("Total Symbols: ${compilationUnit.symbolTable.totalSymbols()}")
            var scopeIdx = 0
            compilationUnit.symbolTable.forEach { frame ->
                scopeIdx += 1
                dumpSB.appendLine(
                    "\nScope #$scopeIdx: Kind=${
                        when (frame.kind) {
                            is SemanticScope.Global -> "Global"
                            is SemanticScope.Module -> "Module"
                            is SemanticScope.Class -> "Class"
                            is SemanticScope.Function -> "Function"
                            is SemanticScope.Trait -> "Trait"
                            else -> frame.kind.toString()
                        }
                    }, Name=${
                        when (frame.kind) {
                            is SemanticScope.Module -> frame.kind.name
                            is SemanticScope.Class -> frame.kind.name
                            is SemanticScope.Function -> frame.kind.name
                            is SemanticScope.Trait -> frame.kind.name
                            is SemanticScope.Global -> "(global)"
                            else -> "(unknown)"
                        }
                    }, Symbols=${frame.symbols.size}"
                )
                if (frame.symbols.isNotEmpty()) {
                    frame.symbols.values.forEach { v ->
                        dumpSB.appendLine("    $v")
                    }
                }
            }
            dumpSB.appendLine("----------- End Dump File -----------")
            dumpFile!!.appendText(dumpSB.toString())
            dumpSB.clear()
            Diagnostics.Logging.info("Kira", "Dumped processed symbols to ${dumpFile.path}.")
        }

        // Backend emit only after a clean semantic pass.
        var backendFailures = 0
        if (diagnosticCount == 0) {
            when (GeneratedProvider.outputMode) {
                GeneratedProvider.OutputTarget.C -> {
                    val out = outputPathIn(outDir, KiraCCodeGenerator.DEFAULT_OUTPUT)
                    Diagnostics.Logging.info("Kira", "Emitting C -> $out")
                    KiraCCodeGenerator(compilationUnit).generate(out)
                    val cSources = project?.cSources.orEmpty()
                    val linkFlags = project?.linkFlags.orEmpty()
                    val extras = buildString {
                        cSources.forEach { append(' ').append(it) }
                        linkFlags.forEach { append(' ').append(it) }
                    }
                    Diagnostics.Logging.info(
                        "Kira",
                        "Done. Compile with: cc -std=c17 -O2 -o app $out$extras && ./app"
                    )
                }

                GeneratedProvider.OutputTarget.JS -> {
                    val out = outputPathIn(outDir, KiraJSCodeGenerator.DEFAULT_OUTPUT)
                    Diagnostics.Logging.info("Kira", "Emitting JS -> $out")
                    KiraJSCodeGenerator(compilationUnit).generate(out)
                    Diagnostics.Logging.info(
                        "Kira",
                        "Done. Run with: node $out"
                    )
                }

                GeneratedProvider.OutputTarget.CPP -> {
                    Diagnostics.Logging.info("Kira", if (checkMode) "Checking C++" else "Emitting C++")
                    val result = try {
                        KiraCppBackend.run(
                            unit = compilationUnit,
                            project = project,
                            check = checkMode,
                            projectRoot = projectRoot,
                            outDirOverride = outDir,
                        )
                    } catch (e: UnsupportedConstruct) {
                        // A construct no part lowers yet; the emitter reports these itself,
                        // this is the belt to that braces (w1-1's note).
                        Diagnostics.Logging.warn("Kira", e.withTarget("C++").message ?: e.toString())
                        kotlin.system.exitProcess(1)
                    }
                    if (result.exitCode != 0) {
                        backendFailures += 1
                    }
                }

                GeneratedProvider.OutputTarget.PY -> {
                    Diagnostics.Logging.info("Kira", if (checkMode) "Checking py" else "Emitting py")
                    val result = KiraPyBackend.run(
                        unit = compilationUnit,
                        check = checkMode,
                        projectRoot = projectRoot,
                        outDirOverride = outDir,
                    )
                    if (result.exitCode != 0) {
                        backendFailures += 1
                    }
                }

                else -> {}
            }
        } else {
            Diagnostics.Logging.warn(
                "Kira",
                "Skipping backend emit because of $diagnosticCount diagnostic${
                    EnglishUtils.getPluralSuffix(diagnosticCount)
                }."
            )
        }

        diagnosticCount + backendFailures
    }
    Diagnostics.Logging.info("Kira", "Everything took ${result.duration}")
    if (result.value > 0) {
        kotlin.system.exitProcess(1)
    }
}

/** `<dir>/<fileName>` when `--out` was given, else the bare file name in the working directory. */
private fun outputPathIn(dir: String?, fileName: String): String {
    if (dir.isNullOrBlank()) {
        return fileName
    }
    val directory = File(dir)
    directory.mkdirs()
    return File(directory, fileName).path
}

private inline fun <T> diagnosedOrExit(block: () -> T): T = try {
    block()
} catch (e: DiagnosticsException) {
    Diagnostics.Logging.warn("Kira", "\n-- Diagnostic Report #1 ${Diagnostics.recordDiagnostics(e)}")
    kotlin.system.exitProcess(1)
}


