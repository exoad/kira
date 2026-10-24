package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test

class ReturnPathPassTest {
    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun everyBranchReturning() {
        expectClean(
            snippet(
                """
                pub sign: (v: Int32) Int32 {
                    v < 0 ? -1 : v == 0 ? 0 : 1
                }
                pub pick: (b: Bool) Int32 {
                    b ? 1 : 0
                }
                """,
            ),
        )
    }

    @Test
    fun throwsAndNeverCallsEndAPath() {
        expectClean(
            snippet(
                """
                kira.os as os
                pub fail: (v: Int32) Int32 {
                    if v > 0 {
                        v
                    } else {
                        throw "negative"
                    }
                }
                pub quit: () Int32 {
                    exit(1)
                }
                pub guarded: (v: Int32) Int32 {
                    try {
                        v
                    } on e: Str {
                        throw e
                    }
                }
                """,
            ),
        )
    }

    @Test
    fun loopsThatNeverFallThrough() {
        expectClean(
            snippet(
                """
                pub spin: () Int32 {
                    mut result: Int32 = 0
                    while true {
                        result = 1
                        break
                    }
                    result
                }
                pub nested: () Int32 {
                    mut result: Int32 = 0
                    while true {
                        mut i: Int32 = 0
                        while i < 3 {
                            if i == 2 {
                                break
                            }
                            i += 1
                        }
                        result = i
                        break
                    }
                    result
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun anIfWithoutElseDoesNotEndEveryPath() {
        val p = snippet(
            """
            pub sign: (v: Int32) Int32 {
                if v < 0 {
                    -1
                } else if v > 0 {
                    1
                }
            }
            pub empty: () Int32 {
            }
            """,
        )
        expectExactly(p, "rules.return.missing", "rules.return.missing")
    }

    @Test
    fun aConditionalLoopMayExit() {
        val p = snippet(
            """
            pub count: (n: Int32) Int32 {
                mut i: Int32 = 0
                while i < n {
                    i += 1
                }
            }
            pub first: (xs: Arr<Int32>) Int32 {
                for x: Int32 in xs {
                    trace(x)
                }
            }
            pub broken: () Int32 {
                while true {
                    break
                }
            }
            """,
        )
        expectExactly(p, "rules.return.missing", "rules.return.missing", "rules.return.missing")
    }

    @Test
    fun aLambdaNeedsItsReturnToo() {
        val p = snippet(
            """
            pub f: (b: Bool) Fx<Tuple1<Int32>, Int32> {
                (x: Int32) Int32 {
                    if b {
                        x
                    }
                }
            }
            """,
        )
        expectExactly(p, "rules.return.missing")
    }
}
