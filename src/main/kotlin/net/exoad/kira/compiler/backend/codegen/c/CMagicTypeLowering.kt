package net.exoad.kira.compiler.backend.codegen.c

data class CMagicTypeBinding(
    val cType: String,
    val requiredIncludes: Set<String> = emptySet()
)

object CMagicTypeLowering {
    private val cMappings = mapOf(
        "Int8" to CMagicTypeBinding("int8_t", setOf("stdint.h")),
        "Int16" to CMagicTypeBinding("int16_t", setOf("stdint.h")),
        "Int32" to CMagicTypeBinding("int32_t", setOf("stdint.h")),
        "Int64" to CMagicTypeBinding("int64_t", setOf("stdint.h")),
        "Float32" to CMagicTypeBinding("float"),
        "Float64" to CMagicTypeBinding("double"),
        "Bool" to CMagicTypeBinding("bool", setOf("stdbool.h")),
        "Void" to CMagicTypeBinding("void"),
        "Never" to CMagicTypeBinding("void"),
        "Str" to CMagicTypeBinding("const char*"),
        "String" to CMagicTypeBinding("const char*"),
        "Any" to CMagicTypeBinding("void*"),
    )

    fun resolve(typeName: String): CMagicTypeBinding? {
        return cMappings[typeName]
    }
}
