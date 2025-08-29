package net.exoad.kira.source

open class SourceLocation(val lineNumber: Int, val column: Int, val srcFile: String) {
    companion object {
        fun bakedIn(): SourceLocation {
            return object : SourceLocation(0, 0, "builtin") {
                override fun toString(): String {
                    return "kira:builtin"
                }
            }
        }

        fun fromPosition(location: SourcePosition, file: String): SourceLocation {
            return SourceLocation(location.lineNumber, location.column, file)
        }
    }

    init {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    fun toPosition(): SourcePosition {
        return SourcePosition(lineNumber, column)
    }

    override fun toString(): String {
        return "[$srcFile] : line $lineNumber, col $column"
    }
}