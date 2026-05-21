package net.exoad.kira.compiler.frontend.parser

import net.exoad.kira.source.SourceContext

class LegacyKiraSourceParser(private val context: SourceContext) : KiraSourceParser {
    override fun parse() {
        KiraParser(context).parse()
    }
}
