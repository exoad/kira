package net.exoad.kira.suite

import net.exoad.kira.dialect.Stage4
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `kira-dialect stage4`'s copy-then-change checks (Stage4Copies): each shape a copy of a List, a Map or a value type
 * took in stage 3's text, which sharing now makes one value, is a HAND line; a copy nobody can see change is not.
 */
class Stage4CopiesTest {
    /**
     * The HAND lines of converting [files] (path under src/ to stage 3's text; [only], just that one), a project beside the
     * worktree's stdlib. Each file proves, but for [only]'s: its new text imports neighbours still in the old dialect.
     */
    private fun hands(files: Map<String, String>, only: String? = null): List<String> {
        val dir = Files.createTempDirectory("stage4-copies").toFile()
        try {
            File(dir, "kira.yaml").writeText("project:\n  name: probe\nsrcDir: src\ndependencies:\n  kira_stdlib:\n    path: ${File("kira").absolutePath.replace('\\', '/')}\n")
            files.forEach { (rel, text) -> File(dir, "src/$rel").apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n") }
            val out = java.io.ByteArrayOutputStream()
            val saved = System.out
            val code = try {
                System.setOut(java.io.PrintStream(out, true))
                Stage4.convertAll(listOf(only?.let { File(dir, "src/$it").path } ?: dir.path), check = false)
            } finally {
                System.setOut(saved)
            }
            val log = out.toString()
            if (only == null) assertFalse(log.contains("NOT-"), log)
            val hands = log.lines().filter { it.startsWith("HAND ") }.map { it.substringAfter("src").replace('\\', '/') }
            assertEquals(if (hands.isEmpty() && only == null) 0 else 1, code, log)
            return hands
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun hands(main: String): List<String> = hands(mapOf("app/main.kira" to main))

    private fun assertHand(hands: List<String>, at: String, says: String) {
        val line = hands.firstOrNull { it.startsWith("/$at:") } ?: kotlin.test.fail("no HAND at $at: $hands")
        assertContains(line, says)
    }

    @Test
    fun aCopyStoredIntoAFieldOrAnElementThenChangedIsListed() {
        val h = hands(
            """
            Holder: Any {
                pub mut items: List<Int32> = List<Int32> { }
            }

            main: () Void {
                h: Holder = Holder { }
                mut cache: List<Int32> = List<Int32> { }
                h.items = cache
                cache.add(1)
                mut rows: List<List<Int32>> = List<List<Int32>> { }
                rows.add(List<Int32> { })
                mut row: List<Int32> = List<Int32> { }
                rows[0] = row
                row.add(1)
                println("${'$'}{h.items.size()} ${'$'}{rows[0].size()}")
            }
            """,
        )
        assertHand(h, "app/main.kira:8", "copied from 'cache' into h.items")
        assertHand(h, "app/main.kira:13", "copied from 'row' into rows[0]")
        assertEquals(2, h.size, h.toString())
    }

    @Test
    fun aSnapshotTakenUnderALockIsListed() {
        // bibo's shape (streamenc's stats()): a Ref filled inside Mutex.lock with the live counts (this stdlib's lock hands its value plainly).
        val h = hands(
            """
            kira.sync as sync

            State: Any {
                pub mut counts: Map<Str, Int64> = Map<Str, Int64> { }
            }

            Enc: Any {
                shared: sync.Mutex<State>

                pub bump: () Void {
                    shared.lock((s: State) Void {
                        s.counts.put("fed", s.counts.get("fed").unwrapOr(0) + 1)
                    })
                }

                pub stats: () Map<Str, Int64> {
                    out: Ref<Map<Str, Int64>> = Ref<Map<Str, Int64>> { value = Map<Str, Int64> { } }
                    shared.lock((s: State) Void {
                        out.value = s.counts
                    })
                    out.value
                }
            }
            """,
        )
        assertHand(h, "app/main.kira:19", "copied from 's.counts' into out.value, then changed (the source changes)")
    }

    @Test
    fun aFieldReadOffAParameterThatChangesIsListed() {
        val h = hands(
            """
            Engine: Any {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut push: (v: Int32) Void {
                    items.add(v)
                }
            }

            look: (e: Engine) Void {
                before: List<Int32> = e.items
                e.push(1)
                println("${'$'}{before.size()} ${'$'}{e.items.size()}")
            }
            """,
        )
        assertHand(h, "app/main.kira:10", "copied from 'e.items' into 'before'")
    }

    @Test
    fun aCalleeInAnotherFileThatKeepsItsParameterCounts() {
        val h = hands(
            mapOf(
                "lib/registry.kira" to """
                    pub Registry: Any {
                        pub mut held: List<Int32> = List<Int32> { }

                        pub mut keep: (xs: List<Int32>) Void {
                            held = xs
                        }

                        pub mut poke: () Void {
                            held.add(1)
                        }
                    }
                """,
                // No `mut` here: the file changes not at all, and its meaning does.
                "app/main.kira" to """
                    probe.lib.registry as registry

                    main: () Void {
                        reg: registry.Registry = registry.Registry { }
                        xs: List<Int32> = List<Int32> { }
                        reg.keep(xs)
                        reg.poke()
                        println("${'$'}{xs.size()} ${'$'}{reg.held.size()}")
                    }
                """,
            ),
        )
        assertHand(h, "app/main.kira:6", "into the parameter 'xs' of keep, which keeps it")
    }

    @Test
    fun aCalleeInAFileTheRunLeavesAloneCounts() {
        // Only main.kira is converted; what registry.kira's keep does with its parameter is read all the same.
        val h = hands(
            mapOf(
                "lib/registry.kira" to """
                    pub Registry: Any {
                        pub mut held: List<Int32> = List<Int32> { }

                        pub mut keep: (xs: List<Int32>) Void {
                            held = xs
                        }

                        pub mut poke: () Void {
                            held.add(1)
                        }
                    }
                """,
                "app/main.kira" to """
                    probe.lib.registry as registry

                    main: () Void {
                        reg: registry.Registry = registry.Registry { }
                        mut xs: List<Int32> = List<Int32> { }
                        reg.keep(xs)
                        reg.poke()
                        println("${'$'}{xs.size()} ${'$'}{reg.held.size()}")
                    }
                """,
            ),
            only = "app/main.kira",
        )
        assertHand(h, "app/main.kira:6", "into the parameter 'xs' of keep, which keeps it")
    }

    @Test
    fun aLoopVariableAndAMapsElementAreTheCollectionsElements() {
        val h = hands(
            """
            main: () Void {
                mut rows: List<List<Int32>> = List<List<Int32>> { }
                rows.add(List<Int32> { })
                mut seen: List<List<Int32>> = List<List<Int32>> { }
                for r: List<Int32> in rows {
                    seen.add(r)
                }
                rows[0].add(1)
                mut m: Map<Str, List<Int32>> = Map<Str, List<Int32>> { }
                m.put("a", List<Int32> { })
                got: List<Int32> = m.get("a").unwrap()
                m.get("a").unwrap().add(2)
                println("${'$'}{seen[0].size()} ${'$'}{got.size()}")
            }
            """,
        )
        assertHand(h, "app/main.kira:6", "copied from 'r' into seen")
        assertHand(h, "app/main.kira:11", "copied from 'm.get(\"a\").unwrap()' into 'got'")
        assertHand(h, "app/main.kira:12", "changed a copy of the element, and now changes the element itself")
    }

    @Test
    fun aWithOnAClassThatTurnsMutableAndAContainsOfAValueTypeAreListed() {
        val h = hands(
            """
            Holder: Any {
                pub items: List<Int32> = List<Int32> { }
            }

            Pt: Struct {
                pub x: Int32 = 0

                pub mut shift: (d: Int32) Void {
                    x += d
                }
            }

            main: () Void {
                a: Holder = Holder { }
                b: Holder = a with {
                    items.add(1)
                }
                mut all: List<Pt> = List<Pt> { }
                all.add(Pt { 1 })
                println("${'$'}{a.items.size()} ${'$'}{all.contains(Pt { 1 })}")
            }
            """,
        )
        assertHand(h, "app/main.kira:15", "a `with` on Holder: it copied the value and now edits it in place")
        assertHand(h, "app/main.kira:20", "compared Pt's fields and now compares identity")
    }

    @Test
    fun aLambdaCapturingAListThatChangesAfterIsListed() {
        val h = hands(
            """
            main: () Void {
                mut xs: List<Int32> = List<Int32> { }
                f: () Int32 = () Int32 {
                    xs.size() as Int32
                }
                xs.add(1)
                println("${'$'}{f()} ${'$'}{xs.size()}")
            }
            """,
        )
        assertHand(h, "app/main.kira:3", "a lambda captures 'xs'")
    }

    @Test
    fun aCopyNobodyCanSeeChangeIsNotListed() {
        // A field only reassigned (through a local or `this`) keeps no copy's change; a local built up and stored, never used again, is moved.
        val h = hands(
            """
            Holder: Any {
                pub mut items: List<Int32> = List<Int32> { }

                pub mut reset: () Void {
                    snap: List<Int32> = items
                    items = @listOf(1)
                    println("${'$'}{snap.size()}")
                }
            }

            main: () Void {
                h: Holder = Holder { }
                snap: List<Int32> = h.items
                h.items = @listOf(1)
                mut built: List<Int32> = List<Int32> { }
                built.add(2)
                h.items = built
                kept: List<Int32> = List<Int32> { }
                h.items = kept
                println("${'$'}{snap.size()} ${'$'}{kept.size()} ${'$'}{h.items.size()}")
            }
            """,
        )
        assertEquals(emptyList(), h)
    }
}
