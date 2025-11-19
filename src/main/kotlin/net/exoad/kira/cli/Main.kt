package net.exoad.kira.cli

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTAtomOneDarkIJTheme
import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.XMLASTVisitorKira
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.ui.KiraVisualViewer
import net.exoad.kira.utils.Chronos
import net.exoad.kira.utils.EnglishUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.UIManager
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.measureTimedValue

internal lateinit var argsParser: ArgsParser

/**
 * This can be called from any other programs, but for the most part, the required parameter
 * is the `--src` option which points to the source file you want to compile. For example, `--src=hello.kira`
 */
fun main(args: Array<String>) {
    try {
        UIManager.setLookAndFeel(FlatMTAtomOneDarkIJTheme())
    } catch (e: Exception) {
        e.printStackTrace()
    }
    argsParser = ArgsParser(args)
    val (_, duration) = measureTimedValue {
        parseArgs().let { it ->
            when (it.useDiagnostics) {
                true -> Diagnostics.useDiagnostics()
                else -> Diagnostics.silenceDiagnostics()
            }
            val projectPathOpt = argsParser.findOption("--project")
            val projectRoot: Path = if (projectPathOpt != null) {
                val p = Paths.get(projectPathOpt)
                if (p.toFile().isFile) p.parent else p
            } else {
                Paths.get(".").toAbsolutePath().normalize()
            }
            var manifest: ProjectManifest? = null
            val manifestPath = listOf("kira.toml")
                .map { projectRoot.resolve(it) }
                .firstOrNull { it.toFile().exists() }
            if (manifestPath != null) {
                try {
                    manifest = ManifestLoader.loadFromPath(manifestPath)
                    val issues = ManifestValidator.validate(manifest, projectRoot)
                    if (issues.isNotEmpty()) {
                        issues.forEach { issue ->
                            Diagnostics.Logging.warn("Kira", "Manifest issue [${issue.field}]: ${issue.message}")
                        }
                        Diagnostics.panic("Manifest validation failed. See warnings above.")
                    }

                    Diagnostics.Logging.info("Kira", "Loaded project manifest from $manifestPath")

                    // Resolve standard library sources from manifest dependencies
                    val stdlibEntries = mutableListOf<String>()
                    if (manifest.dependencies.isNotEmpty()) {
                        manifest.dependencies.forEach { (name, spec) ->
                            // treat dependencies with registry 'kira' as standard lib providers
                            if (spec.registry == "kira") {
                                if (spec.path != null) {
                                    val depPath = projectRoot.resolve(spec.path).normalize()
                                    if (Files.exists(depPath)) {
                                        Files.walk(depPath).use { stream ->
                                            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                                                .forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                                        }
                                    }
                                } else {
                                    // fallback look for 'kira' folder in project root
                                    val kiraDir = projectRoot.resolve("kira").normalize()
                                    if (Files.exists(kiraDir) && Files.isDirectory(kiraDir)) {
                                        Files.walk(kiraDir).use { stream ->
                                            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                                                .forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // If still empty, try default kira folder discovery
                    if (stdlibEntries.isEmpty()) {
                        stdlibEntries.addAll(Public.Builtin.discoverLegacyKiraFolder())
                    }

                    Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()

                    if (manifest.build.target.isNotEmpty()) {
                        when (manifest.build.target.lowercase()) {
                            "c", "native" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.C
                            "neko" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NEKO
                            else -> Diagnostics.Logging.warn(
                                "Kira",
                                "Unknown build target '${manifest.build.target}', ignoring."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Diagnostics.panic("Failed to load manifest from $manifestPath: ${e.message}")
                }
            } else if (projectPathOpt != null) {
                Diagnostics.panic("No 'kira.toml' found in project directory: $projectRoot")
            }
            val dumpSB = if (it.dump != null) StringBuilder() else null
            val workspaceSources: Array<String> = if (manifest != null) {
                val resolvedSources = mutableListOf<String>()
                if (manifest.workspace.entry != null) {
                    val entryPath = projectRoot.resolve(manifest.workspace.entry).normalize()
                    if (entryPath.toFile().exists()) {
                        resolvedSources.add(entryPath.toString())
                    } else {
                        Diagnostics.Logging.warn(
                            "Kira",
                            "Entry point '${manifest.workspace.entry}' not found, skipping."
                        )
                    }
                }
                manifest.workspace.src.forEach { srcPattern ->
                    val srcPath = projectRoot.resolve(srcPattern).normalize()
                    if (srcPath.toFile().exists()) {
                        if (srcPath.toFile().isFile) {
                            resolvedSources.add(srcPath.toString())
                        } else if (srcPath.toFile().isDirectory) {
                            Files.walk(srcPath).use { stream ->
                                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                                    .forEach { resolvedSources.add(it.toString()) }
                            }
                        }
                    } else {
                        val parent = if (srcPattern.contains("/") || srcPattern.contains("\\")) {
                            projectRoot.resolve(srcPattern.substringBeforeLast("/"))
                        } else {
                            projectRoot
                        }
                        if (parent.toFile().exists() && parent.toFile().isDirectory) {
                            Files.walk(parent, 1).use { stream ->
                                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                                    .forEach { resolvedSources.add(it.toString()) }
                            }
                        }
                    }
                }
                if (resolvedSources.isEmpty()) {
                    Diagnostics.Logging.warn("Kira", "No source files found in workspace, falling back to CLI sources.")
                    it.src
                } else {
                    Diagnostics.Logging.info(
                        "Kira",
                        "Using ${resolvedSources.size} source file(s) from manifest workspace."
                    )
                    resolvedSources.distinct().toTypedArray()
                }
            } else {
                it.src
            }
            if (workspaceSources.isEmpty() && Public.Builtin.intrinsicalStandardLibrarySources.isEmpty()) {
                Diagnostics.panic("No source files to compile. Please provide sources via --src or a manifest (kira.toml).")
            }
            val sources = arrayOf(*Public.Builtin.intrinsicalStandardLibrarySources, *workspaceSources)
            dumpSB?.appendLine(
                "----------- Kira Processed Symbols Dump File -----------\nGenerated: ${Chronos.formatTimestamp()}\nTotal Source Files: ${sources.size}\nSources List: \n${
                    sources.joinToString(
                        "\n"
                    ) { " $it" }
                }"
            )
            val dumpFile = if (it.dump != null) File("${it.dump}.kira.txt") else null
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
                    emptyList()
                )
                val (_, duration) = measureTimedValue {
                    val lexer = KiraLexer(srcContext)
                    val tokens = lexer.tokenize()
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
                    KiraParser(srcContext).parse()

                }
                if (Public.flags["enableVisualView"]!!) {
                    KiraVisualViewer(srcContext).also { it.run() }
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
                when (GeneratedProvider.outputMode) {
                    GeneratedProvider.OutputTarget.C -> {
                        Diagnostics.Logging.info("Kira", "Outputting to 'target C'")
                        KiraCCodeGenerator(compilationUnit).generate()
                    }

                    else -> {}
                }
            }
            val semanticAnalyzer = KiraSemanticAnalyzer(compilationUnit)
            val semanticAnalyzerResults = semanticAnalyzer.validateAST()
            if (semanticAnalyzerResults.diagnostics.isNotEmpty()) {
                repeat(semanticAnalyzerResults.diagnostics.size) {
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
                    "** Found ${semanticAnalyzerResults.diagnostics.size} issues. See the diagnostic${
                        EnglishUtils.getPluralSuffix(
                            semanticAnalyzerResults.diagnostics.size
                        )
                    } above."
                )
            }
            if (dumpSB != null) {
                dumpSB.appendLine("############### CANON SYMBOL TABLE ###############")
                dumpSB.appendLine("Total Symbols: ${compilationUnit.symbolTable.totalSymbols()}")
                var scopeIdx = 0
                compilationUnit.symbolTable.forEach { frame ->
                    scopeIdx += 1
                    val scopeKind = when (frame.kind) {
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Global -> "Global"
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Module -> "Module"
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Class -> "Class"
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Function -> "Function"
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Enum -> "Enum"
                        else -> frame.kind.toString()
                    }
                    val scopeName = when (frame.kind) {
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Module -> frame.kind.name
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Class -> frame.kind.name
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Function -> frame.kind.name
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Enum -> frame.kind.name
                        is net.exoad.kira.compiler.analysis.semantic.SemanticScope.Global -> "(global)"
                        else -> "(unknown)"
                    }
                    dumpSB.appendLine("\nScope #$scopeIdx: Kind=$scopeKind, Name=$scopeName, Symbols=${frame.symbols.size}")
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
        }
    }
    Diagnostics.Logging.info("Kira", "Everything took $duration")
}

fun parseArgs(): ArgumentOptions {
    parsePublicFlags()
    val useDiagnostics = argsParser.findOption("--diagnostics", "false")!!.equals("true", true)
    val srcLocOption = argsParser.findOption("--src")
    val srcArray = srcLocOption?.split(",")?.toTypedArray() ?: emptyArray()

    val dump = argsParser.findOption("--dump")
    // ephemeral options
    val outputFileOption = argsParser.findOption("--out")
    val outputModeOption = argsParser.findOption("--target")
    when (outputModeOption?.lowercase()) {
        "neko" -> {
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NEKO
            if (outputFileOption == null) {
                Diagnostics.panic("Output target 'NEKO' requires an output file with the '--out' option!")
            }
            GeneratedProvider.outputFile = outputFileOption
        }

        "c" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.C
        else -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NONE
    }
    return ArgumentOptions(
        useDiagnostics,
        srcArray,
        dump
    )
}

fun parsePublicFlags() {
    // the flipping and unflipping of the conditions just sometimes gets me mixed up for some reason lol
    //
    // is my brain too slow ?
    val flags = mutableMapOf<String, Boolean>()
    Public.flagsDefault.forEach { (k, v) ->
        val res = argsParser.findFlag("-$k")
        flags[k] = if (!res) v else true
    }
    Public.flags = flags
//    Public.Flags.useDiagnosticsUnicode = !argsParser.findFlag("-noPrettyDiagnostics")
//    Public.Flags.beVerbose = argsParser.findFlag("-verbose")
//    Public.Flags.enableVisualView = argsParser.findFlag("-visualView")
}