package net.exoad.kira.compiler.backend.targets

enum class OutputTargets(val canonicalName: String) {
    VALIDATE("validate"),
    C_99("c"),
    JS("js"),
    NEKO_VM("nekovm")
}