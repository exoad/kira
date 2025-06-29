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
                arg.startsWith("--") && arg.contains("=") ->
                {
                    val parts = arg.split("=", limit = 2)
                    options[parts[0]] = parts[1]
                }
                arg.startsWith("--")                      ->
                {
                    if(i + 1 >= args.size || args[i + 1].startsWith("-"))
                    {
                        flags.add(arg)
                    }
                    else
                    {
                        options[arg] = args[i + 1]
                        i++
                    }
                }
                arg.startsWith("-") && arg.length > 1     ->
                {
                    if(i + 1 >= args.size || args[i + 1].startsWith("-"))
                    {
                        flags.add(arg)
                    }
                    else
                    {
                        options[arg] = args[i + 1]
                        i++
                    }
                }
                else                                      ->
                {
                    positionalArgs.add(arg)
                }
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

    fun findPositionalArg(): List<String>
    {
        return positionalArgs.toList()
    }
}