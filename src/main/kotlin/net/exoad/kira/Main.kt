package net.exoad.kira

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.GeneratedProvider
import net.exoad.kira.compiler.back.KiraNekoTranspiler
import net.exoad.kira.compiler.front.*
import net.exoad.kira.compiler.preprocessor.KiraPreprocessor
import net.exoad.kira.utils.ArgsParser
import net.exoad.kira.utils.XMLASTVisitor
import java.io.File
import kotlin.time.measureTimedValue

internal lateinit var argsParser: ArgsParser

/**
 * This can be called from any other programs, but for the most part, the required parameter
 * is the `--src` option which points to the source file you want to compile. For example, `--src=hello.kira`
 */
fun main(args: Array<String>)
{
    val (_, duration) = measureTimedValue {
        argsParser = ArgsParser(args)
        parseArgs().let { it ->
            when(it.useDiagnostics)
            {
                true -> Diagnostics.useDiagnostics()
                else -> Diagnostics.silenceDiagnostics()
            }
            for(sourceFile in it.src)
            {
                val file = File(sourceFile)
                SrcProvider.srcFile = file.canonicalPath
                SrcProvider.srcContent = file.readText()
                KiraPreprocessor.process()
                KiraLexer.tokenize()
                if(it.dumpLexerTokens != null)
                {
                    val lexerTokensDumpFile = File(it.dumpLexerTokens)
                    lexerTokensDumpFile.createNewFile()
                    var i = 0
                    lexerTokensDumpFile.writeText(TokensProvider.tokens.joinToString("\n") { tk ->
                        "${++i}: $tk"
                    })
                    Diagnostics.Logging.info("Kira", "Dumped lexer tokens to ${lexerTokensDumpFile.absolutePath}")
                }
                KiraParser.parseProgram()
                if(it.dumpAST != null)
                {
                    val astDumpFile = File(it.dumpAST)
                    astDumpFile.createNewFile()
                    astDumpFile.writeText(XMLASTVisitor.build(TokensProvider.rootASTNode))
                    Diagnostics.Logging.info("Kira", "Dumped AST representation to ${astDumpFile.absolutePath}")
                }
                when(GeneratedProvider.outputMode)
                {
                    GeneratedProvider.OutputTarget.NEKO -> KiraNekoTranspiler.transpile()
                    GeneratedProvider.OutputTarget.NONE -> Diagnostics.Logging.info("Kira", "No output...")
                }
            }
        }
    }
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
    Public.Flags.useDiagnosticsUnicode = !argsParser.findFlag("--noPrettyDiagnostics")
    Public.Flags.beVerbose = !argsParser.findFlag("--verbose")
}