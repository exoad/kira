package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.io.File

enum class Severity { ERROR, WARNING, NOTE }

/**
 * One finding of the typer or a rule pass.
 *
 * [code] is stable and machine-checked by tests: `types.<area>.<case>` for the typer
 * (`types.type.unknown`, `types.alias.cycle`, ...), `rules.<pass>.<case>` for the rule passes.
 * [source] and [position] locate [node] when it came from a parsed file.
 */
class TypeDiagnostic(
    val code: String,
    val message: String,
    val node: ASTNode?,
    val severity: Severity,
    val source: SourceContext? = null,
    val position: SourcePosition? = null,
) {
    val isError: Boolean get() = severity == Severity.ERROR

    /** `main.kira:3:5: error [types.type.unknown] ...`; [file] overrides the file label. */
    fun render(file: String? = null): String {
        val label = file ?: source?.file?.let { File(it).name } ?: "<unknown>"
        val where = if (position != null && position.lineNumber >= 0) "$label:${position.lineNumber}:${position.column}" else label
        return "$where: ${severity.name.lowercase()} [$code] $message"
    }

    override fun toString(): String = render()
}
