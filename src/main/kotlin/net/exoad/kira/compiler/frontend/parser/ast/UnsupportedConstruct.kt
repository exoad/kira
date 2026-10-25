package net.exoad.kira.compiler.frontend.parser.ast

/**
 * Thrown by a [KiraASTVisitor] that meets a node it has no lowering for.
 *
 * Every new syntax form is a new node class, so an older backend dispatches
 * to the throwing default `visitX` and can never lower the form as something
 * else. The visitor default does not know which backend it is, so [target]
 * is null there; a backend's own guard, or the catch at its entry point,
 * names the target with [withTarget].
 *
 * [construct] is the user-facing name of the form ("struct declaration",
 * "if-expression", ...). It is the one thing a diagnostic must say.
 */
class UnsupportedConstruct(
    val node: ASTNode,
    val construct: String,
    val target: String? = null,
) : RuntimeException(describe(construct, target)) {
    fun withTarget(target: String): UnsupportedConstruct {
        return if (this.target != null) this else UnsupportedConstruct(node, construct, target)
    }

    companion object {
        fun describe(construct: String, target: String?): String {
            return if (target == null) {
                "$construct is not supported by this backend"
            } else {
                "$construct is not supported by the $target backend"
            }
        }
    }
}
