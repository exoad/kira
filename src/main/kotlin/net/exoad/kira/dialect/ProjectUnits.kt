package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A group of .kira files (a project, the stdlib, or a folder of loose files) read as the
 * compiler reads it: its sources and dependencies in one unit, each named as the CLI names it.
 */
object ProjectUnits {
    /** The unit, each source read by its canonical path, and each one that did not parse with its error. */
    class Loaded(val unit: CompilationUnit, val sources: Map<String, SourceContext>, val failures: Map<String, String>)

    /** [group]'s unit; [literals]: `[...]` literals are read too, as the converter does. */
    fun load(group: File, literals: Boolean): Loaded {
        val root = group.absoluteFile.toPath().normalize()
        val yaml = root.resolve("kira.yaml")
        val (deps, workspace, names) = when {
            Files.isRegularFile(yaml) -> {
                val m = ManifestLoader.loadFromPath(yaml)
                val deps = DependencyResolver.resolveDependencySources(m, root)
                val ws = DependencyResolver.resolveProjectSources(m, root)
                Triple(deps, ws, ModuleNames.assign(m, root, deps, ws))
            }
            Files.isRegularFile(root.resolve("core.kira")) -> {
                val ws = kiraFiles(root)
                Triple(emptyList(), ws, ws.associate { canonical(it) to ModuleNames.stdlib(it) })
            }
            else -> {
                val deps = kiraFiles(Path.of("kira").toAbsolutePath().normalize())
                val ws = root.toFile().listFiles().orEmpty().filter { it.isFile && it.extension == "kira" }.map { it.path }.sorted()
                Triple(deps, ws, ModuleNames.assign(null, root, deps, ws))
            }
        }
        val unit = CompilationUnit(bootstrap = false)
        val sources = LinkedHashMap<String, SourceContext>()
        val failures = LinkedHashMap<String, String>()
        for (f in (deps + workspace).distinctBy { canonical(it) }) {
            val path = canonical(f)
            val (ctx, error) = read(unit, path, File(f).readText(), names[path], literals)
            if (ctx != null) sources[path] = ctx else failures[path] = error ?: "unreadable"
        }
        return Loaded(unit, sources, failures)
    }

    /** [text] as the source [path] of [unit], module [uri], parsed: its context, or the parse error. */
    fun read(unit: CompilationUnit, path: String, text: String, uri: String?, literals: Boolean): Pair<SourceContext?, String?> = try {
        val processed = KiraPreprocessor(text).process().processedContent
        val bare = unit.addSource(path, processed, emptyList(), uri)
        val ctx = unit.addSource(path, processed, KiraLexer(bare).tokenize())
        KiraParser(ctx, literals).parse()
        ctx to null
    } catch (e: DiagnosticsException) {
        null to ((e.message.lines().firstOrNull { it.isNotBlank() } ?: e.toString()) + " at ${e.location}")
    } catch (e: Exception) {
        null to (e.message ?: e.toString())
    }

    private fun kiraFiles(dir: Path): List<String> =
        if (!Files.isDirectory(dir)) emptyList() else dir.toFile().walkTopDown().filter { it.isFile && it.extension == "kira" }.map { it.path }.sorted().toList()

    fun canonical(f: String): String = runCatching { File(f).canonicalPath }.getOrDefault(f)
}
