package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypeDiagnostic
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.UntypedExpression
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.source.SourceContext
import java.nio.file.Paths

/**
 * Where [KiraCppBackend] gets its [CppModuleEmitter].
 *
 * [create] types the unit STRICT with the manifest's globs (design 3.5: the C++ target always
 * types strictly). A typer error makes an emitter that carries the diagnostics and emits no
 * file; otherwise the real emitter lowers every module through [CppDeclEmitter] and the parts
 * registered in [CppEmitParts.standard].
 */
object CppModuleEmitterFactory {
    const val UNSUPPORTED_CODE = "cpp.unsupported"
    const val INTERNAL_CODE = CppTypeSpeller.INTERNAL_CODE

    fun create(unit: CompilationUnit, options: CppOptions): CppModuleEmitter = create(unit, options, CppEmitParts.standard())

    /** As [create], with the parts stated (a test swaps one for a fake). */
    fun create(unit: CompilationUnit, options: CppOptions, parts: CppEmitParts): CppModuleEmitter {
        val program = KiraTyper.run(unit, TyperMode.STRICT, TyperOptions(options.freestanding, options.headerOnly, options.namespaces))
        val diagnostics = program.diagnostics.map { convert(it) }
        if (program.hasErrors) {
            return FailedEmitter(diagnostics)
        }
        return TypedCppModuleEmitter(program, options, diagnostics, parts)
    }

    /** A typer diagnostic as the backend reports it: `types.*` codes, the typer's severity. */
    fun convert(d: TypeDiagnostic): CppDiagnostic {
        val severity = if (d.severity == Severity.ERROR) CppSeverity.ERROR else CppSeverity.WARNING
        return CppDiagnostic(d.code, d.message, severity, d.source?.file, d.position)
    }

    /** The typer refused the program: its diagnostics are the run's, and no module is emitted. */
    private class FailedEmitter(override val diagnostics: List<CppDiagnostic>) : CppModuleEmitter {
        override fun emit(source: SourceContext): EmittedModule = EmittedModule("", null, emptyList())
    }
}

/**
 * The real emitter: one [CppEmitContextImpl] per module, the declaration emitter over it.
 * [prepare] hands it the layout and the version before the first [emit]; without it the
 * project root is the working directory and the version `dev`.
 */
class TypedCppModuleEmitter(
    val program: TypedProgram,
    private val options: CppOptions,
    override val diagnostics: List<CppDiagnostic>,
    private val parts: CppEmitParts = CppEmitParts.standard(),
) : CppModuleEmitter {
    private var layout: CppModuleLayout = CppModuleLayout(options, Paths.get("."))
    private var version: String = CppCompilerVersion.DEV
    private val usage: CppUsage by lazy { CppUsage.scan(program) }
    private var placements = CppPlacements()

    /** Design 4.3: a duplicate symbol in a shared namespace. Scanned once per layout, on the first emit, over every module. */
    private var collisions: CppNamespaceCollisions? = null

    override fun prepare(layout: CppModuleLayout, version: String) {
        this.layout = layout
        this.version = version
        // A placement reads the layout (header-only, freestanding), so a new layout starts a new set.
        placements = CppPlacements()
        collisions = null
    }

    private fun contextOf(module: ModuleSymbol): CppEmitContextImpl =
        CppEmitContextImpl(program, options, module.source, layout, module, version, parts, placements)

    private fun collisions(): CppNamespaceCollisions = collisions ?: CppNamespaceCollisions.scan(program, ::contextOf).also { collisions = it }

    override fun emit(source: SourceContext): EmittedModule {
        val module: ModuleSymbol = program.moduleOf(source)
            ?: return EmittedModule(
                "", null,
                listOf(CppDiagnostic(CppModuleEmitterFactory.INTERNAL_CODE, "the typer collected no module for ${source.file}", file = source.file)),
            )
        val ctx = contextOf(module)
        return try {
            val emitted = CppDeclEmitter(ctx, usage).emit()
            val duplicates = collisions().of(module)
            if (duplicates.isEmpty()) emitted else emitted.copy(diagnostics = emitted.diagnostics + duplicates)
        } catch (e: UnsupportedConstruct) {
            ctx.unsupported(e.node, e.construct)
            EmittedModule("", null, ctx.diagnostics.toList())
        } catch (e: UntypedExpression) {
            ctx.diag(e.expr, CppModuleEmitterFactory.INTERNAL_CODE, e.message ?: "an expression has no recorded type")
            EmittedModule("", null, ctx.diagnostics.toList())
        }
    }
}
