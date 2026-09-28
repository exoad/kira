package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import net.exoad.kira.types.rules.RulesTestSupport.warnings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingPassTest {
    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun conventionalNamesWarnNothing() {
        expectClean(
            snippet(
                """
                pub MAX_SIZE: Int32 = 3
                pub mut counter: Int32 = 0
                pub mut BUF: Arr<UInt8, 2> = [0, 0]
                pub enum Status: Int32 {
                    STATUS_OK = 0
                }
                pub trait Comparable<T> {
                    pub fx compareTo: (other: T) Int32;
                }
                pub class HttpClient {
                    pub mut retryCount: Int32 = 0
                    pub fx fetchAll: (maxItems: Int32) Int32 {
                        totalCount: Int32 = maxItems
                        for i: Int32 in 0..totalCount {
                        }
                        return totalCount
                    }
                }
                pub alias Id2 as Int64
                """,
            ),
        )
    }

    @Test
    fun foreignAndOperatorNamesAreTheirOwn() {
        expectClean(
            snippet(
                """
                @_extern(cpp = "ext::ReadBytes", header = "ext.hxx")
                pub fx ReadBytes: () Int32;
                pub struct V2 {
                    pub x: Int32 = 0
                }
                pub fx @op_add: (a: V2, b: V2) V2 {
                    return V2 { a.x + b.x }
                }
                """,
            ),
        )
    }

    @Test
    fun theStdlibIsNotChecked() {
        // kira:core's `true`, `false` and `null` are lowercase constants; a user module never hears about them.
        expectClean(snippet("pub fx f: () Bool {\n    return true\n}"))
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun functionsVariablesAndTypes() {
        // The parser already refuses underscores outside UPPER_SNAKE_CASE; the pass sees the case violations.
        val p = snippet(
            """
            pub class point {
                pub Xpos: Int32 = 0
            }
            pub trait comparable<t> {
                pub fx CompareTo: (Other: t) Int32;
            }
            pub fx DoThing: (Value: Int32) Int32 {
                MyVar: Int32 = Value
                for Idx: Int32 in 0..MyVar {
                }
                return MyVar
            }
            """,
        )
        assertTrue(p.diagnostics.all { it.severity == Severity.WARNING }, "warnings only")
        assertEquals(
            listOf(
                "rules.naming.type", "rules.naming.variable",
                "rules.naming.type", "rules.naming.type", "rules.naming.function", "rules.naming.variable",
                "rules.naming.function", "rules.naming.variable",
                "rules.naming.variable", "rules.naming.variable",
            ),
            warnings(p),
            p.diagnostics.joinToString("\n") { it.render() },
        )
        assertTrue(p.diagnostics.any { it.message.contains("did you mean 'doThing'") }, p.diagnostics.joinToString("\n") { it.render() })
        assertTrue(p.diagnostics.any { it.message.contains("did you mean 'Point'") })
        assertTrue(p.diagnostics.any { it.message.contains("did you mean 'myVar'") })
    }

    @Test
    fun constantsAndEnumEntries() {
        val p = snippet(
            """
            pub maxSize: Int32 = 3
            pub Limit: Int32 = 4
            pub enum Status: Int32 {
                ready = 0,
                Running = 1
            }
            """,
        )
        assertEquals(listOf("rules.naming.constant", "rules.naming.constant", "rules.naming.constant", "rules.naming.constant"), warnings(p))
        assertTrue(message(p, "rules.naming.constant").contains("did you mean 'MAX_SIZE'"))
    }

    @Test
    fun lambdaParametersAndMethods() {
        val p = snippet(
            """
            pub struct S {
                pub fx GetX: () Int32 {
                    return 1
                }
            }
            pub fx f: () Fx<Tuple1<Int32>, Int32> {
                return fx(InVal: Int32) Int32 {
                    return InVal
                }
            }
            """,
        )
        assertEquals(listOf("rules.naming.function", "rules.naming.variable"), warnings(p))
    }
}
