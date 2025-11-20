package net.exoad.kira.cli

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTAtomOneDarkIJTheme
import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
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

fun main() {
    try {
        UIManager.setLookAndFeel(FlatMTAtomOneDarkIJTheme())
    } catch (e: Exception) {
        e.printStackTrace()
    }
    val (_, duration) = measureTimedValue {
//        Diagnostics.silenceDiagnostics()
        val projectRoot: Path = Paths.get(".").toAbsolutePath().normalize()
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
                val stdlibEntries = mutableListOf<String>()
                if (manifest.dependencies.isNotEmpty()) {
                    manifest.dependencies.forEach { (_, spec) ->
                        if (spec.path != null) {
                            var depPath = projectRoot.resolve(spec.path).normalize()
                            if (!Files.exists(depPath)) {
                                val parent = projectRoot.parent
                                if (parent != null) {
                                    val alt = parent.resolve(spec.path).normalize()
                                    if (Files.exists(alt)) depPath = alt
                                }
                            }
                            if (Files.exists(depPath)) {
                                if (Files.isRegularFile(depPath) && depPath.toString().endsWith(".kira")) {
                                    stdlibEntries.add(depPath.toAbsolutePath().toString())
                                } else if (Files.isDirectory(depPath)) {
                                    val depManifestPath = depPath.resolve("kira.toml")
                                    if (Files.exists(depManifestPath)) {
                                        try {
                                            val depManifest = ManifestLoader.loadFromPath(depManifestPath)
                                            depManifest.workspace.src.forEach { pattern ->
                                                val srcPath = depPath.resolve(pattern).normalize()
                                                if (srcPath.toFile().exists()) {
                                                    if (srcPath.toFile().isFile) {
                                                        stdlibEntries.add(srcPath.toAbsolutePath().toString())
                                                    } else if (srcPath.toFile().isDirectory) {
                                                        Files.walk(srcPath).use { stream ->
                                                            stream.filter {
                                                                Files.isRegularFile(it) && it.toString()
                                                                    .endsWith(".kira")
                                                            }.forEach {
                                                                stdlibEntries.add(
                                                                    it.toAbsolutePath().toString()
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {
                                            Files.walk(depPath).use { stream ->
                                                stream.filter {
                                                    Files.isRegularFile(it) && it.toString().endsWith(".kira")
                                                }.forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                                            }
                                        }
                                    } else {
                                        Files.walk(depPath).use { stream ->
                                            stream.filter {
                                                Files.isRegularFile(it) && it.toString().endsWith(".kira")
                                            }.forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                                        }
                                    }
                                }
                            }
                        } else if (spec.registry == "kira") {
                            val kiraDir = projectRoot.resolve("kira").normalize()
                            if (Files.exists(kiraDir) && Files.isDirectory(kiraDir)) {
                                Files.walk(kiraDir).use { stream ->
                                    stream.filter {
                                        Files.isRegularFile(it) && it.toString().endsWith(".kira")
                                    }.forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                                }
                            }
                        }
                    }
                }
                if (stdlibEntries.isEmpty()) {
                    stdlibEntries.addAll(Public.Builtin.discoverLegacyKiraFolder())
                }
                Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()
                if (manifest.build.target.isNotEmpty()) {
                    when (manifest.build.target.lowercase()) {
                        "c", "native" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.C
                        "neko" -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NEKO
                        "none" -> {}
                        else -> Diagnostics.Logging.warn(
                            "Kira",
                            "Unknown build target '${manifest.build.target}', ignoring."
                        )
                    }
                }
            } catch (e: Exception) {
                Diagnostics.panic("Failed to load manifest from $manifestPath: ${e.message}")
            }
        }
        val dumpSB = if (manifest?.build?.emitIR != null) StringBuilder() else null
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
                            stream.filter {
                                Files.isRegularFile(it) && it.toString().endsWith(".kira")
                            }.forEach { resolvedSources.add(it.toString()) }
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
                Diagnostics.Logging.warn(
                    "Kira",
                    "No source files found in workspace, falling back to searching project directory for .kira files."
                )
                // fallthrough to project-wide discovery below
            }
            resolvedSources.distinct().toTypedArray()
        } else {
            // No manifest: search the project root for .kira files
            val resolved = mutableListOf<String>()
            try {
                Files.walk(projectRoot).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                        .forEach { resolved.add(it.toString()) }
                }
            } catch (_: Exception) {
            }
            resolved.distinct().toTypedArray()
        }

        if (workspaceSources.isEmpty() && Public.Builtin.intrinsicalStandardLibrarySources.isEmpty()) {
            Diagnostics.panic("No source files to compile. Please add sources to the workspace or provide a manifest (kira.toml).")
        }

        val sources = arrayOf(*Public.Builtin.intrinsicalStandardLibrarySources, *workspaceSources)
        dumpSB?.appendLine(
            "----------- Kira Processed Symbols Dump File -----------\nGenerated: ${Chronos.formatTimestamp()}\nTotal Source Files: ${sources.size}\nSources List: \n${
                sources.joinToString(
                    "\n"
                ) { " $it" }
            }"
        )
        val dumpFile = if (manifest?.build?.emitIR != null) File(manifest.build.emitIR) else null
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
//            if (Public.flags["enableVisualView"]!!) {
//                KiraVisualViewer(srcContext).also { it.run() }
//            }
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
    }
    Diagnostics.Logging.info("Kira", "Everything took $duration")
}
