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
                names: Arr<Str> { @arrOf("ada", "bob") }
                trace(names[1])
                trace(names[0].length())
                flags: Arr<Bool> { @arrOf(true, false) }
                trace(flags[0])
                nums: List<Int32> { }
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
                wide: Arr<Int64> { @arrOf(4294967296) }
                trace(wide[0])
            }
            """
        }
    }

    @Test
    fun indexWalksIterateArrListAndSet() {
        // The body used to run exactly once whatever the container held.
        assertBothPrint("6\nada\ngrace\n3\n") {
            """
            main: () Void {
                xs: Arr<Int32> { @arrOf(1, 2, 3) }
                sum: Mutable<Int32> { 0 }
                xIndex: Mutable<Int32> { 0 }
                while xIndex < xs.size() {
                    x: Int32 { xs.get(xIndex) }
                    sum = sum + x
                    xIndex += 1
                }
                trace(sum)

                names: List<Str> { }
                names.add("ada")
                names.add("grace")
                nIndex: Mutable<Int32> { 0 }
                while nIndex < names.size() {
                    n: Str { names.get(nIndex) }
                    trace(n)
                    nIndex += 1
                }

                empty: List<Int32> { }
                ran: Mutable<Int32> { 0 }
                eIndex: Mutable<Int32> { 0 }
                while eIndex < empty.size() {
                    e: Int32 { empty.get(eIndex) }
                    ran = ran + 1
                    eIndex += 1
                }
                trace(3 - ran)
            }
            """
        }
    }
}
