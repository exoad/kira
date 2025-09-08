package net.exoad.kira.core

enum class BuiltinIntrinsics(val rep: String, val canMark: Boolean = true) {
    TRACE("trace", false),
    MAGIC("magic"),
    CMP_GT("cmp_gt"),
    CMP_LT("cmp_lt"),
    CMP_GE("cmp_ge"),
    CMP_LE("cmp_le"),
    CMP_EQ("cmp_eq"),
    CMP_NE("cmp_ne"),
    OP_ADD("op_add"),
    OP_SUB("op_sub"),
    OP_MUL("op_mul"),
    OP_DIV("op_div"),
    OP_MOD("op_mod"),
    OP_SHR("op_shr"),
    OP_SHL("op_shl"),
    OP_BIT_AND("op_bit_and"),
    OP_AND("op_and"),
    OP_OR("op_or"),
    OP_BIT_OR("op_bit_or"),
    OP_XOR("op_xor"),
}
