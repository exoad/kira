package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

/**
 * `kira.math as math`: the module [uri] (dotted, as written; or a URI) imported, its pub names
 * seen here and [alias] naming it in a qualified call. A path one past a module names one of
 * its members: `a.b.Decoder as Decoder`.
 */
open class UseStatement(val uri: StringLiteral, val alias: Identifier? = null) : Statement(uri) {
    /** The URI of the module [uri] names, once every file is read (UnitLink); null before, or for none. */
    var resolvedUri: String? = null

    /** For a member import, the member's name; null when the whole module is imported. */
    var member: String? = null

    /** The module's URI, or the path as written while it names none. */
    val moduleUri: String get() = resolvedUri ?: uri.value

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitUseStatement(this)
    }

    override fun toString(): String {
        return "UseStatement(uri=$uri${if (alias != null) ", alias=$alias" else ""})"
    }
}