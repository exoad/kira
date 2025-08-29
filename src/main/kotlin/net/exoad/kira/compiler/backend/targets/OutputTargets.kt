package net.exoad.kira.compiler.backend.targets

enum class OutputTargets(val canonicalName: String) {
    VALIDATE("validate"),
    C_99("c"),
    NEKO_VM("nekovm")
}