package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.Pos
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Design table 5.1, row by row, read off the parameters and return types of one module: a
 * parameter spells the parameter column, a `mut` parameter the third, a return type the
 * value column.
 */
class CppTypeSpellerTest {
    private val module = DeclTestSupport.module(
        "test:types",
        """
        pub enum Kind: Int32 { KIND_A = 0, KIND_B = 1 }

        pub struct Pt { pub x: Int32 = 0 }

        pub class Node { pub mut v: Int32 = 0 }

        pub trait Shape { pub fx area: () Int32; }

        @_opaque
        pub class Handle { }

        @_extern(cpp = "bibo::Scan", header = "scan.hxx")
        pub struct Scan { }

        @_extern(cpp = "bibo::Car", header = "car.hxx")
        pub class Car { pub mut fx drive: () Void; }

        pub alias Frame as Arr<UInt8, 32>

        pub fx scalars: (a: Int8, b: UInt64, c: Size, d: Float32, e: Float64, f: Bool, g: Char, mut h: Int32, mut i: Size) Int64;
        pub fx strings: (s: Str, mut t: Str) Str;
        pub fx arrays: (fixed: Arr<UInt8, 4>, mut fixedOut: Arr<UInt8, 4>, grow: Arr<Int32>, xs: List<Str>, mut ys: List<Str>) List<Int32>;
        pub fx maps: (m: Map<Str, Int32>, s: Set<Int32>, d: Deque<Int32>, st: Stack<Int32>, q: Queue<Int32>, mut mm: Map<Str, Int32>) Map<Str, Int32>;
        pub fx views: (v: View<UInt8>, w: MutView<UInt8>) View<Char> { return "x" }
        pub fx maybes: (a: Maybe<Int32>, b: Maybe<Pt>, c: Maybe<Node>, d: Maybe<Shape>, mut e: Maybe<Int32>) Maybe<Node>;
        pub fx nominals: (p: Pt, mut q: Pt, n: Node, mut o: Node, s: Shape, k: Kind, mut kk: Kind) Node;
        pub fx handles: (h: Handle, w: Weak<Node>, r: Ref<Int32>, u: Unsafe<Int32>, mut v: Unsafe<Int32>) Weak<Node>;
        pub fx nested: (n: Int, mut f: Float) Int;
        pub fx externs: (s: Scan, mut t: Scan, c: Car) Car;
        pub fx fns: (f: Fx<Tuple2<Str, Int32>, Kind>, g: Fx<Tuple1<mut Int32>, Void>, h: Fx<Tuple0, Int64>) Fx<Tuple0, Void>;
        pub fx tuples: (t: Tuple2<Int32, Str>, r: Result<Int32, Str>, z: Tuple0) Tuple2<Int32, Str>;
        pub fx aliased: (frame: Frame, mut out: Frame, m: Maybe<Frame>) Frame;
        pub fx buffers: (b: StrBuf<32>, mut c: StrBuf<32>, s: CStr) Void;
        """,
    )

    private val emitted = DeclTestSupport.emitWith(module, uri = "test:types")
    private val ctx: CppEmitContextImpl = emitted.second

    private fun fn(name: String): FnSymbol = ctx.symbol.members[name] as? FnSymbol ?: fail("no function $name")

    private fun param(fn: String, name: String): String {
        val p: ParamSymbol = fn(fn).params.firstOrNull { it.name == name } ?: fail("no parameter $name in $fn")
        val node = (p.decl as FunctionDeclParameterExpr).typeSpecifier
        return ctx.spell(node, if (p.byRef) Pos.MUT_PARAM else Pos.PARAM)
    }

    private fun ret(fn: String): String = ctx.spell(fn(fn).ret, Pos.RETURN)

    @Test
    fun scalarsByValueAndByReference() {
        assertEquals("std::int8_t", param("scalars", "a"))
        assertEquals("std::uint64_t", param("scalars", "b"))
        assertEquals("kira::Size", param("scalars", "c"))
        assertEquals("float", param("scalars", "d"))
        assertEquals("double", param("scalars", "e"))
        assertEquals("bool", param("scalars", "f"))
        assertEquals("char", param("scalars", "g"))
        assertEquals("std::int32_t&", param("scalars", "h"))
        assertEquals("kira::Size&", param("scalars", "i"))
        assertEquals("std::int64_t", ret("scalars"))
    }

    @Test
    fun strIsConstRefThenRefThenValue() {
        assertEquals("const kira::Str&", param("strings", "s"))
        assertEquals("kira::Str&", param("strings", "t"))
        assertEquals("kira::Str", ret("strings"))
    }

    @Test
    fun arraysByArity() {
        assertEquals("const std::array<std::uint8_t, 4>&", param("arrays", "fixed"))
        assertEquals("std::array<std::uint8_t, 4>&", param("arrays", "fixedOut"))
        assertEquals("const kira::List<std::int32_t>&", param("arrays", "grow"))
        assertEquals("const kira::List<kira::Str>&", param("arrays", "xs"))
        assertEquals("kira::List<kira::Str>&", param("arrays", "ys"))
        assertEquals("kira::List<std::int32_t>", ret("arrays"))
    }

    @Test
    fun containers() {
        assertEquals("const kira::Map<kira::Str, std::int32_t>&", param("maps", "m"))
        assertEquals("const kira::Set<std::int32_t>&", param("maps", "s"))
        assertEquals("const kira::Deque<std::int32_t>&", param("maps", "d"))
        assertEquals("const kira::Stack<std::int32_t>&", param("maps", "st"))
        assertEquals("const kira::Queue<std::int32_t>&", param("maps", "q"))
        assertEquals("kira::Map<kira::Str, std::int32_t>&", param("maps", "mm"))
        assertEquals("kira::Map<kira::Str, std::int32_t>", ret("maps"))
    }

    @Test
    fun viewsByValue() {
        assertEquals("kira::View<std::uint8_t>", param("views", "v"))
        assertEquals("kira::MutView<std::uint8_t>", param("views", "w"))
        assertEquals("kira::View<char>", ret("views"))
    }

    @Test
    fun maybeOfValuesAndOfClasses() {
        assertEquals("const kira::Maybe<std::int32_t>&", param("maybes", "a"))
        assertEquals("const kira::Maybe<Pt>&", param("maybes", "b"))
        assertEquals("const kira::Maybe<kira::Rc<Node>>&", param("maybes", "c"))
        assertEquals("const kira::Maybe<kira::Rc<Shape>>&", param("maybes", "d"))
        assertEquals("kira::Maybe<std::int32_t>&", param("maybes", "e"))
        assertEquals("kira::Maybe<kira::Rc<Node>>", ret("maybes"))
    }

    @Test
    fun structsClassesTraitsAndEnums() {
        assertEquals("const Pt&", param("nominals", "p"))
        assertEquals("Pt&", param("nominals", "q"))
        assertEquals("const kira::Rc<Node>&", param("nominals", "n"))
        assertEquals("kira::Rc<Node>&", param("nominals", "o"))
        assertEquals("const kira::Rc<Shape>&", param("nominals", "s"))
        assertEquals("Kind", param("nominals", "k"))
        assertEquals("Kind&", param("nominals", "kk"))
        assertEquals("kira::Rc<Node>", ret("nominals"))
    }

    @Test
    fun opaqueWeakRefAndUnsafe() {
        assertEquals("Handle*", param("handles", "h"))
        assertEquals("const kira::Weak<Node>&", param("handles", "w"))
        assertEquals("const kira::Rc<kira::Box<std::int32_t>>&", param("handles", "r"))
        // table 5.1: Unsafe<T> is `const T*` unless the binding is `mut`
        assertEquals("const std::int32_t*", param("handles", "u"))
        assertEquals("std::int32_t*", param("handles", "v"))
        assertEquals("kira::Weak<Node>", ret("handles"))
        // the const qualifies the pointee: a pointer to a `const T*`, never `const const T**`. Kira can no
        // longer write an Unsafe of an Unsafe, nor return an Unsafe (decision 4b, 30-second-class 1.2 and
        // 5.2), but the speller still spells the type it is handed.
        val unsafeInt = fn("handles").params.first { it.name == "u" }.type as KType.Nominal
        val nested = KType.Nominal(unsafeInt.sym, listOf(TypeArg.Ty(unsafeInt)))
        assertEquals("const std::int32_t* const*", ctx.spell(nested, Pos.PARAM))
        assertEquals("const std::int32_t**", ctx.spell(nested, Pos.MUT_PARAM))
        assertEquals("const std::int32_t* const*", ctx.spell(nested, Pos.RETURN))
    }

    @Test
    fun theCoreAliasesAreTheirTargets() {
        assertEquals("std::int32_t", param("nested", "n"))
        assertEquals("float&", param("nested", "f"))
    }

    @Test
    fun externTypesUseTheCppName() {
        assertEquals("const ::bibo::Scan&", param("externs", "s"))
        assertEquals("::bibo::Scan&", param("externs", "t"))
        assertEquals("const kira::Rc<::bibo::Car>&", param("externs", "c"))
        assertEquals("kira::Rc<::bibo::Car>", ret("externs"))
    }

    @Test
    fun escapingFxIsKiraFnOverTheParameterColumn() {
        assertEquals("const kira::Fn<Kind(const kira::Str&, std::int32_t)>&", param("fns", "f"))
        assertEquals("const kira::Fn<void(std::int32_t&)>&", param("fns", "g"))
        assertEquals("const kira::Fn<std::int64_t()>&", param("fns", "h"))
        assertEquals("kira::Fn<void()>", ret("fns"))
    }

    @Test
    fun nonEscapingFxSpellsTheCallableConcept() {
        val f = fn("fns").params.first { it.name == "f" }
        assertEquals("kira::Callable<F_f, Kind, const kira::Str&, std::int32_t>", ctx.speller.callable("F_f", f.type as net.exoad.kira.compiler.analysis.types.KType.Fn))
        assertEquals("F_f", ctx.speller.templateParamName(f))
    }

    @Test
    fun tuplesAndResult() {
        assertEquals("const kira::Tuple2<std::int32_t, kira::Str>&", param("tuples", "t"))
        assertEquals("const kira::Result<std::int32_t, kira::Str>&", param("tuples", "r"))
        assertEquals("const kira::Tuple0&", param("tuples", "z"))
        assertEquals("kira::Tuple2<std::int32_t, kira::Str>", ret("tuples"))
    }

    @Test
    fun anAliasKeepsItsNameEverywhereItIsWritten() {
        assertEquals("const Frame&", param("aliased", "frame"))
        assertEquals("Frame&", param("aliased", "out"))
        assertEquals("const kira::Maybe<Frame>&", param("aliased", "m"))
        // The return type is spelled from the Type node too, so the alias survives there.
        val node = (fn("aliased").decl!!).def.returnTypeSpecifier
        assertEquals("Frame", ctx.spell(node, Pos.RETURN))
        // From the KType alone the alias is gone: that is the expansion.
        assertEquals("std::array<std::uint8_t, 32>", ret("aliased"))
    }

    @Test
    fun strBufAndCStr() {
        assertEquals("const kira::StrBuf<32>&", param("buffers", "b"))
        assertEquals("kira::StrBuf<32>&", param("buffers", "c"))
        assertEquals("const char*", param("buffers", "s"))
        assertEquals("void", ret("buffers"))
    }

    @Test
    fun theHeaderDeclaresEveryPrototypeWithTheseSpellings() {
        val header = emitted.first.header
        assertTrue(header.contains("[[nodiscard]] std::int64_t scalars(std::int8_t a, std::uint64_t b, kira::Size c, float d, double e, bool f, char g, std::int32_t& h, kira::Size& i);"), header)
        assertTrue(header.contains("[[nodiscard]] Frame aliased(const Frame& frame, Frame& out, const kira::Maybe<Frame>& m);"), header)
        assertTrue(header.contains("using Frame = std::array<std::uint8_t, 32>;"), header)
    }
}
