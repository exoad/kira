package net.exoad.kira.compiler

open class AbsoluteFileLocation(val lineNumber: Int, val column: Int, val srcFile: String)
{
    companion object
    {
        fun bakedIn(): AbsoluteFileLocation
        {
            return object : AbsoluteFileLocation(0, 0, "builtin")
            {
                override fun toString(): String
                {
                    return "kira:builtin"
                }
            }
        }

        fun fromRelative(location: FileLocation, file: String): AbsoluteFileLocation
        {
            return AbsoluteFileLocation(location.lineNumber, location.column, file)
        }
    }

    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    fun toRelative(): FileLocation
    {
        return FileLocation(lineNumber, column)
    }

    override fun toString(): String
    {
        return "[$srcFile] : line $lineNumber, col $column"
    }
}