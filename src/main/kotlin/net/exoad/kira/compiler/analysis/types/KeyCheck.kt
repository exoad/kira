package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import java.util.IdentityHashMap

internal fun isKeyedContainer(sym: TypeSymbol): Boolean =
    (sym as? ClassSymbol)?.kind == ClassKind.MAGIC && (sym.name == "Map" || sym.name == "Set")

/** A type a Map's key or a Set's element can be; a type parameter is checked where it is instantiated ([KeyCheck]). */
internal fun isKeyType(t: KType): Boolean =
    t is KType.Scalar || t == KType.Str || t is KType.Param || t == KType.Error || (t as? KType.Nominal)?.sym is EnumSymbol

internal const val KEY_RULE = "a Map's key and a Set's element are a scalar, a Str or an enum"

/**
 * Type parameters that reach a Map's key or a Set's element, directly, through a generic or an alias, or through a
 * generic call, checked at every instantiation: `Index<Holder>` for `class Index<K> { m: Map<K, Int32> }`.
 */
internal class KeyCheck(private val program: TypedProgram) {
    private val model = program.model
    private val reaching = IdentityHashMap<TypeParamSymbol, Boolean>()

    fun run() {
        do {
            val before = reaching.size
            model.typeRefs.values.forEach { markType(it) }
            model.calls.values.forEach { markCall(it) }
            model.opCalls.values.forEach { markCall(it) }
        } while (reaching.size != before)
        val found = mutableListOf<Pair<ASTNode, String>>()
        model.typeRefs.forEach { (node, t) ->
            val (what, arg) = firstBad(t, deep = model.aliasRefs.containsKey(node)) ?: return@forEach
            found.add(node to "${KiraUnparser.type(node)} makes ${arg.display()} $what; $KEY_RULE.")
        }
        model.calls.forEach { (node, call) -> badCall(call)?.let { found.add(node to it) } }
        model.opCalls.forEach { (node, call) -> badCall(call)?.let { found.add(node to it) } }
        // The tables are identity maps: report in source order, not hash order.
        found.map { (node, message) -> Triple(node, message, program.locate(node)) }
            .sortedWith(compareBy({ it.third?.first?.file ?: "" }, { it.third?.second?.lineNumber ?: -1 }, { it.third?.second?.column ?: -1 }))
            .forEach { (node, message) -> program.report("types.type.key", message, node) }
    }

    private fun slotParam(n: KType.Nominal, i: Int): TypeParamSymbol? =
        n.sym.typeParams.getOrNull(i)?.takeIf { reaching.containsKey(it) }

    private fun isSlot(n: KType.Nominal, i: Int): Boolean = (i == 0 && isKeyedContainer(n.sym)) || slotParam(n, i) != null

    private fun markType(t: KType) {
        when (t) {
            is KType.Nominal -> t.args.forEachIndexed { i, a ->
                val arg = (a as? TypeArg.Ty)?.t ?: return@forEachIndexed
                if (arg is KType.Param && isSlot(t, i)) {
                    reaching[arg.sym] = true
                }
                markType(arg)
            }
            is KType.Fn -> {
                t.params.forEach { markType(it.type) }
                markType(t.ret)
            }
            else -> {}
        }
    }

    private fun markCall(call: ResolvedCall) {
        call.fn?.typeParams?.forEach { p ->
            val arg = call.substitution[p]
            if (arg is KType.Param && reaching.containsKey(p)) {
                reaching[arg.sym] = true
            }
        }
    }

    /** The first argument in [t] that fills a key slot with a type that is no key; [deep] looks inside the arguments too. */
    private fun firstBad(t: KType, deep: Boolean): Pair<String, KType>? {
        if (t is KType.Nominal) {
            t.args.forEachIndexed { i, a ->
                val arg = (a as? TypeArg.Ty)?.t ?: return@forEachIndexed
                if (isSlot(t, i) && !isKeyType(arg)) {
                    val what = when {
                        i == 0 && isKeyedContainer(t.sym) -> if (t.sym.name == "Map") "a Map's key" else "a Set's element"
                        else -> "a Map's key or a Set's element through ${t.sym.name}'s ${slotParam(t, i)!!.name}"
                    }
                    return what to arg
                }
            }
        }
        if (!deep) {
            return null
        }
        val inner = when (t) {
            is KType.Nominal -> t.typeArgs()
            is KType.Fn -> t.params.map { it.type } + t.ret
            else -> emptyList()
        }
        inner.forEach { firstBad(it, true)?.let { found -> return found } }
        return null
    }

    private fun badCall(call: ResolvedCall): String? {
        val fn = call.fn ?: return null
        val p = fn.typeParams.firstOrNull { reaching.containsKey(it) && call.substitution[it]?.let { a -> !isKeyType(a) } == true } ?: return null
        val inst = "${fn.name}<${fn.typeParams.joinToString(", ") { call.substitution[it]?.display() ?: it.name }}>"
        return "$inst makes ${call.substitution[p]!!.display()} a Map's key or a Set's element through ${fn.name}'s ${p.name}; $KEY_RULE."
    }
}
