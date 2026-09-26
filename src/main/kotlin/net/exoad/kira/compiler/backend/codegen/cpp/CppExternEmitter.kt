package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.core.intrinsics.ExternIntrinsic

/**
 * `@_extern` for the C++ backend (design 7.2): an extern module emits checks, not
 * declarations. The C++ declarations already exist in the headers `header =` names; a second
 * copy would be the drift the memory note "comments that claim other files" warns about, so
 * the module's header includes the real headers, then `kira/ffi.hxx`, and states one
 * `KIRA_EXTERN_CHECK` per declared member: an unevaluated call whose result must match the
 * type Kira declared. Overloads, C++ default arguments and the argument proxies all resolve
 * in that call, and a wrong Kira signature fails with Kira's message on g++, clang and MSVC
 * (probes-S/externcheck.cxx).
 *
 * "Match" is kira/ffi.hxx's rule, recorded at the head of that file: a scalar exactly (the
 * same size, signedness and kind, which is what `int` and `long` are on arm-none-eabi, where
 * `std::int32_t` is `long`), at the return, at every by-value parameter and at every struct
 * field; a `Maybe` and a `Fn` part by part by the same rule; anything else when it converts.
 * Section 7.2 wrote `is_convertible` for the return; that let a C++ `std::uint32_t count()`
 * pass as `count: () Int32`, and since the call site keeps the C++ type, `count() - 1` was
 * computed unsigned (measured: 4294967295 for Kira's -1, with -Wconversion -Wsign-conversion
 * -Werror silent); `std::optional`'s converting constructor opened the same hole one level
 * down (`std::optional<std::uint32_t> find()` as `find: () Maybe<Int32>`, measured the same).
 *
 * The checks follow section 7.2 at global scope: names as the marker spells them
 * (`bibo::Car`, never `::bibo::Car`), a non-mut method through a `const` receiver, a `Void`
 * method as `(call, 0)` against `int`, a `Str` parameter as `kira::ffi::in(...)`, a `mut`
 * parameter as `kira::ffi::out(...)`, a by-value scalar, `Maybe` or `Fn` parameter as
 * `kira::ffi::arg<T>()` (7.2 wrote `std::declval<T>()`, which converts to any scalar
 * parameter, the same hole as the return's), except a `mut p: Unsafe<T>`, which is the
 * writable `T*` itself (table 5.1: `Unsafe<T>` is `const T*` unless `mut`), passed by value
 * with no proxy: that is how a C `void fill(uint8_t*, size_t)` or ImGui's `InputText(char*
 * buf, ...)` is declared. A struct declared with fields also gets a layout twin of the same
 * fields and a `static_assert` on `sizeof`, and one `KIRA_EXTERN_FIELD` per field (the
 * matching type, and the offset the twin gives it, so a same-size drift or a reordered pair
 * is caught).
 *
 * What the check lets through is then made the declared type at the use: a call's result
 * and a constant's read are `kira::ffi::declared<T>(...)`, and a field read of an extern
 * struct `kira::ffi::field<T>(...)`, unless T is `Void` or a pointer (`Unsafe<T>`, `CStr`,
 * an opaque handle: the check lets only a qualification differ, and a pointer's operations
 * are the same either way). A scalar is converted too: the check proves the C++ type is a
 * scalar of the same size, signedness and kind, not the declared type itself, and Kira's
 * own operations are written for the declared type. A C++ `const char* name()` declared
 * `name: () Str` passes its check, and left as the call's own type `name() == ABC` compared
 * two pointers where Kira compares a Str by value (measured: 0 for Kira's 1, on g++, clang
 * and MSVC with -Werror silent); a `long wide()` declared `Int32` (Windows) and a C `int
 * cnt_read(void)` declared `Int32` on arm-none-eabi both passed, and `wide() / d` then
 * failed to compile in `kira::div(long, int32_t&)` (measured, g++ 13.2, zig clang 20 and
 * arm-none-eabi-g++ 13.3); a C enum result declared `Int32` failed the same way inside
 * `kira::cat`. Converted, `wide() / d` divides two `std::int32_t`. What each conversion
 * does with a null `const char*` and with a field whose C++ type is the declared one is
 * kira/ffi.hxx's (the empty Str; the member itself, an lvalue).
 *
 * An extern parameter takes no Kira default: the C++ header's own default fills a parameter
 * Kira leaves undeclared, and a Kira default would be a second, unchecked declaration of it
 * (the check macro cannot see defaults). The declaration is refused instead.
 *
 * A declaration that names its symbol with `c =` alone (design 7.3) reaches a C header: the
 * name is the C one, and the header is included inside `extern "C" { }`, which a header
 * with its own `__cplusplus` guard tolerates (nested linkage specifications are a no-op)
 * and a header without one needs. The wrap has one known edge: a C header that reaches a
 * C++ standard header from inside it fails on libc++ (`<complex.h>` is `<complex>` there,
 * and its templates must have C++ linkage; g++ and MSVC accept it, measured). Such a
 * header carries its own guard, so its symbols are declared with `cpp =`, which includes
 * the header unwrapped.
 *
 * At a call, [call] spells the C++ name with a leading `::` and the proxies (the expression
 * part hands over the spelled receiver and arguments), and converts the result as above.
 * An extern constant is read by its C++ name exactly as the marker spells it, with no `::`
 * added ([constant]), converted the same way: a C constant
 * reached through `c =` is usually an object-like macro (`#define LIMIT 42`, and ImGui's
 * `IM_COL32_*` are too), `::LIMIT` expands to `::42`, and the check already proves the
 * unqualified spelling, so the read uses the spelling the check proved.
 */
object CppExternEmitter : CppExternsPart {
    /** The runtime header the proxies and the check macros live in. */
    const val FFI_HEADER = "kira/ffi.hxx"

    /** The macro of `kira/ffi.hxx` every check states. */
    const val CHECK = "KIRA_EXTERN_CHECK"

    /** The macro of `kira/ffi.hxx` a struct field is checked with: its exact type and its offset. */
    const val FIELD_CHECK = "KIRA_EXTERN_FIELD"

    /** The namespace an extern struct's layout twin goes in: lowercase with `_`, which no Kira name can be. */
    const val LAYOUT_NAMESPACE = "ffi_"

    /** The message a failing check prints; tests grep for it. */
    const val DRIFT_MESSAGE = "no longer matches its C++ header"

    // ---- the marker -----------------------------------------------------------------------------

    /** The `@_extern` marker of [sym], or null when it carries none (a member inherits its owner's). */
    fun externOf(sym: Symbol): Foreign.Extern? = when (sym) {
        is ClassSymbol -> sym.foreign
        is TraitSymbol -> sym.foreign
        is FnSymbol -> sym.foreign
        is GlobalSymbol -> sym.foreign
        is EnumSymbol -> sym.foreign
        else -> null
    } as? Foreign.Extern

    /**
     * The C++ name the marker gives [sym]: `cpp =`, else the positional symbol (the symbol for
     * the current target), else `c =` (a C function is callable from C++ under its C name,
     * design 7.3), else the Kira name (a method of an extern class with no marker of its own
     * is the C++ member of the same name). Spelled as the marker wrote it, without a leading `::`.
     */
    fun cppName(sym: Symbol): String {
        val params = externOf(sym)?.params ?: return sym.name
        return params[ExternIntrinsic.CPP] ?: params[POSITIONAL] ?: params[ExternIntrinsic.C] ?: sym.name
    }

    /**
     * Whether [sym]'s marker names a C symbol and no C++ one (`@_extern(c = "sym", header =
     * "lib.h")`, design 7.3): its header is a C header, included with C linkage.
     */
    fun hasCLinkage(sym: Symbol): Boolean {
        val params = externOf(sym)?.params ?: return false
        return ExternIntrinsic.C in params && ExternIntrinsic.CPP !in params && POSITIONAL !in params
    }

    /**
     * [cppName] as an expression or a type names it from anywhere: with a leading `::`. A C
     * struct a header never typedefs is named with its keyword (`c = "struct cnt_state"`),
     * and the `::` goes after it: `struct ::cnt_state` is the elaborated type, `::struct` is
     * nothing.
     */
    fun globalName(sym: Symbol): String = global(cppName(sym))

    private fun global(name: String): String {
        if (name.startsWith("::")) {
            return name
        }
        val keyword = ELABORATED.firstOrNull { name.startsWith("$it ") } ?: return "::$name"
        val rest = name.removePrefix("$keyword ").trimStart()
        return if (rest.startsWith("::")) "$keyword $rest" else "$keyword ::$rest"
    }

    /** The keywords a C type may be named with when its header declares no typedef. */
    private val ELABORATED = listOf("struct", "union", "enum")

    /** The `header =` of [sym]'s marker, or null. */
    fun headerOf(sym: Symbol): String? = externOf(sym)?.params?.get(ExternIntrinsic.HEADER)

    // ---- includes and checks (the declaration emitter calls these) ---------------------------------

    override fun includes(ctx: CppEmitContextImpl, sym: Symbol): List<String> = headers(sym, cLinkage = false)

    override fun cIncludes(ctx: CppEmitContextImpl, sym: Symbol): List<String> = headers(sym, cLinkage = true)

    /** The headers of [sym] and its methods whose markers have (or have not) C linkage, once each, in declaration order. */
    private fun headers(sym: Symbol, cLinkage: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: Symbol) {
            if (hasCLinkage(s) == cLinkage) {
                headerOf(s)?.let(out::add)
            }
        }
        add(sym)
        if (sym is ClassSymbol) {
            sym.methods.forEach(::add)
        }
        return out.toList()
    }

    override fun check(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter) {
        ctx.includeInHeader(FFI_HEADER)
        (includes(ctx, sym) + cIncludes(ctx, sym)).filter { it.startsWith("<") }.forEach { header ->
            // The declaration emitter writes every include in quotes; `#include "<cmath>"` names a file.
            sym.decl?.let { ctx.unsupported(it, "the system header $header of '${sym.name}' (name a header of the project that includes it)") }
        }
        when (sym) {
            is FnSymbol -> functionCheck(ctx, sym, null, w)
            is ClassSymbol -> classChecks(ctx, sym, w)
            is GlobalSymbol -> globalCheck(ctx, sym, w)
            is TraitSymbol -> sym.decl?.let { ctx.unsupported(it, "the extern trait '${sym.name}' (a C++ interface is reached through an extern class)") }
            is EnumSymbol -> sym.decl?.let { ctx.unsupported(it, "the extern enum '${sym.name}' (declare its values as extern constants)") }
            else -> sym.decl?.let { ctx.unsupported(it, "the extern declaration '${sym.name}'") }
        }
    }

    private fun classChecks(ctx: CppEmitContextImpl, cls: ClassSymbol, w: CppWriter) {
        if (cls.typeParams.isNotEmpty()) {
            cls.decl?.let { ctx.unsupported(it, "the generic extern ${kindOf(cls)} '${cls.name}'") }
            return
        }
        if (cls.superclass != null || cls.traits.isNotEmpty()) {
            cls.decl?.let { ctx.unsupported(it, "the extern ${kindOf(cls)} '${cls.name}' with a parent list (the C++ side owns its hierarchy)") }
            return
        }
        if (cls.fields.isNotEmpty()) {
            if (!cls.isStruct) {
                cls.decl?.let { ctx.unsupported(it, "the fields of the extern class '${cls.name}' (a class is reached through its methods; a struct declares fields)") }
            } else {
                layoutChecks(ctx, cls, w)
            }
        }
        cls.methods.forEach { functionCheck(ctx, it, cls, w) }
    }

    private fun kindOf(cls: ClassSymbol): String = if (cls.isStruct) "struct" else "class"

    /**
     * `namespace ns::ffi_ { struct S { fields; }; }` and a `static_assert` that the C++ struct
     * has the same size, then one [FIELD_CHECK] per field: the C++ member matches the Kira
     * type (a scalar of the same size, signedness and kind, or the same type; `is_convertible`
     * let `int` pass for `Float32`, and `is_same` let no C `int` field pass on arm-none-eabi,
     * where `std::int32_t` is `long`) and sits at the twin's offset (so two same-typed fields
     * in the other order fail too). The twin is a flat
     * aggregate of the same fields in the same order, so it pads as the C++ one does; a sum
     * of sizes would not. A `mut` field of `Unsafe<T>` is `T*`, as a `mut` binding is.
     *
     * A field named a C++ keyword cannot be a member of the C++ struct, so it is refused
     * rather than escaped: the twin and the check must spell the C++ member's own name.
     */
    private fun layoutChecks(ctx: CppEmitContextImpl, cls: ClassSymbol, w: CppWriter) {
        val name = cppName(cls)
        val twin = "${ctx.namespace}::$LAYOUT_NAMESPACE::${ctx.names.escape(cls.name)}"
        cls.fields.filter { ctx.names.escape(it.name) != it.name }.forEach { f ->
            (f.decl ?: cls.decl)?.let { ctx.unsupported(it, "the field '${f.name}' of the extern struct '${cls.name}' (a C++ keyword cannot name a C++ member)") }
        }
        val fields = cls.fields.joinToString(" ") { f -> "${fieldType(ctx, f)} ${f.name};" }
        w.line("namespace ${ctx.namespace}::$LAYOUT_NAMESPACE { struct ${ctx.names.escape(cls.name)} { $fields }; }")
        w.line("static_assert(sizeof($name) == sizeof($twin), \"Kira's ${cls.name} $DRIFT_MESSAGE\");")
        cls.fields.forEach { f ->
            w.line("$FIELD_CHECK($name, $twin, ${f.name}, ${fieldType(ctx, f)}, \"${cls.name}.${f.name}\");")
        }
    }

    private fun fieldType(ctx: CppEmitContextImpl, f: FieldSymbol): String =
        checkType(ctx, f.type, if (f.isMut) Pos.MUT_VALUE else Pos.FIELD)

    private fun functionCheck(ctx: CppEmitContextImpl, fn: FnSymbol, owner: ClassSymbol?, w: CppWriter) {
        if (fn.typeParams.isNotEmpty()) {
            fn.decl?.let { ctx.unsupported(it, "the generic extern function '${fn.name}'") }
            return
        }
        fn.params.firstOrNull { it.default != null }?.let { p ->
            ctx.diag(
                p.default ?: p.decl ?: fn.decl ?: return,
                CppModuleEmitterFactory.UNSUPPORTED_CODE,
                "the default of parameter '${p.name}' of the extern function '${fn.name}': the C++ header's own default fills a parameter Kira leaves undeclared, and a Kira default would be a second, unchecked declaration of it (declare '${p.name}' without one, or leave it out)",
            )
            return
        }
        val what = if (owner != null) "${owner.name}.${fn.name}" else fn.name
        val receiver = when {
            owner == null -> ""
            fn.isMutMethod -> "std::declval<${cppName(owner)}&>()."
            else -> "std::declval<const ${cppName(owner)}&>()."
        }
        val args = fn.params.joinToString(", ") { checkArg(ctx, it) }
        val invoke = "$receiver${cppName(fn)}($args)"
        if (fn.ret == KType.Void) {
            w.line("$CHECK(($invoke, 0), int, \"$what\");")
        } else {
            w.line("$CHECK($invoke, ${checkType(ctx, fn.ret, Pos.RETURN)}, \"$what\");")
        }
    }

    /**
     * One argument of a check: the proxy the call would pass, over a `std::declval` of the
     * Kira type. A `mut` `Unsafe<T>` is the bare `T*` (the speller's `MUT_PARAM` column),
     * never `out(...)`, whose `Out<const T*>` could bind only a `const T*&` or `const T**`.
     * A by-value scalar, `Maybe` or `Fn` is `kira::ffi::arg<T>()`, which converts to what
     * matches T and to nothing else (kira/ffi.hxx): `std::declval<T>()` converted to whatever
     * the C++ parameter was, so a Kira `v: Int32` passed the check over a C++ `std::uint8_t
     * v` and the call then narrowed silently, and a `Maybe<Int32>` did the same over a
     * `std::optional<std::uint8_t>` through optional's converting constructor (300 reached
     * C++ as 44, measured). A struct, a class handle or a pointer has no such constructor
     * and stays `std::declval<const T&>()`, the lvalue the call passes: with a proxy, an
     * overload taking T itself and one taking a type T converts to would rank equal (both
     * user-defined conversions) and the check would be ambiguous where the call is not.
     */
    private fun checkArg(ctx: CppEmitContextImpl, p: ParamSymbol): String = when {
        isUnsafe(p.type) -> "std::declval<${checkType(ctx, p.type, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM)}>()"
        p.byRef -> "kira::ffi::out(std::declval<${checkType(ctx, p.type, Pos.VALUE)}&>())"
        p.type == KType.Str -> "kira::ffi::in(std::declval<const kira::Str&>())"
        takesProxy(p.type) -> "kira::ffi::arg<${checkType(ctx, p.type, Pos.VALUE)}>()"
        else -> "std::declval<${checkType(ctx, p.type, Pos.PARAM)}>()"
    }

    /** Whether a by-value parameter of type [t] is stated as `kira::ffi::arg<T>()`: a scalar, a `Maybe` or a `Fn`. */
    private fun takesProxy(t: KType): Boolean = t is KType.Scalar || t is KType.Fn || isMagic(t, MAYBE)

    private fun globalCheck(ctx: CppEmitContextImpl, g: GlobalSymbol, w: CppWriter) {
        if (g.isMut) {
            g.decl?.let { ctx.unsupported(it, "the extern mut variable '${g.name}' (an extern binding is a constant; write to C++ state through an extern function)") }
            return
        }
        if (g.init != null) {
            g.decl?.let { ctx.diag(it, CppModuleEmitterFactory.UNSUPPORTED_CODE, "the extern constant '${g.name}' takes its value from C++; drop the initializer") }
            return
        }
        w.line("$CHECK(${cppName(g)}, ${checkType(ctx, g.type, Pos.FIELD)}, \"${g.name}\");")
    }

    /**
     * A type as the checks spell it: the type speller's text at [pos], with the leading `::`
     * the speller puts on every cross-module and extern name removed (`kira::Rc<bibo::Car>`,
     * as 7.2 writes it; at global scope the two spellings name the same thing).
     */
    fun checkType(ctx: CppEmitContextImpl, t: KType, pos: Pos): String = unglobal(ctx.spell(t, pos))

    private val LEADING_SCOPE = Regex("(^|[^A-Za-z0-9_>)])::")

    private fun unglobal(text: String): String = LEADING_SCOPE.replace(text) { it.groupValues[1] }

    // ---- calls and constants (the expression part calls these) --------------------------------------

    override fun call(ctx: CppEmitContextImpl, call: ResolvedCall, receiver: String?, args: List<String>): String {
        val fn = call.fn ?: return "/* extern call without a callee */"
        val owner = fn.owner
        val callee = when {
            owner == null || receiver == null -> globalName(fn)
            else -> receiver + accessor(owner) + cppName(fn)
        }
        // An extern parameter has no Kira default (functionCheck refuses one), so every binding
        // here is a given argument; a Default would be a default the C++ side never sees.
        call.args.filterIsInstance<ArgBinding.Default>().firstOrNull()?.let { d ->
            (d.param.default ?: fn.decl)?.let { ctx.unsupported(it, "the default of parameter '${d.param.name}' of the extern function '${fn.name}'") }
        }
        // Every channel this call can hand a caller a *new* pointer through: the result itself,
        // a `mut` parameter (kira::ffi::out(x) lets the callee write a fresh value there - a
        // bare `mut p: Unsafe<T>` is the one exception, since that is the caller's own buffer
        // passed in to be filled, not a value the callee produces, table 5.1), and a `mut fx`'s
        // receiver, which `out(x)` reaches exactly the same way a `mut` parameter does. Issue 2
        // (round 4): a computed Str fed to one argument can dangle through any of these, not
        // only through the return.
        val pointerEscapes = carriesPointer(call.returnType) ||
            fn.params.any { it.byRef && !isUnsafe(it.type) && carriesPointer(it.type) } ||
            (fn.isMutMethod && receiverType(ctx, call)?.let { carriesPointer(it) } == true)
        val texts = args.mapIndexed { i, text -> argument(ctx, fn, pointerEscapes, fn.params.getOrNull(i), call.args.getOrNull(i), text) }
        return declared(ctx, fn.ret, "$callee(${texts.joinToString(", ")})")
    }

    /** The receiver's type for a method call: what [call.receiver] typed to, or, called through
     * an implicit `this` (no receiver expression), the owning class itself. */
    private fun receiverType(ctx: CppEmitContextImpl, call: ResolvedCall): KType? =
        call.receiver?.let { ctx.model.types[it] } ?: (call.fn?.owner as? ClassSymbol)?.let { KType.Nominal(it) }

    /**
     * Whether a value of type [t], read back from an extern call, can point into memory that
     * call's own arguments own: a `CStr`, an `Unsafe<T>`, a `View<T>` or a `MutView<T>`
     * directly (`kira::View`/`kira::MutView` is a borrowed ptr+len over someone else's storage,
     * core.hxx), a `Maybe<X>` where [carriesPointer] is true of X (nothing else names one
     * today), or a class or struct with such a field, checked recursively through its own type
     * arguments ([seen] stops a self-referential type from recursing forever; re-visiting one
     * it has already cleared adds no new pointer). Used at [call] for the return type, a `mut`
     * parameter's or a `mut` method's receiver's type (an output channel exactly like the
     * return, since `kira::ffi::out(x)` lets the callee write a new value there), and at
     * [argument] to refuse a computed `Str` feeding such a call (policy 1):
     * `kira::ffi::CStrBuf(expr)` and `kira::ffi::in(expr)` build a temporary that lives only to
     * the end of the call's own full-expression, which is not long enough once a value
     * answering true here is read afterward. `field<T>` (an extern struct field's read) never
     * returns such a value from a *computed* expression the way a call does, so this only
     * guards [argument].
     */
    private fun carriesPointer(t: KType, seen: MutableSet<ClassSymbol> = mutableSetOf()): Boolean {
        if (isCStr(t) || isUnsafe(t) || isMagic(t, VIEW) || isMagic(t, MUT_VIEW)) {
            return true
        }
        val nominal = t as? KType.Nominal ?: return false
        if (isMagic(t, MAYBE)) {
            return nominal.typeArgs().any { carriesPointer(it, seen) }
        }
        val cls = nominal.sym as? ClassSymbol ?: return false
        if (!seen.add(cls)) {
            return false
        }
        val substitution = cls.typeParams.zip(nominal.typeArgs()).toMap()
        return cls.fields.any { carriesPointer(it.type.substitute(substitution), seen) }
    }

    /**
     * [text], a C++ value the check proved matches the Kira type [t], as that type: itself
     * when [t] is `Void` or a pointer (`Unsafe<T>`, `CStr`, an opaque handle: only a
     * qualification could differ, and a pointer's operations are the same either way);
     * `kira::ffi::declared<T>(text)` otherwise, since what the check let through is not yet
     * the declared type. A `const char*` result declared `Str` is the case that bit first:
     * `name() == ABC` compared pointers (measured, 0 for Kira's 1); a `char letter()`
     * declared `Int8` printed A where an Int8 prints 65 (now refused by the check, since char
     * is only Char); a `long wide()` declared `Int32` passed the check and `wide() / d` then
     * failed to compile in `kira::div(long, int32_t&)` (measured), as did a C `int` on
     * arm-none-eabi and a C enum in `kira::cat`. A result of the declared type itself costs
     * one move.
     */
    private fun declared(ctx: CppEmitContextImpl, t: KType, text: String): String =
        if (keepsCppType(t)) text else "kira::ffi::declared<${ctx.spell(t, Pos.VALUE)}>($text)"

    private fun keepsCppType(t: KType): Boolean =
        t == KType.Void || t == KType.Never || isUnsafe(t) || isCStr(t) || isOpaque(t)

    /**
     * A field read of an extern struct as the declared type ([CppExternsPart.field]):
     * `kira::ffi::field<T>(text)`, which is the member itself when its C++ type is T and the
     * converted value otherwise (a `char c` declared `Int8` printed C where an Int8 prints 67,
     * measured; char is now only Char, and a C `int` on arm-none-eabi or an enum member is
     * still a same-size twin the field check accepts). A field of a Kira type, or a pointer
     * field, is the text as given.
     */
    override fun field(ctx: CppEmitContextImpl, f: FieldSymbol, text: String): String {
        val owner = f.owner as? ClassSymbol ?: return text
        if (externOf(owner) == null || keepsCppType(f.type)) {
            return text
        }
        return "kira::ffi::field<${ctx.spell(f.type, if (f.isMut) Pos.MUT_VALUE else Pos.FIELD)}>($text)"
    }

    /** Whether [f] is a field of an extern struct: a read of it is a boundary crossing ([field]). */
    fun isExternField(f: FieldSymbol): Boolean = (f.owner as? ClassSymbol)?.let { externOf(it) } != null

    /** `->` for a class or an opaque handle (an `Rc` or a pointer), `.` for a struct (a value). */
    private fun accessor(owner: TypeSymbol): String = when {
        owner is ClassSymbol && owner.isStruct -> "."
        else -> "->"
    }

    /**
     * One argument as the extern call passes it: `kira::ffi::out(x)` for a `mut` parameter
     * (but a `mut` `Unsafe<T>` is the `T*` itself), `kira::ffi::in(s)` for a `Str` one, and
     * for a `CStr` parameter given a `Str` (7.2): a literal passes through, a named `Str`
     * becomes `.c_str()`, anything else `kira::ffi::CStrBuf(expr).c_str()`, which lives to
     * the end of the full-expression. A named `Str` that is a Kira `Str` constant is already
     * a `const char*` (D12: `inline constexpr const char*`), so it passes through as the
     * literal does; an extern `Str` constant is read as a `kira::Str` made from whatever C++
     * declared ([constant]: a `std::string` or a `const char*` both pass its check), a
     * temporary, so it takes the buffer.
     *
     * Policy 1 (no silent miscompile): when [pointerEscapes] says a value [fn]'s call can hand
     * back - its result, a `mut` parameter's value, or a `mut` method's mutated receiver - can
     * point into that same buffer (a `CStr`, an `Unsafe<T>`, a `View`/`MutView`, or a `Maybe`
     * or struct holding one; [carriesPointer]'s doc lists the shapes and the channels), a
     * buffer built here is not proven to outlive it. The buffer's own lifetime, "to the end of
     * the full-expression," is already not long enough for a stored result read on a later
     * statement (`m: Maybe<CStr> = afterS(a + b, 44); trace(lenM(m))`, measured: `m` dangles);
     * it is shorter still once W2.3's hoister spills the call into its own statement for
     * sitting beside an impure sibling (measured with MSVC ASan, freed by `~CStrBuf` in the
     * spilled statement, before the result the caller kept was read); a `mut out: Maybe<CStr>`
     * parameter or a `mut fx`'s receiver dangles the same way once the call's own statement
     * ends, with no hoisting needed at all (measured, `afterInto(a + b, 44, mut e)` then
     * `trace(lenM(e))`; `setName(mut o, a + b)`; `o.rename(a + b)`). Rather than track whether a
     * spill will happen, how far a result travels, or which of these channels a given callee
     * uses, the call is refused whenever a temporary buffer would feed *any* of them:
     * [isTemporaryStr] is exactly the shape [argument] would otherwise wrap in
     * `CStrBuf`/`in(...)`, so refusing there is narrower than emitting code no lifetime
     * analysis here can back up (naming the `Str` first, `s: Str = ...; then pass s`, keeps the
     * buffer as the caller's own local, whose lifetime is the caller's to manage instead of
     * this call's).
     *
     * On the `Str`-parameter path this includes a literal and a named Kira `Str` constant, not
     * only a computed expression or an extern constant's read: `kira::ffi::in(text)` takes a
     * `const std::string&`, and `text` for a literal (spelled as a C string literal, W2.3's
     * `cppString`) or a Kira constant (D12: `inline constexpr const char*`) is a `const char*`
     * that `in` converts through a fresh `std::string` temporary just the same, dying at the
     * same full-expression's end (measured: `afterS("hello, world, ...", 44)` and
     * `afterS(GREETING, 44)`, both read 0 where 77/78 are correct, MSVC ASan reports the same
     * use-after-free). The `CStr`-parameter path below is not this exposed: a literal or a
     * named Kira `Str` constant passes through as the `const char*` it already is, with no
     * `in`/`CStrBuf` wrapper and no temporary at all, so [isTemporaryStr] is never consulted
     * there.
     */
    private fun argument(ctx: CppEmitContextImpl, fn: FnSymbol, pointerEscapes: Boolean, p: ParamSymbol?, binding: ArgBinding?, text: String): String {
        if (p == null) {
            return text
        }
        if (p.byRef) {
            return if (isUnsafe(p.type)) text else "kira::ffi::out($text)"
        }
        val expr = (binding as? ArgBinding.Given)?.expr
        if (p.type == KType.Str) {
            if (pointerEscapes && expr != null && isTemporaryStr(ctx, expr)) {
                refuseDanglingStr(ctx, fn, expr)
            }
            return "kira::ffi::in($text)"
        }
        if (isCStr(p.type)) {
            if (expr == null || ctx.model.types[expr] != KType.Str) {
                return text
            }
            return when {
                expr is StringLiteral -> text
                expr is Identifier && expr !is IntrinsicExpr -> when (val sym = ctx.model.symbolOf(expr)) {
                    is GlobalSymbol -> when {
                        externOf(sym) != null -> {
                            if (pointerEscapes) {
                                refuseDanglingStr(ctx, fn, expr)
                            }
                            "kira::ffi::CStrBuf($text).c_str()"
                        }
                        sym.isConstant -> text
                        else -> "$text.c_str()"
                    }
                    else -> "$text.c_str()"
                }
                else -> {
                    if (pointerEscapes) {
                        refuseDanglingStr(ctx, fn, expr)
                    }
                    "kira::ffi::CStrBuf($text).c_str()"
                }
            }
        }
        return text
    }

    /**
     * Whether [expr], bound to a `Str`-typed parameter, is a shape [argument]'s
     * `kira::ffi::in(text)` builds a fresh `std::string` temporary from, rather than binding
     * its reference straight to storage that already outlives this call: a computed
     * expression; a read of an extern `Str` constant (manufactures a fresh `kira::Str` on every
     * call, D12, not the header's own storage); a `StringLiteral` (a C string literal, a
     * `const char*` that `in`'s `const std::string&` parameter converts through a temporary
     * just as it would a computed one); or a named Kira `Str` constant (D12: `inline constexpr
     * const char*`, the same conversion). Only a local, or a plain (non-constant, non-extern)
     * global `Str` - already an lvalue of `in`'s own parameter type, `std::string` - binds the
     * reference with no temporary at all.
     */
    private fun isTemporaryStr(ctx: CppEmitContextImpl, expr: Expr): Boolean = when {
        expr is StringLiteral -> true
        expr is Identifier && expr !is IntrinsicExpr -> when (val sym = ctx.model.symbolOf(expr)) {
            is GlobalSymbol -> externOf(sym) != null || sym.isConstant
            else -> false
        }
        else -> true
    }

    /**
     * Refuses [expr] as a computed `Str` argument to the extern function [fn], one of whose
     * output channels - its result, a `mut` parameter, or a `mut` method's receiver -
     * [carriesPointer] says can point into the temporary buffer that argument would build
     * ([argument]'s doc has the reasoning). Named for what to write instead, as every other
     * refusal in this file does.
     */
    private fun refuseDanglingStr(ctx: CppEmitContextImpl, fn: FnSymbol, expr: Expr) {
        ctx.diag(
            expr,
            CppModuleEmitterFactory.UNSUPPORTED_CODE,
            "a computed Str argument to the extern function '${fn.name}', whose result, a mut argument or a mut " +
                "receiver can point into it (name it first: s: Str = ...; then pass s)",
        )
    }

    /** `CStr`, the FFI `const char*` (design 7.2, a magic class of the builtins). */
    private fun isCStr(t: KType): Boolean = isMagic(t, CSTR)

    /** `Unsafe<T>`, the FFI `T*` (table 5.1: `const T*` unless `mut`). */
    private fun isUnsafe(t: KType): Boolean = isMagic(t, UNSAFE)

    private fun isMagic(t: KType, name: String): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.MAGIC && sym.name == name
    }

    /** An `@_opaque` class, the C++ pointer `C*` (table 5.1). */
    private fun isOpaque(t: KType): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.OPAQUE
    }

    /**
     * The read of an extern constant: [cppName], never [globalName], as the declared type
     * ([declared]). A macro cannot take a `::`, and [globalCheck] stated the check over this
     * same spelling; a marker that wants the global one writes it (`cpp = "::bibo::LIMIT"`),
     * and it passes through unchanged.
     */
    override fun constant(ctx: CppEmitContextImpl, sym: GlobalSymbol): String = declared(ctx, sym.type, cppName(sym))

    /** Whether [call] is one the expression part must hand to [CppExternEmitter.call]: its callee is extern. */
    fun isExternCall(call: ResolvedCall): Boolean = call.fn?.foreign is Foreign.Extern

    private const val CSTR = "CStr"
    private const val UNSAFE = "Unsafe"
    private const val MAYBE = "Maybe"
    private const val VIEW = "View"
    private const val MUT_VIEW = "MutView"

    /** The key DeclarationCollector stores `@_extern("sym")`'s positional string under. */
    private const val POSITIONAL = "symbol"
}
