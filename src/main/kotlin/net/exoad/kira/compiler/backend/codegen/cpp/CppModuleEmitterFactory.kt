package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.source.SourceContext

/**
 * Where [KiraCppBackend] gets its [CppModuleEmitter].
 *
 * This is the plumbing default: an emitter that refuses every module with
 * `cpp.unsupported`, so `kira --target cpp` exits 1 and writes nothing until
 * the real emitter (the typer plus the declaration, expression, class and
 * extern parts) replaces this file.
 */
object CppModuleEmitterFactory {
    const val UNSUPPORTED_CODE = "cpp.unsupported"
    const val UNSUPPORTED_MESSAGE = "the C++ emitter is not built yet"

    @Suppress("UNUSED_PARAMETER")
    fun create(unit: CompilationUnit, options: CppOptions): CppModuleEmitter {
        return UnsupportedCppModuleEmitter
    }

    private object UnsupportedCppModuleEmitter : CppModuleEmitter {
        override fun emit(source: SourceContext): EmittedModule {
            return EmittedModule(
                header = "",
                source = null,
                diagnostics = listOf(
                    CppDiagnostic(UNSUPPORTED_CODE, UNSUPPORTED_MESSAGE, CppSeverity.ERROR, source.file)
                ),
            )
        }
    }
}
