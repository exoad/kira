package net.exoad.kira.cli

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.ast.XMLASTVisitorKira
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.utils.Chronos
import net.exoad.kira.utils.EnglishUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.measureTimedValue

fun main() {
    val result = measureTimedValue {
//        Diagnostics.silenceDiagnostics()
        val projectRoot: Path = Paths.get(".").toAbsolutePath().normalize()
        var manifest: ProjectManifest? = null
        val yamlManifestPath = projectRoot.resolve("kira.yaml")
        val legacyTomlPath = projectRoot.resolve("kira.toml")

        if (!Files.exists(yamlManifestPath) && Files.exists(legacyTomlPath)) {
            Diagnostics.panic(
                "Detected legacy 'kira.toml'. KIM TOML manifests were removed. " +
                    "Please migrate to 'kira.yaml' with keys: project.name, srcDir, build.target, compiler.emitIr, dependencies.<name>.path"
            )
        }

        if (Files.exists(yamlManifestPath)) {
            try {
                manifest = ManifestLoader.loadFromPath(yamlManifestPath)
                val issues = ManifestValidator.validate(manifest, projectRoot)
                if (issues.isNotEmpty()) {
                    issues.forEach { issue ->
                        Diagnostics.Logging.warn("Kira", "Manifest issue [${issue.field}]: ${issue.message}")
                    }
                    Diagnostics.panic("Manifest validation failed. See warnings above.")
                }

                Diagnostics.Logging.info("Kira", "Loaded project config from $yamlManifestPath")

                when (manifest.build.target.lowercase()) {
                    "c", "native" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.C
                    "neko" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NEKO
                    "none" -> {}
                }
            } catch (e: Exception) {
                Diagnostics.panic("Failed to load project config from $yamlManifestPath: ${e.message}")
            }
        }

        val stdlibEntries = DependencyResolver.resolveDependencySources(manifest, projectRoot).toMutableList()
        if (stdlibEntries.isEmpty()) {
            stdlibEntries.addAll(Public.Builtin.discoverLegacyKiraFolder())
        }
        Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()
        val dumpSB = if (manifest?.compiler?.emitIr != null) StringBuilder() else null
        val workspaceSources: Array<String> = DependencyResolver.resolveProjectSources(manifest, projectRoot).toTypedArray()
        if (workspaceSources.isEmpty() && Public.Builtin.intrinsicalStandardLibrarySources.isEmpty()) {
            Diagnostics.panic("No source files to compile. Add .kira files to 'src' or configure 'kira.yaml'.")
        }
        val sources = arrayOf(*Public.Builtin.intrinsicalStandardLibrarySources, *workspaceSources)
        Diagnostics.Logging.info("Kira", "Parser backend: ${KiraSourceParsers.activeBackend().name.lowercase()}")
        dumpSB?.appendLine(
            "----------- Kira Processed Symbols Dump File -----------\nGenerated: ${Chronos.formatTimestamp()}\nTotal Source Files: ${sources.size}\nSources List: \n${
                sources.joinToString(
                    "\n"
                ) { " $it" }
            }"
        )
        val dumpFile = if (manifest?.compiler?.emitIr != null) File(manifest.compiler.emitIr) else null
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
                KiraSourceParsers.from(srcContext).parse()

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
                            is SemanticScope.Enum -> "Enum"
                            is SemanticScope.Trait -> "Trait"
                            is SemanticScope.Variant -> "Variant"
                            is SemanticScope.VariantMember -> "VariantMember"
                            else -> frame.kind.toString()
                        }
                    }, Name=${
                        when (frame.kind) {
                            is SemanticScope.Module -> frame.kind.name
                            is SemanticScope.Class -> frame.kind.name
                            is SemanticScope.Function -> frame.kind.name
                            is SemanticScope.Enum -> frame.kind.name
                            is SemanticScope.Trait -> frame.kind.name
                            is SemanticScope.Variant -> frame.kind.name
                            is SemanticScope.VariantMember -> frame.kind.name
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
        if (diagnosticCount == 0) {
            when (GeneratedProvider.outputMode) {
                GeneratedProvider.OutputTarget.C -> {
                    val out = KiraCCodeGenerator.DEFAULT_OUTPUT
                    Diagnostics.Logging.info("Kira", "Emitting C -> $out")
                    KiraCCodeGenerator(compilationUnit).generate(out)
                    val cSources = manifest?.build?.cSources.orEmpty()
                    val linkFlags = manifest?.build?.linkFlags.orEmpty()
                    val extras = buildString {
                        cSources.forEach { append(' ').append(it) }
                        linkFlags.forEach { append(' ').append(it) }
                    }
                    Diagnostics.Logging.info(
                        "Kira",
                        "Done. Compile with: cc -std=c17 -O2 -o app $out$extras && ./app"
                    )
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

        diagnosticCount
    }
    Diagnostics.Logging.info("Kira", "Everything took ${result.duration}")
    if (result.value > 0) {
        kotlin.system.exitProcess(1)
    }
}


