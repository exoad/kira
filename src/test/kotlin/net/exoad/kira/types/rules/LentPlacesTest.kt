package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.ViewOrigin
import net.exoad.kira.compiler.analysis.types.rules.Rules
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R-A (40-round3 2): a lent result is a place. An accessor whose binding returns a reference into
 * its receiver (`xs.get(i)`, `m.unwrap()`, `r.unwrapErr()`) is the place the other spelling
 * (`xs[i]`, `m.value`, `r.error`) names, so every rule that asks where storage lives gives both
 * spellings one verdict. Round 2's w2-5 #0 (q11, q13a, q13b, q14a), w2-6 #2 (c2, c2b, c2c) and #3
 * (c4, c4b), each a silent use-after-free on the trial, are refused here.
 */
class LentPlacesTest {
    private fun errors(p: TypedProgram): List<String> = p.diagnostics.filter { it.isError }.map { it.code }.sorted()

    private val prelude = """
        pub mut gll: List<List<Int32>> = List<List<Int32>> { }
        pub mut g3: List<List<List<Int32>>> = List<List<List<Int32>>> { }
        pub mut gm: Maybe<List<Int32>> = null
        pub fx clobber: () Int32 {
            gll = List<List<Int32>> { }
            g3 = List<List<List<Int32>>> { }
            return 0
        }
        pub fx clobberM: () Int32 {
            gm = null
            return 0
        }
        pub fx clobberV: (mv: MutView<List<Int32>>) Int32 {
            mv[0] = List<Int32> { }
            return 5
        }
        pub fx both: (mv: MutView<List<Int32>>, v: View<Int32>) Int32 {
            mv[0] = List<Int32> { }
            return v[0]
        }
        pub fx sumK: (v: View<Int32>, k: Int32) Int32 {
            return v[0] + k
        }
        pub fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }
        pub fx pushTo: (mut xs: List<Int32>) Int32 {
            xs.add(3)
            return 0
        }
        pub class Holder {
            pub mut items: List<List<Int32>> = List<List<Int32>> { }
            pub mut fx wipeThen: (v: View<Int32>) Int32 {
                items = List<List<Int32>> { }
                return v[0]
            }
        }
    """

    /** A whole program's functions after [prelude], and the errors it must get. */
    private class Probe(val source: String, val codes: List<String>)

    /** One use, written in the place spelling and in the call spelling, with the verdict both must get. */
    private class Pair(val name: String, val place: String, val call: String, val codes: List<String>)

    @Test
    fun eachSpellingOfALentResultGetsTheSameVerdict() {
        val pairs = listOf(
            // A view of an element of a mut global beside a call that replaces it (4b: any impure call, a mut global).
            Pair("global element", "return sumK(gll[0].view(), clobber())", "return sumK(gll.get(0).view(), clobber())", listOf("rules.view.write")),
            // A nested accessor, and the mixed spelling.
            Pair("nested", "return sumK(g3[0][1].view(), clobber())", "return sumK(g3.get(0).get(1).view(), clobber())", listOf("rules.view.write")),
            Pair("nested, mixed", "return sumK(g3[0].get(1).view(), clobber())", "return sumK(g3.get(0)[1].view(), clobber())", listOf("rules.view.write")),
            // A Maybe's payload beside a call that nulls it.
            Pair("Maybe payload", "return sumK(gm.value.view(), clobberM())", "return sumK(gm.unwrap().view(), clobberM())", listOf("rules.view.write")),
            // A for over an element whose body replaces the owner.
            Pair(
                "loop",
                "mut s: Int32 = 0\n for x: Int32 in gll[0] {\n gll = List<List<Int32>> { }\n s += x\n }\n return s",
                "mut s: Int32 = 0\n for x: Int32 in gll.get(0) {\n gll = List<List<Int32>> { }\n s += x\n }\n return s",
                listOf("rules.exclusivity.loop"),
            ),
            // A class field's element beside the class's own mut fx that replaces the field.
            Pair("class field", "h: Holder = Holder { }\n return h.wipeThen(h.items[0].view())", "h: Holder = Holder { }\n return h.wipeThen(h.items.get(0).view())", listOf("rules.view.write")),
            // D37: a MutView of a local beside a view of its element, through a view expression (q10d's shape; before
            // R-A both spellings were accepted, and the call spelling was a heap-use-after-free).
            Pair(
                "D37 through a view",
                "mut xs: List<List<Int32>> = List<List<Int32>> { }\n return both(xs.view().from(0), xs.view()[0].view())",
                "mut xs: List<List<Int32>> = List<List<Int32>> { }\n return both(xs.view().from(0), xs.view().get(0).view())",
                listOf("rules.exclusivity.argument"),
            ),
            // A by-value element beside a mut write of its owner: an Int32 D33 copies first (F1), both accepted.
            Pair(
                "by-value element",
                "mut xs: List<Int32> = List<Int32> { values = [1, 2] }\n return sub(xs[0], pushTo(mut xs))",
                "mut xs: List<Int32> = List<Int32> { values = [1, 2] }\n return sub(xs.get(0), pushTo(mut xs))",
                emptyList(),
            ),
        )
        val wrong = pairs.flatMap { pair ->
            listOf("place" to pair.place, "call" to pair.call).mapNotNull { (how, body) ->
                val p = snippet(prelude + "pub fx f: () Int32 {\n$body\n}\n")
                val got = errors(p)
                if (got == pair.codes.sorted()) null else "${pair.name} ($how spelling): want ${pair.codes}, got $got\n${TyperTestSupport.render(p)}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun anElementOfAMutViewParameterIsSharedStorageTheCalleeMayWriteInEitherSpelling() {
        // q2's innerIndex/innerGet: clobber(mv) replaces mv[0], freeing the list a view of mv[0] points into. The round-2
        // order clause caught only the call spelling, by accident; both are the view rule's now. So is the spelling that
        // goes through a lender first (mv.from(0)[0], mv.from(0).get(0)): rule 1, a place lent from the parameter.
        for (use in listOf("mv[0]", "mv.get(0)", "mv.from(0)[0]", "mv.from(0).get(0)")) {
            val p = snippet(prelude + "pub fx f: (mv: MutView<List<Int32>>) Int32 {\n return sumK($use.view(), clobberV(mv))\n}\n")
            assertEquals(listOf("rules.view.write"), errors(p), "$use:\n${TyperTestSupport.render(p)}")
        }
    }

    @Test
    fun theRoundTwoProbesAreRefused() {
        val probes = mapOf(
            // w2-5 #0 (4): q11, a local; q10c, a MutView lent in a later sibling (the order clause's before F1).
            "q11" to Probe("""
                pub fx f: () Int32 {
                    mut xs: List<List<Int32>> = List<List<Int32>> {}
                    xs.add(List<Int32> {})
                    xs[0].add(1000)
                    return both(xs.view().from(0 as Size), xs.view().get(0 as Size).view())
                }
            """, listOf("rules.exclusivity.argument")),
            "q10c" to Probe("""
                pub fx f: () Int32 {
                    mut xs: List<List<Int32>> = List<List<Int32>> {}
                    xs.add(List<Int32> {})
                    xs[0].add(1000)
                    return sumK(xs[0].view(), clobberV(xs.view()))
                }
            """, listOf("rules.view.write")),
            // w2-5 #0 (1): q13a, a mut global viewed through get and replaced by the consumer.
            "q13a" to Probe("""
                pub fx clobberG: (v: View<Int32>) Int32 {
                    gll = List<List<Int32>> {}
                    return v[0]
                }
                pub fx f: () Int32 {
                    gll.add(List<Int32> {})
                    gll[0].add(1000)
                    return clobberG(gll.get(0 as Size).view())
                }
            """, listOf("rules.view.write")),
            // w2-5 #0 (2): q13b, a class field through get, replaced by the consumer, a mut fx of the class.
            "q13b" to Probe("""
                pub fx f: () Int32 {
                    h: Holder = Holder {}
                    h.items.add(List<Int32> {})
                    h.items[0].add(1000)
                    return h.wipeThen(h.items.get(0 as Size).view())
                }
            """, listOf("rules.view.write")),
            // w2-5 #0 (3): q14a, a Maybe's payload through unwrap, nulled by the consumer.
            "q14a" to Probe("""
                pub fx clobberN: (v: View<Int32>) Int32 {
                    gm = null
                    return v[0]
                }
                pub fx f: () Int32 {
                    mut xs: List<Int32> = List<Int32> {}
                    xs.add(1000)
                    gm = xs
                    return clobberN(gm.unwrap().view())
                }
            """, listOf("rules.view.write")),
            // w2-6 #2: c2, c2b (a consumer that replaces the global), c2c (an extern handed a lambda that does).
            "c2" to Probe("""
                pub fx sumAndClear: (v: View<Int32>) Int32 {
                    gll = List<List<Int32>> { }
                    return v[0]
                }
                pub fx f: () Int32 {
                    return sumAndClear(gll.get(0).view())
                }
            """, listOf("rules.view.write")),
            "c2b" to Probe("""
                pub fx sumAndClearM: (v: View<Int32>) Int32 {
                    gm = null
                    return v[0]
                }
                pub fx f: () Int32 {
                    return sumAndClearM(gm.unwrap().view())
                }
            """, listOf("rules.view.write")),
            "c2c" to Probe("""
                @_extern(cpp = "w::sumVF", header = "w.hxx")
                pub fx sumVF: (v: View<Int32>, f: Fx<Tuple0, Void>) Int32;
                pub fx f: () Int32 {
                    return sumVF(gll.get(0).view(), fx () Void { gll = List<List<Int32>> { } })
                }
            """, listOf("rules.view.write")),
            // w2-6 #3: c4, c4b, a for over an accessor result whose body replaces the owner.
            "c4" to Probe("""
                pub fx f: () Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in gll.get(0) {
                        gll = List<List<Int32>> { }
                        s += x
                    }
                    return s
                }
            """, listOf("rules.exclusivity.loop")),
            "c4b" to Probe("""
                pub fx f: () Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in gm.unwrap() {
                        gm = null
                        s += x
                    }
                    return s
                }
            """, listOf("rules.exclusivity.loop")),
            // q12a: accepted on the trial (a D33 spill copied the element); R-A makes it the index spelling's
            // literal-4b refusal (a view of a mut global beside an impure sibling).
            "q12a" to Probe("""
                pub fx f: () Int32 {
                    return sumK(gll.get(0).view(), clobber())
                }
            """, listOf("rules.view.write")),
        )
        val wrong = probes.mapNotNull { (name, probe) ->
            val p = snippet(prelude + probe.source)
            val got = errors(p)
            if (got == probe.codes.sorted()) null else "$name: want ${probe.codes}, got $got\n${TyperTestSupport.render(p)}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun aLentPlaceIsRecordedOnceAndGivesNoWriteCapability() {
        val p = snippet(
            """
            pub class K {
                pub child: Int32 = 0
            }
            pub fx f: (xs: List<List<Int32>>, m: Maybe<Str>, r: Result<Int32, Str>, ks: List<K>, v: View<List<Int32>>) Int32 {
                a: Size = xs.get(0).size()
                b: Size = m.unwrap().length()
                c: Size = r.unwrapErr().length()
                d: Int32 = ks.get(0).child
                e: Size = xs.view()[1].size()
                g: Size = v.from(1).get(0).size()
                h: Int32 = mk().get(0)
                return d + h
            }
            pub fx mk: () List<Int32> {
                return List<Int32> { values = [1] }
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        fun lent(text: String): Place? = p.model.readPlace(BodyTestSupport.node<Expr>(p, text, "test:main"))
        fun show(pl: Place?): String = when (pl) {
            null -> "none"
            is Place.Param -> pl.sym.name
            is Place.Local -> pl.sym.name
            is Place.Index -> show(pl.container) + "[" + pl.kind + "]"
            is Place.Field -> show(pl.receiver) + "." + pl.sym.name
            else -> pl.toString()
        }
        assertEquals("xs[LIST]", show(lent("xs.get(0)")))
        assertEquals("m.value", show(lent("m.unwrap()")))
        assertEquals("r.error", show(lent("r.unwrapErr()")))
        assertEquals("ks[LIST].child", show(lent("ks.get(0).child")))
        assertEquals("xs[LIST]", show(lent("xs.view()[1]")))
        assertEquals("v[VIEW]", show(lent("v.from(1).get(0)")))
        // A call result is the temporary it is.
        assertEquals("none", show(lent("mk().get(0)")))
        // Read-only: no assignable place is recorded.
        assertEquals(null, p.model.place(BodyTestSupport.node<Expr>(p, "xs.get(0)", "test:main")))
        assertEquals(IndexKind.LIST, (lent("xs.get(0)") as Place.Index).kind)

        // No new write: a mut argument on an accessor's result stays the typer's refusal (`xs.get(0) = 1` does not parse),
        // and a mut fx on one is refused: MutabilityPass took it for a temporary, but `kira::at` hands back a reference
        // into ys, and C++ grew ys[0] (or failed to compile on a const one).
        for ((body, rule) in listOf("put(mut xs.get(0), 1)" to null, "ys.get(0).add(1)" to "rules.mutability.method", "mm.unwrap().add(1)" to "rules.mutability.method")) {
            val q = snippet(
                """
                pub fx put: (mut x: Int32, v: Int32) Void {
                    x = v
                }
                pub fx g: () Void {
                    mut xs: List<Int32> = List<Int32> { values = [1] }
                    mut ys: List<List<Int32>> = List<List<Int32>> { }
                    mut mm: Maybe<List<Int32>> = null
                    $body
                }
                """,
            )
            val codes = errors(q)
            if (rule == null) {
                assertTrue(codes.isNotEmpty() && codes.none { it.startsWith("rules.") }, "$body:\n${TyperTestSupport.render(q)}")
            } else {
                assertEquals(listOf(rule), codes, "$body:\n${TyperTestSupport.render(q)}")
            }
        }
    }

    @Test
    fun aLentResultOnASecondClassReceiverThatIsNoPlaceTakesTheReceiversOrigins() {
        // Rule 2: pick(...) returns a view that is no place, so its element's storage is where pick's arguments point:
        // private locals beside an impure call are allowed; a mut global is refused. A temporary stays a temporary,
        // and the origins are recorded for the first-class element too, for the emitter.
        val p = snippet(
            prelude + """
            pub fx pick: (a: View<List<Int32>>, b: View<List<Int32>>) View<List<Int32>> {
                return a
            }
            pub fx mkLL: () List<List<Int32>> {
                return List<List<Int32>> { }
            }
            pub fx locals: () Int32 {
                mut a: List<List<Int32>> = List<List<Int32>> { }
                mut b: List<List<Int32>> = List<List<Int32>> { }
                return sumK(pick(a.view(), b.view()).get(0).view(), clobber())
            }
            pub fx temp: () Int32 {
                return sumK(mkLL().view().get(0).view(), clobber())
            }
            pub fx global: () Int32 {
                mut a: List<List<Int32>> = List<List<Int32>> { }
                return sumK(pick(a.view(), gll.view()).get(0).view(), clobber())
            }
            """,
        )
        assertEquals(listOf("rules.view.write"), errors(p), TyperTestSupport.render(p))
        assertTrue(p.diagnostics.single().message.contains("'gll[..]'"), p.diagnostics.single().message)
        val temp = p.model.viewOrigins(BodyTestSupport.node<Expr>(p, "mkLL().view().get(0)", "test:main"))
        assertTrue(temp.single() is ViewOrigin.Temp, temp.toString())
    }

    @Test
    fun theAccessorsAreExactlyTheBindingsThatReturnAReferenceIntoTheReceiver() {
        // The two halves pinned against each other (the lesson of comments that claim other files): every binding whose
        // C++ returns a reference into its receiver is in Rules.ACCESSORS, and nothing else is. W2.3 pins the same set
        // from its side; kira/cpp/tests/rt_test.cxx pins the helpers' lvalue results.
        val found = sortedSetOf<String>()
        for (f in File("kira").listFiles { x -> x.name.endsWith(".bind.yaml") }.orEmpty()) {
            for (line in f.readLines()) {
                val key = line.substringBefore(':').trim()
                val expr = Regex("""expr:\s*"([^"]*)"""").find(line)?.groupValues?.get(1) ?: continue
                val returnsRef = expr.startsWith("kira::at({self}") && !expr.contains("=") ||
                    expr.startsWith("kira::unwrap({self}") || expr == "{self}[{0}]" || expr == "{self}.unwrap()" || expr == "{self}.unwrapErr()"
                if (returnsRef) {
                    found.add(key)
                }
            }
        }
        assertEquals(Rules.ACCESSORS.toSortedSet(), found)
    }
}
