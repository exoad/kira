package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.analysis.types.rules.Rules
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.backend.codegen.cpp.CppHoister.Consumer
import net.exoad.kira.compiler.backend.codegen.cpp.CppHoister.MutOperand
import net.exoad.kira.compiler.backend.codegen.cpp.CppHoister.Operand
import net.exoad.kira.compiler.backend.codegen.cpp.CppHoister.Use

/**
 * Copy by default (50-round4, the user's decision after round 3). Kira passes every value by
 * value; the C++ lowering keeps its signatures (`const T&`, and `T&` for `mut`) and decides at
 * the use. Wherever C++ binds a reference to a first-class expression (a [Use]: an argument, a
 * receiver, an operator's or `kira::cat`'s operand, a `trace` argument, an `Fx` value's callee,
 * an extern's argument, an assignment's value; and a `for` range, [rangeLends]), the expression
 * is a temporary or it is copied, `T(e)`, unless a shape of the whitelist proves that nothing
 * writes, moves or frees the storage while the reference lives. A callee never guards: every
 * caller keeps invariant I (each `const&` parameter is bound to storage nothing changes until
 * the call returns, and each class `this` is held for the call), which is what makes a
 * by-value parameter and a value `this` PRIVATE in the callee.
 *
 * [pass] is the one function that decides, first match wins (2.0):
 *
 * 1. a view owner is lent (W4); one that is no place is made a prvalue first ([owned], O3);
 * 2. a `mut` operand or an assignment target is bound, never copied (W7): it carries no [Use];
 * 3. an operand C++ takes by value, or a prvalue ([isPrvalue]), is as is (W1);
 * 4. an operand D33 spilled is its typed temporary, which is the copy (W1);
 * 5. W2 ([isPrivate], a place only this body names, unwritten by the call) or W3 (any place,
 *    when the consumer is CONFINED, no operand is IMPURE and no own `mut` operand may hold the
 *    place or lie in it) lends; a `for` range lends under W6 ([rangeLends]);
 * 6. anything else is copied ([copy]): `T(e)`, which is `kira::Rc<C>(h)->m(...)` for a class
 *    handle receiver (it holds the object for the call, W5) and `kira::Fn<...>(f)(...)` for an
 *    `Fx` value's callee. A copied handle receiver whose drop may run an IMPURE `finally` is
 *    spilled into an IIFE of its call alone ([dropsOnCopy], E-DROP).
 *
 * A binding result is no prvalue here, so one that returns a reference the `LEND` pin does not
 * know (`List.first: kira::at({self}, 0)`) is copied, never lent: the pin is a spelling and
 * performance pin, not a safety one. `T(e)` of a real prvalue is the prvalue itself (C++17
 * guaranteed elision), so the list costs nothing where a binding returns by value.
 */
class CppCopyPolicy(private val lower: CppLowering) {
    private val model get() = lower.model

    /** The rules' shared predicates (`isReferenceStep`, `mayHold`, `isReference`), one per program. */
    internal val rules: Rules by lazy { Rules(lower.ctx.program) }

    // ---- the decision --------------------------------------------------------------------------

    /**
     * [text], the C++ of [op] at its use, as the policy passes it: as is when [op] carries no
     * [Use], was [spilled] into a typed temporary (W1) or [lends]; else copied, and [op] is
     * marked [Operand.copied]. [ops] are the consumer's operands, [named] its NAMED roots.
     */
    fun pass(op: Operand, text: CppEx, ops: List<Operand>, named: Set<Place>, consumer: Consumer?, spilled: Boolean): CppEx {
        val use = op.use ?: return text
        if (spilled || lends(op, use, ops, named, consumer)) {
            return text
        }
        op.copied = true
        return use.spell?.invoke(text) ?: copy(text, use.type, op.expr)
    }

    /** Whether the use [use] of [op] may bind a reference to it: a prvalue (W1), or a place W2 or W3 keeps still, or a ternary whose every branch is one. */
    private fun lends(op: Operand, use: Use, ops: List<Operand>, named: Set<Place>, consumer: Consumer?): Boolean {
        val e = op.expr
        if (e != null && isPrvalue(e, use.direct)) {
            return true
        }
        if (e is IfExpr && model.ifShape(e) == true) {
            // `c ? a : b` of two places is an lvalue: lent only when each branch is (w2-6 #1).
            return branches(e).all { b -> isPrvalue(b, use.direct) || model.readPlace(b)?.let { placeLends(it, model.typeOrNull(b), ops, named, consumer) } == true }
        }
        val p = use.place ?: e?.let { model.readPlace(it) } ?: return false
        return placeLends(p, use.type, ops, named, consumer)
    }

    /**
     * W2 or W3 for the place [p] of type [t]: its root is not NAMED ([named]: a variable the
     * consumer or one of its operands writes by name) and either [p] is PRIVATE, or the consumer
     * is CONFINED, none of [ops] is IMPURE, and no own `mut` operand of the consumer may hold
     * [p]'s storage or lie inside it (`Rules.mayHold` both ways, w2-6 #0) unless that operand is
     * PRIVATE with another root.
     */
    private fun placeLends(p: Place, t: KType?, ops: List<Operand>, named: Set<Place>, consumer: Consumer?): Boolean {
        val root = p.root()
        if (root in named) {
            return false
        }
        if (isPrivate(p)) {
            return true
        }
        if (consumer == null || !consumer.confined) {
            return false
        }
        if (ops.any { lower.hoister.rankOf(it) == CppHoister.IMPURE }) {
            return false
        }
        return consumer.mutOperands.none { m ->
            (rules.mayHold(m.type, t) || rules.mayHold(t, m.type)) && !(m.place != null && isPrivate(m.place) && m.place.root() != root)
        }
    }

    /** `T(text)`: the one copy primitive (a prvalue's is the prvalue itself). */
    fun copy(text: CppEx, type: KType, at: Expr?): CppEx =
        CppEx("${lower.ctx.spell(type, Pos.VALUE, at)}(${lower.wrap(text, CppPrec.ASSIGN)})", CppPrec.POSTFIX)

    /**
     * O3: [text], the C++ of [op], a prvalue when [op] is a view's first-class owner that is no
     * place and no prvalue (a ternary, a binding's result): the view is then of a copy, which
     * lives to the end of the full-expression, never of storage ViewPass did not check.
     */
    fun owned(op: Operand.Value, text: CppEx): CppEx {
        if (!op.owner) {
            return text
        }
        val e = op.expr ?: return text
        val t = model.typeOrNull(e) ?: return text
        // A place owner (a class handle's object, O1) is ViewPass's to check, never copied (E1).
        if (isPrvalue(e) || model.readPlace(e) != null || CppHoister.isSecondClass(t)) {
            return text
        }
        return copy(text, t, e)
    }

    /** NAMED (2.0): the roots [written] (the operands' own named writes) and those of the consumer's own `mut` operands. */
    fun named(written: Set<Place>, consumer: Consumer?): Set<Place> {
        if (consumer == null || consumer.mutOperands.isEmpty()) {
            return written
        }
        val out = LinkedHashSet(written)
        consumer.mutOperands.forEach { m -> m.place?.root()?.takeUnless { it is Place.Field && it.receiver == null }?.let { out += it } }
        return out
    }

    /**
     * E-DROP (2.5): whether a class handle receiver among [ops] is copied and may be the last
     * handle of an object whose `finally` is IMPURE. Its copy is a C++ temporary, released at
     * the end of the full-expression, where Kira releases it as the call returns; spilled into
     * its call's own IIFE, the `finally` runs before any sibling after the call.
     */
    fun dropsOnCopy(ops: List<Operand>, named: Set<Place>, consumer: Consumer?): Boolean = ops.any { op ->
        val use = op.use
        use != null && use.handle && model.dropsImpureFinally(use.type) && !lends(op, use, ops, named, consumer)
    }

    /**
     * Whether a write of a new value over an old one of [t] (an assignment, `xs[i] = v`,
     * `m[k] = v`, and the bindings `PLACE = {n}`: `List.set`, `Arr.set`, `MutView.set`) is spelled
     * `kira::replace(place) = value`, which stores first and drops the old value after, Kira's
     * order. C++'s `operator=` drops the old value's parts while it writes the new one, member by
     * member, so an IMPURE `finally` that drop runs (`Drops.mayDrop`) would free the place's
     * storage or rewrite it half-written. A class or trait handle, a `Maybe` of one and a `Weak`
     * are one `std::shared_ptr` or `std::weak_ptr`, whose assignment the standard specifies as
     * `shared_ptr(r).swap(*this)`: it stores, then drops, already.
     */
    fun dropsOnWrite(t: KType?): Boolean {
        if (!model.dropsImpureFinally(t)) {
            return false
        }
        val n = CppBindingTable.magicName(t)
        val one = if (n == "Maybe") (t as KType.Nominal).typeArgs().singleOrNull() else t
        return !(isHandle(one) || n == "Weak")
    }

    // ---- the words of 2.0 ----------------------------------------------------------------------

    /**
     * PRVALUE (2.0): [e]'s C++ is an object made for this use. A coercion that converts
     * ([converts]); a numeric, `Char`, `Str`, `null` or array literal; an interpolation; a
     * construction; a lambda literal; an operator or a cast; an if-expression lowered as an
     * IIFE, or a ternary whose branches are prvalues (a `Str` ternary's always are:
     * `kira::Str(branch)`); a class's `this` (`shared_from_this()`, a new handle); a call of a
     * Kira function (every one returns by value). Not a place (a lent result included), not a
     * binding's result, not a value class's `*this` or `kira::deref(...)`: an expression kind
     * not on the list is copied. For a [direct] use (an assignment's value) a `WrapSome` is no
     * conversion: C++ spells it implicitly, and `std::optional`'s `operator=(U&&)` binds the
     * value itself.
     */
    fun isPrvalue(e: Expr, direct: Boolean = false): Boolean {
        if (converts(e) && !(direct && model.coercion(e) is Coercion.WrapSome)) {
            return true
        }
        if (e is ThisExpr) {
            // A class's `this` as a value is `shared_from_this()`, a new handle; a value class's is `*this`.
            return isHandle(model.typeOrNull(e))
        }
        if (model.readPlace(e) != null) {
            return false
        }
        return when (e) {
            is IntegerLiteral, is FloatLiteral, is CharLiteral, is StringLiteral, is NullLiteral, is ArrayLiteral,
            is InterpolatedStringLiteral, is ObjectInitExpr, is LambdaExpr, is BinaryExpr, is UnaryExpr, is TypeCastExpr,
            -> true
            is IfExpr -> if (model.ifShape(e) == true) {
                model.typeOrNull(e) == KType.Str || branches(e).all { isPrvalue(it, direct) }
            } else {
                true
            }
            is FunctionCallExpr -> kiraCall(model.call(e))
            is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { kiraCall(model.call(it)) } == true
            else -> false
        }
    }

    /** A call of a Kira function, which returns by value (a binding's and an extern's may not). */
    private fun kiraCall(rc: ResolvedCall?): Boolean = rc != null && when (rc.kind) {
        CallKind.FREE, CallKind.METHOD, CallKind.VIRTUAL, CallKind.TRAIT, CallKind.FN_VALUE, CallKind.OP_OVERLOAD -> true
        else -> false
    }

    /**
     * Whether the C++ converts [e] into a temporary for its use: a `WrapSome` (`kira::Maybe<T>`),
     * an upcast (another `kira::Rc`), `none`, a function named as a value (`kira::Fn`), a `Str`
     * constant made a `kira::Str` (a `const char*`, as a receiver or where a `const kira::Str&`
     * binds it).
     */
    fun converts(e: Expr): Boolean = when (model.coercion(e)) {
        is Coercion.WrapSome, is Coercion.Upcast, is Coercion.NoneOf, is Coercion.FnRef, Coercion.StrConstReceiver -> true
        else -> lower.isCharPtr(e)
    }

    private fun branches(e: IfExpr): List<Expr> = listOfNotNull(lower.branchValue(e.thenBranch), lower.branchValue(e.elseBranch))

    /**
     * PRIVATE (2.0): the storage of [p] is reached only through its root, which only this body
     * names. The root is a local, a by-value parameter, the `this` of a value class in a method
     * that is no `mut fx` (or the `[*this]` copy a lambda holds), or a value temporary (a
     * receiver-less field root); and every step is a value step (`Rules.isReferenceStep` calls
     * none a step through a handle or into a view). A local's handle slot `h` is PRIVATE;
     * `h.items` is not.
     */
    fun isPrivate(p: Place): Boolean {
        val rootPrivate = when (val r = p.root()) {
            is Place.Local -> true
            is Place.Param -> !r.sym.byRef
            is Place.This -> (r.owner as? ClassSymbol)?.isStruct == true && thisIsValue()
            is Place.Field -> r.receiver == null
            else -> false
        }
        return rootPrivate && p.path().none { rules.isReferenceStep(it) }
    }

    /** Whether `this` here is a value nothing else writes during the call: a `[*this]` copy, or the receiver of a method that is no `mut fx`. */
    private fun thisIsValue(): Boolean {
        val frame = lower.state.frame ?: return false
        return frame.receiverAccess == CppBodyState.ThisCapture.COPY || frame.fn?.isMutMethod != true
    }

    /** A class or trait handle (`kira::Rc`): its copy holds the object (W5). */
    fun isHandle(t: KType?): Boolean {
        val n = t as? KType.Nominal ?: return false
        return when (val sym = n.sym) {
            is TraitSymbol -> true
            is ClassSymbol -> sym.kind == ClassKind.CLASS
            else -> false
        }
    }

    // ---- consumers and BYREF -------------------------------------------------------------------

    /** The consumer of a call's uses: CONFINED as `CallReach.confined` says (2.3), with its own `mut` operands. */
    fun callConsumer(rc: ResolvedCall, receiverType: KType?): Consumer =
        Consumer(CallReach.confined(rc, model, receiverType), mutOperands(rc, receiverType))

    /**
     * The consumer of a C++ runtime function's or operator's uses (`Str` `+` and `==`, a
     * container's `==`, `kira::cat`): CONFINED when no operand type holds a user class, trait
     * or type parameter, whose operators or formatting could run Kira code (2.3).
     */
    fun runtimeConsumer(types: List<KType?>): Consumer = Consumer(types.none { CallReach.holdsUserType(it) })

    /** The own `mut` operands of [rc] (2.0 NAMED, 2.3): `mut` arguments, the source of each `MutView` it is handed, a value `mut fx` receiver. */
    private fun mutOperands(rc: ResolvedCall, receiverType: KType?): List<MutOperand> {
        val out = mutableListOf<MutOperand>()
        rc.args.forEach { a ->
            if (a !is ArgBinding.Given) {
                return@forEach
            }
            val handedMutView = CppBindingTable.magicName(model.typeOrNull(a.expr)) == "MutView" && model.coercion(a.expr) !is Coercion.ToView
            when {
                a.byRef -> out += MutOperand(model.readPlace(a.expr), model.typeOrNull(a.expr))
                handedMutView -> {
                    val source = lower.hoister.lentMutView(a.expr)
                    out += MutOperand(source?.let { model.readPlace(it) }, source?.let { model.typeOrNull(it) })
                }
            }
        }
        if (rc.fn?.isMutMethod == true && receiverType != null && !rules.isReference(receiverType)) {
            val place = rc.receiver?.let { model.readPlace(it) }
                ?: if (rc.implicitThis) lower.state.frame?.owner?.let { Place.This(it) } else null
            out += MutOperand(place, receiverType)
        }
        return out
    }

    /**
     * BYREF for the argument [e] given for parameter [i] of [rc] (2.0): read from the C++
     * spelling of the parameter it binds, the root declaration's unsubstituted type (a generic
     * `T` at `Int32` is `const T&`, an `Fx` a template `F_p&&` or a `const kira::Fn&`); for a
     * binding, `trace` or an `Fx` value, whose C++ parameter this cannot read, the Kira type's
     * (a `Str`, a container, a value class, a `Maybe`, a tuple, an `Fx`, a type parameter); for
     * an extern, that or a class handle (contract 5.4: C++ that keeps a handle's object keeps
     * its own copy). A view is lent (W4), never a use.
     */
    fun argByRef(rc: ResolvedCall, i: Int, e: Expr): Boolean {
        val t = model.typeOrNull(e) ?: return false
        if (CppHoister.isSecondClass(t) || model.coercion(e) is Coercion.ToView) {
            return false
        }
        return when (rc.kind) {
            CallKind.FREE, CallKind.METHOD, CallKind.VIRTUAL, CallKind.TRAIT, CallKind.OP_OVERLOAD, CallKind.CTOR -> {
                val declared = rc.fn?.let { rootOf(it) }?.params?.getOrNull(i)?.type
                when {
                    declared == null -> lower.heldByReference(t)
                    // A first-class value given to a second-class parameter (a `Str` to a prototype's
                    // `CStr`, 50-round4 L2): the pointer C++ passes lends its storage.
                    CppHoister.isSecondClass(declared) -> lower.heldByReference(t)
                    else -> !lower.ctx.speller.byValue(declared)
                }
            }
            CallKind.FN_VALUE -> !lower.ctx.speller.byValue(t)
            CallKind.MAGIC, CallKind.PRINT -> lower.heldByReference(t)
            CallKind.EXTERN -> lower.heldByReference(t) || isHandle(t)
        }
    }

    /** The declaration [fn] overrides at the root of its chain: its parameters are the C++ spelling every override shares. */
    private fun rootOf(fn: FnSymbol): FnSymbol {
        var f = fn
        val seen = HashSet<FnSymbol>()
        while (seen.add(f)) {
            f = f.overrides ?: break
        }
        return f
    }

    // ---- W6: a range-for --------------------------------------------------------------------------

    /**
     * W6 (2.6): whether `for x in [target]` may iterate [target]'s storage in place (the
     * range-for keeps `begin`/`end` into it for the whole loop), else it iterates `T(r)`, a
     * prvalue the loop's reference keeps alive. A range that is no place lends only as a
     * prvalue. A place lends when no named write of the loop (range and [body], outside
     * lambdas) overlaps it or a prefix of it, its root is no temporary (which dies with the
     * range-init), and either it is PRIVATE (written only by name) or EffectsPass ranks the body,
     * the loop variable's drop included, at most READS (it writes only locals and runs no IMPURE
     * `finally`).
     */
    fun rangeLends(target: Expr, body: List<Statement>, element: KType): Boolean {
        val p = model.readPlace(target) ?: return isPrvalue(target)
        val root = p.root()
        if (root is Place.Field && root.receiver == null) {
            return false
        }
        if (lower.hoister.writtenPlaces(listOf<ASTNode>(target) + body).any { it.overlaps(p) }) {
            return false
        }
        return isPrivate(p) || (!model.dropsImpureFinally(element) && atMostReads(body))
    }

    /** Whether EffectsPass ranks every expression of [body] at most READS, and no local it declares may drop an IMPURE `finally`. */
    private fun atMostReads(body: List<Statement>): Boolean {
        val stack = ArrayDeque<ASTNode>()
        body.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when (n) {
                is Type -> {}
                is VariableDecl -> {
                    val local = model.declSymbol(n) as? LocalSymbol
                    if (local == null || model.dropsImpureFinally(local.type)) {
                        return false
                    }
                    n.value?.let { stack.addLast(it) }
                }
                // An expression's entry is the join of its nodes' (its lambdas' bodies aside, which run only when called).
                is Expr -> if ((model.effects[n] ?: Effect.IMPURE) == Effect.IMPURE) {
                    return false
                }
                else -> {
                    if (n is ForIterationStatement) {
                        val v = model.loop(n)?.variable as? LocalSymbol
                        if (v != null && model.dropsImpureFinally(v.type)) {
                            return false
                        }
                    }
                    AstTree.children(n).forEach { stack.addLast(it) }
                }
            }
        }
        return true
    }
}
