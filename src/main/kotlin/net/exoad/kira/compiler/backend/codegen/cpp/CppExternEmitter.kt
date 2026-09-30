package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
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
 * part hands over the spelled receiver and arguments), spells an argument the typer converted
 * as the parameter's type (`kira::Maybe<T>(e)`, `kira::Rc<Base>(e)`: [argument]), so the call
 * is the one the check states, converts the result as above, and
 * spells the copies the copy policy made (50-round4: W2.3's `CppCopyPolicy` decides which
 * argument is copied, as it does for every call; this part only spells it with the proxy).
 * Every call whose body C++ supplies comes here (R-G): a bodiless `pub` prototype too, which
 * Kira declared itself and so is spelled and typed as the module's own function, with no
 * proxy ([proxied]).
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

    /**
     * The checks are written at global scope after the module's own declarations (the
     * declaration emitter puts them there), so a type this module declares is spelled from the
     * global namespace ([CppEmitContextImpl.atGlobalScope]): an extern taking a Kira class of
     * its own module named it bare, at the top of the header, and g++ said "'Wrap' was not
     * declared in this scope" (w2-4 round-2 minor #3, externnested2).
     */
    override fun check(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter) = ctx.atGlobalScope { checkAtGlobalScope(ctx, sym, w) }

    private fun checkAtGlobalScope(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter) {
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

    override fun call(ctx: CppEmitContextImpl, call: ResolvedCall, receiver: String?, args: List<String>, copied: Set<Int>): String {
        val fn = call.fn ?: return "/* extern call without a callee */"
        val owner = fn.owner
        // R-G (40-round3): every function whose body C++ supplies comes here, not only an
        // `@_extern` one. A bodiless `pub` prototype has no marker: Kira declared it in this
        // module's header with its own C++ types, so it is spelled as the module spells its
        // functions and its result is already the declared type (round 2 lowered its call as a
        // Kira call, and `peek(w, v)` / `plen(loc)` failed in g++: 'cannot convert
        // kira::View<int> to const int32_t*', 'cannot convert kira::Str to const char*').
        val marked = externOf(fn) != null
        val proxy = proxied(fn)
        val callee = when {
            owner == null || receiver == null -> if (marked) globalName(fn) else ctx.qualified(fn)
            else -> receiver + accessor(owner) + cppName(fn)
        }
        // An extern parameter has no Kira default (functionCheck refuses one), so every binding
        // here is a given argument; a Default would be a default the C++ side never sees. A
        // prototype Kira declared carries its defaults in its own C++ declaration.
        if (marked) {
            call.args.filterIsInstance<ArgBinding.Default>().firstOrNull()?.let { d ->
                (d.param.default ?: fn.decl)?.let { ctx.unsupported(it, "the default of parameter '${d.param.name}' of the extern function '${fn.name}'") }
            }
        }
        // Whether an extern's result, a `mut` argument, or a `mut fx`'s mutated receiver could
        // hand the caller a fresh pointer into a buffer this call itself built (a computed `Str`
        // wrapped by kira::ffi::in/CStrBuf, alive only to this full-expression's end) was
        // rounds 1-4's `carriesPointer`/`pointerEscapes`, chasing an ever-growing list of shapes
        // (a `Maybe`, a class field, then `View`/`MutView`, then a `mut` argument or receiver -
        // and still missing a collection of pointers, `Unsafe<T>`'s own pointee, and an `Fx`
        // result or callback parameter: converge-result.json's three open issues here). Decision
        // 4b (30-second-class.md) removes the need to chase that list in this emitter: an
        // extern's result, `mut` argument and receiver can never carry a pointer at all any more
        // (5.2, 5.3, refused at the declaration by ViewPass's `rules.view.extern`/`rules.view.type`),
        // so a temporary `Str` buffer fed to any argument of this call is safe for the call's own
        // full-expression, whatever the call hands back.
        //
        // Which argument is copied is the copy policy's (50-round4 1.2, 2.3): W2.3's
        // `CppCopyPolicy` asks the same question for every call, an extern's included (W3's
        // extern row is `!CallReach.mayRunAnything`, and its overlap test reads `Rules.mayHold`
        // both ways against every own `mut` operand: w2-6 #0), and hands the answer over as
        // [copied]. Round 3's R-B (`copiedArguments`) asked it here, one way only, and is gone.
        // What lets the policy lend to an extern at all is contract 5.4: an extern writes only
        // its `mut` arguments and receiver, and runs a Kira `Fx` only during a call that is given
        // it or given something that may hold it (a handle to the C++ object that keeps it,
        // which as a class or opaque receiver or argument always counts); a C++ callback
        // registry is reached through such a handle, never through a free function taking none.
        val texts = args.mapIndexed { i, text -> argument(ctx, fn.params.getOrNull(i), call.args.getOrNull(i), text, i in copied, proxy) }
        val text = "$callee(${texts.joinToString(", ")})"
        return if (marked) declared(ctx, fn.ret, text) else text
    }

    /**
     * Whether a call of [fn] passes its arguments through `kira::ffi`'s proxies (`in`, `out`):
     * C++ declared its signature, which the proxies are written to meet whatever it is (a
     * `const std::string&`, a `std::string_view`, a `const char*`; a `T&` or a `T*`). That is an
     * `@_extern` function or member, and a method of an `@_opaque` class (the C++ class Kira
     * names only as `C*`). A bodiless `pub` prototype is the other callee C++ supplies (R-G), but
     * Kira declares it, in its module's header, in Kira's own C++ types (`const kira::Str&`, `T&`
     * for `mut`, `const char*` for `CStr`, `const T*` for `Unsafe<T>`): its arguments are the
     * module's own spelling, and a prototype's module need not include `kira/ffi.hxx` at all
     * (round 3's t13: `::w::lenS2(kira::ffi::in(loc))` in a module with no `@_extern` failed on
     * g++, clang and MSVC with "'kira::ffi' has not been declared").
     */
    fun proxied(fn: FnSymbol): Boolean = externOf(fn) != null || (fn.owner as? ClassSymbol)?.kind == ClassKind.OPAQUE

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
     * A call's result is a prvalue (50-round4 2.0, W1) when this part makes it one: an
     * `@_extern` result converted by `kira::ffi::declared<T>`, which returns a `T` by value
     * ([declared]), and a bodiless `pub` prototype's, which Kira declared returning by value.
     * So `car.scan().ahead()` is the method of a temporary, never copied again (forward's
     * golden). A result kept as C++ gave it (a pointer, or an `@_opaque` method's, whose
     * signature nothing checks) may be a reference into storage and is no prvalue.
     */
    override fun resultIsTemporary(call: ResolvedCall): Boolean {
        val fn = call.fn ?: return false
        return when {
            externOf(fn) != null -> !keepsCppType(fn.ret)
            else -> !proxied(fn)
        }
    }

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
     * One argument as the extern call passes it: `(text).data()` for an `Unsafe<T>` parameter
     * given a `View<T>` or `MutView<T>` argument (design 1.4, 5.5 - `CallResolver.typeGiven`
     * accepts the mismatch only at an extern call, recording nothing beyond the argument's own
     * `View`/`MutView` type, exactly as the `Str`-for-`CStr` case below needs no coercion
     * either), the bare text otherwise (an `Unsafe<T>` argument that already is one - the
     * exact-type case a bare or `mut p: Unsafe<T>` local still takes directly, table 5.1 - or a
     * struct's `Unsafe<T>` field); `kira::ffi::out(x)` for any other `mut` parameter;
     * `kira::ffi::in(s)` for a `Str` one; and for a `CStr` parameter given a `Str` (7.2): a
     * literal passes through, a place the copy policy lends becomes `.c_str()` of its own
     * buffer however it is spelled (`label`, `gp.name`, `kira::at(gstrs, 0)`: round 3 gave every
     * spelling but a bare name a `CStrBuf`, a copy with no writer, w2-6 minor #0), anything
     * else `kira::ffi::CStrBuf(expr).c_str()`, which lives to the end of the full-expression. A
     * `Str` that is a Kira `Str` constant is already a `const char*` (D12: `inline constexpr
     * const char*`), so it passes through as the literal does; an extern `Str` constant is read
     * as a `kira::Str` made from whatever C++ declared ([constant]: a `std::string` or a `const
     * char*` both pass its check), a temporary, so it takes the buffer.
     *
     * First of all, an argument the typer converted ([convertsToParam]: a `WrapSome`, `NoneOf`,
     * `Upcast` or `FnRef`) is spelled as the parameter's type, `P(text)`, so the call passes the
     * type its check states (`kira::ffi::arg<P>()`, `std::declval<const P&>()`) and resolves the
     * same overload. Left to C++, the conversion happens only where the C++ parameter is that
     * type: an overload set took the unconverted value (`pick(gs)` against `pick(const
     * std::optional<std::string>&)` and `pick(const std::string&)` printed 247 for 147, on g++,
     * clang and MSVC), and a template bound the Kira storage itself (`lenAfter(gls[0], f)` read
     * the element after `f` replaced the List: -1971355728 for 47 on g++, a heap-use-after-free
     * under MSVC ASan; `nameLenAfter(h.kid, f)` with a `Base` parameter bound the `Kid` slot and
     * printed 1 for 47). The copy policy counts the conversion as a temporary (W1,
     * `CppCopyPolicy.converts`), and `P(text)` is that temporary: a `Maybe` or a handle made
     * from the value at the call, which holds its own copy (a `Str`, a list) or the object (a
     * class) for the whole call. A bodiless prototype declares `P` itself (`const
     * kira::Maybe<T>&`, `const kira::Rc<Base>&`), so C++ converts at its call as at a Kira
     * function's, and its text is left as it is.
     *
     * A literal is the same case with no coercion recorded ([typedLiteral]): the typer gives
     * `5` the parameter's type, but C++ reads it as an `int`, so the call and the check were
     * two calls again (round 6: `w(5)` against `w(std::int32_t)` and `w(std::int64_t)`
     * printed 32 for 64 on g++, clang and MSVC, and a `width(T)` template 4 for 8). Every
     * literal argument is spelled as the parameter's type: `std::int64_t{5}`, and
     * `static_cast<const char*>("s")` for a `Str` literal at a `CStr`.
     *
     * [proxied] is false for a bodiless `pub` prototype ([proxied]): Kira declared its C++
     * parameters itself, so a `Str` is passed as the `kira::Str` it is and a `mut` argument as
     * the `T&` it binds, with no `kira::ffi::in`/`out`; its `Unsafe<T>` and `CStr` parameters
     * are the same pointers as an extern's, so `.data()`, `.c_str()` and `CStrBuf` stay.
     *
     * A temporary `Str` buffer built here for a `Str`/`CStr` argument (`kira::ffi::in`,
     * `kira::ffi::CStrBuf`) needs no check against what this call might hand back any more
     * ([call]'s doc, rounds 1-4's `carriesPointer`/`pointerEscapes`): an extern's result, `mut`
     * argument and receiver can never carry a pointer (5.2, 5.3), so the buffer's own lifetime -
     * to the end of the call's own full-expression - is always long enough, whatever a literal,
     * a named `Str`, a constant or a computed expression builds it from.
     *
     * When [copy] (the copy policy's rule 6: the argument is no temporary and no whitelisted
     * lend) it is handed over as a copy made at the call instead of its own storage:
     * `kira::ffi::in(kira::Str(text))` for a `Str`, `kira::ffi::CStrBuf(text).c_str()` for a
     * `Str` given to a `CStr` (CStrBuf holds its own `std::string`), and `T(text)` for any other
     * by-reference type (`kira::List<std::int32_t>(gl)`, `kira::Rc<ns::C>(h)`, a value class's
     * copy constructor). Each copy lives to the end of the call's full-expression.
     */
    private fun argument(ctx: CppEmitContextImpl, p: ParamSymbol?, binding: ArgBinding?, text: String, copy: Boolean, proxied: Boolean): String {
        if (p == null) {
            return text
        }
        val expr = (binding as? ArgBinding.Given)?.expr
        if (proxied && expr != null && convertsToParam(ctx.model.coercion(expr))) {
            return "${ctx.spell(p.type, Pos.VALUE)}($text)"
        }
        if (proxied && expr != null && !p.byRef) {
            typedLiteral(ctx, p.type, expr, text)?.let { return it }
        }
        if (isUnsafe(p.type)) {
            val given = expr?.let { ctx.model.types[it] }
            val viewLike = given != null && (isMagic(given, VIEW) || isMagic(given, MUT_VIEW))
            // Design 1.4: an Arr/List place or a Str literal reaches Unsafe<T> through
            // Coercion.ToView (CallResolver.typeGiven), the same conversion an ordinary
            // View<T> parameter takes; whatever wraps that coercion into a view (`kira::view`)
            // has already run by the time [text] reaches here, so `.data()` is all this adds.
            val toView = expr != null && ctx.model.coercion(expr) is Coercion.ToView
            return if (viewLike || toView) "($text).data()" else text
        }
        if (p.byRef) {
            return if (proxied) "kira::ffi::out($text)" else text
        }
        if (p.type == KType.Str) {
            val s = if (copy) "kira::Str($text)" else text
            return if (proxied) "kira::ffi::in($s)" else s
        }
        if (isCStr(p.type)) {
            if (expr == null || ctx.model.types[expr] != KType.Str) {
                return text
            }
            val global = globalOf(ctx, expr)
            return when {
                copy -> "kira::ffi::CStrBuf($text).c_str()"
                expr is StringLiteral -> text
                global != null && externOf(global) != null -> "kira::ffi::CStrBuf($text).c_str()"
                global != null && global.isConstant && !global.isMut -> text
                ctx.model.readPlace(expr) != null -> "${postfix(text)}.c_str()"
                else -> "kira::ffi::CStrBuf($text).c_str()"
            }
        }
        if (copy) {
            return "${ctx.spell(p.type, Pos.VALUE)}($text)"
        }
        return text
    }

    /**
     * Whether [c], the typer's coercion at an argument, makes a value of another C++ type, which
     * [argument] spells as the parameter's type `P(e)`: a `WrapSome` (`kira::Maybe<T>(e)`, or the
     * nullable `kira::Rc<Base>(e)` of a class), a `NoneOf` (`kira::Maybe<T>(kira::none)`), an
     * `Upcast` (`kira::Rc<Base>(e)`, `kira::Rc<Trait>(e)`) and an `FnRef` (`kira::Fn<...>(f)`).
     * A `ToView` is spelled by the expression part already (`kira::view`), and the `.data()` an
     * `Unsafe<T>` takes of it is [argument]'s.
     */
    private fun convertsToParam(c: Coercion?): Boolean =
        c is Coercion.WrapSome || c is Coercion.NoneOf || c is Coercion.Upcast || c is Coercion.FnRef

    /**
     * [text], the C++ of [e], spelled as the parameter type [t] when [e] is a literal ([isLiteral])
     * at a scalar parameter (`std::int64_t{5}`, `float{1.5f}`, `bool{true}`) or a `Str` literal at
     * a `CStr` one (`static_cast<const char*>("s")`, not the `const char[2]` a `const T&`
     * template would bind); null for any other argument. The braces refuse a narrowing C++ would
     * otherwise make silently. On arm-none-eabi even an `Int32` needs this: `std::int32_t` is
     * `long` there, and `5` is an `int`.
     */
    private fun typedLiteral(ctx: CppEmitContextImpl, t: KType, e: Expr, text: String): String? = when {
        t is KType.Scalar && isLiteral(ctx, e) -> "${ctx.spell(t, Pos.VALUE)}{$text}"
        isCStr(t) && e is StringLiteral -> "static_cast<${ctx.spell(t, Pos.VALUE)}>($text)"
        else -> null
    }

    /**
     * Whether [e] is built of literals only: a number, a character, `true` or `false`, or a sign,
     * `!`, `~` or arithmetic over such operands (`-5`, `2 + 3`). Its C++ type is then C++'s own
     * (`int`, `double`, `char`), whatever type the typer gave it.
     */
    private fun isLiteral(ctx: CppEmitContextImpl, e: Expr): Boolean = when (e) {
        is IntegerLiteral, is FloatLiteral, is CharLiteral -> true
        is UnaryExpr -> isLiteral(ctx, e.operand)
        is BinaryExpr -> e.operator in CppLowering.ARITH_OR_BITS && isLiteral(ctx, e.leftExpr) && isLiteral(ctx, e.rightExpr)
        is Identifier -> (ctx.model.symbolOf(e) as? GlobalSymbol)?.let { g ->
            g.foreign is Foreign.Magic && g.module.isStdlib && (g.name == "true" || g.name == "false")
        } == true
        else -> false
    }

    /** The global [e] names, bare (`GS`) or through its module (`w.GS`), or null. */
    private fun globalOf(ctx: CppEmitContextImpl, e: Expr): GlobalSymbol? = when (e) {
        is IntrinsicExpr -> null
        is Identifier -> ctx.model.symbolOf(e) as? GlobalSymbol
        is MemberAccessExpr -> (ctx.model.member(e) as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol
        else -> null
    }

    /** [text] as the operand of a member access: itself when it is a name or a chain of member accesses, else parenthesized. */
    private fun postfix(text: String): String = if (MEMBER_CHAIN.matches(text)) text else "($text)"

    private val MEMBER_CHAIN = Regex("(::)?[A-Za-z_][A-Za-z0-9_]*((::|\\.|->)[A-Za-z_][A-Za-z0-9_]*)*")

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

    /**
     * Whether [call] is one the expression part must hand to [CppExternEmitter.call]: its
     * callee's body C++ supplies (R-G, [suppliedByCpp]). The typer gives every such call
     * [net.exoad.kira.compiler.analysis.types.CallKind.EXTERN], which is what the expression
     * part dispatches on; the two agree by construction and a test pins it.
     */
    fun isExternCall(call: ResolvedCall): Boolean = call.fn?.suppliedByCpp == true

    private const val CSTR = "CStr"
    private const val UNSAFE = "Unsafe"
    private const val MAYBE = "Maybe"
    private const val VIEW = "View"
    private const val MUT_VIEW = "MutView"

    /** The key DeclarationCollector stores `@_extern("sym")`'s positional string under. */
    private const val POSITIONAL = "symbol"
}
