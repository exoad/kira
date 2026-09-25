package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition

enum class CppSeverity {
    ERROR,
    WARNING,
}

/**
 * One thing the C++ backend has to say about a module. [code] is the stable
 * machine-readable name (`cpp.unsupported`, `cpp.macro-name`, ...), [message]
 * the sentence for a person.
 */
data class CppDiagnostic(
    val code: String,
    val message: String,
    val severity: CppSeverity = CppSeverity.ERROR,
    val file: String? = null,
    val position: SourcePosition? = null,
) {
    val isError: Boolean
        get() = severity == CppSeverity.ERROR

    /** `file:line:col: error: code: message`, the shape editors parse. */
    fun render(): String {
        val where = buildString {
            if (file != null) {
                append(file)
                if (position != null && position.lineNumber >= 0) {
                    append(':').append(position.lineNumber)
                    if (position.column >= 0) {
                        append(':').append(position.column)
                    }
                }
                append(": ")
            }
        }
        val level = if (isError) "error" else "warning"
        return "$where$level: $code: $message"
    }
}

/**
 * What one Kira module becomes: a header, an optional source (a header-only
 * module has none), and whatever the emitter had to say. When [diagnostics]
 * holds an error the texts are not written.
 */
data class EmittedModule(
    val header: String,
    val source: String?,
    val diagnostics: List<CppDiagnostic> = emptyList(),
) {
    val hasErrors: Boolean
        get() = diagnostics.any { it.isError }
}

/**
 * Turns one parsed module into C++ text. [KiraCppBackend] asks
 * [CppModuleEmitterFactory] for one per run and calls [emit] once per
 * workspace and Kira-written stdlib module.
 */
interface CppModuleEmitter {
    /**
     * Diagnostics that belong to the run rather than to one module, for
     * example the typer's, reported before any module is emitted.
     */
    val diagnostics: List<CppDiagnostic>
        get() = emptyList()

    fun emit(source: SourceContext): EmittedModule
}
