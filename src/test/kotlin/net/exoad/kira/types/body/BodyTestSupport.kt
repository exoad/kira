package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import kotlin.test.fail

/** Finding nodes of a typed program by their source text, for the model tests. */
object BodyTestSupport {
    /** Every node of class [T] in [uri]'s module whose unparsed text is [text], in source order. */
    inline fun <reified T : ASTNode> all(program: TypedProgram, text: String, uri: String = "test:main"): List<T> {
        val m = program.module(uri) ?: fail("no module $uri")
        val out = mutableListOf<T>()
        AstTree.walk(m.source.ast) { n -> if (n is T && KiraUnparser.text(n) == text) out.add(n) }
        return out
    }

    /** The first such node; fails when there is none. */
    inline fun <reified T : ASTNode> node(program: TypedProgram, text: String, uri: String = "test:main"): T =
        all<T>(program, text, uri).firstOrNull() ?: fail("no ${T::class.simpleName} '$text' in $uri")

    /** Every node of class [T] in [uri]'s module. */
    inline fun <reified T : ASTNode> every(program: TypedProgram, uri: String = "test:main"): List<T> {
        val m = program.module(uri) ?: fail("no module $uri")
        val out = mutableListOf<T>()
        AstTree.walk(m.source.ast) { n -> if (n is T) out.add(n) }
        return out
    }
}
