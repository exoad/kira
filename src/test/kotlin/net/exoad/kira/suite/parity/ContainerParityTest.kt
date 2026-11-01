package net.exoad.kira.suite.parity

import net.exoad.kira.suite.parity.ParityRun.assertBothPrint
import net.exoad.kira.suite.parity.ParityRun.assertCPrints
import org.junit.jupiter.api.Test

/** Element reads and for-in over containers print the same text on the C and JS backends. */
class ContainerParityTest {
    @Test
    fun indexReadsUseTheElementType() {
        // `a[i]` always read as Int32, so an Arr<Str> element was a truncated pointer.
        assertBothPrint("bob\n3\n1\n6\n") {
            """
            main: () Void {
                names: Arr<Str> = @arrOf("ada", "bob")
                trace(names[1])
                trace(names[0].length())
                flags: Arr<Bool> = @arrOf(true, false)
                trace(flags[0])
                nums: List<Int32> = List<Int32> { }
                nums.add(5)
                nums.add(6)
                trace(nums[1])
            }
            """
        }
    }

    @Test
    fun indexReadsOfInt64Elements() {
        assertCPrints("4294967296\n") {
            """
            main: () Void {
                wide: Arr<Int64> = @arrOf(4294967296)
                trace(wide[0])
            }
            """
        }
    }

    @Test
    fun forInIteratesArrListAndSet() {
        // The body used to run exactly once whatever the container held.
        assertBothPrint("6\nada\ngrace\n7\n3\n") {
            """
            main: () Void {
                xs: Arr<Int32> = @arrOf(1, 2, 3)
                sum: Mutable<Int32> = 0
                for x: Int32 in xs {
                    sum = sum + x
                }
                trace(sum)

                names: List<Str> = List<Str> { }
                names.add("ada")
                names.add("grace")
                for n: Str in names {
                    trace(n)
                }

                seen: Set<Int32> = Set<Int32> { }
                seen.add(3)
                seen.add(3)
                seen.add(4)
                total: Mutable<Int32> = 0
                for v: Int32 in seen {
                    total = total + v
                }
                trace(total)

                empty: List<Int32> = List<Int32> { }
                ran: Mutable<Int32> = 0
                for e: Int32 in empty {
                    ran = ran + 1
                }
                trace(3 - ran)
            }
            """
        }
    }
}
