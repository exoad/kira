package net.exoad.kira.cpp.oop

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W2.9.3 round 1: a value class's construction keeps Kotlin's order where a default is not
 * pure (OQ-2), `==` on a value that holds a container of itself is the defaulted one, and
 * `copy(...)` in a declaration passes the legacy semantic pass the CLI runs first.
 */
class CppValueClassFixesTest {
    private val order = Module(
        "lang:ord",
        """
        mut log: Str = ""
        mut counter: Int32 = 0

        pub fx note: (s: Str, v: Int32) Int32 {
            log = log + s
            return v
        }

        pub fx bump: () Int32 {
            counter += 1
            return counter * 10
        }

        pub class Order {
            pub a: Int32 = note("A", 1)
            pub b: Int32 = note("B", 2)
            pub c: Int32 = note("C", 3)
        }

        // A default that only reads what a given value writes.
        pub class Snap {
            pub seen: Int32 = counter
            pub k: Int32 = 0
        }

        pub class Pure {
            pub a: Int32 = 1
            pub c: Int32 = 3
        }

        pub fx onlyC: () Int32 {
            log = ""
            o: Order = Order { c = note("c", 30) }
            return o.a + o.b + o.c
        }

        pub fx cThenA: () Int32 {
            log = ""
            o: Order = Order { c = note("c", 30), a = note("a", 10) }
            return o.a + o.b + o.c
        }

        pub fx onlyA: () Int32 {
            log = ""
            o: Order = Order { a = note("a", 10) }
            return o.a + o.b + o.c
        }

        pub fx snapped: () Int32 {
            counter = 0
            s: Snap = Snap { k = bump() }
            return s.seen * 100 + s.k
        }

        pub fx pure: () Int32 {
            log = ""
            p: Pure = Pure { c = note("c", 30) }
            return p.a + p.c
        }

        pub fx logNow: () Str {
            return log
        }
        """,
    )

    @Test
    fun aDefaultThatIsNotPureRunsAfterTheGivenValues() {
        val tree = CppExprTestSupport.emit("value-order-text", listOf(order))
        val s = tree.source(order)
        listOf(
            // The given value is spilled first; the aggregate's default member initializers run after it.
            "          const std::int32_t t0_ = note(\"c\", 30);\n          return Order{.c = t0_};\n",
            "          const std::int32_t t0_ = bump();\n          return Snap{.k = t0_};\n",
            // Nothing to order: the left-out defaults come after the given one, or are pure.
            "const Order o = Order{.a = note(\"a\", 10)};",
            "const Pure p = Pure{.c = note(\"c\", 30)};",
        ).forEach { assertTrue(s.contains(it), "expected to find:\n$it\nin:\n$s") }
    }

    @TestFactory
    fun theOrderIsKotlinsOnEveryCompiler(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("value order [${tc.id}]") {
            val tree = CppExprTestSupport.emit("value-order-run", listOf(order))
            val driver = buildString {
                append("#include \"src/lang/ord.kira.hxx\"\n")
                append(CppExprTestSupport.CHECK_PRELUDE)
                append(
                    """
                    int main()
                    {
                        check(ord::onlyC() == 33 && ord::logNow() == "cAB", "Order { c = note(c) }: c, then the defaults A and B");
                        check(ord::cThenA() == 42 && ord::logNow() == "caB", "Order { c = note(c), a = note(a) }: c, a, then B");
                        check(ord::onlyA() == 15 && ord::logNow() == "aBC", "Order { a = note(a) }: a, then B and C");
                        check(ord::snapped() == 110, "Snap { k = bump() }: bump runs before the default reads counter");
                        check(ord::pure() == 31 && ord::logNow() == "c", "pure defaults");
                        std::printf("\n%d checks, %d failed\n", checks, failures);
                        return failures == 0 ? 0 : 1;
                    }
                    """.trimIndent(),
                )
                append("\n")
            }
            val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
            assertTrue(stdout.contains("\n5 checks, 0 failed\n"), "${tc.id}:\n$stdout")
        }
    }

    private val trees = Module(
        "lang:trees",
        """
        pub class Tree {
            pub label: Str = ""
            pub kids: List<Tree> = List<Tree> { }
        }

        pub struct STree {
            pub label: Str = ""
            pub kids: List<STree> = List<STree> { }
        }

        pub fx sameEmpty: () Bool {
            t: Tree = Tree { label = "root" }
            return t == Tree { label = "root" }
        }

        pub fx sameKids: (second: Str) Bool {
            mut kids: List<Tree> = List<Tree> { }
            kids.add(Tree { label = "x" })
            mut others: List<Tree> = List<Tree> { }
            others.add(Tree { label = second })
            a: Tree = Tree { label = "root", kids = kids }
            b: Tree = Tree { label = "root", kids = others }
            return a == b
        }

        pub fx sameStruct: () Bool {
            t: STree = STree { label = "root" }
            return t == STree { label = "root" }
        }
        """,
    )

    @TestFactory
    fun equalityOnAValueHoldingAListOfItselfIsMemberwise(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("recursive == [${tc.id}]") {
            // CppUsage.supportsEquality walked Tree -> List<Tree> -> Tree with no end (StackOverflowError).
            val tree = CppExprTestSupport.emit("value-recursive-eq", listOf(trees))
            val h = tree.header(trees)
            assertTrue(h.contains("      kira::List<Tree> kids{};\n\n      bool operator==(const Tree&) const = default;\n"), h)
            val driver = buildString {
                append("#include \"src/lang/trees.kira.hxx\"\n")
                append(CppExprTestSupport.CHECK_PRELUDE)
                append(
                    """
                    int main()
                    {
                        check(trees::sameEmpty(), "two empty trees are equal");
                        check(trees::sameKids("x") && !trees::sameKids("y"), "the kids are compared");
                        check(trees::sameStruct(), "a struct holding a List of itself");
                        std::printf("\n%d checks, %d failed\n", checks, failures);
                        return failures == 0 ? 0 : 1;
                    }
                    """.trimIndent(),
                )
                append("\n")
            }
            val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
            assertTrue(stdout.contains("\n3 checks, 0 failed\n"), "${tc.id}:\n$stdout")
        }
    }

    @Test
    fun copyInADeclarationPassesTheSemanticPassTheCliRunsFirst() {
        val source = TestCompileSupport.wrapModule(
            "lang:copies",
            """
            pub class V {
                pub a: Int32 = 0
                pub b: Int32 = 0
            }

            pub fx f: (v: V) Int32 {
                w: V = v.copy(b = 2, a = 1)
                x: V = w.copy(a = 3)
                y: Int32 = v.frob(a = 1)
                return x.a + x.b + y
            }
            """,
        )
        val result = TestCompileSupport.compileSnippet(source, TestCompileSupport.logicalPathForModule("lang:copies"), runSemantic = true)
        val messages = result.semanticResults!!.diagnostics.map { it.message }
        assertTrue(messages.none { it.contains("'copy' is not a known function") }, messages.joinToString("\n"))
        // The exemption is copy's alone: a named argument to an unknown method is still refused.
        assertTrue(messages.any { it.contains("'frob' is not a known function") }, messages.joinToString("\n"))
    }
}
