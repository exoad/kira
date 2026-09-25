package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.prim
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.source.SourcePosition
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * One module as C++ text (design 4.2 and table 5.2): the header, and the source unless the
 * module is header-only. Enums, aliases, constants, module state, structs and free functions
 * are lowered here; classes and traits, function bodies, expressions and extern declarations
 * are delegated to the parts of [CppEmitContextImpl].
 *
 * Layout conventions the goldens fix: one blank line between blocks, consecutive one-line
 * declarations without one, prototypes and forward declarations never separated, a blank
 * line between a struct's fields and its methods, `[[nodiscard]]` and default arguments on
 * a prototype only, `constexpr` and `inline` on both prototype and definition.
 */
class CppDeclEmitter(private val ctx: CppEmitContextImpl, private val usage: CppUsage) {
    private val model = ctx.model
    private val m: ModuleSymbol = ctx.symbol
    private val placement = CppPlacement(ctx)
    private val parts = ctx.parts

    // ---- the module's declarations, sorted into kinds ---------------------------------------

    private val declarations: List<Symbol> = m.declarations.filter { sym ->
        when {
            ctx.isMagic(sym) -> false
            sym is FnSymbol && sym.isOperator -> true
            sym is FnSymbol && sym.name == "<anonymous>" -> false
            else -> m.members[sym.name] === sym   // a duplicate name is a typer error; the first keeps the name
        }
    }
    private val externs: List<Symbol> = declarations.filter { foreignOf(it) is Foreign.Extern }
    private val values: List<Symbol> = declarations.filter { it !in externs && (it is GlobalSymbol || it is EnumSymbol || it is AliasSymbol) }
    private val structs: List<ClassSymbol> = declarations.filterIsInstance<ClassSymbol>().filter { it !in externs && it.isStruct }
    private val classes: List<ClassSymbol> = declarations.filterIsInstance<ClassSymbol>().filter { it !in externs && it.kind == ClassKind.CLASS }
    private val opaques: List<ClassSymbol> = declarations.filterIsInstance<ClassSymbol>().filter { it !in externs && it.kind == ClassKind.OPAQUE }
    private val traits: List<TraitSymbol> = declarations.filterIsInstance<TraitSymbol>().filter { it !in externs }
    private val functions: List<FnSymbol> = declarations.filterIsInstance<FnSymbol>().filter { it !in externs && it.owner == null && !it.isOperator }
    private val operators: List<FnSymbol> = declarations.filterIsInstance<FnSymbol>().filter { it.isOperator }
    private val main: FnSymbol? = functions.firstOrNull { ctx.isMain(it) }

    private fun foreignOf(sym: Symbol): Foreign? = when (sym) {
        is ClassSymbol -> sym.foreign
        is TraitSymbol -> sym.foreign
        is FnSymbol -> sym.foreign
        is GlobalSymbol -> sym.foreign
        is EnumSymbol -> sym.foreign
        else -> null
    }

    // ---- entry ---------------------------------------------------------------------------------

    fun emit(): EmittedModule {
        warnMacroNames()
        operators.forEach { op ->
            op.decl?.let { ctx.unsupported(it, "the operator overload '${op.name}'") }
        }
        if (main != null && ctx.isHeaderOnly) {
            main.decl?.let { ctx.diag(it, CppModuleEmitterFactory.UNSUPPORTED_CODE, "fx main in a header-only module has no source file to hold int main; drop the module from build.cpp.headerOnly") }
        }

        val externChecks = CppWriter()
        externs.forEach { parts.externs.check(ctx, it, externChecks) }
        val headerBody = headerBody()
        val sourceBody = if (ctx.isHeaderOnly) "" else sourceBody()

        val header = assembleHeader(externChecks.toString(), headerBody)
        val source = when {
            ctx.isHeaderOnly -> null
            sourceBody.isEmpty() && main == null -> null
            else -> assembleSource(sourceBody)
        }
        return EmittedModule(header, source, ctx.diagnostics.toList())
    }

    private fun banner(): String =
        "// GENERATED by kira ${ctx.version} --target cpp from ${m.uri} (${ctx.relativeSource}). " +
            "Do not edit: python tools/kira_gen.py --check fails on any difference."

    private fun runtimeInclude(): String {
        val freestanding = m.profile == Profile.FREESTANDING || ctx.options.isFreestanding(m.uri)
        return if (freestanding) "kira/core.hxx" else "kira/rt.hxx"
    }

    /** The `use`d modules' headers: relative paths for workspace modules, `kira/std/x.kira.hxx` for Kira-written stdlib ones. */
    private fun moduleIncludes(): List<String> {
        val out = LinkedHashSet<String>()
        m.uses.forEach { use ->
            val target = ctx.program.module(use.uri.value) ?: return@forEach
            if (target === m) {
                return@forEach
            }
            if (target.isStdlib) {
                if (target.declarations.any { !ctx.isMagic(it) }) {
                    val segments = CppModuleLayout.uriSegments(target.uri)
                    out.add("kira/std/" + segments.joinToString("/") + ctx.options.headerExt)
                }
            } else {
                val theirs = ctx.layout.filesFor(target.uri, Path.of(target.source.file)).header
                out.add(ctx.layout.includePath(ctx.files.header, theirs))
            }
        }
        return out.toList()
    }

    private fun assembleHeader(externChecks: String, body: String): String {
        val sb = StringBuilder()
        sb.append(banner()).append('\n')
        sb.append("#pragma once\n")
        val includes = LinkedHashSet<String>()
        includes.add(runtimeInclude())
        includes.addAll(moduleIncludes())
        externs.forEach { includes.addAll(parts.externs.includes(ctx, it)) }
        includes.addAll(ctx.headerIncludes)
        includes.forEach { sb.append("#include \"").append(it).append("\"\n") }
        sb.append("#include \"$MACRO_PUSH\"\n")
        if (externChecks.isNotEmpty()) {
            sb.append(externChecks)
        }
        if (body.isNotEmpty()) {
            val w = CppWriter()
            w.namespace(ctx.namespace) { lines(body) }
            sb.append(w.toString())
        }
        sb.append("#include \"$MACRO_POP\"\n")
        return sb.toString()
    }

    private fun assembleSource(body: String): String {
        val sb = StringBuilder()
        sb.append(banner()).append('\n')
        sb.append("#include \"").append(ctx.files.header.fileName.toString()).append("\"\n")
        if (main != null) {
            sb.append("#include \"kira/main.hxx\"\n")
        }
        ctx.sourceIncludes.forEach { sb.append("#include \"").append(it).append("\"\n") }
        sb.append("#include \"$MACRO_PUSH\"\n")
        if (body.isNotEmpty()) {
            val w = CppWriter()
            w.namespace(ctx.namespace) { lines(body) }
            sb.append(w.toString())
        }
        sb.append("#include \"$MACRO_POP\"\n")
        if (main != null) {
            val w = CppWriter()
            w.block("int main(int argc, char** argv)") {
                line("return kira::rt::runMain(argc, argv, &${ctx.namespace}::main);")
            }
            sb.append(w.toString())
        }
        return sb.toString()
    }

    // ---- the header's namespace body -----------------------------------------------------------

    private fun headerBody(): String {
        val w = CppWriter()
        val sections = mutableListOf<String>()

        // 5. forward declarations of every exported struct, class and trait
        sections += render { forwardDeclarations(this, exported = true) }

        // 6. enums, aliases and constants
        sections += render { valuesSection(this, Home.HEADER) }

        // 7. structs, by-value containment first
        val exportedStructs = placement.sortStructs(structs.filter { placement.type(it).decl == Home.HEADER })
        sections += render { blocks(this, exportedStructs.map { render { struct(this, it) } }) }
        val privateStructs = placement.sortStructs(structs.filter { placement.type(it).decl == Home.HEADER_IMPL })
        val privateTypes = privateStructs.map { render { struct(this, it) } } +
            orderedTraits().filter { placement.type(it).decl == Home.HEADER_IMPL }.map { render { parts.classes.define(ctx, it, this) } } +
            orderedClasses().filter { placement.type(it).decl == Home.HEADER_IMPL }.map { render { parts.classes.define(ctx, it, this) } }
        if (privateTypes.isNotEmpty()) {
            sections += render {
                namespace(CppEmitContextImpl.IMPL_NAMESPACE) {
                    forwardDeclarations(this, exported = false)
                    blank()
                    blocks(this, privateTypes)
                }
            }
        }

        // 8. traits (parents first), then classes
        sections += render {
            blocks(
                this,
                orderedTraits().filter { placement.type(it).decl == Home.HEADER }.map { render { parts.classes.define(ctx, it, this) } } +
                    orderedClasses().filter { placement.type(it).decl == Home.HEADER }.map { render { parts.classes.define(ctx, it, this) } },
            )
        }

        // 9. free-function prototypes: private helpers of a header-only module first, in impl_
        val implPrototypes = functions.filter { placement.function(it).decl == Home.HEADER_IMPL }
        val headerPrototypes = functions.filter { placement.function(it).decl == Home.HEADER }
        sections += render {
            if (implPrototypes.isNotEmpty()) {
                namespace(CppEmitContextImpl.IMPL_NAMESPACE) {
                    implPrototypes.forEach { fn -> prototype(fn).forEach { line(it) } }
                }
            }
            headerPrototypes.forEach { fn -> prototype(fn).forEach { line(it) } }
        }

        // 10. inline definitions
        val implDefinitions = definitionsIn(Home.HEADER_IMPL)
        val headerDefinitions = definitionsIn(Home.HEADER)
        sections += render {
            if (implDefinitions.isNotEmpty()) {
                namespace(CppEmitContextImpl.IMPL_NAMESPACE) { blocks(this, implDefinitions) }
            }
            if (implDefinitions.isNotEmpty() && headerDefinitions.isNotEmpty()) {
                blank()
            }
            blocks(this, headerDefinitions)
        }

        // module-level static_asserts (D7), after everything they may name
        sections += render { moduleStatements(this) }

        sections.filter { it.isNotEmpty() }.forEachIndexed { i, s ->
            if (i > 0) {
                w.blank()
            }
            w.lines(s)
        }
        return w.toString()
    }

    // ---- the source's namespace body -----------------------------------------------------------

    private fun sourceBody(): String {
        val w = CppWriter()
        val privateValues = render { valuesSection(this, Home.SOURCE) }
        val privateStructs = placement.sortStructs(structs.filter { placement.type(it).decl == Home.SOURCE })
        val privateTypes = render {
            forwardDeclarations(this, exported = false)
            blank()
            blocks(
                this,
                privateStructs.map { render { struct(this, it) } } +
                    orderedTraits().filter { placement.type(it).decl == Home.SOURCE }.map { render { parts.classes.define(ctx, it, this) } } +
                    orderedClasses().filter { placement.type(it).decl == Home.SOURCE }.map { render { parts.classes.define(ctx, it, this) } },
            )
        }
        val privatePrototypes = render {
            functions.filter { placement.function(it).decl == Home.SOURCE }.forEach { fn -> prototype(fn).forEach { line(it) } }
        }
        val privateDefinitions = render { blocks(this, definitionsIn(Home.SOURCE, exported = false)) }
        val anonymous = listOf(privateValues, privateTypes, privatePrototypes, privateDefinitions).filter { it.isNotEmpty() }
        if (anonymous.isNotEmpty()) {
            w.namespace(null) {
                anonymous.forEachIndexed { i, s ->
                    if (i > 0) {
                        blank()
                    }
                    lines(s)
                }
            }
        }
        val exportedDefinitions = definitionsIn(Home.SOURCE, exported = true)
        if (exportedDefinitions.isNotEmpty()) {
            w.blank()
            blocks(w, exportedDefinitions)
        }
        return w.toString()
    }

    // ---- sections ------------------------------------------------------------------------------

    private fun render(body: CppWriter.() -> Unit): String {
        val w = CppWriter()
        w.body()
        return w.toString()
    }

    /** Blocks separated by one blank line; consecutive one-line items are not separated. */
    private fun blocks(w: CppWriter, items: List<String>) {
        var previousOneLiner = false
        items.filter { it.isNotEmpty() }.forEachIndexed { i, item ->
            val text = item.trimEnd('\n')
            val oneLiner = !text.contains('\n')
            if (i > 0 && !(oneLiner && previousOneLiner)) {
                w.blank()
            }
            w.lines(text)
            previousOneLiner = oneLiner
        }
    }

    private fun forwardDeclarations(w: CppWriter, exported: Boolean) {
        val wanted = { sym: Symbol -> placement.isExported(sym) == exported }
        declarations.forEach { sym ->
            if (sym in externs || !wanted(sym)) {
                return@forEach
            }
            when (sym) {
                is ClassSymbol -> {
                    if (ctx.isMagic(sym)) {
                        return@forEach
                    }
                    parts.generics.templateHead(ctx, sym.typeParams)?.let { w.line(it) }
                    val keyword = if (sym.isStruct) "struct" else "class"
                    w.line("$keyword ${ctx.names.escape(sym.name)};")
                }
                is TraitSymbol -> {
                    parts.generics.templateHead(ctx, sym.typeParams)?.let { w.line(it) }
                    w.line("class ${ctx.names.escape(sym.name)};")
                }
                else -> {}
            }
        }
    }

    /**
     * Section 6 for [home]: enums (each followed by its `nameOf` and `enum_values` where
     * used), aliases, constants and state in source order. Private ones of a header-only
     * module go in `namespace impl_` blocks, opened and closed as the source order requires.
     */
    private fun valuesSection(w: CppWriter, home: Home) {
        val items = mutableListOf<Pair<Boolean, String>>()   // (in impl_, text)
        values.forEach { sym ->
            val p = placement.value(sym)
            val here = when (home) {
                Home.HEADER -> p.decl == Home.HEADER || p.decl == Home.HEADER_IMPL
                else -> p.decl == home
            }
            if (!here) {
                return@forEach
            }
            val inImpl = p.decl == Home.HEADER_IMPL
            when (sym) {
                is EnumSymbol -> {
                    items += inImpl to render { enum(this, sym) }
                    if (usage.needsNameOf(sym)) {
                        items += inImpl to render { nameOf(this, sym) }
                    }
                    if (usage.needsEnumValues(sym)) {
                        items += inImpl to render { enumValues(this, sym) }
                    }
                }
                is AliasSymbol -> items += inImpl to render { alias(this, sym) }
                is GlobalSymbol -> items += inImpl to render { global(this, sym, p.decl) }
                else -> {}
            }
        }
        var i = 0
        var first = true
        while (i < items.size) {
            val inImpl = items[i].first
            val run = mutableListOf<String>()
            while (i < items.size && items[i].first == inImpl) {
                run += items[i].second
                i += 1
            }
            if (!first) {
                w.blank()
            }
            first = false
            if (inImpl) {
                w.namespace(CppEmitContextImpl.IMPL_NAMESPACE) { blocks(this, run) }
            } else {
                blocks(w, run)
            }
        }
    }

    /** Every definition whose home is [home], in source order: free functions, struct methods, class members. */
    private fun definitionsIn(home: Home, exported: Boolean? = null): List<String> {
        val out = mutableListOf<Pair<SourcePosition, String>>()
        functions.forEach { fn ->
            val p = placement.function(fn)
            if (p.def != home || fn.body == null) {
                return@forEach
            }
            if (exported != null && placement.isExported(fn) != exported) {
                return@forEach
            }
            out += positionOf(fn.decl) to render { definition(this, fn, qualifier = "", p) }
        }
        structs.forEach { s ->
            s.methods.forEach { fn ->
                val p = placement.method(s, fn)
                if (p.def != home || fn.body == null || ctx.isMagic(fn)) {
                    return@forEach
                }
                if (exported != null && placement.isExported(s) != exported) {
                    return@forEach
                }
                out += positionOf(fn.decl) to render { definition(this, fn, qualifier = "${ctx.names.escape(s.name)}::", p, owner = s) }
            }
        }
        (orderedTraits() + orderedClasses()).forEach { t ->
            val p = placement.type(t)
            if (p.def != home) {
                return@forEach
            }
            if (exported != null && placement.isExported(t) != exported) {
                return@forEach
            }
            val text = render { parts.classes.defineMembers(ctx, t, this, inline = p.inlineDefinition) }
            if (text.isNotEmpty()) {
                out += positionOf(t.decl) to text
            }
        }
        return out.sortedWith(compareBy({ it.first.lineNumber }, { it.first.column })).map { it.second }
    }

    private fun positionOf(node: ASTNode?): SourcePosition {
        if (node == null) {
            return SourcePosition.UNKNOWN
        }
        return ctx.program.locate(node)?.second ?: SourcePosition.UNKNOWN
    }

    /** Traits with their parents (of this module) first, source order otherwise. */
    private fun orderedTraits(): List<TraitSymbol> = orderByParents(traits) { t -> t.parents.mapNotNull { it.sym as? TraitSymbol } }

    /** Classes with their superclass (of this module) first, source order otherwise. */
    private fun orderedClasses(): List<ClassSymbol> = orderByParents(classes) { c -> listOfNotNull(c.superclass?.sym as? ClassSymbol) }

    private fun <T : Symbol> orderByParents(items: List<T>, parentsOf: (T) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        val remaining = items.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { item -> parentsOf(item).all { it !in remaining } } ?: remaining.first()
            remaining.remove(next)
            out.add(next)
        }
        return out
    }

    private fun moduleStatements(w: CppWriter) {
        m.statements.forEach { stmt -> staticAssert(w, stmt) }
    }

    private fun staticAssert(w: CppWriter, stmt: Statement) {
        val call = stmt.expr as? IntrinsicExpr
        if (call == null || call.intrinsicKey.name != STATIC_ASSERT || call.parameters == null || call.parameters.isEmpty()) {
            ctx.unsupported(stmt, "the module-level statement ${CppEmitContextImpl.describe(stmt.expr)}")
            return
        }
        val condition = ctx.expr(call.parameters[0])
        val message = call.parameters.getOrNull(1)?.let { msg ->
            if (msg is StringLiteral) cppString(msg.value) else ctx.expr(msg)
        }
        w.line(if (message == null) "static_assert($condition);" else "static_assert($condition, $message);")
    }

    // ---- enums ---------------------------------------------------------------------------------

    private fun enumBase(e: EnumSymbol): String? {
        val base = e.base
        if (base == KType.Str) {
            return "std::int32_t"
        }
        val prim = base.prim
        if (prim == null || !prim.isInteger) {
            e.decl?.let { ctx.unsupported(it, "the enum '${e.name}' with a ${base} base (C++ enums take an integer base)") }
            return null
        }
        return ctx.spell(base, Pos.VALUE)
    }

    private fun enum(w: CppWriter, e: EnumSymbol) {
        val base = enumBase(e) ?: return
        w.block("enum class ${ctx.names.escape(e.name)} : $base", ";") {
            e.entries.forEach { entry ->
                val value = when {
                    e.base == KType.Str -> entry.index.toString()
                    else -> entry.decl?.value?.let { lit -> (lit as? IntegerLiteral)?.let { ctx.rawNumberText(it) } }
                        ?: (entry.value as? ConstValue.IntConst)?.value?.toString()
                        ?: entry.index.toString()
                }
                line("${ctx.names.escape(entry.name)} = $value,")
            }
        }
    }

    /** `nameOf(E)`: the entry's name for an integer enum, its value for a Str-based one (what `x as Str` gives). */
    private fun nameOf(w: CppWriter, e: EnumSymbol) {
        val name = ctx.names.escape(e.name)
        w.block("[[nodiscard]] constexpr const char* nameOf($name v)") {
            block(CppWriter.switchHead("v")) {
                e.entries.forEach { entry ->
                    val text = if (e.base == KType.Str) (entry.value as? ConstValue.StrConst)?.value ?: entry.name else entry.name
                    line("case $name::${ctx.names.escape(entry.name)}:")
                    line("    return ${cppString(text)};")
                }
            }
            line("return \"\";")
        }
    }

    /** `enum_values(E)`: every entry in declaration order, what `kira::EnumTraits<E>` reads for `enumOf`. */
    private fun enumValues(w: CppWriter, e: EnumSymbol) {
        val name = ctx.names.escape(e.name)
        w.block("[[nodiscard]] constexpr std::array<$name, ${e.entries.size}> enum_values($name)") {
            line("return {${e.entries.joinToString(", ") { "$name::${ctx.names.escape(it.name)}" }}};")
        }
    }

    // ---- aliases and globals ---------------------------------------------------------------------

    private fun alias(w: CppWriter, a: AliasSymbol) {
        val decl = a.decl as? TypeAliasDecl
        val target = if (decl != null && model.typeOf(decl.target) != null) ctx.spell(decl.target, Pos.VALUE) else ctx.spell(a.target, Pos.VALUE)
        parts.generics.templateHead(ctx, a.typeParams)?.let { w.line(it) }
        w.line("using ${ctx.names.escape(a.name)} = $target;")
    }

    private fun global(w: CppWriter, g: GlobalSymbol, home: Home) {
        val decl = g.decl as? VariableDecl
        val name = ctx.names.escape(g.name)
        val init = g.init
        if (init == null) {
            decl?.let { ctx.unsupported(it, "the module-level binding '${g.name}' without an initializer") }
            return
        }
        val type = g.type
        val inHeader = home != Home.SOURCE
        if (g.isMut) {
            val spelled = if (decl != null) ctx.spell(decl.type, Pos.FIELD) else ctx.spell(type, Pos.FIELD)
            w.line("${if (inHeader) "inline " else ""}$spelled $name = ${initText(init, type)};")
            return
        }
        val isStr = type == KType.Str
        val spelled = when {
            isStr -> "const char*"
            decl != null -> ctx.spell(decl.type, Pos.FIELD)
            else -> ctx.spell(type, Pos.FIELD)
        }
        val constness = if (isStr || isLiteralType(type)) "constexpr" else "const"
        w.line("${if (inHeader) "inline " else ""}$constness $spelled $name = ${initText(init, type)};")
    }

    /** Whether a constant of [t] can be `constexpr` on the gcc 11.4 floor (no constexpr std::string or std::vector). */
    private fun isLiteralType(t: KType): Boolean = when (t) {
        is KType.Scalar -> true
        KType.Str -> false
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> true
            is ClassSymbol -> when {
                sym.kind == ClassKind.MAGIC -> when {
                    sym.name == Builtins.ARR && t.args.size >= 2 -> t.typeArgs().all { isLiteralType(it) }
                    sym.name == "Maybe" || Builtins.tupleArity(sym.name) != null -> t.typeArgs().all { isLiteralType(it) }
                    sym.name == "View" || sym.name == "MutView" -> true
                    else -> false
                }
                sym.isStruct -> sym.fields.all { isLiteralType(it.type) }
                else -> false
            }
            else -> false
        }
        else -> false
    }

    // ---- structs -------------------------------------------------------------------------------

    private fun struct(w: CppWriter, s: ClassSymbol) {
        val decl = s.decl as? StructDecl
        if (decl?.initially != null) {
            ctx.unsupported(decl, "the initially block of struct '${s.name}' (a struct is an aggregate, D30)")
        }
        parts.generics.templateHead(ctx, s.typeParams)?.let { w.line(it) }
        w.block("struct ${ctx.names.escape(s.name)}", ";") {
            s.fields.forEach { f -> line(field(f)) }
            val methods = s.methods.filter { !ctx.isMagic(it) }
            val equality = usage.needsEquality(s)
            if (methods.isNotEmpty() || equality) {
                blank()
            }
            methods.forEach { fn -> prototype(fn, owner = s).forEach { line(it) } }
            if (equality) {
                line("bool operator==(const ${ctx.names.escape(s.name)}&) const = default;")
            }
        }
    }

    private fun field(f: FieldSymbol): String {
        val decl = f.decl as? VariableDecl
        val type = if (decl != null && model.typeOf(decl.type) != null) ctx.spell(decl.type, Pos.FIELD) else ctx.spell(f.type, Pos.FIELD)
        val name = ctx.names.escape(f.name)
        val default = f.default ?: return "$type $name{};"
        return "$type $name = ${initText(default, f.type)};"
    }

    // ---- functions -------------------------------------------------------------------------------

    /** The template head lines of [fn]: its type parameters and one `F_p` per non-escaping `Fx` parameter. */
    private fun templateHead(fn: FnSymbol): List<String> {
        val fxParams = fn.params.filter { placement.isNonEscapingFx(it) }
        if (fn.typeParams.isEmpty() && fxParams.isEmpty()) {
            return emptyList()
        }
        val names = fn.typeParams.map { "typename ${ctx.names.escape(it.name)}" } +
            fxParams.map { "typename ${ctx.speller.templateParamName(it)}" }
        val out = mutableListOf("template<${names.joinToString(", ")}>")
        if (fxParams.isNotEmpty()) {
            out += "  requires " + fxParams.joinToString(" && ") { p ->
                ctx.speller.callable(ctx.speller.templateParamName(p), p.type as KType.Fn)
            }
        }
        return out
    }

    private fun returnText(fn: FnSymbol): String {
        val node = (fn.decl as? FunctionDecl)?.def?.returnTypeSpecifier
        return if (node != null && model.typeOf(node) != null) ctx.spell(node, Pos.RETURN) else ctx.spell(fn.ret, Pos.RETURN)
    }

    private fun paramText(p: ParamSymbol, withDefault: Boolean, markUnused: Boolean, fn: FnSymbol): String {
        val name = ctx.names.escape(p.name)
        val unused = if (markUnused && !isUsed(fn, p)) "[[maybe_unused]] " else ""
        if (placement.isNonEscapingFx(p)) {
            return "$unused${ctx.speller.templateParamName(p)}&& $name"
        }
        val pos = if (p.byRef) Pos.MUT_PARAM else Pos.PARAM
        val node = (p.decl as? FunctionDeclParameterExpr)?.typeSpecifier
        val type = if (node != null && model.typeOf(node) != null) ctx.spell(node, pos) else ctx.spell(p.type, pos)
        val default = if (withDefault && p.default != null) " = ${initText(p.default, p.type)}" else ""
        return "$unused$type $name$default"
    }

    /** Whether [fn]'s body names [p]. Syntactic: an identifier of that name anywhere in the body. */
    private fun isUsed(fn: FnSymbol, p: ParamSymbol): Boolean {
        val body = fn.body ?: return true
        var used = false
        body.forEach { stmt ->
            AstTree.walk(stmt) { node ->
                if (!used && node is Identifier && node !is IntrinsicExpr && node.value == p.name) {
                    used = true
                }
            }
        }
        return used
    }

    /** The prototype lines: a template head, then `[[nodiscard]] constexpr|inline RET name(params = defaults) const;`. */
    private fun prototype(fn: FnSymbol, owner: ClassSymbol? = null): List<String> {
        val p = if (owner != null) placement.method(owner, fn) else placement.function(fn)
        val nodiscard = if (fn.ret == KType.Void || fn.ret == KType.Never) "" else "[[nodiscard]] "
        val specifier = when {
            fn.isConst -> "constexpr "
            owner == null && p.inlineDefinition && !placement.isTemplate(fn) -> "inline "
            else -> ""
        }
        val params = fn.params.joinToString(", ") { paramText(it, withDefault = true, markUnused = false, fn) }
        val constSuffix = if (owner != null && !fn.isMutMethod) " const" else ""
        return templateHead(fn) + "$nodiscard$specifier${returnText(fn)} ${ctx.names.escape(fn.name)}($params)$constSuffix;"
    }

    /** The definition: the template head, `constexpr|inline RET Owner::name(params) const`, then the body. */
    private fun definition(w: CppWriter, fn: FnSymbol, qualifier: String, p: Placement, owner: ClassSymbol? = null) {
        val specifier = when {
            fn.isConst -> "constexpr "
            p.inlineDefinition && !placement.isTemplate(fn) -> "inline "
            else -> ""
        }
        val params = fn.params.joinToString(", ") { paramText(it, withDefault = false, markUnused = true, fn) }
        val constSuffix = if (owner != null && !fn.isMutMethod) " const" else ""
        templateHead(fn).forEach { w.line(it) }
        w.block("$specifier${returnText(fn)} $qualifier${ctx.names.escape(fn.name)}($params)$constSuffix") {
            ctx.body(fn, fn.body ?: emptyList(), this)
        }
    }

    // ---- initializers and literals ---------------------------------------------------------------

    /**
     * A default or initializer (D48: a literal or a constant's name; also an enum entry, an
     * empty construction and anything ConstEval folded). Anything else goes to the expression part.
     */
    fun initText(e: Expr, type: KType): String {
        when (e) {
            is IntegerLiteral -> return intLiteral(e, type)
            is FloatLiteral -> return floatLiteral(e, type)
            is StringLiteral -> return cppString(e.value)
            is CharLiteral -> return cppChar(e.value)
            is NullLiteral -> return "kira::none"
            is ArrayLiteral -> {
                val element = (type as? KType.Nominal)?.typeArgs()?.firstOrNull() ?: KType.Error
                return e.value.joinToString(", ", "{", "}") { initText(it, element) }
            }
            is UnaryExpr -> if (e.operator == UnaryOp.NEG && (e.operand is IntegerLiteral || e.operand is FloatLiteral)) {
                return "-" + initText(e.operand, type)
            }
            is Identifier -> if (e !is IntrinsicExpr) {
                if (e.value == "true" || e.value == "false") {
                    return e.value
                }
                constantNamed(e)?.let { return ctx.qualified(it) }
            }
            is MemberAccessExpr -> {
                (model.const(e) as? ConstValue.EnumConst)?.let { return ctx.entry(it.entry) }
                val origin = e.origin as? Identifier
                val member = e.member as? Identifier
                if (origin != null && origin !is IntrinsicExpr && member != null) {
                    val found = model.symbolOf(origin) as? EnumSymbol
                        ?: (ctx.program.graph.lookup(m, origin.value) { it is EnumSymbol } as? ModuleGraph.Lookup.Found)?.symbol as? EnumSymbol
                    found?.entry(member.value)?.let { return ctx.entry(it) }
                }
            }
            is ObjectInitExpr -> if (e.positionalArgs.isEmpty() && e.namedArgs.isEmpty()) {
                val spelled = if (model.typeOf(e.typeName) != null) ctx.spell(e.typeName, Pos.VALUE) else ctx.spell(type, Pos.VALUE)
                return "$spelled{}"
            }
            else -> {}
        }
        model.const(e)?.let { return constText(it, type) }
        return ctx.expr(e)
    }

    private fun constantNamed(id: Identifier): GlobalSymbol? {
        (model.symbolOf(id) as? GlobalSymbol)?.let { return it }
        val found = ctx.program.graph.lookup(m, id.value) { it is GlobalSymbol } as? ModuleGraph.Lookup.Found
        return found?.symbol as? GlobalSymbol
    }

    private fun intLiteral(e: IntegerLiteral, type: KType): String {
        val raw = ctx.rawNumberText(e) ?: e.value.toString()
        return when (type.prim) {
            Prim.FLOAT32 -> floatText(e.value.toDouble()) + "f"
            Prim.FLOAT64 -> floatText(e.value.toDouble())
            Prim.UINT32 -> raw + "u"
            Prim.UINT64 -> if (e.value < 0) raw + "u" else raw
            else -> raw
        }
    }

    private fun floatLiteral(e: FloatLiteral, type: KType): String {
        val raw = ctx.rawNumberText(e) ?: floatText(e.value)
        return if (type.prim == Prim.FLOAT32) raw + "f" else raw
    }

    /** A folded value as C++ text, typed by [type] (`0u`, `1.0f`, `"x"`, `Kind::KIND_OK`, `{1, 2}`). */
    fun constText(v: ConstValue, type: KType): String = when (v) {
        is ConstValue.IntConst -> {
            val prim = type.prim ?: v.prim
            when (prim) {
                Prim.FLOAT32 -> floatText(v.value.toDouble()) + "f"
                Prim.FLOAT64 -> floatText(v.value.toDouble())
                Prim.UINT32 -> v.value.toString() + "u"
                Prim.UINT64 -> if (v.bits < 0) v.value.toString() + "u" else v.value.toString()
                else -> v.value.toString()
            }
        }
        is ConstValue.FloatConst -> if ((type.prim ?: v.prim) == Prim.FLOAT32) floatText(v.value) + "f" else floatText(v.value)
        is ConstValue.BoolConst -> v.value.toString()
        is ConstValue.CharConst -> cppChar(v.value)
        is ConstValue.StrConst -> cppString(v.value)
        is ConstValue.EnumConst -> ctx.entry(v.entry)
        is ConstValue.ArrConst -> v.elements.joinToString(", ", "{", "}") { constText(it, v.element) }
        ConstValue.NullConst -> "kira::none"
    }

    companion object {
        const val MACRO_PUSH = "kira/macro_push.hxx"
        const val MACRO_POP = "kira/macro_pop.hxx"
        const val STATIC_ASSERT = "_static_assert"
        const val MACRO_NAME_CODE = "cpp.macro-name"

        /** A double as a C++ literal: `1.0`, `0.5`, `1.0e-4`. */
        fun floatText(v: Double): String {
            if (v.isNaN() || v.isInfinite()) {
                return if (v.isNaN()) "std::numeric_limits<double>::quiet_NaN()" else if (v > 0) "std::numeric_limits<double>::infinity()" else "-std::numeric_limits<double>::infinity()"
            }
            if (v == floor(v) && abs(v) < 1e15) {
                return String.format(Locale.ROOT, "%.1f", v)
            }
            return v.toString().replace('E', 'e')
        }

        /** A C++ string literal for [s]: escapes for `"`, `\`, the C0 controls and DEL; UTF-8 bytes pass through. */
        fun cppString(s: String): String {
            val sb = StringBuilder("\"")
            s.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\t' -> sb.append("\\t")
                    '\r' -> sb.append("\\r")
                    else -> if (c.code < 0x20 || c.code == 0x7f) sb.append(String.format(Locale.ROOT, "\\%03o", c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** A C++ char literal for the byte [c]. */
        fun cppChar(c: Int): String = when (c) {
            '\n'.code -> "'\\n'"
            '\t'.code -> "'\\t'"
            '\r'.code -> "'\\r'"
            '\\'.code -> "'\\\\'"
            '\''.code -> "'\\''"
            0 -> "'\\0'"
            in 32..126 -> "'${c.toChar()}'"
            else -> String.format(Locale.ROOT, "'\\x%02x'", c)
        }
    }

    // ---- D35 ---------------------------------------------------------------------------------------

    /** D35: a `pub` name that `<windows.h>` or a common C or POSIX header defines as an object-like macro. */
    private fun warnMacroNames() {
        fun check(name: String, node: ASTNode?, what: String) {
            if (node != null && CppNames.isObjectLikeMacro(name)) {
                ctx.warn(
                    node,
                    MACRO_NAME_CODE,
                    "pub $what '$name' is an object-like macro under <windows.h> or a common C header, so a C++ caller " +
                        "that includes it cannot spell ${ctx.namespace}::$name; rename it or keep it private",
                )
            }
        }
        declarations.forEach { sym ->
            if (!placement.isExported(sym)) {
                return@forEach
            }
            check(sym.name, sym.decl, kindOf(sym))
            when (sym) {
                is ClassSymbol -> {
                    sym.fields.forEach { check(it.name, it.decl, "field") }
                    sym.methods.forEach { check(it.name, it.decl, "method") }
                }
                is EnumSymbol -> sym.entries.forEach { check(it.name, it.decl, "enum entry") }
                is TraitSymbol -> sym.methods.forEach { check(it.name, it.decl, "method") }
                else -> {}
            }
        }
    }

    private fun kindOf(sym: Symbol): String = when (sym) {
        is FnSymbol -> "function"
        is GlobalSymbol -> if (sym.isMut) "state" else "constant"
        is EnumSymbol -> "enum"
        is AliasSymbol -> "alias"
        is ClassSymbol -> if (sym.isStruct) "struct" else "class"
        is TraitSymbol -> "trait"
        else -> "name"
    }
}

/** Kept private to the emitter: the kinds of type symbol the classes part receives. */
internal fun TypeSymbol.declNode(): ASTNode? = (this as? Symbol)?.decl
