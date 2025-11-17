package net.exoad.kira.compiler.analysis.semantic


sealed class SemanticScope(open val name: String) {
    val symbols: Map<String, SemanticSymbol> = mutableMapOf()

    object Global : SemanticScope("global")

    data class Module(override val name: String) : SemanticScope(name)

    data class Class(override val name: String) : SemanticScope(name)

    data class Function(override val name: String) : SemanticScope(name)

    data class Enum(override val name: String) : SemanticScope(name)

    data class Variant(override val name: String) : SemanticScope(name)
}

//enum class SemanticScope {
//    MODULE,
//    CLASS,
//    GLOBAL,
//
//    @ObsoleteLanguageFeat
//    NAMESPACE,
//    ENUM,
//    FUNCTION,
//}
