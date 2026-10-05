package net.exoad.kira.compiler.backend.codegen.py

import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * One `py:` binding of a stdlib manifest (`kira/<m>.bind.yaml`): a Python expression with
 * `{self}` for the receiver and `{0}`, `{1}`, ... for the arguments in parameter order. Each
 * placeholder appears once, the receiver first and the arguments in order, so Python's
 * left-to-right evaluation is Kira's (D33). A `_k_` name in it is a helper of the runtime.
 */
data class PyBinding(val expr: String) {
    /** The placeholders in the order the expression names them. */
    val placeholders: List<String> get() = PLACEHOLDER.findAll(expr).map { it.groupValues[1] }.toList()

    /** Whether the placeholders appear once each, `self` first and the arguments in order. */
    val isOrdered: Boolean
        get() {
            val names = placeholders
            val want = (if ("self" in names) listOf("self") else emptyList()) + names.filter { it != "self" }.sortedBy { it.toInt() }
            return names == want && names.toSet().size == names.size
        }

    /** The expression's own precedence: fully parenthesized, or a call, subscript or attribute. */
    val prec: Int get() = if (PyRuntime.isWrapped(expr)) PyPrec.ATOM else PyPrec.POSTFIX

    companion object {
        val PLACEHOLDER = Regex("\\{(self|[0-9]+)}")
    }
}

/**
 * The `py:` blocks of the binding manifests beside the stdlib modules a typed program holds
 * (as [net.exoad.kira.compiler.backend.codegen.cpp.CppBindingTable] reads the `cpp:` blocks).
 * Keys are the C++ table's: `Type.method` for a method, the bare name for a free function.
 */
class PyBindingTable {
    private val entries = LinkedHashMap<String, PyBinding>()
    private val loadedDirs = HashSet<Path>()

    fun load(program: TypedProgram) {
        program.modules.filter { it.isStdlib }.forEach { m ->
            val file = runCatching { Path.of(m.source.file) }.getOrNull() ?: return@forEach
            file.toAbsolutePath().normalize().parent?.let { loadDir(it) }
        }
    }

    fun loadDir(dir: Path) {
        val normalized = dir.toAbsolutePath().normalize()
        if (!loadedDirs.add(normalized) || !Files.isDirectory(normalized)) {
            return
        }
        Files.list(normalized).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bind.yaml") }
                .sorted()
                .forEach { entries.putAll(parse(it)) }
        }
    }

    fun lookup(key: String): PyBinding? = entries[key]

    /** Every binding loaded, by key (tests check each is ordered). */
    fun all(): Map<String, PyBinding> = entries

    companion object {
        fun parse(path: Path): Map<String, PyBinding> {
            val out = LinkedHashMap<String, PyBinding>()
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return out
            val yaml = runCatching { Yaml().load<Any>(text) }.getOrNull() as? Map<*, *> ?: return out
            yaml.forEach { (key, value) ->
                val py = (value as? Map<*, *>)?.get("py") as? Map<*, *> ?: return@forEach
                val expr = py["expr"]?.toString() ?: return@forEach
                out[key.toString()] = PyBinding(expr)
            }
            return out
        }

        /**
         * [binding]'s expression with its placeholders replaced: [self] gives the receiver's
         * text and [argument] argument i's, each at the precedence its place needs (any
         * expression as a whole argument, else a primary).
         */
        fun expand(binding: PyBinding, self: ((Int) -> String)?, argument: (Int, Int) -> String): String {
            val template = binding.expr
            return PyBinding.PLACEHOLDER.replace(template) { m ->
                val name = m.groupValues[1]
                val before = template.substring(0, m.range.first).trimEnd().lastOrNull()
                val after = template.substring(m.range.last + 1).trimStart().firstOrNull()
                val whole = (before == '(' || before == ',' || before == '[') && (after == ')' || after == ',' || after == ']')
                val prec = if (whole) PyPrec.TERNARY else PyPrec.POSTFIX
                if (name == "self") self?.invoke(prec) ?: m.value else argument(name.toInt(), prec)
            }
        }
    }
}

/**
 * `kira/py/runtime.py`: the helpers generated Python calls. Each top-level definition is a
 * block (with the comment lines right above it); [select] returns the blocks a module uses and
 * the blocks those use, in the file's order, so a generated module carries only what it needs.
 */
class PyRuntime(text: String) {
    private val blocks = LinkedHashMap<String, String>()

    init {
        val lines = text.replace("\r\n", "\n").split('\n')
        var comments = mutableListOf<String>()
        var name: String? = null
        var body = mutableListOf<String>()
        fun close() {
            val n = name ?: return
            blocks[n] = body.joinToString("\n").trimEnd()
            name = null
            body = mutableListOf()
        }
        for (line in lines) {
            val top = line.isNotEmpty() && !line[0].isWhitespace()
            when {
                top && line.startsWith("#") -> {
                    close()
                    comments.add(line)
                }
                top -> {
                    close()
                    name = DEFINES.find(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                    body = (comments + line).toMutableList()
                    comments = mutableListOf()
                }
                line.isBlank() -> {
                    if (name == null) comments = mutableListOf() else body.add(line)
                }
                else -> body.add(line)
            }
        }
        close()
    }

    /** The helper names the runtime defines. */
    val names: Set<String> get() = blocks.keys

    /** The blocks named in [used], with every block they name, in the runtime's order; "" for none. */
    fun select(used: Set<String>): String {
        val wanted = LinkedHashSet<String>()
        val queue = ArrayDeque(used.filter { it in blocks })
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!wanted.add(n)) {
                continue
            }
            HELPER.findAll(blocks[n]!!).map { it.value }.filter { it in blocks && it != n }.forEach { queue.addLast(it) }
        }
        return blocks.filterKeys { it in wanted }.values.joinToString("\n\n\n")
    }

    companion object {
        const val FILE = "runtime.py"
        val HELPER = Regex("_k_[A-Za-z0-9_]+")
        private val DEFINES = Regex("^(?:def ([A-Za-z_][A-Za-z0-9_]*)|class ([A-Za-z_][A-Za-z0-9_]*)|import [A-Za-z0-9_.]+ as ([A-Za-z_][A-Za-z0-9_]*)|([A-Za-z_][A-Za-z0-9_]*) *=)")

        /**
         * `py/runtime.py` beside the stdlib modules [program] holds (where the binding manifests
         * are read from), else the one [StdlibLayout] finds; null when there is none.
         */
        fun load(program: TypedProgram? = null): PyRuntime? {
            val beside = program?.modules.orEmpty().filter { it.isStdlib }.firstNotNullOfOrNull { m ->
                runCatching { Path.of(m.source.file).toAbsolutePath().normalize().parent.resolve("py").resolve(FILE) }.getOrNull()
                    ?.takeIf { Files.isRegularFile(it) }
            }
            val file = beside ?: StdlibLayout.runtimeFile("py", FILE) ?: return null
            return PyRuntime(Files.readString(file))
        }

        /** Whether [text] is one parenthesized expression: its first `(` closes at its last character. */
        fun isWrapped(text: String): Boolean {
            if (!text.startsWith("(") || !text.endsWith(")")) {
                return false
            }
            var depth = 0
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != null -> if (c == '\\') i += 1 else if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c == '(' || c == '[' || c == '{' -> depth += 1
                    c == ')' || c == ']' || c == '}' -> {
                        depth -= 1
                        if (depth == 0 && i < text.length - 1) {
                            return false
                        }
                    }
                }
                i += 1
            }
            return depth == 0
        }
    }
}

/**
 * Python's operator precedence, loosest first: an operand whose precedence is below what its
 * place needs is parenthesized. A comparison's operands need more than a comparison, so
 * `(a < b) == c` never becomes Python's chained `a < b == c`.
 */
object PyPrec {
    const val NONE = 0
    const val TERNARY = 2
    const val OR = 3
    const val AND = 4
    const val NOT = 5
    const val CMP = 6
    const val BOR = 7
    const val BXOR = 8
    const val BAND = 9
    const val SHIFT = 10
    const val ADD = 11
    const val MUL = 12
    const val UNARY = 13
    const val POSTFIX = 16
    const val ATOM = 17
}

/** Names the py target cannot give a Kira declaration, and why. */
object PyNames {
    val KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    )

    /** The builtins generated code and the runtime call by name, which a Kira name would shadow, and `self`. */
    val RESERVED = setOf(
        "self", "abs", "bool", "float", "int", "len", "list", "max", "min", "print", "str", "object",
        "isinstance", "bytes", "bytearray", "memoryview", "RuntimeError", "ValueError", "OverflowError",
    )

    /** Why [name] cannot be a Python name of a Kira declaration, or null when it can. */
    fun refusal(name: String): String? = when {
        name in KEYWORDS -> "'$name' is a Python keyword"
        name in RESERVED -> "'$name' is a name generated Python uses"
        name.startsWith("_") || name.startsWith("k_") -> "'$name' starts with '_' or 'k_', which the py target keeps for private names and its runtime"
        else -> null
    }
}
