package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import net.exoad.kira.types.rules.RulesTestSupport.warnings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
                pub Comparable<T>: Any {
                    pub compareTo: (other: T) Int32;
                }
                pub HttpClient: Any {
                    pub mut retryCount: Int32 = 0
                    pub fetchAll: (maxItems: Int32) Int32 {
                        totalCount: Int32 = maxItems
                        for i: Int32 in 0..totalCount {
                        }
                        return totalCount
                    }
                }
                pub Int64 as Id2
                """,
            ),
        )
    }

    @Test
    fun foreignAndOperatorNamesAreTheirOwn() {
        expectClean(
            snippet(
                """
                pub @_extern(cpp = "ext::ReadBytes", header = "ext.hxx") ReadBytes: () Int32;
                pub V2: Struct {
                    pub x: Int32 = 0
                }
                pub @op_add: (a: V2, b: V2) V2 {
                    return V2 { a.x + b.x }
                }
                """,
            ),
        )
    }

    @Test
    fun theStdlibIsNotChecked() {
        // kira:core's `true`, `false` and `null` are lowercase constants; a user module never hears about them.
        expectClean(snippet("pub f: () Bool {\n    return true\n}"))
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun functionsVariablesAndTypes() {
        // The parser already refuses underscores outside UPPER_SNAKE_CASE; the pass sees the case violations.
        val p = snippet(
            """
            pub Point: Any {
                pub Xpos: Int32 = 0
            }
            pub Comparable<t>: Any {
                pub CompareTo: (Other: t) Int32;
            }
            pub DoThing: (Value: Int32) Int32 {
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
                "rules.naming.variable",
                "rules.naming.type", "rules.naming.function", "rules.naming.variable",
                "rules.naming.function", "rules.naming.variable",
                "rules.naming.variable", "rules.naming.variable",
            ),
            warnings(p),
            p.diagnostics.joinToString("\n") { it.render() },
        )
        assertTrue(p.diagnostics.any { it.message.contains("did you mean 'doThing'") }, p.diagnostics.joinToString("\n") { it.render() })
        // A type's name is PascalCase or it declares no type: `point: Any { }` is refused as it parses.
        val e = assertThrows<net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException> { snippet("pub point: Any {\n    pub x: Int32 = 0\n}") }
        assertEquals("parse.type.name", e.tag)
        assertTrue(p.diagnostics.any { it.message.contains("did you mean 'myVar'") })
    }

    @Test
    fun constantsAreUpperSnake() {
        val p = snippet(
            """
            pub maxSize: Int32 = 3
            pub Limit: Int32 = 4
            """,
        )
        assertEquals(listOf("rules.naming.constant", "rules.naming.constant"), warnings(p))
        assertTrue(message(p, "rules.naming.constant").contains("did you mean 'MAX_SIZE'"))
    }

    @Test
    fun lambdaParametersAndMethods() {
        val p = snippet(
            """
            pub S: Struct {
                pub GetX: () Int32 {
                    return 1
                }
            }
            pub f: () Fx<Tuple1<Int32>, Int32> {
                return (InVal: Int32) Int32 {
                    return InVal
                }
            }
            """,
        )
        assertEquals(listOf("rules.naming.function", "rules.naming.variable"), warnings(p))
    }
}
