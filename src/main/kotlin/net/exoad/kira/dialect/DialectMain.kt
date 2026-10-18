package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.DialectLink
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `kira-dialect convert [--check] PATH...`: every .kira under each PATH from the old dialect to
 * the clean one, in place, each proved by its tree; `kt [--check] PATH...` does the same for the
 * Kira in Kotlin string literals (`--force-wrong`: also the old Kira a test means to be wrong, by
 * its words); `stage2 [--check] PATH...` takes stage 1's text to stage 2's ([Stage2]). `--check`
 * writes nothing and exits 1 when anything would change or cannot be converted.
 */
object DialectMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 2 && args[0] == "show") {
            // Debugging: one file's conversion and the new parser's reading of it.
            val f = File(args[1])
            val r = DialectConverter.convert(f.readText(), f.path)
            println(r.text)
            r.problems.forEach { println("PROBLEM $it") }
            val (nc, err) = TreeProof.parseNew(r.text, f.path, newUriOf(f).first)
            println(if (nc == null) "NEW-PARSE: $err" else "parsed")
            return
        }
        if (args.isEmpty() || args[0] !in setOf("convert", "kt", "yaml", "stage2")) {
            println("usage: kira-dialect convert|kt|yaml|stage2 [--check] PATH...")
            exitProcess(2)
        }
        val check = "--check" in args
        KotlinStrings.forceWrong = "--force-wrong" in args
        val paths = args.drop(1).filter { it != "--check" && it != "--force-wrong" }
        val code = when (args[0]) {
            "convert" -> convertKira(paths, check)
            "yaml" -> YamlGlobs.convertAll(paths, check)
            "stage2" -> Stage2.convertAll(paths, check)
            else -> KotlinStrings.convertAll(paths, check)
        }
        exitProcess(code)
    }

    /** One .kira file: its old text, its module's old URI and the new one. */
    class Unit(val file: File, val text: String, val old: SourceContext?, val oldUri: String?, val newUri: String, val group: String, val oldError: String? = null)

    fun convertKira(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        val units = files.map { f ->
            val text = f.readText()
            val (ctx, err) = DialectConverter.parseOld(text, f.path)
            val oldUri = ctx?.ast?.statements?.firstNotNullOfOrNull { (it as? Statement)?.expr as? ModuleDecl }?.uri?.value
            val (uri, group) = newUriOf(f)
            Unit(f, text, ctx, oldUri, uri, group, err)
        }
        // A module's old URI names its new module within its own project (or the stdlib).
        val stdlib = units.filter { it.newUri.startsWith("${ModuleNames.STDLIB}:") && it.oldUri != null }.associate { it.oldUri!! to ModuleNames.dotted(it.newUri) }
        val renamesOf = units.groupBy { it.group }.mapValues { (_, us) ->
            DialectConverter.Renames(stdlib + us.filter { it.oldUri != null }.associate { it.oldUri!! to ModuleNames.dotted(it.newUri) })
        }
        var bad = 0
        var changed = 0
        var proved = 0
        val converted = mutableListOf<Pair<Unit, SourceContext>>()
        for (u in units) {
            if (u.old == null && DialectConverter.isRemovedForm(u.oldError)) {
                bad++
                println("PROBLEM ${u.file}: ${u.oldError}")
                continue
            }
            if (u.old == null) {
                println("SKIP ${u.file}: the old parser refuses it (already converted?)")
                continue
            }
            val renames = renamesOf.getValue(u.group)
            val r = DialectConverter.convert(u.text, u.file.path, renames)
            if (r.problems.isNotEmpty()) {
                bad++
                r.problems.forEach { println("PROBLEM ${u.file}:$it") }
                continue
            }
            val (nc, err) = TreeProof.parseNew(r.text, u.file.path, u.newUri)
            if (nc == null) {
                bad++
                println("NEW-PARSE ${u.file}: $err")
                continue
            }
            val diff = TreeProof.compare(u.old, nc, renames)
            if (diff != null) {
                bad++
                println("TREE ${u.file}: $diff")
                continue
            }
            proved++
            converted += u to nc
            if (r.text != u.text) {
                changed++
                if (!check) u.file.writeText(r.text)
            }
        }
        bad += roles(converted)
        bad += yamls(units, check)
        println("kira-dialect: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $bad not converted")
        return if (bad > 0 || (check && changed > 0)) 1 else 0
    }

    /** Each converted project's kira.yaml globs in the dotted spelling, proved on its modules' old and new names. */
    private fun yamls(units: List<Unit>, check: Boolean): Int {
        var bad = 0
        for ((group, us) in units.groupBy { it.group }) {
            val yaml = File(group, "kira.yaml")
            val manifest = yaml.takeIf { it.isFile }?.let { runCatching { ManifestLoader.loadFromPath(it.toPath()) }.getOrNull() } ?: continue
            val srcRoot = yaml.absoluteFile.parentFile.toPath().resolve(manifest.srcDir).normalize()
            val modules = us.filter { it.oldUri != null }.map { YamlGlobs.Module(it.file.path, it.oldUri!!, it.newUri) }
            val r = YamlGlobs.rewrite(yaml.readText(), manifest, srcRoot, modules)
            r.notes.forEach { println("NOTE $yaml: $it") }
            r.problems.forEach { println("PROBLEM $yaml: $it") }
            bad += r.problems.size
            r.rewrites.forEach { (o, n) -> println("GLOB $yaml: '$o' is '$n'") }
            if (r.rewrites.isNotEmpty() && !check) yaml.writeText(r.text)
        }
        return bad
    }

    /**
     * Each type's role after DialectLink against the old dialect's keyword, per project as the
     * compiler reads it: its files with its dependencies (the stdlib among them) beside them.
     */
    private fun roles(converted: List<Pair<Unit, SourceContext>>): Int {
        val byFile = converted.associateBy { canonical(it.first.file) }
        var bad = 0
        for ((group, members) in converted.groupBy { it.first.group }) {
            val cu = CompilationUnit()
            members.forEach { (u, nc) -> adopt(cu, u.file.path, nc, u.newUri) }
            val held = cu.allSources().map { canonical(File(it.file)) }.toMutableSet()
            for ((file, uri) in dependenciesOf(group)) {
                if (!held.add(canonical(File(file)))) continue
                val hit = byFile[canonical(File(file))]
                val ctx = hit?.second ?: readNew(file, uri)
                if (ctx == null) {
                    println("NOTE $file: neither parser reads it, so its types are not in $group's role check")
                    continue
                }
                adopt(cu, file, ctx, hit?.first?.newUri ?: uri)
            }
            DialectLink.link(cu)
            for ((u, _) in members) {
                val newKinds = kinds(cu.getSource(u.file.path)!!)
                val oldKinds = kinds(u.old!!)
                if (newKinds != oldKinds) {
                    bad++
                    println("ROLE ${u.file} (group $group): ${oldKinds.filter { newKinds[it.key] != it.value }.map { "${it.key} was ${it.value}, is ${newKinds[it.key]}" }}")
                }
            }
        }
        return bad
    }

    /** [nc]'s tree in [cu] as [file], module [uri]. */
    fun adopt(cu: CompilationUnit, file: String, nc: SourceContext, uri: String) {
        cu.addSource(file, nc.content, nc.tokens, uri).also {
            it.ast = nc.ast
            it.astOrigins = nc.astOrigins
            it.astIntrinsicMarked = nc.astIntrinsicMarked
            it.astIntrinsicInvocations = nc.astIntrinsicInvocations
        }
    }

    /** [file] in the clean dialect, as module [uri]: as it is, or converted in memory; null when neither parses. */
    fun readNew(file: String, uri: String): SourceContext? {
        val text = File(file).readText()
        TreeProof.parseNew(text, file, uri).first?.let { return it }
        val r = DialectConverter.convert(text, file)
        if (r.problems.isNotEmpty()) return null
        return TreeProof.parseNew(r.text, file, uri).first
    }

    /**
     * The dependency files a group's project reads, each with its module URI: its kira.yaml's
     * dependencies, or (a group with no kira.yaml, the stdlib's own aside) the stdlib under `kira/`.
     */
    fun dependenciesOf(group: String): List<Pair<String, String>> {
        val dir = File(group)
        val yaml = File(dir, "kira.yaml")
        val roots = if (yaml.isFile) {
            val m = runCatching { ManifestLoader.loadFromPath(yaml.toPath()) }.getOrNull() ?: return emptyList()
            m.dependencies.values.mapNotNull { spec -> spec.path?.let { DependencyResolver.resolvePath(dir.absoluteFile.toPath(), it) } }
        } else if (File(dir, "core.kira").isFile) {
            emptyList()
        } else {
            listOfNotNull(Path.of("kira").toAbsolutePath().normalize().takeIf { Files.isRegularFile(it.resolve("core.kira")) })
        }
        return roots.flatMap { root ->
            root.toFile().walkTopDown().filter { it.isFile && it.extension == "kira" }.map { it.path to ModuleNames.dependency(root, it.toPath()) }.toList()
        }
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    fun kinds(s: SourceContext): Map<String, String> = s.ast.statements.mapNotNull { st ->
        when (val e = (st as? Statement)?.expr) {
            is ClassDecl -> ((e.name.identifier as? Identifier)?.value ?: "?") to "class"
            is TraitDecl -> ((e.name.identifier as? Identifier)?.value ?: "?") to "trait"
            is StructDecl -> ((e.name.identifier as? Identifier)?.value ?: "?") to "struct"
            else -> null
        }
    }.toMap()

    /** The new URI of [f] and the group its roles are read in: its project, the stdlib, or its directory. */
    fun newUriOf(f: File): Pair<String, String> {
        val dir = f.absoluteFile.parentFile
        if (File(dir, "core.kira").isFile && !File(dir, "kira.yaml").isFile) {
            return ModuleNames.stdlib(f.path) to dir.path
        }
        var d: File? = dir
        while (d != null) {
            val yaml = File(d, "kira.yaml")
            if (yaml.isFile) {
                val m = runCatching { ManifestLoader.loadFromPath(yaml.toPath()) }.getOrNull()
                if (m != null) {
                    val src = d.toPath().resolve(m.srcDir).normalize()
                    if (f.absoluteFile.toPath().normalize().startsWith(src)) {
                        return ModuleNames.project(m.project.name, src, f.toPath()) to d.path
                    }
                }
                break
            }
            d = d.parentFile
        }
        return ModuleNames.loose(f.path) to dir.path
    }

    fun isDir(p: Path) = Files.isDirectory(p)
}
