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
                pub fx sign: (v: Int32) Int32 {
                    if v < 0 {
                        return -1
                    } else if v == 0 {
                        return 0
                    } else {
                        return 1
                    }
                }
                pub fx pick: (b: Bool) Int32 {
                    if b {
                        return 1
                    }
                    return 0
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
                use "kira:os"
                pub fx fail: (v: Int32) Int32 {
                    if v > 0 {
                        return v
                    }
                    throw "negative"
                }
                pub fx quit: () Int32 {
                    exit(1)
                }
                pub fx guarded: (v: Int32) Int32 {
                    try {
                        return v
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
                pub fx spin: () Int32 {
                    while true {
                        return 1
                    }
                }
                pub fx once: () Int32 {
                    do {
                        return 1
                    } while false
                }
                pub fx nested: () Int32 {
                    while true {
                        mut i: Int32 = 0
                        while i < 3 {
                            if i == 2 {
                                break
                            }
                            i += 1
                        }
                        return i
                    }
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
            pub fx sign: (v: Int32) Int32 {
                if v < 0 {
                    return -1
                } else if v > 0 {
                    return 1
                }
            }
            pub fx empty: () Int32 {
            }
            """,
        )
        expectExactly(p, "rules.return.missing", "rules.return.missing")
    }

    @Test
    fun aConditionalLoopMayExit() {
        val p = snippet(
            """
            pub fx count: (n: Int32) Int32 {
                mut i: Int32 = 0
                while i < n {
                    i += 1
                    return i
                }
            }
            pub fx first: (xs: Arr<Int32>) Int32 {
                for x: Int32 in xs {
                    return x
                }
            }
            pub fx broken: () Int32 {
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
            pub fx f: (b: Bool) Fx<Tuple1<Int32>, Int32> {
                return fx(x: Int32) Int32 {
                    if b {
                        return x
                    }
                }
            }
            """,
        )
        expectExactly(p, "rules.return.missing")
    }
}
