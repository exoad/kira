package net.exoad.kira.cli

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTAtomOneDarkIJTheme
import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourcePosition
import net.exoad.kira.ui.KiraVisualViewer
import net.exoad.kira.utils.LocaleUtils
import net.exoad.kira.utils.XMLASTVisitor
import java.io.File
import javax.swing.UIManager
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.measureTimedValue

internal lateinit var argsParser: ArgsParser

/**
 * This can be called from any other programs, but for the most part, the required parameter
 * is the `--src` option which points to the source file you want to compile. For example, `--src=hello.kira`
 */
fun main(args: Array<String>)
{
    try
    {
        UIManager.setLookAndFeel(FlatMTAtomOneDarkIJTheme())
    }
    catch(e: Exception)
    {
        e.printStackTrace()
    }
    argsParser = ArgsParser(args)
    val (_, duration) = measureTimedValue {
        parseArgs().let { it ->
            when(it.useDiagnostics)
            {
                true -> Diagnostics.useDiagnostics()
                else -> Diagnostics.silenceDiagnostics()
            }
            val compilationUnit = CompilationUnit()
            val sources = arrayOf(*Public.Builtin.intrinsicalStandardLibrarySources, *it.src)
            for(sourceFile in sources)
            {
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
                    if(Public.Flags.enableVisualView)
                    {
                        KiraVisualViewer(srcContext).also { it.run() }
                    }
                    if(it.dumpLexerTokens != null)
                    {
                        val lexerTokensDumpFile = File(it.dumpLexerTokens)
                        lexerTokensDumpFile.createNewFile()
                        var i = 0
                        lexerTokensDumpFile.writeText(srcContext.tokens.joinToString("\n") { tk ->
                            "${
                                (++i).toString().padStart(
                                    length = floor(log10(srcContext.tokens.size.toDouble())).toInt() + 1,
                                    padChar = ' '
                                )
                            }: $tk"
                        })
                        Diagnostics.Logging.info("Kira", "Dumped lexer tokens to ${lexerTokensDumpFile.absolutePath}")
                    }
                    KiraParser(srcContext).parse()
                    // this code is correct, do not erase. commented out for testing the parser only
//                    val semanticAnalyzer = KiraSemanticAnalyzer(compilationUnit)
//                    val semanticAnalyzerResults = semanticAnalyzer.validateAST()
//                    if(semanticAnalyzerResults.diagnostics.isNotEmpty())
//                    {
//                        repeat(semanticAnalyzerResults.diagnostics.size) {
//                            Diagnostics.Logging.warn(
//                                "Kira",
//                                "\n-- Diagnostic Report #${it + 1} ${
//                                    Diagnostics.recordDiagnostics(
//                                        semanticAnalyzerResults.diagnostics[it]
//                                    )
//                                }"
//                            )
//                        }
//                        Diagnostics.Logging.info(
//                            "Kira",
//                            "** Found ${semanticAnalyzerResults.diagnostics.size} issues. See the diagnostic${
//                                LocaleUtils.getPluralSuffix(
//                                    semanticAnalyzerResults.diagnostics.size
//                                )
//                            } above."
//                        )
//                    }
                }
                Diagnostics.Logging.info("Kira", "Compiled ${file.name} in $duration")
                if(it.dumpAST != null)
                {
                    val astDumpFile = File("${it.dumpAST}.xml")
                    astDumpFile.createNewFile()
                    astDumpFile.writeText(XMLASTVisitor.build(srcContext.ast))
                    val astNodeCanonLocations = File("${it.dumpAST}.txt")
                    astNodeCanonLocations.createNewFile()
                    val sb = StringBuilder()
                    var sourceFileCounter = 1
                    sb.appendLine("Total Sources: ${compilationUnit.getSourcesLength()}")
                    compilationUnit.allSources().forEach {
                        sb.appendLine("====== SourceFile [${sourceFileCounter++}]: ${it.file} ======")
                        it.astOrigins.entries.sortedBy { entry -> entry.value }.forEach { element ->
                            sb.appendLine("    ${element.value.lineNumber}, ${element.value.column} : ${element.key}")
                        }
                    }
                    astNodeCanonLocations.writeText(sb.toString())
                    Diagnostics.Logging.info(
                        "Kira",
                        "Dumped AST representation to ${astDumpFile.absolutePath}. Dumped node source locations to ${astNodeCanonLocations.absolutePath}"
                    )
                }
                when(GeneratedProvider.outputMode)
                {
                    else -> Diagnostics.Logging.info("Kira", "No output...")
                }
            }
        }
    }
    Diagnostics.Logging.info("Kira", "Everything took $duration")
}

fun parseArgs(): ArgumentOptions
{
    parsePublicFlags()
    val useDiagnostics = argsParser.findOption("--diagnostics", "false")!!.equals("true", true)
    val srcLocOption = argsParser.findOption("--src")
    if(srcLocOption == null)
    {
        Diagnostics.panic("Could not find the 'src' option pointing to a source file.\nUsage: '--src=main.kira'")
    }
    val dumpLexerTokensOption = argsParser.findOption("--dumpLexerTokens")
    val dumpASTOptionOption = argsParser.findOption("--dumpAST")
    // ephemeral options
    val outputFileOption = argsParser.findOption("--out")
    if(outputFileOption == null)
    {
        GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NONE
    }
    else
    {
        val outputModeOption = argsParser.findOption("--target")
        when(outputModeOption?.lowercase())
        {
            "neko" ->
            {
                GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NEKO
                GeneratedProvider.outputFile = outputFileOption
            }
            else   -> GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NONE
        }
    }
    return ArgumentOptions(
        useDiagnostics,
        srcLocOption.split(",").toTypedArray(),
        dumpLexerTokensOption,
        dumpASTOptionOption
    )
}

fun parsePublicFlags()
{
    // the flipping and unflipping of the conditions just sometimes gets me mixed up for some reason lol
    //
    // is my brain too slow ?
    Public.Flags.useDiagnosticsUnicode = !argsParser.findFlag("-noPrettyDiagnostics")
    Public.Flags.beVerbose = argsParser.findFlag("-verbose")
    Public.Flags.enableVisualView = argsParser.findFlag("-visualView")
}