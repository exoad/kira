package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** `with` on the C++ target: a Mutable class's block is lines before its statement, a value type's is refused. */
class CppWithTest {
    private val types = """
        pub Tally: Mutable<Any> {
            pub count: Mutable<Int32> { 0 }

            pub bump: (by: Int32) Void {
                count += by
            }
        }

        pub Pt: Struct {
            pub x: Int32
            pub y: Int32
        }
    """

    private fun module(body: String) = Module("w:w1", "$types\n${body.trimIndent()}")

    @Test
    fun aBlockOnAMutableClassIsLinesBeforeItsStatementAndEditsTheReceiver() {
        val m = module(
            """
            pub run: (t: Tally) Int32 {
                t with {
                    bump(2)
                    count += 3
                }
                u: Tally { } with {
                    bump(4)
                }
                t.count + u.count
            }

            pub made: () Tally {
                Tally { }
            }

            pub held: () Int32 {
                made() with {
                    bump(6)
                }
                0
            }
            """,
        )
        val text = CppExprTestSupport.emit("with-class", listOf(m)).text(m)
        // A parameter by its own name, a declaration's block in the local itself, any other receiver held once.
        assertTrue(text.contains("      {\n          t->bump(2);\n          t->count += 3;\n      }"), text)
        assertTrue(text.contains("const kira::Rc<Tally> u = std::make_shared<Tally>();\n      {\n          u->bump(4);\n      }"), text)
        assertTrue(text.contains("const kira::Rc<Tally> w0_ = made();\n      {\n          w0_->bump(6);\n      }"), text)
    }

    @Test
    fun aBlockOnAStdlibValueEditsItsOwnPlaceAndNeverACopy() {
        val m = module(
            """
            pub Bag: Mutable<Any> {
                pub items: Mutable<List<Int32>> { }

                pub fill: () Void {
                    items with {
                        add(1)
                        add(2)
                    }
                }
            }

            pub grow: (xs: List<Int32>, size: Int32) Size {
                xs with {
                    add(size)
                }
                ys: Mutable<List<Int32>> { } with {
                    add(3)
                }
                ys.size()
            }
            """,
        )
        val text = CppExprTestSupport.emit("with-stdlib", listOf(m)).text(m)
        // The parameter is taken by reference, as its explicit calls would make it.
        assertTrue(text.contains("grow(kira::List<std::int32_t>& xs, std::int32_t size)") && text.contains("          xs.push_back(size);"), text)
        assertTrue(text.contains("kira::List<std::int32_t> ys = kira::List<std::int32_t>{};\n      {\n          ys.push_back(3);\n      }"), text)
        // A Mutable field is held by reference for the block: its own List grows.
        assertTrue(text.contains("auto& w0_ = items;\n      {\n          w0_.push_back(1);\n          w0_.push_back(2);\n      }"), text)
    }

    @Test
    fun aBlockInAnIfExpressionArmRunsBeforeTheReturnThatHoldsIt() {
        val m = module(
            """
            pub pick: (t: Tally, c: Bool) Tally {
                if c {
                    t.bump(1)
                    t with {
                        bump(5)
                    }
                } else {
                    t.bump(2)
                    t
                }
            }
            """,
        )
        val text = CppExprTestSupport.emit("with-arm", listOf(m)).text(m)
        assertTrue(Regex("""t->bump\(1\);\s+\{\s+t->bump\(5\);\s+\}\s+return t;""").containsMatchIn(text), text)
    }

    @Test
    fun aWithOnAValueTypeIsRefusedWithWhyAndWhereItIsLowered() {
        val m = module(
            """
            pub shifted: (p: Pt) Pt {
                p with {
                    x = 5
                }
            }
            """,
        )
        val result = CppExprTestSupport.emitRefused("with-value", listOf(m))
        assertTrue(result.diagnostics.any { it.isError && it.render().contains("a value type") && it.render().contains("py target") }, result.diagnostics.joinToString("\n") { it.render() })
    }

    @Test
    fun theFoldHintRunsOnTheCppTarget() {
        val m = module(
            """
            pub run: (t: Tally) Void {
                t.bump(1)
                t.bump(2)
            }
            """,
        )
        val tree = CppExprTestSupport.emit("with-hint", listOf(m))
        assertTrue(tree.result.diagnostics.any { it.render().contains("rules.with.fold") }, tree.result.diagnostics.joinToString("\n") { it.render() })
    }
}
