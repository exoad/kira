package net.exoad.kira

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Ownership rules the C backend must hold, pinned at the emit level.
 *
 * The behavioural proof is `examples/regenerate.sh --check` plus the
 * AddressSanitizer / `leaks` sweep; these assertions keep the *shape* honest so
 * a regression shows up as a failing test rather than a corrupted heap.
 */
class ArcLoweringTest {
    private val pet = """
        pub class Pet {
            require pub name: Str
        }
    """.trimIndent()

    /**
     * The user lowering only. The runtime prelude also contains `return 0;` and
     * `kira_rc_release(`, so offset-based assertions must not see it.
     */
    private fun emit(body: String, uri: String): String {
        val full = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false
        )
        val marker = "#endif /* KIRA_RUNTIME_H */"
        val cut = full.indexOf(marker)
        return if (cut >= 0) full.substring(cut + marker.length) else full
    }

    @Test
    fun aliasingRetainsSoBothNamesCanRelease() {
        val c = emit(
            """
            $pet

            fx main: () Void {
                a: Pet = Pet { "m" }
                b: Pet = a
                trace(b.name)
            }
            """,
            "test:arc.alias"
        )

        // Without the retain, two releases would run against one allocation.
        assertTrue(c.contains("kira_rc_retain(b)"), c)
        assertTrue(c.contains("kira_rc_release(b)"), c)
        assertTrue(c.contains("kira_rc_release(a)"), c)
    }

    @Test
    fun reassignmentGoesThroughTheStoreHelper() {
        val c = emit(
            """
            $pet

            fx main: () Void {
                mut a: Pet = Pet { "m" }
                a = Pet { "n" }
                trace(a.name)
            }
            """,
            "test:arc.reassign"
        )

        // A fresh value carries its own +1, so the owned-store variant applies.
        assertTrue(c.contains("kira_rc_store_owned((Void**)&a"), c)
    }

    @Test
    fun releaseLandsInsideTheBlockThatDeclaredTheLocal() {
        val c = emit(
            """
            $pet

            fx main: () Void {
                for mut i: 0..3 {
                    p: Pet = Pet { "m" }
                    trace(p.name)
                }
            }
            """,
            "test:arc.loop"
        )

        // The release must precede the loop's closing brace; hoisting it to
        // function scope referenced `p` out of scope and would not compile.
        val release = c.indexOf("kira_rc_release(p)")
        val loopEnd = c.indexOf("return 0;")
        assertTrue(release in 1 until loopEnd, "release should sit inside the loop body:\n$c")
    }

    @Test
    fun fieldsOwnTheirReferentsViaAFinalizer() {
        val c = emit(
            """
            $pet

            pub class Box {
                require pub inner: Pet
            }

            fx main: () Void {
                b: Box = Box { Pet { "m" } }
                trace(b.inner.name)
            }
            """,
            "test:arc.field"
        )

        assertTrue(c.contains("Box_finalize"), c)
        assertTrue(c.contains("kira_rc_alloc_with(sizeof(Box), Box_finalize)"), c)
        assertTrue(c.contains("kira_rc_release(self->inner)"), c)
    }

    @Test
    fun borrowedConstructorArgumentIsRetainedAtTheCallSite() {
        val c = emit(
            """
            $pet

            pub class Box {
                require pub inner: Pet
            }

            fx main: () Void {
                p: Pet = Pet { "m" }
                b: Box = Box { p }
                trace(b.inner.name)
            }
            """,
            "test:arc.borrowedarg"
        )

        // Fields consume a +1; a borrowed local must be retained on the way in.
        assertTrue(c.contains("kira_rc_retained(p)"), c)
    }

    @Test
    fun releasesPrecedeAnEarlyReturnAndSpareTheReturnedValue() {
        val c = emit(
            """
            $pet

            fx make: () Pet {
                other: Pet = Pet { "o" }
                p: Pet = Pet { "m" }
                return p
            }

            fx main: () Void {
                a: Pet = make()
                trace(a.name)
            }
            """,
            "test:arc.earlyreturn"
        )

        // Look only inside `make`'s definition; prototypes are hoisted above it
        // and `main` has releases of its own.
        val makeBody = c.substringAfter("Pet* make(Void)\n{").substringBefore("Int32 main(Void)\n{")
        val release = makeBody.indexOf("kira_rc_release(other)")
        val ret = makeBody.indexOf("return p;")
        assertTrue(release in 1 until ret, "release must precede the return, not follow it:\n$makeBody")
        // The returned local keeps its +1 -- ownership moves to the caller, and
        // nothing may follow the return (that would be unreachable code).
        assertTrue(
            !makeBody.substringAfter("return p;").contains("kira_rc_release"),
            "no releases may be emitted after the return:\n$makeBody"
        )
    }

    @Test
    fun containerLocalsAreDisposedAtScopeEnd() {
        val c = emit(
            """
            fx main: () Void {
                l: List<Int32> = List<Int32> { }
                m: Map<Str, Int32> = Map<Str, Int32> { }
                s: Set<Int32> = Set<Int32> { }
                l.add(1)
                trace(l.size())
            }
            """,
            "test:arc.containers"
        )

        assertTrue(c.contains("List_dispose(&l)"), c)
        assertTrue(c.contains("Map_dispose(&m)"), c)
        assertTrue(c.contains("Set_dispose(&s)"), c)
    }
}
