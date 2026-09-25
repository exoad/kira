package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.ConversionKind
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "only where used" scan over a typed model. Bodies are typed by W2.1; until then the
 * expression types are planted by hand on the parsed nodes, which is exactly what the scan
 * reads.
 */
class CppUsageTest {
    @Test
    fun castsInterpolationsComparisonsAndEnumOfMarkTheirTypes() {
        val (_, ctx) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub enum Kind: Int32 { KIND_A = 0 }
                pub enum Unused: Int32 { U_A = 0 }
                pub enum Looked: Int32 { L_A = 0 }
                pub struct Pt { pub x: Int32 = 0 }
                pub struct Other { pub x: Int32 = 0 }
                pub fx f: (k: Kind, p: Pt, q: Pt, raw: Int32) Str {
                    same: Bool = p == q
                    which: Maybe<Looked> = enumOf<Looked>(raw)
                    return "${'$'}{k}" + (k as Str)
                }
                """,
            ),
            uri = "test:main",
        )
        val program = ctx.program
        val model = program.model
        val kind = ctx.symbol.members["Kind"] as EnumSymbol
        val unused = ctx.symbol.members["Unused"] as EnumSymbol
        val looked = ctx.symbol.members["Looked"] as EnumSymbol
        val pt = ctx.symbol.members["Pt"] as ClassSymbol
        val other = ctx.symbol.members["Other"] as ClassSymbol
        val f = ctx.symbol.members["f"] as FnSymbol

        // Plant what phase C will record: the operand types, the conversion, the resolved call.
        AstTree.walk(ctx.module.ast) { node ->
            when (node) {
                is TypeCastExpr -> {
                    model.types[node.value] = KType.Nominal(kind)
                    model.conversions[node] = ConversionKind.TO_STR
                }
                is InterpolatedStringLiteral -> node.parts.filterIsInstance<InterpolationPart.Hole>().forEach { model.types[it.expr] = KType.Nominal(kind) }
                is BinaryExpr -> if (node.operator == net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp.EQUALS) {
                    model.types[node.leftExpr] = KType.Nominal(pt)
                    model.types[node.rightExpr] = KType.Nominal(pt)
                }
                is FunctionCallExpr -> if ((node.name as? net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier)?.value == "enumOf") {
                    val enumOf = FnSymbol("enumOf", program.builtins.module, null, emptyList(), emptyList(), foreign = net.exoad.kira.compiler.analysis.types.Foreign.Magic("enumOf"))
                    model.calls[node] = ResolvedCall(
                        CallKind.MAGIC, enumOf, null, false,
                        typeArgs = listOf(KType.Nominal(looked)),
                        args = listOf(ArgBinding.Given(node.positionalParameters.first().value, false)),
                        sourceOrder = listOf(0),
                        returnType = KType.Nominal(program.builtins.classFor("Maybe")!!, listOf(TypeArg.Ty(KType.Nominal(looked)))),
                    )
                }
                else -> {}
            }
        }
        val usage = CppUsage.scan(program)
        assertTrue(usage.needsNameOf(kind), "Kind is cast and interpolated")
        assertFalse(usage.needsNameOf(unused), "Unused is never turned into text")
        assertFalse(usage.needsNameOf(looked), "Looked is only looked up")
        assertTrue(usage.needsEnumValues(looked), "enumOf<Looked> needs its values")
        assertFalse(usage.needsEnumValues(kind))
        assertTrue(usage.needsEquality(pt), "Pt is compared")
        assertFalse(usage.needsEquality(other), "Other is not")
        assertTrue(f.body != null)
    }

    @Test
    fun anUntypedProgramUsesNothing() {
        val (_, ctx) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", "pub enum Kind: Int32 { KIND_A = 0 }\npub fx f: (k: Kind) Str { return k as Str }"),
            uri = "test:main",
        )
        val usage = CppUsage.scan(ctx.program)
        assertFalse(usage.needsNameOf(ctx.symbol.members["Kind"] as EnumSymbol))
    }
}
