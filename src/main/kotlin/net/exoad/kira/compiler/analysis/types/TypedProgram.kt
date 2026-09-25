package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.util.IdentityHashMap

/**
 * The typer's result: every module's symbols ([modules], sorted by URI) and the facts about
 * every node ([model]). Diagnostics accumulate in [diagnostics]; [report] is the one way to
 * add one, so the [mode] applies uniformly.
 */
class TypedProgram(
    val unit: CompilationUnit,
    val modules: List<ModuleSymbol>,
    val model: TypedModel,
    val mode: TyperMode,
) {
    var options: TyperOptions = TyperOptions()

    val diagnostics: MutableList<TypeDiagnostic> = mutableListOf()

    /** The builtin names and their magic classes (set by phase A). */
    var builtins: Builtins = Builtins(emptyList())

    /** Import resolution and scope lookups (set by phase A). */
    var graph: ModuleGraph = ModuleGraph(this)

    val errors: List<TypeDiagnostic> get() = diagnostics.filter { it.isError }

    val hasErrors: Boolean get() = diagnostics.any { it.isError }

    fun module(uri: String): ModuleSymbol? = modules.firstOrNull { it.uri == uri }

    fun moduleOf(source: SourceContext): ModuleSymbol? = modules.firstOrNull { it.source === source }

    /** The modules the user wrote: everything but the stdlib (`kira:*`). */
    val workspaceModules: List<ModuleSymbol> get() = modules.filter { !it.isStdlib }

    /**
     * Records a diagnostic at [node]. In [TyperMode.LENIENT] an error is downgraded to a
     * warning (C and JS then build regardless). Returns the recorded diagnostic.
     */
    fun report(code: String, message: String, node: ASTNode?, severity: Severity = Severity.ERROR): TypeDiagnostic {
        val effective = if (mode == TyperMode.LENIENT && severity == Severity.ERROR) Severity.WARNING else severity
        val where = node?.let { locate(it) }
        val diagnostic = TypeDiagnostic(code, message, node, effective, where?.first, where?.second)
        diagnostics.add(diagnostic)
        return diagnostic
    }

    /** The class a type's members come from (a builtin's magic class, or the nominal's own). */
    fun classOf(type: KType): ClassSymbol? = builtins.classOf(type)

    private val sourceByNode: IdentityHashMap<ASTNode, SourceContext> by lazy {
        val map = IdentityHashMap<ASTNode, SourceContext>()
        unit.allSources().forEach { source ->
            val origins = runCatching { source.astOrigins }.getOrNull() ?: return@forEach
            origins.keys.forEach { map[it] = source }
        }
        map
    }

    /**
     * The source file and position of [node]: its own recorded origin, else the earliest
     * origin in its subtree (the parser records some composite nodes only through their
     * operands). Null for a node no source knows.
     */
    fun locate(node: ASTNode): Pair<SourceContext, SourcePosition>? {
        var best: Pair<SourceContext, SourcePosition>? = null
        AstTree.walk(node) { n ->
            val source = sourceByNode[n] ?: return@walk
            val pos = source.astOrigins[n] ?: return@walk
            if (pos.lineNumber < 0) {
                return@walk
            }
            val current = best
            if (current == null || pos < current.second) {
                best = source to pos
            }
        }
        return best
    }

    /** The source that contains [node], or null. */
    fun sourceOf(node: ASTNode): SourceContext? = locate(node)?.first
}
