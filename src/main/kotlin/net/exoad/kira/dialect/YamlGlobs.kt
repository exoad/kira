package net.exoad.kira.dialect

import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.kim.SourceGlob
import java.io.File
import java.nio.file.Path

/**
 * A kira.yaml's module globs (build.cpp's headerOnly, freestanding and the keys of namespaces)
 * from a module header's spelling (`firmware:lib.**`) to the dotted name the file is now
 * (`bibo.firmware.lib.**`), each proved by the files it names: the same against the old
 * headers before as against the new names after.
 */
object YamlGlobs {
    /** One module: its file, its header's URI and the URI its project and path give it now. */
    class Module(val file: String, val oldUri: String, val newUri: String)

    /** The yaml's new text, each glob's rewrite, and what could not be proved or was not. */
    class Result(val text: String, val rewrites: List<Pair<String, String>>, val problems: List<String>, val notes: List<String>)

    /** `kira-dialect yaml [--check] PATH...`: each kira.yaml under PATH, by its project's module headers. */
    /** Read as text from before stage 5 is: an import's pub names bare, as `use` had them. */
    fun convertAll(paths: List<String>, check: Boolean): Int = DialectGates.aliasOnlyImports(false) { convertAllAsWritten(paths, check) }

    private fun convertAllAsWritten(paths: List<String>, check: Boolean): Int {
        val yamls = paths.flatMap { p ->
            File(p).walkTopDown().onEnter { it.name != "build" && it.name != ".git" }.filter { it.isFile && it.name == "kira.yaml" }.toList()
        }.distinct().sorted()
        var bad = 0
        var changed = 0
        for (y in yamls) {
            val manifest = runCatching { ManifestLoader.loadFromPath(y.toPath()) }.getOrNull() ?: continue
            val root = y.absoluteFile.parentFile.toPath()
            val srcRoot = root.resolve(manifest.srcDir).normalize()
            val modules = DependencyResolver.resolveProjectSources(manifest, root).mapNotNull { f ->
                val old = header(File(f).readText()) ?: return@mapNotNull null
                Module(f, old, ModuleNames.project(manifest.project.name, srcRoot, Path.of(f)))
            }
            val r = rewrite(y.readText(), manifest, srcRoot, modules)
            r.notes.forEach { println("NOTE $y: $it") }
            r.problems.forEach { println("PROBLEM $y: $it") }
            bad += r.problems.size
            if (r.rewrites.isNotEmpty()) {
                changed++
                r.rewrites.forEach { (o, n) -> println("GLOB $y: '$o' is '$n'") }
                if (!check) y.writeText(r.text)
            }
        }
        println("kira-dialect yaml: ${yamls.size} kira.yaml, $changed ${if (check) "would change" else "changed"}, $bad globs not rewritten")
        return if (bad > 0 || (check && changed > 0)) 1 else 0
    }

    private val headerLine = Regex("""(?m)^\s*module\s+"([^"]+)"""")

    /** The URI a file's old `module "a:b.c"` line gives it; null for a file with none. */
    fun header(text: String): String? = headerLine.find(text)?.groupValues?.get(1)

    /** [text] (a kira.yaml) with each old-spelled glob of [manifest] in its dotted spelling, proved on [modules]. */
    fun rewrite(text: String, manifest: ProjectManifest, srcRoot: Path, modules: List<Module>): Result {
        val cpp = manifest.build.cpp
        val globs = (cpp.headerOnly + cpp.freestanding + cpp.namespaces.keys).distinct()
        val rewrites = mutableListOf<Pair<String, String>>()
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (old in globs) {
            if (!old.contains(':')) continue
            val before = modules.filter { SourceGlob.matchesUri(old, it.oldUri) }.map { it.file }.toSet()
            val mapping = mappingFor(old, before, modules, manifest, srcRoot)
            if (mapping == null) {
                problems += "'$old': no one project-and-path prefix gives its modules their new names"
                continue
            }
            val new = (mapping.second + segments(ModuleNames.dotted(old)).drop(mapping.first.size)).joinToString(".")
            val after = modules.filter { SourceGlob.matchesUri(new, it.newUri) }.map { it.file }.toSet()
            if (before != after) {
                problems += "'$old' names ${before.size} modules and '$new' ${after.size}, not the same ones"
                continue
            }
            if (before.isEmpty()) notes += "'$old' names no module, before or after ('$new')"
            rewrites += old to new
        }
        return Result(edit(text, rewrites), rewrites, problems, notes)
    }

    /**
     * The (old prefix, new prefix) of segments that turns [old]'s modules' names into their new
     * ones, the same for each; for a glob naming none, the one the project's other modules use,
     * else the project's name before a first segment that is a directory under [srcRoot].
     */
    private fun mappingFor(old: String, named: Set<String>, modules: List<Module>, manifest: ProjectManifest, srcRoot: Path): Pair<List<String>, List<String>>? {
        val pattern = segments(ModuleNames.dotted(old))
        fun fits(m: Pair<List<String>, List<String>>) = pattern.size >= m.first.size && pattern.take(m.first.size) == m.first
        val own = modules.filter { it.file in named }.map(::mapping).distinct()
        if (own.isNotEmpty()) return own.singleOrNull()?.takeIf(::fits)
        val other = modules.map(::mapping).distinct().filter(::fits)
        if (other.isNotEmpty()) return other.maxBy { it.first.size }.takeIf { m -> other.none { it != m && it.first.size == m.first.size } }
        if (modules.isEmpty() && pattern.isNotEmpty() && srcRoot.resolve(pattern.first()).toFile().isDirectory) {
            return emptyList<String>() to listOf(manifest.project.name)
        }
        return null
    }

    /** What a module's old dotted name gives up, and its new one has, before the tail they share. */
    private fun mapping(m: Module): Pair<List<String>, List<String>> {
        val o = segments(ModuleNames.dotted(m.oldUri))
        val n = segments(ModuleNames.dotted(m.newUri))
        var k = 0
        while (k < o.size && k < n.size && o[o.size - 1 - k] == n[n.size - 1 - k]) k++
        return o.dropLast(k) to n.dropLast(k)
    }

    /** A dotted name or glob by its segments, a `{a,b}` group kept whole. */
    private fun segments(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '{' -> { depth++; cur.append(c) }
                c == '}' -> { depth--; cur.append(c) }
                c == '.' && depth == 0 -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    /** [text] with each glob replaced where it is written, quoted or bare, outside comments. */
    private fun edit(text: String, rewrites: List<Pair<String, String>>): String {
        if (rewrites.isEmpty()) return text
        return text.split("\n").joinToString("\n") { line ->
            val cut = commentStart(line)
            var code = line.substring(0, cut)
            for ((o, n) in rewrites) {
                code = code.replace("\"$o\"", "\"$n\"").replace("'$o'", "'$n'")
                code = Regex("(?<=^|[\\s\\[,{])" + Regex.escape(o) + "(?=[\\s\\],}]|:\\s|:$|$)").replace(code, Regex.escapeReplacement(n))
            }
            code + line.substring(cut)
        }
    }

    /** Where a line's `#` comment starts (outside quotes, after a space or at the start), or its end. */
    private fun commentStart(line: String): Int {
        var quote: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return i
            }
        }
        return line.length
    }
}
