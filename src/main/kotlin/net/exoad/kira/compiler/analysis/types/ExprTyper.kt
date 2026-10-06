package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
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
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.core.OperatorIntrinsics
import java.util.IdentityHashMap

/**
 * The services one run of phase C shares: the program, its model, the rule objects, and the
 * one way to report (which never reports the same code twice at one node, so phase C does not
 * repeat what phase B said).
 */
internal class PhaseC(val program: TypedProgram) {
    val model: TypedModel = program.model
    val builtins: Builtins get() = program.builtins
    val facts = TypeFacts(program.builtins)
    val folder = ConstFolder(this)
    val coercions = CoercionRules(this)
    val conversions = ConversionRules(this)
    val literals = LiteralTyper(this)
    val members = MemberResolver(this)
    val calls = CallResolver(this)
    val lambdas = LambdaTyper(this)
    val exprs = ExprTyper(this)
    val stmts = StmtChecker(this)
    val bindings = MagicBindings()

    /** The lambda whose body declares each local or parameter (absent: declared outside every lambda). */
    val declaredIn: IdentityHashMap<Symbol, LambdaFrame> = IdentityHashMap()

    private val reported = IdentityHashMap<ASTNode, MutableSet<String>>()

    init {
        program.diagnostics.forEach { d -> d.node?.let { reported.getOrPut(it) { HashSet() }.add(d.code) } }
    }

    fun report(code: String, message: String, node: ASTNode?, severity: Severity = Severity.ERROR) {
        if (node != null && !reported.getOrPut(node) { HashSet() }.add(code)) {
            return
        }
        program.report(code, message, node, severity)
    }

    /** A Type node's resolved type. Phase B resolves every one; a missing entry is an internal error. */
    fun typeOf(t: Type): KType = model.typeRefs[t] ?: run {
        report("types.internal", "internal typer failure: phase B left the type '${KiraUnparser.type(t)}' unresolved.", t)
        KType.Error
    }

    fun coreModule(): ModuleSymbol? = program.module("kira:core")

    /** The type of the implicit receiver inside [owner]'s members: `Box<T>` inside `class Box<T>`. */
    fun selfType(owner: TypeSymbol): KType = when (owner) {
        is ClassSymbol -> {
            if (owner.kind == ClassKind.MAGIC) {
                Builtins.prim(owner.name)?.let { return KType.Scalar(it) }
                Builtins.SPECIAL[owner.name]?.let { return it }
            }
            owner.selfType
        }
        is TraitSymbol -> KType.Nominal(owner, owner.typeParams.map { TypeArg.Ty(KType.Param(it)) })
        is EnumSymbol -> KType.Nominal(owner)
    }

    /**
     * Whether writing [p] writes a variable the code may change: a `mut` local, a `mut`
     * parameter, a `mut` global, the receiver of a struct's `mut fx` or of any class method, a
     * struct field through a mutable struct, a `mut` field of a class (a reference, D29), an
     * element of a mutable container or of any `MutView`.
     */
    fun isMutablePlace(p: Place, ctx: BodyContext): Boolean = when (p) {
        is Place.Local -> p.sym.isMut
        is Place.Param -> p.sym.byRef
        is Place.Global -> p.sym.isMut
        is Place.This -> ctx.thisMutable
        is Place.Field -> {
            val owner = p.sym.owner
            if (owner is ClassSymbol && owner.kind == ClassKind.STRUCT) p.receiver?.let { isMutablePlace(it, ctx) } ?: false else p.sym.isMut
        }
        is Place.Index -> when (p.kind) {
            IndexKind.MUT_VIEW -> true
            IndexKind.VIEW, IndexKind.STR -> false
            else -> isMutablePlace(p.container, ctx)
        }
    }

    /**
     * True when writing [p] from [ctx] would write a lambda's capture: the place starts at a
     * variable declared outside the current lambda (or at a struct's receiver, which a lambda
     * copies) and no step on the way passes through a reference (a class, a Ref, a view).
     */
    fun writesCapture(p: Place, ctx: BodyContext): Boolean {
        val frame = ctx.lambda ?: return false
        return when (p) {
            is Place.Local -> declaredIn[p.sym] !== frame && !declaredWithin(p.sym, frame)
            is Place.Param -> declaredIn[p.sym] !== frame && !declaredWithin(p.sym, frame)
            is Place.Global -> false
            is Place.This -> (p.owner as? ClassSymbol)?.kind == ClassKind.STRUCT
            is Place.Field -> {
                val owner = p.sym.owner
                val throughReference = owner is TraitSymbol ||
                    (owner is ClassSymbol && (owner.kind == ClassKind.CLASS || owner.kind == ClassKind.OPAQUE || owner.name == "Ref"))
                if (throughReference) false else p.receiver?.let { writesCapture(it, ctx) } ?: false
            }
            is Place.Index -> if (p.kind == IndexKind.MUT_VIEW || p.kind == IndexKind.VIEW) false else writesCapture(p.container, ctx)
        }
    }

    /** [sym] was declared inside [frame] or a lambda nested in it. */
    private fun declaredWithin(sym: Symbol, frame: LambdaFrame): Boolean {
        var f: LambdaFrame? = declaredIn[sym]
        while (f != null) {
            if (f === frame) {
                return true
            }
            f = f.parent
        }
        return false
    }

    fun run() {
        stmts.all()
    }
}

/**
 * Bidirectional expression typing (design 3.3): [check] types an expression against the type
 * its context expects, recording any implicit conversion (CoercionRules), and [synth] types it
 * from itself alone. The expected type flows into the expressions whose type depends on it:
 * literals, arithmetic built on literals, array literals, lambdas, if-expressions and the
 * `@_infer` magic calls. A `Maybe<T>` context gives them `T` and wraps the result.
 *
 * Every expression typed gets `TypedModel.types`; identifiers get `refs`; places get `places`;
 * constant expressions get `consts` (ConstFolder).
 */
internal class ExprTyper(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    fun check(e: Expr, expected: KType, ctx: BodyContext, scope: Scope, what: String = "this position"): KType {
        val hint = if (e is IfExpr) expected else (facts.maybeInner(expected) ?: expected)
        val actual = type(e, hint, ctx, scope)
        c.coercions.assign(e, actual, expected, what)
        return actual
    }

    fun synth(e: Expr, ctx: BodyContext, scope: Scope): KType = type(e, null, ctx, scope)

    /** Types [e] with [hint] (the expected type, when there is one). */
    fun type(e: Expr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val t = try {
            typeUncached(e, hint, ctx, scope)
        } catch (overflow: StackOverflowError) {
            c.report("types.expr.too-deep", "This expression nests too deeply to type.", e)
            KType.Error
        }
        model.types[e] = t
        c.folder.fold(e)
        return t
    }

    private fun typeUncached(e: Expr, hint: KType?, ctx: BodyContext, scope: Scope): KType = when (e) {
        is IntegerLiteral -> c.literals.integer(e, hint)
        is FloatLiteral -> c.literals.float(e, hint)
        is CharLiteral -> c.literals.char(e)
        is StringLiteral -> c.literals.string(e)
        is InterpolatedStringLiteral -> interpolation(e, ctx, scope)
        is NullLiteral -> KType.NullT
        is ArrayLiteral -> array(e, hint, ctx, scope)
        is IntrinsicExpr -> c.calls.intrinsic(e, ctx, scope)
        is Identifier -> identifier(e, ctx, scope)
        is ThisExpr -> thisExpr(e, ctx)
        is MemberAccessExpr -> c.members.access(e, hint, ctx, scope)
        is FunctionCallExpr -> c.calls.call(e, hint, ctx, scope)
        is ObjectInitExpr -> c.calls.construct(e, ctx, scope)
        is ArrayIndexExpr -> index(e, ctx, scope, forWrite = false)
        is BinaryExpr -> binary(e, hint, ctx, scope)
        is UnaryExpr -> unary(e, hint, ctx, scope)
        is TypeCastExpr -> c.conversions.cast(e, ctx, scope)
        is IfExpr -> ifExpr(e, hint, ctx, scope)
        is LambdaExpr -> c.lambdas.lambda(e, hint, ctx, scope)
        is AssignmentExpr -> c.stmts.assignment(e, ctx, scope)
        is CompoundAssignmentExpr -> c.stmts.compoundAssignment(e, ctx, scope)
        is PlaceAssignmentExpr -> c.stmts.placeAssignment(e, ctx, scope)
        is ThrowExpr -> {
            check(e.value, KType.Str, ctx, scope, "a throw")
            KType.Never
        }
        is TryExpr -> {
            c.stmts.tryExpr(e, ctx, scope)
            KType.Void
        }
        is RangeExpr -> {
            synth(e.begin, ctx, scope)
            synth(e.end, ctx, scope)
            c.report(
                "types.range.position",
                "A range a..b is a for loop's iterable only (for i: T in a..b); it is not a value (D47).",
                e,
            )
            KType.Error
        }
        is TypeCheckExpr -> {
            synth(e.value, ctx, scope)
            c.report("types.is.unsupported", "`is` needs run-time type information, which is deferred (D43).", e)
            KType.Error
        }
        is WithExpr -> {
            c.report("types.with.unsupported", "`with` has no typed lowering; construct the value with T { name = value }.", e)
            KType.Error
        }
        NoExpr -> KType.Void
        is Type -> {
            c.report("types.expr.type", "'${KiraUnparser.type(e)}' is a type, not a value.", e)
            KType.Error
        }
        is Decl -> {
            c.report("types.decl.nested", "A declaration is not an expression here.", e)
            KType.Error
        }
        else -> {
            c.report("types.expr.unsupported", "The typed frontend has no rule for ${e.javaClass.simpleName} here.", e)
            KType.Error
        }
    }

    // ---- names -------------------------------------------------------------------------------

    /** Whether [id] names a value from here: a local, a field of the receiver, a global or a function. */
    fun namesValue(id: Identifier, ctx: BodyContext, scope: Scope): Boolean {
        if (scope.find(id.value) != null) {
            return true
        }
        ctx.owner?.let { owner -> if (c.members.field(c.selfType(owner), id.value) != null) return true }
        return c.program.graph.lookup(ctx.module, id.value) { it is GlobalSymbol || it is FnSymbol } !is ModuleGraph.Lookup.Missing
    }

    /** A name in value position: a local, a field of the implicit receiver, a global, or a function. */
    fun identifier(id: Identifier, ctx: BodyContext, scope: Scope): KType {
        val name = id.value
        scope.find(name)?.let { found ->
            val sym = found.symbol
            model.refs[id] = sym
            captureValue(sym, found.declaredIn, ctx)
            return when (sym) {
                is LocalSymbol -> {
                    model.places[id] = Place.Local(sym)
                    sym.type
                }
                is ParamSymbol -> {
                    model.places[id] = Place.Param(sym)
                    sym.type
                }
                else -> KType.Error
            }
        }
        ctx.owner?.let { owner ->
            val self = c.selfType(owner)
            c.members.field(self, name)?.let { (f, type) ->
                model.refs[id] = f
                model.places[id] = Place.Field(Place.This(owner), f)
                captureThis(ctx, owner, f)
                return type
            }
            if (c.members.method(self, name) != null) {
                c.report(
                    "types.member.method-value",
                    "'$name' is a method; call it ($name(...)), or wrap the call in a lambda to pass it on.",
                    id,
                )
                return KType.Error
            }
        }
        return when (val found = c.program.graph.lookup(ctx.module, name) { it is GlobalSymbol || it is FnSymbol }) {
            is ModuleGraph.Lookup.Found -> moduleValue(id, found.symbol)
            is ModuleGraph.Lookup.NotVisible -> moduleValue(id, found.symbol)
            is ModuleGraph.Lookup.Ambiguous -> {
                c.report(
                    "types.name.ambiguous",
                    "'$name' is exported by more than one used module: " +
                        (listOf(found.first) + found.others).joinToString(", ") { it.from.uri } + ".",
                    id,
                )
                moduleValue(id, found.first.symbol)
            }
            ModuleGraph.Lookup.Missing -> {
                val asType = c.program.graph.lookup(ctx.module, name) { it is ClassSymbol || it is TraitSymbol || it is EnumSymbol || it is AliasSymbol }
                if (asType !is ModuleGraph.Lookup.Missing || Builtins.isBuiltinName(name)) {
                    c.report("types.name.type-as-value", "'$name' is a type, not a value.", id)
                } else {
                    c.report("types.name.unknown", "Unknown name '$name'.", id)
                }
                KType.Error
            }
        }
    }

    private fun moduleValue(id: Identifier, sym: Symbol): KType {
        model.refs[id] = sym
        return when (sym) {
            is GlobalSymbol -> {
                // kira:core's true, false and null are values, not variables.
                if (!(sym.foreign is Foreign.Magic && sym.module.isStdlib)) {
                    model.places[id] = Place.Global(sym)
                }
                if (sym.type == KType.Error) KType.Error else sym.type
            }
            is FnSymbol -> {
                if (sym.typeParams.isNotEmpty()) {
                    c.report(
                        "types.fn.generic-value",
                        "'${sym.name}' is generic; a function value needs its type fixed: wrap a call to ${sym.name}<...>(...) in a lambda.",
                        id,
                    )
                    return KType.Error
                }
                if (sym.owner == null && sym.foreign != null) {
                    c.report(
                        "types.fn.foreign-value",
                        "'${sym.name}' is supplied by the ${if (sym.foreign is Foreign.Magic) "runtime" else "C/C++ side"}; call it, or wrap the call in a lambda to pass it on.",
                        id,
                    )
                    return KType.Error
                }
                model.coercions[id] = Coercion.FnRef(sym)
                sym.fnType
            }
            else -> KType.Error
        }
    }

    /** A local or parameter of an enclosing body, read inside a lambda: each lambda in between captures it. */
    private fun captureValue(sym: Symbol, declaredIn: LambdaFrame?, ctx: BodyContext) {
        var f = ctx.lambda
        while (f != null && f !== declaredIn) {
            f.add(Capture.Value(sym))
            f = f.parent
        }
    }

    /**
     * The implicit receiver used inside a lambda: a struct's field is copied under its own
     * capture ([Capture.Field]); a struct's method call, `this`, and anything of a class or
     * trait capture the receiver itself ([Capture.This]).
     */
    fun captureThis(ctx: BodyContext, owner: TypeSymbol, field: FieldSymbol?) {
        var f = ctx.lambda
        val struct = (owner as? ClassSymbol)?.kind == ClassKind.STRUCT
        while (f != null) {
            if (struct && field != null) f.add(Capture.Field(field)) else f.add(Capture.This(owner))
            f = f.parent
        }
    }

    private fun thisExpr(e: ThisExpr, ctx: BodyContext): KType {
        val owner = ctx.owner
        if (owner == null) {
            c.report("types.this.outside", "`this` is the receiver of a method; there is none here.", e)
            return KType.Error
        }
        model.places[e] = Place.This(owner)
        captureThis(ctx, owner, null)
        return c.selfType(owner)
    }

    // ---- literals ----------------------------------------------------------------------------

    private fun interpolation(e: InterpolatedStringLiteral, ctx: BodyContext, scope: Scope): KType {
        for (part in e.parts) {
            if (part is InterpolationPart.Hole) {
                val t = synth(part.expr, ctx, scope)
                if (!t.containsError() && !facts.isShowable(t)) {
                    c.report(
                        "types.interp.not-showable",
                        "\${...} takes a value with a text form (a number, Char, Bool, Str or an enum); this is ${t.display()}.",
                        part.expr,
                    )
                }
            }
        }
        return KType.Str
    }

    /**
     * `[a, b]` takes its type from the context (design 3.3): an `Arr<T>` or `Arr<T, N>` (whose
     * count must match), or a `List<T>` (the spec's `xs: List<Int32> = [1, 2, 3]`), and its
     * elements check against `T`. Without one it is an `Arr` of its first element's type.
     */
    private fun array(e: ArrayLiteral, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val arr = hint?.takeIf { facts.isArr(it) || facts.isList(it) } as? KType.Nominal
        if (arr != null) {
            val element = facts.elementOf(arr) ?: KType.Error
            val size = arr.constArgs().firstOrNull()
            if (size != null && e.value.size.toLong() != size) {
                c.report(
                    "types.const.arr-size",
                    "This literal has ${e.value.size} element${if (e.value.size == 1) "" else "s"}, but ${arr.display()} holds exactly $size.",
                    e,
                )
            }
            e.value.forEachIndexed { i, v -> check(v, element, ctx, scope, "element ${i + 1} of the array") }
            return arr
        }
        if (e.value.isEmpty()) {
            c.report("types.array.untyped", "An empty array literal needs its type from the context, like xs: Arr<Int32> = [].", e)
            return KType.Error
        }
        val first = synth(e.value.first(), ctx, scope)
        e.value.drop(1).forEachIndexed { i, v -> check(v, first, ctx, scope, "element ${i + 2} of the array") }
        return if (first.containsError()) KType.Error else facts.arrOf(first)
    }

    // ---- indexing ----------------------------------------------------------------------------

    /**
     * `a[i]`: a Str gives its `Char`; an Arr, List, View or MutView gives its element. The index
     * is a `Size` (D2). A Map is written with `m[k] = v` ([forWrite]); a read is `m.get(k)`.
     */
    fun index(e: ArrayIndexExpr, ctx: BodyContext, scope: Scope, forWrite: Boolean): KType {
        val ct = synth(e.originExpr, ctx, scope)
        if (ct.containsError()) {
            synth(e.indexExpr, ctx, scope)
            return KType.Error
        }
        val kind: IndexKind
        val result: KType
        when {
            ct == KType.Str -> {
                kind = IndexKind.STR
                result = KType.CHAR
                if (forWrite) {
                    c.report("types.index.str-write", "A Str's characters are read-only; build a new Str instead.", e)
                }
            }
            facts.isArr(ct) -> {
                kind = IndexKind.ARR
                result = facts.elementOf(ct) ?: KType.Error
            }
            facts.isList(ct) -> {
                kind = IndexKind.LIST
                result = facts.elementOf(ct) ?: KType.Error
            }
            facts.isView(ct) -> {
                kind = IndexKind.VIEW
                result = facts.elementOf(ct) ?: KType.Error
                if (forWrite) {
                    c.report("types.index.view-write", "A View is read-only; write through a MutView.", e)
                }
            }
            facts.isMutView(ct) -> {
                kind = IndexKind.MUT_VIEW
                result = facts.elementOf(ct) ?: KType.Error
            }
            facts.isMap(ct) -> {
                val args = (ct as KType.Nominal).typeArgs()
                check(e.indexExpr, args.getOrElse(0) { KType.Error }, ctx, scope, "a Map key")
                if (!forWrite) {
                    c.report(
                        "types.index.map-read",
                        "Read a Map with m.get(k), which is a Maybe<${args.getOrNull(1)?.display() ?: "V"}>; m[k] = v writes one.",
                        e,
                    )
                }
                containerPlace(e, IndexKind.MAP)
                return args.getOrElse(1) { KType.Error }
            }
            else -> {
                synth(e.indexExpr, ctx, scope)
                c.report("types.index.not-indexable", "${ct.display()} cannot be indexed; a Str, Arr, List, View or MutView can.", e)
                return KType.Error
            }
        }
        indexOperand(e.indexExpr, ctx, scope)
        containerPlace(e, kind)
        return result
    }

    private fun containerPlace(e: ArrayIndexExpr, kind: IndexKind) {
        model.places[e.originExpr]?.let { model.places[e] = Place.Index(it, kind, e.indexExpr) }
    }

    /** An index is a Size: a literal takes Size, anything else must already be one. */
    private fun indexOperand(i: Expr, ctx: BodyContext, scope: Scope) {
        val t = type(i, KType.SIZE, ctx, scope)
        if (!t.containsError() && t != KType.SIZE) {
            c.report(
                "types.index.size",
                "An index is a Size (D2); this is ${t.display()}. Convert it with `as Size`, or keep the index in a Size.",
                i,
            )
        }
    }

    // ---- operators ---------------------------------------------------------------------------

    /**
     * Types two operands so that a literal side (or a side built only of literals) takes the
     * other side's type (design 3.3). [hint] is the expected type of the first side typed.
     */
    fun operands(l: Expr, r: Expr, hint: KType?, ctx: BodyContext, scope: Scope): Pair<KType, KType> {
        if (c.literals.isLiteralOnly(l) && !c.literals.isLiteralOnly(r)) {
            val rt = type(r, hint, ctx, scope)
            val lt = type(l, rt.takeIf { !it.containsError() }, ctx, scope)
            return lt to rt
        }
        val lt = type(l, hint, ctx, scope)
        val rt = type(r, lt.takeIf { !it.containsError() }, ctx, scope)
        return lt to rt
    }

    private fun binary(e: BinaryExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val op = e.operator
        val numericHint = hint?.takeIf { facts.isNumeric(it) }
        return when (op) {
            BinaryOp.AND, BinaryOp.OR -> {
                check(e.leftExpr, KType.BOOL, ctx, scope, "the left side of ${sym(op)}")
                check(e.rightExpr, KType.BOOL, ctx, scope, "the right side of ${sym(op)}")
                KType.BOOL
            }
            in LiteralTyper.SHIFTS -> {
                val lt = type(e.leftExpr, numericHint, ctx, scope)
                val rt = synth(e.rightExpr, ctx, scope)
                if (lt.containsError() || rt.containsError()) {
                    return KType.Error
                }
                overload(e, lt, rt, ctx)?.let { return it }
                if (!facts.isInteger(lt) || !facts.isInteger(rt)) {
                    mismatch(e, op, lt, rt, "a shift takes an integer and an integer count")
                    return KType.Error
                }
                lt
            }
            in LiteralTyper.EQUALITY, in LiteralTyper.ORDERING -> comparison(e, ctx, scope)
            in LiteralTyper.ARITHMETIC, in LiteralTyper.BITS -> {
                val (lt, rt) = operands(e.leftExpr, e.rightExpr, numericHint ?: hint?.takeIf { it == KType.Str }, ctx, scope)
                if (lt.containsError() || rt.containsError()) {
                    return KType.Error
                }
                overload(e, lt, rt, ctx)?.let { return it }
                if (lt != rt) {
                    mismatch(
                        e, op, lt, rt,
                        if (facts.isNumeric(lt) && facts.isNumeric(rt)) "the operands of an operator have one type: no widening, no int with float; convert one side with `as`" else null,
                    )
                    return KType.Error
                }
                arithmetic(e, op, lt)
            }
            else -> {
                synth(e.leftExpr, ctx, scope)
                synth(e.rightExpr, ctx, scope)
                c.report("types.op.unsupported", "The operator ${sym(op)} has no typed meaning.", e)
                KType.Error
            }
        }
    }

    private fun arithmetic(e: BinaryExpr, op: BinaryOp, t: KType): KType {
        if (t == KType.Str) {
            if (op == BinaryOp.ADD) {
                return KType.Str
            }
            mismatch(e, op, t, t, "a Str takes + (concatenation) and the comparisons only")
            return KType.Error
        }
        if (t == KType.CHAR) {
            mismatch(e, op, t, t, "a Char has comparisons only (D3); convert it with `as UInt8` for arithmetic")
            return KType.Error
        }
        if (op in LiteralTyper.BITS) {
            if (!facts.isInteger(t)) {
                mismatch(e, op, t, t, "bitwise operators take integers; use && and || for Bool")
                return KType.Error
            }
            return t
        }
        if (!facts.isNumeric(t)) {
            mismatch(e, op, t, t, null)
            return KType.Error
        }
        if (op == BinaryOp.MOD && facts.isFloat(t)) {
            mismatch(e, op, t, t, "% takes integers")
            return KType.Error
        }
        return t
    }

    private fun comparison(e: BinaryExpr, ctx: BodyContext, scope: Scope): KType {
        val op = e.operator
        val (lt, rt) = operands(e.leftExpr, e.rightExpr, null, ctx, scope)
        if (lt.containsError() || rt.containsError()) {
            return KType.BOOL
        }
        overload(e, lt, rt, ctx)?.let { return it }
        if (lt != rt) {
            // A subclass compares with its base by identity: upcast the narrower side.
            val ln = lt as? KType.Nominal
            val rn = rt as? KType.Nominal
            if (op in LiteralTyper.EQUALITY && ln != null && rn != null && refLike(ln) && refLike(rn)) {
                when {
                    facts.isSubtype(ln, rn) -> {
                        model.coercions[e.leftExpr] = Coercion.Upcast(lt, rt)
                        return KType.BOOL
                    }
                    facts.isSubtype(rn, ln) -> {
                        model.coercions[e.rightExpr] = Coercion.Upcast(rt, lt)
                        return KType.BOOL
                    }
                }
            }
            mismatch(
                e, op, lt, rt,
                when {
                    facts.isInteger(lt) && facts.isInteger(rt) && (lt == KType.SIZE || rt == KType.SIZE) ->
                        "Size mixes with no other integer type (D2): keep the index in a Size, or convert with `as Size`"
                    facts.isNumeric(lt) && facts.isNumeric(rt) -> "both sides of a comparison have one type; convert one with `as`"
                    facts.isMaybe(lt) || facts.isMaybe(rt) -> "test a Maybe with isSome() or isNone()"
                    else -> null
                },
            )
            return KType.BOOL
        }
        if (op in LiteralTyper.ORDERING) {
            if (!facts.isNumeric(lt) && lt != KType.CHAR && lt != KType.Str) {
                mismatch(e, op, lt, rt, "only numbers, Char and Str are ordered")
            }
            return KType.BOOL
        }
        if (!equatable(lt)) {
            mismatch(e, op, lt, rt, "${lt.display()} has no ==")
        }
        return KType.BOOL
    }

    private fun refLike(t: KType.Nominal): Boolean = facts.isClass(t) || facts.isTrait(t)

    /** Types with `==` (R16): scalars, Str, enums, classes (identity), views, and structs whose fields all have it (D27). */
    private fun equatable(t: KType, seen: MutableSet<ClassSymbol> = HashSet()): Boolean = when {
        t is KType.Scalar || t == KType.Str || facts.isEnum(t) || facts.isClass(t) || facts.isTrait(t) -> true
        facts.isView(t) || facts.isMutView(t) || facts.isArr(t) || facts.isList(t) -> facts.elementOf(t)?.let { equatable(it, seen) } ?: false
        Builtins.tupleArity(facts.magicName(t) ?: "") != null -> (t as KType.Nominal).typeArgs().all { equatable(it, seen) }
        facts.isStruct(t) -> {
            val cls = (t as KType.Nominal).sym as ClassSymbol
            if (!seen.add(cls)) true else {
                val sub = cls.typeParams.zip(t.typeArgs()).toMap()
                cls.fields.all { equatable(it.type.substitute(sub), seen) }
            }
        }
        else -> false
    }

    /** An `@op_*` overload for [lt] and [rt], recorded in `opCalls`; null when the operands are builtin or none matches. */
    private fun overload(e: BinaryExpr, lt: KType, rt: KType, ctx: BodyContext): KType? {
        if (lt !is KType.Nominal || facts.magicName(lt) != null) {
            return null
        }
        val name = OperatorIntrinsics.binaryName(e.operator) ?: return null
        val candidates = LinkedHashSet<FnSymbol>()
        (listOf(ctx.module) + ctx.module.imports + listOf(lt.sym.module)).forEach { m -> candidates.addAll(m.operators) }
        val free = candidates.firstOrNull { f ->
            f.name == name && f.params.size == 2 && f.params[0].type == lt && f.params[1].type == rt
        }
        if (free != null) {
            model.opCalls[e] = ResolvedCall(
                CallKind.OP_OVERLOAD, free, null, false, emptyList(),
                listOf(ArgBinding.Given(e.leftExpr, false), ArgBinding.Given(e.rightExpr, false)), listOf(0, 1), free.ret,
            )
            return free.ret
        }
        val cls = lt.sym as? ClassSymbol ?: return null
        val member = cls.methods.firstOrNull { it.isOperator && it.name == name && it.params.size == 1 && it.params[0].type == rt } ?: return null
        model.opCalls[e] = ResolvedCall(
            CallKind.OP_OVERLOAD, member, e.leftExpr, false, emptyList(),
            listOf(ArgBinding.Given(e.rightExpr, false)), listOf(0), member.ret,
        )
        return member.ret
    }

    private fun sym(op: BinaryOp): String = op.symbol.joinToString("") { it.rep.toString() }

    private fun mismatch(e: Expr, op: BinaryOp, lt: KType, rt: KType, why: String?) {
        val reason = CastPrecedence(c.program).hint(e) ?: why
        c.report(
            "types.op.mismatch",
            "${lt.display()} ${sym(op)} ${rt.display()} is not an operation Kira has" + (reason?.let { ": $it." } ?: "."),
            e,
        )
    }

    private fun unary(e: UnaryExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val operand = e.operand
        if (e.operator == UnaryOp.NEG || e.operator == UnaryOp.POS) {
            val negate = e.operator == UnaryOp.NEG
            if (operand is IntegerLiteral) {
                val t = c.literals.integer(operand, hint, negate = negate, at = e)
                model.types[operand] = t
                return t
            }
            if (operand is FloatLiteral) {
                val t = c.literals.float(operand, hint, negate = negate, at = e)
                model.types[operand] = t
                return t
            }
        }
        val t = type(operand, hint?.takeIf { facts.isNumeric(it) || it == KType.BOOL }, ctx, scope)
        if (t.containsError()) {
            return KType.Error
        }
        val ok = when (e.operator) {
            UnaryOp.NEG, UnaryOp.POS -> facts.isNumeric(t)
            UnaryOp.NOT -> t == KType.BOOL
            UnaryOp.BIT_NOT -> facts.isInteger(t)
        }
        if (!ok) {
            c.report(
                "types.op.mismatch",
                "${e.operator.symbol.rep}${t.display()} is not an operation Kira has" + when (e.operator) {
                    UnaryOp.NOT -> ": ! takes a Bool."
                    UnaryOp.BIT_NOT -> ": ~ takes an integer."
                    else -> ": the sign operators take numbers."
                },
                e,
            )
            return KType.Error
        }
        return t
    }

    // ---- if-expressions ----------------------------------------------------------------------

    /**
     * `if c { a } else { b }`: every branch ends in an expression of the expected type (or, with
     * no expected type, of the first branch's). Each branch value gets its own coercion. It is
     * a candidate for a C++ ternary ([TypedModel.ifShape]) when every branch is that one
     * expression and none is a `throw`.
     */
    private fun ifExpr(e: IfExpr, expected: KType?, ctx: BodyContext, scope: Scope): KType {
        check(e.condition, KType.BOOL, ctx, scope, "an if condition")
        if (e.elseBranch.isEmpty()) {
            c.report(
                "types.if.no-else",
                "An if-expression needs an else branch: it must have a value on both paths.",
                e,
            )
        }
        var result: KType? = expected?.takeIf { it != KType.Void }
        var shape = true
        val branches = listOf(e.thenBranch, e.elseBranch).filter { it.isNotEmpty() }
        for (branch in branches) {
            val inner = scope.child()
            branch.dropLast(1).forEach { c.stmts.statement(it, ctx, inner) }
            val last = branch.last()
            val value = branchValue(last)
            if (value == null) {
                c.report(
                    "types.if.branch-value",
                    "A branch of an if-expression ends in the value it produces; this one ends in a statement.",
                    last,
                )
                c.stmts.statement(last, ctx, inner)
                shape = false
                continue
            }
            if (branch.size != 1 || value is ThrowExpr) {
                shape = false
            }
            if (value is IfExpr && model.ifShape[value] == false) {
                shape = false
            }
            val want = result
            val t = if (want != null) check(value, want, ctx, inner, "a branch of the if-expression") else synth(value, ctx, inner)
            if (t == KType.Void && !t.containsError()) {
                c.report("types.if.branch-value", "A branch of an if-expression must produce a value; this one is Void.", value)
            }
            if (result == null && t != KType.Never && !t.containsError()) {
                result = t
            }
        }
        model.ifShape[e] = shape && e.elseBranch.isNotEmpty()
        return result ?: if (branches.isEmpty()) KType.Error else KType.Never
    }

    /** The value expression a branch statement stands for, or null when it is not a plain expression. */
    private fun branchValue(s: Statement): Expr? {
        if (s.javaClass != Statement::class.java) {
            return null
        }
        val e = s.expr
        if (e is Decl || e is AssignmentExpr || e is CompoundAssignmentExpr || e is PlaceAssignmentExpr || e is TryExpr || e === NoExpr) {
            return null
        }
        return e
    }
}
