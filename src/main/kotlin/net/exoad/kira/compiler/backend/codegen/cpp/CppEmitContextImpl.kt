package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumEntrySymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.nio.file.Path
import java.util.IdentityHashMap

// ---- The parts (design 4.4) ----------------------------------------------------------------
//
// Each part is an interface with a default that reports `cpp.unsupported: <construct> is not
// lowered yet` at the node, so a missing part fails loudly and a test can swap a part for a
// fake. The real parts are registered in [CppEmitParts.standard], one line per package.

/** Expressions (W2.3, design 5.3): `emit` returns text and never appends. */
interface CppExprPart {
    /** The C++ text of [e] under a parent of precedence [parentPrec] (0 for none). */
    fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String

    object Unsupported : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String {
            ctx.unsupported(e, "the expression ${CppEmitContextImpl.describe(e)}")
            return "/* ${CppEmitContextImpl.describe(e)} */"
        }
    }
}

/** Statements and function bodies (W2.3, design 5.4). */
interface CppStmtPart {
    /** Appends [s] to [w] at the current indent. */
    fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter)

    /**
     * The whole body of [fn] ([statements]; [fn] is null for a class's `initially` or
     * `finally` block) into [w], already inside the function's braces.
     */
    fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
        statements.forEach { emit(ctx, it, w) }
    }

    object Unsupported : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            ctx.unsupported(s, "the statement ${CppEmitContextImpl.describe(s.expr)}")
            w.line("/* ${CppEmitContextImpl.describe(s.expr)} */")
        }

        override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
            val at: ASTNode = fn?.decl ?: statements.firstOrNull() ?: return
            val what = if (fn != null) "the body of '${fn.name}'" else "an initially/finally block"
            ctx.unsupported(at, what)
            w.line("/* $what */")
        }
    }
}

/** Lambdas and captures (W2.3, design 5.6). */
interface CppLambdaPart {
    fun emit(ctx: CppEmitContextImpl, l: LambdaExpr, parentPrec: Int): String

    object Unsupported : CppLambdaPart {
        override fun emit(ctx: CppEmitContextImpl, l: LambdaExpr, parentPrec: Int): String {
            ctx.unsupported(l, "the lambda")
            return "/* lambda */"
        }
    }
}

/** Classes and traits (W2.4, design 5.5). Structs are the declaration emitter's. */
interface CppClassesPart {
    /**
     * Section 8 of the header (or the source's anonymous namespace for a private one): the
     * definition of the class or trait [sym], ending with `};`.
     */
    fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter)

    /**
     * The out-of-line members of [sym] (constructor, destructor, methods, trait default
     * bodies), each an Allman definition with a blank line between them. [inline] when they
     * go into a header (a header-only module, a template).
     */
    fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean)

    object Unsupported : CppClassesPart {
        override fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter) {
            val kind = if (sym is TraitSymbol) "trait" else "class"
            (sym as? Symbol)?.decl?.let { ctx.unsupported(it, "the $kind '${sym.name}'") }
            w.line("/* $kind ${sym.name} */")
        }

        override fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean) {
            // Reported once, by define().
        }
    }
}

/** Templates (W2.4, design 5.5). The default spells the plain head; W2.4 may replace it. */
interface CppGenericsPart {
    /** `template<typename T, typename U>`, or null for no type parameters. */
    fun templateHead(ctx: CppEmitContextImpl, params: List<TypeParamSymbol>): String? {
        if (params.isEmpty()) {
            return null
        }
        return params.joinToString(", ", prefix = "template<", postfix = ">") { "typename ${ctx.names.escape(it.name)}" }
    }

    object Plain : CppGenericsPart
}

/** `@_extern` declarations (W2.6, design 7.2). */
interface CppExternsPart {
    /** The headers an extern declaration needs (`header = ...` files, then `kira/ffi.hxx`), once each. */
    fun includes(ctx: CppEmitContextImpl, sym: Symbol): List<String> = emptyList()

    /** The drift checks (`KIRA_EXTERN_CHECK`) for [sym], written at global scope after `kira/macro_push.hxx`. */
    fun check(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter)

    object Unsupported : CppExternsPart {
        override fun check(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter) {
            sym.decl?.let { ctx.unsupported(it, "the extern declaration '${sym.name}'") }
        }
    }
}

/** One `cpp:` entry of a `kira/<name>.bind.yaml` manifest (W2.3's CppBindingTable reads them). */
data class CppBinding(
    /** The C++ text with `{self}`, `{0}`.. and `{T0}`.. placeholders. */
    val expr: String,
    val includes: List<String> = emptyList(),
    val pure: Boolean = false,
    val constexpr: Boolean = false,
)

/** The `cpp:` blocks of the binding manifests (W2.3). */
interface CppBindingsPart {
    /** The binding for [key] (`Str.length`, `Num.abs`, a free function's bare name), or null. */
    fun lookup(key: String): CppBinding?

    object Unsupported : CppBindingsPart {
        override fun lookup(key: String): CppBinding? = null
    }
}

/**
 * The parts one emitter run uses. [standard] is the registry: each package that ships a
 * part changes exactly its own line. Tests build a copy with a fake in one slot.
 */
data class CppEmitParts(
    val exprs: CppExprPart = CppExprPart.Unsupported,
    val stmts: CppStmtPart = CppStmtPart.Unsupported,
    val lambdas: CppLambdaPart = CppLambdaPart.Unsupported,
    val bindings: CppBindingsPart = CppBindingsPart.Unsupported,
    val classes: CppClassesPart = CppClassesPart.Unsupported,
    val generics: CppGenericsPart = CppGenericsPart.Plain,
    val externs: CppExternsPart = CppExternsPart.Unsupported,
) {
    companion object {
        /** The parts the compiler runs with. One registration line per package; keep them apart. */
        fun standard(): CppEmitParts = CppEmitParts(
            // W2.3 (expressions, statements, closures, bindings) registers on these four lines:
            exprs = CppExprPart.Unsupported,
            stmts = CppStmtPart.Unsupported,
            lambdas = CppLambdaPart.Unsupported,
            bindings = CppBindingsPart.Unsupported,
            // W2.4 (classes, traits, generics) registers on these two lines:
            classes = CppClassesPart.Unsupported,
            generics = CppGenericsPart.Plain,
            // W2.6 (FFI) registers on this line:
            externs = CppExternsPart.Unsupported,
        )
    }
}

/**
 * One [CppPlacement] per module of the program, shared by every context of one emitter run,
 * so that [CppEmitContextImpl.qualified] spells another module's private declaration where
 * that module's own emission put it (`::ns::impl_::X` when its header needed X, `::ns::X`
 * otherwise) instead of guessing from `headerOnly` alone.
 */
class CppPlacements {
    private val byModule = IdentityHashMap<ModuleSymbol, CppPlacement>()

    fun of(owner: ModuleSymbol, make: () -> CppPlacement): CppPlacement = byModule[owner] ?: make().also { byModule[owner] = it }

    /** The placement of [owner] if one was built already, whichever module's emission built it. */
    fun existing(owner: ModuleSymbol): CppPlacement? = byModule[owner]
}

/**
 * What every part sees while one module is emitted: the plumbing's [CppEmitContext] plus the
 * typed program, the type speller and the parts (design 4.4).
 *
 * One instance serves one module ([symbol], parsed from [module]). Diagnostics accumulate in
 * [diagnostics], which also carries what the module's [placement] found; includes a part asks
 * for accumulate in [headerIncludes] and [sourceIncludes], and the declaration emitter writes
 * them after the runtime and module includes.
 */
class CppEmitContextImpl(
    val program: TypedProgram,
    override val options: CppOptions,
    override val module: SourceContext,
    override val layout: CppModuleLayout,
    /** The module being emitted. */
    val symbol: ModuleSymbol,
    /** The compiler version for the banner (`dev` when nothing says). */
    val version: String = CppCompilerVersion.DEV,
    val parts: CppEmitParts = CppEmitParts.standard(),
    /** The run's placements, one per module; a context made on its own gets a private one. */
    private val placements: CppPlacements = CppPlacements(),
) : CppEmitContext {
    override val names: CppNames = CppNames()
    val model: TypedModel get() = program.model
    val speller: CppTypeSpeller = CppTypeSpeller(this)
    private val reported: MutableList<CppDiagnostic> = mutableListOf()
    val headerIncludes: LinkedHashSet<String> = LinkedHashSet()
    val sourceIncludes: LinkedHashSet<String> = LinkedHashSet()

    /**
     * What this module's emission reported, then what its [placement] found (a cycle, design
     * 4.2). The placement is one per module for the whole run, and another module's
     * [qualified] may build it before this module is emitted, so its findings are read from
     * it here rather than written into whichever context first asked for it.
     */
    val diagnostics: List<CppDiagnostic>
        get() = reported + placements.existing(symbol)?.diagnostics.orEmpty()

    /** Where each of this module's declarations goes (design 4.2), and the order they go in. */
    val placement: CppPlacement by lazy { placements.of(symbol) { CppPlacement(this) } }

    /** [placement] for any module of the program: this one's, or the one [owner]'s own emission uses. */
    fun placementOf(owner: ModuleSymbol): CppPlacement {
        if (owner === symbol) {
            return placement
        }
        return placements.of(owner) {
            CppPlacement(CppEmitContextImpl(program, options, owner.source, layout, owner, version, parts, placements))
        }
    }

    /**
     * Every other module whose names this module's text spelled through [qualified], in
     * first-use order: the header must include theirs, `use`d or not (`kira:core` for `Int`).
     */
    val referencedModules: LinkedHashSet<ModuleSymbol> = LinkedHashSet()

    private val paramNames = IdentityHashMap<ParamSymbol, String>()

    /** The module's `.kira` file. */
    val sourcePath: Path = Path.of(module.file)

    /** The generated header (and source, unless header-only) of this module. */
    val files: CppModuleFiles = layout.filesFor(symbol.uri, sourcePath)

    /** `build.cpp.headerOnly` or a `kira:*` module: no source file, every function inline. */
    val isHeaderOnly: Boolean get() = options.isHeaderOnly(symbol.uri)

    /**
     * The module's `.kira` path as the banner and `#line` spell it: relative to the project
     * root with forward slashes, or `kira/<file>.kira` for a stdlib module, whose file lies
     * wherever the `kira_stdlib` dependency points and must not leak that place into
     * generated text.
     */
    val relativeSource: String get() = displayPath(symbol.isStdlib, sourcePath)

    private fun displayPath(stdlib: Boolean, path: Path): String {
        if (stdlib) {
            return "kira/" + path.fileName.toString()
        }
        return layout.relativeToRoot(path)
    }

    /** The namespace this module's declarations live in (`proto`, `bibo::text`, `kira::math`). */
    val namespace: String get() = layout.namespaceFor(symbol.uri)

    init {
        symbol.members.keys.forEach { names.reserve(it) }
    }

    // ---- CppEmitContext --------------------------------------------------------------------

    override fun includeInHeader(header: String) {
        headerIncludes.add(header)
    }

    override fun includeInSource(header: String) {
        sourceIncludes.add(header)
    }

    override fun diag(node: ASTNode, code: String, message: String) {
        report(node, code, message, CppSeverity.ERROR)
    }

    /** A warning at [node] (`cpp.macro-name`, ...). */
    fun warn(node: ASTNode, code: String, message: String) {
        report(node, code, message, CppSeverity.WARNING)
    }

    /** `cpp.unsupported: <construct> is not lowered yet`, at [node]. */
    fun unsupported(node: ASTNode, construct: String) {
        diag(node, CppModuleEmitterFactory.UNSUPPORTED_CODE, "$construct is not lowered yet")
    }

    private fun report(node: ASTNode, code: String, message: String, severity: CppSeverity) {
        report(diagnosticAt(node, code, message, severity))
    }

    /** Adds [d] to this module's [diagnostics]. */
    fun report(d: CppDiagnostic) {
        reported += d
    }

    /** A diagnostic placed at [node] (its file and line), not yet reported. */
    fun diagnosticAt(node: ASTNode, code: String, message: String, severity: CppSeverity = CppSeverity.ERROR): CppDiagnostic {
        val where = program.locate(node)
        return CppDiagnostic(code, message, severity, where?.first?.file ?: module.file, where?.second)
    }

    override fun lineDirective(node: ASTNode): String? {
        if (!options.lineDirectives) {
            return null
        }
        val where = program.locate(node) ?: return null
        if (where.second.lineNumber < 1) {
            return null
        }
        val owner = program.moduleOf(where.first)
        val path = displayPath(owner?.isStdlib == true, Path.of(where.first.file))
        return "#line ${where.second.lineNumber} \"$path\""
    }

    override fun fresh(stem: String): String = names.fresh(stem)

    // ---- Typed extensions -------------------------------------------------------------------

    fun spell(t: KType, pos: Pos): String = speller.spell(t, pos)

    /** Alias-aware: `Frame` stays `Frame`, `Arr<UInt8, USER_CMD_BYTES>` keeps the constant's name. */
    fun spell(t: Type, pos: Pos): String = speller.spell(t, pos)

    fun expr(e: Expr, parentPrec: Int = 0): String = parts.exprs.emit(this, e, parentPrec)

    fun stmt(s: Statement, w: CppWriter) = parts.stmts.emit(this, s, w)

    fun body(fn: FnSymbol?, statements: List<Statement>, w: CppWriter) = parts.stmts.body(this, fn, statements, w)

    fun lambda(l: LambdaExpr, parentPrec: Int = 0): String = parts.lambdas.emit(this, l, parentPrec)

    /**
     * How this module names [sym] (design 4.3): bare inside its own module, `impl_::f` for a
     * private declaration its header holds (every private one of a header-only module, and
     * what a source module's header needs, [CppPlacement.inImpl]), `::ns::Name` from another
     * module (`::ns::impl_::Name` when that module's header put it there), and a Kira-written
     * stdlib declaration as `::kira::x::name`. A `@_magic` symbol has no name of its own here;
     * the binding table spells its uses.
     */
    fun qualified(sym: Symbol): String {
        val name = names.escape(sym.name)
        val owner: ModuleSymbol = sym.module
        if (owner === symbol) {
            return if (placement.inImpl(sym)) "$IMPL_NAMESPACE::$name" else name
        }
        referencedModules.add(owner)
        val ns = layout.namespaceFor(owner.uri)
        return if (placementOf(owner).inImpl(sym)) "::$ns::$IMPL_NAMESPACE::$name" else "::$ns::$name"
    }

    /**
     * The C++ name of the parameter [p]: its Kira name, unless that would shadow (under
     * `-Wshadow -Werror`) a field or method of the owning struct or a declaration of this
     * module, in which case a synthesized `name_p` (lowercase with an underscore, which no
     * Kira name can be). Stable per parameter, so the prototype, the definition and the
     * statement part (W2.3) spell it alike.
     */
    fun paramName(p: ParamSymbol): String = paramNames.getOrPut(p) {
        if (shadows(p)) names.fresh(p.name.lowercase() + "_p") else names.escape(p.name)
    }

    private fun shadows(p: ParamSymbol): Boolean {
        when (val owner = p.fn?.owner) {
            is ClassSymbol -> if (owner.fields.any { it.name == p.name } || owner.methods.any { it.name == p.name }) {
                return true
            }
            is TraitSymbol -> if (owner.methods.any { it.name == p.name }) {
                return true
            }
            else -> {}
        }
        return symbol.members.containsKey(p.name)
    }

    /** `E::ENTRY`, qualified as [qualified] qualifies the enum. */
    fun entry(entry: EnumEntrySymbol): String = "${qualified(entry.owner)}::${names.escape(entry.name)}"

    /** `fx main` is the program's entry (D23): declared in the header whatever its modifiers say. */
    fun isMain(fn: FnSymbol): Boolean = fn.owner == null && fn.name == "main" && fn.foreign == null

    /** Whether a top-level symbol is exported (`pub`), the way the module graph reads it. */
    fun isPub(sym: Symbol): Boolean = ModuleGraph.isPub(sym)

    /** Whether [sym] is supplied by the runtime or the compiler (`@_magic`), so nothing is emitted for it. */
    fun isMagic(sym: Symbol): Boolean = when (sym) {
        is ClassSymbol -> sym.foreign is Foreign.Magic || sym.kind == net.exoad.kira.compiler.analysis.types.ClassKind.MAGIC
        is FnSymbol -> sym.foreign is Foreign.Magic
        is GlobalSymbol -> sym.foreign is Foreign.Magic
        is TraitSymbol -> sym.foreign is Foreign.Magic
        is EnumSymbol -> sym.foreign is Foreign.Magic
        else -> false
    }

    // ---- Literal source text ---------------------------------------------------------------

    private val numberTokensByPosition: Map<SourcePosition, Int> by lazy {
        val map = HashMap<SourcePosition, Int>()
        module.tokens.forEachIndexed { i, t -> map.putIfAbsent(t.canonicalLocation, i) }
        map
    }

    /**
     * The source spelling of a number literal at [node] (`0xAA`, `0.10`, `1e-3`), so the C++
     * keeps the radix and the digits the author wrote; null when the node has no recorded
     * origin or no number token sits there. A negated literal (the parser folds `-1` into one
     * IntegerLiteral placed at the `-`) comes back with its sign.
     */
    fun rawNumberText(node: ASTNode): String? {
        val origins = runCatching { module.astOrigins }.getOrNull() ?: return null
        val at = origins[node] ?: return null
        var index = numberTokensByPosition[at] ?: return null
        var sign = ""
        val tokens = module.tokens
        val first = tokens[index]
        if (first.type == Token.Type.OP_SUB || first.type == Token.Type.OP_ADD) {
            sign = if (first.type == Token.Type.OP_SUB) "-" else ""
            index += 1
            if (index >= tokens.size) {
                return null
            }
        }
        val token = tokens[index]
        if (token.type != Token.Type.L_INTEGER && token.type != Token.Type.L_FLOAT) {
            return null
        }
        val match = NUMBER.find(module.content, token.pointerPosition) ?: return null
        if (match.range.first != token.pointerPosition) {
            return null
        }
        return sign + match.value
    }

    /**
     * The C++ spelling of the integer literal [e] as a value of [prim] (null for an untyped
     * position such as an array size), keeping the source's radix and digits wherever C++
     * reads them as Kira does, and never where it would not:
     * - Kira reads every decimal literal as decimal (`010` is ten), so leading zeros go,
     *   which C++ would read as octal (`010` is eight, `09` an error);
     * - a hex or binary literal that exceeds a signed target wraps in Kira as in C
     *   (`0xFFFFFFFFFFFFFFFF` is `-1` for `Int64`), which C++ rejects under `-Werror`
     *   (`-Woverflow`, `-Wsign-conversion`), so it is spelled `static_cast<T>(0x...u)`;
     * - `Int64`'s minimum has no decimal literal, and `Int32`'s (`-2147483648`) negates a
     *   `long long`, so each is `std::numeric_limits<...>::min()` (R2).
     */
    fun integerText(e: IntegerLiteral, prim: Prim?): String {
        val value = e.value
        if (prim == Prim.INT32 && value == Int.MIN_VALUE.toLong()) {
            return "std::numeric_limits<std::int32_t>::min()"
        }
        val raw = rawNumberText(e)
        if (raw != null) {
            val negative = raw.startsWith("-")
            val digits = raw.trimStart('-', '+')
            val radix = digits.length > 1 && digits[0] == '0' && digits[1].lowercaseChar() in "xb"
            if (!radix) {
                val stripped = digits.trimStart('0').ifEmpty { "0" }
                if (!(negative && value == Long.MIN_VALUE)) {
                    return (if (negative) "-" else "") + stripped
                }
            } else if (prim == null || !prim.signed || !prim.isInteger) {
                return raw
            } else {
                val max = prim.maxValue
                val exceeds = value < 0 || (max != null && java.math.BigInteger.valueOf(value) > max)
                if (!exceeds) {
                    return raw
                }
                if (!negative) {
                    return "static_cast<${speller.scalarName(prim)}>(${digits}u)"
                }
            }
        }
        if (value == Long.MIN_VALUE) {
            return "std::numeric_limits<std::int64_t>::min()"
        }
        return value.toString()
    }

    companion object {
        /** The namespace private helpers of a header-only module go in (design 4.2). */
        const val IMPL_NAMESPACE = "impl_"

        private val NUMBER = Regex("""0[xX][0-9a-fA-F]+|0[bB][01]+|[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

        /** A node's kind for a message: `IntegerLiteral`, `IfExpr`. */
        fun describe(node: ASTNode): String = node.javaClass.simpleName
    }
}
