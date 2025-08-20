package net.exoad.kira.compiler

data class FileLocation(val lineNumber: Int, val column: Int)
{
    companion object
    {
        val UNKNOWN = FileLocation(-1, -1)
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