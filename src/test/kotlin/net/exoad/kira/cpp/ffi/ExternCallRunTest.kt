package net.exoad.kira.cpp.ffi

import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.exoad.kira.StageThreeText
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Copy by default at the FFI boundary (50-round4 1.2, 6.5). Every row is a Kira function that
 * calls a function whose body C++ supplies (an `@_extern`, a bodiless `pub` prototype), lowered
 * by the real expression emitter: W2.3's copy policy decides which argument is copied (a
 * temporary, a whitelisted lend, or `T(e)`), and CppExternEmitter spells the decision with the
 * argument's proxy. Each row is checked twice: its call text (a copy where the policy copies,
 * none where it lends), and its value on gcc, clang and msvc, written from Kira's semantics
 * (Kira passes every argument by value, so the callee reads it as it was when the call began).
 *
 * The writer is an `Fx` the extern is handed (in a `List`, in a struct field, held by the
 * receiver, or a direct lambda) or a `mut` argument that may be the argument's storage or lie
 * inside it. Round 3's four significant findings of this package are rows: w2-6 #0 (a `mut`
 * argument that is part of the by-value argument, t1 and t1u: 5 for 1, a heap-use-after-free),
 * #1 (an if-expression over places, t5 and t11: 63 for 6, a heap-use-after-free), and #2 and
 * w2-4 #1 (a prototype's call in a module no `@_extern` brings `kira/ffi.hxx` to, t13 and t13b:
 * no compiler built it).
 */
// What this class tests (D4's `mut` arguments and parameters, D44's copies) only stage 3's text spells.
@ExtendWith(StageThreeText::class)
class ExternCallRunTest {
    private val calls = Module(
        "app:w",
        """
        pub @_extern(cpp = "nat::lenAfterL", header = "nat.hxx") lenAfterL: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenAfterCL", header = "nat.hxx") lenAfterCL: (s: CStr, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenAfterCb", header = "nat.hxx") lenAfterCb: (s: Str, c: Cb) Int32;

        pub @_extern(cpp = "nat::lenAfterF", header = "nat.hxx") lenAfterF: (s: Str, f: Fx<Tuple0, Void>) Int32;

        pub @_extern(cpp = "nat::appendLen", header = "nat.hxx") appendLen: (s: Str, mut out: Str) Int32;

        pub @_extern(cpp = "nat::appendLenC", header = "nat.hxx") appendLenC: (s: CStr, mut out: Str) Int32;

        pub @_extern(cpp = "nat::appendLenI", header = "nat.hxx") appendLenI: (s: Str, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::sumListL", header = "nat.hxx") sumListL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::sumArrL", header = "nat.hxx") sumArrL: (a: Arr<Int32, 3>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenMaybeL", header = "nat.hxx") lenMaybeL: (m: Maybe<Str>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::ptLenL", header = "nat.hxx") ptLenL: (p: Pt, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::boxLenL", header = "nat.hxx") boxLenL: (b: Box, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenS", header = "nat.hxx") lenS: (s: Str) Int32;

        pub @_extern(cpp = "nat::newBox", header = "nat.hxx") newBox: (n: Int32) Box;

        pub @_extern(cpp = "nat::pumpWith", header = "nat.hxx") pumpWith: (fs: List<Fx<Tuple0, Void>>) Pump;

        pub @_extern(cpp = "nat::ptNAfterMut", header = "nat.hxx") ptNAfterMut: (p: Pt, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::firstAfterMut", header = "nat.hxx") firstAfterMut: (xs: List<Int32>, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::nameAfterMutS", header = "nat.hxx") nameAfterMutS: (p: Pt, mut out: Str) Int32;

        pub @_extern(cpp = "nat::firstAfterMutInner", header = "nat.hxx") firstAfterMutInner: (ls: List<List<Int32>>, mut o: List<Int32>) Int32;

        pub @_extern(cpp = "nat::firstAfterL", header = "nat.hxx") firstAfterL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::ptNAfterL", header = "nat.hxx") ptNAfterL: (p: Pt, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::Box", header = "nat.hxx") Box: Any {
            pub size: () Int32;
        }

        pub @_extern(cpp = "nat::Pump", header = "nat.hxx") Pump: Any {
            pub mut lenAfter: (s: Str) Int32;
        }

        pub Pt: Struct {
            pub mut name: Str = ""
            pub mut n: Int32 = 0
        }

        pub Cb: Struct {
            pub f: Fx<Tuple0, Void>
        }

        pub peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;

        pub plen: (s: CStr) Int32;

        mut GS: Str = "$LONG"
        mut GS2: Str = "other"
        mut GL: List<Int32> = List<Int32> { values = @arrOf(1, 2, 3) }
        mut GL2: List<Int32> = List<Int32> { values = @arrOf(4, 5, 6) }
        mut GLL: List<List<Int32>> = List<List<Int32>> { values = @arrOf(List<Int32> { values = @arrOf(1, 2, 3) }) }
        mut GA: Arr<Int32, 3> = @arrOf(1, 2, 3)
        mut GM: Maybe<Str> = "$LONG"
        mut GP: Pt = Pt { name = "$LONG", n = 1 }
        mut GP2: Pt = Pt { name = "y", n = 3 }
        mut GSTRS: List<Str> = List<Str> { values = @arrOf("$LONG") }
        mut GBOXES: List<Box> = List<Box> { }
        GREETING: Str = "hi"
        mut TICKS: Int32 = 0

        reset: () Void {
            GS = "$LONG"
            GL = List<Int32> { values = @arrOf(1, 2, 3) }
            GLL = List<List<Int32>> { values = @arrOf(List<Int32> { values = @arrOf(1, 2, 3) }) }
            GA = @arrOf(1, 2, 3)
            GM = "$LONG"
            GP = Pt { name = "$LONG", n = 1 }
            GSTRS = List<Str> { values = @arrOf("$LONG") }
        }

        growGs: () List<Fx<Tuple0, Void>> {
            List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GS = "$LONGER" + GS }) }
        }

        growGl: () List<Fx<Tuple0, Void>> {
            List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GL = List<Int32> { values = @arrOf(7, 7, 7, 7, 7, 7, 7, 7, 7) } }) }
        }

        pub sGlobal: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            lenAfterL(GS, fs)
        }

        pub sLent: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GSTRS = List<Str> { values = @arrOf("$LONGER") } }) }
            lenAfterL(GSTRS[0], fs)
        }

        pub sField: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GP = Pt { name = "$LONGER", n = 2 } }) }
            lenAfterL(GP.name, fs)
        }

        lenOfParam: (p: Str, fs: List<Fx<Tuple0, Void>>) Int32 {
            lenAfterL(p, fs)
        }

        pub sParam: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            lenOfParam(GS, fs)
        }

        pub sLiteral: () Int32 {
            reset()
            lenAfterL("abc", growGs())
        }

        pub sConstant: () Int32 {
            reset()
            lenAfterL(GREETING, growGs())
        }

        pub sComputed: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GS = "" }) }
            lenAfterL(GS + "!", fs)
        }

        pub sStructFx: () Int32 {
            reset()
            c: Cb = Cb { f = () Void { GS = "$LONGER" + GS } }
            lenAfterCb(GS, c)
        }

        pub sReceiver: () Int32 {
            reset()
            pump: Pump = pumpWith(growGs())
            pump.lenAfter(GS)
        }

        tick: () List<Fx<Tuple0, Void>> {
            TICKS += 1
            List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GS = "" }) }
        }

        pub sSpilled: () Int32 {
            reset()
            lenAfterL(GS, tick())
        }

        pub sDirect: () Int32 {
            reset()
            lenAfterF(GS, () Void { GS = "" })
        }

        pub cCStr: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            lenAfterCL(GS, fs)
        }

        pub cList: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GL = List<Int32> { values = @arrOf(9, 7) } }) }
            sumListL(GL, fs)
        }

        pub cArr: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GA = @arrOf(7, 7, 7) }) }
            sumArrL(GA, fs)
        }

        pub cMaybe: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GM = null }) }
            lenMaybeL(GM, fs)
        }

        pub cStruct: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GP = Pt { name = "x", n = 2 } }) }
            ptLenL(GP, fs)
        }

        pub cClass: () Int32 {
            GBOXES = List<Box> { }
            GBOXES.add(newBox(82))
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GBOXES = List<Box> { } }) }
            boxLenL(GBOXES[0], fs)
        }

        appendInto: (mut o: Str) Int32 {
            appendLen(GS, mut o)
        }

        appendIntoC: (mut o: Str) Int32 {
            appendLenC(GS, mut o)
        }

        pub mViaMut: () Int32 {
            reset()
            appendInto(mut GS)
        }

        pub mCStr: () Int32 {
            reset()
            appendIntoC(mut GS)
        }

        pub nUnrelated: () Int32 {
            reset()
            mut n: Int32 = 0
            appendLenI(GS, mut n)
        }

        pub nNone: () Int32 {
            reset()
            lenS(GS)
        }

        pub gPeek: () Int32 {
            ys: List<Int32> = List<Int32> { values = @arrOf(10, 20) }
            mut xs: List<Int32> = List<Int32> { values = @arrOf(1, 2) }
            peek(ys.view(), xs.view())
        }

        pub gPlen: () Int32 {
            loc: Str = "abcd"
            plen(loc) + plen("xy") * 10
        }

        viaN: (mut o: Int32) Int32 {
            ptNAfterMut(GP, mut o)
        }

        viaL: (mut o: Int32) Int32 {
            firstAfterMut(GL, mut o)
        }

        viaS: (mut o: Str) Int32 {
            nameAfterMutS(GP, mut o)
        }

        viaI: (mut o: List<Int32>) Int32 {
            firstAfterMutInner(GLL, mut o)
        }

        pub r0N: () Int32 {
            reset()
            viaN(mut GP.n)
        }

        pub r0L: () Int32 {
            reset()
            viaL(mut GL[0])
        }

        pub r0S: () Int32 {
            reset()
            viaS(mut GP.name)
        }

        pub r0I: () Int32 {
            reset()
            viaI(mut GLL[0])
        }

        pub r1S: (c: Bool) Int32 {
            reset()
            lenAfterL(c ? GS : GS2, growGs())
        }

        pub r1C: (c: Bool) Int32 {
            reset()
            lenAfterCL(c ? GS : GS2, growGs())
        }

        pub r1L: (c: Bool) Int32 {
            reset()
            sumListL(c ? GL : GL2, growGl())
        }

        pub r1F: (c: Bool) Int32 {
            reset()
            firstAfterL(c ? GL : GL2, growGl())
        }

        pub r1P: (c: Bool) Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GP = Pt { name = "x", n = 2 } }) }
            ptNAfterL(c ? GP : GP2, fs)
        }

        pub r1Local: (c: Bool) Int32 {
            reset()
            a: List<Int32> = List<Int32> { values = @arrOf(1, 2, 3) }
            b: List<Int32> = List<Int32> { values = @arrOf(4, 5, 6) }
            sumListL(c ? a : b, growGl())
        }
        """,
    )

    /**
     * One row: the function, the call the driver makes, Kira's value, the call text its body must
     * contain, and why. A text with `T(...)`, `kira::Str(...)`, `CStrBuf` or `kira::Rc<...>(...)`
     * around the argument is a copy; a text without one lends the argument's own storage.
     */
    private data class Row(val fn: String, val call: String, val value: Int, val text: String, val why: String)

    private val rows = listOf(
        // Copied: a place that is not PRIVATE, handed to a call that is not CONFINED (it is given an Fx: in a List, in a struct field, held by the receiver, or a direct lambda).
        Row("sGlobal", "sGlobal()", 82, "::nat::lenAfterL(kira::ffi::in(kira::Str(GS)), fs)", "a global Str beside an Fx is copied; the local List of Fx is lent"),
        Row("sLent", "sLent()", 82, "::nat::lenAfterL(kira::ffi::in(kira::Str(kira::at(GSTRS, 0))), fs)", "a lent element (R-A) is the place it lends from, copied"),
        Row("sField", "sField()", 82, "::nat::lenAfterL(kira::ffi::in(kira::Str(GP.name)), fs)", "a field of a global struct is copied"),
        Row("lenOfParam", "sParam()", 82, "::nat::lenAfterL(kira::ffi::in(p), fs)", "a by-value parameter is PRIVATE (invariant I): lent inside"),
        Row("sParam", "sParam()", 82, "return lenOfParam(kira::Str(GS), fs);", "and copied by its caller, whose callee is not CONFINED"),
        Row("sLiteral", "sLiteral()", 3, "::nat::lenAfterL(kira::ffi::in(\"abc\"), growGs())", "a literal and a call's result are temporaries (W1)"),
        Row("sConstant", "sConstant()", 2, "::nat::lenAfterL(kira::ffi::in(GREETING), growGs())", "a Kira Str constant is a const char* (D12), a temporary where it binds"),
        Row("sComputed", "sComputed()", 83, "::nat::lenAfterL(kira::ffi::in(GS + \"!\"), fs)", "a computed Str is a temporary (W1)"),
        Row("sSpilled", "sSpilled()", 82, "::nat::lenAfterL(kira::ffi::in(t0_), t1_)", "an argument D33 spills before an IMPURE sibling is its typed temporary, the copy (W1), never copied again (w2-6 minor #1)"),
        Row("sStructFx", "sStructFx()", 82, "::nat::lenAfterCb(kira::ffi::in(kira::Str(GS)), c)", "an Fx in a struct field makes the call not CONFINED"),
        Row("sReceiver", "sReceiver()", 82, "pump->lenAfter(kira::ffi::in(kira::Str(GS)))", "a handle receiver may hold an Fx (contract 5.4.3): not CONFINED"),
        Row("sDirect", "sDirect()", 82, "::nat::lenAfterF(kira::ffi::in(kira::Str(GS)), kira::Fn<void()>([]() -> void", "a direct lambda that writes the argument is accepted and the argument copied (the alias rule is deleted); the lambda is the kira::Fn its check states"),
        Row("cCStr", "cCStr()", 82, "::nat::lenAfterCL(kira::ffi::CStrBuf(GS).c_str(), fs)", "a Str copied for a CStr is a CStrBuf"),
        Row("cList", "cList()", 6, "::nat::sumListL(kira::List<std::int32_t>(GL), fs)", "a global List is copied"),
        Row("cArr", "cArr()", 6, "::nat::sumArrL(std::array<std::int32_t, 3>(GA), fs)", "a global Arr is copied"),
        Row("cMaybe", "cMaybe()", 82, "::nat::lenMaybeL(kira::Maybe<kira::Str>(GM), fs)", "a global Maybe is copied"),
        Row("cStruct", "cStruct()", 82, "::nat::ptLenL(Pt(GP), fs)", "a global value struct is copied"),
        Row("cClass", "cClass()", 82, "::nat::boxLenL(kira::Rc<::nat::Box>(kira::at(GBOXES, 0)), fs)", "a handle in a global List is copied: the copy holds the object the hook drops"),
        // Copied: a CONFINED extern whose own mut operand may hold the place (w2-6 #0's direction as well as round 3's).
        Row("appendInto", "mViaMut()", 82, "::nat::appendLen(kira::ffi::in(kira::Str(GS)), kira::ffi::out(o))", "a mut Str parameter may be GS itself"),
        Row("appendIntoC", "mCStr()", 82, "::nat::appendLenC(kira::ffi::CStrBuf(GS).c_str(), kira::ffi::out(o))", "the same through a CStr"),
        Row("viaN", "r0N()", 1, "::nat::ptNAfterMut(Pt(GP), kira::ffi::out(o))", "w2-6 #0 (t1): the mut Int32 may lie inside the Pt, so the Pt is copied"),
        Row("viaL", "r0L()", 1, "::nat::firstAfterMut(kira::List<std::int32_t>(GL), kira::ffi::out(o))", "w2-6 #0 (t1): the mut Int32 may be an element of the List"),
        Row("viaS", "r0S()", 82, "::nat::nameAfterMutS(Pt(GP), kira::ffi::out(o))", "w2-6 #0 (t1): the mut Str may be the Pt's name"),
        Row("viaI", "r0I()", 1, "::nat::firstAfterMutInner(kira::List<kira::List<std::int32_t>>(GLL), kira::ffi::out(o))", "w2-6 #0 (t1u, a heap-use-after-free): the mut List may be an element of the List of Lists"),
        // Lent: nothing the call runs can write the place.
        Row("nUnrelated", "nUnrelated()", 82, "::nat::appendLenI(kira::ffi::in(GS), kira::ffi::out(n))", "a CONFINED extern whose mut Int32 cannot hold a Str lends the global (W3)"),
        Row("nNone", "nNone()", 82, "::nat::lenS(kira::ffi::in(GS))", "a CONFINED extern given nothing else lends the global (W3)"),
        // w2-6 #1 (t5, t11): an if-expression over places is no place and no temporary, so it is copied unless both branches lend.
        Row("r1S", "r1S(true)", 82, "::nat::lenAfterL(kira::ffi::in(c ? kira::Str(GS) : kira::Str(GS2)), growGs())", "a Str if-expression is a temporary already"),
        Row("r1C", "r1C(true)", 82, "::nat::lenAfterCL(kira::ffi::CStrBuf(c ? kira::Str(GS) : kira::Str(GS2)).c_str(), growGs())", "and so is one for a CStr"),
        Row("r1L", "r1L(true)", 6, "::nat::sumListL(kira::List<std::int32_t>(c ? GL : GL2), growGl())", "w2-6 #1 (t5: 63 for 6): a ternary of two global Lists is copied"),
        Row("r1F", "r1F(true)", 1, "::nat::firstAfterL(kira::List<std::int32_t>(c ? GL : GL2), growGl())", "w2-6 #1 (t11, a heap-use-after-free)"),
        Row("r1P", "r1P(true)", 1, "::nat::ptNAfterL(Pt(c ? GP : GP2), fs)", "w2-6 #1 (t11: 2 for 1): a ternary of two global structs is copied"),
        Row("r1Local", "r1Local(true)", 6, "::nat::sumListL(c ? a : b, growGl())", "a ternary of two locals lends: both branches are PRIVATE (W2)"),
        // R-G: a bodiless pub prototype is Kira's own declaration: no proxy, its pointers as an extern's.
        Row("gPeek", "gPeek()", 10, "return peek((kira::view(ys)).data(), (kira::mutView(xs)).data());", "a prototype's Unsafe parameters take the views' pointers"),
        Row("gPlen", "gPlen()", 24, "plen(loc.c_str())", "a prototype's CStr parameter takes a lent Str's own buffer"),
    )

    private val tree by lazy { emitWithNative("ffi-calls", listOf(calls)) }

    /** Emits [modules] and writes the C++ side their externs name (`nat.hxx`) at the tree's root, which the compile includes. */
    private fun emitWithNative(name: String, modules: List<Module>): CppExprTestSupport.Tree =
        CppExprTestSupport.emit(name, modules).also { Files.writeString(it.root.resolve("nat.hxx"), NAT) }

    private fun body(source: String, fn: String): String {
        val head = Regex("\\n( +)[^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n;]*\\n\\1\\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n" + head.groupValues[1] + "}\n", head.range.last)
        return source.substring(head.range.last, if (close < 0) source.length else close)
    }

    @Test
    fun eachArgumentIsCopiedExactlyWhereThePolicyCopiesIt() {
        val source = tree.source(calls)
        val wrong = rows.mapNotNull { row ->
            val b = body(source, row.fn)
            if (b.contains(row.text)) null else "${row.fn} (${row.why}):\n  want ${row.text}\n  in   ${b.trim()}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun theChecksFollowTheModulesOwnDeclarationsAndNameThemFromTheGlobalScope() {
        // w2-4 round-2 minor #3 (externnested2): an extern taking a class or struct this module
        // declares was checked at the top of the header with the bare name.
        val header = tree.header(calls)
        val lines = header.lines()
        val decl = lines.indexOfFirst { it.trim() == "struct Pt" }
        val check = lines.indexOfFirst { it.startsWith("KIRA_EXTERN_CHECK(nat::ptLenL(") }
        assertTrue(decl >= 0 && check > decl, "the check of ptLenL comes after struct Pt:\n$header")
        assertTrue(lines[check].contains("std::declval<const w::Pt&>()"), lines[check])
        assertTrue(lines.any { it.startsWith("KIRA_EXTERN_CHECK(nat::lenAfterCb(") && it.contains("std::declval<const w::Cb&>()") }, header)
    }

    @TestFactory
    fun everyRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("extern calls [${tc.id}]") {
                val checks = rows.distinctBy { it.call }
                val driver = buildString {
                    append("#include \"").append(calls.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append(
                        """

                        namespace w
                        {
                            std::int32_t peek(const std::int32_t* p, std::int32_t* q)
                            {
                                q[0] = p[0];
                                return p[0];
                            }
                            std::int32_t plen(const char* s)
                            {
                                return static_cast<std::int32_t>(std::strlen(s));
                            }
                        }

                        """.trimIndent(),
                    )
                    append("\nint main()\n{\n")
                    checks.forEach { row -> append("    check(w::${row.call} == ${row.value}, \"${row.call} is ${row.value}: ${row.why}\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${checks.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    // ---- bodiless prototypes in modules no @_extern reaches (w2-6 #2, w2-4 #1) ----

    private val proto = Module(
        "proto:p",
        """
        pub lenS2: (s: Str) Int32;

        pub lenAfterP: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;

        pub clenAfterP: (s: CStr, fs: List<Fx<Tuple0, Void>>) Int32;

        pub bumpP: (mut n: Int32) Void;

        pub viaW: (s: Str) Int32 {
            lenS2(s)
        }
        """,
    )

    private val protoUse = Module(
        "proto:caller",
        """
        proto.p as p

        mut GS: Str = "$LONG"

        growGs: () List<Fx<Tuple0, Void>> {
            List<Fx<Tuple0, Void>> { values = @arrOf(() Void { GS = "$LONGER" + GS }) }
        }

        pub t13: () Int32 {
            loc: Str = "abcd"
            lenS2(loc) + lenS2("xy") * 10
        }

        pub t13b: () Int32 {
            viaW("abcd")
        }

        pub pCopy: () Int32 {
            GS = "$LONG"
            fs: List<Fx<Tuple0, Void>> = growGs()
            lenAfterP(GS, fs)
        }

        pub pCStr: () Int32 {
            GS = "$LONG"
            fs: List<Fx<Tuple0, Void>> = growGs()
            clenAfterP(GS, fs)
        }

        pub pMut: () Int32 {
            mut n: Int32 = 1
            bumpP(mut n)
            n
        }
        """,
    )

    private val protoTree by lazy { CppExprTestSupport.emit("ffi-proto", listOf(proto, protoUse)) }

    @Test
    fun aPrototypeIsCalledInItsOwnSpellingAndAsksForFfiOnlyWhereItSpellsIt() {
        val use = protoTree.source(protoUse)
        listOf(
            "::p::lenS2(loc)" to "a prototype's Str parameter is Kira's const kira::Str&: no kira::ffi::in (t13)",
            "::p::lenS2(\"xy\")" to "a literal as well",
            "::p::lenAfterP(kira::Str(GS), fs)" to "the policy's copy of a Str is kira::Str(e), with no proxy around it",
            "::p::clenAfterP(kira::ffi::CStrBuf(GS).c_str(), fs)" to "a Str copied for a prototype's CStr is a CStrBuf",
            "::p::bumpP(n)" to "a prototype's mut parameter is Kira's T&: no kira::ffi::out",
        ).forEach { (text, why) -> assertTrue(use.contains(text), "$why: $text\n$use") }
        assertTrue(body(protoTree.source(proto), "viaW").contains("return lenS2(s);"), "t13b: the prototype called from its own module")
        // kira/ffi.hxx is asked for where kira::ffi:: is spelled, and only there.
        assertTrue(use.contains("#include \"kira/ffi.hxx\""), "the module that spells CStrBuf includes kira/ffi.hxx:\n$use")
        val p = protoTree.text(proto)
        assertFalse(p.contains("kira/ffi.hxx") || p.contains("kira::ffi::"), "the prototypes' own module spells no kira::ffi:: and includes nothing for it:\n$p")
        listOf("kira::ffi::in(", "kira::ffi::out(", "kira::ffi::declared<").forEach { proxy ->
            assertFalse(use.contains(proxy), "no $proxy at a prototype's call:\n$use")
        }
    }

    @TestFactory
    fun everyPrototypeRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("prototypes [${tc.id}]") {
                val checks = listOf(
                    "caller::t13() == 24" to "t13: a prototype given a local and a literal",
                    "caller::t13b() == 4" to "t13b: a prototype called from its own module",
                    "caller::pCopy() == 82" to "a global Str copied at a prototype handed a List of Fx",
                    "caller::pCStr() == 82" to "the same through a CStr",
                    "caller::pMut() == 2" to "a mut Int32 bound as T&",
                )
                val driver = buildString {
                    append("#include \"").append(proto.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append("#include \"").append(protoUse.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append(
                        """

                        namespace p
                        {
                            std::int32_t lenS2(const kira::Str& s)
                            {
                                return static_cast<std::int32_t>(s.size());
                            }
                            std::int32_t lenAfterP(const kira::Str& s, const kira::List<kira::Fn<void()>>& fs)
                            {
                                for (const auto& f : fs)
                                {
                                    f();
                                }
                                return static_cast<std::int32_t>(s.size());
                            }
                            std::int32_t clenAfterP(const char* s, const kira::List<kira::Fn<void()>>& fs)
                            {
                                for (const auto& f : fs)
                                {
                                    f();
                                }
                                return static_cast<std::int32_t>(std::strlen(s));
                            }
                            void bumpP(std::int32_t& n)
                            {
                                n += 1;
                            }
                        }

                        """.trimIndent(),
                    )
                    append("\nint main()\n{\n")
                    checks.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(protoTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${checks.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    private companion object {
        const val LONG = "hello, world, a string long enough to live on the heap and not in the small buffer"
        const val LONGER = "a much longer string, so that the assignment cannot reuse the old heap buffer at all: "

        /** The C++ side: each extern runs the writers it is given (or writes its mut argument), then reads its by-value argument. */
        val NAT = """
            #pragma once
            #include <array>
            #include <cstdint>
            #include <cstring>
            #include <functional>
            #include <memory>
            #include <optional>
            #include <string>
            #include <vector>

            namespace nat
            {
                template<class L>
                inline void runAll(const L& fs)
                {
                    for (const auto& f : fs)
                    {
                        f();
                    }
                }
                template<class L>
                inline std::int32_t lenAfterL(const std::string& s, const L& fs)
                {
                    runAll(fs);
                    return static_cast<std::int32_t>(s.size());
                }
                template<class L>
                inline std::int32_t lenAfterCL(const char* s, const L& fs)
                {
                    runAll(fs);
                    return static_cast<std::int32_t>(std::strlen(s));
                }
                template<class C>
                inline std::int32_t lenAfterCb(const std::string& s, const C& c)
                {
                    c.f();
                    return static_cast<std::int32_t>(s.size());
                }
                template<class F>
                inline std::int32_t lenAfterF(const std::string& s, const F& f)
                {
                    f();
                    return static_cast<std::int32_t>(s.size());
                }
                inline std::int32_t appendLen(const std::string& s, std::string& out)
                {
                    out = "x";
                    return static_cast<std::int32_t>(s.size());
                }
                inline std::int32_t appendLenC(const char* s, std::string& out)
                {
                    out = "a much longer string, so that the assignment cannot reuse the old heap buffer at all, and longer still";
                    return static_cast<std::int32_t>(std::strlen(s));
                }
                inline std::int32_t appendLenI(const std::string& s, std::int32_t& n)
                {
                    n = 5;
                    return static_cast<std::int32_t>(s.size());
                }
                template<class L>
                inline std::int32_t sumListL(const std::vector<std::int32_t>& xs, const L& fs)
                {
                    runAll(fs);
                    return xs[0] + xs[1] + xs[2];
                }
                template<class A, class L>
                inline std::int32_t sumArrL(const A& a, const L& fs)
                {
                    runAll(fs);
                    std::int32_t s = 0;
                    for (auto x : a)
                    {
                        s += x;
                    }
                    return s;
                }
                template<class M, class L>
                inline std::int32_t lenMaybeL(const M& m, const L& fs)
                {
                    runAll(fs);
                    return m.has_value() ? static_cast<std::int32_t>(m->size()) : -1;
                }
                template<class P, class L>
                inline std::int32_t ptLenL(const P& p, const L& fs)
                {
                    runAll(fs);
                    return static_cast<std::int32_t>(p.name.size());
                }
                inline std::int32_t lenS(const std::string& s)
                {
                    return static_cast<std::int32_t>(s.size());
                }
                class Box
                {
                public:
                    explicit Box(std::int32_t n) : n_(n) {}
                    std::int32_t size() const { return n_; }
                private:
                    std::int32_t n_;
                };
                inline std::shared_ptr<Box> newBox(std::int32_t n)
                {
                    return std::make_shared<Box>(n);
                }
                template<class B, class L>
                inline std::int32_t boxLenL(const B& b, const L& fs)
                {
                    runAll(fs);
                    return b->size();
                }
                class Pump
                {
                public:
                    std::vector<std::function<void()>> hooks;
                    std::int32_t lenAfter(const std::string& s)
                    {
                        runAll(hooks);
                        return static_cast<std::int32_t>(s.size());
                    }
                };
                template<class L>
                inline std::shared_ptr<Pump> pumpWith(const L& fs)
                {
                    auto p = std::make_shared<Pump>();
                    for (const auto& f : fs)
                    {
                        p->hooks.push_back(f);
                    }
                    return p;
                }
                template<class P>
                inline std::int32_t ptNAfterMut(const P& p, std::int32_t& n)
                {
                    n = 5;
                    return p.n;
                }
                inline std::int32_t firstAfterMut(const std::vector<std::int32_t>& xs, std::int32_t& n)
                {
                    n = 5;
                    return xs[0];
                }
                template<class P>
                inline std::int32_t nameAfterMutS(const P& p, std::string& out)
                {
                    out = "a much longer string, so that the assignment cannot reuse the old heap buffer at all, and longer still";
                    return static_cast<std::int32_t>(p.name.size());
                }
                inline std::int32_t firstAfterMutInner(const std::vector<std::vector<std::int32_t>>& ls, std::vector<std::int32_t>& o)
                {
                    const std::int32_t* p = ls[0].data();
                    o = std::vector<std::int32_t>(100, 9);
                    return *p;
                }
                template<class L>
                inline std::int32_t firstAfterL(const std::vector<std::int32_t>& xs, const L& fs)
                {
                    const std::int32_t* p = xs.data();
                    runAll(fs);
                    return *p;
                }
                template<class P, class L>
                inline std::int32_t ptNAfterL(const P& p, const L& fs)
                {
                    runAll(fs);
                    return p.n;
                }
            }
        """.trimIndent() + "\n"
    }
}
