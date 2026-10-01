package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W2.9 1.2.2 and 1.2.3, the value lowering: an immutable class is the struct a struct with the
 * same fields is, `initially` gives it constructors, `copy` builds a new value from a receiver
 * read once, a reference class copies through `copy_`, and the shapes compile and run on gcc,
 * clang and MSVC (and for the Pico, freestanding).
 */
class CppValueClassTest {
    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    /** Every module of a golden case, each without its `module` line, as [Module] writes that itself. */
    private fun goldenModules(case: String): List<Module> =
        File("src/test/resources/cpp-golden/$case/src").walkTopDown().filter { it.isFile && it.extension == "kira" }.sortedBy { it.path }.map { f ->
            val text = f.readText().replace("\r\n", "\n")
            Module(text.lineSequence().first().substringAfter("module \"").substringBefore('"'), text.substringAfter('\n'))
        }.toList()

    @Test
    fun aStructReSourcedAsAClassEmitsTheSameTextByteForByte() {
        // closures' Gain captures a field ([c_k = k]) and its own copy ([*this]); modules' Line is a plain aggregate another module reads.
        for ((case, struct) in listOf("closures" to "Gain", "modules" to "Line")) {
            val asStruct = goldenModules(case)
            assertTrue(asStruct.any { it.body.contains("pub struct $struct {") }, "$case still declares struct $struct")
            val asClass = asStruct.map { it.copy(body = it.body.replace("pub struct $struct {", "pub class $struct {")) }
            val a = CppExprTestSupport.emit("value-resource-$case-struct", asStruct)
            val b = CppExprTestSupport.emit("value-resource-$case-class", asClass)
            asStruct.zip(asClass).forEach { (s, c) ->
                assertEquals(a.header(s), b.header(c), "$case: ${s.uri}'s header with class $struct is the struct's")
                assertEquals(a.source(s), b.source(c), "$case: ${s.uri}'s source with class $struct is the struct's")
            }
        }
    }

    private val values = Module(
        "lang:vals",
        """
        pub class V {
            pub a: Int32 = 0
            pub b: Int32 = 0
            pub c: Int32 = 0

            pub fx sum: () Int32 {
                return a + b + c
            }
        }

        pub class Reading {
            pub mm: Int32 = 1
            pub label: Str = ""

            initially {
                if mm <= 0 {
                    mm = 1
                }
            }

            pub fx twice: () Int32 {
                return mm * 2
            }
        }

        pub class Single {
            pub v: Int32 = 0

            initially {
                v = v + 100
            }
        }

        pub class Named {
            pub name: Str
            tag: Int32 = 0

            pub fx tagOf: () Int32 {
                return tag
            }
        }

        pub class Pet: Named {
            age: Int32 = 2

            pub fx ageOf: () Int32 {
                return age
            }
        }

        pub class Tree {
            pub mut kids: List<Kid> = List<Kid> {}

            pub mut fx clear: () Int32 {
                kids = List<Kid> {}
                return 7
            }
        }

        // A value in a List whose method empties the List: the receiver is the caller's copy (invariant I).
        pub class Kid {
            pub name: Str
            pub tree: Tree

            pub fx leave: () Str {
                tree.clear()
                return name
            }
        }

        mut G: V = V { a = 0, b = 0, c = 0 }
        mut calls: Int32 = 0

        pub fx next: () V {
            calls += 1
            return V { a = 1, b = 1, c = 1 }
        }

        pub fx bumpG: () Int32 {
            G = V { a = 0, b = 9, c = 0 }
            return 1
        }

        pub fx copyNext: () V {
            return next().copy(a = 0)
        }

        pub fx copyG: () V {
            return G.copy(a = bumpG())
        }

        pub fx callsSoFar: () Int32 {
            return calls
        }

        pub fx gNow: () V {
            return G
        }

        pub fx copyLocal: (v: V) V {
            w: V = v.copy(c = 5)
            return w.copy(a = w.sum())
        }

        pub fx relabel: (r: Reading, s: Str) Reading {
            return r.copy(label = s)
        }

        pub fx makeReading: (m: Int32) Reading {
            return Reading { mm = m, label = "x" }
        }

        pub fx renamed: (p: Pet) Pet {
            return p.copy(name = "rex")
        }

        pub fx makePet: () Pet {
            return Pet { name = "mochi", tag = 3, age = 4 }
        }

        pub fx equalV: (x: V, y: V) Bool {
            return x == y
        }

        pub fx leaveOwn: () Str {
            t: Tree = Tree {}
            t.kids.add(Kid { name = "a kid's name long enough to live on the heap 0000", tree = t })
            return t.kids[0].leave()
        }
        """,
    )

    @Test
    fun aValueClassIsAStructAndInitiallyGivesItConstructors() {
        val tree = CppExprTestSupport.emit("value-shapes", listOf(values))
        val h = tree.header(values)
        val s = tree.source(values)
        assertContains(
            h,
            "  struct V;\n  struct Reading;\n  struct Single;\n  class Named;\n  class Pet;\n",
            "  struct V\n  {\n      std::int32_t a = 0;\n      std::int32_t b = 0;\n      std::int32_t c = 0;\n\n      [[nodiscard]] std::int32_t sum() const;\n      bool operator==(const V&) const = default;\n  };",
            "  struct Reading\n  {\n      std::int32_t mm = 1;\n      kira::Str label = \"\";\n\n      Reading();\n      Reading(std::int32_t mm_, kira::Str label_);\n      [[nodiscard]] std::int32_t twice() const;\n  };",
            "      Single();\n      explicit Single(std::int32_t v_);\n",
            // A subclass's copy_ keeps the parent's private field from *this: protected in C++, private in Kira.
            "  protected:\n      std::int32_t tag;\n",
            "      struct Copy_\n      {\n          std::optional<kira::Str> name{};\n          std::optional<std::int32_t> tag{};\n          std::optional<std::int32_t> age{};\n      };\n",
            "      [[nodiscard]] kira::Rc<Pet> copy_(const Copy_& w_) const\n",
        )
        assertContains(
            s,
            "  Reading::Reading()\n      : Reading{1, \"\"}\n  {\n  }\n",
            "  Reading::Reading(std::int32_t mm_, kira::Str label_)\n      : mm(mm_), label(std::move(label_))\n  {\n      if(mm <= 0)\n",
            // The receiver is read once, before the arguments (D33): a typed temporary, unless a PURE place.
            "      return [&]() -> V\n      {\n          const V t0_ = next();\n          return V{.a = 0, .b = t0_.b, .c = t0_.c};\n      }();",
            "          const V t0_ = G;\n          const std::int32_t t1_ = bumpG();\n          return V{.a = t1_, .b = t0_.b, .c = t0_.c};\n",
            "      return Reading{r.mm, s};",
            "      return Reading{m, \"x\"};",
            "      return p->copy_({.name = \"rex\"});",
        )
    }

    @TestFactory
    fun valueClassesRunOnEveryCompiler(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("values [${tc.id}]") {
            val tree = CppExprTestSupport.emit("value-run", listOf(values))
            val driver = buildString {
                append("#include \"src/lang/vals.kira.hxx\"\n#include <type_traits>\n")
                append(CppExprTestSupport.CHECK_PRELUDE)
                append(
                    """
                    static_assert(std::is_aggregate_v<vals::V> && std::is_trivially_copyable_v<vals::V> && sizeof(vals::V) == 3 * sizeof(std::int32_t));
                    static_assert(!std::is_aggregate_v<vals::Reading> && std::is_default_constructible_v<vals::Reading> && std::is_copy_constructible_v<vals::Reading>);
                    static_assert(!std::is_convertible_v<std::int32_t, vals::Single>, "explicit for one field");

                    int main()
                    {
                        const vals::V n = vals::copyNext();
                        check(n.a == 0 && n.b == 1 && n.c == 1 && vals::callsSoFar() == 1, "next().copy(a = 0): {0, 1, 1}, next() once");
                        const vals::V g = vals::copyG();
                        const vals::V now = vals::gNow();
                        check(g.a == 1 && g.b == 0 && g.c == 0 && now.b == 9, "G.copy(a = bumpG()): {1, 0, 0}, G read before bumpG ran");
                        const vals::V l = vals::copyLocal(vals::V{1, 2, 3});
                        check(l.a == 8 && l.b == 2 && l.c == 5, "a copy of a local copy");
                        const vals::Reading r = vals::makeReading(-3);
                        const vals::Reading s = vals::relabel(r, "y");
                        check(r.mm == 1 && r.label == "x" && s.mm == 1 && s.label == "y" && s.twice() == 2, "initially runs at construction and at copy");
                        const vals::Reading d;
                        const vals::Single one;
                        check(d.mm == 1 && d.label.empty() && one.v == 100, "the default constructor delegates and runs initially");
                        const kira::Rc<vals::Pet> p = vals::makePet();
                        const kira::Rc<vals::Pet> q = vals::renamed(p);
                        check(p->name == "mochi" && q->name == "rex" && q->ageOf() == 4 && q->tagOf() == 3 && p.get() != q.get(), "copy_ keeps what the call leaves out, a protected parent field included");
                        check(vals::equalV(vals::V{1, 2, 3}, vals::V{1, 2, 3}) && !vals::equalV(vals::V{1, 2, 3}, vals::V{.c = 3}), "== compares the fields");
                        check(vals::leaveOwn() == "a kid's name long enough to live on the heap 0000", "a value's method that empties the List it lives in");
                        std::printf("\n%d checks, %d failed\n", checks, failures);
                        return failures == 0 ? 0 : 1;
                    }
                    """.trimIndent(),
                )
                append("\n")
            }
            val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
            assertTrue(stdout.contains("\n8 checks, 0 failed\n"), "${tc.id}:\n$stdout")
        }
    }

    @Test
    fun aValueClassConstructionThatLeavesAHandleWithoutAValueIsRefused() {
        val result = CppExprTestSupport.emitRefused(
            "value-skipped",
            listOf(
                Module(
                    "lang:skip",
                    """
                    pub class Node {
                        pub mut n: Int32 = 0
                    }
                    pub class Holder {
                        pub node: Node
                        require pub k: Int32
                    }
                    pub fx make: () Holder {
                        return Holder { k = 1 }
                    }
                    """,
                ),
            ),
        )
        assertTrue(result.diagnostics.any { it.message.contains("constructing Holder leaves the field node: Node without a value") }, result.diagnostics.joinToString("\n") { it.render() })
    }

    @Test
    fun aValueClassHoldingAViewIsTheBackstopsInternalErrorPastTheChecker() {
        // Decision 4b refuses the field (rules.view.type, ValueClassTypingTest); past the checker the emitter looks
        // into a value class as it does into a struct (review 1 #6: CppHoister and the classes part saw a class as a
        // handle, whose fields are its object's, and lowered `Win { v = gl }` as no view at all).
        val past = withoutRulePasses {
            net.exoad.kira.cpp.decls.DeclTestSupport.emit(
                net.exoad.kira.cpp.decls.DeclTestSupport.module(
                    "vl:win",
                    """
                    mut gl: List<Int32> = []
                    pub class Win {
                        pub v: View<Int32>
                        pub k: Size = 0
                    }
                    pub fx viewInAField: () Size {
                        w: Size = Win { v = gl, k = 1 }.k
                        return w
                    }
                    """,
                ),
            )
        }
        val internal = past.diagnostics("vl:win").filter { it.code == "cpp.internal" }.map { it.message }
        assertTrue(internal.any { it.contains("holds a view in 'v'") }, internal.joinToString("\n"))
    }

    @TestFactory
    fun aFreestandingValueClassBuildsForThePico(): List<DynamicNode> = listOf(CppToolchain.ARM, CppToolchain.GCC).map { tc ->
        DynamicTest.dynamicTest("freestanding values [${tc.id}]") {
            val pico = Module(
                "pico:hall",
                """
                pub class Decoder {
                    pub ticks: Int32 = 0
                    pub edges: Int32 = 0

                    initially {
                        if ticks < 0 {
                            ticks = 0
                        }
                    }

                    pub fx doubled: () Int32 {
                        return ticks * 2
                    }

                    pub fx feed: (t: Int32) Decoder {
                        return this.copy(ticks = ticks + t, edges = edges + 1)
                    }
                }

                pub class Span {
                    pub lo: Int32 = 0
                    pub hi: Int32 = 0

                    @_const pub fx width: () Int32 {
                        return hi - lo
                    }
                }

                pub mut span: Span = Span { lo = 1, hi = 1 }

                // Pico state is a value, rebound (Q6): a module-level value class, and a local one with initially.
                pub fx step: (t: Int32) Int32 {
                    span = span.copy(hi = span.hi + t)
                    d: Decoder = Decoder { ticks = t }
                    return d.feed(t).doubled() + span.width()
                }
                """,
            )
            val tree = CppExprTestSupport.emit("value-freestanding", listOf(pico), CppOptions(lineDirectives = false, freestanding = listOf("pico:**")))
            val driver = """
                #include "src/pico/hall.kira.hxx"

                static_assert(hall::Span{1, 4}.width() == 3, "a @_const method of a value class is constexpr");

                #if !KIRA_PROFILE_HOSTED
                [[noreturn]] void kira::panic(const char*) noexcept
                {
                    for(;;)
                    {
                    }
                }
                #endif

                int main()
                {
                    static_cast<void>(hall::step(3));
                    return hall::step(4) == 16 + 7 && hall::Decoder{-5, 0}.ticks == 0 ? 0 : 1;
                }
            """.trimIndent() + "\n"
            val out = CppExprTestSupport.compileAndRun(tree, driver, tc)
            assertTrue(out == null || out.isEmpty(), "${tc.id}: $out")
        }
    }

    private fun <T> withoutRulePasses(block: () -> T): T {
        val rules = net.exoad.kira.compiler.analysis.types.KiraTyper.rulePasses.toList()
        net.exoad.kira.compiler.analysis.types.KiraTyper.rulePasses.clear()
        try {
            return block()
        } finally {
            net.exoad.kira.compiler.analysis.types.KiraTyper.rulePasses.addAll(rules)
        }
    }
}
