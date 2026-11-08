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
            """,
        )
        val text = CppExprTestSupport.emit("with-class", listOf(m)).text(m)
        assertTrue(text.contains("const kira::Rc<Tally> w0_ = t;\n      {\n          w0_->bump(2);\n          w0_->count += 3;\n      }"), text)
        assertTrue(text.contains("const kira::Rc<Tally> w1_ = std::make_shared<Tally>();") && text.contains("const kira::Rc<Tally> u = w1_;"), text)
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
        assertTrue(Regex("""const kira::Rc<Tally> w0_ = t;\s+\{\s+w0_->bump\(5\);\s+\}\s+return w0_;""").containsMatchIn(text), text)
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
