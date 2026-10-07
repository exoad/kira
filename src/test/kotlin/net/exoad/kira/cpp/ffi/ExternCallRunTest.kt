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
class ExternCallRunTest {
    private val calls = Module(
        "app:w",
        """
        pub @_extern(cpp = "nat::lenAfterL", header = "nat.hxx") fx lenAfterL: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenAfterCL", header = "nat.hxx") fx lenAfterCL: (s: CStr, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenAfterCb", header = "nat.hxx") fx lenAfterCb: (s: Str, c: Cb) Int32;

        pub @_extern(cpp = "nat::lenAfterF", header = "nat.hxx") fx lenAfterF: (s: Str, f: Fx<Tuple0, Void>) Int32;

        pub @_extern(cpp = "nat::appendLen", header = "nat.hxx") fx appendLen: (s: Str, mut out: Str) Int32;

        pub @_extern(cpp = "nat::appendLenC", header = "nat.hxx") fx appendLenC: (s: CStr, mut out: Str) Int32;

        pub @_extern(cpp = "nat::appendLenI", header = "nat.hxx") fx appendLenI: (s: Str, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::sumListL", header = "nat.hxx") fx sumListL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::sumArrL", header = "nat.hxx") fx sumArrL: (a: Arr<Int32, 3>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenMaybeL", header = "nat.hxx") fx lenMaybeL: (m: Maybe<Str>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::ptLenL", header = "nat.hxx") fx ptLenL: (p: Pt, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::boxLenL", header = "nat.hxx") fx boxLenL: (b: Box, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::lenS", header = "nat.hxx") fx lenS: (s: Str) Int32;

        pub @_extern(cpp = "nat::newBox", header = "nat.hxx") fx newBox: (n: Int32) Box;

        pub @_extern(cpp = "nat::pumpWith", header = "nat.hxx") fx pumpWith: (fs: List<Fx<Tuple0, Void>>) Pump;

        pub @_extern(cpp = "nat::ptNAfterMut", header = "nat.hxx") fx ptNAfterMut: (p: Pt, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::firstAfterMut", header = "nat.hxx") fx firstAfterMut: (xs: List<Int32>, mut n: Int32) Int32;

        pub @_extern(cpp = "nat::nameAfterMutS", header = "nat.hxx") fx nameAfterMutS: (p: Pt, mut out: Str) Int32;

        pub @_extern(cpp = "nat::firstAfterMutInner", header = "nat.hxx") fx firstAfterMutInner: (ls: List<List<Int32>>, mut o: List<Int32>) Int32;

        pub @_extern(cpp = "nat::firstAfterL", header = "nat.hxx") fx firstAfterL: (xs: List<Int32>, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::ptNAfterL", header = "nat.hxx") fx ptNAfterL: (p: Pt, fs: List<Fx<Tuple0, Void>>) Int32;

        pub @_extern(cpp = "nat::Box", header = "nat.hxx") class Box {
            pub fx size: () Int32;
        }

        pub @_extern(cpp = "nat::Pump", header = "nat.hxx") class Pump {
            pub mut fx lenAfter: (s: Str) Int32;
        }

        pub struct Pt {
            pub mut name: Str = ""
            pub mut n: Int32 = 0
        }

        pub struct Cb {
            require pub f: Fx<Tuple0, Void>
        }

        pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;

        pub fx plen: (s: CStr) Int32;

        mut GS: Str = "$LONG"
        mut GS2: Str = "other"
        mut GL: List<Int32> = List<Int32> { values = [1, 2, 3] }
        mut GL2: List<Int32> = List<Int32> { values = [4, 5, 6] }
        mut GLL: List<List<Int32>> = List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
        mut GA: Arr<Int32, 3> = [1, 2, 3]
        mut GM: Maybe<Str> = "$LONG"
        mut GP: Pt = Pt { name = "$LONG", n = 1 }
        mut GP2: Pt = Pt { name = "y", n = 3 }
        mut GSTRS: List<Str> = List<Str> { values = ["$LONG"] }
        mut GBOXES: List<Box> = List<Box> { }
        GREETING: Str = "hi"
        mut TICKS: Int32 = 0

        fx reset: () Void {
            GS = "$LONG"
            GL = List<Int32> { values = [1, 2, 3] }
            GLL = List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
            GA = [1, 2, 3]
            GM = "$LONG"
            GP = Pt { name = "$LONG", n = 1 }
            GSTRS = List<Str> { values = ["$LONG"] }
        }

        fx growGs: () List<Fx<Tuple0, Void>> {
            return List<Fx<Tuple0, Void>> { values = [fx () Void { GS = "$LONGER" + GS }] }
        }

        fx growGl: () List<Fx<Tuple0, Void>> {
            return List<Fx<Tuple0, Void>> { values = [fx () Void { GL = List<Int32> { values = [7, 7, 7, 7, 7, 7, 7, 7, 7] } }] }
        }

        pub fx sGlobal: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            return lenAfterL(GS, fs)
        }

        pub fx sLent: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GSTRS = List<Str> { values = ["$LONGER"] } }] }
            return lenAfterL(GSTRS[0], fs)
        }

        pub fx sField: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GP = Pt { name = "$LONGER", n = 2 } }] }
            return lenAfterL(GP.name, fs)
        }

        fx lenOfParam: (p: Str, fs: List<Fx<Tuple0, Void>>) Int32 {
            return lenAfterL(p, fs)
        }

        pub fx sParam: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            return lenOfParam(GS, fs)
        }

        pub fx sLiteral: () Int32 {
            reset()
            return lenAfterL("abc", growGs())
        }

        pub fx sConstant: () Int32 {
            reset()
            return lenAfterL(GREETING, growGs())
        }

        pub fx sComputed: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GS = "" }] }
            return lenAfterL(GS + "!", fs)
        }

        pub fx sStructFx: () Int32 {
            reset()
            c: Cb = Cb { f = fx () Void { GS = "$LONGER" + GS } }
            return lenAfterCb(GS, c)
        }

        pub fx sReceiver: () Int32 {
            reset()
            pump: Pump = pumpWith(growGs())
            return pump.lenAfter(GS)
        }

        fx tick: () List<Fx<Tuple0, Void>> {
            TICKS += 1
            return List<Fx<Tuple0, Void>> { values = [fx () Void { GS = "" }] }
        }

        pub fx sSpilled: () Int32 {
            reset()
            return lenAfterL(GS, tick())
        }

        pub fx sDirect: () Int32 {
            reset()
            return lenAfterF(GS, fx () Void { GS = "" })
        }

        pub fx cCStr: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = growGs()
            return lenAfterCL(GS, fs)
        }

        pub fx cList: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GL = List<Int32> { values = [9, 7] } }] }
            return sumListL(GL, fs)
        }

        pub fx cArr: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GA = [7, 7, 7] }] }
            return sumArrL(GA, fs)
        }

        pub fx cMaybe: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GM = null }] }
            return lenMaybeL(GM, fs)
        }

        pub fx cStruct: () Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GP = Pt { name = "x", n = 2 } }] }
            return ptLenL(GP, fs)
        }

        pub fx cClass: () Int32 {
            GBOXES = List<Box> { }
            GBOXES.add(newBox(82))
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GBOXES = List<Box> { } }] }
            return boxLenL(GBOXES[0], fs)
        }

        fx appendInto: (mut o: Str) Int32 {
            return appendLen(GS, mut o)
        }

        fx appendIntoC: (mut o: Str) Int32 {
            return appendLenC(GS, mut o)
        }

        pub fx mViaMut: () Int32 {
            reset()
            return appendInto(mut GS)
        }

        pub fx mCStr: () Int32 {
            reset()
            return appendIntoC(mut GS)
        }

        pub fx nUnrelated: () Int32 {
            reset()
            mut n: Int32 = 0
            return appendLenI(GS, mut n)
        }

        pub fx nNone: () Int32 {
            reset()
            return lenS(GS)
        }

        pub fx gPeek: () Int32 {
            ys: List<Int32> = List<Int32> { values = [10, 20] }
            mut xs: List<Int32> = List<Int32> { values = [1, 2] }
            return peek(ys.view(), xs.view())
        }

        pub fx gPlen: () Int32 {
            loc: Str = "abcd"
            return plen(loc) + plen("xy") * 10
        }

        fx viaN: (mut o: Int32) Int32 {
            return ptNAfterMut(GP, mut o)
        }

        fx viaL: (mut o: Int32) Int32 {
            return firstAfterMut(GL, mut o)
        }

        fx viaS: (mut o: Str) Int32 {
            return nameAfterMutS(GP, mut o)
        }

        fx viaI: (mut o: List<Int32>) Int32 {
            return firstAfterMutInner(GLL, mut o)
        }

        pub fx r0N: () Int32 {
            reset()
            return viaN(mut GP.n)
        }

        pub fx r0L: () Int32 {
            reset()
            return viaL(mut GL[0])
        }

        pub fx r0S: () Int32 {
            reset()
            return viaS(mut GP.name)
        }

        pub fx r0I: () Int32 {
            reset()
            return viaI(mut GLL[0])
        }

        pub fx r1S: (c: Bool) Int32 {
            reset()
            return lenAfterL(if c { GS } else { GS2 }, growGs())
        }

        pub fx r1C: (c: Bool) Int32 {
            reset()
            return lenAfterCL(if c { GS } else { GS2 }, growGs())
        }

        pub fx r1L: (c: Bool) Int32 {
            reset()
            return sumListL(if c { GL } else { GL2 }, growGl())
        }

        pub fx r1F: (c: Bool) Int32 {
            reset()
            return firstAfterL(if c { GL } else { GL2 }, growGl())
        }

        pub fx r1P: (c: Bool) Int32 {
            reset()
            fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { values = [fx () Void { GP = Pt { name = "x", n = 2 } }] }
            return ptNAfterL(if c { GP } else { GP2 }, fs)
        }

        pub fx r1Local: (c: Bool) Int32 {
            reset()
            a: List<Int32> = List<Int32> { values = [1, 2, 3] }
            b: List<Int32> = List<Int32> { values = [4, 5, 6] }
            return sumListL(if c { a } else { b }, growGl())
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
        pub fx lenS2: (s: Str) Int32;

        pub fx lenAfterP: (s: Str, fs: List<Fx<Tuple0, Void>>) Int32;

        pub fx clenAfterP: (s: CStr, fs: List<Fx<Tuple0, Void>>) Int32;

        pub fx bumpP: (mut n: Int32) Void;

        pub fx viaW: (s: Str) Int32 {
            return lenS2(s)
        }
        """,
    )

    private val protoUse = Module(
        "proto:caller",
        """
        use "proto:p"

        mut GS: Str = "$LONG"

        fx growGs: () List<Fx<Tuple0, Void>> {
            return List<Fx<Tuple0, Void>> { values = [fx () Void { GS = "$LONGER" + GS }] }
        }

        pub fx t13: () Int32 {
            loc: Str = "abcd"
            return lenS2(loc) + lenS2("xy") * 10
        }

        pub fx t13b: () Int32 {
            return viaW("abcd")
        }

        pub fx pCopy: () Int32 {
            GS = "$LONG"
            fs: List<Fx<Tuple0, Void>> = growGs()
            return lenAfterP(GS, fs)
        }

        pub fx pCStr: () Int32 {
            GS = "$LONG"
            fs: List<Fx<Tuple0, Void>> = growGs()
            return clenAfterP(GS, fs)
        }

        pub fx pMut: () Int32 {
            mut n: Int32 = 1
            bumpP(mut n)
            return n
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
