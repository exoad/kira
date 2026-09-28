package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.constArgs
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import java.util.IdentityHashMap

/**
 * The freestanding profile (design section 10, D22): a module matched by
 * `build.cpp.freestanding`, and every module it `use`s, stays within the Pico subset. Each
 * refusal names the replacement.
 *
 * - `rules.profile.type`: a `Str`, a `List`/`Map`/`Set`/`Deque`/`Stack`/`Queue`, an `Arr<T>`
 *   without a count, a `Ref`/`Weak`/`Unsafe`, a class reference, a trait-typed value, or an
 *   `Fx` held as a value (a local, a field, a return: a std::function). Reported once, at the
 *   innermost type spelled.
 * - `rules.profile.fx`: an `Fx` parameter that escapes (EscapePass), so it cannot be a
 *   template parameter.
 * - `rules.profile.class`: a `class` declared in the module (a heap reference).
 * - `rules.profile.try`: `try` (a `throw` becomes `kira::panic`, D41).
 * - `rules.profile.interpolation`: `"${...}"` producing a `Str`; into a `StrBuf` it appends in place.
 * - `rules.profile.type` again for a `Str` concatenation (`"a" + b`): it allocates a `kira::Str`
 *   even when no `Str` is spelled, so it is reported at the innermost `+` that builds one.
 * - `rules.profile.module`: `kira:os`, `kira:sync` and `kira:time` are hosted.
 */
internal class ProfilePass : RulePass {
    override val name: String = "profile"

    private val hosted = setOf("kira:os", "kira:sync", "kira:time")

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val freestanding = program.modules.filter { it.profile == Profile.FREESTANDING }
        if (freestanding.isEmpty()) {
            return
        }
        val targets = LinkedHashSet<ModuleSymbol>()
        for (m in freestanding) {
            targets.add(m)
            targets.addAll(program.graph.transitiveUses(m))
        }
        for (m in targets) {
            if (!m.isStdlib) {
                module(r, m)
            }
        }
    }

    private fun module(r: Rules, m: ModuleSymbol) {
        val model = r.model
        for (use in m.uses) {
            if (use.uri.value in hosted) {
                r.report("rules.profile.module", "${use.uri.value} is hosted (threads, sockets, clocks): a freestanding module cannot use it.", use)
            }
        }
        // The Type nodes that spell a parameter's type, by their parameter (an Fx there is a template parameter unless it escapes).
        val paramTypes = IdentityHashMap<Type, ParamSymbol>()
        // A declaration's own name, and a type parameter's bound (`<T: Shape>`: traits as bounds are allowed).
        val notValues = IdentityHashMap<ASTNode, Boolean>()
        AstTree.walk(m.source.ast) { n ->
            when (n) {
                is FunctionDeclParameterExpr -> (model.declSyms[n] as? ParamSymbol)?.let { paramTypes[n.typeSpecifier] = it }
                is Decl -> notValues[n.name] = true
                else -> {}
            }
            if (n is Type) {
                n.constraint?.let { bound -> AstTree.walk(bound) { notValues[it] = true } }
            }
        }
        AstTree.walk(m.source.ast) { n ->
            when (n) {
                is ClassDecl -> {
                    val cls = model.declSyms[n] as? ClassSymbol
                    if (cls != null && cls.kind == ClassKind.CLASS) {
                        r.report("rules.profile.class", "A class is a heap reference (an Rc), which the Pico profile has no allocator for: make ${cls.name} a struct.", n.name)
                    }
                }
                is Type -> if (n !is ConstTypeArg && !notValues.containsKey(n)) typeNode(r, n, paramTypes[n])
                is TryExpr -> r.report("rules.profile.try", "try is not available freestanding: a throw becomes kira::panic (D41), which never returns.", n)
                is InterpolatedStringLiteral -> if (model.types[n] == KType.Str) {
                    r.report(
                        "rules.profile.interpolation",
                        "Interpolation into a Str allocates: interpolate into a StrBuf<N> instead (buf.set(\"...\") appends in place).",
                        n,
                    )
                }
                is FunctionCallExpr -> call(r, n)
                is BinaryExpr -> if (model.types[n] == KType.Str && model.opCalls[n] == null) {
                    // Reported once per chain, at the innermost `+` that builds a Str.
                    val innerReported = listOf(n.leftExpr, n.rightExpr).any { it is BinaryExpr && model.types[it] == KType.Str }
                    if (!innerReported) {
                        r.report(
                            "rules.profile.type",
                            "Str concatenation allocates (a kira::Str), which the Pico profile refuses: append into a StrBuf<N> instead (buf.set(\"...\") appends in place).",
                            n,
                        )
                    }
                }
                is Identifier -> if (n !is IntrinsicExpr) hostedSymbol(r, model.refs[n], n)
                else -> {}
            }
        }
    }

    private fun call(r: Rules, e: FunctionCallExpr) {
        val rc = r.model.calls[e] ?: return
        rc.fn?.let { hostedSymbol(r, it, (e.name as? net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr)?.member ?: e.name) }
        val why = refusal(r, rc.returnType) ?: return
        val operands = listOfNotNull(rc.receiver) + e.positionalParameters.map { it.value } + e.namedParameters.map { it.value }
        if (operands.any { o -> r.model.types[o]?.let { refusal(r, it) } != null }) {
            return
        }
        r.report("rules.profile.type", "${rc.fn?.name ?: "This call"} returns ${rc.returnType.display()}, which the Pico profile refuses: $why", e)
    }

    private fun hostedSymbol(r: Rules, sym: Symbol?, at: ASTNode) {
        if (sym == null || sym.module.uri !in hosted) {
            return
        }
        r.report("rules.profile.module", "'${sym.name}' is from ${sym.module.uri}, which is hosted (threads, sockets, clocks): not available freestanding.", at)
    }

    private fun typeNode(r: Rules, node: Type, param: ParamSymbol?) {
        val t = r.model.typeRefs[node] ?: return
        if (param != null && t is KType.Fn) {
            if (r.model.fxEscapes(param)) {
                r.report(
                    "rules.profile.fx",
                    "Parameter '${param.name}' is an Fx that escapes (it is stored, returned, captured or passed on), so it would be a " +
                        "std::function on the heap. Keep it a parameter that is only called, and it becomes a template parameter.",
                    node,
                )
            }
            return
        }
        val why = refusal(r, t) ?: return
        // Report the innermost refused spelling once: `Maybe<Str>` says Str, not Maybe<Str>.
        val childRefused = AstTree.children(node).any { k -> k is Type && k !is ConstTypeArg && r.model.typeRefs[k]?.let { refusal(r, it) } != null }
        if (childRefused) {
            return
        }
        r.report("rules.profile.type", "${t.display()} is not in the freestanding subset (design 10): $why", node)
    }

    /** Why [t] is outside the Pico subset (null when it is inside). */
    private fun refusal(r: Rules, t: KType): String? = when (t) {
        KType.Str -> "Str allocates; use View<Char> for text you read, or StrBuf<N> for text you build."
        is KType.Fn -> "an Fx held as a value is a std::function (heap); pass it as a parameter that is only called, which lowers to a template."
        is KType.Nominal -> when (val sym = t.sym) {
            is TraitSymbol -> "a trait-typed value is a heap reference; take a generic parameter <T: ${sym.name}> and call through the bound."
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> "a class is a heap reference (an Rc); make ${sym.name} a struct."
                ClassKind.STRUCT -> null
                ClassKind.MAGIC -> when (sym.name) {
                    "List", "Deque", "Stack", "Queue" -> "${sym.name}<T> allocates; use Arr<T, N> with a fixed count."
                    "Map", "Set" -> "${sym.name} allocates; use Arr<T, N> with a fixed count and search it."
                    "Ref", "Weak", "Unsafe" -> "${sym.name}<T> is a heap reference; keep the value in a struct."
                    "Arr" -> if (t.constArgs().isEmpty()) "Arr<T> without a count is a std::vector (heap); give it a count, Arr<T, N>." else t.typeArgs().firstNotNullOfOrNull { refusal(r, it) }
                    // Maybe, Result, View, MutView, the tuples, StrBuf, CStr: allowed over allowed arguments.
                    else -> if (sym.module.uri in hosted) "${sym.name} is from ${sym.module.uri}, which is hosted." else t.typeArgs().firstNotNullOfOrNull { refusal(r, it) }
                }
            }
            else -> null
        }
        else -> null
    }
}
