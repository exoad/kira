package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.EnumMemberExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.source.SourceContext

/**
 * The persistent symbols of the typed model. Phase A (DeclarationCollector) creates one for
 * every declaration with its types unresolved ([KType.Error]); phase B (SignatureResolver)
 * fills the types in. Symbols are compared by identity: two classes named `Box` in two
 * modules are two symbols.
 *
 * Fields marked `var` are filled by a later phase:
 * - phase B: every `type`/`ret`/`target`/`base`, parents, [FnSymbol.isVirtual],
 *   [FnSymbol.overrides], [ClassSymbol.isSubclassed], [TraitSymbol.flatMethods],
 *   [GlobalSymbol.constValue], [EnumEntrySymbol.value];
 * - the rule passes (W2.5): [ClassSymbol.thisEscapes].
 */
sealed class Symbol {
    abstract val name: String

    /** The declaring AST node; null only for a symbol the typer synthesized (a builtin). */
    abstract val decl: ASTNode?

    /** The module the symbol is declared in (a [ModuleSymbol] is its own module). */
    abstract val module: ModuleSymbol

    /** `module:uri.Name` for top-level symbols, `Owner.member` for members. */
    open val qualifiedName: String get() = "${module.uri}.$name"

    override fun toString(): String = qualifiedName
}

/** A symbol that names a type: what [KType.Nominal] points at. */
sealed interface TypeSymbol {
    val name: String
    val module: ModuleSymbol
    val typeParams: List<TypeParamSymbol>
    val isPub: Boolean
}

enum class Profile { HOSTED, FREESTANDING }

/**
 * An `@_...` marker in front of a declaration, recorded verbatim: `@_extern(cpp = "bibo::Car",
 * header = "car.hxx")` has name `_extern` and two named parameters.
 */
class Marker(
    val name: String,
    val parameters: List<Expr> = emptyList(),
    val namedParameters: Map<String, Expr> = emptyMap(),
) {
    /** The literal text of a marker argument: a string's value, a number's digits, a name. */
    fun text(expr: Expr): String = markerText(expr)

    override fun toString(): String {
        if (parameters.isEmpty() && namedParameters.isEmpty()) {
            return "@$name"
        }
        val args = parameters.map { "\"${text(it)}\"" } + namedParameters.map { (k, v) -> "$k = \"${text(v)}\"" }
        return "@$name(${args.joinToString(", ")})"
    }

    companion object {
        fun markerText(expr: Expr): String = when (expr) {
            is StringLiteral -> expr.value
            is IntegerLiteral -> expr.value.toString()
            is FloatLiteral -> expr.value.toString()
            is Identifier -> expr.value
            else -> expr.toString()
        }
    }
}

/** How a declaration reaches something outside Kira. */
sealed interface Foreign {
    /**
     * `@_magic`: the compiler or the runtime supplies it. [key] is the marker's argument when
     * it has one, else the declaration's name; a member of a magic class is `Owner.member`
     * (the key the `*.bind.yaml` manifests use).
     */
    data class Magic(val key: String) : Foreign

    /**
     * `@_extern`: its parameters verbatim, for W2.6 to interpret. Named parameters keep their
     * names (`c`, `cpp`, `header`); the first positional one is `symbol`, later ones
     * `symbol#1`, `symbol#2`, ... A member of an extern class or struct that carries no
     * marker of its own is `Extern(emptyMap())`: the foreign member of the same name.
     */
    data class Extern(val params: Map<String, String>) : Foreign
}

class ModuleSymbol(
    val uri: String,
    val source: SourceContext,
    /** Every top-level declaration by name. On a duplicate the first declaration keeps the name. */
    val members: MutableMap<String, Symbol> = linkedMapOf(),
    /** The modules this one `use`s, in source order, once resolved. */
    val imports: MutableList<ModuleSymbol> = mutableListOf(),
    var profile: Profile = Profile.HOSTED,
    var cppNamespace: String = defaultNamespace(uri),
) : Symbol() {
    override val name: String get() = uri
    override var decl: ModuleDecl? = null
    override val module: ModuleSymbol get() = this
    override val qualifiedName: String get() = uri

    /** A `build.cpp.headerOnly` module, or any `kira:*` one: an inline-only header and no source file. */
    var isHeaderOnly: Boolean = false

    /** Every declaration in source order, duplicates and operator overloads included. */
    val declarations: MutableList<Symbol> = mutableListOf()

    /** `fx @op_add: ...` overloads. They may share a name, so they are not in [members]. */
    val operators: MutableList<FnSymbol> = mutableListOf()

    /** The raw `use` statements, in source order. */
    val uses: MutableList<UseStatement> = mutableListOf()

    /** Top-level statements that declare nothing, such as a module-level `@_static_assert(...)`. */
    val statements: MutableList<Statement> = mutableListOf()

    /** True for the stdlib (`kira:*`), whose pub members are ambient in every module. */
    val isStdlib: Boolean get() = uri.startsWith("kira:")

    /** The package before the colon: `firmware` for `firmware:pilot.src.proto`. */
    val packageName: String get() = uri.substringBefore(':')

    /** The dotted path after the colon, split: `[pilot, src, proto]`. */
    val pathSegments: List<String> get() = uri.substringAfter(':').split('.')

    companion object {
        /** D14 with no manifest overrides: [namespaceOf] with an empty map. */
        fun defaultNamespace(uri: String): String = namespaceOf(emptyMap(), uri)

        /**
         * D14, exactly as the C++ layout writes it (CppModuleLayout.namespaceOf): an override
         * from [namespaces] (exact URI, then the most specific glob), else the last URI segment
         * escaped like any C++ name (`new` gives `new_`), and a `kira:a.b` stdlib module goes in
         * `kira::a::b`. A URI the layout cannot split (no package or no path) keeps its text
         * after the last '.' here; the layout refuses that module itself.
         */
        fun namespaceOf(namespaces: Map<String, String>, uri: String): String = try {
            CppModuleLayout.namespaceOf(namespaces, uri)
        } catch (_: IllegalArgumentException) {
            uri.substringAfter(':').substringAfterLast('.')
        }
    }
}

enum class ClassKind { CLASS, STRUCT, MAGIC, OPAQUE }

class ClassSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    val kind: ClassKind,
    override val typeParams: List<TypeParamSymbol>,
    /** The parent class (`class B: A`), with its type arguments. Never set for a struct. */
    var superclass: KType.Nominal? = null,
    /** The traits named in the parent list, in order. */
    val traits: MutableList<KType.Nominal> = mutableListOf(),
    val fields: MutableList<FieldSymbol> = mutableListOf(),
    val methods: MutableList<FnSymbol> = mutableListOf(),
    var initially: List<Statement>? = null,
    var finally: List<Statement>? = null,
    var foreign: Foreign? = null,
    /** Some class in the program names this one as its superclass (so it is not `final`). */
    var isSubclassed: Boolean = false,
    /** Filled by EscapePass: `this` escapes a method, so the class derives `kira::Shared<C>`. */
    var thisEscapes: Boolean = false,
) : Symbol(), TypeSymbol {
    override var isPub: Boolean = false
    val markers: MutableList<Marker> = mutableListOf()

    val isStruct: Boolean get() = kind == ClassKind.STRUCT

    /** This class as a type over its own type parameters: `Box<T>` inside `class Box<T>`. */
    val selfType: KType.Nominal get() = KType.Nominal(this, typeParams.map { TypeArg.Ty(KType.Param(it)) })

    fun field(name: String): FieldSymbol? = fields.firstOrNull { it.name == name }

    fun method(name: String): FnSymbol? = methods.firstOrNull { it.name == name }
}

class TraitSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: TraitDecl?,
    override val typeParams: List<TypeParamSymbol>,
    /** The traits this one extends (`trait B: A`). */
    val parents: MutableList<KType.Nominal> = mutableListOf(),
    /** The trait's own methods, in source order. */
    val methods: MutableList<FnSymbol> = mutableListOf(),
    var foreign: Foreign? = null,
) : Symbol(), TypeSymbol {
    override var isPub: Boolean = false
    val markers: MutableList<Marker> = mutableListOf()

    /**
     * Every method a class implementing this trait must provide or inherit, parents first,
     * each name once. A method this trait redeclares replaces the parent's in place. A method
     * with a default body has [FnSymbol.hasBody]; its [FnSymbol.owner] says which trait wrote it.
     */
    val flatMethods: MutableList<FnSymbol> = mutableListOf()

    fun method(name: String): FnSymbol? = flatMethods.firstOrNull { it.name == name } ?: methods.firstOrNull { it.name == name }
}

class EnumSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: EnumDecl?,
    /** The base type: an integer or float [KType.Scalar], or [KType.Str]. */
    var base: KType = KType.Error,
    val entries: MutableList<EnumEntrySymbol> = mutableListOf(),
) : Symbol(), TypeSymbol {
    override val typeParams: List<TypeParamSymbol> get() = emptyList()
    override var isPub: Boolean = false
    var foreign: Foreign? = null
    val markers: MutableList<Marker> = mutableListOf()

    fun entry(name: String): EnumEntrySymbol? = entries.firstOrNull { it.name == name }
}

class EnumEntrySymbol(
    override val name: String,
    val owner: EnumSymbol,
    val index: Int,
    override val decl: EnumMemberExpr?,
    /** The entry's value in the enum's base type (implied values included), once phase B ran. */
    var value: ConstValue? = null,
) : Symbol() {
    override val module: ModuleSymbol get() = owner.module
    override val qualifiedName: String get() = "${owner.name}.$name"
}

class AliasSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: TypeAliasDecl?,
    val typeParams: List<TypeParamSymbol>,
    /** The target over [typeParams]: `Tuple2<A, B>` for `alias Pair<A, B> as Tuple2<A, B>`. */
    var target: KType = KType.Error,
) : Symbol() {
    var isPub: Boolean = false
    val markers: MutableList<Marker> = mutableListOf()
}

class TypeParamSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    val index: Int,
    /** `<T: Bound>`: resolved in phase B. */
    val bounds: MutableList<KType> = mutableListOf(),
) : Symbol() {
    /** The declaring class, trait, alias or function (set once that symbol exists). */
    var owner: Symbol? = null

    override val qualifiedName: String get() = "${owner?.qualifiedName ?: module.uri}<$name>"
}

class FnSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: FunctionDecl?,
    val typeParams: List<TypeParamSymbol>,
    val params: List<ParamSymbol>,
    var ret: KType = KType.Error,
    /** The class, struct or trait that declares this method; null for a free function. */
    val owner: TypeSymbol? = null,
    /** `mut fx`: the method may mutate its receiver. */
    val isMutMethod: Boolean = false,
    var foreign: Foreign? = null,
    val hasBody: Boolean = false,
    /** Declared with the `override` modifier. */
    val isOverride: Boolean = false,
    /** `@_const`: verified by ConstEligibilityPass, lowered to `constexpr`. */
    val isConst: Boolean = false,
    /** A trait method, or a class method some subclass overrides, or one that overrides. */
    var isVirtual: Boolean = false,
) : Symbol() {
    var isPub: Boolean = false

    /** `fx @op_add: ...`: an operator overload; [name] is the intrinsic's name (`op_add`). */
    var isOperator: Boolean = false
    val markers: MutableList<Marker> = mutableListOf()

    /**
     * The method this one overrides or implements: the nearest superclass method of the same
     * name, else the trait method it provides. Set for struct methods that implement a trait
     * too, though those stay non-virtual (static dispatch, D1).
     */
    var overrides: FnSymbol? = null

    val body: List<Statement>? get() = decl?.def?.body

    override val qualifiedName: String get() = if (owner != null) "${owner.name}.$name" else "${module.uri}.$name"

    /** This function as a value: `Fx<TupleN<...>, R>`. */
    val fnType: KType.Fn get() = KType.Fn(params.map { FnParam(it.type, it.byRef) }, ret)
}

class ParamSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    var type: KType = KType.Error,
    /** `mut p: T` (D4): passed by reference, with a call-site `mut`. */
    val byRef: Boolean = false,
    /** The default value, kept as the expression (D48: a literal or a pub constant). */
    val default: Expr? = null,
    val index: Int = 0,
) : Symbol() {
    /** The function or method (set once it exists); null for a lambda's parameter, which W2.1 creates. */
    var fn: FnSymbol? = null

    override val qualifiedName: String get() = "${fn?.qualifiedName ?: "<lambda>"}($name)"
}

class FieldSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    val owner: TypeSymbol,
    var type: KType = KType.Error,
    val isMut: Boolean = false,
    val isPub: Boolean = false,
    /** `require`: construction must supply it (D38: C++ value-initializes it anyway). */
    val isRequired: Boolean = false,
    val default: Expr? = null,
    /** Position among the owner's own fields, in declaration order. */
    val index: Int = 0,
) : Symbol() {
    val markers: MutableList<Marker> = mutableListOf()
    override val qualifiedName: String get() = "${owner.name}.$name"
}

/** A local binding or loop variable. Phase C (W2.1) creates these; phases A and B never do. */
class LocalSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    var type: KType = KType.Error,
    val isMut: Boolean = false,
    /** The function whose body declares it, when there is one. */
    val fn: FnSymbol? = null,
) : Symbol() {
    override val qualifiedName: String get() = "${fn?.qualifiedName ?: module.uri}::$name"
}

class GlobalSymbol(
    override val name: String,
    override val module: ModuleSymbol,
    override val decl: ASTNode?,
    var type: KType = KType.Error,
    val isMut: Boolean = false,
    /**
     * A module-level binding without `mut`. The spec makes these compile-time constants
     * (UPPER_SNAKE_CASE by convention); [constValue] says whether ConstEval could fold it.
     */
    val isConstant: Boolean = !isMut,
    val init: Expr? = null,
    var constValue: ConstValue? = null,
) : Symbol() {
    var isPub: Boolean = false
    var foreign: Foreign? = null
    val markers: MutableList<Marker> = mutableListOf()
}
