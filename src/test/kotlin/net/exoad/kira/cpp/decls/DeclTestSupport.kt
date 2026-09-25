package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.compiler.backend.codegen.cpp.TypedCppModuleEmitter
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.nio.file.Path
import kotlin.test.fail

/**
 * Runs the real factory over a few Kira sources laid out under a pretend project root
 * (`<root>/src/<pkg>/<path>.kira`, the `beside` layout, `lineDirectives: false` unless
 * asked), the way `kira --target cpp` would, and hands back what each module emitted.
 *
 * Every unit also holds the repository's stdlib (`./kira`), whose Kira-written bodies the
 * default parts refuse; [Emitted.module] therefore looks at the module asked for only.
 */
object DeclTestSupport {
    val root: Path = Path.of("build/tmp/cpp-decls-tests").toAbsolutePath().normalize()

    data class Src(val uri: String, val text: String) {
        val relativePath: String
            get() {
                val (pkg, dotted) = uri.split(":", limit = 2)
                return "src/$pkg/${dotted.replace('.', '/')}.kira"
            }
    }

    fun module(uri: String, body: String): Src = Src(uri, "module \"$uri\"\n\n${body.trimIndent()}\n")

    class Emitted(
        val program: TypedProgram?,
        val runDiagnostics: List<CppDiagnostic>,
        private val modules: Map<String, EmittedModule>,
        private val sources: Map<String, SourceContext>,
        val layout: CppModuleLayout,
    ) {
        fun module(uri: String): EmittedModule = modules[uri] ?: fail("module $uri was not emitted; run diagnostics:\n${render(runDiagnostics)}")

        /** The module's header, normalized as the backend writes it; fails on any error diagnostic. */
        fun header(uri: String): String {
            val m = module(uri)
            if (m.hasErrors) {
                fail("module $uri has errors:\n${render(m.diagnostics)}\n--- header so far ---\n${m.header}")
            }
            return CppWriter.normalize(m.header)
        }

        fun source(uri: String): String? {
            val m = module(uri)
            if (m.hasErrors) {
                fail("module $uri has errors:\n${render(m.diagnostics)}")
            }
            return m.source?.let { CppWriter.normalize(it) }
        }

        fun diagnostics(uri: String): List<CppDiagnostic> = module(uri).diagnostics

        fun source(uri: String, ignoreErrors: Boolean): SourceContext = sources[uri] ?: fail("no source for $uri")

        fun render(diagnostics: List<CppDiagnostic>): String = diagnostics.joinToString("\n") { it.render() }.ifEmpty { "(none)" }
    }

    fun unitOf(sources: List<Src>): Pair<CompilationUnit, Map<String, SourceContext>> {
        val unit = CompilationUnit()
        val contexts = LinkedHashMap<String, SourceContext>()
        for (s in sources) {
            val path = root.resolve(s.relativePath).toString()
            val processed = KiraPreprocessor(s.text).process().processedContent
            val bare = unit.addSource(path, processed, emptyList())
            val tokens = KiraLexer(bare).tokenize()
            val ctx = unit.addSource(path, bare.content, tokens)
            KiraSourceParsers.from(ctx).parse()
            contexts[s.uri] = ctx
        }
        return unit to contexts
    }

    /**
     * Runs the declaration emitter over one module of a freshly typed unit with a stated
     * [usage] (what the program "uses" of its enums and structs) and [parts], bypassing the
     * factory's usage scan: for the `nameOf`, `enum_values` and `operator==` rows, whose real
     * trigger is a typed body the expression typer (W2.1) fills in.
     */
    fun emitWith(
        vararg sources: Src,
        uri: String,
        usage: CppUsage = CppUsage.NONE,
        parts: CppEmitParts = CppEmitParts.standard(),
        options: CppOptions = CppOptions(lineDirectives = false),
        /** Builds the usage from the context (its symbols), since symbols are per typed program. */
        usageOf: ((CppEmitContextImpl) -> CppUsage)? = null,
    ): Pair<EmittedModule, CppEmitContextImpl> {
        val (unit, contexts) = unitOf(sources.toList())
        val program = KiraTyper.run(unit, TyperMode.STRICT, TyperOptions(options.freestanding, options.headerOnly, options.namespaces))
        if (program.hasErrors) {
            fail("the typer refused the sources:\n" + program.diagnostics.joinToString("\n") { it.render() })
        }
        val refs = unit.allSources().mapNotNull { src ->
            val u = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (u.startsWith("(unknown)")) null else CppModuleRef(u, Path.of(src.file))
        }
        val layout = CppModuleLayout(options, root, refs)
        val source = contexts[uri] ?: fail("no source for $uri")
        val module = program.moduleOf(source) ?: fail("no module symbol for $uri")
        val ctx = CppEmitContextImpl(program, options, source, layout, module, "dev", parts)
        return CppDeclEmitter(ctx, usageOf?.invoke(ctx) ?: usage).emit() to ctx
    }

    /** Emits [sources] with [options] (beside layout, no `#line` unless [options] says so) and the given parts. */
    fun emit(
        vararg sources: Src,
        options: CppOptions = CppOptions(lineDirectives = false),
        parts: CppEmitParts = CppEmitParts.standard(),
        version: String = "dev",
        includeStdlib: Boolean = false,
    ): Emitted {
        val (unit, contexts) = unitOf(sources.toList())
        val emitter = CppModuleEmitterFactory.create(unit, options, parts)
        val refs = unit.allSources().mapNotNull { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (uri.startsWith("(unknown)")) null else CppModuleRef(uri, Path.of(src.file))
        }
        val layout = CppModuleLayout(options, root, refs)
        emitter.prepare(layout, version)
        val program = (emitter as? TypedCppModuleEmitter)?.program
        val modules = LinkedHashMap<String, EmittedModule>()
        val wanted = contexts.keys.toSet()
        unit.allSources().forEach { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@forEach
            if (uri in wanted || (includeStdlib && uri.startsWith("kira:"))) {
                modules[uri] = emitter.emit(src)
            }
        }
        return Emitted(program, emitter.diagnostics, modules, contexts, layout)
    }
}
