package net.exoad.kira.core

import net.exoad.kira.core.IntrinsicCapability.*


sealed class IntrinsicCapability {
    object Marker : IntrinsicCapability()

    object Functor : IntrinsicCapability()

    object FunctorIdentifier : IntrinsicCapability()

    object ClassFunctionMemberIdentifier : IntrinsicCapability()

    object ClassVariableMemberIdentifier : IntrinsicCapability()

    object TypeIdentifier : IntrinsicCapability()

    object VariableIdentifier : IntrinsicCapability()

    data class BlockBegin(val endName: String) : IntrinsicCapability() {
        init {
            require(endName.startsWith("end", true)) {
                "Intrinsic block enders must start with 'end' (ignoring case)."
            }
        }
    }

    data class BlockEnd(val beginName: String) : IntrinsicCapability() {
        init {
            require(beginName.startsWith("begin", true)) {
                "Intrinsic block beginners must start with 'begin' (ignoring case)."
            }
        }
    }
}

enum class Intrinsic(val rep: String, vararg val capabilities: IntrinsicCapability) {
    TRACE("trace", Functor),
    MAGIC("magic", Marker),
    GLOBAL("global", Marker),
    BEGIN_DEPRECATED("begin_deprecated", BlockBegin("end_deprecated"), Marker),
    END_DEPRECATED("end_deprecated", BlockEnd("begin_deprecated"), Marker),
    CMP_GT("cmp_gt", Marker, ClassFunctionMemberIdentifier),
    CMP_LT("cmp_lt", Marker, ClassFunctionMemberIdentifier),
    CMP_GE("cmp_ge", Marker, ClassFunctionMemberIdentifier),
    CMP_LE("cmp_le", Marker, ClassFunctionMemberIdentifier),
    CMP_EQ("cmp_eq", Marker, ClassFunctionMemberIdentifier),
    CMP_NE("cmp_ne", Marker, ClassFunctionMemberIdentifier),
    OP_ADD("op_add", Marker, ClassFunctionMemberIdentifier),
    OP_SUB("op_sub", Marker, ClassFunctionMemberIdentifier),
    OP_MUL("op_mul", Marker, ClassFunctionMemberIdentifier),
    OP_DIV("op_div", Marker, ClassFunctionMemberIdentifier),
    OP_MOD("op_mod", Marker, ClassFunctionMemberIdentifier),
    OP_SHR("op_shr", Marker, ClassFunctionMemberIdentifier),
    OP_SHL("op_shl", Marker, ClassFunctionMemberIdentifier),
    OP_BIT_AND("op_bit_and", Marker, ClassFunctionMemberIdentifier),
    OP_AND("op_and", Marker, ClassFunctionMemberIdentifier),
    OP_OR("op_or", Marker, ClassFunctionMemberIdentifier),
    OP_BIT_OR("op_bit_or", Marker, ClassFunctionMemberIdentifier),
    OP_XOR("op_xor", Marker, ClassFunctionMemberIdentifier);

    init {
        if (capabilities.any { it is IntrinsicCapability.BlockEnd }) {
            require(capabilities.contains(Marker)) {
                "Intrinsic block ender must be a marker as well."
            }
        } else if (capabilities.any { it is IntrinsicCapability.BlockBegin }) {
            require(capabilities.contains(Marker)) {
                "Intrinsic block beginner must be a marker as well."
            }
        }
    }

}
