package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "only where used" scan over a typed model. The bodies are typed by phase C (W2.1):
 * the scan reads the conversion it recorded on `k as Str`, the hole types of `"${k}"`, the
 * operand types of `p == q` and the resolved call of `enumOf<Looked>(raw)`, and never
 * guesses from syntax.
 */
class CppUsageTest {
    private val sources = """
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
    """

    @Test
    fun castsInterpolationsComparisonsAndEnumOfMarkTheirTypesFromTypedBodies() {
        val (_, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module("test:main", sources), uri = "test:main")
        val kind = ctx.symbol.members["Kind"] as EnumSymbol
        val unused = ctx.symbol.members["Unused"] as EnumSymbol
        val looked = ctx.symbol.members["Looked"] as EnumSymbol
        val pt = ctx.symbol.members["Pt"] as ClassSymbol
        val other = ctx.symbol.members["Other"] as ClassSymbol
        val f = ctx.symbol.members["f"] as FnSymbol
        val returned = f.body?.filterIsInstance<ReturnStatement>()?.firstOrNull()?.expr
        assertTrue(returned != null && ctx.program.model.typeOrNull(returned) != null, "phase C typed the body's return expression")

        val usage = CppUsage.scan(ctx.program)
        assertTrue(usage.needsNameOf(kind), "Kind is cast and interpolated")
        assertFalse(usage.needsNameOf(unused), "Unused is never turned into text")
        assertFalse(usage.needsNameOf(looked), "Looked is only looked up")
        assertTrue(usage.needsEnumValues(looked), "enumOf<Looked> needs its values")
        assertFalse(usage.needsEnumValues(kind))
        assertTrue(usage.needsEquality(pt), "Pt is compared")
        assertFalse(usage.needsEquality(other), "Other is not")
    }

    @Test
    fun theHeaderEmitsNameOfEnumValuesAndEqualityWhereTheScanFoundThem() {
        // The real scan feeds the emitter. The body of f is the statement part's (W2.3) and
        // its refusal lands in the .cxx; the rows the scan switches on are in the header.
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", sources),
            uri = "test:main",
            usageOf = { ctx -> CppUsage.scan(ctx.program) },
        )
        val h = CppWriter.normalize(emitted.header)
        assertTrue(h.contains("nameOf(Kind "), "Kind gets nameOf:\n$h")
        assertFalse(h.contains("nameOf(Unused") || h.contains("nameOf(Looked"), "the other enums do not:\n$h")
        assertTrue(h.contains("enum_values(Looked"), "Looked gets enum_values:\n$h")
        assertFalse(h.contains("enum_values(Kind"), "Kind does not:\n$h")
        assertTrue(h.contains("bool operator==(const Pt&) const = default;"), "Pt gets operator==:\n$h")
        assertFalse(h.contains("operator==(const Other&)"), "Other does not:\n$h")
    }

    @Test
    fun aBodyThatNeverTurnsAnEnumIntoTextUsesNothing() {
        val (_, ctx) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", "pub enum Kind: Int32 { KIND_A = 0 }\npub struct Pt { pub x: Int32 = 0 }\npub fx f: (k: Kind, p: Pt) Int32 { return p.x }"),
            uri = "test:main",
        )
        val usage = CppUsage.scan(ctx.program)
        assertFalse(usage.needsNameOf(ctx.symbol.members["Kind"] as EnumSymbol))
        assertFalse(usage.needsEnumValues(ctx.symbol.members["Kind"] as EnumSymbol))
        assertFalse(usage.needsEquality(ctx.symbol.members["Pt"] as ClassSymbol))
    }
}
