package net.exoad.kira.source

/**
 * Represents not just a position, but also the containing source file
 */
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
        // allow sentinel (-1) and baked-in (0) positions; only assert against excessively negative values
        assert(lineNumber >= -1) { "Line Number must be >= -1 (BAD: $lineNumber)" }
        assert(column >= -1) { "Column Number must be >= -1 (BAD: $column)" }
    }

    fun toPosition(): SourcePosition {
        return SourcePosition(lineNumber, column)
    }

    override fun toString(): String {
        return "[$srcFile] : line $lineNumber, col $column"
    }
}