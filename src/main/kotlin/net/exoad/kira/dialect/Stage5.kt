package net.exoad.kira.dialect

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.QualifiedIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ProjectFiles
import net.exoad.kira.kim.ProjectKira
import java.io.File

/**
 * `kira-dialect stage5 [--check] [--keep-yaml] PATH...`: stage 3's text to stage 5's. Each bare
 * name a file took from an imported module gets a member alias (`a.b.Decoder as Decoder`), or is
 * written qualified where it is also a module alias, proved by the typed tree; each kira.yaml
 * becomes the project.kira beside it, proved field by field.
 */
object Stage5 {
    /** How many member aliases the last run added, and how many uses it wrote qualified. */
    var aliases = 0
    var qualifiedUses = 0

    fun convertAll(paths: List<String>, check: Boolean, keepYaml: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && it.extension == "kira" && it.name != ProjectKira.FILE }.toList() }.distinct().sorted()
        var changed = 0
        var proved = 0
        var bad = 0
        var hands = 0
        aliases = 0
        qualifiedUses = 0
        for ((group, members) in files.groupBy { DialectMain.newUriOf(it).second }) {
            val r = aliasGroup(File(group), members)
            r.hand.forEach { println("HAND $it") }
            r.problems.forEach { println("NOT-PROVED $it") }
            hands += r.hand.size
            bad += r.problems.size
            proved += members.size - r.problems.size
            for ((f, text) in r.texts) {
                changed++
                val q = r.qualified[f] ?: 0
                println("${if (check) "WOULD-CHANGE" else "CHANGED"} $f: ${r.added[f]} member alias${if (r.added[f] == 1) "" else "es"}${if (q > 0) ", $q uses written qualified" else ""}")
                if (!check) f.writeText(text)
            }
        }
        val (projects, projectBad) = projectFiles(paths, check, keepYaml)
        bad += projectBad
        println("kira-dialect stage5: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"} ($aliases member aliases, $qualifiedUses uses written qualified), " +
            "$projects kira.yaml ${if (check) "would become" else "became"} project.kira, $hands hand fixes, $bad not converted")
        return if (bad > 0 || hands > 0 || (check && (changed > 0 || projects > 0))) 1 else 0
    }

    /** One group's member aliases: each changed file's new text, how many it gained, what is left by hand, what did not prove. */
    class Aliased(val texts: Map<File, String>, val added: Map<File, Int>, val hand: List<String>, val problems: List<String>, val qualified: Map<File, Int> = emptyMap())

    /**
     * The group's files with a member alias for each bare name the old rule (`use`) found in an
     * imported project module: heard on the typer's lookups, so a local or a member of the same name
     * is never mistaken for one. A typer that does not run (no file of the group parses) adds none.
     */
    fun aliasGroup(group: File, members: List<File>): Aliased {
        val paths = members.map { ProjectUnits.canonical(it.path) }.toSet()
        val heard = LinkedHashMap<ModuleSymbol, MutableMap<String, ModuleGraph.Lookup>>()
        val old = DialectGates.aliasOnlyImports(false) { ProjectUnits.load(group, literals = false, extra = members.map { it.path }) }
        ModuleGraph.importTierHits = { m, name, answer -> heard.getOrPut(m) { linkedMapOf() }.putIfAbsent(name, answer) }
        val p1 = try {
            DialectGates.aliasOnlyImports(false) { KiraTyper.run(old.unit, TyperMode.STRICT) }
        } finally {
            ModuleGraph.importTierHits = null
        }
        val texts = LinkedHashMap<File, String>()
        val added = LinkedHashMap<File, Int>()
        val qualifiedUsesOf = LinkedHashMap<File, Int>()
        val inserted = HashMap<String, List<Int>>()
        val qualifiedLines = HashMap<String, Set<Int>>()
        val hand = mutableListOf<String>()
        for ((m, names) in heard) {
            val path = ProjectUnits.canonical(m.source.file)
            if (path !in paths) continue
            val file = members.first { ProjectUnits.canonical(it.path) == path }
            // A file the old rule already refuses is typed by no target (a C example, a test's wrong program).
            if (errors(p1, path).isNotEmpty()) {
                println("NOTE $file: it does not type under the old rule either, so it is left as it is")
                continue
            }
            val uses = m.source.ast.statements.filterIsInstance<UseStatement>()
            val taken = uses.mapNotNull { it.alias?.value }.toSet()
            val lines = mutableMapOf<Int, MutableList<String>>()
            // (line, column, alias) of each use written qualified: its name is a module alias here too.
            val qualify = mutableListOf<Triple<Int, Int, String>>()
            for ((name, answer) in names) {
                val found = answer as? ModuleGraph.Lookup.Found
                if (found == null) {
                    hand += "$file: '$name' is ${if (answer is ModuleGraph.Lookup.Ambiguous) "exported by more than one import" else "not pub in the import that has it"}: name it by its module"
                    continue
                }
                // A nested type's name (Outer_Inner) is reached through its outer, whatever reaches that.
                if ('_' in name && name.any { it.isLowerCase() }) continue
                // The stdlib is ambient: a name only one stdlib module has means the same bare.
                if (found.from.isStdlib && p1.graph.ambient().filter { it !== m && it.members[name]?.let(ModuleGraph::isPub) == true }.singleOrNull() === found.from) continue
                val use = uses.firstOrNull { it.member == null && it.moduleUri == found.from.uri && it.alias != null }
                val at = use?.let { m.source.astOrigins[it] }
                if (use == null || at == null) {
                    hand += "$file: '$name' comes from ${found.from.uri}, which no import line here names"
                    continue
                }
                if (name in taken) {
                    val alias = use.alias!!.value
                    val places = usesOf(m, name, found, p1)
                    if (places == null) {
                        hand += "$file:${at.lineNumber}: '$name' is already an alias here, so `${use.uri.value}.$name as $name` cannot be added: write `$alias.$name` where it is used"
                    } else {
                        places.forEach { (l, c) -> qualify += Triple(l, c, alias) }
                    }
                    continue
                }
                lines.getOrPut(at.lineNumber) { mutableListOf() } += "${use.uri.value}.$name as $name"
            }
            if (lines.isEmpty() && qualify.isEmpty()) continue
            val text = file.readText()
            // Rows by "\n", as the lexer counts lines (a file can mix CRLF and LF); a new row takes the file's CR.
            val cr = if (text.contains("\r\n")) "\r" else ""
            val rows = text.split("\n").toMutableList()
            for ((line, at) in qualify.groupBy { it.first }) {
                for ((_, col, alias) in at.sortedByDescending { it.second }) {
                    rows[line - 1] = rows[line - 1].substring(0, col - 1) + "$alias." + rows[line - 1].substring(col - 1)
                }
            }
            val shifts = mutableListOf<Int>()
            var count = 0
            for ((line, adds) in lines.entries.sortedByDescending { it.key }) {
                val sorted = adds.distinct().sorted()
                rows.addAll(line, sorted.map { it + cr })
                count += sorted.size
            }
            // The new text's inserted line numbers, for the proof's dump.
            var offset = 0
            for ((line, adds) in lines.entries.sortedBy { it.key }) {
                val n = adds.distinct().size
                for (i in 1..n) shifts += line + offset + i
                offset += n
            }
            texts[file] = rows.joinToString("\n")
            added[file] = count
            inserted[path] = shifts
            qualifiedLines[path] = qualify.map { it.first }.toSet()
            qualifiedUsesOf[file] = qualify.size
        }
        if (texts.isEmpty()) return Aliased(emptyMap(), emptyMap(), hand, emptyList())
        val problems = proveAliases(group, members, p1, old, texts, inserted, qualifiedLines)
        problems.keys.forEach { f -> texts.remove(f); added.remove(f); qualifiedUsesOf.remove(f) }
        aliases += added.values.sum()
        qualifiedUses += qualifiedUsesOf.values.sum()
        return Aliased(texts, added, hand, problems.map { (f, why) -> "$f: $why" }, qualifiedUsesOf)
    }

    /**
     * The proof: the group typed again, alias-only, with the new texts. Each changed file keeps its
     * errors (by code and count) and its typed tree: every expression's type, call, member and
     * symbol, line for line, the added lines aside.
     */
    private fun proveAliases(
        group: File, members: List<File>, p1: TypedProgram, old: ProjectUnits.Loaded,
        texts: Map<File, String>, inserted: Map<String, List<Int>>, qualifiedLines: Map<String, Set<Int>>,
    ): Map<File, String> {
        val overlay = texts.entries.associate { (f, t) -> ProjectUnits.canonical(f.path) to t }
        val again = ProjectUnits.load(group, literals = false, overlay, members.map { it.path })
        val p2 = DialectGates.aliasOnlyImports(true) { KiraTyper.run(again.unit, TyperMode.STRICT) }
        val bad = LinkedHashMap<File, String>()
        for (f in texts.keys) {
            val path = ProjectUnits.canonical(f.path)
            val before = old.sources[path]
            val after = again.sources[path]
            if (before == null || after == null) {
                bad[f] = "the new text does not parse in its group: ${again.failures[path]}"
                continue
            }
            val e1 = errors(p1, path)
            val e2 = errors(p2, path)
            if (e1 != e2) {
                val first = p2.diagnostics.firstOrNull { it.isError && it.source?.file?.let(ProjectUnits::canonical) == path }
                bad[f] = "its errors differ: $e1 against $e2" + (first?.let { " (${it.render()})" } ?: "")
                continue
            }
            val shifts = inserted[path].orEmpty().toSet()
            val qualified = qualifiedLines[path].orEmpty()
            val (q1, d1) = TypedModelDumper.dump(p1, before).lines().partition { lineOf(it) in qualified }
            val (q2, d2) = TypedModelDumper.dump(p2, after).lines().mapNotNull { l -> remap(l, shifts) }.partition { lineOf(it) in qualified }
            if (d1 != d2) {
                val i = d1.indices.firstOrNull { it >= d2.size || d1[it] != d2[it] } ?: d1.size
                bad[f] = "its typed tree differs at '${d1.getOrNull(i)}' against '${d2.getOrNull(i)}'"
                continue
            }
            // A line a name was qualified on: the same types, calls and symbols, the module alias's own aside.
            val f1 = q1.map(::facts).filter(::notCallee).sorted()
            val f2 = q2.map(::facts).filter(::notCallee).sorted()
            if (f1 != f2) bad[f] = "its typed tree differs on a line written qualified: $f1 against $f2"
        }
        return bad
    }

    /**
     * Where [name] (found as [found]) is used bare in [m]: each call of it and each value read of
     * it, as (line, column); null when one has no place to write the qualifier at.
     */
    private fun usesOf(m: ModuleSymbol, name: String, found: ModuleGraph.Lookup.Found, p: TypedProgram): List<Pair<Int, Int>>? {
        val out = LinkedHashSet<Pair<Int, Int>>()
        var unplaced = false
        fun plain(e: Any?): Identifier? = (e as? Identifier)?.takeIf { it.value == name && it !is QualifiedIdentifier && it !is IntrinsicExpr }
        AstTree.walk(m.source.ast) { n ->
            val id = when {
                n is FunctionCallExpr && p.model.calls[n]?.fn === found.symbol -> plain(n.name)
                n is Identifier && p.model.refs[n] === found.symbol -> plain(n)
                else -> null
            } ?: return@walk
            val pos = m.source.astOrigins[id]
            if (pos == null) unplaced = true else out += pos.lineNumber to pos.column
        }
        return if (unplaced || out.isEmpty()) null else out.toList()
    }

    /** A dump line's source line. */
    private fun lineOf(line: String): Int? = Regex("""^[^:]+:(\d+):""").find(line)?.groupValues?.get(1)?.toInt()

    /** Not the bare line of a call's name, which old text dumps as its symbol and new text as the module's member. */
    private fun notCallee(facts: String): Boolean = !facts.startsWith("?  sym=") && !facts.startsWith("?  member=Module(")

    /** A dump line's type and facts, its place and text aside. */
    private fun facts(line: String): String = line.split("  ").drop(2).joinToString("  ")

    /** A dump line of the new text with its line number as the old text's; null for a line on an added alias. */
    private fun remap(line: String, inserted: Set<Int>): String? {
        // A string may hold a line separator (U+2028), which a plain `.` stops at.
        val m = Regex("""^([^:]+):(\d+):(\d+)(.*)$""", RegexOption.DOT_MATCHES_ALL).find(line) ?: return line
        val n = m.groupValues[2].toInt()
        if (n in inserted) return null
        val shifted = n - inserted.count { it < n }
        return "${m.groupValues[1]}:$shifted:${m.groupValues[3]}${m.groupValues[4]}"
    }

    private fun errors(p: TypedProgram, path: String): Map<String, Int> =
        p.diagnostics.filter { it.isError && it.source?.file?.let(ProjectUnits::canonical) == path }.groupingBy { it.code }.eachCount()

    /** Each kira.yaml under [paths] (build and .git aside) as project.kira; (how many, how many not proved). */
    fun projectFiles(paths: List<String>, check: Boolean, keepYaml: Boolean): Pair<Int, Int> {
        val yamls = paths.flatMap { p ->
            File(p).walkTopDown().onEnter { it.name != "build" && it.name != ".git" }.filter { it.isFile && it.name == ProjectFiles.YAML }.toList()
        }.distinct().sorted()
        var done = 0
        var bad = 0
        for (y in yamls) {
            val target = File(y.parentFile, ProjectKira.FILE)
            if (target.isFile) {
                println("NOTE $y: ${ProjectKira.FILE} is already beside it; left as it is")
                continue
            }
            val r = ProjectYaml.convert(y.readText())
            if (r.text == null) {
                bad++
                println("NOT-PROVED $y: ${r.problem}")
                continue
            }
            // It types as any code does, against the stdlib it names (when that is there to read).
            val root = y.absoluteFile.parentFile.toPath()
            val stdlib = DependencyResolver.resolveDependencySources(ProjectKira.parse(r.text), root)
            if (stdlib.isEmpty()) {
                println("NOTE $y: its stdlib is not there to type ${ProjectKira.FILE} with; proved field by field only")
            } else {
                val found = ProjectKira.check(target.toPath(), stdlib, r.text)
                if (found.isNotEmpty()) {
                    bad++
                    println("NOT-PROVED $y: the ${ProjectKira.FILE} written does not type: ${found.first()}")
                    continue
                }
            }
            done++
            println("${if (check) "WOULD-WRITE" else "WROTE"} $target${if (keepYaml) "" else ", ${ProjectFiles.YAML} removed"}")
            if (!check) {
                target.writeText(r.text)
                if (!keepYaml) y.delete()
            }
        }
        return done to bad
    }
}
