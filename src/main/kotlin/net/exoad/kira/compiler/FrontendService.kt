package net.exoad.kira.compiler

import net.exoad.kira.Public
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared frontend pipeline used by the CLI and the language server.
 *
 * Parses every source (stdlib + project, with optional in-memory overlays),
 * then runs the semantic analyzer. Never emits backend code and never
 * process-exits on failure -- callers receive a [FrontendResult].
 */
object FrontendService {

    data class Diagnostic(
        val file: String,
        val message: String,
        val tag: String,
        val start: SourcePosition,
        val end: SourcePosition,
        val severity: Severity = Severity.ERROR,
    ) {
        enum class Severity { ERROR, WARNING, INFORMATION, HINT }
    }

    data class FrontendResult(
        val compilationUnit: CompilationUnit?,
        val diagnostics: List<Diagnostic>,
        val projectRoot: Path?,
        val manifest: ProjectManifest?,
    ) {
        val isOk: Boolean get() = diagnostics.none { it.severity == Diagnostic.Severity.ERROR }
    }

    /**
     * Compile a project rooted at [projectRoot] (directory containing `kira.yaml`
     * or a folder of `.kira` files). [overlays] maps absolute/canonical paths to
     * unsaved buffer text -- used by the language server for open documents.
     */
    fun compileProject(
        projectRoot: Path,
        overlays: Map<String, String> = emptyMap(),
    ): FrontendResult {
        val root = projectRoot.toAbsolutePath().normalize()
        val diagnostics = mutableListOf<Diagnostic>()

        val yamlPath = root.resolve("kira.yaml")
        val manifest: ProjectManifest? = if (Files.exists(yamlPath)) {
            try {
                val loaded = ManifestLoader.loadFromPath(yamlPath)
                val issues = ManifestValidator.validate(loaded, root)
                issues.forEach { issue ->
                    diagnostics += Diagnostic(
                        file = yamlPath.toString(),
                        message = "Manifest [${issue.field}]: ${issue.message}",
                        tag = "Manifest",
                        start = SourcePosition(1, 1),
                        end = SourcePosition(1, 1),
                    )
                }
                if (issues.isNotEmpty()) {
                    return FrontendResult(null, diagnostics, root, null)
                }
                loaded
            } catch (e: Exception) {
                diagnostics += Diagnostic(
                    file = yamlPath.toString(),
                    message = "Failed to load kira.yaml: ${e.message}",
                    tag = "Manifest",
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
                return FrontendResult(null, diagnostics, root, null)
            }
        } else {
            null
        }

        val stdlib = DependencyResolver.resolveDependencySources(manifest, root).toMutableList()
        if (stdlib.isEmpty()) {
            // Fall back relative to project, then process cwd.
            val local = root.resolve("kira")
            if (Files.isDirectory(local)) {
                stdlib.addAll(scanKira(local))
            } else {
                stdlib.addAll(Public.Builtin.discoverLegacyKiraFolder().toList())
            }
        }
        Public.Builtin.intrinsicalStandardLibrarySources =
            stdlib.distinct().sorted().toTypedArray()

        val workspace = DependencyResolver.resolveProjectSources(manifest, root)
        if (workspace.isEmpty() && stdlib.isEmpty()) {
            diagnostics += Diagnostic(
                file = root.toString(),
                message = "No .kira sources found. Add files under src/ or set srcDir in kira.yaml.",
                tag = "Frontend",
                start = SourcePosition(1, 1),
                end = SourcePosition(1, 1),
            )
            return FrontendResult(null, diagnostics, root, manifest)
        }

        val sources = (stdlib + workspace).distinct().sorted()
        return compileSources(sources, overlays, root, manifest, diagnostics)
    }

    /**
     * Compile an explicit list of source paths (absolute). Overlays replace
     * disk content when present. Useful for single-file smoke checks.
     */
    fun compileSources(
        sourcePaths: List<String>,
        overlays: Map<String, String> = emptyMap(),
        projectRoot: Path? = null,
        manifest: ProjectManifest? = null,
        seedDiagnostics: MutableList<Diagnostic> = mutableListOf(),
    ): FrontendResult {
        val diagnostics = seedDiagnostics
        val compilationUnit = CompilationUnit()
        val overlayByCanonical = overlays.mapKeys { canonicalize(it.key) }

        for (sourcePath in sourcePaths) {
            val canonical = canonicalize(sourcePath)
            val text = overlayByCanonical[canonical] ?: readFileOrNull(canonical)
            if (text == null) {
                diagnostics += Diagnostic(
                    file = canonical,
                    message = "Source file not found: $canonical",
                    tag = "Frontend",
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
                continue
            }
            try {
                parseOne(compilationUnit, canonical, text)
            } catch (e: DiagnosticsException) {
                diagnostics += fromException(e)
            } catch (e: IllegalStateException) {
                diagnostics += Diagnostic(
                    file = canonical,
                    message = e.message ?: e.toString(),
                    tag = "Panic",
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
            } catch (e: Exception) {
                diagnostics += Diagnostic(
                    file = canonical,
                    message = e.message ?: e.toString(),
                    tag = e.javaClass.simpleName,
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
            }
        }

        // Also parse overlay-only files that are not on the disk source list
        // (e.g. a brand-new unsaved buffer under the project).
        val known = sourcePaths.map { canonicalize(it) }.toSet()
        for ((path, text) in overlayByCanonical) {
            if (path in known) continue
            if (!path.endsWith(".kira")) continue
            try {
                parseOne(compilationUnit, path, text)
            } catch (e: DiagnosticsException) {
                diagnostics += fromException(e)
            } catch (e: IllegalStateException) {
                diagnostics += Diagnostic(
                    file = path,
                    message = e.message ?: e.toString(),
                    tag = "Panic",
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
            } catch (e: Exception) {
                diagnostics += Diagnostic(
                    file = path,
                    message = e.message ?: e.toString(),
                    tag = e.javaClass.simpleName,
                    start = SourcePosition(1, 1),
                    end = SourcePosition(1, 1),
                )
            }
        }

        val semantic: SemanticAnalyzerResults? = try {
            KiraSemanticAnalyzer(compilationUnit).validateAST()
        } catch (e: DiagnosticsException) {
            diagnostics += fromException(e)
            null
        } catch (e: Exception) {
            diagnostics += Diagnostic(
                file = projectRoot?.toString() ?: "",
                message = "Semantic analysis failed: ${e.message}",
                tag = "Semantic",
                start = SourcePosition(1, 1),
                end = SourcePosition(1, 1),
            )
            null
        }

        semantic?.diagnostics?.forEach { diagnostics += fromException(it) }

        return FrontendResult(compilationUnit, diagnostics.toList(), projectRoot, manifest)
    }

    private fun parseOne(cu: CompilationUnit, path: String, text: String) {
        val processed = KiraPreprocessor(text).process().processedContent
        var ctx = cu.addSource(path, processed, emptyList())
        val tokens = KiraLexer(ctx).tokenize()
        ctx = cu.addSource(path, ctx.content, tokens)
        KiraSourceParsers.from(ctx).parse()
    }

    fun fromException(e: DiagnosticsException): Diagnostic {
        val start = e.location ?: SourcePosition(1, 1)
        val endCol = if (e.location != null) {
            (e.location.column + e.selectorLength.coerceAtLeast(1)).coerceAtLeast(e.location.column + 1)
        } else {
            1
        }
        val end = SourcePosition(start.lineNumber.coerceAtLeast(1), endCol.coerceAtLeast(1))
        return Diagnostic(
            file = e.context.file,
            message = e.message,
            tag = e.tag,
            start = SourcePosition(start.lineNumber.coerceAtLeast(1), start.column.coerceAtLeast(1)),
            end = end,
        )
    }

    fun findProjectRoot(start: Path): Path {
        var cur = start.toAbsolutePath().normalize()
        if (Files.isRegularFile(cur)) {
            cur = cur.parent
        }
        var walk: Path? = cur
        while (walk != null) {
            if (Files.exists(walk.resolve("kira.yaml"))) {
                return walk
            }
            walk = walk.parent
        }
        return cur
    }

    private fun canonicalize(path: String): String {
        return try {
            File(path).canonicalPath
        } catch (_: Exception) {
            Paths.get(path).toAbsolutePath().normalize().toString()
        }
    }

    private fun readFileOrNull(path: String): String? {
        return try {
            val f = File(path)
            if (f.isFile) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun scanKira(dir: Path): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = mutableListOf<String>()
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                .forEach { out.add(it.toAbsolutePath().normalize().toString()) }
        }
        return out.sorted()
    }
}
