package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.modules
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class VisibilityPassTest {
    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun pubNamesCrossModules() {
        expectClean(
            modules(
                "test:lib" to """
                    pub SCALE: Int32 = 100
                    pub fx percent: (v: Int32) Int32 {
                        return clamp(v) * SCALE
                    }
                    fx clamp: (v: Int32) Int32 {
                        return v
                    }
                """,
                "test:main" to """
                    use "test:lib"
                    pub fx f: () Int32 {
                        return percent(3) + lib.percent(SCALE)
                    }
                """,
            ),
        )
    }

    @Test
    fun privateMembersInsideTheirType() {
        expectClean(
            snippet(
                """
                pub class Counter {
                    mut n: Int32 = 0
                    fx step: () Int32 {
                        return 1
                    }
                    pub mut fx bump: () Int32 {
                        n += step()
                        return this.n
                    }
                }
                pub trait Shape {
                    pub fx area: () Int32;
                    fx scale: () Int32 {
                        return 2
                    }
                    pub fx scaled: () Int32 {
                        return area() * scale()
                    }
                }
                """,
            ),
        )
    }

    @Test
    fun constructionSuppliesRequireFieldsWhateverTheirVisibility() {
        expectClean(
            snippet(
                """
                pub class Cfg {
                    require n: Int32
                    pub fx get: () Int32 {
                        return n
                    }
                }
                pub struct Pt {
                    x: Int32 = 0
                }
                pub fx f: () Int32 {
                    c: Cfg = Cfg { n = 1 }
                    d: Cfg = Cfg { 2 }
                    p: Pt = Pt { x = 3 }
                    return c.get() + d.get()
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aPrivateFunctionOrConstantOfAnotherModule() {
        val p = modules(
            "test:lib" to """
                LIMIT: Int32 = 3
                fx helper: () Int32 {
                    return LIMIT
                }
                pub fx ok: () Int32 {
                    return 1
                }
            """,
            "test:main" to """
                use "test:lib"
                pub fx f: () Int32 {
                    return helper() + LIMIT + lib.helper() + ok()
                }
            """,
        )
        expectExactly(p, "rules.visibility.module", "rules.visibility.module", "rules.visibility.module")
        assertTrue(message(p, "rules.visibility.module").contains("'helper' is declared in test:lib but is not pub"))
    }

    @Test
    fun aPrivateMemberOutsideItsClass() {
        val p = snippet(
            """
            pub class Counter {
                mut n: Int32 = 0
                fx step: () Int32 {
                    return 1
                }
                pub mut fx bump: () Void {
                    n += 1
                }
            }
            pub fx f: (c: Counter) Int32 {
                c.bump()
                c.n = 2
                return c.n + c.step()
            }
            """,
        )
        expectExactly(p, "rules.visibility.member", "rules.visibility.member", "rules.visibility.member")
        assertTrue(message(p, "rules.visibility.member").contains("Field 'n' of Counter is not pub"))
    }

    @Test
    fun aSubclassIsOutsideItsParent() {
        val p = snippet(
            """
            pub class Base {
                mut n: Int32 = 0
                fx secret: () Int32 {
                    return n
                }
            }
            pub class Derived: Base {
                pub fx peek: () Int32 {
                    return n + secret()
                }
            }
            """,
        )
        expectExactly(p, "rules.visibility.member", "rules.visibility.member")
    }
}
