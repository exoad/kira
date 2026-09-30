package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the CLI's C++ path (manifest, semantic pass, typer, the real layout, every part)
 * over one golden case, the way CppGoldenEmitTest does, and hands back what each module
 * emitted by URI, so the FFI tests can look at one module of a case whose other modules
 * still wait for a part (forward's program body is W2.3's).
 */
object FfiGoldenSupport {
    val corpus: File = File("src/test/resources/cpp-golden")

    class CaseEmission(val root: Path, val modules: Map<String, EmittedModule>, val sourcePaths: Map<String, Path>, val layout: CppModuleLayout) {
        fun header(uri: String): String {
            val m = modules[uri] ?: fail("module $uri was not emitted; got ${modules.keys}")
            val errors = m.diagnostics.filter { it.isError }
            if (errors.isNotEmpty()) {
                fail("module $uri has errors:\n${errors.joinToString("\n") { it.render() }}\n--- header ---\n${m.header}")
            }
            return CppWriter.normalize(m.header)
        }

        /** The `expected/` file of [uri]'s header, LF-normalized. */
        fun expectedHeader(uri: String): String {
            val files = layout.filesFor(uri, sourcePaths[uri] ?: fail("no source for $uri"))
            val expected = root.resolve("expected").resolve(root.relativize(files.header))
            assertTrue(Files.isRegularFile(expected), "no expected header at $expected")
            return String(CppWriter.lfBytes(Files.readAllBytes(expected)), Charsets.UTF_8)
        }
    }

    fun emit(caseName: String): CaseEmission {
        val caseRoot = File(corpus, caseName).toPath().toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(caseRoot), "no golden case at $caseRoot")
        val manifest = ManifestLoader.loadFromPath(caseRoot.resolve("kira.yaml"))
        val issues = ManifestValidator.validate(manifest, caseRoot)
        assertTrue(issues.isEmpty(), "$caseName: kira.yaml issues: ${issues.joinToString { "${it.field}: ${it.message}" }}")
        val workspace = DependencyResolver.resolveProjectSources(manifest, caseRoot)
        assertTrue(workspace.isNotEmpty(), "$caseName: no Kira sources under src/")
        val unit = CompilationUnit()
        workspace.sorted().forEach { file ->
            val text = File(file).readText()
            val processed = KiraPreprocessor(text).process().processedContent
            val canonical = File(file).canonicalPath
            var ctx = unit.addSource(canonical, processed, emptyList())
            val tokens = KiraLexer(ctx).tokenize()
            ctx = unit.addSource(canonical, ctx.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        val semantic = KiraSemanticAnalyzer(unit).validateAST()
        assertTrue(semantic.diagnostics.isEmpty(), "$caseName: the semantic pass refused the case:\n${semantic.diagnostics.joinToString("\n")}")

        val options = KiraCppBackend.buildOptions(manifest, null)
        val refs = unit.allSources().mapNotNull { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (uri.startsWith("(unknown)")) null else CppModuleRef(uri, Path.of(src.file))
        }
        val layout = CppModuleLayout(options, caseRoot, refs)
        val emitter = CppModuleEmitterFactory.create(unit, options)
        emitter.prepare(layout, "dev")
        val runErrors = emitter.diagnostics.filter { it.isError }
        assertTrue(runErrors.isEmpty(), "$caseName: the typer refused the case:\n${runErrors.joinToString("\n") { it.render() }}")

        val sources = unit.allSources()
            .map { runCatching { it.getModuleUri() }.getOrNull().orEmpty() to it }
            .filter { it.first.isNotEmpty() && !it.first.startsWith("(unknown)") }
            .sortedBy { it.first }
            .map { (uri, source) -> CppModuleRef(uri, Path.of(source.file)) to source }
        val modules = LinkedHashMap<String, EmittedModule>()
        val paths = LinkedHashMap<String, Path>()
        for ((ref, source, emitted) in KiraCppBackend.emitModules(emitter, sources)) {
            modules[ref.uri] = emitted
            paths[ref.uri] = Path.of(source.file)
        }
        return CaseEmission(caseRoot, modules, paths, layout)
    }
}
