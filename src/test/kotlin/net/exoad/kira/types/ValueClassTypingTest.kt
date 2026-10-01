package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Shape
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W2.9 1.1 and 1.2 in the typer: immutability, the shape of a class and why, `copy`, `Weak`
 * of an immutable class, `this` in `initially`, a value class freestanding, and the warning
 * a far subclass earns.
 */
class ValueClassTypingTest {
    private fun cls(p: TypedProgram, name: String, uri: String = "test:main"): ClassSymbol = RulesTestSupport.cls(p, name, uri)

    private fun errors(p: TypedProgram): List<String> = RulesTestSupport.errors(p)

    private fun clean(p: TypedProgram) = assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))

    // ---- shape (1.2.1) --------------------------------------------------------------------------------

    @Test
    fun theShapeIsReadOnlyOncePhaseBSetsIt() {
        val module = net.exoad.kira.compiler.analysis.types.ModuleSymbol("test:hand", net.exoad.kira.source.SourceContext("", "hand.kira", emptyList()))
        val c = ClassSymbol("Hand", module, null, ClassKind.USER, emptyList())
        val thrown = assertFailsWith<IllegalStateException> { c.shape }
        assertTrue(thrown.message!!.contains("before phase B set it"), thrown.message)
        // A magic, opaque or struct declaration has its shape from its kind.
        assertEquals(Shape.MAGIC, ClassSymbol("M", module, null, ClassKind.MAGIC, emptyList()).shape)
        assertEquals(Shape.VALUE, ClassSymbol("S", module, null, ClassKind.STRUCT, emptyList()).shape)
        // Phases A and B alone set it.
        val p = TyperTestSupport.phasesAAndB { TyperTestSupport.snippet("pub class V { pub x: Int32 = 0 }") }
        assertEquals(Shape.VALUE, cls(p, "V").shape)
    }

    @Test
    fun anImmutableClassIsAValueUnlessAConditionMakesItAReference() {
        val p = TyperTestSupport.snippet(
            """
            pub class Plain {
                pub x: Int32 = 0
                pub fx twice: () Int32 { return x * 2 }
            }
            pub class Counter {
                mut ticks: Int32 = 0
            }
            pub class Stepper {
                pub fx step: () Int32 { return 1 }
                pub mut fx bump: () Void { }
            }
            pub class Base {
                pub name: Str = ""
            }
            pub class Kid: Base {
                pub k: Int32 = 0
            }
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub class Square: Shape {
                pub side: Int32 = 1
                override pub fx area: () Int32 { return side }
            }
            pub class Conn {
                pub fd: Int32 = 0
                finally { }
            }
            pub class Node {
                pub v: Int32 = 0
                pub next: Maybe<Node> = null
            }
            pub class Ping {
                pub pong: Maybe<Pong> = null
            }
            pub class Pong {
                pub ping: Maybe<Ping> = null
            }
            pub class Box<T> {
                pub value: T
            }
            pub class Tree {
                pub kids: List<Tree> = List<Tree> {}
                pub boxed: Box<Counter>
            }
            pub class Wrapped {
                pub inner: Box<Wrapped>
            }
            pub class Sub: Counter {
                pub n: Int32 = 0
            }
            """,
        )
        clean(p)
        val shape = { n: String -> cls(p, n).shape to cls(p, n).valueWhyNot }
        assertEquals(Shape.VALUE to null, shape("Plain"))
        assertEquals(Shape.REF to "Counter is mutable (field `ticks` is `mut`)", shape("Counter"))
        assertEquals(Shape.REF to "Stepper is mutable (method `bump` is a `mut fx`)", shape("Stepper"))
        assertEquals(Shape.REF to "Base is extended by Kid", shape("Base"))
        assertEquals(Shape.REF to "Kid extends Base", shape("Kid"))
        assertEquals(Shape.REF, shape("Square").first)
        assertTrue(shape("Square").second!!.startsWith("Square implements the trait Shape"), shape("Square").second)
        assertEquals(Shape.REF to "Conn has a finally block", shape("Conn"))
        assertEquals(Shape.REF to "Node holds itself through field `next`", shape("Node"))
        assertEquals(Shape.REF to "Ping holds itself through field `pong`", shape("Ping"))
        assertEquals(Shape.REF to "Pong holds itself through field `ping`", shape("Pong"))
        // A generic class's value-ness is its declaration's, whatever its arguments; a List holds nothing by value.
        assertEquals(Shape.VALUE to null, shape("Box"))
        assertEquals(Shape.VALUE to null, shape("Tree"))
        assertEquals(Shape.REF to "Wrapped holds itself through field `inner`", shape("Wrapped"))
        // Immutability is inherited, and type arguments do not count.
        assertTrue(!cls(p, "Sub").isImmutable && cls(p, "Box").isImmutable && cls(p, "Tree").isImmutable)
        assertEquals("Sub is mutable (it inherits from Counter, whose field `ticks` is `mut`)", cls(p, "Sub").valueWhyNot)
    }

    @Test
    fun anExternClassIsTheValueOrTheHandleItsDeclarationStates() {
        val p = TyperTestSupport.snippet(
            """
            @_extern(cpp = "bibo::Scan", header = "car.hxx")
            pub class Scan {
                pub fx ahead: () Float32;
            }
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx drive: () Void;
            }
            """,
        )
        clean(p)
        assertEquals(Shape.VALUE, cls(p, "Scan").shape)
        assertEquals(Shape.REF, cls(p, "Car").shape)
    }

    @Test
    fun aKiraClassExtendingAnExternIsRefused() {
        val p = TyperTestSupport.snippet(
            """
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub mut fx drive: () Void;
            }
            pub class Racer: Car {
                pub n: Int32 = 0
            }
            """,
        )
        assertTrue("ffi.extern.extended" in errors(p), TyperTestSupport.render(p))
    }

    @Test
    fun aSubclassInAnotherModuleWarnsThatItMadeAPubValueAReference() {
        val p = RulesTestSupport.modules(
            "pilot:proto" to """
                pub class Reply {
                    pub kind: Int32 = 0
                }
            """,
            "pilot:log" to """
                use "pilot:proto"
                pub class Loud: Reply {
                    pub volume: Int32 = 0
                }
            """,
        )
        clean(p)
        val lost = p.diagnostics.filter { it.code == "cpp.value.lost" }
        assertEquals(1, lost.size, TyperTestSupport.render(p))
        assertTrue(lost.single().message.startsWith("proto::Reply is now a shared reference: C++ callers see kira::Rc<Reply>"), lost.single().message)
        assertEquals(Shape.REF, cls(p, "Reply", "pilot:proto").shape)
        // In one module, extending a class says nothing.
        val near = TyperTestSupport.snippet("pub class A { pub x: Int32 = 0 }\npub class B: A { pub y: Int32 = 0 }")
        assertTrue(near.diagnostics.none { it.code == "cpp.value.lost" }, TyperTestSupport.render(near))
    }

    // ---- immutability (1.1) ---------------------------------------------------------------------------

    @Test
    fun anImmutableClassesFieldIsWrittenOnlyInItsOwnInitially() {
        val p = TyperTestSupport.snippet(
            """
            pub class Reply {
                pub kind: Int32 = 0
                pub note: Str = ""
                initially {
                    kind = kind + 1
                    this.note = "built"
                }
            }
            pub fx edit: (r: Reply) Reply {
                r.kind = 3
                return r
            }
            pub fx rebind: (mut r: Reply) Void {
                r = r.copy(kind = 4)
            }
            pub fx local: () Int32 {
                mut r: Reply = Reply {}
                r = Reply { kind = 2 }
                return r.kind
            }
            """,
        )
        assertEquals(listOf("rules.mutability.field"), errors(p), TyperTestSupport.render(p))
        assertTrue(RulesTestSupport.message(p, "rules.mutability.field").startsWith("Reply is immutable: build a new one, or use copy"), RulesTestSupport.message(p, "rules.mutability.field"))
    }

    // ---- this in initially (1.2.11) -------------------------------------------------------------------

    @Test
    fun thisInInitiallyIsOnlyForItsOwnFieldsAndMethodsThatKeepIt() {
        val refused = TyperTestSupport.snippet(
            """
            pub mut LAST: Maybe<Seen> = null
            pub fx keep: (s: Seen) Void { }
            pub class Seen {
                pub n: Int32 = 0
                initially {
                    keep(this)
                }
            }
            pub class Stored {
                pub n: Int32 = 0
                initially {
                    publish()
                }
                pub fx publish: () Void {
                    keep2(this)
                }
            }
            pub fx keep2: (s: Stored) Void { }
            pub class Captured {
                pub n: Int32 = 0
                pub f: Maybe<Fx<Tuple0, Int32>> = null
                initially {
                    g: Fx<Tuple0, Int32> = fx() Int32 { return twice() }
                }
                pub fx twice: () Int32 { return n * 2 }
            }
            """,
        )
        val codes = errors(refused)
        assertEquals(3, codes.count { it == "rules.escape.this-in-initially" }, TyperTestSupport.render(refused))
        val allowed = TyperTestSupport.snippet(
            """
            pub class Reading {
                pub mm: Int32 = 1
                pub label: Str = ""
                initially {
                    if this.mm <= 0 {
                        mm = 1
                    }
                    label = describe()
                }
                pub fx describe: () Str {
                    return "mm=${'$'}{twice()}"
                }
                pub fx twice: () Int32 {
                    return mm * 2
                }
            }
            """,
        )
        clean(allowed)
    }

    // ---- copy (1.2.3, Q7) -------------------------------------------------------------------------------

    @Test
    fun copyIsASynthesizedCallOnAnImmutableClassThatNothingExtends() {
        val p = TyperTestSupport.snippet(
            """
            pub class Decoder {
                pub ticks: Int32 = 0
                pub primed: Bool = false
                hidden: Int32 = 0
                pub fx again: () Decoder {
                    return copy2()
                }
                pub fx copy2: () Decoder {
                    return this.copy(hidden = 1)
                }
            }
            pub fx feed: (d: Decoder, t: Int32) Decoder {
                return d.copy(primed = true, ticks = d.ticks + t)
            }
            pub fx same: (d: Decoder) Decoder {
                return d.copy()
            }
            """,
        )
        clean(p)
        val copies = BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { p.model.calls[it] }.filter { it.kind == CallKind.COPY }
        assertEquals(3, copies.size)
        val feed = copies.first { it.args.count { a -> a is net.exoad.kira.compiler.analysis.types.ArgBinding.Given } == 2 }
        assertEquals(listOf("ticks", "primed", "hidden"), feed.fn!!.params.map { it.name })
        assertEquals(listOf(1, 0), feed.sourceOrder, "the arguments in the order they were written")
    }

    @Test
    fun copyIsRefusedWhereItWouldSliceOrIsDeclaredOrAbsent() {
        val p = TyperTestSupport.snippet(
            """
            pub class Base {
                pub name: Str = ""
            }
            pub class Kid: Base {
                pub k: Int32 = 0
            }
            pub class Own {
                pub n: Int32 = 0
                pub fx copy: () Own { return this }
            }
            pub class Counter {
                pub mut n: Int32 = 0
            }
            pub class Secret {
                pub shown: Int32 = 0
                hidden: Int32 = 0
            }
            pub fx a: (b: Base) Base { return b.copy(name = "x") }
            pub fx b: (k: Kid) Kid { return k.copy(k = 1, name = "y") }
            pub fx c: (c: Counter) Counter { return c.copy(n = 1) }
            pub fx d: (s: Secret) Secret { return s.copy(hidden = 2) }
            """,
        )
        val codes = errors(p)
        assertTrue("types.copy.extended" in codes, TyperTestSupport.render(p))
        assertTrue("types.copy.declared" in codes, TyperTestSupport.render(p))
        assertTrue("types.member.unknown" in codes, TyperTestSupport.render(p))
        assertTrue("types.copy.private" in codes, TyperTestSupport.render(p))
        // A subclass nothing extends has its copy, its parent's fields first.
        assertTrue(codes.count { it == "types.copy.extended" } == 1, TyperTestSupport.render(p))
    }

    // ---- Weak (1.2.11, Q5) --------------------------------------------------------------------------------

    @Test
    fun aWeakOfAnImmutableClassIsRefusedWhereverItIsFormed() {
        val p = TyperTestSupport.snippet(
            """
            pub class Reply {
                pub kind: Int32 = 0
            }
            pub class Node {
                pub mut v: Int32 = 0
            }
            pub class Holder<T> {
                pub w: Maybe<Weak<T>> = null
            }
            pub fx declared: (w: Weak<Reply>) Int32 { return 0 }
            pub fx mutable: (w: Weak<Node>) Int32 { return 0 }
            pub fx inst: (h: Holder<Reply>) Int32 { return 0 }
            pub fx fine: (h: Holder<Node>) Int32 { return 0 }
            """,
        )
        val weak = p.diagnostics.filter { it.code == "types.weak.immutable" }
        assertEquals(2, weak.size, TyperTestSupport.render(p))
        assertTrue(weak.all { it.message.startsWith("Weak needs an object with identity: Reply is immutable") }, TyperTestSupport.render(p))
    }

    // ---- views, freestanding, constants (1.2.8, 1.2.9, D49) -------------------------------------------------

    @Test
    fun noClassHoldsAViewAValueClassNeitherByDecision4b() {
        val p = TyperTestSupport.snippet(
            """
            pub class Win {
                pub v: View<Int32>
                pub k: Size = 0
            }
            """,
        )
        assertTrue("rules.view.type" in errors(p), TyperTestSupport.render(p))
    }

    @Test
    fun aValueClassIsFreestandingAndAReferenceClassIsRefusedWithItsReason() {
        val options = TyperOptions(freestanding = listOf("pico:**"))
        val p = TyperTestSupport.snippet(
            """
            pub class Decoder {
                pub ticks: Int32 = 0
                pub edges: Int32 = 0
                initially {
                    if ticks < 0 {
                        ticks = 0
                    }
                }
                pub fx feed: (t: Int32) Decoder { return this.copy(ticks = ticks + t) }
            }
            pub class Span {
                pub lo: Int32 = 0
                pub hi: Int32 = 0
                @_const pub fx width: () Int32 { return hi - lo }
            }
            pub class Counter {
                mut n: Int32 = 0
            }
            """,
            uri = "pico:hall",
            options = options,
        )
        val refused = p.diagnostics.filter { it.code == "rules.profile.class" }
        assertEquals(1, refused.size, TyperTestSupport.render(p))
        assertTrue(refused.single().message.startsWith("Counter is mutable (field `n` is `mut`), so it is a shared reference"), refused.single().message)
        assertNull(p.diagnostics.firstOrNull { it.isError && it.code != "rules.profile.class" }, TyperTestSupport.render(p))
    }

    @Test
    fun aConstMethodOfAValueClassWithInitiallyIsRefusedItsConstructorIsNoConstexpr() {
        val p = TyperTestSupport.snippet(
            """
            pub class Reading {
                pub mm: Int32 = 1
                initially {
                    mm = mm + 1
                }
                @_const pub fx twice: () Int32 { return mm * 2 }
            }
            """,
        )
        assertTrue("rules.const.type" in errors(p), TyperTestSupport.render(p))
        assertTrue(RulesTestSupport.message(p, "rules.const.type").contains("its initially block makes its constructor no constexpr"), RulesTestSupport.message(p, "rules.const.type"))
    }

    @Test
    fun aModuleStateOfAValueClassStartsFromItsConstructionAsAStructsDoes() {
        val p = TyperTestSupport.snippet(
            """
            pub class V2 {
                pub x: Float32 = 0.0
                pub y: Float32 = 0.0
            }
            pub ORIGIN: V2 = V2 { 1.0, 2.0 }
            pub mut AT: V2 = V2 { y = 2.0 }
            """,
        )
        clean(p)
    }
}
