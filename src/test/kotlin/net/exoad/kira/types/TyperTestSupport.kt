package net.exoad.kira.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypeDiagnostic
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File
import java.util.IdentityHashMap
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    /**
     * A module whose AST is built by hand: for syntax the parser in this branch does not
     * produce yet (struct, override, parameter defaults, `Arr<T, 32>`, `Tuple1<mut T>`). The
     * nodes carry no source positions.
     */
    fun astModule(unit: CompilationUnit, uri: String, vararg decls: ASTNode): SourceContext {
        val (pkg, dotted) = uri.split(":", limit = 2)
        val ctx = unit.addSource("$pkg/${dotted.replace('.', '/')}.kira", "", emptyList())
        ctx.astOrigins = IdentityHashMap()
        ctx.astIntrinsicMarked = IdentityHashMap()
        ctx.ast = RootASTNode(listOf(Statement(ModuleDecl(StringLiteral(uri)))) + decls.map { if (it is Statement) it else Statement(it as Expr) })
        return ctx
    }

    fun ty(name: String, vararg children: Type, mutParam: Boolean = false): Type =
        Type(Identifier(name), null, children.toList(), mutParam)

    fun param(name: String, type: Type, default: Expr? = null, mut: Boolean = false): FunctionDeclParameterExpr =
        FunctionDeclParameterExpr(Identifier(name), type, if (mut) listOf(Modifier.MUTABLE) else emptyList(), default)

    fun fn(
        name: String,
        params: List<FunctionDeclParameterExpr>,
        ret: Type,
        body: List<Statement>? = listOf(),
        modifiers: List<Modifier> = listOf(Modifier.PUBLIC),
    ): FunctionDecl = FunctionDecl(Identifier(name), FunctionDefExpr(ret, params, body), modifiers)

    fun field(name: String, type: Type, default: Expr? = null, vararg modifiers: Modifier): VariableDecl =
        VariableDecl(Identifier(name), type, default, modifiers.toList())

    /**
     * Whether this branch carries the frontend package's (W1.1) parser for the design's
     * dialect. Two independent signals are read: `pub struct` parses, and SourceContext has
     * `intrinsicInvocationsOf` (marker arguments are recorded). They must agree. A branch
     * with one and not the other is a broken merge, not an absent package, so reading this
     * fails every test that asks, with the reason.
     *
     * A test over dialect sources skips while this is false, and only then: once the parser
     * is present a source that does not parse fails the test (see [parsingDialect]).
     */
    val hasDialectParser: Boolean by lazy {
        val parses = try {
            unitOf(module("probe:dialect", "pub struct Probe { pub x: Int32 = 0 }"))
            true
        } catch (_: DiagnosticsException) {
            false
        }
        val recordsMarkerArguments = SourceContext::class.java.methods.any { it.name == "intrinsicInvocationsOf" }
        if (parses != recordsMarkerArguments) {
            throw AssertionError(
                "the dialect parser is half present: `pub struct` " + (if (parses) "parses" else "does not parse") +
                    " and SourceContext.intrinsicInvocationsOf " + (if (recordsMarkerArguments) "exists" else "is missing") +
                    "; the frontend package (W1.1) must be merged whole",
            )
        }
        parses
    }

    /** Skips the current test unless the dialect parser is present ([hasDialectParser]). */
    fun assumeDialectParser(what: String) {
        assumeTrue(hasDialectParser, "$what needs the frontend package's parser (W1.1), which this branch does not have")
    }

    /**
     * Runs [block], which parses sources in the design's dialect. A parse failure skips the
     * test only while the dialect parser is absent; with it present the failure is a failure.
     */
    fun <T> parsingDialect(what: String, block: () -> T): T {
        try {
            return block()
        } catch (e: DiagnosticsException) {
            if (!hasDialectParser) {
                assumeTrue(false, "$what does not parse without the frontend package's parser (W1.1): ${e.message?.lineSequence()?.firstOrNull()}")
            }
            throw AssertionError("$what does not parse:\n${e.message}", e)
        }
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
