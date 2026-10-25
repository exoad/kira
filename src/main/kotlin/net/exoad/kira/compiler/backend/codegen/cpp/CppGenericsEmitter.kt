package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/**
 * Templates (design 5.5, W2.4).
 *
 * - A generic class, trait, struct or function is a template in the header
 *   ([CppPlacement] puts its definitions there): `template<typename T>`. A bound
 *   (`<T: Shape>`) is Kira's to check (GenericBoundsPass); the C++ carries no concept.
 * - A call names its type arguments explicitly (Kira has no inference but the
 *   `@_infer` math family, D20, whose arguments the typer records all the same):
 *   `id<std::int32_t>(7)`.
 * - A member access on a receiver of type-parameter type is `kira::deref(x).m()`, which
 *   is `x.m()` for a value and `x->m()` for a class held by `kira::Rc` (measured in
 *   probes-S/maybe.cxx, [S1]): one spelling for every instantiation.
 * - `Box<T>` is the program's own template class; the spec's `Ref<T>` is
 *   `kira::Rc<kira::Box<T>>` and `Weak<T>` is `kira::Weak<T>`, both spelled by
 *   [CppTypeSpeller], constructed by [CppClassEmitter.construct], and `upgrade()` is the
 *   `lock()` binding (`kira/result.bind.yaml`).
 */
object CppGenericsEmitter : CppGenericsPart {
    override fun templateHead(ctx: CppEmitContextImpl, params: List<TypeParamSymbol>): String? {
        if (params.isEmpty()) {
            return null
        }
        return params.joinToString(", ", prefix = "template<", postfix = ">") { "typename ${ctx.names.escape(it.name)}" }
    }

    override fun typeArguments(ctx: CppEmitContextImpl, at: ASTNode, typeArgs: List<KType>): String {
        if (typeArgs.isEmpty()) {
            return ""
        }
        return typeArgs.joinToString(", ", prefix = "<", postfix = ">") { ctx.spell(it, Pos.TEMPLATE_ARG, at) }
    }

    /** Null for any receiver that is not a type parameter, an untyped one included: the contract of [CppGenericsPart.receiver]. */
    override fun receiver(ctx: CppEmitContextImpl, receiver: Expr, text: String): String? {
        if (ctx.model.typeOrNull(receiver) !is KType.Param) {
            return null
        }
        return "kira::deref($text)"
    }
}
