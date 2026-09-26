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
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.ResolvedInit
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
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
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
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
 *   overrides it or a trait declares it; one that overrides says `override`; a non-`mut`
 *   one is `const` (D29: still callable through a `const kira::Rc<C>&`).
 * - Bodies are out of line: in the `.kira.cxx`, or in the header for a template (a generic
 *   class, a generic method, a method with a non-escaping `Fx` parameter) and for a
 *   header-only module.
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
 * derives it in C++ (static dispatch through a generic bound, D1); boxing one into a trait
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

    override fun construct(ctx: CppEmitContextImpl, e: ObjectInitExpr): String = lowering(ctx).construct(e)

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
        facts.unconnectedTraitMethods(c).forEach { (trait, method, via) ->
            ctx.unsupported(
                c.decl ?: return@forEach,
                "${c.name} implementing ${trait.name}.${method.name} through ${via.name}.${method.name}, which C++ does not take as its override (override ${method.name} in ${c.name})",
            )
        }
        refuseAmbiguous(c)
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
            }
        }
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

    private fun returnText(fn: FnSymbol): String {
        val node = (fn.decl as? FunctionDecl)?.def?.returnTypeSpecifier
        return if (node != null && model.typeOf(node) != null) ctx.spell(node, Pos.RETURN) else ctx.spell(fn.ret, Pos.RETURN, fn.decl)
    }

    private fun paramText(fn: FnSymbol, p: ParamSymbol, withDefault: Boolean, markUnused: Boolean): String {
        val name = ctx.paramName(p)
        val unused = if (markUnused && !placement.bodyNames(fn, p)) "[[maybe_unused]] " else ""
        if (isTemplateFx(fn, p)) {
            return "$unused${ctx.speller.templateParamName(p)}&& $name"
        }
        val pos = if (p.byRef) Pos.MUT_PARAM else Pos.PARAM
        val node = (p.decl as? FunctionDeclParameterExpr)?.typeSpecifier
        val type = if (node != null && model.typeOf(node) != null) ctx.spell(node, pos) else ctx.spell(p.type, pos, p.decl ?: fn.decl)
        val default = if (withDefault && p.default != null) " = ${decls.initText(p.default, p.type)}" else ""
        return "$unused$type $name$default"
    }

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
        val params = fn.params.joinToString(", ") { paramText(fn, it, withDefault = true, markUnused = false) }
        val constSuffix = if (fn.isMutMethod) "" else " const"
        val overrideSuffix = if (overrides) " override" else ""
        val pure = if (virtual && fn.body == null) " = 0" else ""
        return templateHead(fn) + "$nodiscard$specifier${returnText(fn)} ${ctx.names.escape(fn.name)}($params)$constSuffix$overrideSuffix$pure;"
    }

    // ---- forwarding overrides (a diamond's dominance, CppClassFacts.forwarders) -----------------------

    private fun forwarderSubstitution(f: CppClassFacts.Forwarder): Map<TypeParamSymbol, KType> =
        f.via.sym.typeParams.zip(f.via.typeArgs()).toMap()

    private fun forwarderParams(f: CppClassFacts.Forwarder, withDefault: Boolean): String {
        val sub = forwarderSubstitution(f)
        return f.method.params.mapIndexed { i, p ->
            val type = p.type.substitute(sub)
            val default = if (withDefault && p.default != null) " = ${decls.initText(p.default, type)}" else ""
            "${ctx.spell(type, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM, f.method.decl)} ${forwardedName(i)}$default"
        }.joinToString(", ")
    }

    /** `a0_`, `a1_`: synthesized, so a forwarder's parameters never shadow a member (`-Wshadow`). */
    private fun forwardedName(i: Int): String = "a${i}_"

    private fun forwarderPrototype(f: CppClassFacts.Forwarder): String {
        val m = f.method
        val ret = m.ret.substitute(forwarderSubstitution(f))
        val nodiscard = if (ret == KType.Void || ret == KType.Never) "" else "[[nodiscard]] "
        val constSuffix = if (m.isMutMethod) "" else " const"
        return "$nodiscard${ctx.spell(ret, Pos.RETURN, m.decl)} ${ctx.names.escape(m.name)}(${forwarderParams(f, withDefault = true)})$constSuffix override;"
    }

    private fun forwarderDefinition(w: CppWriter, owner: TypeSymbol, f: CppClassFacts.Forwarder, inline: Boolean) {
        val m = f.method
        val ret = m.ret.substitute(forwarderSubstitution(f))
        ctx.parts.generics.templateHead(ctx, owner.typeParams)?.let { w.line(it) }
        val constSuffix = if (m.isMutMethod) "" else " const"
        val head = "${inlineSpecifier(owner, inline, template = false)}${ctx.spell(ret, Pos.RETURN, m.decl)} ${qualifier(owner)}${ctx.names.escape(m.name)}(${forwarderParams(f, withDefault = false)})$constSuffix"
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
            is ClassSymbol -> {
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

    private fun methodDefinition(w: CppWriter, owner: TypeSymbol, fn: FnSymbol, inline: Boolean) {
        fn.decl?.let { node -> ctx.lineDirective(node)?.let { w.line(it) } }
        ctx.parts.generics.templateHead(ctx, owner.typeParams)?.let { w.line(it) }
        templateHead(fn).forEach { w.line(it) }
        val params = fn.params.joinToString(", ") { paramText(fn, it, withDefault = false, markUnused = true) }
        val constSuffix = if (fn.isMutMethod) "" else " const"
        val head = "${inlineSpecifier(owner, inline, isTemplate(fn))}${returnText(fn)} ${qualifier(owner)}${ctx.names.escape(fn.name)}($params)$constSuffix"
        w.block(head) {
            ctx.body(fn, fn.body ?: emptyList(), this)
        }
    }

    private fun constructorDefinition(c: ClassSymbol, inline: Boolean): (CppWriter.() -> Unit)? {
        val params = constructorParams(c, withDefaults = false)
        if (params.isEmpty() && c.initially == null) {
            return null
        }
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

    private fun destructorDefinition(c: ClassSymbol, inline: Boolean): (CppWriter.() -> Unit)? {
        val statements = c.finally ?: return null
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
     * (`static_pointer_cast`), and a non-`mut` method sees a `const` object, whose
     * `shared_from_this()` is `shared_ptr<const Root>` (`const_pointer_cast`: a class is a
     * reference, and D29 lets a `mut fx` run through any reference). Inside a lambda that
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
        val constant = !fn.isMutMethod
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
     * in a non-`mut` method), as the closures golden spells it and W2.3's closure part
     * captures it; [thisValue] casts it to the class. In a class template it is
     * `this->shared_from_this()`, since the `kira::Shared` base may be dependent there.
     */
    @Suppress("UNUSED_PARAMETER")
    fun selfCapture(owner: ClassSymbol, method: FnSymbol, at: ASTNode): String {
        val root = facts.chain(owner).first()
        if (!facts.derivesShared(root.cls)) {
            ctx.diag(at, CppModuleEmitterFactory.INTERNAL_CODE, "a lambda captures shared_from_this() in ${owner.name}, but its root class ${root.cls.name} derives no kira::Shared")
        }
        return sharedFromThis(owner)
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
                val temp = ctx.fresh("t")
                spilled += "const ${ctx.spell(types[i], Pos.VALUE, e)} $temp = ${argument((fields[i] as FieldInit.Given).expr)};"
                texts[i] = temp
            }
        }
        for (i in 0 until end) {
            if (texts[i] != null) {
                continue
            }
            texts[i] = when (val f = fields[i]) {
                is FieldInit.Given -> argument(f.expr)
                is FieldInit.Default -> f.field.default?.let { decls.initText(it, types[i]) } ?: "${ctx.spell(types[i], Pos.VALUE, e)}{}"
            }
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
    private fun argument(e: Expr): String {
        val text = ctx.expr(e)
        val literal = e is IntegerLiteral || (e is UnaryExpr && e.operator == UnaryOp.NEG && e.operand is IntegerLiteral)
        val prim = model.typeOrNull(e)?.prim
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
    private val pathMemo = IdentityHashMap<Symbol, Map<TraitSymbol, Int>>()
    private val pathVisiting: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
    private val dominanceMemo = IdentityHashMap<TypeSymbol, Pair<List<Forwarder>, List<Pair<FnSymbol, List<KType.Nominal>>>>>()

    /** Every class, struct and trait the program defines itself (no `@_magic`, `@_opaque` or `@_extern` one). */
    private val types: List<Symbol> = program.modules.flatMap { it.declarations }.filter { sym ->
        when (sym) {
            is ClassSymbol -> (sym.kind == ClassKind.CLASS || sym.kind == ClassKind.STRUCT) && sym.foreign == null
            is TraitSymbol -> sym.foreign == null
            else -> false
        }
    }

    init {
        collectNonEscapingLambdas()
        types.forEach { scanBodies(it) }
        types.forEach { t ->
            if (t is ClassSymbol && t.kind == ClassKind.CLASS && (t.thisEscapes || t in escapes)) {
                t.thisEscapes = true
                sharedRoots.add(chain(t).first().cls)
            }
        }
        findDiamonds()
    }

    // ---- queries ---------------------------------------------------------------------------------------

    fun site(e: ThisExpr): ThisSite? = sites[e]

    /** Whether some body names the field (reads or writes it); a mem-initializer does not count. */
    fun isReferenced(f: FieldSymbol): Boolean = f in referenced

    /** Whether [c] derives `kira::Shared<C>`: it is the root of a chain in which some class's `this` escapes. */
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
     * The first method [c] leaves without a body (its own bodyless one, a superclass's, or a
     * trait method no class of the chain implements and no trait gives a default), with the
     * type that declares it; null when C++ can construct [c].
     */
    fun missingImplementation(c: ClassSymbol): Pair<TypeSymbol, FnSymbol>? {
        val implemented = HashSet<String>()
        val required = LinkedHashMap<String, Pair<TypeSymbol, FnSymbol>>()
        for (link in chain(c)) {
            for (t in traitClosure(link.cls.traits)) {
                t.methods.forEach { m ->
                    if (m.body != null) implemented.add(m.name) else required.putIfAbsent(m.name, t to m)
                }
            }
            link.cls.methods.forEach { m ->
                if (m.body != null) implemented.add(m.name) else required.putIfAbsent(m.name, link.cls to m)
            }
        }
        return required.entries.firstOrNull { it.key !in implemented }?.value
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

    /** A method [via]'s declaration [method] a class or trait inherits by dominance, and forwards to. */
    data class Forwarder(val method: FnSymbol, val via: KType.Nominal)

    /**
     * The methods [x] inherits by dominance in a diamond: a trait method that one path to the
     * virtual base overrides and another does not (`class D: B, C` over `B: A`, `C: A`, where
     * B overrides `A.m`; or `class Dog: Animal, Tagged` where Animal and Tagged both reach
     * Named and Animal implements `id`). C++ takes B's `m`, but MSVC warns C4250 ("inherits
     * via dominance", measured, an error under /W4 /WX), so [x] declares `m` and forwards it
     * to B's. Only a declaration with a body is forwarded to; a pure one leaves [x] to
     * implement it.
     */
    fun forwarders(x: TypeSymbol): List<Forwarder> = dominance(x).first

    /** Trait methods [x] reaches with two different overriders and does not override: C++ has no final overrider for them. */
    fun ambiguousOverriders(x: TypeSymbol): List<Pair<FnSymbol, List<KType.Nominal>>> = dominance(x).second

    private fun dominance(x: TypeSymbol): Pair<List<Forwarder>, List<Pair<FnSymbol, List<KType.Nominal>>>> = dominanceMemo.getOrPut(x) {
        val forwarders = mutableListOf<Forwarder>()
        val ambiguous = mutableListOf<Pair<FnSymbol, List<KType.Nominal>>>()
        val own = methodsOf(x as Symbol).map { it.name }.toSet()
        paths(x).filter { it.value >= 2 }.keys.forEach { v ->
            v.methods.filter { !it.isOperator && it.name !in own }.forEach { m ->
                val found = LinkedHashMap<FnSymbol, KType.Nominal>()
                directBases(x).forEach { b -> overriders(b, m.name).forEach { (fn, via) -> found.putIfAbsent(fn, via) } }
                val others = found.filterKeys { it !== m }
                when {
                    others.size >= 2 -> ambiguous += m to others.values.toList()
                    others.size == 1 && found.size > 1 && others.keys.single().body != null ->
                        forwarders += Forwarder(others.keys.single(), others.values.single())
                }
            }
        }
        forwarders.toList() to ambiguous.toList()
    }

    private fun methodsOf(t: Symbol): List<FnSymbol> = when (t) {
        is ClassSymbol -> t.methods
        is TraitSymbol -> t.methods
        else -> emptyList()
    }

    /** The nearest declarations of [name] on each path up from [n] (inclusive), with their owners as [n]'s subtree names them. */
    private fun overriders(n: KType.Nominal, name: String): List<Pair<FnSymbol, KType.Nominal>> {
        val sym = n.sym as? Symbol ?: return emptyList()
        methodsOf(sym).firstOrNull { it.name == name }?.let { return listOf(it to n) }
        // A type that forwards the method declares it in C++: below it nothing dominates.
        forwarders(n.sym).firstOrNull { it.method.name == name }?.let { return listOf(it.method to n) }
        val sub = (n.sym.typeParams).zip(n.typeArgs()).toMap()
        return directBases(sym).flatMap { b -> overriders(b.substitute(sub) as KType.Nominal, name) }
    }

    companion object {
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
