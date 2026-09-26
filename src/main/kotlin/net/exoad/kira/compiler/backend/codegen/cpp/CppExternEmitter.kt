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
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
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
 * and a constant's read are `static_cast<T>(...)` unless T is a scalar (proved exact by the
 * check), `Void`, or a pointer (`Unsafe<T>`, `CStr`, an opaque handle: the check lets only a
 * qualification differ). A C++ `const char* name()` declared `name: () Str` passes its
 * check, and left as the call's own type `name() == ABC` compared two pointers where Kira
 * compares a Str by value (measured: 0 for Kira's 1, on g++, clang and MSVC with -Werror
 * silent); `static_cast<kira::Str>(::probe::name()) == ABC` compares the text.
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

    /** [cppName] as an expression names it from anywhere: with a leading `::`. */
    fun globalName(sym: Symbol): String = global(cppName(sym))

    private fun global(name: String): String = if (name.startsWith("::")) name else "::$name"

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
        val texts = args.mapIndexed { i, text -> argument(ctx, fn.params.getOrNull(i), call.args.getOrNull(i), text) }
        return declared(ctx, fn.ret, "$callee(${texts.joinToString(", ")})")
    }

    /**
     * [text], a C++ value the check proved matches the Kira type [t], as that type: itself
     * when [t] is `Void`, a scalar (the check is exact there) or a pointer (`Unsafe<T>`,
     * `CStr`, an opaque handle: only a qualification could differ, and a pointer's operations
     * are the same either way); `static_cast<T>(text)` otherwise, since what is_convertible
     * let through is not yet the declared type. A `const char*` result declared `Str` is the
     * case that bit: `name() == ABC` compared pointers (measured, 0 for Kira's 1), and a
     * `std::optional<const char*>` declared `Maybe<Str>` would do the same one level down.
     * A result of the declared type itself is a prvalue and the cast is elided.
     */
    private fun declared(ctx: CppEmitContextImpl, t: KType, text: String): String =
        if (keepsCppType(t)) text else "static_cast<${ctx.spell(t, Pos.VALUE)}>($text)"

    private fun keepsCppType(t: KType): Boolean =
        t == KType.Void || t == KType.Never || t is KType.Scalar || isUnsafe(t) || isCStr(t) || isOpaque(t)

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
     */
    private fun argument(ctx: CppEmitContextImpl, p: ParamSymbol?, binding: ArgBinding?, text: String): String {
        if (p == null) {
            return text
        }
        if (p.byRef) {
            return if (isUnsafe(p.type)) text else "kira::ffi::out($text)"
        }
        if (p.type == KType.Str) {
            return "kira::ffi::in($text)"
        }
        if (isCStr(p.type)) {
            val expr = (binding as? ArgBinding.Given)?.expr ?: return text
            if (ctx.model.types[expr] != KType.Str) {
                return text
            }
            return when {
                expr is StringLiteral -> text
                expr is Identifier && expr !is IntrinsicExpr -> when (val sym = ctx.model.symbolOf(expr)) {
                    is GlobalSymbol -> when {
                        externOf(sym) != null -> "kira::ffi::CStrBuf($text).c_str()"
                        sym.isConstant -> text
                        else -> "$text.c_str()"
                    }
                    else -> "$text.c_str()"
                }
                else -> "kira::ffi::CStrBuf($text).c_str()"
            }
        }
        return text
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

    /** The key DeclarationCollector stores `@_extern("sym")`'s positional string under. */
    private const val POSITIONAL = "symbol"
}
