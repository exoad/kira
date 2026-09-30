package net.exoad.kira.cpp.oop

import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Maybe of a Maybe keeps both levels (D32, round 6's W2.4 finding). Hosted, `Maybe<C>` of a
 * class is a nullable handle; it was the very `std::shared_ptr<C>` that `C` is, so
 * `kira::Maybe<kira::Maybe<kira::Rc<C>>>` collapsed into one shared_ptr and a stored null
 * (Some(None)) read as nothing there (None). It is now `kira::MaybeRc<C>`, the shared_ptr as a
 * type of its own, and a Maybe of it is a `kira::Nested` optional (rt.hxx, core.hxx); the
 * emitter's spelling is unchanged. A Maybe of a value Maybe was already two optionals, but a
 * bare `kira::none` met the inner one first: `Maybe<Maybe<Int32>> x = null` did not build.
 *
 * b10 is the round-6 verifier's probe (scratchpad/v6w24r/atk/b10) verbatim, its `fx main` made
 * `pub fx run`. Before the fix it printed 0/1/0 and then panicked, "unwrap of an empty Maybe",
 * on g++, zig c++ and MSVC; Kira's value is 1/1/0/1. b10s and b10v are its container, generic
 * and value twins.
 */
class CppMaybeNestingTest {
    private val b10 = Module(
        "oop:b10",
        """
        // b10: a Maybe of a Maybe of a class. Hosted C++ spells Maybe<Rc<C>> as a nullable shared_ptr,
        // and MaybeOf<shared_ptr<U>> is shared_ptr<U> again, so Maybe<Maybe<C>> may collapse to one
        // shared_ptr, in which Some(None) and None are the same value. Kira keeps them apart:
        // Map.get of a key mapped to null is Some(None); of a missing key, None.

        pub class Node {
            pub n: Int32 = 7
        }

        pub fx run: () Void {
            mut m: Map<Str, Maybe<Node>> = Map<Str, Maybe<Node>> { }
            m["a"] = null
            m["b"] = Node { }
            a: Maybe<Maybe<Node>> = m.get("a")
            b: Maybe<Maybe<Node>> = m.get("b")
            c: Maybe<Maybe<Node>> = m.get("c")
            // Kira: 1 (the key is there, mapped to null), 1, then 0 (no key)
            if a.isNull() {
                trace(0)
            } else {
                trace(1)
            }
            if b.isNull() {
                trace(0)
            } else {
                trace(1)
            }
            if c.isNull() {
                trace(0)
            } else {
                trace(1)
            }
            // Kira: 1 then 0: inner of a is null
            if a.value.isNull() {
                trace(1)
            } else {
                trace(0)
            }
        }
        """,
    )

    private val b10s = Module(
        "oop:b10s",
        """
        pub class Node {
            pub n: Int32 = 7
        }

        fx some<T>: (x: T) Maybe<T> {
            return x
        }

        fx level: (m: Maybe<Maybe<Node>>) Int32 {
            if m.isNull() {
                return 0
            }
            if m.value.isNull() {
                return 1
            }
            return 2
        }

        pub fx run: () Void {
            mut s: Stack<Maybe<Node>> = Stack<Maybe<Node>> { }
            s.push(null)
            s.push(Node { })
            trace(level(s.peek()))
            trace(level(s.pop()))
            trace(level(s.pop()))
            trace(level(s.pop()))
            mut q: Queue<Maybe<Node>> = Queue<Maybe<Node>> { }
            q.enqueue(null)
            trace(level(q.dequeue()))
            trace(level(q.dequeue()))
            nul: Maybe<Node> = null
            nn: Maybe<Node> = Node { }
            trace(level(some<Maybe<Node>>(nul)))
            trace(level(some<Maybe<Node>>(nn)))
            mut x: Maybe<Maybe<Node>> = null
            trace(level(x))
            x = some<Maybe<Node>>(nul)
            trace(level(x))
            x = some<Maybe<Node>>(nn)
            trace(level(x))
            x = null
            trace(level(x))
            mut m: Map<Str, Maybe<Node>> = Map<Str, Maybe<Node>> { }
            m["a"] = null
            trace(level(m.remove("a")))
            trace(level(m.remove("a")))
        }
        """,
    )

    private val b10v = Module(
        "oop:b10v",
        """
        fx level: (m: Maybe<Maybe<Int32>>) Int32 {
            if m.isNull() {
                return 0
            }
            if m.value.isNull() {
                return 1
            }
            return 2
        }

        pub fx run: () Void {
            mut m: Map<Str, Maybe<Int32>> = Map<Str, Maybe<Int32>> { }
            m["a"] = null
            m["b"] = 5
            trace(level(m.get("a")))
            trace(level(m.get("b")))
            trace(level(m.get("c")))
            mut x: Maybe<Maybe<Int32>> = null
            trace(level(x))
            x = m.get("a")
            trace(level(x))
            x = m.get("b")
            trace(level(x))
            x = null
            trace(level(x))
        }
        """,
    )

    private val tree by lazy { CppExprTestSupport.emit("oop-maybe-nesting", listOf(b10, b10s, b10v)) }

    /** Kira's values, derived from Kira's semantics: 0 is None, 1 is Some(None), 2 is Some(Some). */
    private val expected = listOf(
        "b10:", "1", "1", "0", "1",
        // peek, pop, pop, pop / dequeue, dequeue / some(null), some(node) / null, Some(None), Some(Some), null / remove, remove
        "b10s:", "2", "2", "1", "0", "1", "0", "1", "2", "0", "1", "2", "0", "1", "0",
        "b10v:", "1", "2", "0", "0", "1", "2", "0",
    )

    @Test
    fun theEmitterSpellsBothLevels() {
        val text = tree.text(b10)
        assertTrue(text.contains("kira::Map<kira::Str, kira::Maybe<kira::Rc<Node>>> m = "), text)
        assertTrue(text.contains("const kira::Maybe<kira::Maybe<kira::Rc<Node>>> a = m.get(\"a\");"), text)
        assertTrue(text.contains("if(!kira::isSome(kira::unwrap(a)))"), text)
        assertTrue(tree.text(b10v).contains("kira::Maybe<kira::Maybe<std::int32_t>> x = kira::none;"), tree.text(b10v))
    }

    @TestFactory
    fun aMaybeOfAMaybeKeepsBothLevelsOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("b10, b10s, b10v [${tc.id}]") {
                val driver = buildString {
                    listOf(b10, b10s, b10v).forEach { append("#include \"").append(it.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n") }
                    append("#include <cstdio>\n#include <type_traits>\n")
                    // The two levels are two C++ types; one level of a class stays the nullable shared_ptr.
                    append("static_assert(std::is_same_v<kira::Maybe<kira::Maybe<kira::Rc<b10::Node>>>, kira::Nested<kira::MaybeRc<b10::Node>>>);\n")
                    append("static_assert(std::is_base_of_v<std::shared_ptr<b10::Node>, kira::Maybe<kira::Rc<b10::Node>>>);\n")
                    append("static_assert(std::is_same_v<kira::Maybe<kira::Maybe<std::int32_t>>, kira::Nested<std::optional<std::int32_t>>>);\n")
                    append("int main()\n{\n")
                    append("    std::printf(\"b10:\\n\");\n    b10::run();\n")
                    append("    std::printf(\"b10s:\\n\");\n    b10s::run();\n")
                    append("    std::printf(\"b10v:\\n\");\n    b10v::run();\n")
                    append("    return 0;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
                assertEquals(expected, stdout.replace("\r", "").trim().lines(), "${tc.id}:\n$stdout")
            }
        }
}
