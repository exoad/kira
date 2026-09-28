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
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
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
        // A separate hazard 4b does not close: contract 5.4's point 3 lets an extern run Kira
        // code through the `Fx` arguments it is given, and that code may reassign a `Str` place
        // (a `mut` global, a field through a `Ref` or a handle, a local through a `mut` param)
        // this same call also passes by name. `argument` binds a plain `Str` parameter straight
        // to the place's own storage (`kira::ffi::in(place)`, a `const std::string&`), which Kira
        // passes by value; a reassignment inside the `Fx` frees that storage while the C++
        // parameter still points at it. Measured on the trial CLI (round-1 verdict, probes u5/u8):
        // `lenAfter(gs, fx() Void { gs = "" })` with `gs: mut Str` global printed 0 for 82 (g++,
        // clang), MSVC ASan heap-use-after-free in the callee reading the freed buffer;
        // `lenAfter(r.value, fx() Void { r.value = "" })` with `r: Ref<Str>` the same. [reenters]
        // is conservative (`mayHoldFx`): true when any given argument's type is an `Fx`, or may
        // hold one through value composition (an `Arr`/`List`/.../`Maybe`/a `TupleN`/a struct's
        // fields) or through any reference type (a class, a trait, `Ref`, `Weak`), which cannot be
        // seen through. When it holds, every `Str` argument that is a place ([argument] reads
        // `ctx.model.places`) is copied first, never bound to the place itself; a temporary or a
        // literal is unaffected (nothing else can name it to write it back).
        val reenters = call.args.any { b -> (b as? ArgBinding.Given)?.expr?.let { mayHoldFx(ctx.model.types[it]) } == true }
        val texts = args.mapIndexed { i, text -> argument(ctx, fn.params.getOrNull(i), call.args.getOrNull(i), text, reenters) }
        return declared(ctx, fn.ret, "$callee(${texts.joinToString(", ")})")
    }

    /**
     * Whether a value of type [t] is an `Fx`, or may hold one where this walk cannot see past
     * it: by value, through an `Arr`, `List`, `Map`, `Set`, `Deque`, `Stack`, `Queue`, `Maybe`,
     * `Result` or a `TupleN`'s type arguments, or a struct's fields (cycle-guarded by [seen]);
     * or through any reference type this call's static type does not fix the contents of - a
     * class, a trait value, `Ref<T>` or `Weak<T>` (the same grouping `TypeFacts.isReference`
     * uses) - which may reach any object, including one holding a closure elsewhere. `View`,
     * `MutView`, `Unsafe` and `CStr` hold no `Fx` (they are raw pointers/spans, not values). A
     * generic `T` is unknown, so it counts as holding one.
     */
    private fun mayHoldFx(t: KType?, seen: MutableSet<ClassSymbol> = HashSet()): Boolean = when (t) {
        null -> false
        is KType.Fn -> true
        is KType.Param -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> true
                ClassKind.STRUCT -> if (!seen.add(sym)) false else t.typeArgsSubstitutedFields().any { mayHoldFx(it, seen) }
                ClassKind.MAGIC -> when (sym.name) {
                    UNSAFE, VIEW, MUT_VIEW, CSTR -> false
                    "Ref", "Weak" -> true
                    else -> t.typeArgs().any { mayHoldFx(it, seen) }
                }
            }
            is TraitSymbol -> true
            else -> false
        }
        else -> false
    }

    /** [t]'s fields' types, its own type arguments substituted for its type parameters. */
    private fun KType.Nominal.typeArgsSubstitutedFields(): List<KType> {
        val cls = sym as ClassSymbol
        val sub = cls.typeParams.zip(typeArgs()).toMap()
        return cls.fields.map { it.type.substitute(sub) }
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
     * One argument as the extern call passes it: `(text).data()` for an `Unsafe<T>` parameter
     * given a `View<T>` or `MutView<T>` argument (design 1.4, 5.5 - `CallResolver.typeGiven`
     * accepts the mismatch only at an extern call, recording nothing beyond the argument's own
     * `View`/`MutView` type, exactly as the `Str`-for-`CStr` case below needs no coercion
     * either), the bare text otherwise (an `Unsafe<T>` argument that already is one - the
     * exact-type case a bare or `mut p: Unsafe<T>` local still takes directly, table 5.1 - or a
     * struct's `Unsafe<T>` field); `kira::ffi::out(x)` for any other `mut` parameter;
     * `kira::ffi::in(s)` for a `Str` one; and for a `CStr` parameter given a `Str` (7.2): a
     * literal passes through, a named `Str` becomes `.c_str()`, anything else
     * `kira::ffi::CStrBuf(expr).c_str()`, which lives to the end of the full-expression. A named
     * `Str` that is a Kira `Str` constant is already a `const char*` (D12: `inline constexpr
     * const char*`), so it passes through as the literal does; an extern `Str` constant is read
     * as a `kira::Str` made from whatever C++ declared ([constant]: a `std::string` or a `const
     * char*` both pass its check), a temporary, so it takes the buffer.
     *
     * A temporary `Str` buffer built here for a `Str`/`CStr` argument (`kira::ffi::in`,
     * `kira::ffi::CStrBuf`) needs no check against what this call might hand back any more
     * ([call]'s doc, rounds 1-4's `carriesPointer`/`pointerEscapes`): an extern's result, `mut`
     * argument and receiver can never carry a pointer (5.2, 5.3), so the buffer's own lifetime -
     * to the end of the call's own full-expression - is always long enough, whatever a literal,
     * a named `Str`, a constant or a computed expression builds it from.
     *
     * `kira::ffi::in(text)` for a plain `Str` parameter binds a `const std::string&` straight to
     * [text]'s own storage when [text] is a place - no copy, since Kira passes a `Str` by value
     * and the callee only reads it for the call. That is unsound when [reenters] ([call]'s doc):
     * the extern may run Kira code through an `Fx` argument that reassigns the very place this
     * argument names, freeing the buffer the C++ parameter still points at (measured, round-1
     * verdict). So when [reenters] and [expr] is a place (`ctx.model.places`),
     * the argument is copied first, `kira::ffi::in(kira::Str(text))`: a fresh `kira::Str` a
     * reassignment elsewhere cannot reach. A literal or a computed expression is already a
     * temporary nothing else can name, so it is unaffected either way.
     */
    private fun argument(ctx: CppEmitContextImpl, p: ParamSymbol?, binding: ArgBinding?, text: String, reenters: Boolean = false): String {
        if (p == null) {
            return text
        }
        val expr = (binding as? ArgBinding.Given)?.expr
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
            return "kira::ffi::out($text)"
        }
        if (p.type == KType.Str) {
            return if (reenters && expr != null && ctx.model.places[expr] != null) "kira::ffi::in(kira::Str($text))" else "kira::ffi::in($text)"
        }
        if (isCStr(p.type)) {
            if (expr == null || ctx.model.types[expr] != KType.Str) {
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
    private const val VIEW = "View"
    private const val MUT_VIEW = "MutView"

    /** The key DeclarationCollector stores `@_extern("sym")`'s positional string under. */
    private const val POSITIONAL = "symbol"
}
