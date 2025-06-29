package net.exoad.kira

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.KiraLexer
import net.exoad.kira.compiler.frontend.KiraParser
import net.exoad.kira.compiler.frontend.KiraPreprocessor
import net.exoad.kira.compiler.frontend.RootASTNode
import net.exoad.kira.compiler.frontend.Token
import net.exoad.kira.utils.ASTPrettyPrinterVisitor
import net.exoad.kira.utils.ArgsParser
import java.io.File

internal lateinit var argsParser: ArgsParser

/**
 * This can be called from any other programs, but for the most part, the required parameter
 * is the `--src` option which points to the source file you want to compile. For example, `--src=hello.kira`
 */
fun main(args: Array<String>)
{
    argsParser = ArgsParser(args)
    parseArgs().let {
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
            when(it.stepOnly)
            {
                ArgsCompileStep.ALL -> printAST(parse(lex(preprocess(file))))
                else -> Diagnostics.panic("NOT SUPPORTED!")
            }
        }
    }
    Diagnostics.Logging.info("Main", "Hello, World!")
}

fun parseArgs(): ArgsOptions
{
    val useDiagnostics = argsParser.findOption("--diagnostics", "false")!!.equals("true", true)
    val stepOnly = ArgsCompileStep.of(argsParser.findOption("--stepOnly", "all")!!)
    val srcLocOption = argsParser.findOption("--src")
    if(srcLocOption == null)
    {
        Diagnostics.panic("Could not find the 'src' option pointing to a source file.\nUsage: '--src=main.kira'")
    }
    return ArgsOptions(useDiagnostics, stepOnly, srcLocOption.split(","))
}

fun preprocess(inputFile: File): String
{
    val contents = KiraPreprocessor.process(inputFile.readText())
    Diagnostics.Logging.info("Main", "=====================[ Preprocessor ]=====================")
    Diagnostics.Logging.info("Main", contents)
    return contents
}

fun lex(source: String): List<Token>
{
    val lexer = KiraLexer(source)
    val tokens = lexer.tokenize()
    Diagnostics.Logging.info("Main", "=====================[ Lexer ]=====================")
    tokens.forEachIndexed { index, token ->
        Diagnostics.Logging.info("Main", "${index + 1}: $token")
    }
    Diagnostics.Logging.info("Main", "Total Tokens: ${tokens.size}")
    return tokens
}

fun parse(tokens: List<Token>): RootASTNode
{
    Diagnostics.Logging.info("Main", "=====================[ Parser ]=====================")
    val parser = KiraParser(tokens)
    return parser.parseProgram()
}

fun printAST(ast: RootASTNode)
{
    Diagnostics.Logging.info("Main", "\n${ASTPrettyPrinterVisitor.build(ast)}")
}
