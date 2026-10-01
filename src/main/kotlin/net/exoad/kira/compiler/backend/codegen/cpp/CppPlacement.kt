package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.ConversionKind
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.prim
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import java.util.Collections
import java.util.IdentityHashMap

/** Where a declaration (or its definition) is written. */
enum class Home {
    /** The header's namespace. */
    HEADER,

    /** The header's `namespace impl_`: a private declaration the header needs (every private one of a header-only module). */
    HEADER_IMPL,

    /** The `.kira.cxx`: its anonymous namespace for a private declaration, the module namespace for a definition. */
    SOURCE,
}

/** A declaration's home and its definition's home (the same for a value or a type). */
data class Placement(val decl: Home, val def: Home) {
    /** The definition goes in a header, so it is `inline` (unless constexpr or a template). */
    val inlineDefinition: Boolean get() = def != Home.SOURCE
}

/**
 * Design 4.2 for one module: which declaration goes in the header, the source or
 * `namespace impl_`, and in what order.
 *
 * **What the header names must be in the header.** A `pub` declaration may name a private
 * one: a struct field of a private enum type, a default argument that is a private constant,
 * a `static_assert` over a private helper, a `constexpr` or template body that calls one.
 * Those private declarations go in the header's `namespace impl_` (design 4.2 item 10),
 * with everything they name in turn; in a header-only module every private declaration
 * does. The rest of a source module's private declarations go in the `.cxx`.
 *
 * **Order.** Sections 6 to 8 of the header (enums, aliases, constants and state; structs;
 * traits, then classes) are one dependency-ordered sequence: a declaration follows every
 * declaration of this module that C++ must have seen complete first (a constant it names,
 * a struct it holds by value or constructs, a base class), and otherwise the section order,
 * then source order. So a module with no forward reference reads exactly as design 4.2
 * lists it, a constant of struct type follows its struct, and a struct whose default names
 * a later constant follows the constant. A cycle is a Kira error (`cpp.struct-cycle` for
 * by-value containment, `cpp.decl-cycle` otherwise), and the cyclic declarations follow in
 * section order.
 */
class CppPlacement(private val ctx: CppEmitContextImpl) {
    private val m = ctx.symbol
    private val model = ctx.model
    private val headerOnly: Boolean get() = ctx.isHeaderOnly

    /**
     * The Kira errors the order found ([STRUCT_CYCLE_CODE], [DECL_CYCLE_CODE]). They are the
     * placement's, not [ctx]'s: one placement serves the whole run, and the context that first
     * built it may be another module's (its `qualified` asks where this module put a name), so
     * [CppEmitContextImpl.diagnostics] of the owning module reads them from here.
     */
    val diagnostics: MutableList<CppDiagnostic> = mutableListOf()

    private fun error(node: ASTNode, code: String, message: String) {
        diagnostics += ctx.diagnosticAt(node, code, message)
    }

    // ---- the module's declarations ---------------------------------------------------------------

    /** The module's own top-level declarations in source order: no `@_magic`, no anonymous function, one per name. */
    val declarations: List<Symbol> = m.declarations.filter { sym ->
        when {
            ctx.isMagic(sym) -> false
            sym is FnSymbol && sym.isOperator -> true
            sym is FnSymbol && sym.name == "<anonymous>" -> false
            else -> m.members[sym.name] === sym   // a duplicate name is a typer error; the first keeps the name
        }
    }

    private val declared: Set<Symbol> = declarations.toCollection(java.util.Collections.newSetFromMap(IdentityHashMap()))

    /** Exported (`pub`, or `fx main`) declarations are declared in the header. */
    fun isExported(sym: Symbol): Boolean = ctx.isPub(sym) || (sym is FnSymbol && ctx.isMain(sym))

    /** Whether [sym] is one of this module's declarations that goes in the header's `namespace impl_`. */
    fun inImpl(sym: Symbol): Boolean = sym in declared && !isExported(sym) && privateHome(sym) == Home.HEADER_IMPL

    private fun privateHome(sym: Symbol): Home = if (headerOnly || sym in headerNeeds) Home.HEADER_IMPL else Home.SOURCE

    /** A constant, module state, enum or alias. */
    fun value(sym: Symbol): Placement {
        val home = if (isExported(sym)) Home.HEADER else privateHome(sym)
        return Placement(home, home)
    }

    /** A struct, class or trait: its definition, and where its member definitions go. */
    fun type(sym: Symbol): Placement {
        val decl = if (isExported(sym)) Home.HEADER else privateHome(sym)
        val def = when {
            decl != Home.HEADER -> decl
            headerOnly || isGeneric(sym) -> Home.HEADER
            else -> Home.SOURCE
        }
        return Placement(decl, def)
    }

    /** A free function. */
    fun function(fn: FnSymbol): Placement {
        val decl = if (isExported(fn)) Home.HEADER else privateHome(fn)
        val def = when {
            decl != Home.HEADER -> decl
            headerOnly || fn.isConst || isTemplate(fn) -> Home.HEADER
            else -> Home.SOURCE
        }
        return Placement(decl, def)
    }

    /** A struct method: declared in the class body, defined where the struct's [type] says, or inline when constexpr or a template. */
    fun method(owner: ClassSymbol, fn: FnSymbol): Placement {
        val ownerPlacement = type(owner)
        val def = when {
            ownerPlacement.decl != Home.HEADER -> ownerPlacement.decl
            fn.isConst || isTemplate(fn, owner) -> Home.HEADER
            else -> ownerPlacement.def
        }
        return Placement(ownerPlacement.decl, def)
    }

    /** A generic function, one of a generic struct, or one with a non-escaping `Fx` parameter (a template parameter, design 5.1). */
    fun isTemplate(fn: FnSymbol, owner: ClassSymbol? = null): Boolean =
        fn.typeParams.isNotEmpty() || (owner != null && owner.typeParams.isNotEmpty()) || fn.params.any { isNonEscapingFx(it) }

    private fun isGeneric(sym: Symbol): Boolean = when (sym) {
        is ClassSymbol -> sym.typeParams.isNotEmpty()
        is TraitSymbol -> sym.typeParams.isNotEmpty()
        else -> false
    }

    private val escapes by lazy { CppEscapes(model) }

    /**
     * An `Fx` parameter EscapePass proved local to the call (design 5.1), or, where the model
     * has no entry (an absent one means escaping), one [CppEscapes] sees called and nothing else.
     */
    fun isNonEscapingFx(p: ParamSymbol): Boolean = p.type is KType.Fn && !p.byRef && !escapes.fxEscapes(p)

    // ---- what a declaration names ----------------------------------------------------------------

    /**
     * What one declaration's text names among this module's declarations: [mentions] is
     * every one, [completes] those C++ must have defined (not just declared) before it, and
     * [constructed] the structs and classes a default in it makes a value of (`Pt {}`, or a
     * call returning a struct), which C++ checks where the default is declared.
     */
    private class Refs(val mentions: Set<Symbol>, val completes: Set<Symbol>, val constructed: Set<Symbol>)

    private val signatureRefs = IdentityHashMap<Symbol, Refs>()
    private val bodyRefs = IdentityHashMap<Symbol, Set<Symbol>>()

    /** This module's declarations that the declaration text of [sym] (its signature, fields, defaults; not its bodies) names. */
    fun mentions(sym: Symbol): Set<Symbol> = refsOf(sym).mentions

    /** This module's declarations that must be defined before [sym]'s. */
    fun completes(sym: Symbol): Set<Symbol> = refsOf(sym).completes

    /** This module's types that a default in the declaration text of [sym] constructs, or that a call in one returns by value. */
    fun constructed(sym: Symbol): Set<Symbol> = refsOf(sym).constructed

    /** This module's declarations that the bodies of [sym] (a function, or a type's methods and blocks) name. */
    fun bodyMentions(sym: Symbol): Set<Symbol> = bodyRefs.getOrPut(sym) {
        val out = newSymbolSet()
        bodiesOf(sym).forEach { stmt -> visitRefs(stmt, bodies = true) { s -> out.add(s) } }
        out.remove(sym)
        out
    }

    private fun bodiesOf(sym: Symbol): List<Statement> = when (sym) {
        is FnSymbol -> sym.body.orEmpty()
        is ClassSymbol -> sym.methods.flatMap { it.body.orEmpty() } + sym.initially.orEmpty() + sym.finally.orEmpty()
        is TraitSymbol -> sym.methods.flatMap { it.body.orEmpty() }
        else -> emptyList()
    }

    private fun refsOf(sym: Symbol): Refs = signatureRefs.getOrPut(sym) {
        val mentions = newSymbolSet()
        val completes = newSymbolSet()
        val constructed = newSymbolSet()
        val isType = sym is ClassSymbol || sym is TraitSymbol
        val decl = sym.decl
        if (decl != null) {
            val constructs = { s: Symbol ->
                if (s !== sym) {
                    mentions.add(s)
                    completes.add(s)
                    constructed.add(s)
                }
            }
            visitRefs(decl, bodies = false, constructs = constructs) { s ->
                mentions.add(s)
                // A type needs its values, enums and aliases defined; another type only declared (by-value containment is added below).
                if (!isType || s !is ClassSymbol && s !is TraitSymbol) {
                    completes.add(s)
                }
            }
        }
        when (sym) {
            is ClassSymbol -> {
                val held = LinkedHashSet<ClassSymbol>()
                sym.fields.forEach { f -> needsComplete(f.type, held) }
                completes.addAll(held)
                (sym.superclass?.sym as? Symbol)?.let { completes.add(it) }
                sym.traits.forEach { (it.sym as? Symbol)?.let { s -> completes.add(s) } }
            }
            is TraitSymbol -> sym.parents.forEach { (it.sym as? Symbol)?.let { s -> completes.add(s) } }
            else -> {}
        }
        mentions.remove(sym)
        completes.remove(sym)
        constructed.remove(sym)
        Refs(
            mentions.filter { it in declared }.toCollection(newSymbolSet()),
            completes.filter { it in declared }.toCollection(newSymbolSet()),
            constructed.filter { it in declared }.toCollection(newSymbolSet()),
        )
    }

    /**
     * Calls [visit] with every declaration of this module that [root] names: type references
     * (through aliases, and the constant in `Arr<T, N>`), identifiers, enum owners in
     * `Kind.KIND_OK`. A member name after `.` and a named argument's name are not
     * references. Function bodies and `initially`/`finally` blocks are walked only with
     * [bodies]. [constructs] gets the struct or class an `S { }` constructs, and the struct a
     * call returns by value (`origin()`), which must be complete there.
     */
    private fun visitRefs(root: ASTNode, bodies: Boolean, constructs: (Symbol) -> Unit = {}, visit: (Symbol) -> Unit) {
        walk(root, bodies) { node ->
            when (node) {
                is Type -> {
                    model.aliasRefs[node]?.let { visit(it) }
                    model.typeOf(node)?.let { t -> collectTypes(t, visit) }
                }
                is Identifier -> if (node !is IntrinsicExpr) {
                    val sym = model.symbolOf(node) ?: m.members[node.value]
                    if (sym != null && sym in declared) {
                        visit(sym)
                    }
                }
                is ObjectInitExpr -> {
                    val t = model.typeOf(node.typeName) as? KType.Nominal
                    val sym = t?.sym as? ClassSymbol
                    if (sym != null && sym.kind != ClassKind.MAGIC && sym in declared) {
                        constructs(sym)
                    }
                }
                is FunctionCallExpr -> {
                    // A class comes back as an Rc, which needs no definition; a struct comes back by
                    // value. A default argument's call is not typed (the typer types bodies), so the
                    // callee's declared return type stands in for the call's.
                    val callee = (node.name as? Identifier)?.takeIf { it !is IntrinsicExpr }?.let { model.symbolOf(it) ?: m.members[it.value] } as? FnSymbol
                    val t = (model.typeOrNull(node) ?: model.call(node)?.returnType ?: callee?.ret) as? KType.Nominal
                    val sym = t?.sym as? ClassSymbol
                    if (sym != null && sym.isValue && sym in declared) {
                        constructs(sym)
                    }
                }
                else -> {}
            }
        }
    }

    private fun collectTypes(t: KType, visit: (Symbol) -> Unit) {
        when (t) {
            is KType.Nominal -> {
                (t.sym as? Symbol)?.let { if (it in declared) visit(it) }
                t.typeArgs().forEach { collectTypes(it, visit) }
            }
            is KType.Fn -> {
                t.params.forEach { collectTypes(it.type, visit) }
                collectTypes(t.ret, visit)
            }
            else -> {}
        }
    }

    /** Whether [fn]'s body names its parameter [p]: an identifier that is not a member name or a named argument's name. */
    fun bodyNames(fn: FnSymbol, p: ParamSymbol): Boolean {
        val body = fn.body ?: return true
        var used = false
        body.forEach { stmt ->
            walk(stmt, bodies = true) { node ->
                if (!used && node is Identifier && node !is IntrinsicExpr && node.value == p.name) {
                    val recorded = model.symbolOf(node)
                    used = recorded == null || recorded === p
                }
            }
        }
        return used
    }

    // ---- what the header needs, and what is referenced at all -------------------------------------

    /**
     * The private declarations of a source module that the header must hold: named by an
     * exported declaration's text, by a body the header defines, or by a module-level
     * statement; then, transitively, what those name.
     */
    val headerNeeds: Set<Symbol> by lazy {
        val needed = newSymbolSet()
        if (headerOnly) {
            return@lazy needed
        }
        val queue = ArrayDeque<Symbol>()
        fun need(s: Symbol) {
            if (!isExported(s) && needed.add(s)) {
                queue.addLast(s)
            }
        }
        declarations.filter { isExported(it) }.forEach { d ->
            mentions(d).forEach(::need)
            headerBodiesOf(d).forEach { stmt -> visitRefs(stmt, bodies = true) { s -> need(s) } }
        }
        m.statements.forEach { stmt -> visitRefs(stmt, bodies = true) { s -> need(s) } }
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            mentions(s).forEach(::need)
            bodyMentions(s).forEach(::need)
        }
        needed
    }

    /** The bodies of the exported [d] that a source module still defines in its header: constexpr, templates, generic types. */
    private fun headerBodiesOf(d: Symbol): List<Statement> = when (d) {
        is FnSymbol -> if (d.isConst || isTemplate(d)) d.body.orEmpty() else emptyList()
        is ClassSymbol -> if (isGeneric(d)) {
            bodiesOf(d)
        } else {
            d.methods.filter { it.isConst || isTemplate(it) }.flatMap { it.body.orEmpty() }
        }
        is TraitSymbol -> if (isGeneric(d)) bodiesOf(d) else emptyList()
        else -> emptyList()
    }

    /** Every declaration some other declaration, body or module-level statement of this module names. */
    private val referenced: Set<Symbol> by lazy {
        val out = newSymbolSet()
        declarations.forEach { d ->
            out.addAll(mentions(d))
            out.addAll(bodyMentions(d))
        }
        m.statements.forEach { stmt -> visitRefs(stmt, bodies = true) { s -> out.add(s) } }
        out
    }

    /** Whether anything in this module names [sym] besides its own declaration (a private helper nothing calls is `[[maybe_unused]]`). */
    fun isReferenced(sym: Symbol): Boolean = sym in referenced

    // ---- order -------------------------------------------------------------------------------------

    /** What one position of [orderedItems] holds. */
    enum class ItemKind {
        /** A value, enum, alias, struct, trait or class: its definition. */
        DECL,

        /** The prototype of a free function hoisted before a declaration that names it. */
        PROTO,

        /** The definition of a hoisted function, when it lives in the same file as its prototype. */
        DEF,
    }

    /** One position of [orderedItems]. */
    data class OrderedItem(val sym: Symbol, val kind: ItemKind)

    /**
     * Sections 6 to 8 of design 4.2 as one sequence: every value, struct, trait and class of
     * the module, each after what it [completes], and otherwise in section order (values,
     * structs, traits, classes) then source order.
     *
     * A free function that a value's initializer or a type's declaration text names (a
     * constant `X: UInt32 = crc32(DATA)`, a field default `n: Int32 = limit()`) is hoisted into
     * the sequence rather than left to sections 9 and 10, which come after every value: its
     * prototype before the first declaration that names it, and, when its definition lives in
     * the same file as the prototype (`@_const`, a template, a header-only module, a private
     * function of the `.cxx`), its definition before a value that evaluates it, so a constexpr
     * initializer finds the body it needs. What those functions name in turn is hoisted with
     * them.
     */
    val orderedItems: List<OrderedItem> by lazy { order() }

    /** [orderedItems] without the hoisted functions: the values and types, in the order the header defines them. */
    val ordered: List<Symbol> by lazy { orderedItems.filter { it.kind == ItemKind.DECL }.map { it.sym } }

    private val hoistedKinds = IdentityHashMap<FnSymbol, Set<ItemKind>>()

    /** Whether [fn]'s prototype ([ItemKind.PROTO]) or definition ([ItemKind.DEF]) is written in the ordered sequence rather than in its own section. */
    fun isHoisted(fn: FnSymbol, kind: ItemKind): Boolean {
        orderedItems
        return hoistedKinds[fn]?.contains(kind) == true
    }

    private fun rank(item: OrderedItem): Int = when (item.kind) {
        ItemKind.PROTO -> -2
        ItemKind.DEF -> -1
        ItemKind.DECL -> when (val sym = item.sym) {
            is GlobalSymbol, is EnumSymbol, is AliasSymbol -> 0
            is ClassSymbol -> if (sym.isValue) 1 else 3
            is TraitSymbol -> 2
            else -> 4
        }
    }

    /** A free function of this module that the ordered sequence may hold: not extern, not an operator, not a method. */
    private fun isHoistable(sym: Symbol): Boolean =
        sym is FnSymbol && sym in declared && sym.owner == null && !sym.isOperator && foreignOf(sym) !is Foreign.Extern

    private fun order(): List<OrderedItem> {
        val base = declarations.filter { sym ->
            when (sym) {
                is GlobalSymbol, is EnumSymbol, is AliasSymbol, is TraitSymbol -> foreignOf(sym) !is Foreign.Extern
                is ClassSymbol -> foreignOf(sym) !is Foreign.Extern && sym.kind != ClassKind.OPAQUE
                else -> false
            }
        }

        // The hoisted functions: what a base declaration names, then what those name in turn.
        val hoisted = LinkedHashSet<FnSymbol>()
        val queue = ArrayDeque<FnSymbol>()
        fun hoist(s: Symbol) {
            if (s is FnSymbol && isHoistable(s) && hoisted.add(s)) {
                queue.addLast(s)
            }
        }
        base.forEach { s -> mentions(s).forEach(::hoist) }
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            mentions(f).forEach(::hoist)
            bodyMentions(f).forEach(::hoist)
        }
        fun hasDef(f: FnSymbol): Boolean = f.body != null && function(f).def == function(f).decl

        val items = mutableListOf<OrderedItem>()
        val itemOf = IdentityHashMap<Symbol, MutableMap<ItemKind, OrderedItem>>()
        fun add(sym: Symbol, kind: ItemKind) {
            val item = OrderedItem(sym, kind)
            items += item
            itemOf.getOrPut(sym) { java.util.EnumMap(ItemKind::class.java) }[kind] = item
        }
        hoistedKinds.clear()
        hoisted.forEach { f ->
            add(f, ItemKind.PROTO)
            if (hasDef(f)) {
                add(f, ItemKind.DEF)
            }
            hoistedKinds[f] = if (hasDef(f)) setOf(ItemKind.PROTO, ItemKind.DEF) else setOf(ItemKind.PROTO)
        }
        base.forEach { add(it, ItemKind.DECL) }
        fun item(sym: Symbol, kind: ItemKind): OrderedItem? = itemOf[sym]?.get(kind)
        fun defOrProto(f: FnSymbol): OrderedItem? = item(f, ItemKind.DEF) ?: item(f, ItemKind.PROTO)

        // Evaluating a call to [f] in an initializer reaches f's body and every hoisted body it calls.
        fun evaluates(f: FnSymbol): List<OrderedItem> {
            val reached = LinkedHashSet<FnSymbol>()
            val todo = ArrayDeque(listOf(f))
            while (todo.isNotEmpty()) {
                val g = todo.removeFirst()
                if (reached.add(g)) {
                    bodyMentions(g).forEach { c -> if (c is FnSymbol && c in hoisted) todo.addLast(c) }
                }
            }
            return reached.mapNotNull(::defOrProto)
        }

        val index = IdentityHashMap<Symbol, Int>()
        base.forEachIndexed { i, s -> index[s] = i }
        hoisted.forEachIndexed { i, f -> index[f] = i }
        val deps = HashMap<OrderedItem, List<OrderedItem>>()
        items.forEach { item ->
            val s = item.sym
            deps[item] = when (item.kind) {
                ItemKind.DECL -> completes(s).flatMap { c ->
                    when {
                        c is FnSymbol && c in hoisted ->
                            // A value evaluates the call; a type's default only names the function.
                            if (s is GlobalSymbol) evaluates(c) else listOfNotNull(item(c, ItemKind.PROTO))
                        else -> listOfNotNull(item(c, ItemKind.DECL))
                    }
                }
                ItemKind.PROTO -> mentions(s).mapNotNull { c ->
                    when {
                        c is FnSymbol && c in hoisted -> item(c, ItemKind.PROTO)
                        // A signature needs only the forward declaration; a default argument that
                        // constructs the type (`p: Pt = Pt {}`), or calls something returning it by
                        // value, is checked where it is declared and needs the definition.
                        c is ClassSymbol || c is TraitSymbol -> if (c in constructed(s)) item(c, ItemKind.DECL) else null
                        else -> item(c, ItemKind.DECL)
                    }
                }
                ItemKind.DEF -> listOfNotNull(item(s, ItemKind.PROTO)) + (mentions(s) + bodyMentions(s)).mapNotNull { c ->
                    if (c is FnSymbol && c in hoisted) item(c, ItemKind.PROTO) else item(c, ItemKind.DECL)
                }
            }
            if (item.kind == ItemKind.DECL && s is ClassSymbol && heldByValue(s).any { it === s }) {
                reportStructCycle(s, listOf(s))
            }
        }
        val remaining = items.sortedWith(compareBy({ rank(it) }, { index[it.sym]!! }, { it.kind.ordinal })).toMutableList()
        val placed = HashSet<OrderedItem>()
        val out = mutableListOf<OrderedItem>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { item -> deps[item]!!.all { it in placed } }
            if (next == null) {
                // Every remaining item waits on another remaining one: a cycle.
                remaining.forEach { item -> reportCycle(item.sym, cycleThrough(item, deps, remaining)) }
                out.addAll(remaining)
                break
            }
            remaining.remove(next)
            placed.add(next)
            out.add(next)
        }
        return out
    }

    private fun cycleThrough(start: OrderedItem, deps: Map<OrderedItem, List<OrderedItem>>, pool: List<OrderedItem>): List<Symbol> {
        val path = mutableListOf(start.sym)
        val seen = HashSet<OrderedItem>()
        var cur = start
        while (seen.add(cur)) {
            cur = deps[cur]!!.firstOrNull { it in pool } ?: break
            if (cur.sym !== path.last()) {
                path.add(cur.sym)
            }
            if (cur.sym === start.sym) {
                break
            }
        }
        return path
    }

    private fun reportCycle(s: Symbol, path: List<Symbol>) {
        val structsOnly = path.all { it is ClassSymbol && it.isValue } && path.size > 1
        if (structsOnly && s is ClassSymbol) {
            if (path.zipWithNext().all { (a, b) -> b in heldByValue(a as ClassSymbol) }) {
                reportStructCycle(s, path)
                return
            }
            error(
                s.decl ?: return,
                DECL_CYCLE_CODE,
                "struct ${s.name} holds a container of a struct that holds one of it (${path.joinToString(" -> ") { it.name }}); " +
                    "C++ (libc++, MSVC) must see the element complete before the holding struct, so hold one side through a class",
            )
            return
        }
        val decl = s.decl ?: return
        error(
            decl,
            DECL_CYCLE_CODE,
            "${kindOf(s)} ${s.name} cannot be declared before what it names (${path.joinToString(" -> ") { it.name }}); " +
                "C++ needs one side declared first, so break the cycle",
        )
    }

    private fun heldByValue(s: ClassSymbol): Set<ClassSymbol> {
        val held = LinkedHashSet<ClassSymbol>()
        s.fields.forEach { f -> byValue(f.type, held) }
        return held
    }

    private fun reportStructCycle(s: ClassSymbol, cycle: List<Symbol>) {
        val decl = s.decl ?: return
        error(
            decl,
            STRUCT_CYCLE_CODE,
            "struct ${s.name} holds itself by value (${cycle.joinToString(" -> ") { it.name }}), which C++ cannot lay out; " +
                "hold one side through a class, a List or a Maybe of a class",
        )
    }

    private fun kindOf(sym: Symbol): String = when (sym) {
        is GlobalSymbol -> if (sym.isMut) "state" else "constant"
        is EnumSymbol -> "enum"
        is AliasSymbol -> "alias"
        is ClassSymbol -> if (sym.isValue) "struct" else "class"
        is TraitSymbol -> "trait"
        is FnSymbol -> "function"
        else -> "declaration"
    }

    /**
     * [structs] in the order [ordered] gives them (a struct after every struct it holds by
     * value, and after the values it names).
     */
    fun sortStructs(structs: List<ClassSymbol>): List<ClassSymbol> {
        val wanted = structs.toCollection(java.util.Collections.newSetFromMap(IdentityHashMap<ClassSymbol, Boolean>()))
        return ordered.filterIsInstance<ClassSymbol>().filter { it in wanted }
    }

    /**
     * Whether the private type [sym] needs a forward declaration: some declaration placed
     * before it in [ordered] names it (through a `List<T>`, a `Maybe<C>` or a signature).
     * Exported types are always forward-declared (design 4.2 item 5).
     */
    fun needsForwardDeclaration(sym: Symbol): Boolean {
        for (item in orderedItems) {
            if (item.sym === sym && item.kind == ItemKind.DECL) {
                return false
            }
            if (sym in mentions(item.sym)) {
                return true
            }
        }
        return false
    }

    /**
     * Collects into [into] every struct [t] embeds by value: itself (and its type arguments),
     * or through `Arr<T, N>`, `Maybe`, `Result` or a tuple, which C++ lays out inside the
     * holder. A struct holding itself this way is [STRUCT_CYCLE_CODE].
     */
    private fun byValue(t: KType, into: MutableSet<ClassSymbol>) {
        val n = t as? KType.Nominal ?: return
        val sym = n.sym
        if (sym is ClassSymbol && sym.isValue) {
            into.add(sym)
            n.typeArgs().forEach { byValue(it, into) }
            return
        }
        if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC) {
            val embeds = when {
                sym.name == Builtins.ARR -> n.args.size >= 2
                sym.name == "Maybe" || sym.name == "Result" -> true
                Builtins.tupleArity(sym.name) != null -> true
                else -> false
            }
            if (embeds) {
                n.typeArgs().forEach { byValue(it, into) }
            }
        }
    }

    /**
     * Collects into [into] every struct a field of type [t] needs complete where the holding
     * struct is defined: what [byValue] embeds, and the element of a container whose C++
     * form takes no incomplete type. Measured on the goldens' four compilers with `Later`
     * defined after the holder: libc++ (zig c++, x86 and aarch64) and MSVC reject
     * `kira::Deque<Later>`, `Stack` and `Queue` in every form, libc++ rejects
     * `kira::View<Later>`, and gcc accepts them all; `kira::List<Later> xs{};`,
     * `kira::Map<K, Later>`, `kira::Fn<void(Later)>`, `kira::Weak`, `kira::Ref` and a raw
     * pointer compile everywhere (the emitter writes an empty container default as `xs{}`,
     * since libc++ rejects the prvalue form `= kira::List<Later>{}`). A `Set` element and a
     * `Map` key are hashed, so they are required complete. A struct's own recursion through a
     * container (`List<S>` inside `S`) is fine everywhere: a default member initializer is a
     * complete-class context.
     */
    private fun needsComplete(t: KType, into: MutableSet<ClassSymbol>) {
        val n = t as? KType.Nominal ?: return
        val sym = n.sym
        if (sym is ClassSymbol && sym.isValue) {
            into.add(sym)
            n.typeArgs().forEach { needsComplete(it, into) }
            return
        }
        if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC) {
            val args = n.typeArgs()
            when (sym.name) {
                "Weak", "Unsafe", "Ref", "List" -> {}
                "Map" -> args.firstOrNull()?.let { needsComplete(it, into) }
                else -> args.forEach { needsComplete(it, into) }
            }
        }
    }

    private fun foreignOf(sym: Symbol): Foreign? = when (sym) {
        is ClassSymbol -> sym.foreign
        is TraitSymbol -> sym.foreign
        is FnSymbol -> sym.foreign
        is GlobalSymbol -> sym.foreign
        is EnumSymbol -> sym.foreign
        else -> null
    }

    companion object {
        const val STRUCT_CYCLE_CODE = "cpp.struct-cycle"
        const val DECL_CYCLE_CODE = "cpp.decl-cycle"

        private fun newSymbolSet(): MutableSet<Symbol> = java.util.Collections.newSetFromMap(IdentityHashMap())

        /**
         * A pre-order walk of [root] that skips what is not a reference: the member name of
         * `a.b`, the name of a named argument, and, unless [bodies], every function body and
         * `initially`/`finally` block.
         */
        fun walk(root: ASTNode, bodies: Boolean, visit: (ASTNode) -> Unit) {
            val seen = IdentityHashMap<ASTNode, Boolean>()
            fun go(node: ASTNode) {
                if (seen.put(node, true) != null) {
                    return
                }
                visit(node)
                val skipped: Set<ASTNode> = skippedChildren(node, bodies)
                AstTree.children(node).forEach { child ->
                    if (child !in skipped) {
                        go(child)
                    }
                }
            }
            go(root)
        }

        private fun skippedChildren(node: ASTNode, bodies: Boolean): Set<ASTNode> {
            val out = java.util.Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
            when (node) {
                is MemberAccessExpr -> if (node.member is Identifier && node.member !is IntrinsicExpr) {
                    out.add(node.member)
                }
                is FunctionCallNamedParameterExpr -> out.add(node.name)
                is FunctionDefExpr -> if (!bodies) {
                    node.body?.forEach { out.add(it) }
                }
                is StructDecl -> if (!bodies) {
                    node.initially?.forEach { out.add(it) }
                }
                is ClassDecl -> if (!bodies) {
                    node.initially?.forEach { out.add(it) }
                    node.finally?.forEach { out.add(it) }
                }
                else -> {}
            }
            return out
        }
    }
}

/**
 * What the program uses of its enums and structs, so `nameOf`, `enum_values` and a struct's
 * `operator==` are emitted only where used (design 5.2, and the goldens' convention).
 * Computed once per program over every module's bodies.
 */
class CppUsage private constructor(
    private val nameOf: Set<EnumSymbol>,
    private val enumValues: Set<EnumSymbol>,
    private val compared: Set<ClassSymbol>,
    private val valueOf: Set<EnumSymbol>,
) {
    /** `x as Str` or `"${x}"` on the enum somewhere in the program (`kira::text` calls ADL `nameOf`). */
    fun needsNameOf(e: EnumSymbol): Boolean = e in nameOf

    /** `enumOf<E>(raw)` somewhere in the program (`kira::EnumTraits` reads ADL `enum_values`). */
    fun needsEnumValues(e: EnumSymbol): Boolean = e in enumValues

    /** `x as Float32` (or `Float64`) on a float-based enum somewhere in the program: its entries are numbered, `valueOf` gives the value. */
    fun needsValueOf(e: EnumSymbol): Boolean = e in valueOf

    /**
     * `a == b` or `a != b` somewhere in the program on the struct, or on a `Maybe`, `List`,
     * `Arr`, `Map`, `Set`, `Deque` or tuple holding it, or on a struct whose field holds it:
     * a defaulted `operator==` compares members, so every struct reachable from a compared
     * one needs its own.
     */
    fun needsEquality(s: ClassSymbol): Boolean = s in compared

    companion object {
        val NONE: CppUsage = CppUsage(emptySet(), emptySet(), emptySet(), emptySet())

        /** A usage set stated outright, for tests and for callers that know better; [compared] is closed over fields as [scan] closes it. */
        fun of(
            nameOf: Set<EnumSymbol> = emptySet(),
            enumValues: Set<EnumSymbol> = emptySet(),
            compared: Set<ClassSymbol> = emptySet(),
            valueOf: Set<EnumSymbol> = emptySet(),
        ): CppUsage = CppUsage(nameOf, enumValues, closeOverFields(compared), valueOf)

        fun scan(program: TypedProgram): CppUsage {
            val model = program.model
            val nameOf = LinkedHashSet<EnumSymbol>()
            val enumValues = LinkedHashSet<EnumSymbol>()
            val compared = LinkedHashSet<ClassSymbol>()
            val valueOf = LinkedHashSet<EnumSymbol>()
            fun enumOf(e: Expr): EnumSymbol? = (model.typeOrNull(e) as? KType.Nominal)?.sym as? EnumSymbol
            for (m in program.modules) {
                val ast = runCatching { m.source.ast }.getOrNull() ?: continue
                AstTree.walk(ast) { node ->
                    when (node) {
                        is TypeCastExpr -> {
                            val toStr = model.conversion(node) == ConversionKind.TO_STR || model.typeOf(node.type) == KType.Str
                            if (toStr) {
                                enumOf(node.value)?.let { nameOf.add(it) }
                            }
                            val toFloat = (model.typeOf(node.type) as? KType.Scalar)?.prim?.isFloat == true
                            if (toFloat) {
                                enumOf(node.value)?.let { e -> if (e.base.prim?.isFloat == true) valueOf.add(e) }
                            }
                        }
                        is InterpolatedStringLiteral -> node.parts.forEach { part ->
                            if (part is InterpolationPart.Hole) {
                                enumOf(part.expr)?.let { nameOf.add(it) }
                            }
                        }
                        is BinaryExpr -> if (node.operator == BinaryOp.EQUALS || node.operator == BinaryOp.NOT_EQUAL) {
                            model.typeOrNull(node.leftExpr)?.let { structsIn(it, compared) }
                            model.typeOrNull(node.rightExpr)?.let { structsIn(it, compared) }
                        }
                        is FunctionCallExpr -> model.call(node)?.let { call ->
                            if (call.kind == CallKind.MAGIC && call.fn?.name == "enumOf") {
                                (call.typeArgs.firstOrNull() as? KType.Nominal)?.sym?.let { s -> (s as? EnumSymbol)?.let { enumValues.add(it) } }
                                ((call.returnType as? KType.Nominal)?.args?.firstOrNull() as? TypeArg.Ty)?.t?.let { r ->
                                    ((r as? KType.Nominal)?.sym as? EnumSymbol)?.let { enumValues.add(it) }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            return CppUsage(nameOf, enumValues, closeOverFields(compared), valueOf)
        }

        /** [compared] plus every struct a compared struct's fields hold, at any depth. */
        private fun closeOverFields(compared: Set<ClassSymbol>): Set<ClassSymbol> {
            val out = LinkedHashSet(compared)
            val queue = ArrayDeque(compared)
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                s.fields.forEach { f ->
                    val held = LinkedHashSet<ClassSymbol>()
                    structsIn(f.type, held)
                    held.forEach { if (out.add(it)) queue.addLast(it) }
                }
            }
            return out
        }

        /**
         * Every struct in [t]: itself and its type arguments (`Pair<Pt>` compares `Pt`), or the
         * elements of a container, `Maybe` or tuple of structs (what a defaulted `==` compares).
         */
        fun structsIn(t: KType, into: MutableSet<ClassSymbol>) {
            val n = t as? KType.Nominal ?: return
            val sym = n.sym
            if (sym is ClassSymbol && sym.isValue) {
                into.add(sym)
                n.typeArgs().forEach { structsIn(it, into) }
                return
            }
            if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC) {
                n.typeArgs().forEach { structsIn(it, into) }
            }
        }

        /**
         * The first field of [s] whose type has no `==` in C++ (an `Fx`, a `Weak`, a `Stack`,
         * `Queue` or `Result`), or null when a defaulted `operator==` is well-formed.
         */
        fun fieldWithoutEquality(s: ClassSymbol): FieldSymbol? = s.fields.firstOrNull { !supportsEquality(it.type) }

        private fun supportsEquality(t: KType): Boolean = when (t) {
            is KType.Fn -> false
            is KType.Nominal -> when (val sym = t.sym) {
                is ClassSymbol -> when {
                    sym.kind == ClassKind.MAGIC -> when (sym.name) {
                        "Weak", "Stack", "Queue", "Result", Builtins.STRBUF -> false
                        else -> t.typeArgs().all { supportsEquality(it) }
                    }
                    sym.isValue -> sym.fields.all { supportsEquality(it.type) }
                    else -> true   // a class or trait value is an identity: shared_ptr ==
                }
                else -> true
            }
            else -> true
        }
    }
}

/**
 * Design 4.3 and D14: two modules may share a namespace, and a duplicate symbol in one is a
 * Kira error. Every declaration takes part, in the module's namespace (an exported one) or
 * its `impl_` (a private one the header needs, [CppPlacement.inImpl]), and so does a
 * private declaration of the `.cxx`: its anonymous namespace is one per file, so two
 * modules' private names never meet, but a private name beside another module's exported
 * name in the same namespace does. The exported name's out-of-line bodies sit in
 * `namespace shared` outside the anonymous one, and a body there naming `LIMIT` finds both
 * `shared::LIMIT` and `shared::{anonymous}::LIMIT` (measured, g++: "reference to 'LIMIT' is
 * ambiguous"). That needs the two in one translation unit: the private module's `.cxx`,
 * which sees another module's header only through the modules it `use`s, directly or
 * through their headers. So a private name meets an exported one only when the exporting
 * module is in the private module's transitive `use` closure ([ModuleSymbol.imports]); two
 * modules of one namespace that never reach each other keep their names. A module's
 * namespace nested under another's (`bibo::text` beside `bibo`)
 * collides with a declaration of that name (`pub struct text` in `bibo`) the same way, since
 * C++ has one meaning per name and scope. Stdlib modules are skipped: each has its own
 * `kira::<segments>` by construction.
 *
 * Every colliding declaration gets [CODE] at its own node, naming the other, so the error
 * reaches whichever module is emitted and no header is written.
 */
class CppNamespaceCollisions private constructor(private val byModule: Map<ModuleSymbol, List<CppDiagnostic>>) {
    /** The collisions [module]'s own declarations take part in. */
    fun of(module: ModuleSymbol): List<CppDiagnostic> = byModule[module].orEmpty()

    companion object {
        const val CODE = "cpp.duplicate-symbol"

        val NONE = CppNamespaceCollisions(emptyMap())

        /** One declared name; [isPrivate] for a `.cxx`-private one (in the file's anonymous namespace). */
        private class Entry(val module: ModuleSymbol, val symbol: Symbol?, val ctx: CppEmitContextImpl?, val isPrivate: Boolean = false)

        /** Scans every non-stdlib module of [program]; [contextOf] gives a module's emit context (its placement and layout). */
        fun scan(program: TypedProgram, contextOf: (ModuleSymbol) -> CppEmitContextImpl): CppNamespaceCollisions {
            val entries = LinkedHashMap<String, MutableList<Entry>>()
            fun add(ns: String, name: String, entry: Entry) {
                entries.getOrPut("$ns::$name") { mutableListOf() }.add(entry)
            }
            val modules = program.modules.filter { !it.isStdlib }
            modules.forEach { m ->
                val ctx = contextOf(m)
                val ns = ctx.namespace
                val placement = ctx.placement
                placement.declarations.forEach { sym ->
                    if (sym is FnSymbol && sym.isOperator) {
                        return@forEach
                    }
                    val name = ctx.names.escape(sym.name)
                    when {
                        placement.isExported(sym) -> add(ns, name, Entry(m, sym, ctx))
                        placement.inImpl(sym) -> add("$ns::${CppEmitContextImpl.IMPL_NAMESPACE}", name, Entry(m, sym, ctx))
                        else -> add(ns, name, Entry(m, sym, ctx, isPrivate = true))
                    }
                }
                // `a::b::c` declares `b` in `a` and `c` in `a::b`.
                val segments = ns.split("::")
                for (i in 1 until segments.size) {
                    add(segments.subList(0, i).joinToString("::"), segments[i], Entry(m, null, null))
                }
            }
            val reach = IdentityHashMap<ModuleSymbol, Set<ModuleSymbol>>()
            modules.forEach { m -> reach[m] = reachableFrom(m) }
            /** Whether [a] and [b] can sit in one translation unit: a private name only with what its own `.cxx` includes. */
            fun meet(a: Entry, b: Entry): Boolean = when {
                a.isPrivate && b.isPrivate -> false // two anonymous namespaces never meet
                a.isPrivate -> b.module in reach[a.module].orEmpty()
                b.isPrivate -> a.module in reach[b.module].orEmpty()
                else -> true
            }
            val byModule = IdentityHashMap<ModuleSymbol, MutableList<CppDiagnostic>>()
            entries.forEach { (key, list) ->
                val scope = key.substringBeforeLast("::")
                val name = key.substringAfterLast("::")
                list.forEach { entry ->
                    val colliding = list.filter { it.module !== entry.module && meet(entry, it) }
                    if (colliding.isEmpty()) {
                        return@forEach
                    }
                    val sym = entry.symbol ?: return@forEach
                    val ctx = entry.ctx ?: return@forEach
                    val node = sym.decl ?: return@forEach
                    val others = colliding.joinToString(", ") { describe(it, program, ctx) }
                    val message = if (entry.isPrivate) {
                        "'$name' is private to this module's .cxx, but namespace $scope holds '$name' from $others too; " +
                            "a body of this module naming it would find both (C++ calls that ambiguous), so one of them needs another name"
                    } else {
                        "'$name' is declared in namespace $scope by $others too; two modules may share a namespace, but one name is one declaration (design 4.3)"
                    }
                    byModule.getOrPut(entry.module) { mutableListOf() } += ctx.diagnosticAt(node, CODE, message)
                }
            }
            return CppNamespaceCollisions(byModule)
        }

        /** Every module [m]'s `.cxx` can include a header of: what it `use`s, and what those use, and so on; never [m] itself. */
        private fun reachableFrom(m: ModuleSymbol): Set<ModuleSymbol> {
            val seen = Collections.newSetFromMap(IdentityHashMap<ModuleSymbol, Boolean>())
            val todo = ArrayDeque(m.imports)
            while (todo.isNotEmpty()) {
                val next = todo.removeFirst()
                if (next !== m && seen.add(next)) {
                    todo.addAll(next.imports)
                }
            }
            return seen
        }

        private fun describe(entry: Entry, program: TypedProgram, ctx: CppEmitContextImpl): String {
            val sym = entry.symbol ?: return "module '${entry.module.uri}' (its namespace ${ctx.layout.namespaceFor(entry.module.uri)})"
            val where = sym.decl?.let { program.locate(it) }
            val at = if (where == null) "" else " (${ctx.layout.relativeToRoot(java.nio.file.Path.of(where.first.file))}:${where.second.lineNumber})"
            val private = if (entry.isPrivate) ", private to its .cxx" else ""
            return "module '${entry.module.uri}'$at$private"
        }
    }
}
