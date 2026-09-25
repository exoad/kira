package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.CompilationUnit

/**
 * - [OFF]: the typer does not run (C and JS until the W2 gate): an empty program.
 * - [LENIENT]: the model is filled and every diagnostic is a warning.
 * - [STRICT]: errors are errors. The C++ target always types STRICT.
 */
enum class TyperMode { OFF, LENIENT, STRICT }

/**
 * Per-project inputs. The globs match module URIs (`firmware:lib.**`): `*` matches within one
 * dotted segment, `**` matches any run of segments, `?` one character, `{a,b}` either
 * alternative. A trailing `.**` also matches the prefix module itself.
 *
 * @property freestanding `build.cpp.freestanding`: modules typed against the Pico subset.
 * @property headerOnly `build.cpp.headerOnly`: modules that emit an inline-only header.
 * @property namespaces `build.cpp.namespaces`: URI (or glob) to C++ namespace. An exact URI
 *   wins over a glob; among globs the first listed wins.
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

/** Glob matching over module URIs, for [TyperOptions]. */
object ModuleGlob {
    fun matches(glob: String, uri: String): Boolean {
        if (glob == uri) {
            return true
        }
        if (glob.endsWith(".**") && uri == glob.removeSuffix(".**")) {
            return true
        }
        return toRegex(glob).matches(uri)
    }

    /** The first value of [map] whose key matches [uri]: an exact key first, then globs in order. */
    fun <V> lookup(map: Map<String, V>, uri: String): V? {
        map[uri]?.let { return it }
        return map.entries.firstOrNull { (glob, _) -> matches(glob, uri) }?.value
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun toRegex(glob: String): Regex = cache.getOrPut(glob) {
        val sb = StringBuilder()
        var i = 0
        var inAlternation = false
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i++
                }
                c == '*' -> sb.append("[^.:]*")
                c == '?' -> sb.append("[^.:]")
                c == '{' -> {
                    sb.append("(?:")
                    inAlternation = true
                }
                c == '}' && inAlternation -> {
                    sb.append(")")
                    inAlternation = false
                }
                c == ',' && inAlternation -> sb.append("|")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        Regex(sb.toString())
    }
}
