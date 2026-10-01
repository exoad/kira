package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppGenericsEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.compiler.backend.codegen.cpp.Pos
import net.exoad.kira.compiler.backend.codegen.cpp.TypedCppModuleEmitter
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.cpp.support.CppGoldenCase
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Path
import kotlin.test.fail

/**
 * Support for the classes, traits and generics tests (W2.4). They must not depend on when
 * W2.3's expression and statement parts land (the brief), so they register fakes through
 * the context instead ([FakeExprEmitter], [FakeStmtEmitter]), with the real classes and
 * generics parts beside them:
 *
 * - [FakeExprEmitter] spells what these tests' bodies hold, the little that class code
 *   needs: literals, names, a member access through a class or `this`, a call, an
 *   assignment, and hands `this` as a value and a class construction to the classes part,
 *   exactly where W2.3's emitter is to call it.
 * - [FakeStmtEmitter] writes `return e;` and `e;` statements, so a small program runs.
 */
object OopTestSupport {
    val root: Path = Path.of("build/tmp/cpp-oop-tests").toAbsolutePath().normalize()

    /** The real classes and generics parts, with the fakes for bodies and expressions. */
    fun parts(): CppEmitParts = CppEmitParts(
        exprs = FakeExprEmitter,
        stmts = FakeStmtEmitter,
        classes = CppClassEmitter(),
        generics = CppGenericsEmitter,
    )

    /** The fakes for bodies and expressions over the standard parts (W2.6's externs among them). */
    fun partsOverStandard(): CppEmitParts = CppEmitParts.standard().copy(exprs = FakeExprEmitter, stmts = FakeStmtEmitter)

    /**
     * The expression part the tests register: enough C++ for a class method's body, and the
     * classes part's own entry points wherever `this` is a value or a class is constructed.
     */
    object FakeExprEmitter : CppExprPart {
        /** The class and method of the escaping lambda being emitted, whose implicit receiver is the captured `self`. */
        private var selfFrame: Pair<ClassSymbol, FnSymbol>? = null

        /** Inside an escaping lambda: the classes part's `self` receiver (W2.3's selfPointer is to call the same); else `this`. */
        private fun receiver(ctx: CppEmitContextImpl): String =
            selfFrame?.let { (owner, method) -> ctx.parts.classes.selfReceiver(ctx, owner, method) } ?: "this"

        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String {
            val model = ctx.model
            return when (e) {
                is ThisExpr -> ctx.parts.classes.thisValue(ctx, e)
                is ObjectInitExpr -> {
                    val cls = model.init(e)?.cls
                    if (cls != null && (cls.kind == ClassKind.USER && (cls.isRef || cls.initially != null) || cls.name == "Ref")) ctx.parts.classes.construct(ctx, e) else "<ObjectInitExpr>"
                }
                is IntegerLiteral -> e.value.toString()
                is UnaryExpr -> if (e.operator == UnaryOp.NEG) "-${ctx.expr(e.operand)}" else "<UnaryExpr>"
                is StringLiteral -> CppDeclEmitter.cppString(e.value)
                is IntrinsicExpr -> "<IntrinsicExpr>"
                is Identifier -> when (val sym = model.symbolOf(e)) {
                    is ParamSymbol -> ctx.paramName(sym)
                    is LocalSymbol -> ctx.names.escape(e.value)
                    // An implicit field inside an escaping lambda goes through the captured self.
                    is FieldSymbol -> if (selfFrame != null) "${receiver(ctx)}->${ctx.names.escape(e.value)}" else ctx.names.escape(e.value)
                    // true, false and null are kira:core's @_magic globals: never ::kira::core::null.
                    is GlobalSymbol -> when {
                        !ctx.isMagic(sym) -> ctx.qualified(sym)
                        e.value == "null" -> "kira::none"
                        else -> e.value
                    }
                    else -> e.value
                }
                is MemberAccessExpr -> {
                    val member = (e.member as? Identifier)?.value ?: "<member>"
                    if (e.origin is ThisExpr) {
                        return "${receiver(ctx)}->$member"
                    }
                    val origin = ctx.expr(e.origin)
                    val generic = ctx.parts.generics.receiver(ctx, e.origin, origin)
                    when {
                        generic != null -> "$generic.$member"
                        isReference(model.typeOrNull(e.origin)) -> "$origin->$member"
                        else -> "$origin.$member"
                    }
                }
                is FunctionCallExpr -> call(ctx, e)
                is LambdaExpr -> lambda(ctx, e)
                // The writes a class method may hold (the derived const rule): `n = 0`, `n += 1`, `this.n = 0`.
                is AssignmentExpr -> "${ctx.expr(e.target)} = ${ctx.expr(e.value)}"
                is CompoundAssignmentExpr -> "${ctx.expr(e.left)} ${e.operator.symbol.joinToString("") { it.rep.toString() }}= ${ctx.expr(e.right)}"
                is PlaceAssignmentExpr -> "${ctx.expr(e.target)} ${e.operator?.symbol?.joinToString("") { it.rep.toString() } ?: ""}= ${ctx.expr(e.value)}"
                else -> "<${e.javaClass.simpleName}>"
            }
        }

        /**
         * A lambda of a class method, as W2.3's closure part is to spell the one that captures
         * the receiver and escapes (design 5.6): `[self = ...]` from the classes part's
         * [net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart.selfCapture], so `this`
         * inside it is `self`. Only what these tests' lambdas hold: `return e` statements.
         */
        private fun lambda(ctx: CppEmitContextImpl, e: LambdaExpr): String {
            val ret = ctx.spell(e.def.returnTypeSpecifier, Pos.RETURN)
            val params = e.def.parameters.joinToString(", ") { p -> "${ctx.spell(p.typeSpecifier, Pos.PARAM)} ${p.name.value}" }
            val owner = ctx.scope as? ClassSymbol
            val capture = if (owner != null && owner.kind == ClassKind.USER && owner.isRef && ctx.model.captures(e).orEmpty().any { it is Capture.This }) {
                val method = owner.methods.first { m -> m.body.orEmpty().any { s -> contains(s, e) } }
                "self = ${ctx.parts.classes.selfCapture(ctx, owner, method, e)}"
            } else {
                ""
            }
            val before = selfFrame
            if (capture.isNotEmpty()) {
                selfFrame = owner!! to owner.methods.first { m -> m.body.orEmpty().any { s -> contains(s, e) } }
            }
            try {
                val body = e.def.body.orEmpty().joinToString(" ") { s ->
                    if (s is ReturnStatement) "return ${ctx.expr(s.expr)};" else "static_cast<void>(${ctx.expr(s.expr)});"
                }
                return "[$capture]($params) -> $ret { $body }"
            } finally {
                selfFrame = before
            }
        }

        private fun contains(root: ASTNode, target: ASTNode): Boolean {
            var found = false
            AstTree.walk(root) { if (it === target) found = true }
            return found
        }

        private fun isReference(t: KType?): Boolean {
            val sym = (t as? KType.Nominal)?.sym ?: return false
            return (sym is ClassSymbol && (sym.kind == ClassKind.USER && sym.isRef || sym.name == "Ref")) || sym is net.exoad.kira.compiler.analysis.types.TraitSymbol
        }

        private fun call(ctx: CppEmitContextImpl, e: FunctionCallExpr): String {
            val rc = ctx.model.call(e) ?: return "<FunctionCallExpr>"
            val args = rc.args.mapNotNull { (it as? net.exoad.kira.compiler.analysis.types.ArgBinding.Given)?.expr }.joinToString(", ") { ctx.expr(it) }
            val fn: FnSymbol? = rc.fn
            val typeArgs = ctx.parts.generics.typeArguments(ctx, e, rc.typeArgs)
            val receiver = rc.receiver
            return when {
                receiver != null -> {
                    val text = ctx.expr(receiver)
                    val generic = ctx.parts.generics.receiver(ctx, receiver, text)
                    val name = fn?.name ?: "<fn>"
                    when {
                        generic != null -> "$generic.$name$typeArgs($args)"
                        receiver is ThisExpr -> "${receiver(ctx)}->$name$typeArgs($args)"
                        isReference(ctx.model.typeOrNull(receiver)) -> "$text->$name$typeArgs($args)"
                        else -> "$text.$name$typeArgs($args)"
                    }
                }
                // An implicit-this call inside an escaping lambda goes through the captured self.
                fn != null && fn.owner != null && selfFrame != null -> "${receiver(ctx)}->${fn.name}$typeArgs($args)"
                fn != null && fn.owner != null -> "${fn.name}$typeArgs($args)"
                fn != null -> "${ctx.qualified(fn)}$typeArgs($args)"
                else -> "${(e.name as? Identifier)?.value ?: "<callee>"}($args)"
            }
        }
    }

    /** `return e;` and `static_cast<void>(e);`, the statements these tests' bodies hold. */
    object FakeStmtEmitter : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            if (s is ReturnStatement) {
                val t = ctx.model.typeOrNull(s.expr)
                if (t == null || t == KType.Void) w.line("return;") else w.line("return ${ctx.expr(s.expr)};")
                return
            }
            w.line("static_cast<void>(${ctx.expr(s.expr)});")
        }
    }

    // ---- a module or two, emitted -----------------------------------------------------------------

    data class Src(val uri: String, val text: String) {
        val relativePath: String
            get() {
                val (pkg, dotted) = uri.split(":", limit = 2)
                return "src/$pkg/${dotted.replace('.', '/')}.kira"
            }
    }

    fun module(uri: String, body: String): Src = Src(uri, "module \"$uri\"\n\n${body.trimIndent()}\n")

    class Emitted(val program: TypedProgram, private val modules: Map<String, EmittedModule>, val layout: CppModuleLayout) {
        fun module(uri: String): EmittedModule = modules[uri] ?: fail("module $uri was not emitted")

        fun errors(uri: String): List<CppDiagnostic> = module(uri).diagnostics.filter { it.isError }

        fun header(uri: String): String {
            val m = module(uri)
            if (m.hasErrors) {
                fail("module $uri has errors:\n${render(m.diagnostics)}\n--- header so far ---\n${m.header}")
            }
            return CppWriter.normalize(m.header)
        }

        fun source(uri: String): String {
            val m = module(uri)
            if (m.hasErrors) {
                fail("module $uri has errors:\n${render(m.diagnostics)}")
            }
            return CppWriter.normalize(m.source ?: fail("module $uri emitted no source"))
        }
    }

    fun render(diagnostics: List<CppDiagnostic>): String = diagnostics.joinToString("\n") { it.render() }.ifEmpty { "(none)" }

    private fun unitOf(sources: List<Src>, at: Path): CompilationUnit {
        val unit = CompilationUnit()
        for (s in sources) {
            val path = at.resolve(s.relativePath).toString()
            val processed = KiraPreprocessor(s.text).process().processedContent
            val bare = unit.addSource(path, processed, emptyList())
            val tokens = KiraLexer(bare).tokenize()
            val ctx = unit.addSource(path, bare.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        return unit
    }

    /** The typer's error diagnostics for [sources] (STRICT, every rule pass), rendered (`file:line:col: error [code] message`), without emitting. */
    fun typerErrors(vararg sources: Src): List<String> {
        val program = KiraTyper.run(unitOf(sources.toList(), root), TyperMode.STRICT)
        return program.diagnostics.filter { it.isError }.map { it.render() }
    }

    /**
     * Runs [block] with the rule pass named [name] (`exclusivity`, ...) switched off: the
     * classes part's own refusal of a program the rules refuse first stays tested as a
     * backstop (40-round3 4.2). The pass list is the typer's, restored afterwards.
     */
    fun <T> withoutRulePass(name: String, block: () -> T): T {
        val passes = KiraTyper.rulePasses.toList()
        check(passes.any { it.name == name }) { "no rule pass named $name: ${passes.map { it.name }}" }
        KiraTyper.rulePasses.removeAll { it.name == name }
        try {
            return block()
        } finally {
            KiraTyper.rulePasses.clear()
            KiraTyper.rulePasses.addAll(passes)
        }
    }

    /**
     * Types [sources] STRICT, lets [tweak] adjust the model (a stand-in for a rule pass not
     * merged yet), and emits every workspace module with [parts]. Fails on a typer error.
     */
    fun emit(
        vararg sources: Src,
        options: CppOptions = CppOptions(lineDirectives = false),
        parts: CppEmitParts = parts(),
        tweak: (TypedProgram) -> Unit = {},
    ): Emitted {
        val unit = unitOf(sources.toList(), root)
        return emitUnit(unit, root, options, parts, tweak) { uri -> sources.any { it.uri == uri } }
    }

    private fun emitUnit(
        unit: CompilationUnit,
        at: Path,
        options: CppOptions,
        parts: CppEmitParts,
        tweak: (TypedProgram) -> Unit,
        wanted: (String) -> Boolean,
    ): Emitted {
        val program = KiraTyper.run(unit, TyperMode.STRICT, TyperOptions(options.freestanding, options.headerOnly, options.namespaces))
        if (program.hasErrors) {
            fail("the typer refused the sources:\n" + program.diagnostics.filter { it.isError }.joinToString("\n") { it.render() })
        }
        tweak(program)
        val emitter = TypedCppModuleEmitter(program, options, program.diagnostics.map { CppModuleEmitterFactory.convert(it) }, parts)
        val refs = unit.allSources().mapNotNull { src ->
            val u = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (u.startsWith("(unknown)")) null else CppModuleRef(u, Path.of(src.file))
        }
        val layout = CppModuleLayout(options, at, refs)
        emitter.prepare(layout, "dev")
        val modules = LinkedHashMap<String, EmittedModule>()
        unit.allSources().forEach { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@forEach
            if (wanted(uri)) {
                modules[uri] = emitter.emit(src)
            }
        }
        return Emitted(program, modules, layout)
    }

    // ---- golden cases ------------------------------------------------------------------------------------

    val goldenRoot: File = File("src/test/resources/cpp-golden")

    fun case(name: String): CppGoldenCase =
        CppGoldenCase.discover(goldenRoot).firstOrNull { it.name == name } ?: fail("no golden case '$name' under $goldenRoot")

    /** One generated file of a golden case: where it goes under the case root, and its text. */
    data class GeneratedFile(val relative: String, val text: String)

    /**
     * Emits the golden [case] the way CppGoldenEmitTest does (its kira.yaml, the semantic
     * pass, the typer STRICT, the module emitter over the case's layout), but with [parts]
     * and after [tweak], and returns every file it writes for the case's own modules.
     */
    fun emitCase(case: CppGoldenCase, parts: CppEmitParts = partsOverStandard(), tweak: (TypedProgram) -> Unit = {}): List<GeneratedFile> {
        val caseRoot = case.dir.toPath().toAbsolutePath().normalize()
        val manifest = ManifestLoader.loadFromPath(caseRoot.resolve("kira.yaml"))
        val workspace = DependencyResolver.resolveProjectSources(manifest, caseRoot)
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
        if (semantic.diagnostics.isNotEmpty()) {
            fail("${case.name}: the semantic pass refused the case:\n${semantic.diagnostics.joinToString("\n")}")
        }
        val options = KiraCppBackend.buildOptions(manifest, null)
        val emitted = emitUnit(unit, caseRoot, options, parts, tweak) { uri -> !uri.startsWith("kira:") }
        val out = mutableListOf<GeneratedFile>()
        unit.allSources().forEach { source: SourceContext ->
            val uri = runCatching { source.getModuleUri() }.getOrNull() ?: return@forEach
            if (uri.startsWith("kira:") || uri.startsWith("(unknown)")) {
                return@forEach
            }
            val m = emitted.module(uri)
            if (m.hasErrors) {
                fail("${case.name}: module $uri has errors:\n${render(m.diagnostics)}")
            }
            val files = emitted.layout.filesFor(uri, Path.of(source.file))
            out += GeneratedFile(caseRoot.relativize(files.header).toString().replace('\\', '/'), CppWriter.normalize(m.header))
            val src = files.source
            if (src != null && m.source != null) {
                out += GeneratedFile(caseRoot.relativize(src).toString().replace('\\', '/'), CppWriter.normalize(m.source!!))
            }
        }
        return out
    }

    /** The expected file of [case] at [relative] (`src/pilot/chain.kira.hxx`), LF only. */
    fun expected(case: CppGoldenCase, relative: String): String {
        val f = File(case.expectedDir, relative)
        if (!f.isFile) {
            fail("${case.name}: no expected/$relative")
        }
        return String(CppWriter.lfBytes(f.readBytes()), Charsets.UTF_8)
    }

    /**
     * [text] with every function body emptied: a line `{` at some indent that follows a line
     * holding `(` (a function head, or a constructor's mem-initializers) keeps its braces and
     * loses everything up to the `}` at the same indent. Class, struct, enum and namespace
     * braces follow a line without `(` and stay whole. What remains is every declaration,
     * signature, class body and definition head, in order: the part W2.4 owns.
     */
    fun stripBodies(text: String): String {
        val lines = text.split('\n')
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            out += line
            val trimmed = line.trimStart()
            val previous = if (i > 0) lines[i - 1] else ""
            if (trimmed == "{" && previous.contains('(')) {
                val indent = line.length - trimmed.length
                val close = " ".repeat(indent) + "}"
                var j = i + 1
                while (j < lines.size && lines[j] != close) {
                    j += 1
                }
                i = j
                continue
            }
            i += 1
        }
        return out.joinToString("\n")
    }
}
