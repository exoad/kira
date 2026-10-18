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
 * its words). `--check` writes nothing and exits 1 when anything would change or cannot be converted.
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
        if (args.isEmpty() || args[0] !in setOf("convert", "kt")) {
            println("usage: kira-dialect convert|kt [--check] PATH...")
            exitProcess(2)
        }
        val check = "--check" in args
        KotlinStrings.forceWrong = "--force-wrong" in args
        val paths = args.drop(1).filter { it != "--check" && it != "--force-wrong" }
        val code = if (args[0] == "convert") convertKira(paths, check) else KotlinStrings.convertAll(paths, check)
        exitProcess(code)
    }

    /** One .kira file: its old text, its module's old URI and the new one. */
    class Unit(val file: File, val text: String, val old: SourceContext?, val oldUri: String?, val newUri: String, val group: String)

    fun convertKira(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        val units = files.map { f ->
            val text = f.readText()
            val (ctx, _) = DialectConverter.parseOld(text, f.path)
            val oldUri = ctx?.ast?.statements?.firstNotNullOfOrNull { (it as? Statement)?.expr as? ModuleDecl }?.uri?.value
            val (uri, group) = newUriOf(f)
            Unit(f, text, ctx, oldUri, uri, group)
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
        println("kira-dialect: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $bad not converted")
        return if (bad > 0 || (check && changed > 0)) 1 else 0
    }

    /** Each type's role after DialectLink, per group, against the old dialect's keyword. */
    private fun roles(converted: List<Pair<Unit, SourceContext>>): Int {
        var bad = 0
        for ((group, members) in converted.groupBy { it.first.group }) {
            val cu = CompilationUnit()
            members.forEach { (u, nc) -> cu.addSource(u.file.path, nc.content, nc.tokens, u.newUri).also { it.ast = nc.ast; it.astOrigins = nc.astOrigins; it.astIntrinsicMarked = nc.astIntrinsicMarked; it.astIntrinsicInvocations = nc.astIntrinsicInvocations } }
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

    private fun kinds(s: SourceContext): Map<String, String> = s.ast.statements.mapNotNull { st ->
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
