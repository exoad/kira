package net.exoad.kira.source

data class SourcePosition(val lineNumber: Int, val column: Int)
{
    companion object
    {
        val UNKNOWN = SourcePosition(-1, -1)
    }

    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    override fun toString(): String
    {
        return "line $lineNumber, col $column"
    }
}