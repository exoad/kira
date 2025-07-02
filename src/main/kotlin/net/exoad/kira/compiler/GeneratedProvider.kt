package net.exoad.kira.compiler

object GeneratedProvider
{
    enum class OutputTarget
    {
        /**
         * See [net.exoad.kira.compiler]
         */
        NEKO,
        NONE
    }

    /**
     * Represents where a generated output should go to
     */
    lateinit var outputFile: String
    var outputMode: OutputTarget = OutputTarget.NONE
}