package net.exoad.kira.utils

class ArgsParser(private val args: Array<String>)
{
    private val options = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()
    private val positionalArgs = mutableListOf<String>()
    // public viewer getters
    val viewOptions get() = options.toMap()
    val viewFlags get() = flags.toSet()
    val viewPositionalArgs get() = positionalArgs.toList()

    init
    {
        parseArgs()
    }

    private fun parseArgs()
    {
        var i = 0
        while(i < args.size)
        {
            val arg = args[i]
            when
            {
                arg.startsWith("--") && arg.contains("=") -> arg.split("=", limit = 2).let { options[it[0]] = it[1] }
                arg.startsWith("--")                      -> flags.add(arg)
                arg.startsWith("-") && arg.length > 1     -> flags.add(arg)
                else                                      -> positionalArgs.add(arg)
            }
            i++
        }
    }

    fun findFlag(flag: String): Boolean
    {
        return flags.contains(flag)
    }

    fun findOption(option: String, defaultValue: String? = null): String?
    {
        return options[option] ?: defaultValue
    }
}