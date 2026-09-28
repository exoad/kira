package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.DeclarationCollector
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.ResolvedInit
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.prim
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Classes and traits (design 5.5, W2.4); structs are the declaration emitter's.
 *
 * A class:
 * ```
 * class Dog final : public Animal
 * {
 * public:
 *     Dog(kira::Str name_, std::int32_t tricks_ = 0);
 *     Dog(const Dog&) = delete;
 *     Dog& operator=(const Dog&) = delete;
 *     [[nodiscard]] kira::Str sound() const override;
 *     [[nodiscard]] std::int32_t learn();
 * private:
 *     std::int32_t tricks;
 * };
 * ```
 * - `public:` holds the constructor, the two copy deletes, the destructor (from `finally`,
 *   or a virtual default for the first class of a chain with virtual methods), the `pub`
 *   methods and the `pub` fields; `private:` the other methods and fields; each in Kira
 *   declaration order, with no blank line inside the class. A class is an identity:
 *   copying one is deleted.
 * - The constructor takes every field of the superclass chain, root first, in declaration
 *   order, each by value as `<field>_`; the trailing run of defaulted fields are C++ default
 *   arguments; it is `explicit` for exactly one parameter. Its mem-initializers keep that
 *   order (the superclass's constructor first, so the superclass's `initially` runs first)
 *   and move what is not a scalar; `initially` is its body. No field and no `initially`:
 *   `C() = default;`.
 * - `final` unless the program subclasses it. A method is virtual only when the program
 *   overrides it or a trait declares it; one that overrides says `override`; it is `const`
 *   unless it is `mut` or it writes its receiver ([CppClassFacts.isConstMethod]): the typer
 *   lets a plain `fx` of a class write a `mut` field and call a `mut fx` on itself (a class
 *   is a reference, D29: `thisMutable` holds in every class method), so `const` has to come
 *   from the body, not the modifier, or no compiler takes the method. A C++ `override`
 *   overrides every same-named virtual of every base at once, so the whole family (the
 *   superclass method and each trait method it implements, and on through their bases and
 *   overriders) is decided together. Either way it is callable through a `const kira::Rc<C>&`.
 *   An override's parameters are spelled as what it overrides spells them, under the base's
 *   type arguments ([ClassLowering.overrideParams]): a generic base's `v: T` is `const T&`,
 *   so its override at Int32 says `const std::int32_t&`, or it overrides nothing; two bases
 *   whose spellings differ are refused, since no one signature overrides both.
 * - Bodies are out of line: in the `.kira.cxx`, or in the header for a template (a generic
 *   class, a generic method, a method with a non-escaping `Fx` parameter) and for a
 *   header-only module.
 * - `initially` is the constructor's body and `finally` the destructor's, so a call in
 *   either dispatches to the class's own method, never to a subclass's override (C++), where
 *   the spec's Kotlin `init` would reach the override. A call from there, directly or
 *   through the class's own methods, to a method some subclass overrides is refused rather
 *   than lowered to the other meaning ([CppClassFacts.initializerDispatches]).
 * - `this` as a value is `shared_from_this()` ([thisValue]); the root of the class's
 *   superclass chain then derives `kira::Shared<Root>`, since `make_shared` wires exactly
 *   one `enable_shared_from_this` base (D11).
 * - Construction is `std::make_shared<C>(arguments in constructor order)` ([construct]).
 *
 * A trait is an abstract class with a virtual destructor. A bodyless method is pure, a
 * method with a body a virtual default whose body goes where a class member's would. Trait
 * inheritance is public, and `virtual` only for a trait that some class or trait of the
 * program reaches twice (a diamond); a class or trait that inherits a method of that trait
 * by dominance declares a forwarding override ([CppClassFacts.forwarders]), and one that
 * inherits two different overriders is refused. A virtual cannot be a template, so an `Fx`
 * parameter of a virtual method is always `kira::Fn`. A struct implementing a trait never
 * derives it in C++ (static dispatch through a generic bound, D1): each trait default body
 * it inherits becomes a member of the struct, declared in its body ([structInherited]) and
 * defined with its other methods ([ClassLowering.members]); boxing a struct into a trait
 * value is refused ([upcast], D43).
 */
class CppClassEmitter : CppClassesPart {
    private val factsByModel = WeakHashMap<TypedModel, CppClassFacts>()

    /** The program-wide class facts of [ctx]'s program, computed once per typed program. */
    fun facts(ctx: CppEmitContextImpl): CppClassFacts = synchronized(factsByModel) {
        factsByModel.getOrPut(ctx.model) { CppClassFacts(ctx.program) }
    }

    private fun lowering(ctx: CppEmitContextImpl): ClassLowering = ClassLowering(ctx, facts(ctx))

    override fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter) {
        when (sym) {
            is TraitSymbol -> lowering(ctx).defineTrait(sym, w)
            is ClassSymbol -> lowering(ctx).defineClass(sym, w)
            else -> sym.declNode()?.let { ctx.unsupported(it, "the type '${sym.name}'") }
        }
    }

    override fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean) {
        lowering(ctx).members(sym, w, inline)
    }

    override fun thisValue(ctx: CppEmitContextImpl, e: ThisExpr): String = lowering(ctx).thisValue(e)

    override fun selfCapture(ctx: CppEmitContextImpl, owner: ClassSymbol, method: FnSymbol, at: ASTNode): String =
        lowering(ctx).selfCapture(owner, method, at)

    override fun isConstMethod(ctx: CppEmitContextImpl, method: FnSymbol): Boolean = facts(ctx).isConstMethod(method)

    override fun selfReceiver(ctx: CppEmitContextImpl, owner: ClassSymbol, method: FnSymbol): String =
        lowering(ctx).selfReceiver(owner, method)

    override fun structInherited(ctx: CppEmitContextImpl, s: ClassSymbol): List<String> = lowering(ctx).structInherited(s)

    override fun structsCopying(ctx: CppEmitContextImpl, fn: FnSymbol): List<ClassSymbol> = lowering(ctx).structsCopying(fn)

    override fun construct(ctx: CppEmitContextImpl, e: ObjectInitExpr): String = lowering(ctx).construct(e)

    override fun guards(ctx: CppEmitContextImpl, fn: FnSymbol): CppGuards = lowering(ctx).declGuards(fn)

    override fun upcast(ctx: CppEmitContextImpl, e: Expr, c: Coercion.Upcast, text: String): String {
        val from = CppClassFacts.referent(c.from)
        if (from is ClassSymbol && from.isStruct) {
            ctx.unsupported(
                e,
                "boxing struct ${from.name} into a ${c.to.display()} value (D43: take it through a generic parameter bounded by the trait)",
            )
        }
        return text
    }

    companion object {
        /** What a lambda that escapes a class method captures `this` as: `[self = shared_from_this()]` (design 5.6). */
        const val SELF = "self"

        /** `std::make_shared<C>` adopts the one `enable_shared_from_this` base; the root of the superclass chain carries it. */
        const val SHARED_BASE = "kira::Shared"
    }
}

/** One module's view of the classes part: the text of its classes, traits and constructions. */
internal class ClassLowering(private val ctx: CppEmitContextImpl, private val facts: CppClassFacts) {
    private val model = ctx.model
    private val placement = ctx.placement

    /** For the defaults a constructor or a method takes: the declaration emitter's literal and constant spelling. */
    private val decls: CppDeclEmitter by lazy { CppDeclEmitter(ctx, CppUsage.NONE) }

    // ---- what a member is ------------------------------------------------------------------------

    /** Virtual in C++: a trait method, or a class method the program overrides or that overrides. */
    private fun isVirtual(fn: FnSymbol): Boolean = fn.owner is TraitSymbol || fn.isVirtual

    /** `const` in C++: not `mut`, and neither it nor any method of its override family writes the receiver. */
    private fun constSuffix(fn: FnSymbol): String = if (facts.isConstMethod(fn)) " const" else ""

    /** An `Fx` parameter spelled as a template parameter: non-escaping (EscapePass), on a method that is not virtual. */
    private fun isTemplateFx(fn: FnSymbol, p: ParamSymbol): Boolean = !isVirtual(fn) && placement.isNonEscapingFx(p)

    /** A member that is a template of its own: a generic method, or one with a template `Fx` parameter. */
    private fun isTemplate(fn: FnSymbol): Boolean = fn.typeParams.isNotEmpty() || fn.params.any { isTemplateFx(fn, it) }

    /** In the public section: `pub`, a trait's, or overriding a method that is public. */
    private fun isPublic(fn: FnSymbol): Boolean = fn.isPub || fn.owner is TraitSymbol || fn.overrides?.let { isPublic(it) } == true

    /** Whether the classes part lowers [fn] at all; the rest is refused by [refuseMembers]. */
    private fun isLowered(fn: FnSymbol): Boolean =
        !fn.isOperator && fn.name != ANONYMOUS && fn.foreign == null && !fn.isConst && !(fn.typeParams.isNotEmpty() && isVirtual(fn))

    private fun name(sym: TypeSymbol): String = ctx.names.escape(sym.name)

    // ---- classes -----------------------------------------------------------------------------------

    fun defineClass(c: ClassSymbol, w: CppWriter) {
        refuseClass(c)
        val name = name(c)
        ctx.parts.generics.templateHead(ctx, c.typeParams)?.let { w.line(it) }
        val public = mutableListOf<String>()
        val private = mutableListOf<String>()
        public += constructorDeclaration(c)
        public += "$name(const $name&) = delete;"
        public += "$name& operator=(const $name&) = delete;"
        destructorDeclaration(c)?.let { public += it }
        c.methods.filter { isLowered(it) }.forEach { fn -> (if (isPublic(fn)) public else private) += prototype(fn) }
        facts.forwarders(c).forEach { public += forwarderPrototype(it) }
        c.fields.forEach { f -> if (f.isPub) public += field(f) else private += field(f) }
        w.line("class $name${if (c.isSubclassed) "" else " final"}${bases(c)}")
        w.line("{")
        section(w, "public:", public)
        section(w, "private:", private)
        w.line("};")
    }

    private fun section(w: CppWriter, label: String, lines: List<String>) {
        if (lines.isEmpty()) {
            return
        }
        w.line(label)
        lines.forEach { w.line("$MEMBER_INDENT$it") }
    }

    private fun bases(c: ClassSymbol): String {
        val out = mutableListOf<String>()
        c.superclass?.let { if (facts.isKiraClass(it.sym)) out += "public ${ctx.speller.bareClass(it)}" }
        c.traits.forEach { t -> out += "public ${if (facts.isVirtualBase(t.sym)) "virtual " else ""}${ctx.speller.bareClass(t)}" }
        if (facts.derivesShared(c)) {
            out += "public ${CppClassEmitter.SHARED_BASE}<${ctx.speller.bareClass(c.selfType)}>"
        }
        return if (out.isEmpty()) "" else " : " + out.joinToString(", ")
    }

    /**
     * The Kira constructs a class may hold that C++ cannot take as they are. Each is a
     * `cpp.unsupported` at its declaration, so no header is written; a class the emitter
     * cannot lower faithfully is never lowered approximately.
     */
    private fun refuseClass(c: ClassSymbol) {
        c.superclass?.let { sup ->
            if (!facts.isKiraClass(sup.sym)) {
                ctx.unsupported(c.decl ?: return@let, "class ${c.name} extending ${sup.display()}, which is not a class this program defines (C++ derives only from one)")
            }
        }
        refuseMembers(c, c.methods)
        c.methods.forEach { fn ->
            if (fn.body == null && isLowered(fn) && !fn.isVirtual) {
                ctx.unsupported(fn.decl ?: c.decl ?: return@forEach, "'${c.name}.${fn.name}' without a body, a method implemented at instantiation (D43)")
            }
        }
        facts.initializerThisUses(c).forEach { node ->
            ctx.unsupported(node, "this captured or used as a value in an initially or finally block of ${c.name} (C++ has no shared_ptr to an object under construction or destruction)")
        }
        facts.initializerDispatches(c).forEach { d ->
            val reached = if (d.callee === d.overridden) "" else ", which reaches ${d.overridden.owner?.name}.${d.overridden.name}"
            ctx.unsupported(
                d.site,
                "the call to ${d.callee.name} in an initially or finally block of ${c.name}$reached: ${d.overrider.name} overrides it, " +
                    "and a C++ constructor or destructor runs ${c.name}'s own ${d.overridden.name}, where the spec's init block would run ${d.overrider.name}'s",
            )
        }
        facts.unconnectedTraitMethods(c).forEach { (trait, method, via) ->
            ctx.unsupported(
                c.decl ?: return@forEach,
                "${c.name} implementing ${trait.name}.${method.name} through ${via.name}.${method.name}, which C++ does not take as its override (override ${method.name} in ${c.name})",
            )
        }
        refuseAmbiguous(c)
        // A field's default is spelled in the constructor's declaration, and a lambda there is
        // the statement part's as much as one in a body (fielddefault: a Fx field defaulting to
        // bagloop2's lambda read freed heap bytes on g++; MSVC ASan: heap-use-after-free).
        c.fields.forEach { f -> f.default?.let { reportRefusals(listOf(it), null, c) } }
    }

    /** A diamond whose paths override a trait method differently, and [x] does not: C++ has no final overrider. */
    private fun refuseAmbiguous(x: TypeSymbol) {
        facts.ambiguousOverriders(x).forEach { (method, owners) ->
            ctx.unsupported(
                x.declNode() ?: return@forEach,
                "${x.name} inheriting ${method.name} from both ${owners.joinToString(" and ") { it.sym.name }} (C++ needs one final overrider: override ${method.name} in ${x.name})",
            )
        }
    }

    private fun refuseMembers(owner: TypeSymbol, methods: List<FnSymbol>) {
        val what = if (owner is TraitSymbol) "trait" else "class"
        methods.forEach { fn ->
            val node = fn.decl ?: owner.declNode() ?: return@forEach
            when {
                fn.isOperator -> ctx.unsupported(node, "the operator overload '${fn.name}' on $what ${owner.name}")
                fn.name == ANONYMOUS -> ctx.unsupported(node, "the anonymous method of $what ${owner.name}")
                fn.foreign != null -> ctx.unsupported(node, "the foreign method '${owner.name}.${fn.name}'")
                fn.isConst -> ctx.unsupported(node, "@_const on the $what method '${owner.name}.${fn.name}' (a $what reference is never a literal type)")
                fn.typeParams.isNotEmpty() && isVirtual(fn) -> ctx.unsupported(node, "the generic virtual method '${owner.name}.${fn.name}' (a C++ virtual cannot be a template)")
                else -> refuseOverride(owner, fn)
            }
        }
        facts.forwarders(owner).forEach { refuseForwarder(owner, it) }
    }

    // ---- constructor, destructor, fields -------------------------------------------------------------

    /** One constructor parameter: a field of the class or of a superclass. */
    private class CtorParam(val field: FieldSymbol, val owner: ClassSymbol, val type: String, val name: String, val default: String?, val moved: Boolean) {
        val argument: String get() = if (moved) "std::move($name)" else name
    }

    /**
     * Every field of the chain, root first, as the constructor takes it. The defaults are
     * spelled only [withDefaults] (the declaration), so a default is lowered once.
     */
    private fun constructorParams(c: ClassSymbol, withDefaults: Boolean): List<CtorParam> {
        val fields = facts.chain(c).flatMap { link -> link.cls.fields.map { f -> link to f } }
        var firstDefault = fields.size
        while (firstDefault > 0 && fields[firstDefault - 1].second.default != null) {
            firstDefault -= 1
        }
        return fields.mapIndexed { i, (link, f) ->
            val type = f.type.substitute(link.substitution)
            val default = if (withDefaults && i >= firstDefault) decls.initText(f.default!!, type) else null
            CtorParam(f, link.cls, fieldText(f, link.substitution), constructorParamName(f), default, !ctx.speller.byValue(type))
        }
    }

    /** `name_`: never a Kira name, so it shadows nothing; a keyword-escaped field (`new_`) gives `new_p`. */
    private fun constructorParamName(f: FieldSymbol): String {
        val escaped = ctx.names.escape(f.name)
        return if (escaped == f.name) "${f.name}_" else "${escaped}p"
    }

    /** A field's C++ type, alias-aware from its declaration unless a superclass's type arguments substitute it. */
    private fun fieldText(f: FieldSymbol, substitution: Map<TypeParamSymbol, KType>): String {
        val pos = if (f.isMut) Pos.MUT_VALUE else Pos.FIELD
        val decl = f.decl as? VariableDecl
        if (substitution.isEmpty() && decl != null && model.typeOf(decl.type) != null) {
            return ctx.spell(decl.type, pos)
        }
        return ctx.spell(f.type.substitute(substitution), pos, f.decl)
    }

    private fun constructorDeclaration(c: ClassSymbol): String {
        val name = name(c)
        val params = constructorParams(c, withDefaults = true)
        if (params.isEmpty() && c.initially == null) {
            return "$name() = default;"
        }
        val explicit = if (params.size == 1) "explicit " else ""
        return "$explicit$name(${params.joinToString(", ") { p -> "${p.type} ${p.name}" + (p.default?.let { " = $it" } ?: "") }});"
    }

    /** The superclass's constructor (when it takes anything), then each own field, in declaration order. */
    private fun memInitializers(c: ClassSymbol, params: List<CtorParam>): List<String> {
        val out = mutableListOf<String>()
        val inherited = params.filter { it.owner !== c }
        val sup = c.superclass
        if (sup != null && inherited.isNotEmpty()) {
            out += "${ctx.speller.bareClass(sup)}(${inherited.joinToString(", ") { it.argument }})"
        }
        params.filter { it.owner === c }.forEach { out += "${ctx.names.escape(it.field.name)}(${it.argument})" }
        return out
    }

    private fun destructorDeclaration(c: ClassSymbol): String? {
        val name = name(c)
        val own = facts.ownsVirtualDestructor(c)
        return when {
            c.finally != null -> when {
                own -> "virtual ~$name();"
                facts.baseHasVirtualDestructor(c) -> "~$name() override;"
                else -> "~$name();"
            }
            own -> "virtual ~$name() = default;"
            else -> null
        }
    }

    /**
     * `T name;`: the constructor initializes every field, so none carries a default here. A
     * private field no body reads or writes is `[[maybe_unused]]`: clang's
     * `-Wunused-private-field` (in `-Wall`) rejects one that only its mem-initializer names
     * (measured, zig c++ 0.15).
     */
    private fun field(f: FieldSymbol): String {
        val unused = if (!f.isPub && !facts.isReferenced(f)) "[[maybe_unused]] " else ""
        return "$unused${fieldText(f, emptyMap())} ${ctx.names.escape(f.name)};"
    }

    // ---- methods ---------------------------------------------------------------------------------------

    /**
     * The return type: what [fn] owes what it overrides ([overrideReturn]) when [owed], else
     * its own declaration's (alias-aware). A struct's copy of a trait default is a plain
     * member that overrides nothing, so it is spelled as written.
     */
    private fun returnText(fn: FnSymbol, owed: Boolean = true): String {
        if (owed) {
            overrideReturn(fn)?.let { return it }
        }
        val node = (fn.decl as? FunctionDecl)?.def?.returnTypeSpecifier
        return if (node != null && model.typeOf(node) != null) ctx.spell(node, Pos.RETURN) else ctx.spell(fn.ret, Pos.RETURN, fn.decl)
    }

    /**
     * One parameter, `[[maybe_unused]] const kira::Str& name = default`: its type from its
     * own declaration (alias-aware), unless [type] is given, the spelling an override owes
     * what it overrides ([overrideParams]).
     */
    private fun paramText(fn: FnSymbol, p: ParamSymbol, withDefault: Boolean, markUnused: Boolean, type: String? = null, name: String = ctx.paramName(p)): String {
        val unused = if (markUnused && !placement.bodyNames(fn, p)) "[[maybe_unused]] " else ""
        if (isTemplateFx(fn, p)) {
            return "$unused${ctx.speller.templateParamName(p)}&& $name"
        }
        val default = if (withDefault && p.default != null) " = ${decls.initText(p.default, p.type)}" else ""
        return "$unused${type ?: ownParam(fn, p)} $name$default"
    }

    /** [p]'s type as [fn]'s own declaration spells it in the parameter column (alias-aware): `std::int32_t`, `const kira::Str&`, `Handle*`. */
    private fun ownParam(fn: FnSymbol, p: ParamSymbol): String {
        val pos = if (p.byRef) Pos.MUT_PARAM else Pos.PARAM
        val node = (p.decl as? FunctionDeclParameterExpr)?.typeSpecifier
        return if (node != null && model.typeOf(node) != null) ctx.spell(node, pos) else ctx.spell(p.type, pos, p.decl ?: fn.decl)
    }

    /** Every parameter of [fn], the override spellings applied where [fn] owes them; one in [references] named as it says ([copiedParams]). */
    private fun paramsText(fn: FnSymbol, withDefault: Boolean, markUnused: Boolean, references: List<Pair<ParamSymbol, String>> = emptyList()): String {
        val owed = overrideParams(fn)
        return fn.params.mapIndexed { i, p ->
            val name = references.firstOrNull { it.first === p }?.second ?: ctx.paramName(p)
            paramText(fn, p, withDefault, markUnused, owed?.getOrNull(i), name)
        }.joinToString(", ")
    }

    // ---- what an override owes what it overrides -------------------------------------------------------

    /** The type arguments [d]'s owner has where [d] is seen from: `Source<Int32>` fixes Source's `T` to Int32. */
    private fun substitutionOf(d: CppClassFacts.Declared): Map<TypeParamSymbol, KType> = d.via.sym.typeParams.zip(d.via.typeArgs()).toMap()

    /**
     * The declarations [d] overrides in C++ ([CppClassFacts.overridden]), each seen through
     * [d]'s own type arguments (`Source<U>` under `Mid<Int32>` is `Source<Int32>`), or null
     * when it overrides nothing (a struct's method, a class method no base declares, a
     * trait's first declaration, the arities differ) or when they disagree
     * ([disagreement]; [refuseOverride] reports that), so [d] is spelled as written.
     */
    private fun owedBy(d: CppClassFacts.Declared): List<CppClassFacts.Declared>? {
        if (d in owedMemo) {
            return owedMemo[d]
        }
        // Asked again while it is being answered only through a cyclic hierarchy, which the typer refuses.
        if (!owedAnswering.add(d)) {
            return null
        }
        try {
            val family = overriddenFamily(d.method)
            val owed = if (family.isNullOrEmpty()) {
                null
            } else {
                val sub = substitutionOf(d)
                val seen = family.map { CppClassFacts.Declared(it.method, it.via.substitute(sub) as KType.Nominal) }
                if (disagreement(seen) == null) seen else null
            }
            owedMemo[d] = owed
            return owed
        } finally {
            owedAnswering.remove(d)
        }
    }

    /**
     * [owedBy]'s answers. Each one asks [disagreement] of the family above it, which asks
     * [rootOf] of every member, which asks [owedBy] again one level up: unremembered, that
     * is exponential in the depth of an override chain (a 3-parameter override at each of
     * 10 levels took 40 s to emit, 14 did not finish in 9 minutes; measured), remembered it
     * is one answer per declaration.
     */
    private val owedMemo = HashMap<CppClassFacts.Declared, List<CppClassFacts.Declared>?>()
    private val owedAnswering = HashSet<CppClassFacts.Declared>()
    private val rootMemo = HashMap<CppClassFacts.Declared, CppClassFacts.Declared>()

    /**
     * The declaration whose spelling C++ gives [d]: the one at the root of what [d]
     * overrides, seen through the type arguments of every step ([owedBy]), or [d] itself
     * when it overrides nothing. An override's C++ signature is never what its Kira
     * declaration wrote but what the declaration it overrides has in C++, and that one's is
     * the same question again: `Leaf: Mid` overriding `Mid.take(v: Int32)`, which overrides
     * `Source<T>.take(v: T)` at Int32, owes Source's `const std::int32_t&`, not Mid's written
     * `std::int32_t` (gcc "marked override, but does not override" on Leaf, measured).
     */
    private fun rootOf(d: CppClassFacts.Declared): CppClassFacts.Declared {
        rootMemo[d]?.let { return it }
        var cur = d
        val visited: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
        while (visited.add(cur.method)) {
            cur = owedBy(cur)?.first() ?: break
        }
        rootMemo[d] = cur
        return cur
    }

    /**
     * How C++ spells parameter [i] of the declaration [d]: as the root of what it overrides
     * wrote it ([rootOf]), under the type arguments that root's owner has there, the shape
     * decided before the arguments go in ([CppTypeSpeller.spellUnder]). A type parameter is
     * never known to be by value, so `Source<T>` spells `v: T` as `const T&`, and at
     * `Source<Int32>` that parameter is `const std::int32_t&`, not the `std::int32_t` a
     * declaration written at Int32 spells on its own; `f: Fx<Tuple1<T>, Int32>` is
     * `const kira::Fn<std::int32_t(const T&)>&` and stays so at Int32.
     */
    private fun cppParam(d: CppClassFacts.Declared, i: Int): String {
        val r = rootOf(d)
        val p = r.method.params[i]
        return ctx.speller.spellUnder(p.type, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM, substitutionOf(r), p.decl ?: r.method.decl)
    }

    /** How C++ spells the return type of [d], as [cppParam] spells a parameter: `Fx<Tuple1<T>, Int32>` at Int32 is `kira::Fn<std::int32_t(const std::int32_t&)>`. */
    private fun cppReturn(d: CppClassFacts.Declared): String {
        val r = rootOf(d)
        return ctx.speller.spellUnder(r.method.ret, Pos.RETURN, substitutionOf(r), r.method.decl)
    }

    /** [d]'s parameter list as C++ spells it ([cppParam]), `(const std::int32_t&, kira::Str&)`. */
    private fun signatureOf(d: CppClassFacts.Declared): String = "(${d.method.params.indices.joinToString(", ") { cppParam(d, it) }})"

    /** `std::int32_t Def<Int32>.id(const std::int32_t&)`: [d] as C++ has it, for a diagnostic. */
    private fun describe(d: CppClassFacts.Declared): String = "${cppReturn(d)} ${d.via.display()}.${d.method.name}${signatureOf(d)}"

    /**
     * Two declarations an override or a forwarder would have to override at once that C++
     * spells differently, or null when every one agrees. A C++ override overrides every
     * same-named virtual of every base, and ties to each only when the parameter types match
     * exactly: `class C: Abs, Def<Int32>` meets `Abs.id(v: Int32)` as `std::int32_t` and
     * `Def<T>.id(v: T)` as `const std::int32_t&`, so one signature overrides one and hides
     * the other (gcc and MSVC run it with two virtuals untied, clang stops on it under
     * `-Woverloaded-virtual`, measured), and a program that means one method is refused. Two
     * that tie by their parameters but return differently are refused too ("conflicting
     * return type"). Each is compared as C++ has it ([rootOf]), so `Mid.take` written at
     * Int32 over `Source<Int32>` is the `const std::int32_t&` a sibling `Gen<Int32>.take`
     * is, and not the `std::int32_t` an `Abs.take` written at Int32 is.
     */
    private fun disagreement(family: List<CppClassFacts.Declared>): Pair<CppClassFacts.Declared, CppClassFacts.Declared>? {
        val first = family.firstOrNull() ?: return null
        val sig = signatureOf(first) + cppReturn(first)
        val other = family.drop(1).firstOrNull { signatureOf(it) + cppReturn(it) != sig } ?: return null
        return first to other
    }

    /** [fn] as its own owner declares it, `Source<T>.take` under `Source<T>`: the view every spelling of [fn] starts from. */
    private fun self(fn: FnSymbol): CppClassFacts.Declared = CppClassFacts.Declared(fn, identityOf(fn.owner as TypeSymbol))

    /**
     * The parameter spellings [fn] owes what it overrides ([owedBy]), or null when it owes
     * nothing. One entry per parameter: the text C++ needs ([cppParam] of the overridden
     * declaration), or null where [fn]'s own spelling already has that shape, which keeps
     * an alias's name. An override of a generic base's `v: T` at Int32 is thereby
     * `const std::int32_t&`, as gcc, clang and MSVC require ("marked override, but does
     * not override", C3668, and the class stays abstract; measured).
     */
    private fun overrideParams(fn: FnSymbol): List<String?>? {
        val owed = owedBy(self(fn))?.first() ?: return null
        return fn.params.mapIndexed { i, p ->
            val text = cppParam(owed, i)
            if (text == writtenParam(p, fn)) null else text
        }
    }

    /** The return type [fn] owes what it overrides ([cppReturn]), or null when it owes nothing or its own spelling already has that shape. */
    private fun overrideReturn(fn: FnSymbol): String? {
        val owed = owedBy(self(fn))?.first() ?: return null
        val text = cppReturn(owed)
        return if (text == ctx.spell(fn.ret, Pos.RETURN, fn.decl)) null else text
    }

    /** [p] as [fn]'s own declaration spells it, from its type: `std::int32_t`, `const kira::Str&`, `std::int32_t&`. */
    private fun writtenParam(p: ParamSymbol, fn: FnSymbol): String =
        ctx.spell(p.type, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM, p.decl ?: fn.decl)

    /**
     * The parameters of [fn] that an override takes by `const&` where its own declaration
     * takes them by value ([overrideParams] against [writtenParam]): a scalar, a `Bool`, an
     * enum, a view, a pointer. Each is copied into a local of the parameter's own name at the
     * top of the definition ([valueCopy]), so the body reads the value the caller passed,
     * as Kira's declaration says, where the reference would alias the argument's place: a
     * class's plain `fx` may write a field (D29), and `f.take(g.n)` with `take` writing `n`
     * before it returns `v` gave 101 for the 1 Kira and the JS backend give (measured on
     * gcc, clang and MSVC). A parameter the body never names needs no copy; the reference
     * is then `[[maybe_unused]]` as any unnamed parameter is.
     */
    private fun copiedParams(fn: FnSymbol): List<ParamSymbol> {
        val owed = overrideParams(fn) ?: return emptyList()
        return fn.params.filterIndexed { i, p -> owed[i] != null && !p.byRef && ctx.speller.byValue(p.type) && placement.bodyNames(fn, p) }
    }

    /**
     * The parameters of [fn] that C++ takes by `const&` and that some effect of the body may
     * reach before the body reads them ([CppClassLifetimes.Guard.snapshots]): bound to a field
     * or an element of an object, the reference would read what the body's own writes left
     * there, or freed memory (`a.append(b.lines[0])` with `append` growing `lines`; measured on
     * g++: std::bad_alloc). Each is copied at entry ([copyLine]), which is the value the caller
     * passed. A template `Fx` parameter (`F_p&& g`) bound to an `Fx` an object holds is
     * copied the same way ([copyLine]: `const auto g = gRef_;`), since what it runs may replace
     * that `Fx` (fxparam3: the lambda reassigning `b.f` it runs as freed its own captures;
     * fxparam2: the body's `b.f = ...` then `g()` ran the replacement where Kira runs the
     * value passed). [owed] when the definition is spelled as what it overrides spells it
     * ([overrideParams]); a struct's copy of a trait default is spelled as written.
     */
    private fun snapshotParams(fn: FnSymbol, owed: Boolean): List<ParamSymbol> {
        val snapshots = facts.lifetimes.guard(fn).snapshots
        if (snapshots.isEmpty()) {
            return emptyList()
        }
        val spelled = if (owed) overrideParams(fn) else null
        return fn.params.filterIndexed { i, p ->
            snapshots.any { it === p } && placement.bodyNames(fn, p) && (isTemplateFx(fn, p) || isReference(spelled?.getOrNull(i) ?: ownParam(fn, p)))
        }
    }

    /** A parameter spelling that binds a reference: `const kira::Str&`, not `std::int32_t` or a template's `F_p&&` (which [snapshotParams] takes apart). */
    private fun isReference(text: String): Boolean = text.endsWith("&") && !text.endsWith("&&")

    /**
     * The parameters [fn]'s definition copies at entry, in parameter order: the ones an
     * override takes by `const&` where its own declaration takes them by value
     * ([copiedParams]), and the ones an effect of the body may reach ([snapshotParams]).
     */
    private fun copiedAtEntry(fn: FnSymbol, owed: Boolean = true): List<ParamSymbol> {
        val byValue = if (owed) copiedParams(fn) else emptyList()
        val snapshots = snapshotParams(fn, owed)
        return fn.params.filter { p -> byValue.any { it === p } || snapshots.any { it === p } }
    }

    /**
     * The name of each copied parameter's reference in the definition being written
     * ([copiedAtEntry]): `vRef_` for `v`, a name of the classes part's own ([bodyName]).
     * `v_` was the `t0_` of the statement part's first D33 temporary for a parameter named
     * `t0`, and the `ex_` of its catch variable for one named `ex`, either of which then
     * shadowed the parameter (g++ -Werror=shadow, measured); for a synthesized `x_p0_` it
     * was the reserved `x_p0__`.
     */
    private fun referenceNames(fn: FnSymbol, owed: Boolean = true): List<Pair<ParamSymbol, String>> =
        copiedAtEntry(fn, owed).map { p -> p to bodyName(ctx.paramName(p), "Ref") }

    /**
     * The local that copies [p] from its reference [reference] at the top of a definition: a
     * by-value type as [valueCopy] spells it, anything else as a `const` value of its own type,
     * `const kira::Str s = sRef_;`, `const T v = vRef_;`.
     */
    private fun copyLine(fn: FnSymbol, p: ParamSymbol, reference: String): String = when {
        isTemplateFx(fn, p) -> templateFxCopy(p, reference)
        ctx.speller.byValue(p.type) -> valueCopy(fn, p, reference)
        else -> "${constLocal(valueText(fn, p))} ${ctx.paramName(p)} = $reference;"
    }

    /** `const auto g = gRef_;`: a copy of what a template `Fx` parameter was bound to, a closure or a `kira::Fn`, whose call is `const`. */
    private fun templateFxCopy(p: ParamSymbol, reference: String): String = "const auto ${ctx.paramName(p)} = $reference;"

    /** [p]'s type as a value, alias-aware from its declaration: `kira::Str`, `kira::Maybe<kira::Rc<Item>>`, `T`. */
    private fun valueText(fn: FnSymbol, p: ParamSymbol): String {
        val node = (p.decl as? FunctionDeclParameterExpr)?.typeSpecifier
        return if (node != null && model.typeOf(node) != null) ctx.spell(node, Pos.VALUE) else ctx.spell(p.type, Pos.VALUE, p.decl ?: fn.decl)
    }

    /**
     * `[[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();`: the method holds the
     * object it runs on for the call ([CppClassLifetimes.Guard.holdsThis]), since its body reads
     * the receiver after an effect that may drop the object's last other owner
     * (`t.kids[0].leave()` with `leave` calling `tree.clear()`: the Kid was freed under its own
     * method, measured on g++). `weak_from_this()` is empty for an object no `kira::Rc` owns (a
     * C++ caller's stack object), where `shared_from_this()` would throw.
     */
    private fun holdLine(owner: ClassSymbol): String {
        val weak = if (owner.typeParams.isNotEmpty()) "this->weak_from_this()" else "weak_from_this()"
        return "[[maybe_unused]] const auto ${bodyName("keep", "Alive")} = $weak.lock();"
    }

    /** Reports what [nodes] (a body, or a field's default) do that no guard makes safe ([CppClassLifetimes.refusals]). */
    private fun reportRefusals(nodes: List<ASTNode>, fn: FnSymbol?, owner: TypeSymbol?) {
        facts.lifetimes.refusals(nodes, fn, owner).forEach { (node, message) -> ctx.diag(node, CppModuleEmitterFactory.UNSUPPORTED_CODE, message) }
    }

    /**
     * What a free function's or a struct method's definition writes ahead of its body
     * ([CppClassesPart.guards]): the parameters C++ takes by `const&` that an effect of the
     * body may reach ([CppClassLifetimes.Guard.snapshots]), each copied at entry. What the body
     * does that no guard makes safe is reported here. A struct's `this` is the declaration
     * emitter's, and never held.
     */
    fun declGuards(fn: FnSymbol): CppGuards {
        val body = fn.body
        if (fn.isConst || body == null) {
            return CppGuards.NONE
        }
        reportRefusals(body, fn, fn.owner)
        val snapshots = facts.lifetimes.guard(fn).snapshots
        val copied = fn.params.filter { p ->
            snapshots.any { it === p } && (placement.isNonEscapingFx(p) || !ctx.speller.byValue(p.type)) && placement.bodyNames(fn, p)
        }
        if (copied.isEmpty()) {
            return CppGuards.NONE
        }
        val references = LinkedHashMap<ParamSymbol, String>()
        copied.forEach { p -> references[p] = bodyName(ctx.paramName(p), "Ref") }
        return CppGuards(
            references,
            references.map { (p, reference) ->
                if (placement.isNonEscapingFx(p)) templateFxCopy(p, reference) else "${constLocal(valueText(fn, p))} ${ctx.paramName(p)} = $reference;"
            },
        )
    }

    /**
     * `const std::int32_t v = vRef_;`: the local that copies the reference [reference] of [p],
     * of the type [fn]'s own declaration gives the parameter (alias-aware), which is a value
     * type ([copiedParams]); a pointer (`Unsafe<X>`, `CStr`, an opaque class) keeps its own
     * `const` where the pointee's is, `const std::int32_t* const v`, `Handle* const v`,
     * never `const const std::int32_t*` or a `const Handle*` the handle's callee refuses.
     */
    private fun valueCopy(fn: FnSymbol, p: ParamSymbol, reference: String): String =
        "${constLocal(ownParam(fn, p))} ${ctx.paramName(p)} = $reference;"

    /** [owner] named by its own type parameters, `Source<T>`: the substitution under which its declarations are spelled as written. */
    private fun identityOf(owner: TypeSymbol): KType.Nominal = KType.Nominal(owner, owner.typeParams.map { TypeArg.Ty(KType.Param(it)) })

    /** What [fn] overrides in C++ ([CppClassFacts.overridden]), or null when it is no virtual or the arities differ (the typer's to refuse). */
    private fun overriddenFamily(fn: FnSymbol): List<CppClassFacts.Declared>? {
        val owner = fn.owner ?: return null
        if (!isVirtual(fn)) {
            return null
        }
        val family = facts.overridden(owner, fn.name)
        return if (family.all { it.method.params.size == fn.params.size }) family else null
    }

    private fun refuseOverride(owner: TypeSymbol, fn: FnSymbol) {
        val (a, b) = disagreement(overriddenFamily(fn).orEmpty()) ?: return
        val node = fn.decl ?: owner.declNode() ?: return
        ctx.unsupported(
            node,
            "${owner.name}.${fn.name} overriding both ${describe(a)} and ${describe(b)}, which C++ spells differently, so no one signature overrides both",
        )
    }

    /** As [refuseOverride], for a forwarder [f] of [x]: its target and every declaration it overrides are one signature, or the name is refused. */
    private fun refuseForwarder(x: TypeSymbol, f: CppClassFacts.Forwarder) {
        val (a, b) = disagreement(forwarderFamily(f)) ?: return
        val node = x.declNode() ?: return
        ctx.unsupported(
            node,
            "${x.name} inheriting ${f.method.name} from both ${describe(a)} and ${describe(b)}, " +
                "which C++ spells differently, so no one override ties them (override ${f.method.name} in ${x.name})",
        )
    }

    /** The body a forwarder calls and every declaration it overrides: one C++ signature ([disagreement]). */
    private fun forwarderFamily(f: CppClassFacts.Forwarder): List<CppClassFacts.Declared> = listOf(CppClassFacts.Declared(f.method, f.via)) + f.overridden

    /** The method's own template head: its type parameters, then one `F_p` per template `Fx` parameter. */
    private fun templateHead(fn: FnSymbol): List<String> {
        val fxParams = fn.params.filter { isTemplateFx(fn, it) }
        if (fn.typeParams.isEmpty() && fxParams.isEmpty()) {
            return emptyList()
        }
        val names = fn.typeParams.map { "typename ${ctx.names.escape(it.name)}" } + fxParams.map { "typename ${ctx.speller.templateParamName(it)}" }
        val out = mutableListOf("template<${names.joinToString(", ")}>")
        if (fxParams.isNotEmpty()) {
            out += "  requires " + fxParams.joinToString(" && ") { p -> ctx.speller.callable(ctx.speller.templateParamName(p), p.type as KType.Fn) }
        }
        return out
    }

    /** `[[nodiscard]] virtual R name(params = defaults) const = 0;` and its variants, after the method's own template head. */
    private fun prototype(fn: FnSymbol): List<String> {
        val nodiscard = if (fn.ret == KType.Void || fn.ret == KType.Never) "" else "[[nodiscard]] "
        val virtual = isVirtual(fn)
        val overrides = virtual && fn.overrides != null
        val specifier = if (virtual && !overrides) "virtual " else ""
        val params = paramsText(fn, withDefault = true, markUnused = false)
        val constSuffix = constSuffix(fn)
        val overrideSuffix = if (overrides) " override" else ""
        val pure = if (virtual && fn.body == null) " = 0" else ""
        return templateHead(fn) + "$nodiscard$specifier${returnText(fn)} ${ctx.names.escape(fn.name)}($params)$constSuffix$overrideSuffix$pure;"
    }

    // ---- forwarding overrides (a diamond's dominance, CppClassFacts.forwarders) -----------------------

    private fun forwarderSubstitution(f: CppClassFacts.Forwarder): Map<TypeParamSymbol, KType> =
        f.via.sym.typeParams.zip(f.via.typeArgs()).toMap()

    /** The body a forwarder calls, as the forwarding type names its owner: what the forwarder's C++ signature is spelled from. */
    private fun target(f: CppClassFacts.Forwarder): CppClassFacts.Declared = CppClassFacts.Declared(f.method, f.via)

    /**
     * The forwarder's parameters, spelled as C++ spells its target ([cppParam]: as the
     * target's own root declaration spells them under the target's type arguments), which
     * every declaration it overrides spells the same way or the forwarder is refused
     * ([refuseForwarder]): a generic sibling's `v: T` at Int32 is `const std::int32_t&`, and
     * a by-value `std::int32_t` would override nothing of it.
     */
    private fun forwarderParams(f: CppClassFacts.Forwarder, withDefault: Boolean): String {
        val sub = forwarderSubstitution(f)
        val target = target(f)
        return f.method.params.mapIndexed { i, p ->
            val default = if (withDefault && p.default != null) " = ${decls.initText(p.default, p.type.substitute(sub))}" else ""
            "${cppParam(target, i)} ${forwardedName(i)}$default"
        }.joinToString(", ")
    }

    /** `a0_`, `a1_`: synthesized, so a forwarder's parameters never shadow a member (`-Wshadow`). */
    private fun forwardedName(i: Int): String = "a${i}_"

    private fun forwarderPrototype(f: CppClassFacts.Forwarder): String {
        val m = f.method
        val ret = m.ret.substitute(forwarderSubstitution(f))
        val nodiscard = if (ret == KType.Void || ret == KType.Never) "" else "[[nodiscard]] "
        val constSuffix = constSuffix(m)
        return "$nodiscard${cppReturn(target(f))} ${ctx.names.escape(m.name)}(${forwarderParams(f, withDefault = true)})$constSuffix override;"
    }

    private fun forwarderDefinition(w: CppWriter, owner: TypeSymbol, f: CppClassFacts.Forwarder, inline: Boolean) {
        val m = f.method
        val ret = m.ret.substitute(forwarderSubstitution(f))
        ctx.parts.generics.templateHead(ctx, owner.typeParams)?.let { w.line(it) }
        val constSuffix = constSuffix(m)
        val head = "${inlineSpecifier(owner, inline, template = false)}${cppReturn(target(f))} ${qualifier(owner)}${ctx.names.escape(m.name)}(${forwarderParams(f, withDefault = false)})$constSuffix"
        val call = "${ctx.speller.bareClass(f.via)}::${ctx.names.escape(m.name)}(${m.params.indices.joinToString(", ") { forwardedName(it) }})"
        w.block(head) {
            line(if (ret == KType.Void || ret == KType.Never) "$call;" else "return $call;")
        }
    }

    /** `Name::` or `Name<T, U>::` before an out-of-line member. */
    private fun qualifier(owner: TypeSymbol): String {
        val name = name(owner)
        if (owner.typeParams.isEmpty()) {
            return "$name::"
        }
        return "$name<${owner.typeParams.joinToString(", ") { ctx.names.escape(it.name) }}>::"
    }

    // ---- traits ------------------------------------------------------------------------------------------

    fun defineTrait(t: TraitSymbol, w: CppWriter) {
        refuseMembers(t, t.methods)
        facts.traitThisUses(t).forEach { node ->
            ctx.unsupported(node, "a lambda that captures this and escapes a default body of trait ${t.name} (a trait has no shared_from_this)")
        }
        val name = name(t)
        ctx.parts.generics.templateHead(ctx, t.typeParams)?.let { w.line(it) }
        val bases = t.parents.map { p -> "public ${if (facts.isVirtualBase(p.sym)) "virtual " else ""}${ctx.speller.bareClass(p)}" }
        val public = mutableListOf<String>()
        if (t.parents.isEmpty()) {
            public += "virtual ~$name() = default;"
        }
        t.methods.filter { isLowered(it) }.forEach { public += prototype(it) }
        facts.forwarders(t).forEach { public += forwarderPrototype(it) }
        refuseAmbiguous(t)
        w.line("class $name${if (bases.isEmpty()) "" else " : " + bases.joinToString(", ")}")
        w.line("{")
        section(w, "public:", public)
        w.line("};")
    }

    // ---- out-of-line members -----------------------------------------------------------------------------

    /**
     * The out-of-line members of [sym]. An exported class of a source module is asked twice
     * ([CppDeclEmitter.definitionsIn]): its template members go to the header ([inline]),
     * the rest to the source. Anywhere else one call writes them all, `inline` in a header
     * unless they are templates.
     */
    fun members(sym: TypeSymbol, w: CppWriter, inline: Boolean) {
        val p = placement.type(sym as Symbol)
        val split = p.decl == Home.HEADER && p.def == Home.SOURCE
        fun wanted(fn: FnSymbol): Boolean = fn.body != null && isLowered(fn) && (!split || inline == isTemplate(fn))
        val blocks = mutableListOf<CppWriter.() -> Unit>()
        when (sym) {
            // A struct's own methods are the declaration emitter's; the trait default bodies it
            // inherits are members of its own here (structInherited), never templates.
            is ClassSymbol -> if (sym.isStruct) {
                if (!split || !inline) {
                    structDefaults(sym, report = false).forEach { fn -> blocks += { inheritedDefinition(this, sym, fn, inline) } }
                }
            } else {
                if (!split || !inline) {
                    constructorDefinition(sym, inline)?.let { blocks += it }
                    destructorDefinition(sym, inline)?.let { blocks += it }
                }
                sym.methods.filter { wanted(it) }.forEach { fn -> blocks += { methodDefinition(this, sym, fn, inline) } }
            }
            is TraitSymbol -> sym.methods.filter { wanted(it) }.forEach { fn -> blocks += { methodDefinition(this, sym, fn, inline) } }
            else -> {}
        }
        if (!split || !inline) {
            facts.forwarders(sym).forEach { f -> blocks += { forwarderDefinition(this, sym, f, inline) } }
        }
        blocks.forEachIndexed { i, write ->
            if (i > 0) {
                w.blank()
            }
            w.write()
        }
    }

    private fun inlineSpecifier(owner: TypeSymbol, inline: Boolean, template: Boolean): String =
        if (inline && owner.typeParams.isEmpty() && !template) "inline " else ""

    /**
     * `R Owner::name(params) const { ... }`. A class method whose body reads its receiver after
     * an effect that may free an object holds itself first ([holdLine]); a trait's default
     * body cannot, and is refused ([refuseTraitHold]). A parameter the definition copies at
     * entry ([copiedAtEntry]: one the override takes by reference where its own declaration
     * takes it by value, or one an effect of the body may reach) is named `vRef_` here
     * ([referenceNames]) and copied into `v` before the body ([copyLine]), so the body sees the
     * value it was passed, as Kira's parameters are values.
     */
    private fun methodDefinition(w: CppWriter, owner: TypeSymbol, fn: FnSymbol, inline: Boolean) {
        val body = fn.body ?: emptyList()
        reportRefusals(body, fn, owner)
        val guard = facts.lifetimes.guard(fn)
        val hold = guard.holdsThis && owner is ClassSymbol && owner.kind == ClassKind.CLASS
        if (guard.holdsThis && owner is TraitSymbol) {
            refuseTraitHold(owner, fn, guard)
        }
        fn.decl?.let { node -> ctx.lineDirective(node)?.let { w.line(it) } }
        ctx.parts.generics.templateHead(ctx, owner.typeParams)?.let { w.line(it) }
        templateHead(fn).forEach { w.line(it) }
        val references = referenceNames(fn)
        val params = paramsText(fn, withDefault = false, markUnused = true, references = references)
        val constSuffix = constSuffix(fn)
        val head = "${inlineSpecifier(owner, inline, isTemplate(fn))}${returnText(fn)} ${qualifier(owner)}${ctx.names.escape(fn.name)}($params)$constSuffix"
        w.block(head) {
            if (hold) {
                line(holdLine(owner as ClassSymbol))
            }
            references.forEach { (p, reference) -> line(copyLine(fn, p, reference)) }
            ctx.body(fn, body, this)
        }
    }

    /**
     * A trait's default body that reads its receiver after an effect that may free the object
     * it runs on: a class method holds itself for the call ([holdLine]), but a trait has no
     * `shared_from_this`, and the object may be freed under the body (a use after free).
     */
    private fun refuseTraitHold(t: TraitSymbol, fn: FnSymbol, guard: CppClassLifetimes.Guard) {
        val node = fn.decl ?: t.decl ?: return
        val cause = guard.releasedBy?.let { facts.lifetimes.describe(it) } ?: "an effect that may free an object"
        ctx.diag(
            node,
            CppModuleEmitterFactory.UNSUPPORTED_CODE,
            "the default body of ${t.name}.${fn.name} reads its receiver after $cause, which may free the object it runs on, and a trait has no " +
                "shared_from_this to keep that object alive for the call: override ${fn.name} in each class that implements ${t.name}, " +
                "or read what the body needs from this before that call",
        )
    }

    private fun constructorDefinition(c: ClassSymbol, inline: Boolean): (CppWriter.() -> Unit)? {
        val params = constructorParams(c, withDefaults = false)
        if (params.isEmpty() && c.initially == null) {
            return null
        }
        c.initially?.let { reportRefusals(it, null, c) }
        return {
            c.decl?.let { node -> ctx.lineDirective(node)?.let { line(it) } }
            ctx.parts.generics.templateHead(ctx, c.typeParams)?.let { line(it) }
            val head = "${inlineSpecifier(c, inline, template = false)}${qualifier(c)}${name(c)}(${params.joinToString(", ") { "${it.type} ${it.name}" }})"
            val inits = memInitializers(c, params)
            val body: CppWriter.() -> Unit = { c.initially?.let { ctx.body(null, it, this) } }
            if (inits.isEmpty()) {
                block(head, body = body)
            } else {
                line(head)
                block("$MEMBER_INDENT: ${inits.joinToString(", ")}", body = body)
            }
        }
    }

    // ---- a struct's inherited trait defaults (static dispatch, D1) -----------------------------------------

    /**
     * The prototypes of the trait default bodies the struct [s] inherits, for its body: a
     * plain member, `[[nodiscard]] R name(params = defaults) const;`, never virtual, since
     * the struct derives no trait in C++ and is reached only by static dispatch (a generic
     * bound, or a direct call). It is `const` unless the trait declares it `mut` or its
     * body writes the receiver ([CppClassFacts.traitBodyWrites]: a trait's body may call a
     * `mut fx` on `this` without being `mut`, as a class's may).
     */
    fun structInherited(s: ClassSymbol): List<String> = structDefaults(s, report = true).map { m ->
        ctx.deferringTo(m.module) {
            val nodiscard = if (m.ret == KType.Void || m.ret == KType.Never) "" else "[[nodiscard]] "
            val params = m.params.joinToString(", ") { paramText(m, it, withDefault = true, markUnused = false) }
            "$nodiscard${returnText(m, owed = false)} ${ctx.names.escape(m.name)}($params)${structConstSuffix(m)};"
        }
    }

    /** The structs that take the trait default body [fn] as a member ([structInherited]): its parameters are spelled in their class scope too. */
    fun structsCopying(fn: FnSymbol): List<ClassSymbol> = facts.structsCopying(fn)

    private fun structConstSuffix(m: FnSymbol): String = if (m.isMutMethod || facts.traitBodyWrites(m)) "" else " const"

    /**
     * The trait default bodies the struct [s] takes as members: every one it inherits and
     * does not override, less those the trait's own lowering refuses ([isLowered]). Refused
     * ([report], once, at the struct): one whose owner is a generic trait (reached directly
     * or through a trait that forwards to it), since its body is typed under the owner's
     * parameters, which the struct's copy would have to substitute, and the copy is spelled
     * from the body as written; one from another module whose body names a declaration
     * that module's `.kira.cxx` keeps to itself ([privateReferences]), since the copy is
     * spelled in [s]'s module, where `::shapes::helper` names nothing; and a name two trait
     * paths give different bodies to ([CppClassFacts.ambiguousDefaults]).
     */
    private fun structDefaults(s: ClassSymbol, report: Boolean): List<FnSymbol> {
        if (report) {
            facts.ambiguousDefaults(s).forEach { (method, owners) ->
                ctx.unsupported(
                    s.decl ?: return@forEach,
                    "struct ${s.name} inheriting ${method.name} from both ${owners.joinToString(" and ") { it.sym.name }} (two default bodies, and the pick would be silent: override ${method.name} in ${s.name})",
                )
            }
        }
        return facts.inheritedDefaults(s).mapNotNull { (m, via) ->
            val at = s.decl ?: m.decl
            val hidden = if (m.module === s.module) emptyList() else privateReferences(m)
            when {
                !isLowered(m) -> null
                m.owner?.typeParams?.isNotEmpty() == true -> {
                    if (report && at != null) {
                        ctx.unsupported(at, "struct ${s.name} inheriting the default body of ${via.display()}.${m.name} from a generic trait (override ${m.name} in ${s.name})")
                    }
                    null
                }
                hidden.isNotEmpty() -> {
                    if (report && at != null) {
                        val names = hidden.joinToString(", ") { "'${it.name}'" }
                        ctx.unsupported(
                            at,
                            "struct ${s.name} inheriting the default body of ${via.display()}.${m.name} from module ${m.module.uri}, which names $names, private to that module " +
                                "(override ${m.name} in ${s.name}, or make ${if (hidden.size == 1) "it" else "them"} pub)",
                        )
                    }
                    null
                }
                else -> m
            }
        }
    }

    /**
     * The module-level declarations of [m]'s own module that [m]'s body names and that
     * module's `.kira.cxx` keeps in its anonymous namespace: not `pub`, and not hoisted into
     * the header's `impl_` (a header-only module, or one a template needs). A copy of the
     * body spelled in another module cannot reach them. A function, a constant or module
     * state, a type: what the body calls, reads, constructs or spells as a type; a member
     * of a type is reached through the type, so it is not one.
     */
    private fun privateReferences(m: FnSymbol): List<Symbol> {
        val home = m.module
        val homePlacement = ctx.placementOf(home)
        val out = LinkedHashMap<Symbol, Unit>()
        fun note(sym: Symbol?) {
            if (sym == null || sym.module !== home || home.declarations.none { it === sym }) {
                return
            }
            if (homePlacement.isExported(sym) || homePlacement.inImpl(sym)) {
                return
            }
            out.putIfAbsent(sym, Unit)
        }
        fun noteType(t: KType?) {
            when (t) {
                is KType.Nominal -> {
                    note(t.sym as? Symbol)
                    t.typeArgs().forEach(::noteType)
                }
                is KType.Fn -> {
                    t.params.forEach { noteType(it.type) }
                    noteType(t.ret)
                }
                else -> {}
            }
        }
        m.body?.forEach { s ->
            AstTree.walk(s) { node ->
                when (node) {
                    is Identifier -> note(model.symbolOf(node))
                    is FunctionCallExpr -> note(model.call(node)?.fn)
                    is ObjectInitExpr -> note(model.init(node)?.cls)
                    // A Type spelled through an alias is spelled by the alias's name (CppTypeSpeller.text).
                    is Type -> {
                        note(model.aliasRefs[node])
                        noteType(model.typeOf(node))
                    }
                    else -> {}
                }
            }
        }
        return out.keys.toList()
    }

    /**
     * `R Square::twice() const { ... }`: the trait's body, as a member of the struct
     * (`Pair<T>::` and the template head for a generic struct). What the body refuses is
     * refused where the body is written, by the trait's own module, which spells that body
     * as the trait's member: the copy leaves those to it ([CppEmitContextImpl.deferringTo]),
     * so a program reports one construct once, whichever module copies it.
     */
    private fun inheritedDefinition(w: CppWriter, s: ClassSymbol, m: FnSymbol, inline: Boolean) = ctx.deferringTo(m.module) {
        m.decl?.let { node -> ctx.lineDirective(node)?.let { w.line(it) } }
        ctx.parts.generics.templateHead(ctx, s.typeParams)?.let { w.line(it) }
        // The copy overrides nothing, so it is spelled as written; what the body may reach is copied as in the trait's own.
        val references = referenceNames(m, owed = false)
        val params = m.params.joinToString(", ") { p ->
            paramText(m, p, withDefault = false, markUnused = true, name = references.firstOrNull { it.first === p }?.second ?: ctx.paramName(p))
        }
        val head = "${inlineSpecifier(s, inline, template = false)}${returnText(m, owed = false)} ${qualifier(s)}${ctx.names.escape(m.name)}($params)${structConstSuffix(m)}"
        w.block(head) {
            references.forEach { (p, reference) -> line(copyLine(m, p, reference)) }
            ctx.body(m, m.body ?: emptyList(), this)
        }
    }

    private fun destructorDefinition(c: ClassSymbol, inline: Boolean): (CppWriter.() -> Unit)? {
        val statements = c.finally ?: return null
        reportRefusals(statements, null, c)
        return {
            c.decl?.let { node -> ctx.lineDirective(node)?.let { line(it) } }
            ctx.parts.generics.templateHead(ctx, c.typeParams)?.let { line(it) }
            block("${inlineSpecifier(c, inline, template = false)}${qualifier(c)}~${name(c)}()") {
                ctx.body(null, statements, this)
            }
        }
    }

    // ---- this ------------------------------------------------------------------------------------------------

    /**
     * `this` as a value. In a struct method `*this` (inside a `[*this]` lambda, the copy).
     * In a class method the object's own `kira::Rc<C>`, from `shared_from_this()`: the
     * `enable_shared_from_this` base is the chain's root, so a subclass casts down to itself
     * (`static_pointer_cast`), and a `const` method ([CppClassFacts.isConstMethod]) sees a
     * `const` object, whose `shared_from_this()` is `shared_ptr<const Root>`
     * (`const_pointer_cast`: a class is a reference, and D29 lets a `mut fx` run through any
     * reference). Inside a lambda that
     * escapes the method the same casts apply to the captured `self` ([selfCapture]), which is
     * that `shared_from_this()`.
     */
    fun thisValue(e: ThisExpr): String {
        val site = facts.site(e)
        if (site == null) {
            ctx.diag(e, CppModuleEmitterFactory.INTERNAL_CODE, "this outside a method body of a class, struct or trait")
            return "/* this */"
        }
        val owner = site.owner
        if (owner is ClassSymbol && owner.isStruct) {
            return "*this"
        }
        if (owner !is ClassSymbol || owner.kind != ClassKind.CLASS) {
            ctx.unsupported(e, "this as a value in a default body of trait ${owner.name} (a trait has no shared_from_this)")
            return "/* this */"
        }
        val fn = site.fn
        if (fn == null) {
            ctx.unsupported(e, "this as a value in an initially or finally block of ${owner.name} (C++ has no shared_ptr to an object under construction or destruction)")
            return "/* this */"
        }
        val root = facts.chain(owner).first()
        if (!facts.derivesShared(root.cls)) {
            ctx.diag(e, CppModuleEmitterFactory.INTERNAL_CODE, "this is used as a value in ${owner.name}, but its root class ${root.cls.name} derives no kira::Shared")
            return "/* this */"
        }
        val self = ctx.speller.bareClass(owner.selfType)
        val constant = facts.isConstMethod(fn)
        val source = if (site.inEscapingLambda) CppClassEmitter.SELF else sharedFromThis(owner)
        return when {
            root.cls === owner && !constant -> source
            root.cls === owner -> "std::const_pointer_cast<$self>($source)"
            !constant -> "std::static_pointer_cast<$self>($source)"
            else -> "std::static_pointer_cast<$self>(std::const_pointer_cast<${ctx.speller.bareClass(root.type)}>($source))"
        }
    }

    /**
     * The initializer of `self` in `[self = ...]` for a lambda that escapes [method] of
     * [owner] (design 5.6): `shared_from_this()`, a `shared_ptr` to the chain's root (`const`
     * in a `const` method, [CppClassFacts.isConstMethod], the fact the closure part reads
     * through `CppClassesPart.isConstMethod`), as the closures golden spells it and W2.3's
     * closure part captures it; [thisValue] casts it to the class and [selfReceiver] spells
     * it as a receiver. In a class template it is `this->shared_from_this()`, since the
     * `kira::Shared` base may be dependent there.
     */
    @Suppress("UNUSED_PARAMETER")
    fun selfCapture(owner: ClassSymbol, method: FnSymbol, at: ASTNode): String {
        val root = facts.chain(owner).first()
        if (!facts.derivesShared(root.cls)) {
            ctx.diag(at, CppModuleEmitterFactory.INTERNAL_CODE, "a lambda captures shared_from_this() in ${owner.name}, but its root class ${root.cls.name} derives no kira::Shared")
        }
        return sharedFromThis(owner)
    }

    /**
     * The captured `self` as the object of `->` inside a lambda that escapes [method] of
     * [owner]: `self` itself in the chain's root, else cast down to [owner]
     * (`std::static_pointer_cast<Sub>(self)`), `const Sub` only in a `const` method: a
     * plain `fx` whose lambda writes a field or calls a method that writes is not one
     * ([CppClassFacts.isConstMethod]), and `self` is then a `shared_ptr<Root>`, not
     * `<const Root>`, so the cast has to say so or the write is refused.
     */
    fun selfReceiver(owner: ClassSymbol, method: FnSymbol): String {
        val root = facts.chain(owner).first().cls
        if (root === owner) {
            return CppClassEmitter.SELF
        }
        val constant = if (facts.isConstMethod(method)) "const " else ""
        return "std::static_pointer_cast<$constant${ctx.speller.bareClass(owner.selfType)}>(${CppClassEmitter.SELF})"
    }

    /** `shared_from_this()`, qualified in a class template, where the `kira::Shared` base may be dependent and unqualified lookup never looks. */
    private fun sharedFromThis(owner: ClassSymbol): String = if (owner.typeParams.isNotEmpty()) "this->shared_from_this()" else "shared_from_this()"

    // ---- construction ------------------------------------------------------------------------------------------

    /**
     * `C { ... }` for a class (R9): `std::make_shared<C>(...)` with one argument per field of
     * the chain in constructor order ([ResolvedInit.fields]). The trailing fields left to
     * their defaults are left to the C++ default arguments; a skipped earlier one is filled
     * in with its default (or `T{}` for a field without one, D38). `Ref<T> { value = v }` is
     * `std::make_shared<kira::Box<T>>(v)` (D46), and a system module's class is its
     * runtime's (`kira::sync::Thread`).
     *
     * D33: when two or more arguments call something impure, the calls run left to right as
     * written, into typed temporaries of an immediately invoked lambda (R19), since C++
     * leaves the order of function arguments unspecified.
     */
    fun construct(e: ObjectInitExpr): String {
        val init = model.init(e)
        val t = init?.type as? KType.Nominal
        val cls = init?.cls
        if (init == null || t == null || cls == null) {
            ctx.diag(e, CppModuleEmitterFactory.INTERNAL_CODE, "no construction was recorded for ${CppEmitContextImpl.describe(e)}")
            return "/* construction */"
        }
        val target = when {
            facts.isKiraClass(cls) -> {
                facts.missingImplementation(cls)?.let { (owner, method) ->
                    ctx.unsupported(e, "constructing ${cls.name}, which leaves ${owner.name}.${method.name} without a body (C++ holds the class abstract; a method implemented at instantiation is deferred, D43)")
                    return "/* ${cls.name} */"
                }
                ctx.speller.bareClass(t)
            }
            cls.kind == ClassKind.MAGIC && cls.name == REF -> "kira::Box<${ctx.spell(t.typeArgs().firstOrNull() ?: KType.Error, Pos.TEMPLATE_ARG, e)}>"
            ctx.speller.isSystemClass(cls) -> ctx.speller.bareClass(t)
            else -> {
                ctx.unsupported(e, "the construction of ${t.display()} as a class")
                return "/* ${t.display()} */"
            }
        }
        return construction(e, init, target)
    }

    private fun construction(e: ObjectInitExpr, init: ResolvedInit, target: String): String {
        val fields = init.fields
        // No object holds a view (decision 4b): ViewPass refuses the field's type (rules.view.type) before any emitter runs.
        fields.firstOrNull { holdsSecondClass(it.field.type.substitute(init.substitution)) }?.let { f ->
            ctx.diag(
                (f as? FieldInit.Given)?.expr ?: e,
                CppModuleEmitterFactory.INTERNAL_CODE,
                "the ${f.field.name} of ${init.type.display()} holds a view or a pointer, which reached the emitter although no field may hold one (rules.view.type)",
            )
        }
        var end = fields.size
        while (end > 0 && fields[end - 1] is FieldInit.Default && fields[end - 1].field.default != null) {
            end -= 1
        }
        val types = fields.map { it.field.type.substitute(init.substitution) }
        val texts = arrayOfNulls<String>(end)
        val impure = (0 until end).filter { i -> (fields[i] as? FieldInit.Given)?.let { facts.callsImpurely(it.expr) } == true }
        val spilled = mutableListOf<String>()
        if (impure.size >= 2) {
            init.sourceOrder.filter { it in impure }.forEach { i ->
                // `t0_Arg_`, never the `t0_` of a spilled call around the construction ([bodyName]).
                val temp = bodyName(ctx.fresh("t"), "Arg")
                // The field's own column: a `mut` field's Unsafe<X> is the `X*` its constructor parameter takes, not `const X*`.
                val column = if (fields[i].field.isMut) Pos.MUT_VALUE else Pos.FIELD
                spilled += "${constLocal(ctx.spell(types[i], column, e))} $temp = ${argument((fields[i] as FieldInit.Given).expr)};"
                texts[i] = temp
            }
        }
        var refused = false
        for (i in 0 until end) {
            if (texts[i] != null) {
                continue
            }
            texts[i] = when (val f = fields[i]) {
                is FieldInit.Given -> argument(f.expr)
                is FieldInit.Default -> f.field.default?.let { skippedDefault(it, types[i], e) } ?: run {
                    if (!hasEmptyValue(types[i])) {
                        // Every such field is named, then nothing is lowered.
                        refuseSkipped(e, init, f.field, types[i])
                        refused = true
                    }
                    valueInitialized(ctx.spell(types[i], if (f.field.isMut) Pos.MUT_VALUE else Pos.FIELD, e))
                }
            }
        }
        if (refused) {
            return "/* ${init.cls?.name ?: "construction"} */"
        }
        val call = "std::make_shared<$target>(${texts.joinToString(", ")})"
        if (spilled.isEmpty()) {
            return call
        }
        return "[&]() -> ${ctx.spell(init.type, Pos.RETURN, e)} { ${spilled.joinToString(" ")} return $call; }()"
    }

    /**
     * A construction argument. `make_shared` forwards it to the constructor through a
     * deduced template parameter, so an integer literal of a width C++ gives no literal of
     * its own is spelled `T{lit}` (R2), and MSVC `/W4 /WX` never sees an `int` narrowed
     * inside the standard library (C4244).
     */
    private fun argument(e: Expr): String = typedLiteral(e, ctx.expr(e), model.typeOrNull(e)?.prim)

    /**
     * A skipped middle default, filled in as an argument. The declaration emitter's text is
     * what the typed constructor parameter takes as a default argument, where the type is
     * known: a bare braced list for an `Arr` literal (`{1, 2, 3, 4}`), a bare integer
     * literal. Through `make_shared`'s forwarding parameter neither says its type, and a
     * braced list deduces nothing at all (no compiler takes it, measured: gcc 13, clang 20,
     * MSVC 14.44), so the list gets its type in front (`std::array<std::uint8_t, 4>{1, 2, 3,
     * 4}`, as a given literal is spelled) and a literal goes through [typedLiteral] as a
     * given argument does. Everything else the declaration emitter spells is typed already
     * (`"x"`, `1.5f`, `Kind::OK`, `kira::none`, `kira::List<T>{}`, a constant's name).
     */
    private fun skippedDefault(default: Expr, type: KType, at: ASTNode): String {
        val text = decls.initText(default, type)
        if (text.startsWith("{")) {
            return "${ctx.spell(type, Pos.VALUE, at)}$text"
        }
        return typedLiteral(default, text, type.prim)
    }

    /**
     * Whether a value-initialized [t] (D38's `T{}`) is a value Kira has: a scalar, a `Str`, an
     * enum, an empty container or `Maybe`, an empty `Weak` or view, a null pointer of the FFI's
     * `Unsafe`, `CStr` and `@_opaque` handles, and a struct or tuple or `Arr` of those. A class,
     * trait, `Ref` or system class is a null `kira::Rc` and an `Fx` an empty `kira::Fn`, though
     * the Kira type is not nullable (`Maybe` is): the first member access segfaults, the first
     * call throws `std::bad_function_call` (measured on g++ and zig c++).
     */
    private fun hasEmptyValue(t: KType, seen: MutableSet<TypeSymbol> = Collections.newSetFromMap(IdentityHashMap())): Boolean = when (t) {
        is KType.Scalar, KType.Str -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> true
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS -> false
                ClassKind.OPAQUE -> true
                ClassKind.STRUCT -> !seen.add(sym) || sym.typeParams.zip(t.typeArgs()).toMap().let { sub ->
                    sym.fields.all { f -> f.default != null || hasEmptyValue(f.type.substitute(sub), seen) }
                }
                ClassKind.MAGIC -> when {
                    ctx.speller.isSystemClass(sym) || sym.name == REF || sym.name == "Result" -> false
                    sym.name == "Arr" || sym.name.startsWith("Tuple") -> t.typeArgs().all { hasEmptyValue(it, seen) }
                    else -> true
                }
            }
            else -> false
        }
        else -> false
    }

    /** A construction that skips [field], which has no default, where its [type] has no empty value ([hasEmptyValue]). */
    private fun refuseSkipped(e: ObjectInitExpr, init: ResolvedInit, field: FieldSymbol, type: KType) {
        val cls = init.cls?.name ?: "the object"
        val maybe = if (type is KType.Nominal && (type.sym is ClassSymbol || type.sym is TraitSymbol)) ", or make it Maybe<${type.display()}>" else ""
        ctx.diag(
            e,
            CppModuleEmitterFactory.UNSUPPORTED_CODE,
            "constructing $cls leaves the field ${field.name}: ${type.display()} without a value, and ${type.display()} has no empty value in C++ " +
                "(it would be a null handle or an empty function, which the first use dereferences): give ${field.name} a value here, " +
                "declare it with a default$maybe",
        )
    }

    /**
     * A value-initialized [type] as an argument (D38): `T{}`, or for a pointer spelling
     * `static_cast<const std::int32_t*>(nullptr)`, since `const std::int32_t*{}` and
     * `Handle*{}` are no expression (a functional cast takes one simple type name).
     */
    private fun valueInitialized(type: String): String = if (type.endsWith("*")) "static_cast<$type>(nullptr)" else "$type{}"

    /** [text], the C++ of [e], as `T{text}` when [e] is an integer literal and [prim] narrower than `int`. */
    private fun typedLiteral(e: Expr, text: String, prim: Prim?): String {
        val literal = e is IntegerLiteral || (e is UnaryExpr && e.operator == UnaryOp.NEG && e.operand is IntegerLiteral)
        if (!literal || prim == null || !prim.isInteger || prim == Prim.INT32 || prim == Prim.UINT32) {
            return text
        }
        val scalar = ctx.speller.scalarName(prim)
        if (text.startsWith("$scalar{") || text.startsWith("static_cast") || text.startsWith("std::numeric_limits")) {
            return text
        }
        return "$scalar{$text}"
    }

    companion object {
        /**
         * [type] as the type of a `const` local: `const std::int32_t`, `const kira::Str`, and
         * for a pointer the pointer itself const, `const std::int32_t* const`, `Handle* const`,
         * `const char* const`. A `const` in front of a pointer spelling would say
         * `const const std::int32_t*` for an `Unsafe<Int32>` (gcc "duplicate 'const'", clang
         * -Wduplicate-decl-specifier, MSVC C4114) and `const Handle*` for an opaque handle,
         * which no function taking the handle accepts (measured).
         */
        fun constLocal(type: String): String = if (type.endsWith("*")) "$type const" else "const $type"

        /** The second-class types (decision 4b, 30-second-class.md 1.1): each points into storage it does not own. */
        private val SECOND_CLASS = setOf("View", "MutView", "CStr", "Unsafe")

        /**
         * Whether a value of type [t] is, or holds by value, a second-class type: one itself, a
         * type argument of a container, `Maybe`, `Ref` or generic class that is one, or a struct
         * field that holds one. An `Fx` signature holds none (it receives or returns a view, 1.1).
         */
        fun holdsSecondClass(t: KType, seen: MutableSet<TypeSymbol> = Collections.newSetFromMap(IdentityHashMap())): Boolean {
            val n = t as? KType.Nominal ?: return false
            val sym = n.sym as? ClassSymbol ?: return false
            return when (sym.kind) {
                ClassKind.MAGIC -> sym.name in SECOND_CLASS || (sym.name != "Fx" && n.typeArgs().any { holdsSecondClass(it, seen) })
                ClassKind.STRUCT -> seen.add(sym) && sym.typeParams.zip(n.typeArgs()).toMap().let { sub -> sym.fields.any { holdsSecondClass(it.type.substitute(sub), seen) } }
                ClassKind.CLASS -> n.typeArgs().any { holdsSecondClass(it, seen) }
                else -> false
            }
        }

        /**
         * A name the classes part declares inside a body, [base] then [mark] then `_`: a
         * copied parameter's reference (`vRef_`) and a construction's D33 temporaries
         * (`t0_Arg_`). [mark] starts with an uppercase letter, which no name the statement
         * part or [CppNames.fresh] synthesizes has (both lowercase every stem), and the name
         * has an underscore beside a lowercase letter, which no Kira name has (the lexer
         * allows an underscore only in UPPER_SNAKE_CASE). The statement part draws its
         * temporaries from a pool of its own that never sees the context's [CppNames], so a
         * lowercase name of ours could be one of its names: a construction's `t0_` inside a
         * spilled call's `t0_` shadowed it, as did a reference `t0_` for a parameter `t0`
         * (g++ -Werror=shadow, measured). A name of this shape can neither shadow nor be
         * shadowed by anything the body spells around it, and [base] (a parameter's C++
         * name, or a name [CppNames.fresh] never repeats) keeps two of them apart.
         */
        fun bodyName(base: String, mark: String): String {
            require(mark.firstOrNull()?.isUpperCase() == true && mark.drop(1).all { it.isLowerCase() }) { "a body name's mark is Capitalized: '$mark'" }
            return "$base${mark}_"
        }

        /** A class member's indent inside `public:` / `private:`, and a mem-initializer's under its constructor. */
        const val MEMBER_INDENT = "    "
        const val ANONYMOUS = DeclarationCollector.ANONYMOUS
        const val REF = "Ref"
    }
}

/**
 * What the class lowering needs to know about the whole program, computed once per typed
 * program: where each `this` stands, which lambdas escape, whose `this` escapes (and so which
 * root classes derive `kira::Shared`), which traits a diamond makes virtual bases, and which
 * fields some body names.
 *
 * A lambda escapes unless it is written directly as the argument of a non-escaping `Fx`
 * parameter (EscapePass's `fxEscapes`, where an absent entry means escaping): the rule
 * the closure lowering applies to choose `[this]` over `[self = shared_from_this()]`
 * (design 5.6). A class's `this` escapes when [ClassSymbol.thisEscapes] says so (EscapePass,
 * or the closure lowering), when a method uses `this` as a value, or when a lambda that
 * escapes a method captures the receiver; the scan marks [ClassSymbol.thisEscapes] for the
 * last two, so the class head (written before any body) and the bodies agree.
 */
class CppClassFacts(private val program: TypedProgram) {
    private val model = program.model

    /** Where one `this` stands: its owner, its method (null in `initially`/`finally`), and whether an escaping lambda encloses it. */
    data class ThisSite(val owner: TypeSymbol, val fn: FnSymbol?, val inEscapingLambda: Boolean, val asValue: Boolean)

    /** One class of a superclass chain as a subclass sees it: [type] and [substitution] carry its type arguments. */
    data class Link(val cls: ClassSymbol, val type: KType.Nominal, val substitution: Map<TypeParamSymbol, KType>)

    private val sites = IdentityHashMap<ThisExpr, ThisSite>()
    private val nonEscaping: MutableSet<LambdaExpr> = Collections.newSetFromMap(IdentityHashMap())
    private val referenced: MutableSet<FieldSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val escapes: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val sharedRoots: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val virtualBases: MutableSet<TraitSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val initializerUses = IdentityHashMap<ClassSymbol, MutableList<ASTNode>>()
    private val traitUses = IdentityHashMap<TraitSymbol, MutableList<ASTNode>>()
    private val nonConst: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val traitWriters: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val familyParent = IdentityHashMap<FnSymbol, FnSymbol>()
    private val thisCalls = IdentityHashMap<FnSymbol, MutableList<FnSymbol>>()
    private val initializerCalls = IdentityHashMap<ClassSymbol, MutableList<Pair<FunctionCallExpr, FnSymbol>>>()
    private val pathMemo = IdentityHashMap<Symbol, Map<TraitSymbol, Int>>()
    private val pathVisiting: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
    private val dominanceMemo = IdentityHashMap<TypeSymbol, Pair<List<Forwarder>, List<Pair<FnSymbol, List<KType.Nominal>>>>>()
    private val dominanceVisiting: MutableSet<TypeSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val structMemo = IdentityHashMap<ClassSymbol, Pair<List<InheritedDefault>, List<Pair<FnSymbol, List<KType.Nominal>>>>>()

    /** Every class, struct and trait the program defines itself (no `@_magic`, `@_opaque` or `@_extern` one). */
    private val types: List<Symbol> = program.modules.flatMap { it.declarations }.filter { sym ->
        when (sym) {
            is ClassSymbol -> (sym.kind == ClassKind.CLASS || sym.kind == ClassKind.STRUCT) && sym.foreign == null
            is TraitSymbol -> sym.foreign == null
            else -> false
        }
    }

    /** Where a reference the lowering keeps may meet code that overwrites or frees what it refers to ([CppClassLifetimes]). */
    val lifetimes: CppClassLifetimes = CppClassLifetimes(program)

    init {
        collectNonEscapingLambdas()
        types.forEach { scanBodies(it) }
        types.forEach { t ->
            if (t is ClassSymbol && t.kind == ClassKind.CLASS && (t.thisEscapes || t in escapes)) {
                t.thisEscapes = true
                sharedRoots.add(chain(t).first().cls)
            }
            // A method that holds itself for the call (weak_from_this) needs the one kira::Shared base too.
            if (t is ClassSymbol && t.kind == ClassKind.CLASS && t.methods.any { m -> m.body != null && lifetimes.guard(m).holdsThis }) {
                sharedRoots.add(chain(t).first().cls)
            }
        }
        findDiamonds()
        deriveConstness()
    }

    // ---- queries ---------------------------------------------------------------------------------------

    fun site(e: ThisExpr): ThisSite? = sites[e]

    /**
     * Whether the class or trait method is `const` in C++: not a `mut fx`, and neither it nor
     * any method of its override family (what it overrides, what overrides it, transitively)
     * writes the receiver. The typer lets any class method write a `mut` field of the class,
     * pass one as a `mut` argument, and call a `mut fx` on itself or on a struct or container
     * held by value (`thisMutable` holds in every class method, D29), so the modifier alone
     * cannot decide `const`; a `const` method that did any of that compiles nowhere. A family
     * is decided together because an override cannot differ from its base in `const`.
     */
    fun isConstMethod(fn: FnSymbol): Boolean = fn !in nonConst

    /** One call in `initially` or `finally` that C++ would dispatch differently from the spec's init block. */
    data class InitializerDispatch(val site: FunctionCallExpr, val callee: FnSymbol, val overridden: FnSymbol, val overrider: ClassSymbol)

    /**
     * The calls in [c]'s `initially` or `finally` that reach, directly or through [c]'s own
     * methods called on `this`, a method some subclass of [c] overrides: a C++ constructor or
     * destructor runs [c]'s version, where the spec (Kotlin's init block) would run the
     * override. One entry per call site, for the first such method it reaches.
     */
    fun initializerDispatches(c: ClassSymbol): List<InitializerDispatch> {
        val out = mutableListOf<InitializerDispatch>()
        for ((site, callee) in initializerCalls[c].orEmpty()) {
            val seen: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
            val queue = ArrayDeque(listOf(callee))
            while (queue.isNotEmpty()) {
                val m = queue.removeFirst()
                if (!seen.add(m)) {
                    continue
                }
                val overrider = overriderBelow(c, m)
                if (overrider != null) {
                    out += InitializerDispatch(site, callee, m, overrider)
                    break
                }
                thisCalls[m].orEmpty().forEach { queue.add(it) }
            }
        }
        return out
    }

    /** A class strictly below [c] in the program whose own method overrides [m] (directly or through further overrides). */
    private fun overriderBelow(c: ClassSymbol, m: FnSymbol): ClassSymbol? = types.firstOrNull { d ->
        d is ClassSymbol && d !== c && d.kind == ClassKind.CLASS && chain(d).any { it.cls === c } && d.methods.any { g -> overrides(g, m) }
    } as ClassSymbol?

    private fun overrides(g: FnSymbol, m: FnSymbol): Boolean {
        var f = g.overrides
        val seen: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
        while (f != null && seen.add(f)) {
            if (f === m) {
                return true
            }
            f = f.overrides
        }
        return false
    }

    /** Whether some body names the field (reads or writes it); a mem-initializer does not count. */
    fun isReferenced(f: FieldSymbol): Boolean = f in referenced

    /**
     * Whether [c] derives `kira::Shared<C>`: it is the root of a chain in which some class's
     * `this` escapes, or some method holds itself for its call ([CppClassLifetimes.Guard.holdsThis]).
     */
    fun derivesShared(c: ClassSymbol): Boolean = c in sharedRoots

    /** Whether the trait is reached twice by some class or trait of the program, so every derivation from it is `virtual`. */
    fun isVirtualBase(t: TypeSymbol): Boolean = t in virtualBases

    /** A class this program defines, which C++ can derive from and construct: not magic, opaque, extern or a struct. */
    fun isKiraClass(t: TypeSymbol): Boolean = t is ClassSymbol && t.kind == ClassKind.CLASS && t.foreign == null

    /** `this` used as a value, or captured by a lambda that escapes, in [c]'s `initially` or `finally`. */
    fun initializerThisUses(c: ClassSymbol): List<ASTNode> = initializerUses[c].orEmpty()

    /** Escaping lambdas that capture `this` in [t]'s default bodies. */
    fun traitThisUses(t: TraitSymbol): List<ASTNode> = traitUses[t].orEmpty()

    /** [c]'s superclass chain, root first and [c] last, each link under [c]'s view of its type arguments. */
    fun chain(c: ClassSymbol): List<Link> {
        val out = mutableListOf(Link(c, c.selfType, emptyMap()))
        val seen: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())
        seen.add(c)
        var outer: Map<TypeParamSymbol, KType> = emptyMap()
        var next = c.superclass
        while (next != null) {
            val sc = next.sym as? ClassSymbol ?: break
            if (!isKiraClass(sc) || !seen.add(sc)) {
                break
            }
            val type = next.substitute(outer) as KType.Nominal
            val sub = sc.typeParams.zip(type.typeArgs()).toMap()
            out.add(Link(sc, type, sub))
            outer = sub
            next = sc.superclass
        }
        return out.reversed()
    }

    /** Some base of [c] (a trait, or a superclass that has one) already has a virtual destructor. */
    fun baseHasVirtualDestructor(c: ClassSymbol): Boolean {
        if (c.traits.isNotEmpty()) {
            return true
        }
        val sup = c.superclass?.sym as? ClassSymbol ?: return false
        return isKiraClass(sup) && sup !== c && hasVirtualDestructor(sup)
    }

    /**
     * [c] declares `virtual ~C()`: it has virtual methods and no base gave it a virtual
     * destructor (`-Wnon-virtual-dtor`, the goldens' convention). A base without virtual
     * methods stays non-polymorphic: every Kira reference to a subclass comes from
     * `make_shared<Sub>`, whose control block destroys the object as a `Sub`.
     */
    fun ownsVirtualDestructor(c: ClassSymbol): Boolean = !baseHasVirtualDestructor(c) && c.methods.any { it.isVirtual }

    private fun hasVirtualDestructor(c: ClassSymbol): Boolean = baseHasVirtualDestructor(c) || ownsVirtualDestructor(c)

    /**
     * The first method [c] leaves without a body, with the type that declares it; null when
     * C++ can construct [c]. For each method name over [c]'s chain and traits, the nearest
     * declarations on each path up from [c] itself ([overriders]: its own, a forwarder's
     * target, a superclass's, a trait's), less those another candidate overrides; a
     * bodyless one among them is missing, unless it is a root requirement some other
     * candidate's body satisfies (`class C: Abs, Def`, whose forwarder ties them; a
     * superclass method beside an unrelated trait's requirement, which
     * [unconnectedTraitMethods] refuses). A body that some nearer declaration re-abstracts
     * satisfies nothing: C++ holds the class abstract.
     */
    fun missingImplementation(c: ClassSymbol): Pair<TypeSymbol, FnSymbol>? {
        val names = LinkedHashSet<String>()
        for (link in chain(c)) {
            traitClosure(link.cls.traits).forEach { t -> t.methods.forEach { names.add(it.name) } }
            link.cls.methods.forEach { names.add(it.name) }
        }
        for (name in names) {
            val found = overriders(c.selfType, name)
            val remaining = found.filter { (fn, _) -> found.none { (o, _) -> o !== fn && isBaseOf(fn.owner, o.owner) } }
            val satisfied = remaining.any { (fn, _) -> fn.body != null }
            remaining.firstOrNull { (fn, _) -> fn.body == null && !(satisfied && isRootRequirement(fn)) }?.let { (fn, via) ->
                return (fn.owner ?: via.sym) to fn
            }
        }
        return null
    }

    /**
     * Trait methods [c] meets only through a superclass method of the same name that does not
     * implement them: `class B: A, T` where `A.m` is A's own and T declares `m`. Kira takes
     * `A.m` for `T.m`; C++ leaves `T::m` pure in B. Each is (trait, trait method, the class
     * whose method Kira would use).
     */
    fun unconnectedTraitMethods(c: ClassSymbol): List<Triple<TraitSymbol, FnSymbol, ClassSymbol>> {
        val out = mutableListOf<Triple<TraitSymbol, FnSymbol, ClassSymbol>>()
        val ancestors = chain(c).dropLast(1)
        for (t in traitClosure(c.traits)) {
            for (m in t.methods) {
                if (m.body != null || c.methods.any { it.name == m.name }) {
                    continue
                }
                val via = ancestors.lastOrNull { link -> link.cls.methods.any { it.name == m.name && it.body != null } } ?: continue
                if (t !in traitClosure(via.cls.traits) && ancestors.none { it.cls !== via.cls && t in traitClosure(it.cls.traits) }) {
                    out += Triple(t, m, via.cls)
                }
            }
        }
        return out
    }

    /** Whether [e] runs an impure call or construction itself (not inside a lambda it creates): D33's operand test. */
    fun callsImpurely(e: Expr): Boolean {
        if (model.effect(e) != Effect.IMPURE) {
            return false
        }
        var found = false
        fun go(node: ASTNode) {
            if (found || node is LambdaExpr) {
                return
            }
            if (node is FunctionCallExpr || node is ObjectInitExpr) {
                found = true
                return
            }
            AstTree.children(node).forEach(::go)
        }
        go(e)
        return found
    }

    // ---- the scans -------------------------------------------------------------------------------------

    private fun traitClosure(roots: List<KType.Nominal>): List<TraitSymbol> {
        val out = mutableListOf<TraitSymbol>()
        val seen: MutableSet<TraitSymbol> = Collections.newSetFromMap(IdentityHashMap())
        fun visit(t: TraitSymbol) {
            if (!seen.add(t)) {
                return
            }
            out.add(t)
            t.parents.forEach { p -> (p.sym as? TraitSymbol)?.let(::visit) }
        }
        roots.forEach { r -> (r.sym as? TraitSymbol)?.let(::visit) }
        return out
    }

    /** Every lambda written directly as the argument of a non-escaping `Fx` parameter, anywhere in the program. */
    private fun collectNonEscapingLambdas() {
        for (m in program.modules) {
            val ast = runCatching { m.source.ast }.getOrNull() ?: continue
            AstTree.walk(ast) { node ->
                val call = (node as? FunctionCallExpr)?.let { model.call(it) } ?: return@walk
                val fn = call.fn ?: return@walk
                if (call.kind == CallKind.FN_VALUE) {
                    return@walk
                }
                call.args.forEachIndexed { i, arg ->
                    val lambda = (arg as? ArgBinding.Given)?.expr as? LambdaExpr ?: return@forEachIndexed
                    val p = fn.params.getOrNull(i) ?: return@forEachIndexed
                    if (p.type is KType.Fn && !p.byRef && !model.fxEscapes(p)) {
                        nonEscaping.add(lambda)
                    }
                }
            }
        }
    }

    private fun scanBodies(t: Symbol) {
        when (t) {
            is ClassSymbol -> {
                t.methods.forEach { fn -> fn.body?.forEach { scan(it, t, fn, false, null) } }
                t.initially?.forEach { scan(it, t, null, false, null) }
                t.finally?.forEach { scan(it, t, null, false, null) }
            }
            is TraitSymbol -> t.methods.forEach { fn -> fn.body?.forEach { scan(it, t, fn, false, null) } }
            else -> {}
        }
    }

    private fun scan(node: ASTNode, owner: TypeSymbol, fn: FnSymbol?, inEscaping: Boolean, parent: ASTNode?) {
        var below = inEscaping
        when (node) {
            is ThisExpr -> {
                val asValue = !(parent is MemberAccessExpr && parent.origin === node)
                sites[node] = ThisSite(owner, fn, inEscaping, asValue)
                if (asValue) {
                    thisEscapes(owner, fn, node, lambda = false)
                }
            }
            is LambdaExpr -> {
                val escaping = node !in nonEscaping
                if (escaping && model.captures(node).orEmpty().any { it is Capture.This }) {
                    thisEscapes(owner, fn, node, lambda = true)
                }
                below = inEscaping || escaping
            }
            is MemberAccessExpr -> (model.member(node) as? MemberRef.Field)?.let { referenced.add(it.field) }
            is Identifier -> if (node !is IntrinsicExpr) {
                (model.symbolOf(node) as? FieldSymbol)?.let { referenced.add(it) }
            }
            is FunctionCallExpr -> model.call(node)?.let { call ->
                val callee = call.fn
                if (callee?.owner != null && (call.implicitThis || call.receiver is ThisExpr)) {
                    when {
                        fn != null -> thisCalls.getOrPut(fn) { mutableListOf() }.add(callee)
                        owner is ClassSymbol -> initializerCalls.getOrPut(owner) { mutableListOf() }.add(node to callee)
                    }
                }
            }
            else -> {}
        }
        val skipped: ASTNode? = when (node) {
            // A member name after `.` and a named argument's name refer to nothing on their own.
            is MemberAccessExpr -> node.member.takeIf { it is Identifier && it !is IntrinsicExpr }
            is FunctionCallNamedParameterExpr -> node.name
            else -> null
        }
        AstTree.children(node).forEach { child ->
            if (child !== skipped) {
                scan(child, owner, fn, below, node)
            }
        }
    }

    private fun thisEscapes(owner: TypeSymbol, fn: FnSymbol?, node: ASTNode, lambda: Boolean) {
        when {
            owner is ClassSymbol && owner.kind == ClassKind.CLASS ->
                if (fn == null) initializerUses.getOrPut(owner) { mutableListOf() }.add(node) else escapes.add(owner)
            // A trait's `this` as a value is refused where it is spelled (thisValue); a lambda here.
            owner is TraitSymbol && lambda -> traitUses.getOrPut(owner) { mutableListOf() }.add(node)
            else -> {}
        }
    }

    /** The C++ bases of a class (a Kira superclass, then its traits) or a trait (its parents); a struct has none. */
    private fun directBases(t: Symbol): List<KType.Nominal> = when {
        t is ClassSymbol && !t.isStruct -> listOfNotNull(t.superclass?.takeIf { isKiraClass(it.sym) }) + t.traits.filter { it.sym is TraitSymbol }
        t is TraitSymbol -> t.parents.filter { it.sym is TraitSymbol }
        else -> emptyList()
    }

    /** How many derivation paths lead from [t] to each trait above it. */
    private fun paths(t: Symbol): Map<TraitSymbol, Int> {
        pathMemo[t]?.let { return it }
        if (!pathVisiting.add(t)) {
            return emptyMap()
        }
        val out = IdentityHashMap<TraitSymbol, Int>()
        directBases(t).forEach { n ->
            val b = n.sym as Symbol
            if (b is TraitSymbol) {
                out[b] = (out[b] ?: 0) + 1
            }
            paths(b).forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
        }
        pathVisiting.remove(t)
        pathMemo[t] = out
        return out
    }

    /** A trait reached by two derivation paths from one class or trait is a virtual base everywhere it is derived. */
    private fun findDiamonds() {
        types.forEach { t -> paths(t).forEach { (trait, n) -> if (n >= 2) virtualBases.add(trait) } }
    }

    // ---- const ------------------------------------------------------------------------------------------

    /**
     * Which class and trait methods are not `const` ([isConstMethod]): every `mut fx`, then,
     * to a fixed point, every method whose body writes the receiver (a write, a `mut`
     * argument, or a call to a method already known to write) and every method of a family
     * one of those belongs to. A struct method never takes part: the typer already needs
     * `mut` on it to write, and a struct implements a trait by static dispatch (D1); the
     * trait default bodies it takes as members follow [traitBodyWrites].
     *
     * A family is what C++ ties together: an `override` in a class or trait overrides every
     * same-named virtual of every base, direct or indirect, at once (`class C: A, B` with
     * `id` in both traits; `class Dog: Animal, Tagged` where `Dog.tag` overrides `Animal.tag`
     * and implements `Tagged.tag`), so the families are the connected components of "same
     * name in a base" over every class and trait of the program, and the typer's own single
     * [FnSymbol.overrides] link besides. A family with one member is the method alone.
     */
    private fun deriveConstness() {
        val methods = types.flatMap { t ->
            when {
                t is ClassSymbol && t.kind == ClassKind.CLASS -> t.methods
                t is TraitSymbol -> t.methods
                else -> emptyList()
            }
        }
        methods.filter { it.isMutMethod }.forEach { nonConst.add(it) }
        methods.forEach { m ->
            m.overrides?.let { unite(m, it) }
            val owner = m.owner as? Symbol
            if (owner != null && !m.isOperator && m.name != DeclarationCollector.ANONYMOUS) {
                baseMethods(owner, m.name).forEach { unite(m, it) }
            }
        }
        // A forwarder overrides every base's declaration of its name at once, as an override does.
        types.forEach { t -> forwarders(t as TypeSymbol).forEach { f -> f.overridden.forEach { unite(f.method, it.method) } } }
        val families = IdentityHashMap<FnSymbol, MutableList<FnSymbol>>()
        methods.forEach { m -> families.getOrPut(familyRoot(m)) { mutableListOf() }.add(m) }
        var changed = true
        while (changed) {
            changed = false
            methods.forEach { m ->
                if (m !in nonConst && m.body != null && writesReceiver(m, nonConst)) {
                    nonConst.add(m)
                    changed = true
                }
            }
            families.values.forEach { family ->
                if (family.any { it in nonConst }) {
                    family.forEach { if (nonConst.add(it)) changed = true }
                }
            }
        }
        deriveTraitWrites()
    }

    /** [familyParent] is the union-find over the override families: a method's representative, itself until [unite] joins it. */
    private fun familyRoot(fn: FnSymbol): FnSymbol {
        var f = fn
        while (true) {
            f = familyParent[f] ?: return f
        }
    }

    private fun unite(a: FnSymbol, b: FnSymbol) {
        val ra = familyRoot(a)
        val rb = familyRoot(b)
        if (ra !== rb) {
            familyParent[ra] = rb
        }
    }

    /** Every C++ base of [x], direct and indirect: its Kira superclass chain and every trait any of them derives. */
    private fun allBases(x: Symbol): List<Symbol> {
        val out = mutableListOf<Symbol>()
        val seen: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
        fun visit(s: Symbol) {
            directBases(s).forEach { n ->
                val b = n.sym as? Symbol ?: return@forEach
                if (seen.add(b)) {
                    out.add(b)
                    visit(b)
                }
            }
        }
        visit(x)
        return out
    }

    /** The methods named [name] that the C++ bases of [x] declare: the virtuals an override of [name] in [x] overrides. */
    private fun baseMethods(x: Symbol, name: String): List<FnSymbol> =
        allBases(x).flatMap { b -> methodsOf(b).filter { it.name == name && !it.isOperator } }

    /**
     * The trait methods whose default body writes the receiver: every `mut fx` of a trait,
     * then to a fixed point every default body that writes ([writesReceiver], a call to one
     * of these included). Decided on the trait bodies alone, apart from the class families
     * ([nonConst]): a struct's copy of a default body ([inheritedDefaults]) is `const` by
     * what that body does, not by what some class's override of it does.
     */
    private fun deriveTraitWrites() {
        val methods = types.filterIsInstance<TraitSymbol>().flatMap { it.methods }
        methods.filter { it.isMutMethod }.forEach { traitWriters.add(it) }
        var changed = true
        while (changed) {
            changed = false
            methods.forEach { m ->
                if (m !in traitWriters && m.body != null && writesReceiver(m, traitWriters)) {
                    traitWriters.add(m)
                    changed = true
                }
            }
        }
    }

    /** Whether the default body of the trait method [m] writes its receiver (a `mut fx` counts), so a struct's copy of it cannot be `const`. */
    fun traitBodyWrites(m: FnSymbol): Boolean = m in traitWriters

    /** A trait method with a default body the struct inherits, and the trait type (as the struct names it) that declares it. */
    data class InheritedDefault(val method: FnSymbol, val via: KType.Nominal)

    /**
     * The trait default bodies the struct [s] takes as members (static dispatch, D1): for
     * each method name over the traits [s] implements that [s] declares no method of, the
     * one body that reaches it ([inherited]): the nearest on its path, a sibling trait's
     * beside another's bodyless requirement. A nearer bodyless declaration re-abstracts the
     * name, so a default above it is not inherited; two bodies, or a body beside a
     * re-abstraction, are [ambiguousDefaults].
     */
    fun inheritedDefaults(s: ClassSymbol): List<InheritedDefault> = structResolution(s).first

    /** Method names two of [s]'s trait paths give different bodies to, which [s] does not override: Kira's pick would be silent. */
    fun ambiguousDefaults(s: ClassSymbol): List<Pair<FnSymbol, List<KType.Nominal>>> = structResolution(s).second

    /**
     * The structs of the program that inherit the trait default body [fn] as a member of
     * their own ([inheritedDefaults]), in declaration order. Its body is spelled in each
     * one's class scope as well as its trait's, so a parameter of it that is named as a
     * field of one of them is renamed wherever it is spelled ([CppEmitContextImpl.paramName]:
     * gcc `-Wshadow`, clang and MSVC C4458 refuse the copy otherwise, measured).
     */
    fun structsCopying(fn: FnSymbol): List<ClassSymbol> = types.filter { t ->
        t is ClassSymbol && t.isStruct && inheritedDefaults(t).any { it.method === fn }
    }.map { it as ClassSymbol }

    private fun structResolution(s: ClassSymbol): Pair<List<InheritedDefault>, List<Pair<FnSymbol, List<KType.Nominal>>>> = structMemo.getOrPut(s) {
        val own = s.methods.map { it.name }.toSet()
        val bases = s.traits.filter { it.sym is TraitSymbol }
        val names = traitClosure(bases).flatMap { it.methods }.filter { !it.isOperator && it.name != DeclarationCollector.ANONYMOUS }.map { it.name }.distinct()
        val taken = mutableListOf<InheritedDefault>()
        val ambiguous = mutableListOf<Pair<FnSymbol, List<KType.Nominal>>>()
        names.filter { it !in own }.forEach { name ->
            when (val r = inherited(bases, name)) {
                is Inherited.One -> taken += InheritedDefault(r.method, r.via)
                is Inherited.Ambiguous -> ambiguous += r.method to r.owners
                Inherited.None -> {}
            }
        }
        taken.toList() to ambiguous.toList()
    }

    private fun writesReceiver(m: FnSymbol, known: Set<FnSymbol>): Boolean {
        var found = false
        m.body?.forEach { s ->
            AstTree.walk(s) { node ->
                if (!found && writesReceiverAt(node, known)) {
                    found = true
                }
            }
        }
        return found
    }

    /** An assignment to a place inside the object, or a call that writes one ([callWritesReceiver]). */
    private fun writesReceiverAt(node: ASTNode, known: Set<FnSymbol>): Boolean = when (node) {
        is AssignmentExpr -> insideThis(model.place(node.target))
        is CompoundAssignmentExpr -> insideThis(model.place(node.left))
        is PlaceAssignmentExpr -> insideThis(model.place(node.target))
        is FunctionCallExpr -> callWritesReceiver(node, known)
        else -> false
    }

    /**
     * A call needs a non-`const` `this` when it passes a place inside the object as a `mut`
     * argument; when it is a `mut fx` (or a method [known] to write) on `this` itself; or when
     * it is one on a struct or container the object holds by value, or lends a `MutView` of
     * one. A call on a field that is a reference (a class, a `Ref`) goes through the pointer
     * and leaves `this` alone.
     */
    private fun callWritesReceiver(e: FunctionCallExpr, known: Set<FnSymbol>): Boolean {
        val call = model.call(e) ?: return false
        if (call.args.any { a -> a is ArgBinding.Given && a.byRef && insideThis(model.place(a.expr)) }) {
            return true
        }
        val fn = call.fn ?: return false
        val mutates = fn.isMutMethod || fn in known
        if (call.implicitThis || call.receiver is ThisExpr) {
            return mutates
        }
        val receiver = call.receiver ?: return false
        val place = model.place(receiver) ?: return false
        if (!insideThis(place) || isReferenceType(model.typeOrNull(receiver))) {
            return false
        }
        return mutates || isMutView(call.returnType)
    }

    /**
     * Whether writing [p] writes into the object `this` is: the receiver itself, one of its
     * own fields, and on from there through fields of structs and elements of containers held
     * by value, but never through a reference (a field of a class, a `Ref`, a `MutView`,
     * which a `const` object hands out unchanged).
     */
    private fun insideThis(p: Place?): Boolean = when (p) {
        null -> false
        is Place.This -> true
        is Place.Field -> when {
            p.receiver is Place.This -> true
            p.receiver == null -> false
            isReferenceOwner(p.sym.owner) -> false
            else -> insideThis(p.receiver)
        }
        is Place.Index -> p.kind != IndexKind.MUT_VIEW && p.kind != IndexKind.VIEW && p.kind != IndexKind.STR && insideThis(p.container)
        else -> false
    }

    /** A type whose members are reached through a pointer: a class, an opaque class, a trait, `Ref`, `Weak`, `Unsafe`. */
    private fun isReferenceOwner(sym: Any?): Boolean = when (sym) {
        is TraitSymbol -> true
        is ClassSymbol -> sym.kind == ClassKind.CLASS || sym.kind == ClassKind.OPAQUE || (sym.kind == ClassKind.MAGIC && sym.name in REFERENCE_MAGIC)
        else -> false
    }

    /** As [isReferenceOwner], for a receiver's type; a type parameter counts as a value (a struct instantiation needs the non-`const` `this`). */
    private fun isReferenceType(t: KType?): Boolean = isReferenceOwner((t as? KType.Nominal)?.sym)

    private fun isMutView(t: KType): Boolean {
        val sym = (t as? KType.Nominal)?.sym
        return sym is ClassSymbol && sym.kind == ClassKind.MAGIC && sym.name == "MutView"
    }

    /** A method declaration and the type of its owner as some subtype names it (`Def<Int32>` for `Def<T>.id`), which fixes its type parameters. */
    data class Declared(val method: FnSymbol, val via: KType.Nominal)

    /**
     * A method [via]'s declaration [method] a class or trait inherits from its bases and
     * forwards to, [overridden] being the other declarations of the name the forwarder
     * overrides in C++ (a dominated one, a sibling's pure requirement), each with its
     * owner's type.
     */
    data class Forwarder(val method: FnSymbol, val via: KType.Nominal, val overridden: List<Declared>)

    /**
     * What a class, trait or struct inherits under one method name it does not declare
     * itself, from the nearest declarations of the name on each path up through its bases
     * ([inherited]).
     */
    private sealed interface Inherited {
        /**
         * One body reaches the name: [method], as [via] names its owner. [others] are the
         * other nearest declarations, which that body stands for: one it overrides (its owner
         * is above [method]'s), or a bodyless root requirement of a sibling base; empty when
         * the name comes one way, as most do.
         */
        data class One(val method: FnSymbol, val via: KType.Nominal, val others: List<Declared>) : Inherited

        /** Two bodies, or a body beside a re-abstraction of the name: Kira's pick would be silent, and C++ has no final overrider. */
        data class Ambiguous(val method: FnSymbol, val owners: List<KType.Nominal>) : Inherited

        /** Nothing declares the name, or no body reaches it (every nearest declaration is bodyless). */
        data object None : Inherited
    }

    /**
     * The nearest declarations of [name] on each path up from [bases] ([overriders]), with
     * one that another candidate overrides dropped (its owner is a base of that candidate's
     * owner, C++'s dominance: `class D: B, C` over `B: A`, `C: A` where B overrides `A.m`
     * keeps B's). Of what remains, one body is what the type inherits: a bodyless
     * declaration beside it is a root requirement (`trait Abs { fx f; }` next to
     * `trait Def { fx f { ... } }`, or `trait Abs: Abs0 { override fx f; }` re-declaring
     * Abs0's bodyless `f`), which that body satisfies as the typer takes it, unless it
     * re-abstracts a name some base gave a body (`C: A` redeclaring `A.m` bodyless), a
     * deliberate conflict with the other path's body. Two bodies conflict.
     */
    private fun inherited(bases: List<KType.Nominal>, name: String): Inherited {
        val found = LinkedHashMap<FnSymbol, KType.Nominal>()
        bases.forEach { b -> overriders(b, name).forEach { (fn, via) -> found.putIfAbsent(fn, via) } }
        if (found.isEmpty()) {
            return Inherited.None
        }
        val remaining = found.filterKeys { fn -> found.keys.none { o -> o !== fn && isBaseOf(fn.owner, o.owner) } }
        val bodies = remaining.filterKeys { it.body != null }
        val requirements = remaining.keys.filter { isRootRequirement(it) }
        return when {
            bodies.size >= 2 -> Inherited.Ambiguous(bodies.keys.first(), bodies.values.toList())
            bodies.size == 1 && remaining.size == bodies.size + requirements.size -> {
                val (method, via) = bodies.entries.single()
                Inherited.One(method, via, found.filterKeys { it !== method }.map { (fn, v) -> Declared(fn, v) })
            }
            bodies.size == 1 -> Inherited.Ambiguous(bodies.keys.single(), remaining.values.toList())
            else -> Inherited.None
        }
    }

    /**
     * A bodyless declaration of a name no base of its owner gives a body: a requirement any
     * body of the name satisfies, whether it is the first declaration or re-declares another
     * requirement bodyless. A bodyless declaration over a base's body re-abstracts it.
     */
    private fun isRootRequirement(fn: FnSymbol): Boolean =
        fn.body == null && (fn.owner as? Symbol)?.let { o -> baseMethods(o, fn.name).all { it.body == null } } != false

    /**
     * The declarations of [name] an override in [x] overrides in C++: the nearest on each
     * path up through [x]'s bases ([overriders]), each with its owner's type as [x] names it
     * (`Source<Int32>` for `Source<T>.take` in `Five: Source<Int32>`). Empty when no base
     * declares the name.
     */
    fun overridden(x: TypeSymbol, name: String): List<Declared> {
        val found = LinkedHashMap<FnSymbol, KType.Nominal>()
        directBases(x as Symbol).forEach { b -> overriders(b, name).forEach { (fn, via) -> found.putIfAbsent(fn, via) } }
        return found.map { (fn, via) -> Declared(fn, via) }
    }

    /** Whether [base] is a C++ base of [x], directly or indirectly. */
    private fun isBaseOf(base: TypeSymbol?, x: TypeSymbol?): Boolean =
        base != null && (x as? Symbol)?.let { allBases(it).any { b -> b === base } } == true

    /**
     * The methods [x] inherits from two or more of its bases at once and declares, forwarding
     * to the one body ([inherited]): a trait method that one path to a virtual base overrides
     * and another does not (`class D: B, C` over `B: A`, `C: A`, where B overrides `A.m`; or
     * `class Dog: Animal, Tagged` where Animal and Tagged both reach Named and Animal
     * implements `id`), which C++ takes by dominance but MSVC warns about (C4250, measured, an
     * error under /W4 /WX); and a trait's default beside a sibling trait's bodyless
     * declaration of the same name (`class C: Abs, Def`), two virtuals C++ ties to nothing,
     * so C stays abstract and `c.f()` is ambiguous. Either way [x] declares `m` and forwards
     * it to the body's owner; the forwarder overrides every base's `m` at once, so the
     * [Forwarder.overridden] are one `const` family with it ([deriveConstness]). A
     * superclass's own method beside an unrelated trait's requirement is not forwarded to:
     * [unconnectedTraitMethods] refuses it.
     */
    fun forwarders(x: TypeSymbol): List<Forwarder> = dominance(x).first

    /** Method names [x] reaches with two bodies, or a body beside a re-abstraction, and does not declare: no final overrider. */
    fun ambiguousOverriders(x: TypeSymbol): List<Pair<FnSymbol, List<KType.Nominal>>> = dominance(x).second

    private fun dominance(x: TypeSymbol): Pair<List<Forwarder>, List<Pair<FnSymbol, List<KType.Nominal>>>> {
        dominanceMemo[x]?.let { return it }
        if (!dominanceVisiting.add(x)) {
            return emptyList<Forwarder>() to emptyList()
        }
        val forwarders = mutableListOf<Forwarder>()
        val ambiguous = mutableListOf<Pair<FnSymbol, List<KType.Nominal>>>()
        val own = methodsOf(x as Symbol).map { it.name }.toSet()
        val names = allBases(x).flatMap { methodsOf(it) }.filter { !it.isOperator && it.name != DeclarationCollector.ANONYMOUS }.map { it.name }.distinct()
        names.filter { it !in own }.forEach { name ->
            when (val r = inherited(directBases(x), name)) {
                is Inherited.One -> if (r.others.isNotEmpty() && isForwardable(r)) {
                    forwarders += Forwarder(r.method, r.via, r.others)
                }
                is Inherited.Ambiguous -> ambiguous += r.method to r.owners
                Inherited.None -> {}
            }
        }
        dominanceVisiting.remove(x)
        val out = forwarders.toList() to ambiguous.toList()
        dominanceMemo[x] = out
        return out
    }

    /**
     * A forwarder overrides every other declaration of the name, so each must be a virtual: a
     * trait's, or a class method the program overrides. A superclass's own body beside a
     * trait requirement that superclass does not implement is left to [unconnectedTraitMethods].
     */
    private fun isForwardable(r: Inherited.One): Boolean {
        if (r.others.any { it.method.owner !is TraitSymbol && !it.method.isVirtual }) {
            return false
        }
        val owner = r.method.owner
        return owner is TraitSymbol || r.others.none { it.method.owner is TraitSymbol && !isBaseOf(it.method.owner, owner) }
    }

    private fun methodsOf(t: Symbol): List<FnSymbol> = when (t) {
        is ClassSymbol -> t.methods
        is TraitSymbol -> t.methods
        else -> emptyList()
    }

    /**
     * The nearest declarations of [name] on each path up from [n] (inclusive), each with
     * the type of its owner as [n]'s subtree names it: a body found through a type that
     * forwards to it is paired with the body's own owner (`Def<Int32>` for
     * `Both: Abs, Def<Int32>`), never with the forwarding type, since what its type
     * parameters stand for is what the owner's type says.
     */
    private fun overriders(n: KType.Nominal, name: String): List<Pair<FnSymbol, KType.Nominal>> {
        val sym = n.sym as? Symbol ?: return emptyList()
        methodsOf(sym).firstOrNull { it.name == name }?.let { return listOf(it to n) }
        val sub = (n.sym.typeParams).zip(n.typeArgs()).toMap()
        // A type that forwards the method declares it in C++: below it nothing dominates.
        forwarders(n.sym).firstOrNull { it.method.name == name }?.let { return listOf(it.method to (it.via.substitute(sub) as KType.Nominal)) }
        return directBases(sym).flatMap { b -> overriders(b.substitute(sub) as KType.Nominal, name) }
    }

    companion object {
        /** The `@_magic` classes that are references in C++ (`kira::Rc`, `kira::Weak`, a pointer), as the typer's TypeFacts.isReference names them. */
        private val REFERENCE_MAGIC = setOf("Ref", "Weak", "Unsafe")

        /** The class or trait a class-like type names: itself, or the inner type of a `Maybe`. */
        fun referent(t: KType): TypeSymbol? {
            val n = t as? KType.Nominal ?: return null
            val sym = n.sym
            if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC && sym.name == "Maybe") {
                return n.typeArgs().firstOrNull()?.let { referent(it) }
            }
            return sym
        }
    }
}
