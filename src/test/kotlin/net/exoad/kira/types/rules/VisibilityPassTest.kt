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
                    pub percent: (v: Int32) Int32 {
                        clamp(v) * SCALE
                    }
                    clamp: (v: Int32) Int32 {
                        v
                    }
                """,
                "test:main" to """
                    test.lib as lib
                    test.lib.percent as percent
                    test.lib.SCALE as SCALE
                    pub f: () Int32 {
                        percent(3) + lib.percent(SCALE)
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
                pub Counter: Any {
                    mut n: Int32 = 0
                    step: () Int32 {
                        1
                    }
                    pub mut bump: () Int32 {
                        n += step()
                        this.n
                    }
                }
                pub Shape: Any {
                    pub area: () Int32;
                    scale: () Int32 {
                        2
                    }
                    pub scaled: () Int32 {
                        area() * scale()
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
                pub Cfg: Any {
                    n: Int32
                    pub get: () Int32 {
                        n
                    }
                }
                pub Pt: Struct {
                    x: Int32 = 0
                }
                pub f: () Int32 {
                    c: Cfg = Cfg { n = 1 }
                    d: Cfg = Cfg { 2 }
                    p: Pt = Pt { x = 3 }
                    c.get() + d.get()
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
                helper: () Int32 {
                    LIMIT
                }
                pub ok: () Int32 {
                    1
                }
            """,
            "test:main" to """
                test.lib as lib
                test.lib.ok as ok
                pub f: () Int32 {
                    helper() + LIMIT + lib.helper() + ok()
                }
            """,
        )
        // Bare, a private name is out of scope (stage 5); qualified, the visibility rule refuses it.
        expectExactly(p, "types.use.bare", "types.use.bare", "rules.visibility.module")
        assertTrue(message(p, "rules.visibility.module").contains("'helper' is declared in test:lib but is not pub"))
        assertTrue(message(p, "types.use.bare").contains("it is not pub there"), message(p, "types.use.bare"))
    }

    @Test
    fun aPrivateMemberOutsideItsClass() {
        val p = snippet(
            """
            pub Counter: Any {
                mut n: Int32 = 0
                step: () Int32 {
                    1
                }
                pub mut bump: () Void {
                    n += 1
                }
            }
            pub f: (c: Counter) Int32 {
                c.bump()
                c.n = 2
                c.n + c.step()
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
            pub Base: Any {
                mut n: Int32 = 0
                secret: () Int32 {
                    n
                }
            }
            pub Derived: Base {
                pub peek: () Int32 {
                    n + secret()
                }
            }
            """,
        )
        expectExactly(p, "rules.visibility.member", "rules.visibility.member")
    }
}
