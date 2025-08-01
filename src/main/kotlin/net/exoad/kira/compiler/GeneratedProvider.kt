package net.exoad.kira.compiler

/**
 * Holds the information on the information necessary to output the final generated output format.
 *
 * Things like the compilation format with [OutputTarget]
 */
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