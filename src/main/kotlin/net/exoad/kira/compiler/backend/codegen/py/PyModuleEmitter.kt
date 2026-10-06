package net.exoad.kira.compiler.backend.codegen.py

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.ConversionKind
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.EnumEntrySymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.ResolvedInit
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.constArgs
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.prim
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.backend.codegen.cpp.CppBindingTable
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
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
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One Kira module as one Python module (3.10 and later), from the typed model; what it does not
 * lower is refused as `py.unsupported`. Kira's values are copied wherever Python would share them
 * ([asValue]), and Kira's arithmetic and checks are the runtime's `_k_` helpers.
 */
class PyModuleEmitter(
    private val program: TypedProgram,
    private val module: ModuleSymbol,
    private val bindings: PyBindingTable,
    private val runtime: PyRuntime,
    private val imports: PyImports? = null,
    private val shared: Shared = Shared(),
    private val usage: CppUsage = CppUsage.NONE,
) {
    /** One generated module's state, shared with the emitters of the stdlib functions it carries. */
    class Shared {
        val diagnostics = mutableListOf<CppDiagnostic>()
        val helpers = LinkedHashSet<String>()
        val reported: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
        val bundled = LinkedHashSet<FnSymbol>()
        val pending = ArrayDeque<FnSymbol>()
    }

    private val model = program.model
    val diagnostics: MutableList<CppDiagnostic> get() = shared.diagnostics
    private val helpers: MutableSet<String> get() = shared.helpers
    private val reported: MutableSet<ASTNode> get() = shared.reported

    /** The Python names of the module's own top-level declarations: a local may not take one. */
    private val topNames = HashSet<String>()

    private val used = LinkedHashMap<ModuleSymbol, String>()

    /** Loop variables, each bound to an element of what its loop walks, not a value of its own. */
    private val elementBound: MutableSet<LocalSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /** The call being written as a whole statement. */
    private var statementCall: FunctionCallExpr? = null

    /** The module's text, [header] lines first, or null when anything was refused. */
    fun emit(header: List<String>): String? {
        module.uses.forEach { use ->
            val target = program.module(use.uri.value) ?: return@forEach
            if (!target.isStdlib && target !== module) {
                alias(target, use)
            }
        }
        if (module.operators.isNotEmpty()) {
            refuse(module.operators.first().decl ?: module.source.ast, "an operator overload")
        }
        module.declarations.forEach { sym ->
            when (sym) {
                is FnSymbol, is ClassSymbol, is GlobalSymbol, is EnumSymbol -> topNames.add(pyName(sym))
                else -> {}
            }
        }
        module.statements.forEach { st ->
            val e = st.expr
            if (!(e is IntrinsicExpr && e.intrinsicKey.name == "_static_assert")) {
                refuse(st, "a statement at module level")
            }
        }
        val constants = mutableListOf<String>()
        val definitions = mutableListOf<List<String>>()
        val globals = LinkedHashMap<GlobalSymbol, String>()
        var main: FnSymbol? = null
        module.declarations.forEach { sym ->
            when (sym) {
                is GlobalSymbol -> global(sym)?.let { (folded, line) -> if (folded) constants += line else globals[sym] = line }
                is FnSymbol -> {
                    definitions += function(sym, null)
                    if (sym.name == "main") {
                        main = sym
                    }
                }
                is ClassSymbol -> definitions += classDecl(sym)
                is AliasSymbol -> {}
                is EnumSymbol -> definitions += enumDecl(sym)
                is TraitSymbol -> refuse(sym.decl ?: module.source.ast, "the trait ${sym.name}")
                else -> refuse(sym.decl ?: module.source.ast, "the declaration ${sym.name}")
            }
        }
        val entry = main?.let { mainCall(it) }
        val stdlib = mutableListOf<List<String>>()
        while (shared.pending.isNotEmpty()) {
            val fn = shared.pending.removeFirst()
            stdlib += PyModuleEmitter(program, fn.module, bindings, runtime, null, shared).function(fn, null)
        }
        // The constants come before the loads: in a use cycle the other module reads them while this one is half loaded.
        val loads = mutableListOf<String>()
        if (imports != null && imports.cycle.isNotEmpty()) {
            loads += call("_k_self").text
        }
        used.forEach { (m, name) -> loads += "$name = ${call("_k_use", "__file__", pyString(imports?.path(m) ?: "")).text}" }
        if (diagnostics.any { it.isError }) {
            return null
        }
        val out = StringBuilder()
        header.forEach { out.append(it).append('\n') }
        val sections = mutableListOf<String>()
        runtime.select(helpers).takeIf { it.isNotEmpty() }?.let { sections += it }
        if (constants.isNotEmpty()) {
            sections += constants.joinToString("\n")
        }
        if (loads.isNotEmpty()) {
            sections += loads.joinToString("\n")
        }
        stdlib.forEach { sections += it.joinToString("\n") }
        definitions.forEach { sections += it.joinToString("\n") }
        if (globals.isNotEmpty()) {
            sections += initOrder(globals.keys.toList()).joinToString("\n") { globals.getValue(it) }
        }
        entry?.let { sections += it }
        sections.forEach { out.append("\n\n").append(it).append('\n') }
        return out.toString()
    }

    private fun alias(target: ModuleSymbol, at: ASTNode): String? {
        used[target]?.let { return it }
        if (imports?.path(target) == null) {
            return refuseText(at, "a use of '${target.uri}', whose generated file has no path relative to this one's")
        }
        val base = "_k_m_" + sanitized(target.uri)
        var name = base
        var n = 2
        while (name in used.values) {
            name = "${base}_${n++}"
        }
        used[target] = name
        return name
    }

    /** A top-level function, class or global as Python names it here; a Kira-written stdlib function is queued to be carried. */
    private fun ref(sym: Symbol, at: ASTNode): String? = when {
        sym.module.isStdlib -> if (sym is FnSymbol && sym.foreign == null && sym.body != null && sym.owner == null) {
            if (shared.bundled.add(sym)) {
                shared.pending.addLast(sym)
            }
            pyName(sym)
        } else {
            refuseText(at, "the stdlib's '${sym.name}'")
        }
        sym.module === module -> pyName(sym)
        else -> alias(sym.module, at)?.let { "$it.${pyName(sym)}" }
    }

    private fun folded(g: GlobalSymbol): String? = g.constValue?.let { constText(it) }

    // ---- declarations ------------------------------------------------------------------------

    /**
     * [globals] (those not folded into a literal) in an order where each starts after every
     * global its initializer reads, as C++ initializes a constant before its use: Python runs a
     * module's lines in order, so `A = B` before `B = [7, 8]` would name B unbound. Source order
     * otherwise, and for any cycle.
     */
    private fun initOrder(globals: List<GlobalSymbol>): List<GlobalSymbol> {
        val pending = globals.toSet()
        val out = LinkedHashSet<GlobalSymbol>()
        val visiting = HashSet<GlobalSymbol>()
        fun visit(g: GlobalSymbol) {
            if (g in out || !visiting.add(g)) {
                return
            }
            val reads = LinkedHashSet<GlobalSymbol>()
            g.init?.let { globalsRead(it, Collections.newSetFromMap(IdentityHashMap()), reads) }
            reads.filter { it in pending && it !== g }.forEach { visit(it) }
            out.add(g)
        }
        globals.forEach { visit(it) }
        return out.toList()
    }

    /** The module's globals [root] reads: named in it, or in a function it calls or a class it builds, transitively. */
    private fun globalsRead(root: ASTNode, seen: MutableSet<Any>, out: MutableSet<GlobalSymbol>) {
        AstTree.walk(root) { n ->
            when (n) {
                is Identifier -> (model.symbolOf(n) as? GlobalSymbol)?.takeIf { it.module === module }?.let { out.add(it) }
                is FunctionCallExpr -> model.call(n)?.fn?.takeIf { it.module === module && seen.add(it) }?.body?.forEach { globalsRead(it, seen, out) }
                is ObjectInitExpr -> model.init(n)?.cls?.takeIf { it.module === module && seen.add(it) }?.let { c ->
                    c.fields.forEach { fd -> fd.default?.let { globalsRead(it, seen, out) } }
                    c.initially?.forEach { globalsRead(it, seen, out) }
                }
                else -> {}
            }
        }
    }

    /** A global as (folded into a literal, its line): constants go before every definition. */
    private fun global(g: GlobalSymbol): Pair<Boolean, String>? {
        val decl = g.decl ?: return null
        checkName(g.name, decl)
        checkType(g.type, decl, "the global '${g.name}'")
        if (g.foreign != null) {
            refuse(decl, "the foreign global '${g.name}'")
            return null
        }
        val folded = g.constValue?.let { constText(it) }
        if (folded != null) {
            return true to "${pyName(g)} = $folded"
        }
        val init = g.init ?: return false to "${pyName(g)} = ${zeroValue(g.type, decl)}"
        return false to "${pyName(g)} = ${asValue(init, Frame(null, null), if (g.isMut) Use.STORE else Use.CONSTANT).text}"
    }

    private fun function(fn: FnSymbol, cls: ClassSymbol?): List<String> {
        val at: ASTNode = fn.decl ?: module.source.ast
        checkName(fn.name, at)
        when {
            fn.isOperator -> refuse(at, "an operator overload")
            fn.foreign != null -> refuse(at, "the foreign function '${fn.name}'")
            fn.typeParams.isNotEmpty() -> refuse(at, "the generic function '${fn.name}'")
            !fn.hasBody || fn.body == null -> refuse(at, "the body-less method '${fn.name}'")
        }
        checkType(fn.ret, at, "the return type of '${fn.name}'")
        val frame = Frame(cls, fn)
        val params = fn.params.map { p ->
            val node: ASTNode = p.decl ?: at
            checkName(p.name, node)
            checkLocalName(p.name, node)
            checkType(p.type, node, "the parameter '${p.name}'")
            if (p.byRef && !isValue(p.type) && !isStruct(p.type)) {
                refuse(node, "the mut parameter '${p.name}': ${p.type.display()} (only a List, a Map or a struct is passed by reference)")
            }
            frame.scopes.first().add(p.name)
            pyDefault(p)?.let { "${p.name}=$it" } ?: p.name
        }
        val head = "def ${pyName(fn)}(${(listOfNotNull(if (cls != null) "self" else null) + params).joinToString(", ")}):"
        val body = mutableListOf<String>()
        assignedGlobals(fn.body.orEmpty()).takeIf { it.isNotEmpty() }?.let { body += "global ${it.joinToString(", ")}" }
        body += block(fn.body.orEmpty(), frame)
        return listOf(head) + indent(body)
    }

    private fun classDecl(c: ClassSymbol): List<String> {
        val at: ASTNode = c.decl ?: module.source.ast
        checkName(c.name, at)
        when {
            c.kind != ClassKind.CLASS && c.kind != ClassKind.STRUCT -> refuse(at, "the ${c.kind.name.lowercase()} ${c.name}")
            c.typeParams.isNotEmpty() -> refuse(at, "the generic class ${c.name}")
            c.superclass != null -> refuse(at, "the subclass ${c.name} (class inheritance)")
            c.traits.isNotEmpty() -> refuse(at, "the class ${c.name} implementing a trait")
            c.finally != null -> refuse(at, "the finally block of ${c.name}")
            c.foreign != null -> refuse(at, "the foreign class ${c.name}")
        }
        val out = mutableListOf("class ${pyName(c)}:")
        val body = mutableListOf<String>()
        val slots = c.fields.map { pyName(it) }
        body += "__slots__ = (${slots.joinToString(", ") { "\"$it\"" }}${if (slots.size == 1) "," else ""})"
        val required = c.fields.filter { isPositional(it) }
        val optional = c.fields.filter { isKeyword(it) }
        val frame = Frame(c, null)
        val init = mutableListOf<String>()
        assignedGlobals(c.initially.orEmpty()).takeIf { it.isNotEmpty() }?.let { init += "global ${it.joinToString(", ")}" }
        c.fields.forEach { f ->
            val node: ASTNode = f.decl ?: at
            checkName(f.name, node)
            if (isPositional(f) || isKeyword(f)) {
                checkLocalName(f.name, node)
            }
            checkType(f.type, node, "the field '${f.name}'")
            val given = if (holdsValue(f.type)) copyOf(Py(f.name, PyPrec.ATOM), f.type).text else f.name
            val absent = {
                when {
                    f.default != null -> asValue(f.default, frame, Use.STORE)
                    else -> zeroValue(f.type, node).let { Py(it, if (it.contains(" * ")) PyPrec.MUL else PyPrec.POSTFIX) }
                }
            }
            val v = when {
                isPositional(f) -> given
                isKeyword(f) -> {
                    helpers.add("_k_unset")
                    "${wrap(absent(), PyPrec.OR)} if ${f.name} is _k_unset else $given"
                }
                else -> absent().text
            }
            init += "self.${pyName(f)} = $v"
        }
        c.initially?.let { init += block(it, frame) }
        if (init.isNotEmpty()) {
            body += ""
            body += "def __init__(${(listOf("self") + required.map { it.name } + optional.map { "${it.name}=_k_unset" }).joinToString(", ")}):"
            body += indent(init)
        }
        if (c.kind == ClassKind.STRUCT) {
            // A struct is a value (D1): the emitter copies it where a second name could see a write.
            val copied = c.fields.map { "c.${pyName(it)} = ${copyOf(Py("self.${pyName(it)}", PyPrec.POSTFIX), it.type).text}" }
            body += listOf("", "def _k_clone(self):") + indent(listOf("c = object.__new__(self.__class__)") + copied + "return c")
            val set = c.fields.map { "self.${pyName(it)} = o.${pyName(it)}" }.ifEmpty { listOf("pass") }
            body += listOf("", "def _k_set(self, o):") + indent(set)
            if (usage.needsEquality(c)) {
                val same = c.fields.joinToString(" and ") { "self.${pyName(it)} == o.${pyName(it)}" }.ifEmpty { "True" }
                body += listOf("", "def __eq__(self, o):") + indent(listOf("return $same"))
            }
        }
        c.methods.forEach { m ->
            body += ""
            body += function(m, c)
        }
        return out + indent(body)
    }

    /** The def's own default when it and every later one fold to a literal; a call names any other. */
    private fun pyDefault(p: ParamSymbol): String? {
        val params = p.fn?.params ?: return null
        val folded = params.drop(params.indexOf(p)).map { q -> q.default?.let { model.const(it) }?.let { constText(it) } }
        return if (folded.all { it != null }) folded.first() else null
    }

    private fun isPositional(f: FieldSymbol): Boolean = f.isRequired && f.default == null

    private fun isKeyword(f: FieldSymbol): Boolean = !isPositional(f) && f.isPub

    /** A Str or float enum's entries are their indices, as C++ numbers them. */
    private fun enumDecl(e: EnumSymbol): List<String> {
        val at: ASTNode = e.decl ?: module.source.ast
        checkName(e.name, at)
        if (e.foreign != null) {
            refuse(at, "the foreign enum ${e.name}")
        }
        val body = mutableListOf<String>()
        val names = LinkedHashMap<BigInteger, String>()
        e.entries.forEach { entry ->
            if (entry.name in PyNames.KEYWORDS || entry.name.startsWith("_")) {
                refuse(entry.decl ?: at, "the enum entry '${entry.name}', which Python cannot name")
            }
            val v = enumValue(entry)
            body += "${entry.name} = $v"
            names.putIfAbsent(v, if (e.base == KType.Str) (entry.value as? ConstValue.StrConst)?.value ?: entry.name else entry.name)
        }
        body += "_k_names = {${names.entries.joinToString(", ") { "${it.key}: ${pyString(it.value)}" }}}"
        body += "_k_order = (${e.entries.joinToString(", ") { enumValue(it).toString() }}${if (e.entries.size == 1) "," else ""})"
        return listOf("class ${pyName(e)}:") + indent(body)
    }

    private fun enumValue(entry: EnumEntrySymbol): BigInteger = when {
        entry.owner.base == KType.Str || entry.owner.base.prim?.isFloat == true -> BigInteger.valueOf(entry.index.toLong())
        else -> (entry.value as? ConstValue.IntConst)?.value
            ?: (entry.decl?.value as? IntegerLiteral)?.let { BigInteger.valueOf(it.value) }
            ?: BigInteger.valueOf(entry.index.toLong())
    }

    private fun isEnum(t: KType?): Boolean = (t as? KType.Nominal)?.sym is EnumSymbol

    private fun mainCall(fn: FnSymbol): String? {
        if (fn.params.isNotEmpty()) {
            return null
        }
        val call = when (fn.ret) {
            KType.Void -> "${pyName(fn)}()"
            KType.INT32 -> call("_k_exit", "${pyName(fn)}()").text
            else -> return null
        }
        return "if __name__ == \"__main__\":\n    $call"
    }

    /** The Python names of the module's globals [statements] assign: `global` for each. */
    private fun assignedGlobals(statements: List<Statement>): List<String> {
        val out = LinkedHashSet<String>()
        statements.forEach { s ->
            AstTree.walk(s) { n ->
                val target = when (n) {
                    is AssignmentExpr -> n.target
                    is CompoundAssignmentExpr -> n.left
                    is PlaceAssignmentExpr -> n.target
                    else -> null
                }
                val sym = (target as? Identifier)?.let { model.symbolOf(it) } as? GlobalSymbol
                if (sym != null && sym.module === module) {
                    out.add(pyName(sym))
                }
            }
        }
        return out.toList()
    }

    // ---- statements --------------------------------------------------------------------------

    /** A body being written: its class (`self`), its function, the locals live in each block. */
    private class Frame(val cls: ClassSymbol?, val fn: FnSymbol?) {
        val scopes = ArrayDeque<MutableSet<String>>().apply { addLast(HashSet()) }
        var temps = 0
        fun fresh(): String = "_k_t${temps++}"
    }

    private fun block(statements: List<Statement>, f: Frame): List<String> {
        f.scopes.addLast(HashSet())
        val out = statements.flatMap { stmt(it, f) }
        f.scopes.removeLast()
        return out.ifEmpty { listOf("pass") }
    }

    private fun indent(lines: List<String>): List<String> = lines.map { if (it.isEmpty()) it else "    $it" }

    private fun stmt(s: Statement, f: Frame): List<String> = when (s) {
        is ReturnStatement -> if (s.expr === NoExpr) listOf("return") else listOf("return ${asValue(s.expr, f, Use.RETURN).text}")
        is IfSelectionStatement -> {
            val out = mutableListOf("if ${expr(s.expr, f).text}:")
            out += indent(block(s.thenStatements, f))
            s.elseBranches.forEach { b ->
                when (b) {
                    is ElseIfBranchStatement -> {
                        out += "elif ${expr(b.condition, f).text}:"
                        out += indent(block(b.statements, f))
                    }
                    is ElseBranchStatement -> {
                        out += "else:"
                        out += indent(block(b.statements, f))
                    }
                }
            }
            out
        }
        is WhileIterationStatement -> listOf("while ${expr(s.condition, f).text}:") + indent(block(s.statements, f))
        is ForIterationStatement -> forLoop(s, f)
        is BreakStatement -> listOf("break")
        is ContinueStatement -> listOf("continue")
        else -> {
            if (s.javaClass != Statement::class.java) {
                val what = when (s.javaClass.simpleName) {
                    "DoWhileIterationStatement" -> "a do-while loop"
                    "UseStatement" -> "a use statement inside a body"
                    else -> "the statement ${s.javaClass.simpleName}"
                }
                refuse(s, what)
                emptyList()
            } else {
                exprStatement(s, s.expr, f)
            }
        }
    }

    private fun forLoop(s: ForIterationStatement, f: Frame): List<String> {
        val fe = s.forIterationExpr
        val plan = model.loop(s) ?: return refuse(fe, "a for loop the typer did not resolve").let { emptyList() }
        val v = plan.variable as? LocalSymbol ?: return refuse(fe, "a for loop without its variable").let { emptyList() }
        if (plan.isLegacy) {
            refuse(fe, "the legacy `for mut ${v.name}: ...` loop (D17: write `for ${v.name}: T in ...`)")
            return emptyList()
        }
        checkName(v.name, fe)
        checkLocalName(v.name, fe)
        checkType(v.type, fe, "the loop variable '${v.name}'")
        if (f.scopes.any { v.name in it }) {
            refuse(fe, "a loop variable '${v.name}' that shadows a local of its name (Python has one scope per function; rename it)")
        }
        val target = fe.target
        val over = when (plan.kind) {
            LoopKind.RANGE -> {
                val r = target as? RangeExpr ?: return refuse(fe, "a range loop without a range").let { emptyList() }
                call("range", wrap(expr(r.begin, f), PyPrec.TERNARY), wrap(expr(r.end, f), PyPrec.TERNARY)).text
            }
            LoopKind.LIST, LoopKind.ARR -> {
                val t = typeOf(target) ?: return emptyList()
                val it = wrap(expr(target, f), PyPrec.POSTFIX)
                if (walksUnwritten(target, s.body)) it else copyOf(Py(it, PyPrec.POSTFIX), t).text
            }
            LoopKind.VIEW -> wrap(expr(target, f), PyPrec.TERNARY)
            LoopKind.MAP -> {
                val items = "${wrap(expr(target, f), PyPrec.POSTFIX)}.items()"
                if (walksUnwritten(target, s.body)) items else call("list", items).text
            }
            LoopKind.SET -> {
                val it = wrap(expr(target, f), PyPrec.POSTFIX)
                if (walksUnwritten(target, s.body)) it else call("list", it).text
            }
            else -> return refuse(target, "a for loop over a ${typeOf(target)?.display()}").let { emptyList() }
        }
        elementBound.add(v)
        f.scopes.addLast(hashSetOf(v.name))
        val body = block(s.body, f)
        f.scopes.removeLast()
        return listOf("for ${v.name} in $over:") + indent(body)
    }

    /** C++ walks a copy of a List the body may write: so does Python, unless nothing but the body could write it. */
    private fun walksUnwritten(target: Expr, body: List<Statement>): Boolean = readsOnly(body) || when (target) {
        is Identifier -> when (val sym = model.symbolOf(target)) {
            is LocalSymbol -> body.none { writesByName(it, sym) }
            is ParamSymbol -> !sym.byRef && body.none { writesByName(it, sym) }
            is GlobalSymbol -> !sym.isMut
            else -> false
        }
        is FunctionCallExpr, is ObjectInitExpr, is ArrayLiteral -> true
        is MemberAccessExpr -> target.member is FunctionCallExpr && model.member(target) !is MemberRef.Field
        else -> false
    }

    /** No expression of [body] is IMPURE, so nothing writes the range while it is walked (C++'s W6 lends it then). */
    private fun readsOnly(body: List<Statement>): Boolean {
        val stack = ArrayDeque<ASTNode>(body)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is Type -> {}
                is VariableDecl -> n.value?.let { stack.addLast(it) }
                is Expr -> if (model.effect(n) == Effect.IMPURE) return false
                else -> AstTree.children(n).forEach { stack.addLast(it) }
            }
        }
        return true
    }

    private fun exprStatement(s: Statement, e: Expr, f: Frame): List<String> = when (e) {
        NoExpr -> emptyList()
        is VariableDecl -> local(e, f)
        is AssignmentExpr -> assign(e.target, null, e.value, f)
        is CompoundAssignmentExpr -> assign(e.left, e.operator, e.right, f)
        is PlaceAssignmentExpr -> assign(e.target, e.operator, e.value, f)
        is ThrowExpr -> listOf("raise ${helper("_k_Error")}(${wrap(expr(e.value, f), PyPrec.TERNARY)})")
        is TryExpr -> tryStatement(e, f)
        is Decl -> {
            refuse(s, "a declaration inside a body")
            emptyList()
        }
        else -> {
            statementCall = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr
            val text = expr(e, f).text
            statementCall = null
            listOf(text)
        }
    }

    /** `try { } on e: Str { }` (D41) catches only a throw: a panic is a RuntimeError, which C++ never catches either. */
    private fun tryStatement(e: TryExpr, f: Frame): List<String> {
        val err = helper("_k_Error")
        val out = mutableListOf("try:")
        out += indent(block(e.tryBlock, f))
        val name = e.exceptionName
        if (name == null) {
            out += "except $err:"
            out += indent(block(e.handlerBlock, f))
            return out
        }
        checkName(name.value, name)
        checkLocalName(name.value, name)
        if (f.scopes.any { name.value in it }) {
            refuse(name, "an error '${name.value}' that shadows a local of its name (Python has one scope per function; rename it)")
        }
        // `except ... as x` unbinds x when the handler ends, so the Kira name is a copy of it.
        val caught = f.fresh()
        out += "except $err as $caught:"
        f.scopes.addLast(hashSetOf(name.value))
        out += indent(listOf("${name.value} = $caught.args[0]") + block(e.handlerBlock, f))
        f.scopes.removeLast()
        return out
    }

    private fun local(decl: VariableDecl, f: Frame): List<String> {
        val sym = model.declSymbol(decl) as? LocalSymbol
        if (sym == null) {
            refuse(decl, "the local '${decl.name.value}' (no symbol was recorded)")
            return emptyList()
        }
        checkName(sym.name, decl)
        checkLocalName(sym.name, decl)
        checkType(sym.type, decl, "the local '${sym.name}'")
        if (f.scopes.any { sym.name in it }) {
            refuse(decl, "a local '${sym.name}' that shadows another of its name (Python has one scope per function; rename it)")
        }
        f.scopes.last().add(sym.name)
        val init = decl.value?.let { asValue(it, f, Use.STORE).text } ?: zeroValue(sym.type, decl)
        return listOf("${sym.name} = $init")
    }

    /**
     * `target = value`, or `target op= value` when [op] is set. A compound assignment reads its
     * target before the value runs (OQ-1) because Python evaluates `t = t + v` from the left.
     * Python computes an assignment's target after its value, where Kira locates it first (D33),
     * so the object a field or container is read through and an index that is not a constant or
     * a local are computed into temporaries first whenever the value has an effect, and always
     * in a compound assignment, which names its target twice.
     */
    private fun assign(target: Expr, op: BinaryOp?, value: Expr, f: Frame): List<String> {
        val pre = mutableListOf<String>()
        val spill = op != null || model.effect(value) != Effect.PURE
        val lhs = placeText(target, f, spill, pre) ?: return emptyList()
        if (op == null) {
            val tt = model.typeOrNull(target)
            if (tt != null && (isValue(tt) || isStruct(tt)) && target !is ArrayIndexExpr && !(target is Identifier && model.symbolOf(target) is LocalSymbol)) {
                // A field, global or mut parameter keeps its object: a mut parameter may be bound to it (C++'s T&).
                val v = if (isStruct(tt) || holdsValue(elementOf(tt))) asValue(value, f, Use.STORE) else expr(value, f)
                return pre + when {
                    isStruct(tt) -> "$lhs._k_set(${v.text})"
                    isMap(tt) || isSet(tt) -> call("_k_mapset", lhs, v.text).text
                    isQueue(tt) -> call("_k_dqset", lhs, v.text).text
                    else -> "$lhs[:] = ${v.text}"
                }
            }
            return pre + "$lhs = ${asValue(value, f, Use.STORE).text}"
        }
        val t = typeOf(target) ?: return emptyList()
        val combined = arith(op, t, Py(lhs, PyPrec.POSTFIX), expr(value, f), value, target)
        return pre + "$lhs = ${combined.text}"
    }

    /** The Python place an assignment writes, its parts computed into [pre] when [spill] says so. */
    private fun placeText(target: Expr, f: Frame, spill: Boolean, pre: MutableList<String>): String? {
        return when (target) {
            is Identifier -> when (val sym = model.symbolOf(target)) {
                is LocalSymbol, is ParamSymbol -> sym.name
                is FieldSymbol -> fieldOfThis(sym, target, f)
                is GlobalSymbol -> ref(sym, target)
                else -> refuseText(target, "an assignment to '${target.value}'")
            }
            is MemberAccessExpr -> {
                (model.member(target) as? MemberRef.ModuleMember)?.let { mm ->
                    return if (mm.symbol is GlobalSymbol) ref(mm.symbol, target) else refuseText(target, "an assignment to this member")
                }
                val m = model.member(target) as? MemberRef.Field ?: return refuseText(target, "an assignment to this member")
                if (!isUserClass(m.field.owner)) {
                    return refuseText(target, "an assignment to a field of ${m.field.owner.name}")
                }
                "${objectPlace(target.origin, f, spill, pre)}.${pyName(m.field)}"
            }
            is ArrayIndexExpr -> {
                val key = isMap(model.typeOrNull(target.originExpr))
                val container = containerPlace(target.originExpr, f, spill, pre) ?: return null
                val index = target.indexExpr
                val stays = model.const(index) != null ||
                    (index is Identifier && model.symbolOf(index).let { it is LocalSymbol || it is ParamSymbol })
                val text = { if (key) wrap(expr(index, f), PyPrec.TERNARY) else indexText(index, f) }
                val i = if (spill && !stays) {
                    val t = f.fresh()
                    pre += "$t = ${text()}"
                    t
                } else {
                    text()
                }
                "$container[$i]"
            }
            else -> refuseText(target, "an assignment to this place")
        }
    }

    /** A handle C++ reads before the value runs (D33). */
    private fun objectPlace(origin: Expr, f: Frame, spill: Boolean, pre: MutableList<String>): String = when {
        origin is ThisExpr -> "self"
        origin is Identifier && model.symbolOf(origin).let { it is LocalSymbol || it is ParamSymbol } -> origin.value
        spill -> {
            val t = f.fresh()
            pre += "$t = ${expr(origin, f).text}"
            t
        }
        else -> wrap(expr(origin, f), PyPrec.POSTFIX)
    }

    private fun containerPlace(e: Expr, f: Frame, spill: Boolean, pre: MutableList<String>): String? {
        val field = (e as? MemberAccessExpr)?.let { model.member(it) as? MemberRef.Field }?.field
        return when {
            e is MemberAccessExpr && field != null ->
                if (isUserClass(field.owner)) "${objectPlace(e.origin, f, spill, pre)}.${pyName(field)}" else refuseText(e, "this List")
            spill && e !is Identifier -> {
                val t = f.fresh()
                pre += "$t = ${listPlace(e, f) ?: return null}"
                t
            }
            else -> listPlace(e, f)
        }
    }

    // ---- expressions -------------------------------------------------------------------------

    /** Python text and its precedence ([PyPrec]). */
    private data class Py(val text: String, val prec: Int)

    private fun wrap(p: Py, need: Int): String = if (p.prec < need) "(${p.text})" else p.text

    private fun infix(l: Py, sym: String, r: Py, prec: Int): Py = Py("${wrap(l, prec)} $sym ${wrap(r, prec + 1)}", prec)

    private fun call(name: String, vararg args: String): Py {
        if (name.startsWith("_k_")) {
            helpers.add(name)
        }
        return Py("$name(${args.joinToString(", ")})", PyPrec.POSTFIX)
    }

    private fun typeOf(e: Expr): KType? = model.typeOrNull(e) ?: run {
        refuse(e, "an untyped expression")
        null
    }

    /**
     * [e] with the implicit conversion the typer recorded applied. A List, Arr or MutView becomes
     * a View as itself: the view's reader indexes it, and from and slice make memoryviews of it.
     * A MutView of any element read only as a View (`sum(xs.from(1))` on a `mut` List) is one.
     */
    private fun expr(e: Expr, f: Frame): Py {
        val c = model.coercion(e)
        when (c) {
            is Coercion.WrapSome -> if (isMaybe(c.inner)) return refusePy(e, "a Maybe of a Maybe")
            is Coercion.NoneOf -> return Py("None", PyPrec.ATOM)
            is Coercion.ToView -> if (c.from == KType.Str) return refusePy(e, "a Str as a View<Char> (text is a Str on the py target)")
            is Coercion.StrConstReceiver, null -> {}
            else -> return refusePy(e, "the conversion ${c.javaClass.simpleName}")
        }
        val t = model.typeOrNull(e)
        if (t != null) {
            val held = if (c is Coercion.ToView && magicName(t) == "MutView") (t as KType.Nominal).typeArgs().firstOrNull() ?: t else t
            unsupportedType(held)?.let { return refusePy(e, it) }
        }
        return raw(e, f)
    }

    /**
     * Where a List or Map value goes: into a variable or field, into a global without `mut`
     * (which nothing can write), out of a return, or to a parameter.
     */
    private enum class Use { STORE, CONSTANT, RETURN, ARG }

    /**
     * [e] as a value that goes on (D44: a List or a Map is a value). A Python list or dict is shared
     * by every name given it, so one is copied where a second name could see a write: stored from
     * any variable, and returned from anything but a local, which dies. An argument is copied
     * only when something may write it before the callee is done with it, as the C++ target's
     * copy policy decides: a field, a `mut` global or a `mut` parameter when the callee may write
     * what its caller sees ([calleeWrites]: not CONFINED, or handed a `mut` operand) or a
     * sibling in [later] is IMPURE; a local only when a sibling writes it by name (a `mut`
     * argument, a `mut fx` on it, a MutView of it), as nothing else reaches another function's
     * locals; a by-value parameter or a constant never, as nothing may write either. A
     * constant stored into a constant is shared. A call or a construction makes a List no one
     * else holds. A List given as a View is lent, never copied (ViewPass keeps any IMPURE call
     * away from a view of a shared place).
     */
    private fun asValue(e: Expr, f: Frame, use: Use, later: List<Expr> = emptyList(), calleeWrites: Boolean = true): Py {
        val v = expr(e, f)
        val t = model.typeOrNull(e) ?: return v
        if (!holdsValue(t) || model.coercion(e) is Coercion.ToView) {
            return v
        }
        val shared = calleeWrites || later.any { model.effect(it) == Effect.IMPURE }
        return if (copies(e, use, later, shared)) copyOf(v, t) else v
    }

    /**
     * Whether the call [rc] may write a field or global it is given while it runs (C++'s W3):
     * it is not CONFINED (`CallReach.confined`: it may write what its caller sees, or run code
     * the checker does not see), or it has a `mut` operand of its own, a `mut` argument or a
     * MutView it is handed, which may be that field.
     */
    private fun callWrites(rc: ResolvedCall): Boolean =
        !CallReach.confined(rc, model) || rc.args.any { a ->
            a is ArgBinding.Given && (a.byRef || (magicName(model.typeOrNull(a.expr)) == "MutView" && model.coercion(a.expr) !is Coercion.ToView))
        }

    private fun copies(e: Expr, use: Use, later: List<Expr>, sharedWritten: Boolean): Boolean = when (e) {
        is Identifier -> when (val sym = model.symbolOf(e)) {
            is LocalSymbol -> when (use) {
                Use.ARG -> later.any { writesByName(it, sym) }
                Use.RETURN -> sym in elementBound
                else -> true
            }
            is ParamSymbol -> if (sym.byRef) use != Use.ARG || sharedWritten else use != Use.ARG
            is GlobalSymbol -> when {
                sym.isMut -> use != Use.ARG || sharedWritten
                else -> use == Use.STORE || use == Use.RETURN
            }
            is FieldSymbol -> use != Use.ARG || sharedWritten
            else -> false
        }
        is MemberAccessExpr -> (model.member(e) is MemberRef.Field || borrows(e)) && (use != Use.ARG || sharedWritten)
        is ArrayIndexExpr, is ThisExpr -> use != Use.ARG || sharedWritten
        is FunctionCallExpr -> borrows(e) && (use != Use.ARG || sharedWritten)
        is IfExpr -> listOfNotNull(branchValue(e.thenBranch), branchValue(e.elseBranch)).any { copies(it, use, later, sharedWritten) }
        else -> false
    }

    /** A magic accessor gives what its receiver holds, not a value of its own (kira::unwrap's `const T&`). */
    private fun borrows(e: Expr): Boolean {
        val c = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return false
        val rc = model.call(c) ?: return false
        return rc.kind == CallKind.MAGIC && rc.fn?.name in BORROWERS
    }

    /** Whether [e] writes the local [sym] by name, the only way a sibling argument can write a local (C++'s NAMED test). */
    private fun writesByName(e: ASTNode, sym: Symbol): Boolean {
        var writes = false
        AstTree.walk(e) { n ->
            if (writes || n !is Expr) {
                return@walk
            }
            val assigned = when (n) {
                is AssignmentExpr -> n.target
                is CompoundAssignmentExpr -> n.left
                is PlaceAssignmentExpr -> n.target
                else -> null
            }
            var place = assigned
            while (place is ArrayIndexExpr) {
                place = place.originExpr
            }
            if (place is Identifier && model.symbolOf(place) === sym) {
                writes = true
            }
            if (magicName(model.typeOrNull(n)) == "MutView" && rootedAt(n, sym)) {
                writes = true
            }
            val rc = (n as? FunctionCallExpr)?.let { model.call(it) } ?: return@walk
            if (rc.fn?.isMutMethod == true && rc.receiver?.let { rootedAt(it, sym) } == true) {
                writes = true
            }
            if (rc.args.any { it is ArgBinding.Given && it.byRef && rootedAt(it.expr, sym) }) {
                writes = true
            }
        }
        return writes
    }

    /** Whether [e] is the local [sym] or a view lent from it (`xs.from(1)`, `xs.view().slice(0, 2)`). */
    private fun rootedAt(e: Expr, sym: Symbol): Boolean = when (e) {
        is Identifier -> model.symbolOf(e) === sym
        is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { rootedAt(it, sym) } ?: false
        is FunctionCallExpr -> model.call(e)?.let { rc -> rc.fn?.name in LENDERS && rc.receiver?.let { rootedAt(it, sym) } == true } ?: false
        else -> false
    }

    private fun copyOf(v: Py, t: KType, depth: Int = 0): Py {
        val args = (t as? KType.Nominal)?.typeArgs().orEmpty()
        val d = depth
        return when {
            isMap(t) -> if (holdsValue(args[1])) {
                Py("{_k_k$d: ${copyOf(Py("_k_v$d", PyPrec.ATOM), args[1], d + 1).text} for _k_k$d, _k_v$d in ${wrap(v, PyPrec.POSTFIX)}.items()}", PyPrec.ATOM)
            } else {
                call("dict", v.text)
            }
            isBytes(t) -> call("bytearray", v.text)
            isSet(t) -> call("dict", v.text)
            isQueue(t) -> {
                val items = when {
                    isStruct(args[0]) -> clones(v, args[0])
                    holdsValue(args[0]) -> "[${copyOf(Py("_k_e$d", PyPrec.ATOM), args[0], d + 1).text} for _k_e$d in ${wrap(v, PyPrec.OR + 1)}]"
                    else -> v.text
                }
                Py("${helper("_k_collections")}.deque($items)", PyPrec.POSTFIX)
            }
            (isList(t) || isStack(t)) && isStruct(args[0]) -> call("list", clones(v, args[0]))
            isList(t) || isStack(t) -> if (holdsValue(args[0])) {
                Py("[${copyOf(Py("_k_e$d", PyPrec.ATOM), args[0], d + 1).text} for _k_e$d in ${wrap(v, PyPrec.OR + 1)}]", PyPrec.ATOM)
            } else {
                call("list", v.text)
            }
            isStruct(t) -> Py("${wrap(v, PyPrec.POSTFIX)}._k_clone()", PyPrec.POSTFIX)
            isMaybe(t) -> call("_k_mcopy", v.text, "lambda _k_m$d: ${copyOf(Py("_k_m$d", PyPrec.ATOM), args[0], d + 1).text}")
            else -> v
        }
    }

    /** `_k_map(Pt._k_clone, xs)`: a comprehension is a frame of its own before 3.12, which halved how deep a tree of structs could be copied. */
    private fun clones(v: Py, element: KType): String {
        val s = (element as KType.Nominal).sym as ClassSymbol
        return "${helper("_k_map")}(${ref(s, s.decl ?: module.source.ast) ?: "None"}._k_clone, ${v.text})"
    }

    private fun raw(e: Expr, f: Frame): Py {
        literal(e)?.let { return it }
        return when (e) {
            is StringLiteral -> Py(pyString(e.value), PyPrec.ATOM)
            is CharLiteral -> Py(e.value.toString(), PyPrec.ATOM)
            is InterpolatedStringLiteral -> interpolation(e, f)
            is NullLiteral -> Py("None", PyPrec.ATOM)
            is IntrinsicExpr -> refusePy(e, "the intrinsic @${e.intrinsicKey.name}")
            is Identifier -> identifier(e, f)
            is ThisExpr -> if (f.cls != null) Py("self", PyPrec.ATOM) else refusePy(e, "this outside a method")
            is MemberAccessExpr -> member(e, f)
            is FunctionCallExpr -> callExpr(e, f)
            is ObjectInitExpr -> construction(e, f)
            is ArrayIndexExpr -> index(e, f)
            is BinaryExpr -> binary(e, f)
            is UnaryExpr -> unary(e, f)
            is TypeCastExpr -> cast(e, f)
            is IfExpr -> ternary(e, f)
            is ArrayLiteral -> arrayLiteral(e, f)
            is ThrowExpr -> call("_k_throw", wrap(expr(e.value, f), PyPrec.TERNARY))
            else -> refusePy(e, "the expression ${e.javaClass.simpleName.removeSuffix("Expr").removeSuffix("Literal")}")
        }
    }

    /** `[a, b]` as the List or Arr the typer gave it: a list, or a bytearray of UInt8. */
    private fun arrayLiteral(e: ArrayLiteral, f: Frame): Py {
        val t = typeOf(e) ?: return Py("None", PyPrec.ATOM)
        if (!isList(t)) {
            return refusePy(e, "an array literal of ${article(t.display())}")
        }
        val items = e.value.joinToString(", ") { wrap(asValue(it, f, Use.STORE), PyPrec.TERNARY) }
        return if (isBytes(t)) call("bytearray", "($items${if (e.value.size == 1) "," else ""})") else Py("[$items]", PyPrec.ATOM)
    }

    /** A numeric literal, with any sign before it, as its recorded type spells it; else null. */
    private fun literal(e: Expr): Py? {
        var negative = false
        var lit: Expr = e
        while (lit is UnaryExpr && (lit.operator == UnaryOp.NEG || lit.operator == UnaryOp.POS)) {
            negative = negative xor (lit.operator == UnaryOp.NEG)
            lit = lit.operand
        }
        if (lit !is IntegerLiteral && lit !is FloatLiteral) {
            return null
        }
        val t = model.typeOrNull(e)
        t?.let { unsupportedType(it) }?.let { return refusePy(e, it) }
        val float = t?.prim?.isFloat == true || lit is FloatLiteral
        val magnitude = when {
            lit is FloatLiteral -> floatText(lit.value)
            float -> floatText((lit as IntegerLiteral).value.toDouble())
            else -> ((model.const(lit) as? ConstValue.IntConst)?.value ?: BigInteger.valueOf((lit as IntegerLiteral).value)).toString()
        }
        val text = if (magnitude.startsWith("-")) magnitude.removePrefix("-").also { negative = !negative } else magnitude
        return if (negative) Py("-$text", PyPrec.UNARY) else Py(text, PyPrec.ATOM)
    }

    private fun identifier(id: Identifier, f: Frame): Py = when (val sym = model.symbolOf(id)) {
        is LocalSymbol, is ParamSymbol -> Py(sym.name, PyPrec.ATOM)
        is FieldSymbol -> fieldOfThis(sym, id, f)?.let { Py(it, PyPrec.POSTFIX) } ?: Py("None", PyPrec.ATOM)
        is GlobalSymbol -> globalRead(sym, id)
        is FnSymbol -> refusePy(id, "a function used as a value ('${sym.name}')")
        else -> refusePy(id, "the name '${id.value}'")
    }

    private fun globalRead(sym: GlobalSymbol, at: Expr): Py = when {
        sym.module.isStdlib && sym.foreign is Foreign.Magic -> when (sym.name) {
            "true" -> Py("True", PyPrec.ATOM)
            "false" -> Py("False", PyPrec.ATOM)
            "null" -> Py("None", PyPrec.ATOM)
            else -> refusePy(at, "the magic value '${sym.name}'")
        }
        sym.module.isStdlib -> folded(sym)?.let { Py(it, if (it.startsWith("-")) PyPrec.UNARY else PyPrec.ATOM) }
            ?: refusePy(at, "the stdlib's global '${sym.name}'")
        else -> ref(sym, at)?.let { Py(it, if (sym.module === module) PyPrec.ATOM else PyPrec.POSTFIX) } ?: Py("None", PyPrec.ATOM)
    }

    /** `self.f` for a field of the class whose method or `initially` is being written. */
    private fun fieldOfThis(field: FieldSymbol, at: Expr, f: Frame): String? {
        if (f.cls == null || field.owner !== f.cls) {
            return refuseText(at, "the field '${field.name}' outside its class")
        }
        return "self.${pyName(field)}"
    }

    private fun member(e: MemberAccessExpr, f: Frame): Py {
        (e.member as? FunctionCallExpr)?.let { c -> if (model.call(c) != null) return callExpr(c, f) }
        return when (val m = model.member(e)) {
            is MemberRef.ModuleMember -> when (val sym = m.symbol) {
                is GlobalSymbol -> globalRead(sym, e)
                else -> refusePy(e, "a function used as a value ('${sym.name}')")
            }
            is MemberRef.EnumEntry -> ref(m.entry.owner, e)?.let { Py("$it.${m.entry.name}", PyPrec.POSTFIX) } ?: Py("None", PyPrec.ATOM)
            is MemberRef.Field -> {
                val ot = typeOf(e.origin) ?: return Py("None", PyPrec.ATOM)
                when {
                    magicName(ot) == "Maybe" && m.field.name == "value" -> {
                        val b = bindings.lookup("Maybe.unwrap") ?: return refusePy(e, "Maybe.value (no py binding)")
                        bound(b, { prec -> wrap(expr(e.origin, f), prec) }, emptyList(), f)
                    }
                    magicName(ot) == "Result" && (m.field.name == "value" || m.field.name == "error") -> {
                        val key = if (m.field.name == "value") "Result.unwrap" else "Result.unwrapErr"
                        val b = bindings.lookup(key) ?: return refusePy(e, "Result.${m.field.name} (no py binding)")
                        bound(b, { prec -> wrap(expr(e.origin, f), prec) }, emptyList(), f)
                    }
                    isUserClass(m.field.owner) -> Py("${wrap(objectOf(e.origin, f), PyPrec.POSTFIX)}.${pyName(m.field)}", PyPrec.POSTFIX)
                    isTuple(ot) -> Py("${wrap(expr(e.origin, f), PyPrec.POSTFIX)}[${m.field.index}]", PyPrec.POSTFIX)
                    else -> refusePy(e, "the field ${m.field.owner.name}.${m.field.name}")
                }
            }
            else -> refusePy(e, "this member access")
        }
    }

    /** The object a member is read through: `self` for `this`, else the value. */
    private fun objectOf(origin: Expr, f: Frame): Py =
        if (origin is ThisExpr && f.cls != null) Py("self", PyPrec.ATOM) else expr(origin, f)

    /**
     * A List or Map as the receiver of its method, the container of an index or a `mut` argument:
     * the variable or field itself, never copied; any other expression (a call) is its own.
     */
    private fun listPlace(e: Expr, f: Frame): String? = when (e) {
        is Identifier -> when (val sym = model.symbolOf(e)) {
            is LocalSymbol, is ParamSymbol -> sym.name
            is FieldSymbol -> fieldOfThis(sym, e, f)
            is GlobalSymbol -> ref(sym, e)
            else -> refuseText(e, "this List")
        }
        is MemberAccessExpr -> when (val m = model.member(e)) {
            is MemberRef.ModuleMember -> (m.symbol as? GlobalSymbol)?.let { ref(it, e) } ?: refuseText(e, "this List")
            is MemberRef.Field ->
                if (isUserClass(m.field.owner)) "${wrap(objectOf(e.origin, f), PyPrec.POSTFIX)}.${pyName(m.field)}" else refuseText(e, "this List")
            else -> wrap(expr(e, f), PyPrec.POSTFIX)
        }
        else -> wrap(expr(e, f), PyPrec.POSTFIX)
    }

    private fun callExpr(c: FunctionCallExpr, f: Frame): Py {
        val rc = model.call(c) ?: return refusePy(c, "a call the typer did not resolve")
        val fn = rc.fn
        val userCall = (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD) && fn != null && fn.foreign == null && fn.body != null
        rc.args.forEachIndexed { i, a ->
            if (a is ArgBinding.Given && a.byRef && !(userCall && fn!!.params.getOrNull(i)?.type?.let { isValue(it) || isStruct(it) } == true)) {
                return refusePy(c, "a mut argument to '${fn?.name ?: c.name}' (only a List, a Map or a struct given to a Kira function is passed by reference)")
            }
        }
        return when (rc.kind) {
            CallKind.PRINT -> trace(c, rc, f)
            CallKind.FREE -> when {
                fn == null || !userCall -> refusePy(c, "a call of '${fn?.name ?: c.name}'")
                else -> ref(fn, c)?.let { Py("$it(${userArgs(c, rc, f)})", PyPrec.POSTFIX) } ?: Py("None", PyPrec.ATOM)
            }
            CallKind.METHOD -> {
                if (fn == null || !isUserClass(fn.owner)) {
                    return refusePy(c, "this method call")
                }
                val receiver = rc.receiver
                val obj = when {
                    rc.implicitThis || receiver == null || receiver is ThisExpr -> if (f.cls != null) "self" else return refusePy(c, "a method call outside a method")
                    else -> wrap(objectOf(receiver, f), PyPrec.POSTFIX)
                }
                Py("$obj.${pyName(fn)}(${userArgs(c, rc, f)})", PyPrec.POSTFIX)
            }
            CallKind.MAGIC -> magicCall(c, rc, f)
            else -> refusePy(c, "a ${rc.kind.name.lowercase().replace('_', ' ')} call")
        }
    }

    /** A user function's arguments as written: positionally, then by name, in source order (D33). */
    private fun userArgs(c: FunctionCallExpr, rc: ResolvedCall, f: Frame): String {
        val fn = rc.fn ?: return ""
        val named: Set<Expr> = Collections.newSetFromMap(IdentityHashMap<Expr, Boolean>()).apply { c.namedParameters.forEach { add(it.value) } }
        val parts = mutableListOf<String>()
        var sawNamed = false
        val given = rc.sourceOrder.mapNotNull { k -> (rc.args[k] as? ArgBinding.Given)?.let { k to it } }
        val writes = callWrites(rc)
        given.forEachIndexed { at, (k, b) ->
            val text = if (b.byRef) {
                listPlace(b.expr, f) ?: "None"
            } else {
                wrap(asValue(b.expr, f, Use.ARG, given.drop(at + 1).map { it.second.expr }, writes), PyPrec.TERNARY)
            }
            if (b.expr in named) {
                sawNamed = true
                parts += "${fn.params[k].name}=$text"
            } else {
                if (sawNamed) {
                    refuse(b.expr, "a positional argument after a named one")
                }
                parts += text
            }
        }
        rc.args.filterIsInstance<ArgBinding.Default>().forEach { d ->
            val value = d.param.default
            if (value != null && pyDefault(d.param) == null) {
                parts += "${d.param.name}=${wrap(asValue(value, f, Use.ARG, emptyList(), false), PyPrec.TERNARY)}"
            }
        }
        return parts.joinToString(", ")
    }

    private fun magicCall(c: FunctionCallExpr, rc: ResolvedCall, f: Frame): Py {
        val fn = rc.fn ?: return refusePy(c, "this call")
        val key = (fn.foreign as? Foreign.Magic)?.key
        if (key == "Result.success" || key == "Result.error") {
            // D39: compiler-known, as C++'s static factories are; a Result is (True, value) or (False, error).
            val v = (rc.args.singleOrNull() as? ArgBinding.Given)?.expr ?: return refusePy(c, "$key without its value")
            return Py("(${if (key == "Result.success") "True" else "False"}, ${wrap(asValue(v, f, Use.STORE), PyPrec.TERNARY)})", PyPrec.ATOM)
        }
        if (fn.name == "enumOf" && fn.module.uri == "kira:core") {
            val e = (rc.typeArgs.firstOrNull() as? KType.Nominal)?.sym as? EnumSymbol ?: return refusePy(c, "enumOf of no enum")
            val raw = (rc.args.singleOrNull() as? ArgBinding.Given)?.expr ?: return refusePy(c, "enumOf without its value")
            return call("_k_enumof", "${ref(e, c) ?: "None"}._k_order", wrap(expr(raw, f), PyPrec.TERNARY))
        }
        val receiver = rc.receiver
        val keys = CppBindingTable.keysFor(fn, receiver?.let { model.typeOrNull(it) }, program)
        val binding = keys.firstNotNullOfOrNull { bindings.lookup(it) }
            ?: return refusePy(c, "'${keys.firstOrNull() ?: fn.name}' (it has no py binding)")
        // Python evaluates a binding's arguments in parameter order: named ones are taken only
        // where that is the order they were written in (D33).
        if (rc.args.any { it !is ArgBinding.Given } || rc.sourceOrder != rc.args.indices.toList()) {
            return refusePy(c, "a call of '${keys.firstOrNull() ?: fn.name}' with defaulted arguments, or named ones out of its parameters' order")
        }
        val self: ((Int) -> String)? = receiver?.let { r ->
            { prec ->
                val t = model.typeOrNull(r)
                if (t != null && isValue(t)) listPlace(r, f) ?: "None" else wrap(expr(r, f), prec)
            }
        }
        val args = rc.args.map { (it as ArgBinding.Given).expr }
        // In any order these run the same: nothing writes, and at most one may stop the program.
        val plain = receiver != null && isPlace(receiver) && args.none { model.effect(it) == Effect.IMPURE } &&
            args.count { model.const(it) == null && !isPlace(it) } <= 1
        val text = when {
            plain && binding.statement != null && statementCall === c -> binding.statement
            plain && binding.place != null -> binding.place
            else -> binding.expr
        }
        // addAll copies the elements it takes, so its List is copied only when they hold values.
        val stores = fn.isMutMethod && (fn.name != "addAll" || args.any { a -> holdsValue(model.typeOrNull(a)?.let { elementOf(it) }) })
        // `{list}`: list, or bytearray when the call returns a List<UInt8>.
        val list = if (isBytes(model.typeOrNull(c) ?: KType.Error)) "bytearray" else "list"
        val result = bound(PyBinding(text.replace("{list}", list)), self, args, f, store = stores)
        // These make a new List of the elements they hold, which hold values a write would share.
        val t = model.typeOrNull(c)
        return if (fn.name in SHALLOW && t != null && holdsValue(elementOf(t))) copyOf(result, t) else result
    }

    /** A variable, `this` or a field of one: reading it runs nothing and stops nothing. */
    private fun isPlace(e: Expr): Boolean = when (e) {
        is ThisExpr -> true
        is Identifier -> model.symbolOf(e).let { it is LocalSymbol || it is ParamSymbol || it is FieldSymbol || (it is GlobalSymbol && it.module === module) }
        is MemberAccessExpr -> (model.member(e) as? MemberRef.Field)?.field?.owner.let { isUserClass(it) } && isPlace(e.origin)
        else -> false
    }

    /** A value a mutating method stores ([store]: `xs.add(v)`, `m.put(k, v)`) is a copy, as C++ copies it in. */
    private fun bound(binding: PyBinding, self: ((Int) -> String)?, args: List<Expr>, f: Frame, store: Boolean = false): Py {
        val text = PyBindingTable.expand(binding, self) { i, prec ->
            args.getOrNull(i)?.let { wrap(if (store) asValue(it, f, Use.STORE) else expr(it, f), prec) } ?: "None"
        }
        PyRuntime.HELPER.findAll(binding.expr).forEach { helpers.add(it.value) }
        return Py(text, binding.prec)
    }

    /** eprint flushes, as C++'s stderr is unbuffered. */
    private fun trace(c: FunctionCallExpr, rc: ResolvedCall, f: Frame): Py {
        val tail = when (rc.fn?.name) {
            null, "println" -> ""
            "print" -> ", end=\"\""
            "eprint" -> ", end=\"\", file=${helper("_k_sys")}.stderr, flush=True"
            else -> return refusePy(c, "'${rc.fn.name}'")
        }
        val arg = (rc.args.singleOrNull() as? ArgBinding.Given)?.expr ?: return Py("print()", PyPrec.POSTFIX)
        val t = typeOf(arg) ?: return Py("None", PyPrec.ATOM)
        val v = expr(arg, f)
        val prim = t.prim
        val text = when {
            t == KType.Str || prim?.isInteger == true || isEnum(t) -> v.text
            prim == Prim.CHAR -> call("chr", v.text).text
            prim == Prim.BOOL -> "1 if ${wrap(v, PyPrec.OR)} else 0"
            prim == Prim.FLOAT64 -> call("_k_gtext", v.text).text
            else -> return refusePy(arg, "${rc.fn?.name ?: "trace"} of ${article(t.display())}")
        }
        return Py("print($text$tail)", PyPrec.POSTFIX)
    }

    private fun helper(name: String): String {
        helpers.add(name)
        return name
    }

    /** A Tuple is a Python tuple, its values copies in field order, which must be the written order unless each is PURE (D33). */
    private fun tuple(o: ObjectInitExpr, init: ResolvedInit, f: Frame): Py {
        val given = init.fields.map { it as? FieldInit.Given ?: return refusePy(o, "a Tuple without its ${it.field.name}") }
        if (init.sourceOrder != init.sourceOrder.sorted() && given.any { model.effect(it.expr) != Effect.PURE }) {
            return refusePy(o, "a Tuple whose values, written out of their order, have effects")
        }
        val items = given.map { wrap(asValue(it.expr, f, Use.STORE), PyPrec.TERNARY) }
        return Py("(${items.joinToString(", ")}${if (items.size == 1) "," else ""})", PyPrec.ATOM)
    }

    private fun construction(o: ObjectInitExpr, f: Frame): Py {
        val init = model.init(o) ?: return refusePy(o, "a construction the typer did not resolve")
        val cls = init.cls ?: return refusePy(o, "this construction")
        if (cls.kind == ClassKind.MAGIC) {
            val t = typeOf(o) ?: return Py("None", PyPrec.ATOM)
            if (isTuple(t)) {
                return tuple(o, init, f)
            }
            if (!isValue(t)) {
                return refusePy(o, "a construction of ${t.display()}")
            }
            val given = init.fields.filterIsInstance<FieldInit.Given>()
            return when {
                given.isEmpty() -> zeroValue(t, o).let { Py(it, if (it.contains(" * ")) PyPrec.MUL else if (it == "{}") PyPrec.ATOM else PyPrec.POSTFIX) }
                given.size == 1 && cls.name == "List" -> asValue(given[0].expr, f, Use.STORE)
                given.size == 1 && cls.name == "Map" -> call("dict", asValue(given[0].expr, f, Use.STORE).text)
                given.size == 1 && cls.name == "Set" -> Py("dict.fromkeys(${expr(given[0].expr, f).text})", PyPrec.POSTFIX)
                else -> refusePy(o, "${article(cls.name)} construction with these values")
            }
        }
        if (!isUserClass(cls)) {
            return refusePy(o, "a construction of ${cls.name}")
        }
        val clsName = ref(cls, o) ?: return Py("None", PyPrec.ATOM)
        val parts = mutableListOf<String>()
        var sawNamed = false
        val given = init.sourceOrder.mapNotNull { k -> init.fields[k] as? FieldInit.Given }
        // The construction's defaults and `initially` run before __init__ copies a List.
        val writes = !CallReach.construction(cls, given.associate { it.field to it.expr }, model)
        // A value for a field __init__ takes by name makes every value one by name, in source order.
        val byName = given.any { isKeyword(it.field) }
        given.forEachIndexed { at, fi ->
            if (!isPositional(fi.field) && !isKeyword(fi.field)) {
                refuse(fi.expr, "a value given to the private field '${fi.field.name}' at a construction (__init__ takes a pub one by name)")
            }
            // __init__ copies a List it stores, after the defaults before it ran: a List is
            // copied here, as an argument is, when they or a later value may write it.
            val text = wrap(asValue(fi.expr, f, Use.ARG, given.drop(at + 1).map { it.expr }, writes), PyPrec.TERNARY)
            if (fi.named || byName) {
                sawNamed = true
                parts += "${fi.field.name}=$text"
            } else {
                if (sawNamed) {
                    refuse(fi.expr, "a positional value after a named one")
                }
                parts += text
            }
        }
        return Py("$clsName(${parts.joinToString(", ")})", PyPrec.POSTFIX)
    }

    private fun index(e: ArrayIndexExpr, f: Frame): Py {
        val ct = typeOf(e.originExpr) ?: return Py("None", PyPrec.ATOM)
        if (ct == KType.Str) {
            // s[i] is the Char at code point i: its code, as Str.at binds it.
            return call("ord", "${wrap(expr(e.originExpr, f), PyPrec.POSTFIX)}[${indexText(e.indexExpr, f)}]")
        }
        if (!isList(ct) && !isView(ct)) {
            return refusePy(e, "an index into ${article(ct.display())}")
        }
        val container = listPlace(e.originExpr, f) ?: return Py("None", PyPrec.ATOM)
        return Py("$container[${indexText(e.indexExpr, f)}]", PyPrec.POSTFIX)
    }

    /** An index: an unsigned integer, which Python never reads from the end as it does a negative one. */
    private fun indexText(i: Expr, f: Frame): String {
        val prim = model.typeOrNull(i)?.prim
        if (prim == null || !prim.isInteger || prim.signed) {
            return refuseText(i, "an index that is not an unsigned integer") ?: "0"
        }
        return wrap(expr(i, f), PyPrec.TERNARY)
    }

    private fun binary(e: BinaryExpr, f: Frame): Py {
        if (model.opCall(e) != null) {
            return refusePy(e, "an operator overload")
        }
        val op = e.operator
        return when (op) {
            BinaryOp.AND -> infix(expr(e.leftExpr, f), "and", expr(e.rightExpr, f), PyPrec.AND)
            BinaryOp.OR -> infix(expr(e.leftExpr, f), "or", expr(e.rightExpr, f), PyPrec.OR)
            in COMPARISONS -> comparison(e, f)
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD, in BITS -> {
                val t = typeOf(e) ?: return Py("None", PyPrec.ATOM)
                arith(op, t, expr(e.leftExpr, f), expr(e.rightExpr, f), e.rightExpr, e)
            }
            else -> refusePy(e, "the operator ${spelled(op)}")
        }
    }

    /** A comparison of numbers, Bools, Strs or enums, or `==` and `!=` of two structs (their `__eq__`, C++'s defaulted operator==). */
    private fun comparison(e: BinaryExpr, f: Frame): Py {
        val lt = typeOf(e.leftExpr) ?: return Py("None", PyPrec.ATOM)
        val rt = typeOf(e.rightExpr) ?: return Py("None", PyPrec.ATOM)
        val structs = isStruct(lt) && isStruct(rt) && (e.operator == BinaryOp.EQUALS || e.operator == BinaryOp.NOT_EQUAL)
        if (!structs && ((lt != KType.Str && lt !is KType.Scalar && !isEnum(lt)) || (rt != KType.Str && rt !is KType.Scalar && !isEnum(rt)))) {
            return refusePy(e, "a comparison of ${lt.display()} and ${rt.display()}")
        }
        val l = expr(e.leftExpr, f)
        val r = expr(e.rightExpr, f)
        return Py("${wrap(l, PyPrec.CMP + 1)} ${COMPARISONS.getValue(e.operator)} ${wrap(r, PyPrec.CMP + 1)}", PyPrec.CMP)
    }

    /** `l op r` of type [t] in Kira's arithmetic ([PyModuleEmitter]'s semantics). */
    private fun arith(op: BinaryOp, t: KType, l: Py, r: Py, right: Expr, at: Expr): Py {
        if (op in BITS) {
            val prim = t.prim
            if (prim?.isInteger != true) {
                return refusePy(at, "a bitwise operator on ${article(t.display())}")
            }
            return when (op) {
                BinaryOp.CONJUNCTIVE_AND -> infix(l, "&", r, PyPrec.BAND)
                BinaryOp.CONJUNCTIVE_OR -> infix(l, "|", r, PyPrec.BOR)
                BinaryOp.XOR -> infix(l, "^", r, PyPrec.BXOR)
                else -> shift(op, prim, l, r, right)
            }
        }
        if (t == KType.Str) {
            return if (op == BinaryOp.ADD) infix(l, "+", r, PyPrec.ADD) else refusePy(at, "the operator ${spelled(op)} on Str")
        }
        val prim = t.prim ?: return refusePy(at, "arithmetic on ${article(t.display())}")
        val sym = when (op) {
            BinaryOp.ADD -> "+"
            BinaryOp.SUB -> "-"
            BinaryOp.MUL -> "*"
            else -> null
        }
        if (prim == Prim.FLOAT64) {
            return when {
                sym != null -> infix(l, sym, r, if (op == BinaryOp.MUL) PyPrec.MUL else PyPrec.ADD)
                op == BinaryOp.DIV -> {
                    val divisor = (model.const(right) as? ConstValue.FloatConst)?.value
                    if (divisor != null && divisor != 0.0 && !divisor.isNaN()) infix(l, "/", r, PyPrec.MUL) else call("_k_fdiv", l.text, r.text)
                }
                else -> refusePy(at, "the operator ${spelled(op)} on Float64")
            }
        }
        if (!prim.isInteger) {
            return refusePy(at, "arithmetic on ${article(t.display())}")
        }
        return when (op) {
            BinaryOp.DIV -> if (prim.signed) call("_k_divs", l.text, r.text, prim.bits.toString()) else call("_k_divu", l.text, r.text)
            BinaryOp.MOD -> if (prim.signed) call("_k_mods", l.text, r.text) else call("_k_modu", l.text, r.text)
            else -> intResult(prim, infix(l, sym ?: return refusePy(at, "the operator ${spelled(op)}"), r, if (op == BinaryOp.MUL) PyPrec.MUL else PyPrec.ADD))
        }
    }

    /**
     * `v << n`, `v >> n` and `v >>> n` in [prim], as the C++ target's (R12): a count outside
     * `0 until` the width, of whatever integer type, stops the program (`_k_count`, as
     * kira::shl and kira::shr do), checked unless it is a constant inside; `<<` wraps into the
     * type, signed ones included, as C++20 defines it; `>>` is Python's, arithmetic on a negative
     * value as C++20's; `>>>` shifts a signed value's bits as its unsigned counterpart's and
     * reads the result back as signed.
     */
    private fun shift(op: BinaryOp, prim: Prim, v: Py, n: Py, count: Expr): Py {
        val c = (model.const(count) as? ConstValue.IntConst)?.value
        val inside = c != null && c.signum() >= 0 && c < BigInteger.valueOf(prim.bits.toLong())
        val k = if (inside) n else call("_k_count", n.text, prim.bits.toString())
        return when {
            op == BinaryOp.SHL -> call(AS_HELPERS.getValue(prim), infix(v, "<<", k, PyPrec.SHIFT).text)
            op == BinaryOp.USHR && prim.signed -> {
                val bits = infix(v, "&", Py(MASKS.getValue(prim.bits), PyPrec.ATOM), PyPrec.BAND)
                call(AS_HELPERS.getValue(prim), infix(bits, ">>", k, PyPrec.SHIFT).text)
            }
            else -> infix(v, ">>", k, PyPrec.SHIFT)
        }
    }

    /** An integer result in its type: checked for Int32 and Int64 (D8), wrapped for the rest. */
    private fun intResult(prim: Prim, p: Py): Py = call(INT_HELPERS.getValue(prim), p.text)

    private fun unary(e: UnaryExpr, f: Frame): Py {
        val t = typeOf(e) ?: return Py("None", PyPrec.ATOM)
        val operand = expr(e.operand, f)
        val prim = t.prim
        return when (e.operator) {
            UnaryOp.NOT -> Py("not ${wrap(operand, PyPrec.NOT)}", PyPrec.NOT)
            UnaryOp.POS -> operand
            UnaryOp.NEG -> when {
                prim == Prim.FLOAT64 -> Py("-${wrap(operand, PyPrec.UNARY)}", PyPrec.UNARY)
                prim?.isInteger == true -> intResult(prim, Py("-${wrap(operand, PyPrec.UNARY)}", PyPrec.UNARY))
                else -> refusePy(e, "a minus on ${article(t.display())}")
            }
            UnaryOp.BIT_NOT -> when {
                prim?.isInteger == true && prim.signed -> Py("~${wrap(operand, PyPrec.UNARY)}", PyPrec.UNARY)
                prim?.isInteger == true -> intResult(prim, Py("~${wrap(operand, PyPrec.UNARY)}", PyPrec.UNARY))
                else -> refusePy(e, "a ~ on ${article(t.display())}")
            }
        }
    }

    /**
     * `x as T` (R13): integers wrap, a float saturates into an integer, an integer widens to
     * Float64. A Char is its code point: `as` an integer type that holds every code point is the
     * identity, and a narrower one wraps it, the identity for ASCII; an integer as a Char is its
     * low 8 bits, as C++'s static_cast<char>.
     */
    private fun cast(e: TypeCastExpr, f: Frame): Py {
        val kind = model.conversion(e) ?: return refusePy(e, "an `as` the typer did not resolve")
        val from = typeOf(e.value) ?: return Py("None", PyPrec.ATOM)
        val to = typeOf(e) ?: return Py("None", PyPrec.ATOM)
        // A Char literal and the integer it converts to (`'0' as UInt8`) are written as their codes.
        when (val k = model.const(e)) {
            is ConstValue.CharConst -> return Py(k.value.toString(), PyPrec.ATOM)
            is ConstValue.IntConst -> if (kind == ConversionKind.CHAR_TO_INT) return Py(k.value.toString(), PyPrec.ATOM)
            else -> {}
        }
        val v = expr(e.value, f)
        val fp = from.prim
        val tp = to.prim
        return when (kind) {
            ConversionKind.INT_WRAP -> if (fp != null && tp != null && fits(fp, tp)) v else call(AS_HELPERS.getValue(tp!!), v.text)
            ConversionKind.INT_TO_FLOAT -> call("float", v.text)
            ConversionKind.FLOAT_TO_INT_SAT -> call("_k_f2i", v.text, tp!!.bits.toString(), if (tp.signed) "True" else "False")
            ConversionKind.CHAR_TO_INT -> if (tp!!.bits >= 32) v else call(AS_HELPERS.getValue(tp), v.text)
            ConversionKind.INT_TO_CHAR -> if (fp != null && fits(fp, Prim.UINT8)) v else call("_k_u8", v.text)
            ConversionKind.TO_STR -> text(e.value, from, v) ?: Py("None", PyPrec.ATOM)
            ConversionKind.ENUM_TO_BASE -> v
            else -> refusePy(e, "the conversion ${from.display()} as ${to.display()}")
        }
    }

    /** Whether every value of [from] is a value of [to], so `as` changes nothing. */
    private fun fits(from: Prim, to: Prim): Boolean = when {
        from.signed -> to.signed && to.bits >= from.bits
        else -> if (to.signed) to.bits > from.bits else to.bits >= from.bits
    }

    /**
     * [v], the Python of [e] of type [t], as Kira's text (kira::text): a Bool is true or false,
     * a Char its character, a Float64 the shortest text std::to_chars writes (D50).
     */
    private fun text(e: Expr, t: KType, v: Py): Py? {
        val prim = t.prim
        return when {
            t == KType.Str -> v
            prim?.isInteger == true -> call("str", v.text)
            prim == Prim.CHAR -> call("chr", v.text)
            prim == Prim.BOOL -> call("_k_btext", v.text)
            prim == Prim.FLOAT64 -> call("_k_ftext", v.text)
            isEnum(t) -> ref((t as KType.Nominal).sym as EnumSymbol, e)?.let { Py("$it._k_names.get(${v.text}, \"\")", PyPrec.POSTFIX) }
            else -> {
                refuse(e, "${article(t.display())} as text")
                null
            }
        }
    }

    private fun interpolation(e: InterpolatedStringLiteral, f: Frame): Py {
        val parts = e.parts.mapNotNull { part ->
            when (part) {
                is InterpolationPart.Text -> if (part.text.isEmpty()) null else Py(pyString(part.text), PyPrec.ATOM)
                is InterpolationPart.Hole -> {
                    val t = typeOf(part.expr) ?: return Py("None", PyPrec.ATOM)
                    text(part.expr, t, expr(part.expr, f)) ?: return Py("None", PyPrec.ATOM)
                }
            }
        }
        return when (parts.size) {
            0 -> Py("\"\"", PyPrec.ATOM)
            1 -> parts[0]
            else -> parts.drop(1).fold(parts[0]) { acc, p -> infix(acc, "+", p, PyPrec.ADD) }
        }
    }

    private fun ternary(e: IfExpr, f: Frame): Py {
        val yes = branchValue(e.thenBranch) ?: return refusePy(e, "an if-expression whose branch is not one value")
        val no = branchValue(e.elseBranch) ?: return refusePy(e, "an if-expression whose branch is not one value")
        val c = expr(e.condition, f)
        return Py("${wrap(expr(yes, f), PyPrec.OR)} if ${wrap(c, PyPrec.OR)} else ${wrap(expr(no, f), PyPrec.TERNARY)}", PyPrec.TERNARY)
    }

    private fun branchValue(statements: List<Statement>): Expr? {
        val s = statements.singleOrNull() ?: return null
        if (s.javaClass != Statement::class.java) {
            return null
        }
        return s.expr.takeIf { it !is Decl && it !is AssignmentExpr && it !is CompoundAssignmentExpr && it !is PlaceAssignmentExpr && it !== NoExpr }
    }

    // ---- types, names, values ----------------------------------------------------------------

    /** [op] as Kira spells it (`<<`, `&`, `%`). */
    private fun spelled(op: BinaryOp): String = op.symbol.joinToString("") { it.rep.toString() }

    private fun magicName(t: KType?): String? = CppBindingTable.magicName(t)

    private fun isMaybe(t: KType): Boolean = magicName(t) == "Maybe"

    /** A List, or an Arr (growable or fixed), which is one too: a Python list, a bytearray of UInt8. */
    private fun isList(t: KType): Boolean = magicName(t).let { it == "List" || it == "Arr" }

    /** A List or Arr of UInt8: a bytearray. */
    private fun isBytes(t: KType): Boolean = isList(t) && (t as? KType.Nominal)?.typeArgs()?.firstOrNull() == KType.UINT8

    private fun isMap(t: KType?): Boolean = magicName(t) == "Map"

    private fun isSet(t: KType?): Boolean = magicName(t) == "Set"

    private fun isStack(t: KType?): Boolean = magicName(t) == "Stack"

    private fun isQueue(t: KType?): Boolean = magicName(t).let { it == "Queue" || it == "Deque" }

    /** A value (D44) Python shares: copied where a second name could see a write. */
    private fun isValue(t: KType): Boolean = isList(t) || isMap(t) || isSet(t) || isStack(t) || isQueue(t)

    /** A View or MutView: what it was lent from (a list, a bytearray), a memoryview of bytes, or a copied slice. */
    private fun isView(t: KType): Boolean = magicName(t).let { it == "View" || it == "MutView" }

    private fun isUserClass(owner: Any?): Boolean =
        owner is ClassSymbol && (owner.kind == ClassKind.CLASS || owner.kind == ClassKind.STRUCT) && !owner.module.isStdlib && owner.typeParams.isEmpty()

    private fun isStruct(t: KType?): Boolean = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.let { it.kind == ClassKind.STRUCT && isUserClass(it) } == true

    /** A value Python would share where Kira copies it: a container, a struct, a Maybe of one. */
    private fun holdsValue(t: KType?): Boolean =
        t != null && (isValue(t) || isStruct(t) || (isMaybe(t) && holdsValue((t as KType.Nominal).typeArgs().firstOrNull())))

    private fun elementOf(t: KType): KType? = (t as? KType.Nominal)?.typeArgs()?.let { if (isMap(t)) it.getOrNull(1) else it.firstOrNull() }

    /** Why the py target cannot hold a value of [t], or null when it can. */
    private fun unsupportedType(t: KType): String? = when (t) {
        is KType.Scalar -> when (t.prim) {
            Prim.FLOAT32 -> "Float32 (Float64 is Python's float)"
            else -> null
        }
        KType.Str, KType.Void, KType.NullT, KType.Never -> null
        is KType.Nominal -> when (val s = t.sym) {
            is ClassSymbol -> when {
                s.kind == ClassKind.MAGIC && s.name in setOf("Maybe", "List", "Arr", "View", "MutView") -> {
                    val inner = t.typeArgs().singleOrNull()
                    when {
                        inner == null -> "${article(s.name)} without its element type"
                        isView(inner) || (isMaybe(t) && isMaybe(inner)) -> article(t.display())
                        // Text is a Str, never a view of Chars; a Str's view would index as a str.
                        isView(t) && inner == KType.CHAR -> "${article(t.display())} (text is a Str on the py target)"
                        // A view of other elements is a copied slice, which a write would not reach.
                        s.name == "MutView" && inner != KType.UINT8 -> "${article(t.display())} (a MutView<UInt8> is the one that writes through)"
                        else -> unsupportedType(inner)
                    }
                }
                s.kind == ClassKind.MAGIC && s.name == "Map" -> mapRefusal(t)
                s.kind == ClassKind.MAGIC && (isSet(t) || isStack(t) || isQueue(t)) -> {
                    val e = t.typeArgs().singleOrNull()
                    when {
                        e == null -> "${article(s.name)} without its element type"
                        isSet(t) -> keyRefusal(e, t)
                        isView(e) -> article(t.display())
                        else -> unsupportedType(e)
                    }
                }
                s.kind == ClassKind.MAGIC && s.name == "Result" -> resultRefusal(t)
                s.kind == ClassKind.MAGIC && isTuple(t) -> t.typeArgs().firstNotNullOfOrNull { a ->
                    unsupportedType(a) ?: if (isView(a)) "${article(t.display())} (a view lives only as long as the call it is lent to)" else null
                }
                s.kind == ClassKind.MAGIC -> "the type ${s.name}"
                isUserClass(s) -> null
                else -> "the ${if (s.typeParams.isNotEmpty()) "generic " else ""}${s.kind.name.lowercase()} ${s.name}"
            }
            is EnumSymbol -> if (s.module.isStdlib || s.foreign != null) "the enum ${s.name}" else null
            is TraitSymbol -> "the trait ${s.name}"
            else -> "the type ${t.display()}"
        }
        is KType.Fn -> "an Fx value"
        is KType.Param -> "a type parameter"
        KType.Error -> "an untyped value"
    }

    /** A NaN key is a new entry at each put in kira::Map; a Map of Maybes is refused where its get, a Maybe of a Maybe, is read. */
    private fun mapRefusal(t: KType.Nominal): String? {
        val (k, v) = t.typeArgs().takeIf { it.size == 2 } ?: return "a Map without its key and value types"
        return keyRefusal(k, t) ?: if (isView(v)) "${article(t.display())} (a Map's value is no view)" else unsupportedType(v)
    }

    private fun keyRefusal(k: KType, t: KType): String? {
        val kp = k.prim
        val keyed = k == KType.Str || isEnum(k) || (kp != null && (kp.isInteger || kp == Prim.BOOL || kp == Prim.CHAR))
        return when {
            kp == Prim.FLOAT64 || kp == Prim.FLOAT32 -> "${article(t.display())} (a float key: a NaN key differs between kira::Map and a dict)"
            unsupportedType(k) != null -> unsupportedType(k)
            !keyed -> "${article(t.display())} (a Map's key is a Str, an integer, a Bool, a Char or an enum)"
            else -> null
        }
    }

    /** A Result, as a Tuple, is immutable: what goes in is a copy and what comes out is copied where it is kept. */
    private fun resultRefusal(t: KType.Nominal): String? {
        val args = t.typeArgs().takeIf { it.size == 2 } ?: return "a Result without its value and error types"
        return args.firstNotNullOfOrNull { a ->
            unsupportedType(a) ?: if (isView(a)) "${article(t.display())} (a view lives only as long as the call it is lent to)" else null
        }
    }

    private fun isTuple(t: KType?): Boolean = magicName(t)?.let { TUPLE.matches(it) } == true

    private fun article(word: String): String = if (word.first() in "AEIOaeio") "an $word" else "a $word"

    private fun checkType(t: KType, at: ASTNode, what: String) {
        unsupportedType(t)?.let { refuse(at, "$what: $it") }
    }

    private fun checkName(name: String, at: ASTNode) {
        PyNames.refusal(name)?.let { refuse(at, "the name $it") }
    }

    /** A local or parameter that takes a module declaration's Python name would hide it from the whole function. */
    private fun checkLocalName(name: String, at: ASTNode) {
        if (name in topNames) {
            refuse(at, "a local or parameter named like the module's own '$name'")
        }
    }

    /** A Kira-written stdlib function is `_k_kira_math_clamp`: every module that calls one carries its own copy. */
    private fun pyName(sym: Symbol): String {
        if (sym.module.isStdlib && (sym is FnSymbol && sym.owner == null || sym is GlobalSymbol)) {
            return "_k_${sanitized(sym.module.uri)}_${sym.name}"
        }
        val pub = when (sym) {
            is FnSymbol -> sym.isPub
            is ClassSymbol -> sym.isPub
            is EnumSymbol -> sym.isPub
            is GlobalSymbol -> sym.isPub
            is FieldSymbol -> sym.isPub
            else -> true
        }
        return if (pub) sym.name else "_${sym.name}"
    }

    private fun sanitized(text: String): String =
        text.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }.joinToString("")

    /**
     * A value of [t] before anything assigns one (D38): 0, 0.0, False, "", None, [], bytearray(),
     * {}; an `Arr<T, N>` holds N zeros (`[0] * N`, `bytearray(N)`).
     */
    private fun zeroValue(t: KType, at: ASTNode): String {
        val prim = t.prim
        val n = (t as? KType.Nominal)?.takeIf { isList(it) }?.constArgs()?.firstOrNull()
        return when {
            prim == Prim.BOOL -> "False"
            prim == Prim.FLOAT64 -> "0.0"
            prim?.isInteger == true || prim == Prim.CHAR || isEnum(t) -> "0"
            t == KType.Str -> "\"\""
            isMaybe(t) -> "None"
            magicName(t) == "Result" -> "(False, ${zeroValue((t as KType.Nominal).typeArgs()[1], at)})"
            isTuple(t) -> (t as KType.Nominal).typeArgs().map { zeroValue(it, at) }.let { "(${it.joinToString(", ")}${if (it.size == 1) "," else ""})" }
            isBytes(t) -> if (n == null) "bytearray()" else "bytearray($n)"
            isList(t) && n == null -> "[]"
            isList(t) && holdsValue(elementOf(t)) -> "[${zeroValue(elementOf(t)!!, at)} for _k_i in range($n)]"
            isList(t) -> "[${zeroValue(elementOf(t)!!, at)}] * $n"
            isMap(t) || isSet(t) -> "{}"
            isStack(t) -> "[]"
            isQueue(t) -> "${helper("_k_collections")}.deque()"
            isStruct(t) -> ((t as KType.Nominal).sym as ClassSymbol).let { s ->
                "${ref(s, at) ?: "None"}(${s.fields.filter { isPositional(it) }.joinToString(", ") { zeroValue(it.type, at) }})"
            }
            else -> refuseText(at, "${article(t.display())} without a value") ?: "None"
        }
    }

    /** A folded constant as a Python literal, or null for one Python has no literal for. */
    private fun constText(c: ConstValue): String? = when (c) {
        is ConstValue.IntConst -> c.value.toString()
        is ConstValue.FloatConst -> if (c.prim == Prim.FLOAT64 && c.value.isFinite()) floatText(c.value) else null
        is ConstValue.BoolConst -> if (c.value) "True" else "False"
        is ConstValue.StrConst -> pyString(c.value)
        is ConstValue.CharConst -> c.value.toString()
        is ConstValue.EnumConst -> enumValue(c.entry).toString()
        is ConstValue.NullConst -> "None"
        else -> null
    }

    // ---- diagnostics -------------------------------------------------------------------------

    private fun refuse(at: ASTNode, construct: String) {
        if (!reported.add(at)) {
            return
        }
        val where = program.locate(at)
        diagnostics += CppDiagnostic(
            UNSUPPORTED_CODE,
            "$construct is not supported on the py target yet",
            file = where?.first?.file ?: module.source.file,
            position = where?.second,
        )
    }

    private fun refusePy(at: ASTNode, construct: String): Py {
        refuse(at, construct)
        return Py("None", PyPrec.ATOM)
    }

    private fun refuseText(at: ASTNode, construct: String): String? {
        refuse(at, construct)
        return null
    }

    companion object {
        const val UNSUPPORTED_CODE = "py.unsupported"

        /** The methods that lend a view of their receiver, a MutView of a mutable List or Arr. */
        private val LENDERS = setOf("from", "slice", "view")

        private val TUPLE = Regex("Tuple[0-9]")

        private val SHALLOW = setOf("toArr", "clone", "toList", "valuesArr")

        private val BORROWERS = setOf("get", "unwrap", "unwrapOr", "unwrapErr", "peek")

        private val COMPARISONS = mapOf(
            BinaryOp.EQUALS to "==",
            BinaryOp.NOT_EQUAL to "!=",
            BinaryOp.LESS_THAN to "<",
            BinaryOp.LESS_THAN_OR_EQUAL to "<=",
            BinaryOp.GREATER_THAN to ">",
            BinaryOp.GREATER_THAN_OR_EQUAL to ">=",
        )

        /** The result of `+`, `-`, `*` and unary `-` in each integer type. */
        private val INT_HELPERS = mapOf(
            Prim.INT8 to "_k_i8", Prim.INT16 to "_k_i16", Prim.INT32 to "_k_i32", Prim.INT64 to "_k_i64",
            Prim.UINT8 to "_k_u8", Prim.UINT16 to "_k_u16", Prim.UINT32 to "_k_u32", Prim.UINT64 to "_k_u64", Prim.SIZE to "_k_u64",
        )

        /** `as` into each integer type: a wrap, Int32 and Int64 included. */
        private val AS_HELPERS = INT_HELPERS + mapOf(Prim.INT32 to "_k_as_i32", Prim.INT64 to "_k_as_i64")

        /** The bitwise operators and the shifts: integers only. */
        private val BITS = setOf(BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR, BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR)

        /** A width's bits, which `>>>` keeps of a signed value. */
        private val MASKS = mapOf(8 to "0xFF", 16 to "0xFFFF", 32 to "0xFFFFFFFF", 64 to "0xFFFFFFFFFFFFFFFF")

        /** A Float64 as a Python literal that reads back to the same double (Java's shortest repr). */
        fun floatText(v: Double): String {
            val s = java.lang.Double.toString(v)
            return if (s.contains('.') || s.contains('E') || s.contains('e')) s else "$s.0"
        }

        /** [s] as a Python string literal. */
        fun pyString(s: String): String {
            val sb = StringBuilder("\"")
            s.forEach { c ->
                when {
                    c == '\\' -> sb.append("\\\\")
                    c == '"' -> sb.append("\\\"")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 || c.code == 0x7F -> sb.append(String.format("\\x%02x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
