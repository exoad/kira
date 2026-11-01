package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

/**
 * Tuples of arity 7 and up, generated on demand. kira/tuples.kira declares T0..T6 by hand;
 * a program that names `T<n>` with n above 6 gets a magic class for each such arity, parsed as
 * the stdlib module [MODULE_URI] before analysis. Each backend emits only the arities a
 * program names: C++ a header of structs, JS a class per arity, py nothing (a Python tuple).
 *
 * Names above the hand-written set, identical on every backend: the fields are `first`..`tenth`
 * and then `item11`, `item12`, ...; the generic parameters are `A`..`J` and then `P11`, `P12`, ...
 */
object GeneratedTuples {
    /** The largest arity kira/tuples.kira declares by hand. */
    const val HAND_WRITTEN_MAX = 6

    /** Arities above this warn: each one is a generated type on every backend that emits it. */
    const val WARN_ABOVE = 10

    const val MODULE_URI = "kira:tuples_gen"
    const val CODE_ABOVE_MAX = "types.tuple.above-max"

    /** The logical path of the generated module; it is never read from disk. */
    private const val FILE = "kira/tuples_gen.kira"

    /** The C++ header of the generated structs, under the runtime's `kira/` directory. */
    const val CPP_HEADER = "tuples_gen.hxx"

    private val FIELDS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val PARAMS = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")

    /** The arities a unit needs, each with its first use (for the warning). */
    class Generated(val firstUse: Map<Int, Identifier>) {
        val arities: Set<Int> get() = firstUse.keys
    }

    /** A warning about one use, located in its source. */
    data class Warning(val code: String, val message: String, val file: String, val line: Int, val column: Int)

    /** The name of the [position]th field (1-based). */
    fun field(position: Int): String = FIELDS.getOrNull(position - 1) ?: "item$position"

    /** The name of the [position]th generic parameter (1-based). */
    fun param(position: Int): String = PARAMS.getOrNull(position - 1) ?: "P$position"

    /**
     * Finds every `T<n>` (n above [HAND_WRITTEN_MAX]) the parsed sources name, and parses the
     * generated module declaring those arities into [unit]. Idempotent: a unit is scanned once.
     */
    fun ensure(unit: CompilationUnit): Generated {
        unit.generatedTuples?.let { return it }
        val firstUse = linkedMapOf<Int, Identifier>()
        unit.allSources().forEach { source ->
            val ast = runCatching { source.ast }.getOrNull() ?: return@forEach
            AstTree.walk(ast) { node ->
                if (node is Identifier) {
                    Builtins.tupleArity(node.value)?.takeIf { it > HAND_WRITTEN_MAX }?.let { n -> firstUse.putIfAbsent(n, node) }
                }
            }
        }
        val generated = Generated(firstUse.toSortedMap())
        unit.generatedTuples = generated
        if (generated.arities.isNotEmpty()) {
            parseModule(unit, generated.arities)
        }
        return generated
    }

    /** The arities [unit] generates, empty when it names none above [HAND_WRITTEN_MAX]. */
    fun arities(unit: CompilationUnit): Set<Int> = unit.generatedTuples?.arities ?: emptySet()

    /** One warning per arity above [WARN_ABOVE], at its first use; empty when [unit] names none. */
    fun warnings(unit: CompilationUnit): List<Warning> {
        val generated = unit.generatedTuples ?: return emptyList()
        return generated.firstUse.filterKeys { it > WARN_ABOVE }.mapNotNull { (n, use) ->
            unit.allSources().firstNotNullOfOrNull { source ->
                runCatching { source.astOrigins[use] }.getOrNull()?.let { at ->
                    Warning(
                        CODE_ABOVE_MAX,
                        "T$n is generated on use: each arity above $WARN_ABOVE costs a generated type on every backend that emits it.",
                        source.file,
                        at.lineNumber,
                        at.column,
                    )
                }
            }
        }
    }

    /** The Kira declaration of the magic class T<n>, the shape of the hand-written ones in kira/tuples.kira. */
    fun kiraDeclaration(n: Int): String {
        val params = (1..n).map { param(it) }
        val self = "T$n<${params.joinToString(", ")}>"
        val out = StringBuilder()
        out.append("pub @_magic $self: Tuple<$self> {\n")
        (1..n).forEach { out.append("    pub ${field(it)}: ${param(it)}\n") }
        out.append("\n    pub size: () Int32 {\n        $n\n    }\n}\n")
        return out.toString()
    }

    /** The C++ header of the generated structs: `kira::TupleN` aggregates with `==`, as core.hxx writes the hand-written ones. */
    fun cppHeader(arities: Set<Int>): String {
        val out = StringBuilder()
        out.append("#pragma once\n#include \"kira/core.hxx\"\n\nnamespace kira {\n")
        arities.sorted().forEach { n ->
            out.append("template<${(1..n).joinToString(", ") { "class ${param(it)}" }}>\n")
            out.append("struct Tuple$n\n{\n")
            (1..n).forEach { out.append("    ${param(it)} ${field(it)};\n") }
            out.append("    bool operator==(const Tuple$n&) const = default;\n};\n")
        }
        out.append("}\n")
        return out.toString()
    }

    /** The JS classes of the generated arities, the shape of the hand-written KiraTuple classes in js_generator.js. */
    fun jsClasses(arities: Set<Int>): String {
        val out = StringBuilder()
        arities.sorted().forEach { n ->
            val fields = (1..n).map { field(it) }
            out.append("class KiraTuple$n {\n")
            out.append("  constructor(${fields.joinToString(", ")}) {\n")
            fields.forEach { out.append("    this.$it = $it;\n") }
            out.append("  }\n  size() { return $n; }\n}\n\n")
        }
        return out.toString()
    }

    private fun parseModule(unit: CompilationUnit, arities: Set<Int>) {
        val text = arities.sorted().joinToString("\n") { kiraDeclaration(it) }
        val bare = unit.addSource(FILE, text, emptyList(), MODULE_URI)
        val tokens = KiraLexer(bare).tokenize()
        val ctx = unit.addSource(FILE, bare.content, tokens)
        KiraSourceParsers.from(ctx).parse()
    }
}
