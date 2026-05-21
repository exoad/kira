package net.exoad.kira.compiler.frontend.parser

import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.frontend.parser.antlr.generated.KiraAntlrLexer
import net.exoad.kira.compiler.frontend.parser.antlr.generated.KiraAntlrParser
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.RecognitionException

private data class SyntaxIssue(val line: Int, val column: Int, val message: String)

class AntlrKiraSourceParser(private val context: SourceContext) : KiraSourceParser {
    override fun parse() {
        val issues = mutableListOf<SyntaxIssue>()
        val errorListener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?
            ) {
                if (issues.size < 8) {
                    issues.add(SyntaxIssue(line, charPositionInLine + 1, msg))
                }
            }
        }

        val lexer = KiraAntlrLexer(CharStreams.fromString(context.content, context.file))
        val tokenStream = CommonTokenStream(lexer)
        val parser = KiraAntlrParser(tokenStream)

        lexer.removeErrorListeners()
        parser.removeErrorListeners()
        lexer.addErrorListener(errorListener)
        parser.addErrorListener(errorListener)

        parser.program()

        if (issues.isNotEmpty()) {
            val first = issues.first()
            Diagnostics.panic(
                "AntlrKiraSourceParser::parse",
                buildString {
                    appendLine("ANTLR parser found syntax issues:")
                    issues.forEachIndexed { idx, issue ->
                        appendLine("${idx + 1}. line ${issue.line}, col ${issue.column}: ${issue.message}")
                    }
                },
                context = context,
                location = SourcePosition(first.line, first.column),
                selectorLength = 1
            )
        }

        // Keep legacy AST contracts stable while ANTLR grammar handles syntax validation.
        KiraParser(context).parse()
    }
}
