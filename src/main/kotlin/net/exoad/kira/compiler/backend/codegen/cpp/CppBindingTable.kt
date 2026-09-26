package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The `cpp:` blocks of the stdlib's binding manifests (design 7.4): every `kira/<m>.bind.yaml`
 * beside the stdlib modules the typed program holds. An entry is `Key: { cpp: { expr, includes,
 * pure, constexpr } }`; the C loader reads only `symbol` and `includes`, so a `cpp:` sub-map is
 * invisible to it.
 *
 * **Keys** are `Type.method` for a method and the bare name for a free function (a
 * [Foreign.Magic] key). A method is keyed on the class the call reaches it through: a scalar
 * receiver finds `Int32.abs` nowhere and walks its parents to `Num.abs`, and `s.equals(t)` on a
 * `Str` is `Str.equals` though `Equatable` declares it ([keysFor]).
 *
 * **Placeholders** in `expr`: `{self}` the receiver, `{0}`, `{1}`, ... the arguments in
 * parameter order, `{T0}`, `{T1}`, ... the type arguments of the bound declaration as the
 * typer instantiated them (a free function's own, or a generic class's, read from the
 * receiver's type) ([expand]).
 *
 * **Includes** go to the header when the code that uses them is header-placed, else to the
 * source ([use]). `CppDeclEmitter` writes every include it is handed as `#include "..."`, so a
 * system header the manifest spells `<cmath>` is handed on as `cmath`, which a quoted include
 * finds through its fallback to the system search on gcc, clang and MSVC.
 */
class CppBindingTable : CppBindingsPart {
    private val entries = LinkedHashMap<String, CppBinding>()
    private val loadedDirs = HashSet<Path>()
    private val loadedPrograms = Collections.newSetFromMap(IdentityHashMap<TypedProgram, Boolean>())

    /** Loads the manifests beside the stdlib modules of [program] (once per program and directory). */
    fun load(program: TypedProgram) {
        if (!loadedPrograms.add(program)) {
            return
        }
        program.modules.filter { it.isStdlib }.forEach { m ->
            val file = runCatching { Path.of(m.source.file) }.getOrNull() ?: return@forEach
            file.toAbsolutePath().normalize().parent?.let { loadDir(it) }
        }
    }

    /** Loads every `*.bind.yaml` directly in [dir]. */
    fun loadDir(dir: Path) {
        val normalized = dir.toAbsolutePath().normalize()
        if (!loadedDirs.add(normalized) || !Files.isDirectory(normalized)) {
            return
        }
        Files.list(normalized).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(MANIFEST_SUFFIX) }
                .sorted()
                .forEach { entries.putAll(parse(it)) }
        }
    }

    override fun lookup(key: String): CppBinding? {
        if (loadedDirs.isEmpty()) {
            loadDir(Path.of("kira"))
        }
        return entries[key]
    }

    companion object {
        const val MANIFEST_SUFFIX = ".bind.yaml"

        /** The `cpp:` entries of one manifest, by key. An entry without an `expr` is skipped. */
        fun parse(path: Path): Map<String, CppBinding> {
            val out = LinkedHashMap<String, CppBinding>()
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return out
            val yaml = runCatching { Yaml().load<Any>(text) }.getOrNull() as? Map<*, *> ?: return out
            yaml.forEach { (key, value) ->
                val cpp = (value as? Map<*, *>)?.get("cpp") as? Map<*, *> ?: return@forEach
                val expr = cpp["expr"]?.toString() ?: return@forEach
                val includes = (cpp["includes"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                out[key.toString()] = CppBinding(expr, includes, cpp["pure"] == true, cpp["constexpr"] == true)
            }
            return out
        }

        /**
         * The keys a call of [fn] on a receiver of type [receiver] may be bound under, most
         * specific first: the receiver's class and its parents (`Int32.abs`, then `Num.abs`), the
         * method's own declaring type, and the symbol's [Foreign.Magic] key. A free function has
         * its magic key (or its name) only.
         */
        fun keysFor(fn: FnSymbol, receiver: KType?, program: TypedProgram): List<String> {
            val out = LinkedHashSet<String>()
            if (fn.owner != null || receiver != null) {
                receiverClasses(receiver, program).forEach { out.add("${it.name}.${fn.name}") }
                fn.owner?.let { out.add("${it.name}.${fn.name}") }
            }
            (fn.foreign as? Foreign.Magic)?.key?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            if (fn.owner == null && receiver == null) {
                out.add(fn.name)
            }
            return out.toList()
        }

        /** The classes (and traits) a receiver of type [t] reaches, nearest first: its own, then its parents. */
        private fun receiverClasses(t: KType?, program: TypedProgram): List<TypeSymbol> {
            val start: List<TypeSymbol> = when (t) {
                null -> emptyList()
                is KType.Nominal -> listOf(t.sym)
                is KType.Param -> t.sym.bounds.mapNotNull { (it as? KType.Nominal)?.sym }
                is KType.Fn -> listOfNotNull(program.builtins.classOf(t))
                else -> listOfNotNull(program.builtins.classOf(t))
            }
            val out = mutableListOf<TypeSymbol>()
            val seen = Collections.newSetFromMap(IdentityHashMap<TypeSymbol, Boolean>())
            val queue = ArrayDeque(start)
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s)) {
                    continue
                }
                out.add(s)
                when (s) {
                    is ClassSymbol -> {
                        s.superclass?.let { queue.addLast(it.sym) }
                        s.traits.forEach { queue.addLast(it.sym) }
                    }
                    is TraitSymbol -> s.parents.forEach { queue.addLast(it.sym) }
                    else -> {}
                }
            }
            return out
        }

        /** Whether [t] is a magic class (a runtime container, a scalar's class, Str's): its members bind through the manifests. */
        fun isMagicReceiver(t: KType, program: TypedProgram): Boolean = when (t) {
            is KType.Scalar, KType.Str -> true
            is KType.Nominal -> (t.sym as? ClassSymbol)?.kind == ClassKind.MAGIC
            is KType.Fn -> program.builtins.classOf(t) != null
            else -> false
        }

        /** The magic class name of [t] (`Arr`, `View`, `Maybe`), or null. */
        fun magicName(t: KType?): String? = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.takeIf { it.kind == ClassKind.MAGIC }?.name

        /** Whether [t] is `Arr<T>` or `Arr<T, N>`. */
        fun isArr(t: KType?): Boolean = magicName(t) == Builtins.ARR

        /**
         * [binding]'s expression with its placeholders replaced. [self] is null for a free
         * function. [argument] gives the text of argument i at the precedence its position in
         * the template needs; [typeArgs] the spelled type arguments.
         */
        fun expand(binding: CppBinding, self: ((Int) -> String)?, argument: (Int, Int) -> String, typeArgs: List<String>): String {
            val template = binding.expr
            val sb = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '{') {
                    val close = template.indexOf('}', i)
                    if (close > i) {
                        val name = template.substring(i + 1, close)
                        val prec = placeholderPrec(template, i, close)
                        val replaced: String? = when {
                            name == "self" -> self?.invoke(prec)
                            name.startsWith("T") && name.drop(1).toIntOrNull() != null -> typeArgs.getOrNull(name.drop(1).toInt())
                            name.toIntOrNull() != null -> argument(name.toInt(), prec)
                            else -> null
                        }
                        if (replaced != null) {
                            sb.append(replaced)
                            i = close + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i += 1
            }
            return sb.toString()
        }

        /** Whether the binding is member-style on its receiver: `{self}.size()`, `{self}[{0}]`, `{self}->bind(...)`. */
        fun isMemberStyle(binding: CppBinding): Boolean {
            val t = binding.expr.trimStart('(')
            return t.startsWith("{self}.") || t.startsWith("{self}[") || t.startsWith("{self}->")
        }

        /**
         * The precedence a placeholder at `[open, close]` of [template] must be emitted at: a whole
         * argument, subscript, initializer or assigned value takes anything but a comma; the
         * object of `.`, `->` or `[` a postfix expression; an operand of an operator a unary one.
         */
        private fun placeholderPrec(template: String, open: Int, close: Int): Int {
            val after = template.substring(close + 1).trimStart()
            val before = template.substring(0, open).trimEnd()
            val next = after.firstOrNull()
            if (next == '.' || next == '[' || after.startsWith("->")) {
                return CppPrec.POSTFIX
            }
            val prev = before.lastOrNull()
            val openedAsWhole = prev == '(' || prev == ',' || prev == '[' || prev == '{' || (prev == '=' && before.getOrNull(before.length - 2) == ' ')
            val closedAsWhole = next == null || next == ')' || next == ',' || next == ']' || next == '}'
            if (openedAsWhole && closedAsWhole) {
                return CppPrec.ASSIGN
            }
            return CppPrec.UNARY
        }

        /**
         * The precedence of [text], a C++ expression written by a binding or the emitter: the
         * loosest operator at its top level (outside parentheses, brackets, braces, template
         * arguments and literals), or [CppPrec.POSTFIX] for a call, a member access, a name.
         * Space-separated operators are the manifests' convention (`{self} == {0}`).
         */
        fun precOf(text: String): Int {
            val t = text.trim()
            if (t.isEmpty()) {
                return CppPrec.PRIMARY
            }
            var depth = 0
            var i = 0
            var lowest = Int.MAX_VALUE
            var inString = false
            var inChar = false
            while (i < t.length) {
                val c = t[i]
                when {
                    inString -> {
                        if (c == '\\') i += 1 else if (c == '"') inString = false
                    }
                    inChar -> {
                        if (c == '\\') i += 1 else if (c == '\'') inChar = false
                    }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '(' || c == '[' || c == '{' -> depth += 1
                    c == ')' || c == ']' || c == '}' -> depth -= 1
                    c == '<' && depth == 0 && i > 0 && (t[i - 1].isLetterOrDigit() || t[i - 1] == '_' || t[i - 1] == ':') && t.getOrNull(i + 1) != '<' && t.getOrNull(i + 1) != '=' -> {
                        // A template argument list: skip to its matching '>'.
                        var d = 1
                        var j = i + 1
                        while (j < t.length && d > 0) {
                            when (t[j]) {
                                '<' -> d += 1
                                '>' -> d -= 1
                            }
                            j += 1
                        }
                        i = j - 1
                    }
                    depth == 0 -> {
                        val p = operatorAt(t, i)
                        if (p != null) {
                            lowest = minOf(lowest, p.first)
                            i += p.second - 1
                        }
                    }
                }
                i += 1
            }
            if (lowest != Int.MAX_VALUE) {
                return lowest
            }
            if (t.startsWith("!") || t.startsWith("~") || (t.startsWith("-") && !t.startsWith("->")) || t.startsWith("*") || t.startsWith("&")) {
                return CppPrec.UNARY
            }
            return CppPrec.POSTFIX
        }

        /** A space-delimited binary operator (or a top-level comma, or the `?` of a conditional) at [i]: its precedence and length. */
        private fun operatorAt(t: String, i: Int): Pair<Int, Int>? {
            if (t[i] == ',') {
                return CppPrec.COMMA to 1
            }
            if (t[i] == '?' && i > 0 && t[i - 1] == ' ') {
                return CppPrec.ASSIGN to 1
            }
            if (i == 0 || t[i - 1] != ' ') {
                return null
            }
            val ops = listOf(
                "<<=" to CppPrec.ASSIGN, ">>=" to CppPrec.ASSIGN, "+=" to CppPrec.ASSIGN, "-=" to CppPrec.ASSIGN, "*=" to CppPrec.ASSIGN,
                "/=" to CppPrec.ASSIGN, "%=" to CppPrec.ASSIGN, "&=" to CppPrec.ASSIGN, "|=" to CppPrec.ASSIGN, "^=" to CppPrec.ASSIGN,
                "||" to CppPrec.OR, "&&" to CppPrec.AND, "==" to CppPrec.EQ, "!=" to CppPrec.EQ, "<=" to CppPrec.REL, ">=" to CppPrec.REL,
                "<<" to CppPrec.SHIFT, ">>" to CppPrec.SHIFT, "=" to CppPrec.ASSIGN, "<" to CppPrec.REL, ">" to CppPrec.REL,
                "|" to CppPrec.BIT_OR, "^" to CppPrec.BIT_XOR, "&" to CppPrec.BIT_AND, "+" to CppPrec.ADD, "-" to CppPrec.ADD,
                "*" to CppPrec.MUL, "/" to CppPrec.MUL, "%" to CppPrec.MUL,
            )
            for ((op, prec) in ops) {
                if (t.startsWith(op, i) && t.getOrNull(i + op.length) == ' ') {
                    return prec to op.length
                }
            }
            return null
        }

        /**
         * An include a manifest names, as [CppEmitContext.includeInHeader] takes it: `<cmath>`
         * becomes `cmath` (the declaration emitter quotes every include, and a quoted include of
         * a system header falls back to the system search), anything else is kept.
         */
        fun includeName(include: String): String {
            val t = include.trim()
            return if (t.startsWith("<") && t.endsWith(">")) t.substring(1, t.length - 1) else t
        }
    }
}

/**
 * C++ operator precedence, loosest first, as the expression emitter compares it: an operand
 * whose own precedence is below the one its position asks for is parenthesized.
 */
object CppPrec {
    const val NONE = 0
    const val COMMA = 1

    /** Assignment and the conditional operator (right-associative). */
    const val ASSIGN = 2
    const val OR = 3
    const val AND = 4
    const val BIT_OR = 5
    const val BIT_XOR = 6
    const val BIT_AND = 7
    const val EQ = 8
    const val REL = 9
    const val SHIFT = 11
    const val ADD = 12
    const val MUL = 13
    const val UNARY = 15
    const val POSTFIX = 16
    const val PRIMARY = 17
}
