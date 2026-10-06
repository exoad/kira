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
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.ResolvedCall
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
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
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
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One Kira module as one Python module (Python 3.10 and later), from the typed model.
 *
 * The py target lowers what bibo's dashboard needs and refuses the rest with
 * `py.unsupported`, naming the construct (DECISIONS rule 0: one simple way, no corners):
 * - one module per program: no `use` of another workspace module; the stdlib's magic
 *   functions and methods bind through the `py:` blocks of the `.bind.yaml` manifests;
 * - module constants and `mut` globals, functions, and classes without a parent, a trait,
 *   type parameters or a `finally`. A class is a plain Python class, `__slots__` its fields;
 * - Bool, Str (a Python str, whose lengths and indices count code points where C++ counts UTF-8
 *   bytes: the same for ASCII; its methods are kira::str's through core.bind.yaml), Char (its
 *   code point, an int: a literal is its code, `s[i]` is ord() of the character, its text is
 *   chr() of it, so one from 128 to 255 is a Latin-1 code point where C++ writes the raw byte,
 *   and only an ASCII Char agrees; a View<Char> is refused, as text is a Str), the integers,
 *   Float64, Maybe<T> (None or the value), List<T> and Arr<T> (and
 *   Arr<T, N>) as a Python list, a bytearray of UInt8, Map<K, V> as a dict, whose order is
 *   kira::Map's (D27), keyed by a Str, an integer, a Bool or a Char (`m[k] = v` puts; entries, a
 *   List of Tuple2, is refused), and the module's own classes.
 *   Kira's List and Map are values (D44) and a Python list or dict is shared, so one is copied
 *   wherever a second name could see a write ([asValue]); a `mut` List or Map parameter is the
 *   caller's own, and a field, global or `mut` parameter assigned keeps its list or dict and
 *   takes the new elements, as C++'s `T&` sees them;
 * - View<T> and MutView<UInt8>, which are second-class (decision 4b: only an argument, a
 *   receiver or a return, so none outlives the call that consumes it): a view is what it was
 *   lent from, and its from and slice are checked memoryviews of bytes, which a MutView writes
 *   through, or copied slices of any other element, which only a View, read-only, can be;
 *   kira:bytes reads and writes little-endian through `_k_` helpers that stop the program on a
 *   short view, as kira::View's slice does;
 * - if/else, while, break, continue, return, locals, assignments, calls, constructions, `as`,
 *   if-expressions, interpolation and `trace`.
 *
 * **Names.** A `pub` declaration keeps its Kira name, so hand-written Python constructs and
 * calls it; a private module declaration or member is `_name`; parameters and locals keep
 * theirs. A class's constructor takes its `require` fields, in declaration order, positionally
 * or by name. A field is an attribute and a method a method: Python reads `x.level` for a `pub`
 * field and calls `x.level()` for a method; there are no properties.
 *
 * **Semantics** where Python's own differ, as the C++ backend gives them: integer `/` truncates
 * and `%` takes the dividend's sign, a zero divisor stops the program (`_k_divs`, `_k_mods`);
 * Int32 and Int64 overflow stops the program (D8), Int8 and Int16 wrap (R1), every unsigned type
 * wraps; Float64 `/` by zero is IEEE's; `as` wraps between integers and saturates from a float,
 * a Char `as` an integer type too narrow for every code point wraps (the identity for ASCII) and
 * an integer `as` a Char keeps its low 8 bits, as C++'s static_cast<char>;
 * a shift count outside the width stops the program and `<<` wraps, signed types included
 * ([shift]); kira:math's functions are C's on a double (the `_k_` helpers its manifest binds),
 * where a NaN's sign is the machine's and not Kira's on either target (an x86 C++ build may trace
 * -nan where Python traces nan); a Float64 as text is the shortest text std::to_chars writes and
 * `fixed` is C's %.*f, any NaN nan in both (D50), and `toHex` is %x (D51); an index past the
 * end of a List, Arr, view or Str stops the program as Python's IndexError, the other panics as
 * `_k_panic`'s RuntimeError; D33 and OQ-1 hold because Python evaluates operands, arguments and
 * an augmented target left to right, reading the target first, and an assignment whose value
 * has an effect has its index computed first, where Python would compute it after the value.
 */
class PyModuleEmitter(
    private val program: TypedProgram,
    private val module: ModuleSymbol,
    private val bindings: PyBindingTable,
    private val runtime: PyRuntime,
) {
    private val model = program.model
    val diagnostics = mutableListOf<CppDiagnostic>()
    private val helpers = LinkedHashSet<String>()
    private val reported: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())

    /** The Python names of the module's own top-level declarations: a local may not take one. */
    private val topNames = HashSet<String>()

    /** The module's text, [header] lines first, or null when anything was refused. */
    fun emit(header: List<String>): String? {
        module.uses.forEach { use ->
            val uri = use.uri.value
            if (!uri.startsWith("kira:")) {
                refuse(use, "a use of another module ('$uri'): a program is one module on the py target")
            }
        }
        if (module.operators.isNotEmpty()) {
            refuse(module.operators.first().decl ?: module.source.ast, "an operator overload")
        }
        module.declarations.forEach { sym ->
            when (sym) {
                is FnSymbol, is ClassSymbol, is GlobalSymbol -> topNames.add(pyName(sym))
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
                is EnumSymbol -> refuse(sym.decl ?: module.source.ast, "the enum ${sym.name}")
                is TraitSymbol -> refuse(sym.decl ?: module.source.ast, "the trait ${sym.name}")
                else -> refuse(sym.decl ?: module.source.ast, "the declaration ${sym.name}")
            }
        }
        val entry = main?.let { mainCall(it) }
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
        definitions.forEach { sections += it.joinToString("\n") }
        if (globals.isNotEmpty()) {
            sections += initOrder(globals.keys.toList()).joinToString("\n") { globals.getValue(it) }
        }
        entry?.let { sections += it }
        sections.forEach { out.append("\n\n").append(it).append('\n') }
        return out.toString()
    }

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
            if (p.byRef && !isValue(p.type)) {
                refuse(node, "the mut parameter '${p.name}': ${p.type.display()} (only a List or a Map is passed by reference)")
            }
            if (p.default != null) {
                refuse(node, "the default value of the parameter '${p.name}'")
            }
            frame.scopes.first().add(p.name)
            p.name
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
            c.kind != ClassKind.CLASS -> refuse(at, "the ${c.kind.name.lowercase()} ${c.name}")
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
        val required = c.fields.filter { it.isRequired }
        val frame = Frame(c, null)
        val init = mutableListOf<String>()
        assignedGlobals(c.initially.orEmpty()).takeIf { it.isNotEmpty() }?.let { init += "global ${it.joinToString(", ")}" }
        c.fields.forEach { f ->
            val node: ASTNode = f.decl ?: at
            checkName(f.name, node)
            if (f.isRequired) {
                checkLocalName(f.name, node)
            }
            checkType(f.type, node, "the field '${f.name}'")
            val v = when {
                f.isRequired -> if (isValue(f.type)) copyOf(f.name, f.type) else f.name
                f.default != null -> asValue(f.default, frame, Use.STORE).text
                else -> zeroValue(f.type, node)
            }
            init += "self.${pyName(f)} = $v"
        }
        c.initially?.let { init += block(it, frame) }
        if (init.isNotEmpty()) {
            body += ""
            body += "def __init__(${(listOf("self") + required.map { it.name }).joinToString(", ")}):"
            body += indent(init)
        }
        c.methods.forEach { m ->
            body += ""
            body += function(m, c)
        }
        return out + indent(body)
    }

    private fun mainCall(fn: FnSymbol): String? {
        if (fn.params.isNotEmpty()) {
            return null
        }
        val call = when (fn.ret) {
            KType.Void -> "${pyName(fn)}()"
            KType.INT32 -> "raise SystemExit(${pyName(fn)}())"
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
        is BreakStatement -> listOf("break")
        is ContinueStatement -> listOf("continue")
        else -> {
            if (s.javaClass != Statement::class.java) {
                val what = when (s.javaClass.simpleName) {
                    "ForIterationStatement" -> "a for loop (write it as a while loop)"
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

    private fun exprStatement(s: Statement, e: Expr, f: Frame): List<String> = when (e) {
        NoExpr -> emptyList()
        is VariableDecl -> local(e, f)
        is AssignmentExpr -> assign(e.target, null, e.value, f)
        is CompoundAssignmentExpr -> assign(e.left, e.operator, e.right, f)
        is PlaceAssignmentExpr -> assign(e.target, e.operator, e.value, f)
        is Decl -> {
            refuse(s, "a declaration inside a body")
            emptyList()
        }
        else -> listOf(expr(e, f).text)
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
            if (tt != null && isValue(tt) && !(target is Identifier && model.symbolOf(target) is LocalSymbol)) {
                // A field, global or mut parameter keeps its one List or Map and takes the new
                // elements, so a mut parameter bound to it still names it (C++'s T&); the slice
                // and _k_mapset copy them.
                val v = expr(value, f)
                return pre + if (isMap(tt)) call("_k_mapset", lhs, v.text).text else "$lhs[:] = ${v.text}"
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
                is GlobalSymbol -> if (sym.module === module) pyName(sym) else refuseText(target, "an assignment to another module's global")
                else -> refuseText(target, "an assignment to '${target.value}'")
            }
            is MemberAccessExpr -> {
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

    /** The object a written field is read through; a handle C++ reads before the value runs. */
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

    /** The List or Map an index assignment writes, read through [objectPlace] when it is a field. */
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
        if (mapEntries(e)) {
            return refusePy(e, "Map.entries (a Tuple2 is not on the py target: read keys() and get(k))")
        }
        val t = model.typeOrNull(e)
        if (t != null) {
            val held = if (c is Coercion.ToView && magicName(t) == "MutView") (t as KType.Nominal).typeArgs().firstOrNull() ?: t else t
            unsupportedType(held)?.let { return refusePy(e, it) }
        }
        return raw(e, f)
    }

    /** Whether [e] is `m.entries()` on a Map, a List of Tuple2, named before its type is refused. */
    private fun mapEntries(e: Expr): Boolean {
        val c = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return false
        val rc = model.call(c) ?: return false
        return rc.kind == CallKind.MAGIC && rc.fn?.name == "entries" && isMap(rc.receiver?.let { model.typeOrNull(it) })
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
        if (!isValue(t) || model.coercion(e) is Coercion.ToView) {
            return v
        }
        val shared = calleeWrites || later.any { model.effect(it) == Effect.IMPURE }
        return if (copies(e, use, later, shared)) Py(copyOf(v.text, t), PyPrec.POSTFIX) else v
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
                Use.RETURN -> false
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
        is MemberAccessExpr -> model.member(e) is MemberRef.Field && (use != Use.ARG || sharedWritten)
        is IfExpr -> listOfNotNull(branchValue(e.thenBranch), branchValue(e.elseBranch)).any { copies(it, use, later, sharedWritten) }
        else -> false
    }

    /**
     * Whether evaluating [e] writes the local [sym] by name, the one way a local is written
     * while a sibling argument holds it (Python reaches no other function's locals): a `mut`
     * argument rooted at it, a `mut fx` called on it, or a MutView lent from it, as the C++
     * target's NAMED test reads it.
     */
    private fun writesByName(e: Expr, sym: LocalSymbol): Boolean {
        var writes = false
        AstTree.walk(e) { n ->
            if (writes || n !is Expr) {
                return@walk
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
    private fun rootedAt(e: Expr, sym: LocalSymbol): Boolean = when (e) {
        is Identifier -> model.symbolOf(e) === sym
        is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { rootedAt(it, sym) } ?: false
        is FunctionCallExpr -> model.call(e)?.let { rc -> rc.fn?.name in LENDERS && rc.receiver?.let { rootedAt(it, sym) } == true } ?: false
        else -> false
    }

    /** A new Python list holding the elements of [text], a List or Arr of type [t] (a bytearray of UInt8), or a new dict of a Map's. */
    private fun copyOf(text: String, t: KType): String = when {
        isMap(t) -> "dict($text)"
        isBytes(t) -> "bytearray($text)"
        else -> "list($text)"
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
            else -> refusePy(e, "the expression ${e.javaClass.simpleName.removeSuffix("Expr").removeSuffix("Literal")}")
        }
    }

    /** `[a, b]` as the List or Arr the typer gave it: a list, or a bytearray of UInt8. */
    private fun arrayLiteral(e: ArrayLiteral, f: Frame): Py {
        val t = typeOf(e) ?: return Py("None", PyPrec.ATOM)
        if (!isList(t)) {
            return refusePy(e, "an array literal of ${article(t.display())}")
        }
        val items = e.value.joinToString(", ") { wrap(expr(it, f), PyPrec.TERNARY) }
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
        is GlobalSymbol -> when {
            sym.module.isStdlib && sym.foreign is Foreign.Magic -> when (sym.name) {
                "true" -> Py("True", PyPrec.ATOM)
                "false" -> Py("False", PyPrec.ATOM)
                "null" -> Py("None", PyPrec.ATOM)
                else -> refusePy(id, "the magic value '${sym.name}'")
            }
            sym.module === module -> Py(pyName(sym), PyPrec.ATOM)
            else -> refusePy(id, "a global of another module ('${sym.name}')")
        }
        is FnSymbol -> refusePy(id, "a function used as a value ('${sym.name}')")
        else -> refusePy(id, "the name '${id.value}'")
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
            is MemberRef.Field -> {
                val ot = typeOf(e.origin) ?: return Py("None", PyPrec.ATOM)
                when {
                    magicName(ot) == "Maybe" && m.field.name == "value" -> {
                        val b = bindings.lookup("Maybe.unwrap") ?: return refusePy(e, "Maybe.value (no py binding)")
                        bound(b, { prec -> wrap(expr(e.origin, f), prec) }, emptyList(), f)
                    }
                    isUserClass(m.field.owner) -> Py("${wrap(objectOf(e.origin, f), PyPrec.POSTFIX)}.${pyName(m.field)}", PyPrec.POSTFIX)
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
            is GlobalSymbol -> if (sym.module === module) pyName(sym) else refuseText(e, "a List of another module")
            else -> refuseText(e, "this List")
        }
        is MemberAccessExpr -> when (val m = model.member(e)) {
            is MemberRef.Field ->
                if (isUserClass(m.field.owner)) "${wrap(objectOf(e.origin, f), PyPrec.POSTFIX)}.${pyName(m.field)}" else refuseText(e, "this List")
            else -> wrap(expr(e, f), PyPrec.POSTFIX)
        }
        else -> wrap(expr(e, f), PyPrec.POSTFIX)
    }

    private fun callExpr(c: FunctionCallExpr, f: Frame): Py {
        val rc = model.call(c) ?: return refusePy(c, "a call the typer did not resolve")
        val fn = rc.fn
        val userCall = (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD) && fn != null && fn.module === module
        rc.args.forEachIndexed { i, a ->
            if (a is ArgBinding.Given && a.byRef && !(userCall && fn!!.params.getOrNull(i)?.type?.let { isValue(it) } == true)) {
                return refusePy(c, "a mut argument to '${fn?.name ?: c.name}' (only a List or a Map given to a function of the module is passed by reference)")
            }
        }
        return when (rc.kind) {
            CallKind.PRINT -> trace(c, rc, f)
            CallKind.FREE -> when {
                fn == null || fn.module !== module -> refusePy(c, "a call of '${fn?.name ?: c.name}' in another module")
                else -> Py("${pyName(fn)}(${userArgs(c, rc, f)})", PyPrec.POSTFIX)
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
        return parts.joinToString(", ")
    }

    private fun magicCall(c: FunctionCallExpr, rc: ResolvedCall, f: Frame): Py {
        val fn = rc.fn ?: return refusePy(c, "this call")
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
        // `{list}` is the List the call returns (Map.keys, Map.valuesArr): a bytearray of UInt8.
        val made = if ("{list}" in binding.expr) {
            PyBinding(binding.expr.replace("{list}", if (isBytes(model.typeOrNull(c) ?: KType.Error)) "bytearray" else "list"))
        } else {
            binding
        }
        return bound(made, self, rc.args.map { (it as ArgBinding.Given).expr }, f)
    }

    private fun bound(binding: PyBinding, self: ((Int) -> String)?, args: List<Expr>, f: Frame): Py {
        val text = PyBindingTable.expand(binding, self) { i, prec -> args.getOrNull(i)?.let { wrap(expr(it, f), prec) } ?: "None" }
        PyRuntime.HELPER.findAll(binding.expr).forEach { helpers.add(it.value) }
        return Py(text, binding.prec)
    }

    /** `trace(x)` in D42's format: a Bool as 1 or 0, a Float64 as %g, integers and Str as they are. */
    private fun trace(c: FunctionCallExpr, rc: ResolvedCall, f: Frame): Py {
        if (rc.fn != null) {
            return refusePy(c, "'${rc.fn.name}' (only trace prints on the py target)")
        }
        val arg = (rc.args.singleOrNull() as? ArgBinding.Given)?.expr ?: return Py("print()", PyPrec.POSTFIX)
        val t = typeOf(arg) ?: return Py("None", PyPrec.ATOM)
        val v = expr(arg, f)
        val prim = t.prim
        val text = when {
            t == KType.Str || prim?.isInteger == true -> v.text
            prim == Prim.CHAR -> call("chr", v.text).text
            prim == Prim.BOOL -> "1 if ${wrap(v, PyPrec.OR)} else 0"
            prim == Prim.FLOAT64 -> call("_k_gtext", v.text).text
            else -> return refusePy(arg, "trace of ${article(t.display())}")
        }
        return Py("print($text)", PyPrec.POSTFIX)
    }

    private fun construction(o: ObjectInitExpr, f: Frame): Py {
        val init = model.init(o) ?: return refusePy(o, "a construction the typer did not resolve")
        val cls = init.cls ?: return refusePy(o, "this construction")
        if (cls.kind == ClassKind.MAGIC) {
            val t = typeOf(o) ?: return Py("None", PyPrec.ATOM)
            if (!isValue(t)) {
                return refusePy(o, "a construction of ${t.display()}")
            }
            // `List<T> { }`, `Arr<T, N> { }` and `Map<K, V> { }` are their zero value;
            // `List<T> { values = a }` a copy of a.
            val given = init.fields.filterIsInstance<FieldInit.Given>()
            return when {
                given.isEmpty() -> zeroValue(t, o).let { Py(it, if (it.contains(" * ")) PyPrec.MUL else if (it == "{}") PyPrec.ATOM else PyPrec.POSTFIX) }
                given.size == 1 && cls.name == "List" -> asValue(given[0].expr, f, Use.STORE)
                cls.name == "Map" -> refusePy(o, "a Map construction with entries (a Tuple2 is not on the py target: put each one)")
                else -> refusePy(o, "${article(cls.name)} construction with these values")
            }
        }
        if (!isUserClass(cls)) {
            return refusePy(o, "a construction of ${cls.name}")
        }
        val parts = mutableListOf<String>()
        var sawNamed = false
        val given = init.sourceOrder.mapNotNull { k -> init.fields[k] as? FieldInit.Given }
        // The construction's defaults and `initially` run before __init__ copies a List.
        val writes = !CallReach.construction(cls, given.associate { it.field to it.expr }, model)
        given.forEachIndexed { at, fi ->
            if (fi.field.default != null || !fi.field.isRequired) {
                refuse(fi.expr, "a value given to the defaulted field '${fi.field.name}' at a construction")
            }
            // __init__ copies a List it stores, after the defaults before it ran: a List is
            // copied here, as an argument is, when they or a later value may write it.
            val text = wrap(asValue(fi.expr, f, Use.ARG, given.drop(at + 1).map { it.expr }, writes), PyPrec.TERNARY)
            if (fi.named) {
                sawNamed = true
                parts += "${fi.field.name}=$text"
            } else {
                if (sawNamed) {
                    refuse(fi.expr, "a positional value after a named one")
                }
                parts += text
            }
        }
        return Py("${pyName(cls)}(${parts.joinToString(", ")})", PyPrec.POSTFIX)
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

    /** A comparison of numbers, Bools or Strs (the typer has no `==` on a Maybe, a class or a container). */
    private fun comparison(e: BinaryExpr, f: Frame): Py {
        val lt = typeOf(e.leftExpr) ?: return Py("None", PyPrec.ATOM)
        val rt = typeOf(e.rightExpr) ?: return Py("None", PyPrec.ATOM)
        if ((lt != KType.Str && lt !is KType.Scalar) || (rt != KType.Str && rt !is KType.Scalar)) {
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

    /** A Map: a dict. */
    private fun isMap(t: KType?): Boolean = magicName(t) == "Map"

    /** A List, an Arr or a Map: a value (D44) Python shares, so it is copied where a second name could see a write. */
    private fun isValue(t: KType): Boolean = isList(t) || isMap(t)

    /** A View or MutView: what it was lent from (a list, a bytearray), a memoryview of bytes, or a copied slice. */
    private fun isView(t: KType): Boolean = magicName(t).let { it == "View" || it == "MutView" }

    private fun isUserClass(owner: Any?): Boolean =
        owner is ClassSymbol && owner.kind == ClassKind.CLASS && owner.module === module && owner.typeParams.isEmpty()

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
                        isList(inner) || isMap(inner) -> "${article(t.display())} (Python would share the ${magicName(inner)} inside it, which Kira copies)"
                        isMaybe(inner) || isView(inner) -> article(t.display())
                        // Text is a Str, never a view of Chars; a Str's view would index as a str.
                        isView(t) && inner == KType.CHAR -> "${article(t.display())} (text is a Str on the py target)"
                        // A view of other elements is a copied slice, which a write would not reach.
                        s.name == "MutView" && inner != KType.UINT8 -> "${article(t.display())} (a MutView<UInt8> is the one that writes through)"
                        else -> unsupportedType(inner)
                    }
                }
                s.kind == ClassKind.MAGIC && s.name == "Map" -> mapRefusal(t)
                s.kind == ClassKind.MAGIC -> "the type ${s.name}"
                isUserClass(s) -> null
                s.module !== module -> "the class ${s.name} of another module"
                else -> "the ${if (s.typeParams.isNotEmpty()) "generic " else ""}${s.kind.name.lowercase()} ${s.name}"
            }
            is EnumSymbol -> "the enum ${s.name}"
            is TraitSymbol -> "the trait ${s.name}"
            else -> "the type ${t.display()}"
        }
        is KType.Fn -> "an Fx value"
        is KType.Param -> "a type parameter"
        KType.Error -> "an untyped value"
    }

    /**
     * Why the py target cannot hold the Map [t], or null when it can: a key is a Str, an integer,
     * a Bool or a Char, never a float (a NaN key is a new entry at each put in kira::Map, and the
     * entry it was in a dict when it is the same object); a value is anything the target holds
     * but a Maybe (get's None could not tell a missing key) or a container (a dict's copy would
     * share it).
     */
    private fun mapRefusal(t: KType.Nominal): String? {
        val (k, v) = t.typeArgs().takeIf { it.size == 2 } ?: return "a Map without its key and value types"
        val kp = k.prim
        val keyed = k == KType.Str || (kp != null && (kp.isInteger || kp == Prim.BOOL || kp == Prim.CHAR))
        return when {
            kp == Prim.FLOAT64 || kp == Prim.FLOAT32 -> "${article(t.display())} (a float key: a NaN key differs between kira::Map and a dict)"
            unsupportedType(k) != null -> unsupportedType(k)
            !keyed -> "${article(t.display())} (a Map's key is a Str, an integer, a Bool or a Char)"
            isMaybe(v) || isValue(v) || isView(v) -> "${article(t.display())} (a Map's value is no Maybe, List, Arr, view or Map)"
            else -> unsupportedType(v)
        }
    }

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

    private fun pyName(sym: Symbol): String {
        val pub = when (sym) {
            is FnSymbol -> sym.isPub
            is ClassSymbol -> sym.isPub
            is GlobalSymbol -> sym.isPub
            is FieldSymbol -> sym.isPub
            else -> true
        }
        return if (pub) sym.name else "_${sym.name}"
    }

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
            prim?.isInteger == true || prim == Prim.CHAR -> "0"
            t == KType.Str -> "\"\""
            isMaybe(t) -> "None"
            isBytes(t) -> if (n == null) "bytearray()" else "bytearray($n)"
            isList(t) && n == null -> "[]"
            isList(t) -> "[${zeroValue((t as KType.Nominal).typeArgs().first(), at)}] * $n"
            isMap(t) -> "{}"
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
