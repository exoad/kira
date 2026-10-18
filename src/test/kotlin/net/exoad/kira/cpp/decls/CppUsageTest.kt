package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "only where used" scan over a typed model. The bodies are typed by phase C (W2.1):
 * the scan reads the operand types of `p == q`, and never guesses from syntax.
 */
class CppUsageTest {
    private val sources = """
        pub Pt: Struct { pub x: Int32 = 0 }
        pub Other: Struct { pub x: Int32 = 0 }
        pub f: (p: Pt, q: Pt) Bool {
            same: Bool = p == q
            return same
        }
    """

    @Test
    fun comparisonsMarkTheirTypesFromTypedBodies() {
        val (_, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module("test:main", sources), uri = "test:main")
        val pt = ctx.symbol.members["Pt"] as ClassSymbol
        val other = ctx.symbol.members["Other"] as ClassSymbol
        val f = ctx.symbol.members["f"] as FnSymbol
        val returned = f.body?.filterIsInstance<ReturnStatement>()?.firstOrNull()?.expr
        assertTrue(returned != null && ctx.program.model.typeOrNull(returned) != null, "phase C typed the body's return expression")

        val usage = CppUsage.scan(ctx.program)
        assertTrue(usage.needsEquality(pt), "Pt is compared")
        assertFalse(usage.needsEquality(other), "Other is not")
    }

    @Test
    fun theHeaderEmitsEqualityWhereTheScanFoundIt() {
        // The real scan feeds the emitter. The body of f is the statement part's (W2.3) and
        // its refusal lands in the .cxx; the rows the scan switches on are in the header.
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", sources),
            uri = "test:main",
            usageOf = { ctx -> CppUsage.scan(ctx.program) },
        )
        val h = CppWriter.normalize(emitted.header)
        assertTrue(h.contains("bool operator==(const Pt&) const = default;"), "Pt gets operator==:\n$h")
        assertFalse(h.contains("operator==(const Other&)"), "Other does not:\n$h")
    }

    @Test
    fun containsAndContainsValueCompareTheStructsTheyAreGiven() {
        val (_, ctx) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                "pub Pt: Struct { pub x: Int32 = 0 }\npub Val: Struct { pub x: Int32 = 0 }\npub Other: Struct { pub x: Int32 = 0 }\n" +
                    "pub f: (xs: List<Pt>, m: Map<Str, Val>, o: List<Other>) Bool { return xs.contains(Pt { 1 }) && m.containsValue(Val { 2 }) && o.size() > 0 }",
            ),
            uri = "test:main",
        )
        val usage = CppUsage.scan(ctx.program)
        assertTrue(usage.needsEquality(ctx.symbol.members["Pt"] as ClassSymbol))
        assertTrue(usage.needsEquality(ctx.symbol.members["Val"] as ClassSymbol))
        assertFalse(usage.needsEquality(ctx.symbol.members["Other"] as ClassSymbol))
    }
}
