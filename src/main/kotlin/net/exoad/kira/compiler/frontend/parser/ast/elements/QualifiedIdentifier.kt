package net.exoad.kira.compiler.frontend.parser.ast.elements

/**
 * A type reached through an imported module's alias, `shapes.Impl`: [value] is the type's own
 * name and [qualifier] the alias that names its module.
 */
class QualifiedIdentifier(val qualifier: Identifier, name: String) : Identifier(name) {
    /** As written: `shapes.Impl`. */
    val text: String get() = "${qualifier.value}.$value"

    override fun toString(): String = "'$text'"

    override fun hashCode(): Int = text.hashCode()

    override fun equals(other: Any?): Boolean = other is QualifiedIdentifier && other.text == text
}
