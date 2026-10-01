package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Lambdas (design 5.6, measured [S2]): explicit captures only, never `[=]` (C++20 `-Werror`
 * rejects its implicit `this`), each from `TypedModel.captures`:
 *
 * | captured | C++ |
 * |---|---|
 * | a local or parameter `k` | `[k]`, a copy |
 * | a struct field `k`, read in a struct method | `[c_k = k]`, reads `c_k`: never `[k = k]`, which clang's `-Wshadow-all` rejects |
 * | a struct's receiver (a method call, `this`) | `[*this]`, a copy of the struct |
 * | a class's receiver, the lambda passed to a non-escaping `Fx` parameter | `[this]` |
 * | a class's receiver, the lambda escaping | `[self = shared_from_this()]`, reads `self->k`; the class derives `kira::Shared<C>` |
 * | a `Ref<T>` | `[counter]`: the reference is copied, the cell shared |
 *
 * A lambda nested in one that copied a field or the receiver copies the outer copy (`[c_k]`,
 * `[self]`). Parameters are spelled as a function's (`const kira::Str&`, `T&` for a `mut`
 * one, D24), an unread one `[[maybe_unused]]`; the return type is always written.
 *
 * [prepare] runs before any class is defined, since the class definition (W2.4) must know
 * whether it derives `kira::Shared<C>` ([ClassSymbol.thisEscapes]): it marks every class a
 * lambda of which escapes capturing the receiver, and every class whose `this` is used as a
 * value (`shared_from_this()`, design 5.5). A non-escaping `Fx` parameter is spelled as a
 * template parameter (`template<typename F_p> requires kira::Callable<F_p, R, A...>`, `F_p&&
 * p`) by the declaration emitter, which also places the function in the header
 * ([CppPlacement.isTemplate]); both read the same fact, [CppPlacement.isNonEscapingFx].
 */
class CppClosureEmitter : CppLambdaPart {
    private val prepared = Collections.newSetFromMap(IdentityHashMap<TypedProgram, Boolean>())
    private val nonEscaping = IdentityHashMap<LambdaExpr, Boolean>()

    override fun emit(ctx: CppEmitContextImpl, l: LambdaExpr, parentPrec: Int): String {
        prepare(ctx.program)
        val lower = CppLowering.of(ctx)
        val state = lower.state
        val model = ctx.model
        val captures = model.captures(l) ?: run {
            ctx.diag(l, CppModuleEmitterFactory.INTERNAL_CODE, "no captures were recorded for this lambda")
            emptyList()
        }
        val outer = state.frame
        val owner = outer?.owner ?: ctx.scope
        // A value's lambda (a struct's, a value class's: W2.9 1.2.2) copies the object, `[*this]`, and its fields `[c_k = k]`.
        val struct = (owner as? ClassSymbol)?.isValue == true
        val capturesThis = captures.any { it is Capture.This }
        val outerAccess = outer?.receiverAccess ?: CppBodyState.ThisCapture.NONE
        val thisCapture = when {
            !capturesThis -> CppBodyState.ThisCapture.NONE
            struct -> CppBodyState.ThisCapture.COPY
            outerAccess == CppBodyState.ThisCapture.SELF -> CppBodyState.ThisCapture.SELF
            l in nonEscaping -> CppBodyState.ThisCapture.POINTER
            else -> CppBodyState.ThisCapture.SELF
        }
        val frame = CppBodyState.Frame(outer?.fn, owner, outer, l, thisCapture)
        val list = mutableListOf<String>()
        captures.forEach { c ->
            when (c) {
                is Capture.Value -> list += lower.nameOfSymbol(c.symbol, l) ?: ctx.names.escape(c.symbol.name)
                is Capture.Field -> if (thisCapture != CppBodyState.ThisCapture.COPY) {
                    val copied = outer?.fieldName(c.field)
                    if (copied != null) {
                        frame.fieldNames[c.field] = copied
                        list += copied
                    } else {
                        val name = state.fresh("c_${c.field.name}")
                        frame.fieldNames[c.field] = name
                        list += "$name = ${lower.implicitField(c.field)}"
                    }
                }
                is Capture.This -> list += when (thisCapture) {
                    CppBodyState.ThisCapture.COPY -> "*this"
                    CppBodyState.ThisCapture.POINTER -> "this"
                    CppBodyState.ThisCapture.SELF -> {
                        val cls = owner as? ClassSymbol
                        val method = outer?.fn
                        when {
                            outerAccess == CppBodyState.ThisCapture.SELF -> "self"
                            // W2.4's CppClassesPart.selfCapture spells it (this->shared_from_this() in a class template).
                            cls != null && method != null -> "self = ${ctx.parts.classes.selfCapture(ctx, cls, method, l)}"
                            else -> "self = shared_from_this()"
                        }
                    }
                    CppBodyState.ThisCapture.NONE -> "this"
                }
            }
        }
        if (thisCapture == CppBodyState.ThisCapture.SELF && outerAccess != CppBodyState.ThisCapture.SELF) {
            val cls = owner as? ClassSymbol
            when {
                cls == null || !cls.isRef ->
                    ctx.unsupported(l, "an escaping lambda capturing the receiver of ${owner?.name ?: "no class"} (only a class has a shared_from_this)")
                outer?.fn == null ->
                    ctx.unsupported(l, "an escaping lambda capturing this in an initially or finally block of ${cls.name} (C++ has no shared_ptr to an object under construction or destruction)")
                !cls.thisEscapes ->
                    // prepare() saw every lambda of the program; a class it missed would fail to compile.
                    ctx.diag(l, CppModuleEmitterFactory.INTERNAL_CODE, "class ${cls.name} captures shared_from_this() but was not marked thisEscapes before its definition")
            }
        }
        val stmts = ctx.parts.stmts as? CppStmtEmitter
        val def = l.def
        return state.inFrame(frame) {
            val params = def.parameters.map { p ->
                val sym = model.declSymbol(p) as? ParamSymbol
                val byRef = sym?.byRef == true
                val pos = if (byRef) Pos.MUT_PARAM else Pos.PARAM
                val type = if (model.typeOf(p.typeSpecifier) != null) ctx.spell(p.typeSpecifier, pos) else ctx.spell(sym?.type ?: KType.Error, pos, p)
                val name = if (sym != null) state.declare(sym, ctx.names.escape(p.name.value)) { stmts?.taken(ctx, it) ?: false } else ctx.names.escape(p.name.value)
                val unused = if (sym != null && stmts != null && !stmts.isRead(ctx, sym)) "[[maybe_unused]] " else ""
                "$unused$type $name"
            }
            val ret = if (model.typeOf(def.returnTypeSpecifier) != null) ctx.spell(def.returnTypeSpecifier, Pos.RETURN) else "void"
            val body: List<String> = when {
                stmts != null -> stmts.bodyLines(ctx, def.body ?: emptyList())
                else -> {
                    ctx.unsupported(l, "a lambda body without W2.3's statement part")
                    emptyList()
                }
            }
            val head = "[${list.joinToString(", ")}](${params.joinToString(", ")}) -> $ret"
            if (body.isEmpty()) "$head\n{\n}" else "$head\n{\n${CppHoister.indent(body)}\n}"
        }
    }

    /**
     * Once per program, before any declaration is emitted: which lambdas are passed straight to
     * a non-escaping `Fx` parameter (they capture a class's `this` as `[this]`), and which classes
     * derive `kira::Shared<C>` because a lambda of theirs escapes with the receiver, or `this`
     * is used as a value ([ClassSymbol.thisEscapes], read by the class part, W2.4).
     */
    fun prepare(program: TypedProgram) {
        if (!prepared.add(program)) {
            return
        }
        val model = program.model
        val escapes = CppEscapes(model)
        for (rc in model.calls.values) {
            val fn = rc.fn ?: continue
            rc.args.forEachIndexed { i, a ->
                val lambda = (a as? ArgBinding.Given)?.expr as? LambdaExpr ?: return@forEachIndexed
                val p = fn.params.getOrNull(i) ?: return@forEachIndexed
                if (p.type is KType.Fn && !p.byRef && !escapes.fxEscapes(p)) {
                    nonEscaping[lambda] = true
                }
            }
        }
        for (m in program.modules) {
            for (sym in m.declarations) {
                val cls = sym as? ClassSymbol ?: continue
                if (!cls.isRef) {
                    continue
                }
                val bodies = cls.methods.flatMap { it.body.orEmpty() } + cls.initially.orEmpty() + cls.finally.orEmpty()
                if (bodies.any { escapesThis(program, cls, it) }) {
                    cls.thisEscapes = true
                }
            }
        }
    }

    /** Whether [root] (in a method of [cls]) lets `this` escape: a lambda capturing it that escapes, or `this` as a value. */
    private fun escapesThis(program: TypedProgram, cls: ClassSymbol, root: Statement): Boolean {
        val model = program.model
        val receivers = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
        AstTree.walk(root) { node ->
            when (node) {
                is MemberAccessExpr -> if (node.origin is ThisExpr) receivers.add(node.origin)
                is FunctionCallExpr -> model.call(node)?.receiver?.let { if (it is ThisExpr) receivers.add(it) }
                is PlaceAssignmentExpr -> if (node.target is ThisExpr) receivers.add(node.target)
                else -> {}
            }
        }
        var escapes = false
        AstTree.walk(root) { node ->
            when (node) {
                is LambdaExpr -> {
                    val captures = model.captures(node).orEmpty()
                    if (node !in nonEscaping && captures.any { it is Capture.This && it.owner === cls }) {
                        escapes = true
                    }
                }
                is ThisExpr -> if (node !in receivers) escapes = true
                else -> {}
            }
        }
        return escapes
    }
}
