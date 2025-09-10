package net.exoad.kira.cli

data class ArgumentOptions(
    val useDiagnostics: Boolean,
    val src: Array<String>,
    val dump: String?
) {
    override fun toString(): String {
        return "ArgumentOptions{ UseDiagnostics: $useDiagnostics,  Src: $src, Dump: $dump }"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ArgumentOptions
        if (useDiagnostics != other.useDiagnostics) return false
        if (!src.contentEquals(other.src)) return false
        if (dump != other.dump) return false
        return true
    }

    override fun hashCode(): Int {
        var result = useDiagnostics.hashCode()
        result = 31 * result + src.contentHashCode()
        result = 31 * result + (dump?.hashCode() ?: 0)
        return result
    }
}
