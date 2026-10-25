package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test

class MutabilityPassTest {
    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aWriteToACaptureIsPhaseCsAlone() {
        // Phase C reports the capture; the pass does not add a second diagnostic for the same write.
        val p = snippet(
            """
            pub f: () Void {
                n: Int32 = 0
                g: Fx<Tuple0, Void> = () Void {
                    n = 1
                }
            }
            """,
        )
        expectExactly(p, "types.lambda.assign-capture")
    }
}
