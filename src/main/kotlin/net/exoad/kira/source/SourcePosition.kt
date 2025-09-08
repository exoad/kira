package net.exoad.kira.source

data class SourcePosition(val lineNumber: Int, val column: Int) : Comparable<SourcePosition> {
    companion object {
        val UNKNOWN = SourcePosition(-1, -1)
    }

    init {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    override fun toString(): String {
        return "line $lineNumber, col $column"
    }

    fun offsetBy(lineOffset: Int, columnOffset: Int): SourcePosition {
        return SourcePosition(lineNumber + lineOffset, column + columnOffset)
    }

    override fun compareTo(other: SourcePosition): Int {
        val lineCompare = lineNumber.compareTo(other.lineNumber)
        return if (lineCompare != 0) lineCompare else column.compareTo(other.column)
    }
}