package net.exoad.kira

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.KiraLexer
import net.exoad.kira.compiler.frontend.KiraParser
import net.exoad.kira.compiler.frontend.KiraPreprocessor
import net.exoad.kira.utils.ASTPrettyPrinterVisitor
import java.io.File

fun main()
{
    Diagnostics.Logging.info("Main", "Hello World!")
    val inputFile = File("meta/test.kira")
    val preprocessedFileContents = KiraPreprocessor.stripComments(inputFile)
    val lexer = KiraLexer(preprocessedFileContents)
    Diagnostics.Logging.info("Main", "=====================[ Preprocessor ]=====================")
    Diagnostics.Logging.info("Main", preprocessedFileContents)
    val tokens = lexer.tokenize()
    var lineIndex = 0
    Diagnostics.Logging.info("Main", "=====================[ Lexer ]=====================")
    for(token in tokens)
    {
        Diagnostics.Logging.info("Main", "${++lineIndex}: $token")
    }
    Diagnostics.Logging.info("Main", "Total Tokens: ${tokens.size}")
    Diagnostics.Logging.info("Main", "=====================[ Parser ]=====================")
    val parser = KiraParser(tokens)
    val ast = parser.parseProgram()
    Diagnostics.Logging.info("Main", "\n${ASTPrettyPrinterVisitor.build(ast)}")
}