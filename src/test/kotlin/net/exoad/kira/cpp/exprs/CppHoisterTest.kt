package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R19 and D33 on the emitted text: which calls, reads and operators are spilled into typed
 * temporaries or bound by reference, and which are left alone (the evalorder golden runs
 * the same rules on gcc, clang and msvc). Without EffectsPass (W2.5) a call's absent effect
 * is impure, a stdlib binding marked `pure: true` is pure, a read of a `mut` global, a
 * field or a by-reference parameter is READS, and an operand holding neither is pure.
 */
class CppHoisterTest {
    private val module = Module(
        "hoist:cases",
        """
        mut ticks: Int32 = 0
        mut idx: Size = 0

        fx next: () Int32 {
            ticks += 1
            return ticks
        }

        fx nextSize: () Size {
            idx += 1
            return idx
        }

        fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }

        fx put: (mut into: Int32, a: Int32, b: Int32) Void {
            into = a - b
        }

        pub struct Box {
            pub v: Int32 = 0

            pub fx pair: (a: Int32, b: Int32) Int32 {
                return v * 100 + a * 10 + b
            }
        }

        fx makeBox: () Box {
            ticks += 1
            return Box { v = ticks }
        }

        pub fx readBeside: () Int32 {
            return sub(ticks, next())
        }

        pub fx readAfter: () Int32 {
            return sub(next(), ticks)
        }

        pub fx twoReads: () Int32 {
            return sub(ticks, ticks)
        }

        pub fx localBeside: (x: Int32) Int32 {
            return sub(x, next())
        }

        pub fx placeIndex: () Int32 {
            mut q: Arr<Int32, 4> = [0, 0, 0, 0]
            put(mut q[nextSize()], next(), next())
            return q[1]
        }

        pub fx receiverFirst: () Int32 {
            return makeBox().pair(next(), next())
        }

        pub fx localReceiver: (b: Box) Int32 {
            return b.pair(next(), next())
        }

        pub fx targetFirst: () Void {
            mut s: Arr<Int32, 4> = [0, 0, 0, 0]
            s[nextSize()] = next()
            s[0] = next()
        }

        pub fx twoImpure: () Int32 {
            return sub(next(), next())
        }

        pub fx oneImpure: (x: Int32) Int32 {
            return sub(next(), x + 1)
        }

        pub fx operands: () Int32 {
            return next() * next()
        }

        pub fx shortCircuit: () Bool {
            return next() > 0 && next() > 1
        }

        pub fx mutStays: () Int32 {
            mut slot: Int32 = 0
            put(mut slot, next(), next())
            return slot
        }

        pub fx pureBindings: (s: Str) Size {
            return s.length() + s.length()
        }

        pub fx holes: () Str {
            return "${'$'}{next()}-${'$'}{next()}"
        }

        pub fx nested: () Int32 {
            return sub(sub(next(), next()), next())
        }
        """,
    )

    private val text by lazy { CppExprTestSupport.emit("hoister", listOf(module)).source(module) }

    /** The body of the exported function [fn]'s definition (module namespace, two-space indent). */
    private fun body(fn: String): String {
        val head = Regex("\\n  [^\\n ][^\\n]* $fn\\([^\\n]*\\)\\n  \\{\\n").find(text) ?: error("no definition of $fn in:\n$text")
        val close = text.indexOf("\n  }\n", head.range.last)
        return text.substring(head.range.last, close)
    }

    @Test
    fun twoImpureArgumentsAreSpilledInSourceOrder() {
        val b = body("twoImpure")
        assertTrue(b.contains("return [&]() -> std::int32_t\n      {\n          const std::int32_t t0_ = next();\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);\n      }();"), b)
    }

    @Test
    fun oneImpureArgumentIsNot() {
        assertTrue(body("oneImpure").contains("return sub(next(), x + 1);"), body("oneImpure"))
    }

    @Test
    fun anOperatorsOperandsAreSpilledToo() {
        assertTrue(body("operands").contains("return t0_ * t1_;"), body("operands"))
    }

    @Test
    fun theRightSideOfAndIsNeverSpilled() {
        val b = body("shortCircuit")
        assertTrue(b.contains("return next() > 0 && next() > 1;"), b)
        assertFalse(b.contains("[&]"), b)
    }

    @Test
    fun aMutArgumentIsAPlaceAndStaysOne() {
        val b = body("mutStays")
        assertTrue(b.contains("put(slot, t0_, t1_);"), b)
        assertFalse(b.contains("= slot;"), "a mut argument is never copied into a temporary:\n$b")
    }

    @Test
    fun pureBindingsNeedNoSpill() {
        assertTrue(body("pureBindings").contains("return kira::str::length(s) + kira::str::length(s);"), body("pureBindings"))
    }

    @Test
    fun interpolationHolesAreArgumentsOfCat() {
        assertTrue(body("holes").contains("return kira::cat(t0_, \"-\", t1_);"), body("holes"))
    }

    @Test
    fun aReadOfSharedStateBesideAnEffectIsCopiedFirst() {
        val b = body("readBeside")
        assertTrue(b.contains("const std::int32_t t0_ = ticks;\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);"), b)
        val after = body("readAfter")
        assertTrue(after.contains("const std::int32_t t0_ = next();\n          const std::int32_t t1_ = ticks;\n          return sub(t0_, t1_);"), after)
    }

    @Test
    fun twoReadsOrALocalBesideAnEffectNeedNothing() {
        assertTrue(body("twoReads").contains("return sub(ticks, ticks);"), body("twoReads"))
        assertTrue(body("localBeside").contains("return sub(x, next());"), body("localBeside"))
    }

    @Test
    fun aMutArgumentsImpureIndexIsBoundByReferenceInOrder() {
        val b = body("placeIndex")
        assertTrue(b.contains("std::int32_t& r0_ = kira::at(q, nextSize());\n          const std::int32_t t0_ = next();\n          const std::int32_t t1_ = next();\n          put(r0_, t0_, t1_);"), b)
    }

    @Test
    fun anImpureReceiverIsSpilledBeforeTheArguments() {
        val b = body("receiverFirst")
        assertTrue(b.contains("const Box t0_ = makeBox();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);"), b)
        // A struct parameter is read by const& (READS): copied before the arguments run.
        val local = body("localReceiver")
        assertTrue(local.contains("const Box t0_ = b;\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);"), local)
    }

    @Test
    fun aPlaceAssignmentLocatesItsTargetBeforeAnImpureValue() {
        val b = body("targetFirst")
        assertTrue(b.contains("std::int32_t& r0_ = kira::at(s, nextSize());\n          const std::int32_t t0_ = next();\n          r0_ = t0_;"), b)
        assertTrue(b.contains("kira::at(s, 0) = next();"), b)
    }

    @Test
    fun aNestedSpillNeverReusesAnEnclosingTemporarysName() {
        val b = body("nested")
        // The inner IIFE sits in t0_'s initializer, where t0_ is already declared (-Wshadow).
        assertTrue(b.contains("const std::int32_t t0_ = [&]() -> std::int32_t"), b)
        assertTrue(b.contains("const std::int32_t t1_ = next();\n              const std::int32_t t2_ = next();\n              return sub(t1_, t2_);"), b)
        assertTrue(b.contains("return sub(t0_, t1_);"), b)
    }
}
