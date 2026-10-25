package net.exoad.kira.dialect

import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ProjectKira
import net.exoad.kira.kim.ProjectManifest
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * A kira.yaml as the project.kira that replaces it: each key the yaml sets as one assignment, in
 * the yaml's order, its comments carried over as `//` lines; [convert] proves the two equal.
 */
object ProjectYaml {
    /** The project.kira text, or why it cannot be written. */
    class Result(val text: String?, val problem: String?)

    /** [yaml]'s project.kira, proved: both load to the same configuration, field by field. */
    fun convert(yaml: String): Result {
        val manifest = runCatching { ManifestLoader.parse(yaml) }.getOrElse { return Result(null, "the kira.yaml does not load: ${it.message}") }
        val text = runCatching { write(yaml, manifest) }.getOrElse { return Result(null, it.message ?: it.toString()) }
        val back = runCatching { ProjectKira.parse(text) }.getOrElse { return Result(null, "the project.kira written does not load: ${it.message}") }
        if (back != manifest) return Result(null, "the configurations differ: ${diff(manifest, back)}")
        return Result(text, null)
    }

    /** The first field where [a] and [b] differ, by name. */
    private fun diff(a: ProjectManifest, b: ProjectManifest): String {
        val pairs = listOf(
            "name" to (a.project to b.project), "srcDir" to (a.srcDir to b.srcDir), "srcExclude" to (a.srcExclude to b.srcExclude),
            "build" to (a.build to b.build), "compiler" to (a.compiler to b.compiler), "dependencies" to (a.dependencies to b.dependencies),
        )
        return pairs.firstOrNull { it.second.first != it.second.second }?.let { "${it.first}: ${it.second.first} against ${it.second.second}" } ?: "?"
    }

    /** One comment's place: the field it goes with ("" for the file's head), before or after it. */
    private class Comments {
        val before = LinkedHashMap<String, MutableList<String>>()
        val after = HashMap<String, String>()
        val tail = mutableListOf<String>()
    }

    private val keyLine = Regex("""^(\s*)(?:-\s+)?("[^"]*"|'[^']*'|[A-Za-z_][A-Za-z0-9_.-]*)\s*:(\s|$)""")

    /** project.kira for [manifest], each key [yaml] sets, its comments with it. */
    private fun write(yaml: String, manifest: ProjectManifest): String {
        @Suppress("UNCHECKED_CAST")
        val raw = (Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
        val order = mutableListOf<String>()
        val comments = Comments()
        val pending = mutableListOf<String>()
        val stack = ArrayDeque<Pair<Int, String>>()
        var seenKey = false
        for (line in yaml.replace("\r\n", "\n").split("\n")) {
            val cut = commentStart(line)
            val code = line.substring(0, cut)
            // A comment keeps its own indentation after the `#` and one space.
            val comment = line.substring(cut).removePrefix("#").let { if (it.startsWith(" ")) it.substring(1) else it }.trimEnd().takeIf { cut < line.length }
            val m = keyLine.find(code)
            if (m == null) {
                if (code.isBlank()) comment?.let { pending += it }
                else if (comment != null) pending += comment
                continue
            }
            val indent = m.groupValues[1].length
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            stack.addLast(indent to m.groupValues[2].trim('"', '\''))
            val field = fieldOf(stack.map { it.second }, code.substring(m.range.last + 1).trim())
            if (!seenKey) {
                seenKey = true
                comments.before.getOrPut("") { mutableListOf() }.addAll(pending)
                pending.clear()
            }
            if (field == null) {
                comment?.let { pending += it }
                continue
            }
            if (field !in order) order += field
            comments.before.getOrPut(field) { mutableListOf() }.addAll(pending)
            pending.clear()
            // A field's later lines (a dependency's entry) trail it too, or sit above it.
            comment?.let { if (field in comments.after) comments.before.getOrPut(field) { mutableListOf() } += it else comments.after[field] = it }
        }
        comments.tail.addAll(pending)
        return render(manifest, raw, order, comments)
    }

    /** The project.kira field a yaml key path sets (`build.cpp.headerOnly` is `cpp.headerOnly`); null for a group's line. */
    private fun fieldOf(path: List<String>, rest: String): String? {
        val k = path.map { camel(it) }
        return when {
            k[0] == "project" -> if (k.size == 1) (if (rest.isNotEmpty()) "name" else null) else k[1].takeIf { it in setOf("name", "author", "version") }
            k[0] == "srcDir" || k[0] == "srcExclude" -> k[0]
            k[0] == "build" && k.size == 1 -> if (rest.isNotEmpty()) "target" else null
            k[0] == "build" && k[1] == "cpp" -> if (k.size == 2) "cpp" else if (k.size == 3) "cpp.${k[2]}" else null
            k[0] == "build" && k.size == 2 -> k[1]
            k[0] == "compiler" && k.size == 1 -> if (rest.isNotEmpty()) "types" else null
            k[0] == "compiler" -> k[1]
            k[0] == "dependencies" -> "dependencies"
            else -> null
        }
    }

    private fun camel(k: String): String = k.split('_').mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }.joinToString("")

    /** Whether the yaml sets [path] (camelCase or snake_case keys). */
    private fun present(raw: Map<String, Any?>, vararg path: String): Boolean {
        var at: Any? = raw
        for (p in path) {
            val m = at as? Map<*, *> ?: return false
            val snake = p.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }
            at = if (m.containsKey(p)) m[p] else if (m.containsKey(snake)) m[snake] else return false
        }
        return true
    }

    private fun render(m: ProjectManifest, raw: Map<String, Any?>, order: List<String>, c: Comments): String {
        val out = StringBuilder()
        c.before[""].orEmpty().forEach { out.append("// ").append(it).append('\n') }
        out.append("${ProjectKira.CONSTANT}: Project = Project { } with {\n")
        val cpp = m.build.cpp
        val values = linkedMapOf<String, String?>(
            "name" to str(m.project.name),
            "author" to m.project.author.takeIf { present(raw, "project", "author") }?.let(::str),
            "version" to m.project.version.takeIf { present(raw, "project", "version") }?.let(::str),
            "srcDir" to m.srcDir.takeIf { present(raw, "srcDir") }?.let(::str),
            "srcExclude" to m.srcExclude.takeIf { present(raw, "srcExclude") }?.let(::list),
            "target" to m.build.target.takeIf { present(raw, "build", "target") }?.let(::str),
            "cSources" to m.build.cSources.takeIf { present(raw, "build", "cSources") }?.let(::list),
            "linkFlags" to m.build.linkFlags.takeIf { present(raw, "build", "linkFlags") }?.let(::list),
            "minify" to m.build.minify.takeIf { present(raw, "build", "minify") }?.toString(),
            "types" to m.compiler.types.name.lowercase().takeIf { present(raw, "compiler", "types") }?.let(::str),
            "emitIr" to m.compiler.emitIr?.takeIf { present(raw, "compiler", "emitIr") }?.let(::str),
            "dependencies" to m.dependencies.takeIf { present(raw, "dependencies") }?.let { deps ->
                if (deps.isEmpty()) "Map<Str, Str> { }" else "@mapOf(" + deps.entries.joinToString(", ") { (k, v) -> "${str(k)}, ${str(v.path ?: "")}" } + ")"
            },
        )
        val cppValues = linkedMapOf<String, String?>(
            "layout" to cpp.layout.name.lowercase().takeIf { present(raw, "build", "cpp", "layout") }?.let(::str),
            "outDir" to cpp.outDir.takeIf { present(raw, "build", "cpp", "outDir") }?.let(::str),
            "runtimeDir" to cpp.runtimeDir?.takeIf { present(raw, "build", "cpp", "runtimeDir") }?.let(::str),
            "lineDirectives" to cpp.lineDirectives.takeIf { present(raw, "build", "cpp", "lineDirectives") }?.toString(),
            "namespaces" to cpp.namespaces.takeIf { present(raw, "build", "cpp", "namespaces") }?.let { ns ->
                if (ns.isEmpty()) "Map<Str, Str> { }" else "@mapOf(" + ns.entries.joinToString(", ") { (k, v) -> "${str(k)}, ${str(v)}" } + ")"
            },
            "headerOnly" to cpp.headerOnly.takeIf { present(raw, "build", "cpp", "headerOnly") }?.let(::list),
            "freestanding" to cpp.freestanding.takeIf { present(raw, "build", "cpp", "freestanding") }?.let(::list),
            "headerExt" to cpp.headerExt.takeIf { present(raw, "build", "cpp", "headerExt") }?.let(::str),
            "sourceExt" to cpp.sourceExt.takeIf { present(raw, "build", "cpp", "sourceExt") }?.let(::str),
        )
        fun line(indent: String, field: String, key: String, value: String, comma: Boolean = false) {
            c.before[field].orEmpty().forEach { out.append(indent).append("// ").append(it).append('\n') }
            out.append(indent).append(key).append(" = ").append(value)
            if (comma) out.append(',')
            c.after[field]?.let { out.append(" // ").append(it) }
            out.append('\n')
        }
        val fields = (order.map { it.substringBefore('.') } + values.keys + listOf("cpp")).distinct()
        for (f in fields) {
            if (f == "cpp") {
                if (!present(raw, "build", "cpp")) continue
                val inner = (order.filter { it.startsWith("cpp.") }.map { it.removePrefix("cpp.") } + cppValues.keys).distinct()
                    .mapNotNull { k -> cppValues[k]?.let { k to it } }
                c.before["cpp"].orEmpty().forEach { out.append("    // ").append(it).append('\n') }
                // One construction, its fields named: a `with` inside a constant's value is refused.
                out.append("    cpp = Cpp {").append(c.after["cpp"]?.let { " // $it" } ?: "").append('\n')
                inner.forEachIndexed { i, (k, v) -> line("        ", "cpp.$k", k, v, comma = i < inner.lastIndex) }
                out.append("    }\n")
                continue
            }
            values[f]?.let { line("    ", f, f, it) }
        }
        out.append("}\n")
        c.tail.forEach { out.append("// ").append(it).append('\n') }
        return out.toString()
    }

    /** [s] as a Kira string literal. */
    fun str(s: String): String = ProjectKira.quote(s)

    private fun list(xs: List<String>): String = if (xs.isEmpty()) "List<Str> { }" else "@listOf(" + xs.joinToString(", ") { str(it) } + ")"

    /** Where a yaml line's `#` comment starts (outside quotes, at the start or after a space), or its end. */
    private fun commentStart(line: String): Int {
        var quote: Char? = null
        for ((i, ch) in line.withIndex()) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return i
            }
        }
        return line.length
    }
}
