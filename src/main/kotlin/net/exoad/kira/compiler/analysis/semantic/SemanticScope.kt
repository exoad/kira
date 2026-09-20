package net.exoad.kira.compiler.analysis.semantic


sealed class SemanticScope(open val name: String) {
    val symbols: Map<String, SemanticSymbol> = mutableMapOf()

    object Global : SemanticScope("global")

    data class Module(override val name: String) : SemanticScope(name)

    data class Class(override val name: String) : SemanticScope(name)

    data class Function(override val name: String) : SemanticScope(name)

    data class Enum(override val name: String) : SemanticScope(name)

    data class Trait(override val name: String) : SemanticScope(name)

    data class Variant(override val name: String) : SemanticScope(name)

    data class VariantVariance(override val name: String) : SemanticScope(name)

    data class VariantMember(override val name: String) : SemanticScope(name)
}
