package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.ConversionKind
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import java.util.IdentityHashMap

/** Where a declaration (or its definition) is written. */
enum class Home {
    /** The header's namespace. */
    HEADER,

    /** The header's `namespace impl_`: a private helper of a header-only module. */
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
 * Design 4.2: which declaration goes in the header, the source or `namespace impl_`, the
 * topological order of structs, and what the program uses of each enum and struct.
 */
class CppPlacement(private val ctx: CppEmitContextImpl) {
    private val headerOnly: Boolean get() = ctx.isHeaderOnly

    /** Exported (`pub`, or `fx main`) declarations are declared in the header. */
    fun isExported(sym: Symbol): Boolean = ctx.isPub(sym) || (sym is FnSymbol && ctx.isMain(sym))

    private fun privateHome(): Home = if (headerOnly) Home.HEADER_IMPL else Home.SOURCE

    /** A constant, module state, enum or alias. */
    fun value(sym: Symbol): Placement {
        val home = if (isExported(sym)) Home.HEADER else privateHome()
        return Placement(home, home)
    }

    /** A struct, class or trait: its definition, and where its member definitions go. */
    fun type(sym: Symbol): Placement {
        val decl = if (isExported(sym)) Home.HEADER else privateHome()
        val generic = when (sym) {
            is ClassSymbol -> sym.typeParams.isNotEmpty()
            is TraitSymbol -> sym.typeParams.isNotEmpty()
            else -> false
        }
        val def = when {
            decl != Home.HEADER -> decl
            headerOnly || generic -> Home.HEADER
            else -> Home.SOURCE
        }
        return Placement(decl, def)
    }

    /** A free function. */
    fun function(fn: FnSymbol): Placement {
        val decl = if (isExported(fn)) Home.HEADER else privateHome()
        val def = when {
            decl != Home.HEADER -> decl
            headerOnly || fn.isConst || isTemplate(fn) -> Home.HEADER
            else -> Home.SOURCE
        }
        return Placement(decl, def)
    }

    /** A struct method: declared in the class body, defined where the struct's [type] says, or inline when constexpr. */
    fun method(owner: ClassSymbol, fn: FnSymbol): Placement {
        val ownerPlacement = type(owner)
        val def = when {
            ownerPlacement.decl != Home.HEADER -> ownerPlacement.decl
            fn.isConst || isTemplate(fn) -> Home.HEADER
            else -> ownerPlacement.def
        }
        return Placement(ownerPlacement.decl, def)
    }

    /** A generic function, or one with a non-escaping `Fx` parameter (a template parameter, design 5.1). */
    fun isTemplate(fn: FnSymbol): Boolean = fn.typeParams.isNotEmpty() || fn.params.any { isNonEscapingFx(it) }

    /** An `Fx` parameter EscapePass proved local to the call (design 5.1); an absent entry means escaping. */
    fun isNonEscapingFx(p: net.exoad.kira.compiler.analysis.types.ParamSymbol): Boolean =
        p.type is KType.Fn && !p.byRef && !ctx.model.fxEscapes(p)

    // ---- struct order --------------------------------------------------------------------------

    /**
     * [structs] sorted so that a struct comes after every struct it holds by value (a field
     * of that type, or an `Arr<S, N>`, `Maybe<S>` or `TupleN<..S..>` of it), source order
     * otherwise. A cycle is reported (`cpp.struct-cycle`) at each struct on it, and those
     * structs follow in source order.
     */
    fun sortStructs(structs: List<ClassSymbol>): List<ClassSymbol> {
        val index = IdentityHashMap<ClassSymbol, Int>()
        structs.forEachIndexed { i, s -> index[s] = i }
        val edges = IdentityHashMap<ClassSymbol, List<ClassSymbol>>()
        structs.forEach { s ->
            val held = LinkedHashSet<ClassSymbol>()
            s.fields.forEach { f -> byValue(f.type, held) }
            edges[s] = held.filter { it !== s && index.containsKey(it) }
            if (held.any { it === s }) {
                report(s, listOf(s))
            }
        }
        val remaining = structs.toMutableList()
        val out = mutableListOf<ClassSymbol>()
        val placed = IdentityHashMap<ClassSymbol, Boolean>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { s -> edges[s]!!.all { placed.containsKey(it) } }
            if (next == null) {
                // Every remaining struct waits on another remaining one: a cycle.
                remaining.forEach { report(it, cycleThrough(it, edges, remaining)) }
                out.addAll(remaining)
                break
            }
            remaining.remove(next)
            placed[next] = true
            out.add(next)
        }
        return out
    }

    private fun cycleThrough(start: ClassSymbol, edges: Map<ClassSymbol, List<ClassSymbol>>, pool: List<ClassSymbol>): List<ClassSymbol> {
        val path = mutableListOf(start)
        val seen = IdentityHashMap<ClassSymbol, Boolean>()
        var cur = start
        while (seen.put(cur, true) == null) {
            cur = edges[cur]!!.firstOrNull { it in pool } ?: break
            path.add(cur)
            if (cur === start) {
                break
            }
        }
        return path
    }

    private fun report(s: ClassSymbol, cycle: List<ClassSymbol>) {
        val decl = s.decl ?: return
        ctx.diag(
            decl,
            STRUCT_CYCLE_CODE,
            "struct ${s.name} holds itself by value (${cycle.joinToString(" -> ") { it.name }}), which C++ cannot lay out; " +
                "hold one side through a class, a List or a Maybe of a class",
        )
    }

    /** Collects into [into] every struct [t] embeds by value. */
    private fun byValue(t: KType, into: MutableSet<ClassSymbol>) {
        val n = t as? KType.Nominal ?: return
        val sym = n.sym
        if (sym is ClassSymbol && sym.isStruct) {
            into.add(sym)
            return
        }
        if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC) {
            val embeds = when {
                sym.name == Builtins.ARR -> n.args.size >= 2
                sym.name == "Maybe" -> true
                Builtins.tupleArity(sym.name) != null -> true
                else -> false
            }
            if (embeds) {
                n.typeArgs().forEach { byValue(it, into) }
            }
        }
    }

    companion object {
        const val STRUCT_CYCLE_CODE = "cpp.struct-cycle"
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
) {
    /** `x as Str` or `"${x}"` on the enum somewhere in the program (`kira::text` calls ADL `nameOf`). */
    fun needsNameOf(e: EnumSymbol): Boolean = e in nameOf

    /** `enumOf<E>(raw)` somewhere in the program (`kira::EnumTraits` reads ADL `enum_values`). */
    fun needsEnumValues(e: EnumSymbol): Boolean = e in enumValues

    /** `a == b` or `a != b` on the struct somewhere in the program. */
    fun needsEquality(s: ClassSymbol): Boolean = s in compared

    companion object {
        val NONE: CppUsage = CppUsage(emptySet(), emptySet(), emptySet())

        /** A usage set stated outright, for tests and for callers that know better. */
        fun of(nameOf: Set<EnumSymbol> = emptySet(), enumValues: Set<EnumSymbol> = emptySet(), compared: Set<ClassSymbol> = emptySet()): CppUsage =
            CppUsage(nameOf, enumValues, compared)

        fun scan(program: TypedProgram): CppUsage {
            val model = program.model
            val nameOf = LinkedHashSet<EnumSymbol>()
            val enumValues = LinkedHashSet<EnumSymbol>()
            val compared = LinkedHashSet<ClassSymbol>()
            fun enumOf(e: Expr): EnumSymbol? = (model.typeOrNull(e) as? KType.Nominal)?.sym as? EnumSymbol
            fun structOf(e: Expr): ClassSymbol? = ((model.typeOrNull(e) as? KType.Nominal)?.sym as? ClassSymbol)?.takeIf { it.isStruct }
            for (m in program.modules) {
                val ast = runCatching { m.source.ast }.getOrNull() ?: continue
                AstTree.walk(ast) { node ->
                    when (node) {
                        is TypeCastExpr -> {
                            val toStr = model.conversion(node) == ConversionKind.TO_STR || model.typeOf(node.type) == KType.Str
                            if (toStr) {
                                enumOf(node.value)?.let { nameOf.add(it) }
                            }
                        }
                        is InterpolatedStringLiteral -> node.parts.forEach { part ->
                            if (part is InterpolationPart.Hole) {
                                enumOf(part.expr)?.let { nameOf.add(it) }
                            }
                        }
                        is BinaryExpr -> if (node.operator == BinaryOp.EQUALS || node.operator == BinaryOp.NOT_EQUAL) {
                            structOf(node.leftExpr)?.let { compared.add(it) }
                            structOf(node.rightExpr)?.let { compared.add(it) }
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
            return CppUsage(nameOf, enumValues, compared)
        }
    }
}
