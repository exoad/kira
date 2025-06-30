package net.exoad.kira

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.*
import net.exoad.kira.utils.ASTPrettyPrinterVisitor
import net.exoad.kira.utils.ArgsParser
import java.io.File
import kotlin.system.exitProcess
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
            if(it.useDiagnostics)
            {
                Diagnostics.useDiagnostics()
            }
            else
            {
                Diagnostics.silenceDiagnostics()
            }
            for(sourceFile in it.src)
            {
                val file = File(sourceFile)
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
                    Diagnostics.Logging.info("Kira::main", "Dumped lexer tokens to ${lexerTokensDumpFile.absolutePath}")
                }
                KiraParser.parseProgram()
                if(it.dumpAST != null)
                {
                    val astDumpFile = File(it.dumpAST)
                    astDumpFile.createNewFile()
                    astDumpFile.writeText(ASTPrettyPrinterVisitor.build(TokensProvider.rootASTNode))
                    Diagnostics.Logging.info("Kira::main", "Dumped AST representation to ${astDumpFile.absolutePath}")
                }
                else
                {
                    Diagnostics.Logging.ohNo("Kira::main", "Not finished!")
                    exitProcess(0)
                }
            }
        }
    }
    Diagnostics.Logging.info("Kira::main", "Completed in $duration")
}

fun parseArgs(): ArgsOptions
{
    val useDiagnostics = argsParser.findOption("--diagnostics", "false")!!.equals("true", true)
    val srcLocOption = argsParser.findOption("--src")
    if(srcLocOption == null)
    {
        Diagnostics.panic("Could not find the 'src' option pointing to a source file.\nUsage: '--src=main.kira'")
    }
    val dumpLexerTokensOption = argsParser.findOption("--dumpLexerTokens")
    val dumpASTOptionOption = argsParser.findOption("--dumpAST")
    parsePublicFlags()
    return ArgsOptions(useDiagnostics, srcLocOption.split(","), dumpLexerTokensOption, dumpASTOptionOption)
}

fun parsePublicFlags()
{
    Public.Flags.useDiagnosticsUnicode = !argsParser.findFlag("--noPrettyDiagnostics")
}

fun printAST(ast: RootASTNode)
{
    Diagnostics.Logging.info("Main", "\n${ASTPrettyPrinterVisitor.build(ast)}")
}
