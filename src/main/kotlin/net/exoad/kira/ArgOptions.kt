package net.exoad.kira

enum class ArgsCompileStep
{
    PREPROCESS,
    LEX,
    PARSE,
    ALL;

    companion object
    {
        fun of(string: String): ArgsCompileStep
        {
            for(step in entries)
            {
                if(step.name.equals(string, true))
                {
                    return step
                }
            }
            return ALL
        }
    }
}

data class ArgsOptions(val useDiagnostics: Boolean, val stepOnly: ArgsCompileStep, val src: List<String>)
{
    override fun toString(): String
    {
        return "ArgsOptions{ UseDiagnostics: $useDiagnostics, StepOnly: $stepOnly, Src: $src }"
    }
}
