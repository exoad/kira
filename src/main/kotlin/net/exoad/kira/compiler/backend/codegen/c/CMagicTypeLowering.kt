package net.exoad.kira.compiler.backend.codegen.c

data class CMagicTypeBinding(
    val cType: String,
    val requiredIncludes: Set<String> = emptySet()
)

/**
 * Maps Kira magic / builtin type names onto the Jack-style C runtime types
 * defined in the embedded prelude (`c_generator.c`).
 */
object CMagicTypeLowering {
    private val cMappings = mapOf(
        "Int8" to CMagicTypeBinding("Int8"),
        "Int16" to CMagicTypeBinding("Int16"),
        "Int32" to CMagicTypeBinding("Int32"),
        "Int64" to CMagicTypeBinding("Int64"),
        "Int" to CMagicTypeBinding("Int32"),
        "Float32" to CMagicTypeBinding("Float32"),
        "Float64" to CMagicTypeBinding("Float64"),
        "Float" to CMagicTypeBinding("Float32"),
        "Bool" to CMagicTypeBinding("Bool"),
        "Void" to CMagicTypeBinding("Void"),
        "Never" to CMagicTypeBinding("Void"),
        "Str" to CMagicTypeBinding("Str"),
        "String" to CMagicTypeBinding("Str"),
        "Any" to CMagicTypeBinding("Any"),
        // Baseline collections -- generics erased; bodies live in c_generator.c
        "Arr" to CMagicTypeBinding("Arr"),
        "List" to CMagicTypeBinding("List"),
        "Map" to CMagicTypeBinding("Map"),
        "Set" to CMagicTypeBinding("Map"), // count-bag stand-in until Set lands
    )

    fun resolve(typeName: String): CMagicTypeBinding? {
        return cMappings[typeName]
    }
}
