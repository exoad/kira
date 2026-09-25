package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.kim.SourceGlob

/**
 * - [OFF]: the typer does not run (C and JS until the W2 gate): an empty program.
 * - [LENIENT]: the model is filled and every diagnostic is a warning.
 * - [STRICT]: errors are errors. The C++ target always types STRICT.
 */
enum class TyperMode { OFF, LENIENT, STRICT }

/**
 * Per-project inputs. The globs match module URIs (`firmware:lib.**`) by the manifest's own
 * rules ([net.exoad.kira.kim.SourceGlob]): `*` matches within one dotted segment, a `**`
 * segment matches zero or more segments, `?` one character, `{a,b}` either alternative. A
 * trailing `.**` therefore also matches the prefix module itself.
 *
 * @property freestanding `build.cpp.freestanding`: modules typed against the Pico subset.
 * @property headerOnly `build.cpp.headerOnly`: modules that emit an inline-only header.
 * @property namespaces `build.cpp.namespaces`: URI (or glob) to C++ namespace, resolved as
 *   CppModuleLayout.namespaceOf resolves it: an exact URI first, then the glob with the most
 *   literal text, then the longer glob.
 */
data class TyperOptions(
    val freestanding: List<String> = emptyList(),
    val headerOnly: List<String> = emptyList(),
    val namespaces: Map<String, String> = emptyMap(),
)

/** Phase C: types every body. W2.1 sets [KiraTyper.bodyTyper]. */
interface BodyTyper {
    fun type(program: TypedProgram)

    companion object {
        /** No phase C: bodies stay untyped. */
        val NONE: BodyTyper = object : BodyTyper {
            override fun type(program: TypedProgram) {}
        }
    }
}

/** A read-only walk that adds diagnostics (EffectsPass and EscapePass also fill their tables). W2.5 fills the list. */
interface RulePass {
    val name: String
    fun run(program: TypedProgram)
}

/**
 * The typed frontend: phase A (DeclarationCollector, ModuleGraph) collects a symbol for every
 * declaration, phase B (TypeResolver, SignatureResolver, ConstEval) resolves every type,
 * signature and constant, phase C ([bodyTyper]) types the bodies, and the [rulePasses] check
 * the rules. See design section 3.
 */
object KiraTyper {
    internal var bodyTyper: BodyTyper = BodyTyper.NONE

    internal val rulePasses: MutableList<RulePass> = mutableListOf()

    /**
     * Types [unit]. Never throws: an internal failure becomes a `types.internal` diagnostic,
     * and whatever the failing step was resolving stays [KType.Error].
     */
    fun run(unit: CompilationUnit, mode: TyperMode, options: TyperOptions = TyperOptions()): TypedProgram {
        val modules = mutableListOf<ModuleSymbol>()
        val program = TypedProgram(unit, modules, TypedModel(), mode)
        program.options = options
        if (mode == TyperMode.OFF) {
            return program
        }
        guard(program, "declaration collection") {
            DeclarationCollector(program, modules).collect()
        }
        guard(program, "module graph") {
            program.graph.link()
        }
        guard(program, "signature resolution") {
            SignatureResolver(program).resolveAll()
        }
        guard(program, "body typing") {
            bodyTyper.type(program)
        }
        rulePasses.forEach { pass ->
            guard(program, "rule pass ${pass.name}") {
                pass.run(program)
            }
        }
        return program
    }

    /** Runs [step]; a throw becomes a `types.internal` diagnostic instead of escaping. */
    internal fun guard(program: TypedProgram, what: String, node: net.exoad.kira.compiler.frontend.parser.ast.ASTNode? = null, step: () -> Unit): Boolean {
        return try {
            step()
            true
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is StackOverflowError) {
                throw t
            }
            program.report(
                "types.internal",
                "internal typer failure during $what: ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}",
                node,
            )
            false
        }
    }
}

/**
 * Glob matching over module URIs, for [TyperOptions]. It is the manifest's matcher
 * ([SourceGlob], which CppOptions and CppModuleLayout use), so a module the typer marks
 * header-only or freestanding is the one the C++ backend lays out that way.
 */
object ModuleGlob {
    fun matches(glob: String, uri: String): Boolean = SourceGlob.matchesUri(glob, uri)

    /** The value of the key in [map] that names [uri]: an exact key, then the most specific glob ([SourceGlob.lookupUri]). */
    fun <V> lookup(map: Map<String, V>, uri: String): V? = SourceGlob.lookupUri(map, uri)
}
