package net.exoad.kira.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypeDiagnostic
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compiles Kira sources into a [TypedProgram] for the typer tests.
 *
 * A [CompilationUnit] loads the stdlib from `./kira` (the Gradle test working directory is
 * the repository root), so every program here sees the real `kira:*` modules.
 */
object TyperTestSupport {
    /** One source: its logical path (`app/main.kira`) and its text. */
    data class Src(val path: String, val text: String)

    /** `module "<uri>"` plus [body], at the path the URI implies. */
    fun module(uri: String, body: String): Src {
        val (pkg, dotted) = uri.split(":", limit = 2)
        val path = "$pkg/${dotted.replace('.', '/')}.kira"
        return Src(path, "module \"$uri\"\n\n${body.trimIndent()}\n")
    }

    /** Parses [sources] into one unit (plus the stdlib). A parse error fails the test. */
    fun unitOf(vararg sources: Src): CompilationUnit {
        val cu = CompilationUnit()
        for (s in sources) {
            val processed = KiraPreprocessor(s.text).process().processedContent
            val bare = cu.addSource(s.path, processed, emptyList())
            val tokens = KiraLexer(bare).tokenize()
            val ctx = cu.addSource(s.path, bare.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        return cu
    }

    fun type(
        vararg sources: Src,
        mode: TyperMode = TyperMode.STRICT,
        options: TyperOptions = TyperOptions(),
    ): TypedProgram = KiraTyper.run(unitOf(*sources), mode, options)

    /** One module's snippet, typed. */
    fun snippet(
        body: String,
        uri: String = "test:main",
        mode: TyperMode = TyperMode.STRICT,
        options: TyperOptions = TyperOptions(),
    ): TypedProgram = type(module(uri, body), mode = mode, options = options)

    /** Every `.kira` file under [dir]`/src` (a project directory such as examples/04-classes), typed. */
    fun project(dir: File, mode: TyperMode = TyperMode.STRICT, options: TyperOptions = TyperOptions()): TypedProgram {
        val src = File(dir, "src")
        require(src.isDirectory) { "no src/ in $dir" }
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kira" }.sortedBy { it.path }.toList()
        require(files.isNotEmpty()) { "no .kira files under $src" }
        val sources = files.map { Src(it.canonicalPath, it.readText()) }
        return type(*sources.toTypedArray(), mode = mode, options = options)
    }

    fun codes(program: TypedProgram): List<String> = program.diagnostics.map { it.code }

    /** Fails unless some diagnostic has [code]; returns it. */
    fun expectDiagnostic(program: TypedProgram, code: String): TypeDiagnostic {
        return program.diagnostics.firstOrNull { it.code == code }
            ?: fail("expected a $code diagnostic, got:\n${render(program)}")
    }

    fun expectNoErrors(program: TypedProgram) {
        assertTrue(!program.hasErrors, "expected no errors, got:\n${render(program)}")
    }

    fun render(program: TypedProgram): String =
        program.diagnostics.joinToString("\n") { it.render() }.ifEmpty { "(no diagnostics)" }
}
