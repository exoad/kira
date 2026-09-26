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

/**
 * `@_extern` for the C++ backend (design 7.2): an extern module emits checks, not
 * declarations. The C++ declarations already exist in the headers `header =` names; a second
 * copy would be the drift the memory note "comments that claim other files" warns about, so
 * the module's header includes the real headers, then `kira/ffi.hxx`, and states one
 * `KIRA_EXTERN_CHECK` per declared member: an unevaluated call whose result must convert to
 * the type Kira declared. Overloads, C++ default arguments and the argument proxies all
 * resolve in that call, and a wrong Kira signature fails with Kira's message on g++, clang
 * and MSVC (probes-S/externcheck.cxx).
 *
 * The checks follow section 7.2 literally, at global scope: names as the marker spells them
 * (`bibo::Car`, never `::bibo::Car`), a non-mut method through a `const` receiver, a `Void`
 * method as `(call, 0)` against `int`, a `Str` parameter as `kira::ffi::in(...)`, a `mut`
 * parameter as `kira::ffi::out(...)`. A struct declared with fields also gets a layout
 * struct of the same fields and a `static_assert` on `sizeof`, and one check per field.
 *
 * At a call, [call] spells the C++ name and the proxies (the expression part hands over the
 * spelled receiver and arguments); an extern constant is read by its C++ name ([constant]).
 */
object CppExternEmitter : CppExternsPart {
    /** The runtime header the proxies and the check macro live in. */
    const val FFI_HEADER = "kira/ffi.hxx"

    /** The macro of `kira/ffi.hxx` every check states. */
    const val CHECK = "KIRA_EXTERN_CHECK"

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
     * The C++ name the marker gives [sym]: `cpp =`, else the positional symbol, else the Kira
     * name (a method of an extern class with no marker of its own is the C++ member of the
     * same name). Spelled as the marker wrote it, without a leading `::`.
     */
    fun cppName(sym: Symbol): String {
        val params = externOf(sym)?.params ?: return sym.name
        return params["cpp"] ?: params["symbol"] ?: sym.name
    }

    /** [cppName] as an expression names it from anywhere: with a leading `::`. */
    fun globalName(sym: Symbol): String = global(cppName(sym))

    private fun global(name: String): String = if (name.startsWith("::")) name else "::$name"

    /** The `header =` of [sym]'s marker, or null. */
    fun headerOf(sym: Symbol): String? = externOf(sym)?.params?.get("header")

    // ---- includes and checks (the declaration emitter calls these) ---------------------------------

    override fun includes(ctx: CppEmitContextImpl, sym: Symbol): List<String> {
        val out = LinkedHashSet<String>()
        headerOf(sym)?.let(out::add)
        if (sym is ClassSymbol) {
            sym.methods.forEach { m -> headerOf(m)?.let(out::add) }
        }
        return out.toList()
    }

    override fun check(ctx: CppEmitContextImpl, sym: Symbol, w: CppWriter) {
        ctx.includeInHeader(FFI_HEADER)
        includes(ctx, sym).filter { it.startsWith("<") }.forEach { header ->
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
     * has the same size, then one check per field: the field exists, and reads as its Kira type.
     * The twin is a flat aggregate of the same fields in the same order, so it pads as the
     * C++ one does; a sum of sizes would not.
     */
    private fun layoutChecks(ctx: CppEmitContextImpl, cls: ClassSymbol, w: CppWriter) {
        val name = cppName(cls)
        val twin = "${ctx.namespace}::$LAYOUT_NAMESPACE::${ctx.names.escape(cls.name)}"
        val fields = cls.fields.joinToString(" ") { f -> "${checkType(ctx, f.type, Pos.FIELD)} ${ctx.names.escape(f.name)};" }
        w.line("namespace ${ctx.namespace}::$LAYOUT_NAMESPACE { struct ${ctx.names.escape(cls.name)} { $fields }; }")
        w.line("static_assert(sizeof($name) == sizeof($twin), \"Kira's ${cls.name} $DRIFT_MESSAGE\");")
        cls.fields.forEach { f ->
            w.line("$CHECK(std::declval<$name&>().${fieldName(f)}, ${checkType(ctx, f.type, Pos.FIELD)}, \"${cls.name}.${f.name}\");")
        }
    }

    private fun fieldName(f: FieldSymbol): String = f.name

    private fun functionCheck(ctx: CppEmitContextImpl, fn: FnSymbol, owner: ClassSymbol?, w: CppWriter) {
        if (fn.typeParams.isNotEmpty()) {
            fn.decl?.let { ctx.unsupported(it, "the generic extern function '${fn.name}'") }
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

    /** One argument of a check: the proxy the call would pass, over a `std::declval` of the Kira type. */
    private fun checkArg(ctx: CppEmitContextImpl, p: ParamSymbol): String = when {
        p.byRef -> "kira::ffi::out(std::declval<${checkType(ctx, p.type, Pos.VALUE)}&>())"
        p.type == KType.Str -> "kira::ffi::in(std::declval<const kira::Str&>())"
        else -> "std::declval<${checkType(ctx, p.type, Pos.PARAM)}>()"
    }

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
        val texts = args.mapIndexed { i, text -> argument(ctx, fn.params.getOrNull(i), call.args.getOrNull(i), text) }
        return "$callee(${texts.joinToString(", ")})"
    }

    /** `->` for a class or an opaque handle (an `Rc` or a pointer), `.` for a struct (a value). */
    private fun accessor(owner: TypeSymbol): String = when {
        owner is ClassSymbol && owner.isStruct -> "."
        else -> "->"
    }

    /**
     * One argument as the extern call passes it: `kira::ffi::out(x)` for a `mut` parameter,
     * `kira::ffi::in(s)` for a `Str` one, and for a `CStr` parameter given a `Str` (7.2): a
     * literal passes through, a named `Str` becomes `.c_str()`, anything else
     * `kira::ffi::CStrBuf(expr).c_str()`, which lives to the end of the full-expression.
     */
    private fun argument(ctx: CppEmitContextImpl, p: ParamSymbol?, binding: ArgBinding?, text: String): String {
        if (p == null) {
            return text
        }
        if (p.byRef) {
            return "kira::ffi::out($text)"
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
                expr is Identifier && expr !is IntrinsicExpr -> "$text.c_str()"
                else -> "kira::ffi::CStrBuf($text).c_str()"
            }
        }
        return text
    }

    /** `CStr`, the FFI `const char*` (design 7.2, a magic class of the builtins). */
    private fun isCStr(t: KType): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.MAGIC && sym.name == CSTR
    }

    override fun constant(ctx: CppEmitContextImpl, sym: GlobalSymbol): String = globalName(sym)

    /** Whether [call] is one the expression part must hand to [CppExternEmitter.call]: its callee is extern. */
    fun isExternCall(call: ResolvedCall): Boolean = call.fn?.foreign is Foreign.Extern

    private const val CSTR = "CStr"
}
