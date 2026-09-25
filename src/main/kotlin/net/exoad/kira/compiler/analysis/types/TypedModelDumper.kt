package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.EnumMemberExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExprMember
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ContinueStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Text dumps of a [TypedProgram] for golden tests.
 *
 * [dump] prints one line per expression, in source order:
 *
 *     proto.kira:31:12  s.length()  Size  call=MAGIC(Str.length)
 *
 * The columns are separated by two spaces: `file:line:col`, the expression's text, its type
 * (`?` when none was recorded), then any of `call=`, `member=`, `coerce=`, `conv=`,
 * `const=`, `place=` and `sym=` that the model holds for it, in that order.
 *
 * [dumpSymbols] prints one line per symbol (kind, type and flags) and then the diagnostics.
 */
object TypedModelDumper {
    /**
     * One line per expression of [source]. With [includeUntyped] false (the default) an
     * expression the model says nothing about is skipped.
     */
    fun dump(program: TypedProgram, source: SourceContext, includeUntyped: Boolean = false): String {
        val model = program.model
        val file = File(source.file).name
        val names = declarationNames(source.ast)
        val positions = PositionIndex(source)
        data class Line(val pos: SourcePosition, val order: Int, val text: String)
        val lines = mutableListOf<Line>()
        var order = 0
        AstTree.walk(source.ast) { node ->
            if (node !is Expr || !isDumpable(node) || names.containsKey(node)) {
                return@walk
            }
            val facts = facts(model, node)
            val type = model.types[node]
            if (!includeUntyped && type == null && facts.isEmpty()) {
                return@walk
            }
            val pos = positions.of(node) ?: SourcePosition.UNKNOWN
            val cols = mutableListOf("$file:${pos.lineNumber}:${pos.column}", KiraUnparser.text(node), type?.display() ?: "?")
            cols.addAll(facts)
            lines.add(Line(pos, order++, cols.joinToString("  ")))
        }
        return lines.sortedWith(compareBy<Line>({ it.pos }, { it.order })).joinToString("\n") { it.text }
    }

    private fun isDumpable(node: Expr): Boolean = when (node) {
        is Decl, is Type, is FunctionDefExpr, is FunctionDeclParameterExpr, is ModuleDecl, is EnumMemberExpr,
        is FunctionCallPositionalParameterExpr, is FunctionCallNamedParameterExpr, is WithExprMember,
        is ForIterationExpr, is NoExpr -> false
        else -> true
    }

    private fun facts(model: TypedModel, node: Expr): List<String> {
        val out = mutableListOf<String>()
        val call = (node as? FunctionCallExpr)?.let { model.calls[it] } ?: model.opCalls[node]
        call?.let { out.add("call=${callText(it)}") }
        (node as? MemberAccessExpr)?.let { model.members[it] }?.let { out.add("member=${memberText(it)}") }
        model.coercions[node]?.let { out.add("coerce=${coercionText(it)}") }
        (node as? TypeCastExpr)?.let { model.conversions[it] }?.let { out.add("conv=$it") }
        model.consts[node]?.let { out.add("const=$it") }
        model.places[node]?.let { out.add("place=${placeText(it)}") }
        (node as? Identifier)?.let { model.refs[it] }?.let { out.add("sym=${it.qualifiedName}") }
        return out
    }

    fun callText(call: ResolvedCall): String {
        val fn = call.fn ?: return call.kind.name
        return "${call.kind.name}(${fn.qualifiedNameShort()})"
    }

    private fun FnSymbol.qualifiedNameShort(): String = if (owner != null) "${owner.name}.$name" else name

    fun memberText(ref: MemberRef): String = when (ref) {
        is MemberRef.Field -> "Field(${ref.field.qualifiedName}: ${ref.type.display()})"
        is MemberRef.Method -> "Method(${ref.fn.qualifiedName})"
        is MemberRef.EnumEntry -> "Enum(${ref.entry.qualifiedName})"
        is MemberRef.ModuleMember -> "Module(${ref.module.uri}.${ref.symbol.name})"
    }

    fun coercionText(c: Coercion): String = when (c) {
        is Coercion.WrapSome -> "WrapSome(${c.inner.display()})"
        is Coercion.NoneOf -> "NoneOf(${c.inner.display()})"
        is Coercion.Upcast -> "Upcast(${c.from.display()} -> ${c.to.display()})"
        is Coercion.FnRef -> "FnRef(${c.fn.qualifiedName})"
        is Coercion.ToView -> "ToView(${c.from.display()})"
        Coercion.StrConstReceiver -> "StrConstReceiver"
    }

    fun placeText(p: Place): String = when (p) {
        is Place.Local -> "Local(${p.sym.name})"
        is Place.Param -> "Param(${p.sym.name})"
        is Place.Global -> "Global(${p.sym.name})"
        is Place.This -> "This(${p.owner.name})"
        is Place.Field -> "Field(${p.receiver?.let { placeText(it) } ?: "_"}.${p.sym.name})"
        is Place.Index -> "Index(${placeText(p.container)}, ${p.kind})"
    }

    /** Identifiers that name a declaration rather than use one; the expression dump skips them. */
    private fun declarationNames(root: ASTNode): IdentityHashMap<ASTNode, Boolean> {
        val names = IdentityHashMap<ASTNode, Boolean>()
        AstTree.walk(root) { node ->
            when (node) {
                is Decl -> names[node.name] = true
                is FunctionDeclParameterExpr -> names[node.name] = true
                is ForIterationExpr -> names[node.initializer] = true
                is EnumMemberExpr -> names[node.name] = true
                is FunctionCallNamedParameterExpr -> names[node.name] = true
                is WithExprMember -> names[node.name] = true
                is TryExpr -> node.exceptionName?.let { names[it] = true }
                is ObjectInitExpr -> {}
                else -> {}
            }
        }
        return names
    }

    /**
     * One line per symbol of the modules [include] accepts (all but the stdlib by default),
     * in module-URI order, each module's declarations in source order, then the diagnostics
     * located in those modules. Stable across machines: files are shown by their module path.
     */
    fun dumpSymbols(program: TypedProgram, include: (ModuleSymbol) -> Boolean = { !it.isStdlib }): String {
        val out = StringBuilder()
        val shown = program.modules.filter(include).sortedBy { it.uri }
        for (m in shown) {
            SymbolLines(program, out).module(m)
        }
        val shownSources = shown.map { it.source }.toSet()
        val diags = program.diagnostics
            .filter { d -> d.source == null || d.source in shownSources }
            .sortedWith(compareBy<TypeDiagnostic>({ d -> d.source?.let { program.moduleOf(it)?.uri } ?: "" }, { it.position ?: SourcePosition.UNKNOWN }, { it.code }, { it.message }))
        for (d in diags) {
            val file = d.source?.let { program.moduleOf(it) }?.let { logicalPath(it) } ?: "<unknown>"
            out.append("diag ").append(d.severity.name.lowercase()).append(' ').append(d.code).append(' ')
                .append(d.render(file).substringBefore(": ")).append("  ").append(d.message).append('\n')
        }
        return out.toString()
    }

    /** `app/main.kira` for `app:main`: stable regardless of where the checkout lives. */
    fun logicalPath(m: ModuleSymbol): String = "${m.packageName}/${m.pathSegments.joinToString("/")}.kira"

    private class SymbolLines(val program: TypedProgram, val out: StringBuilder) {
        fun module(m: ModuleSymbol) {
            val flags = mutableListOf("profile=${m.profile}", "ns=${m.cppNamespace}")
            if (m.isHeaderOnly) flags.add("headerOnly")
            flags.add("imports=[${m.imports.joinToString(", ") { it.uri }}]")
            line(0, "module ${m.uri}", flags)
            for (s in m.declarations) {
                symbol(1, s)
            }
            for (st in m.statements) {
                line(1, "stmt ${KiraUnparser.text(st.expr)}", emptyList())
            }
        }

        fun symbol(indent: Int, s: Symbol) {
            when (s) {
                is ClassSymbol -> classLine(indent, s)
                is TraitSymbol -> traitLine(indent, s)
                is EnumSymbol -> {
                    line(indent, "enum ${s.name}: ${s.base.display()}", common(s.isPub, s.markers, s.foreign))
                    s.entries.forEach { e -> line(indent + 1, "entry ${e.name} = ${e.value ?: "?"}", listOf("#${e.index}")) }
                }
                is AliasSymbol -> line(indent, "alias ${s.name}${typeParams(s.typeParams)} = ${s.target.display()}", common(s.isPub, s.markers, null))
                is GlobalSymbol -> {
                    val flags = common(s.isPub, s.markers, s.foreign)
                    if (s.isMut) flags.add("mut")
                    if (s.isConstant) flags.add("const")
                    s.constValue?.let { flags.add("value=$it") }
                    if (s.init != null && s.constValue == null) flags.add("init=${KiraUnparser.text(s.init)}")
                    line(indent, "global ${s.name}: ${s.type.display()}", flags)
                }
                is FnSymbol -> fnLine(indent, s)
                else -> line(indent, "${s.javaClass.simpleName} ${s.name}", emptyList())
            }
        }

        private fun classLine(indent: Int, c: ClassSymbol) {
            val parents = listOfNotNull(c.superclass?.display()) + c.traits.map { it.display() }
            val head = "${c.kind.name.lowercase()} ${c.name}${typeParams(c.typeParams)}${if (parents.isEmpty()) "" else " : ${parents.joinToString(", ")}"}"
            val flags = common(c.isPub, c.markers, c.foreign)
            if (c.isSubclassed) flags.add("subclassed")
            if (c.initially != null) flags.add("initially")
            if (c.finally != null) flags.add("finally")
            line(indent, head, flags)
            c.fields.forEach { f ->
                val ff = mutableListOf<String>()
                if (f.isPub) ff.add("pub")
                if (f.isMut) ff.add("mut")
                if (f.isRequired) ff.add("require")
                f.default?.let { ff.add("default=${KiraUnparser.text(it)}") }
                if (f.markers.isNotEmpty()) ff.add(f.markers.joinToString(" "))
                ff.add("#${f.index}")
                line(indent + 1, "field ${f.name}: ${f.type.display()}", ff)
            }
            c.methods.forEach { fnLine(indent + 1, it) }
        }

        private fun traitLine(indent: Int, t: TraitSymbol) {
            val head = "trait ${t.name}${typeParams(t.typeParams)}${if (t.parents.isEmpty()) "" else " : ${t.parents.joinToString(", ") { it.display() }}"}"
            line(indent, head, common(t.isPub, t.markers, t.foreign))
            t.methods.forEach { fnLine(indent + 1, it) }
            line(indent + 1, "flat [${t.flatMethods.joinToString(", ") { it.qualifiedName }}]", emptyList())
        }

        private fun fnLine(indent: Int, f: FnSymbol) {
            val params = f.params.joinToString(", ") { p ->
                (if (p.byRef) "mut " else "") + "${p.name}: ${p.type.display()}" + (p.default?.let { " = ${KiraUnparser.text(it)}" } ?: "")
            }
            val kind = if (f.isOperator) "op" else "fn"
            val flags = common(f.isPub, f.markers, f.foreign)
            if (f.isMutMethod) flags.add("mut")
            if (f.isOverride) flags.add("override")
            if (f.isVirtual) flags.add("virtual")
            if (f.isConst) flags.add("const")
            if (f.hasBody) flags.add("body")
            f.overrides?.let { flags.add("overrides=${it.qualifiedName}") }
            line(indent, "$kind ${f.name}${typeParams(f.typeParams)}: ($params) ${f.ret.display()}", flags)
        }

        private fun typeParams(tps: List<TypeParamSymbol>): String {
            if (tps.isEmpty()) return ""
            return tps.joinToString(", ", "<", ">") { tp ->
                if (tp.bounds.isEmpty()) tp.name else "${tp.name}: ${tp.bounds.joinToString(" + ") { it.display() }}"
            }
        }

        private fun common(isPub: Boolean, markers: List<Marker>, foreign: Foreign?): MutableList<String> {
            val flags = mutableListOf<String>()
            if (isPub) flags.add("pub")
            when (foreign) {
                is Foreign.Magic -> flags.add("magic=${foreign.key}")
                is Foreign.Extern -> flags.add("extern=${foreign.params.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}")
                null -> {}
            }
            markers.filter { it.name != "_magic" && it.name != "_extern" && it.name != "magic" }.forEach { flags.add(it.toString()) }
            return flags
        }

        private fun line(indent: Int, head: String, flags: List<String>) {
            out.append("  ".repeat(indent)).append(head)
            if (flags.isNotEmpty()) {
                out.append("  ").append(flags.joinToString(" "))
            }
            out.append('\n')
        }
    }

    /** The start of each node: its own origin or its subtree's earliest one, memoized. */
    private class PositionIndex(val source: SourceContext) {
        private val memo = IdentityHashMap<ASTNode, SourcePosition?>()

        fun of(node: ASTNode): SourcePosition? {
            if (memo.containsKey(node)) {
                return memo[node]
            }
            var best: SourcePosition? = source.astOrigins[node]?.takeIf { it.lineNumber >= 0 }
            for (child in AstTree.children(node)) {
                val p = of(child) ?: continue
                if (best == null || p < best) {
                    best = p
                }
            }
            memo[node] = best
            return best
        }
    }
}

/**
 * The children of any AST node, found by reflection over its fields so that a node class or
 * field added later (the frontend grows in parallel) is never silently skipped. A child is
 * any field value that is an [ASTNode], or a collection, array or map value of them, or a
 * non-node object from the AST package that holds them (an interpolation part). Each child
 * appears once, in field declaration order, base class first.
 */
object AstTree {
    private const val AST_PACKAGE = "net.exoad.kira.compiler.frontend.parser.ast"
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun children(node: Any): List<ASTNode> {
        val out = ArrayList<ASTNode>()
        val seen = IdentityHashMap<Any, Boolean>()
        seen[node] = true
        collectFields(node, out, seen)
        return out
    }

    /** Pre-order walk of [root] and everything under it; each node is visited once. */
    fun walk(root: ASTNode, visit: (ASTNode) -> Unit) {
        val seen = IdentityHashMap<ASTNode, Boolean>()
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null) {
                continue
            }
            visit(node)
            val kids = children(node)
            for (i in kids.indices.reversed()) {
                stack.addLast(kids[i])
            }
        }
    }

    private fun collectFields(obj: Any, out: MutableList<ASTNode>, seen: IdentityHashMap<Any, Boolean>) {
        for (field in fieldsOf(obj.javaClass)) {
            val value = try {
                field.get(obj)
            } catch (_: Exception) {
                null
            } ?: continue
            collectValue(value, out, seen)
        }
    }

    private fun collectValue(value: Any, out: MutableList<ASTNode>, seen: IdentityHashMap<Any, Boolean>) {
        when (value) {
            is ASTNode -> if (seen.put(value, true) == null) out.add(value)
            is Collection<*> -> value.forEach { it?.let { v -> collectValue(v, out, seen) } }
            is Array<*> -> value.forEach { it?.let { v -> collectValue(v, out, seen) } }
            is Map<*, *> -> value.values.forEach { it?.let { v -> collectValue(v, out, seen) } }
            is Enum<*> -> {}
            else -> {
                if (value.javaClass.name.startsWith(AST_PACKAGE) && seen.put(value, true) == null) {
                    collectFields(value, out, seen)
                }
            }
        }
    }

    private fun fieldsOf(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
        val chain = generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.toList().reversed()
        chain.flatMap { k ->
            k.declaredFields.filter { f ->
                !Modifier.isStatic(f.modifiers) && !f.isSynthetic && !f.type.isPrimitive && f.type != String::class.java
            }.onEach { it.isAccessible = true }
        }
    }
}

/**
 * Kira source text for an expression, rebuilt from the AST (the parser keeps no end
 * positions). Blocks are elided as `{ ... }`; operators are parenthesized where precedence
 * needs it, so the text reads back as the same tree.
 */
object KiraUnparser {
    fun text(node: ASTNode?): String = when (node) {
        null -> ""
        is Expr -> expr(node, 0)
        is Statement -> statement(node)
        else -> node.javaClass.simpleName
    }

    private fun statement(s: Statement): String = when (s) {
        is ReturnStatement -> if (s.expr === NoExpr) "return" else "return ${expr(s.expr, 0)}"
        is BreakStatement -> "break"
        is ContinueStatement -> "continue"
        is UseStatement -> "use \"${s.uri.value}\""
        is IfSelectionStatement -> "if ${expr(s.expr, 0)} { ... }"
        is WhileIterationStatement -> "while ${expr(s.condition, 0)} { ... }"
        is DoWhileIterationStatement -> "do { ... } while ${expr(s.condition, 0)}"
        is ForIterationStatement -> "for ${expr(s.forIterationExpr, 0)} { ... }"
        else -> expr(s.expr, 0)
    }

    private fun op(o: BinaryOp): String = when (o) {
        BinaryOp.TYPE_CHECK -> "is"
        BinaryOp.TYPE_CAST -> "as"
        else -> o.symbol.joinToString("") { it.rep.toString() }
    }

    private const val BS = '\\'

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                BS -> sb.append(BS).append(BS)
                '\n' -> sb.append(BS).append('n')
                '\t' -> sb.append(BS).append('t')
                '\r' -> sb.append(BS).append('r')
                '"' -> sb.append(BS).append('"')
                '$' -> sb.append(BS).append('$')
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun charText(c: Int): String = when (c) {
        '\n'.code -> "${BS}n"
        '\t'.code -> "${BS}t"
        '\r'.code -> "${BS}r"
        BS.code -> "$BS$BS"
        '\''.code -> "$BS'"
        0 -> "${BS}0"
        else -> c.toChar().toString()
    }

    fun type(t: Type): String {
        if (t is ConstTypeArg) {
            return t.value.value.toString()
        }
        val head = (if (t.isMutParam) "mut " else "") + expr(t.identifier, 0)
        val args = if (t.children.isEmpty()) "" else t.children.joinToString(", ", "<", ">") { type(it) }
        val bound = t.constraint?.let { ": ${type(it)}" } ?: ""
        return head + args + bound
    }

    private fun args(call: FunctionCallExpr): String {
        val positional = call.positionalParameters.map { (if (it.isMut) "mut " else "") + expr(it.value, 0) }
        val named = call.namedParameters.map { "${it.name.value} = " + (if (it.isMut) "mut " else "") + expr(it.value, 0) }
        return (positional + named).joinToString(", ")
    }

    private fun params(def: FunctionDefExpr): String = def.parameters.joinToString(", ") { p ->
        (if (net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier.MUTABLE in p.modifiers) "mut " else "") +
            "${p.name.value}: ${type(p.typeSpecifier)}" + (p.defaultValue?.let { " = ${expr(it, 0)}" } ?: "")
    }

    private fun wrap(inner: String, prec: Int, parent: Int): String = if (prec < parent) "($inner)" else inner

    private const val POSTFIX = 16

    private fun expr(e: Expr, parent: Int): String = when (e) {
        is ConstTypeArg -> e.value.value.toString()
        is Type -> type(e)
        is IntegerLiteral -> e.value.toString()
        is FloatLiteral -> e.value.toString()
        is StringLiteral -> quote(e.value)
        is CharLiteral -> "'${charText(e.value)}'"
        is NullLiteral -> "null"
        is ArrayLiteral -> e.value.joinToString(", ", "[", "]") { expr(it, 0) }
        is InterpolatedStringLiteral -> e.parts.joinToString("", "\"", "\"") { part ->
            when (part) {
                is InterpolationPart.Text -> quote(part.text).removeSurrounding("\"")
                is InterpolationPart.Hole -> "\${" + expr(part.expr, 0) + "}"
            }
        }
        is IntrinsicExpr -> "@${e.intrinsicKey.name}" + (e.parameters?.let { ps ->
            (ps.map { expr(it, 0) } + e.namedParameters.map { (k, v) -> "$k = ${expr(v, 0)}" }).joinToString(", ", "(", ")")
        } ?: "")
        AnonymousIdentifier -> "<anonymous>"
        is Identifier -> e.value
        is ThisExpr -> "this"
        is BinaryExpr -> {
            val p = e.operator.precedence
            wrap("${expr(e.leftExpr, p)} ${op(e.operator)} ${expr(e.rightExpr, p + 1)}", p, parent)
        }
        is UnaryExpr -> {
            val p = e.operator.precedence
            wrap("${e.operator.symbol.rep}${expr(e.operand, p)}", p, parent)
        }
        is TypeCastExpr -> wrap("${expr(e.value, 9)} as ${type(e.type)}", 9, parent)
        is TypeCheckExpr -> wrap("${expr(e.value, 9)} is ${type(e.type)}", 9, parent)
        is RangeExpr -> wrap("${expr(e.begin, 15)}..${expr(e.end, 15)}", 14, parent)
        is FunctionCallExpr -> {
            val targs = if (e.typeArguments.isEmpty()) "" else e.typeArguments.joinToString(", ", "<", ">") { type(it) }
            "${expr(e.name, POSTFIX)}$targs(${args(e)})"
        }
        is MemberAccessExpr -> "${expr(e.origin, POSTFIX)}.${expr(e.member, POSTFIX)}"
        is ArrayIndexExpr -> "${expr(e.originExpr, POSTFIX)}[${expr(e.indexExpr, 0)}]"
        is ObjectInitExpr -> {
            val all = e.positionalArgs.map { expr(it, 0) } +
                e.namedArgs.map { "${it.name.value} = " + (if (it.isMut) "mut " else "") + expr(it.value, 0) }
            if (all.isEmpty()) "${type(e.typeName)} { }" else "${type(e.typeName)} { ${all.joinToString(", ")} }"
        }
        is AssignmentExpr -> "${e.target.value} = ${expr(e.value, 0)}"
        is CompoundAssignmentExpr -> "${expr(e.left, 0)} ${op(e.operator)}= ${expr(e.right, 0)}"
        is PlaceAssignmentExpr -> "${expr(e.target, 0)} ${e.operator?.let { op(it) } ?: ""}= ${expr(e.value, 0)}"
        is IfExpr -> {
            val thenValue = e.thenBranch.lastOrNull()?.let { statement(it) } ?: ""
            val nested = e.elseBranch.singleOrNull()?.expr as? IfExpr
            val elseText = nested?.let { expr(it, 0) } ?: "{ ${e.elseBranch.lastOrNull()?.let { statement(it) } ?: ""} }"
            "if ${expr(e.condition, 0)} { $thenValue } else $elseText"
        }
        is LambdaExpr -> "fx(${params(e.def)}) ${type(e.def.returnTypeSpecifier)} { ... }"
        is FunctionDefExpr -> "fx(${params(e)}) ${type(e.returnTypeSpecifier)} { ... }"
        is ThrowExpr -> "throw ${expr(e.value, 0)}"
        is TryExpr -> "try { ... }"
        is WithExpr -> "with { ... }"
        is ForIterationExpr -> {
            val declared = e.declaredType?.let { ": ${type(it)}" } ?: ""
            if (e.isLegacy) "mut ${e.initializer.value}$declared: ${expr(e.target, 0)}" else "${e.initializer.value}$declared in ${expr(e.target, 0)}"
        }
        is FunctionCallPositionalParameterExpr -> (if (e.isMut) "mut " else "") + expr(e.value, parent)
        is FunctionCallNamedParameterExpr -> "${e.name.value} = " + (if (e.isMut) "mut " else "") + expr(e.value, 0)
        is ModuleDecl -> "module \"${e.uri.value}\""
        NoExpr -> ""
        is Decl -> "<${e.javaClass.simpleName} ${expr(e.name, 0)}>"
        else -> "<${e.javaClass.simpleName}>"
    }
}
