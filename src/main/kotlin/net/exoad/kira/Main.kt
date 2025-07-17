package net.exoad.kira

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTAtomOneDarkIJTheme
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.GeneratedProvider
import net.exoad.kira.compiler.SourceContext
import net.exoad.kira.compiler.front.*
import net.exoad.kira.compiler.front.KiraPreprocessor
import net.exoad.kira.ui.KiraVisualViewer
import net.exoad.kira.utils.ArgsParser
import net.exoad.kira.utils.XMLASTVisitor
import java.io.File
import javax.swing.UIManager
import kotlin.math.floor
import kotlin.math.log10
import kotlin.properties.Delegates
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
    var srcContext by Delegates.notNull<SourceContext>()
    val (_, duration) = measureTimedValue { // ignore the first parameter because this a void or unit block so the result is not important!
        argsParser = ArgsParser(args)
        parseArgs().let { it ->
            when(it.useDiagnostics)
            {
                // the thing with this is that, we cannot log anything in functions before this happens, since the flag by default off
                //
                // the only thing we can do is panic!! silently panic LOL
                true -> Diagnostics.useDiagnostics()
                else -> Diagnostics.silenceDiagnostics()
            }
            for(sourceFile in it.src)
            {
                val file = File(sourceFile)
                val preprocessor = KiraPreprocessor(file.readText())
                val preprocessingResult = preprocessor.process()
                srcContext = SourceContext(
                    preprocessingResult.processedContent,
                    file.canonicalPath,
                    emptyList(),
                )
                val lexer = KiraLexer(srcContext)
                srcContext = srcContext.with(srcContext.content, lexer.tokenize())
                if(Public.Flags.enableVisualView)
                {
                    KiraVisualViewer(srcContext).also {
                        it.run()
                    }
                }
                if(it.dumpLexerTokens != null)
                {
                    val lexerTokensDumpFile = File(it.dumpLexerTokens)
                    lexerTokensDumpFile.createNewFile()
                    var i = 0
                    lexerTokensDumpFile.writeText(srcContext.tokens.joinToString("\n") { tk ->
                        "${
                            (++i).toString()
                                .padStart(
                                    floor(log10(srcContext.tokens.size.toDouble())).toInt() + 1,
                                    ' '
                                ) // yikes, this math is for padding the left side of the token number being parsed to make sure that the tokens are never pushed out of alignment in this column form
                            // basically it figures out the length of the number without using loops
                        }: $tk"
                    })
                    Diagnostics.Logging.info("Kira", "Dumped lexer tokens to ${lexerTokensDumpFile.absolutePath}")
                }
                val parser = KiraParser(srcContext)
                parser.parse()
                if(it.dumpAST != null)
                {
                    val astDumpFile = File(it.dumpAST)
                    astDumpFile.createNewFile()
                    astDumpFile.writeText(XMLASTVisitor.build(srcContext.ast))
                    Diagnostics.Logging.info("Kira", "Dumped AST representation to ${astDumpFile.absolutePath}")
                }
                val semanticAnalyzer = KiraSemanticAnalyzer(srcContext)
                val semanticAnalyzerResults = semanticAnalyzer.validateAST()
                Diagnostics.Logging.warn(
                    "Kira",
                    if(semanticAnalyzerResults.isHealthy) "Source Health OK" else "There are problems in your code. Refer to the diagnostics below."
                )
                if(semanticAnalyzerResults.diagnostics.isNotEmpty())
                {
                    repeat(semanticAnalyzerResults.diagnostics.size)
                    {
                        Diagnostics.Logging.warn(
                            "Kira",
                            "\n-- Pumped Diagnostic #${it + 1}${Diagnostics.recordDiagnostics(semanticAnalyzerResults.diagnostics[it])}"
                        )
                    }
                }
                when(GeneratedProvider.outputMode)
                {
//                    GeneratedProvider.OutputTarget.NEKO -> `KiraNekoTranspiler.txt`.transpile()
                    else -> Diagnostics.Logging.info("Kira", "No output...")
                }
            }
        }
    }

    // todo: should this be some kind of "finer" message or should we leave it everytime for the user to see?
    Diagnostics.Logging.info("Kira", "Completed in $duration")
}

fun parseArgs(): ArgsOptions
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
    return ArgsOptions(useDiagnostics, srcLocOption.split(","), dumpLexerTokensOption, dumpASTOptionOption)
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