package net.exoad.kira.types.rules

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `@_only` (rules.only.*): what each declaration is built for, what may reach it, and what a build drops. */
class OnlyTargetsTest {
    private val targets = listOf(null, "py", "cpp")

    private fun on(target: String?, body: String): TypedProgram = snippet(body, options = TyperOptions(target = target))

    /** [body] must give exactly [codes] on every target, the typer's own view being the same on each. */
    private fun everywhere(body: String, vararg codes: String): TypedProgram {
        var last: TypedProgram? = null
        for (t in targets) {
            val p = on(t, body)
            expectExactly(p, *codes)
            last = p
        }
        return last!!
    }

    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun aProgramWithoutOnlyKeepsEveryDeclaration() {
        for (t in targets) {
            val p = on(t, "pub f: () Int32 {\n    1\n}\npub g: () Int32 {\n    f()\n}")
            expectClean(p)
            assertEquals(listOf("f", "g"), p.module("test:main")!!.declarations.map { it.name })
        }
    }

    @Test
    fun limitedCodeMayUseSharedCodeAndCodeLimitedToTheSameTargets() {
        everywhere(
            """
            pub shared: () Int32 {
                1
            }
            pub @_only("py") hostName: () Int32 {
                shared()
            }
            pub @_only("py", "cpp") both: () Int32 {
                shared()
            }
            pub @_only("py") slow: () Int32 {
                hostName() + both()
            }
            """
        )
    }

    @Test
    fun anyOfTheFourTargetsMayBeNamed() {
        everywhere("pub @_only(\"py\", \"cpp\", \"c\", \"js\") f: () Int32 {\n    1\n}")
    }

    @Test
    fun aMethodMayNarrowItsOwnersTargets() {
        everywhere(
            """
            pub @_only("py", "cpp") Gadget: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub @_only("py") bump: () Int32 {
                    n += 1
                    n
                }
                pub plain: () Int32 {
                    2
                }
            }
            pub @_only("py") usage: () Int32 {
                g: Gadget { }
                g.bump() + g.plain()
            }
            """
        )
    }

    @Test
    fun aMemberOfALimitedTypeIsBuiltForTheTypesTargets() {
        everywhere(
            """
            pub @_only("py") hostName: () Int32 {
                1
            }
            pub @_only("py") Gadget: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub read: () Int32 {
                    hostName()
                }
            }
            """
        )
    }

    // ---- reach -------------------------------------------------------------------------------

    @Test
    fun sharedCodeThatCallsALimitedFunctionIsRefusedOnEveryTarget() {
        val p = everywhere(
            """
            pub @_only("py") hostName: () Int32 {
                1
            }
            pub slow: () Int32 {
                hostName()
            }
            """,
            "rules.only.reach",
        )
        val m = message(p, "rules.only.reach")
        assertTrue(m.contains("'hostName' is built for py only, but 'slow' is built for every target"), m)
        assertTrue(m.contains("mark 'slow' @_only(\"py\")"), m)
    }

    @Test
    fun aFunctionLimitedToOtherTargetsIsRefused() {
        everywhere(
            """
            pub @_only("cpp") lights: () Int32 {
                1
            }
            pub @_only("py") bench: () Int32 {
                lights()
            }
            """,
            "rules.only.reach",
        )
    }

    @Test
    fun aSignatureOrAConstructionReachesTheTypeToo() {
        everywhere(
            """
            pub @_only("py") Gadget: Mutable<Any> {
                pub n: Mutable<Int32> = 0
            }
            pub take: (g: Gadget) Int32 {
                1
            }
            pub make: () Int32 {
                g: Gadget { }
                2
            }
            """,
            "rules.only.reach", "rules.only.reach",
        )
    }

    @Test
    fun aLimitedConstantIsReachedLikeAFunction() {
        everywhere(
            """
            pub @_only("cpp") PIN: Int32 = 3
            pub f: () Int32 {
                PIN
            }
            """,
            "rules.only.reach",
        )
    }

    // ---- spelling, places, widening ----------------------------------------------------------

    @Test
    fun anUnknownTargetOrNoTargetIsRefused() {
        val p = everywhere("pub @_only(\"rust\") f: () Int32 {\n    1\n}", "rules.only.names")
        assertTrue(message(p, "rules.only.names").contains("c, cpp, js, py"), message(p, "rules.only.names"))
        everywhere("pub @_only() f: () Int32 {\n    1\n}", "rules.only.names")
        everywhere("pub @_only(\"py\", \"py\") f: () Int32 {\n    1\n}", "rules.only.names")
    }

    @Test
    fun aFieldATraitMemberAnOverrideAndAnAliasTakeNoTarget() {
        everywhere(
            """
            pub Cell: Mutable<Any> {
                pub @_only("py") n: Mutable<Int32> = 0
            }
            """,
            "rules.only.place",
        )
        everywhere(
            """
            pub Shape: Any {
                pub @_only("py") area: () Int32
            }
            """,
            "rules.only.place",
        )
    }

    @Test
    fun aMemberNeverWidensItsOwnersTargets() {
        val p = everywhere(
            """
            pub @_only("py") Gadget: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub @_only("cpp") bump: () Int32 {
                    1
                }
            }
            """,
            "rules.only.widen",
        )
        assertTrue(message(p, "rules.only.widen").contains("never widen"), message(p, "rules.only.widen"))
    }

    // ---- the drop ----------------------------------------------------------------------------

    @Test
    fun aBuildDropsWhatItsTargetDoesNotBuildAndNoneKeepsAll() {
        val body = """
            pub shared: () Int32 {
                1
            }
            pub @_only("py") pyOnly: () Int32 {
                2
            }
            pub @_only("cpp") cppOnly: () Int32 {
                3
            }
            pub Box: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub @_only("py") pyBump: () Int32 {
                    4
                }
                pub plain: () Int32 {
                    5
                }
            }
            """
        fun names(t: String?): List<String> = on(t, body).module("test:main")!!.declarations.map { it.name }
        assertEquals(listOf("shared", "pyOnly", "cppOnly", "Box"), names(null))
        assertEquals(listOf("shared", "pyOnly", "Box"), names("py"))
        assertEquals(listOf("shared", "cppOnly", "Box"), names("cpp"))
        assertEquals(listOf("shared", "cppOnly", "Box"), names("cpp"))
        val py = on("py", body)
        expectClean(py)
        assertNotNull(py.module("test:main")!!.members["pyOnly"])
        assertNull(py.module("test:main")!!.members["cppOnly"])
        assertEquals(listOf("plain", "pyBump"), (py.module("test:main")!!.members["Box"] as net.exoad.kira.compiler.analysis.types.ClassSymbol).methods.map { it.name }.sorted())
        val cpp = on("cpp", body)
        assertEquals(listOf("plain"), (cpp.module("test:main")!!.members["Box"] as net.exoad.kira.compiler.analysis.types.ClassSymbol).methods.map { it.name })
    }

    // ---- the semantic pass: C and JS refuse, the extern arguments follow the build -----------

    private fun semantic(body: String): List<String> {
        val uri = "test:only"
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = true,
        )
        return result.semanticResults!!.diagnostics.map { it.message }
    }

    private fun inMode(mode: GeneratedProvider.OutputTarget, check: () -> Unit) {
        val previous = GeneratedProvider.outputMode
        try {
            GeneratedProvider.outputMode = mode
            check()
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }

    @Test
    fun cAndJsRefuseItAndTheTypedTargetsAndNoneReadIt() {
        val body = "pub @_only(\"py\") f: () Int32 {\n    1\n}"
        for (mode in listOf(GeneratedProvider.OutputTarget.C, GeneratedProvider.OutputTarget.JS)) {
            inMode(mode) {
                val messages = semantic(body)
                assertTrue(messages.any { it.contains("@_only is read by the typed backends, py and cpp") }, messages.joinToString("\n"))
            }
        }
        for (mode in listOf(GeneratedProvider.OutputTarget.PY, GeneratedProvider.OutputTarget.CPP, GeneratedProvider.OutputTarget.NONE)) {
            inMode(mode) { assertEquals(emptyList(), semantic(body), mode.name) }
        }
    }

    @Test
    fun theIntrinsicRefusesItsSpellingAndItsPlaces() {
        inMode(GeneratedProvider.OutputTarget.NONE) {
            assertTrue(semantic("pub @_only(\"rust\") f: () Int32 {\n    1\n}").any { it.contains("does not know the target 'rust'") })
            assertTrue(semantic("pub @_only() f: () Int32 {\n    1\n}").any { it.contains("names the targets a declaration is built for") })
            assertTrue(semantic("pub Cell: Mutable<Any> {\n    pub @_only(\"py\") n: Mutable<Int32> = 0\n}").any { it.contains("module-level constant") })
        }
    }

    @Test
    fun anExternLimitedToAnotherTargetIsNotCheckedForThisOne() {
        val raises = "pub @_only(\"py\") @_extern(raises = \"OSError\") readText: (path: Str) Str"
        val cpp = "pub @_only(\"cpp\") @_extern(cpp = \"ns::f\", header = \"f.hxx\") f: () Int32"
        for (mode in GeneratedProvider.OutputTarget.entries) {
            inMode(mode) {
                if (mode == GeneratedProvider.OutputTarget.C || mode == GeneratedProvider.OutputTarget.JS) {
                    return@inMode
                }
                assertEquals(emptyList(), semantic("$raises\n$cpp"), mode.name)
            }
        }
        // Left unmarked, each is still the target-global error it was.
        inMode(GeneratedProvider.OutputTarget.CPP) {
            assertTrue(semantic("pub @_extern(raises = \"OSError\") readText: (path: Str) Str").any { it.contains("raises = names Python exceptions") })
        }
        inMode(GeneratedProvider.OutputTarget.PY) {
            assertTrue(semantic("pub @_extern(cpp = \"ns::f\", header = \"f.hxx\") f: () Int32").any { it.contains("the py backend binds an extern") })
        }
        // The same extern, limited to the build's own target, is checked as ever.
        inMode(GeneratedProvider.OutputTarget.PY) {
            assertTrue(semantic("pub @_only(\"py\") @_extern(cpp = \"ns::f\", header = \"f.hxx\") f: () Int32").any { it.contains("the py backend binds an extern") })
        }
    }

    // ---- the backends -------------------------------------------------------------------------

    @Test
    fun pyEmitsWhatItBuildsAndNeedsNoSidecarForACppExtern() {
        val e = PyTestSupport.emit(
            """
            pub @_only("cpp") @_extern(cpp = "ns::lights", header = "lights.hxx") lights: () Int32

            pub @_only("cpp") cppOnly: () Int32 {
                3
            }

            pub @_only("py") pyOnly: () Int32 {
                2
            }

            pub shared: () Int32 {
                1
            }
            """
        )
        assertEquals(emptyList(), e.errors)
        val py = e.python()
        assertTrue(py.contains("def pyOnly("), py)
        assertTrue(py.contains("def shared("), py)
        assertFalse(py.contains("cppOnly"), py)
        assertFalse(py.contains("lights"), py)
        assertFalse(py.contains("_ext"), "a module of cpp-only externs has no sidecar:\n$py")
    }

    @Test
    fun pyRefusesSharedCodeThatReachesACppOnlyFunction() {
        val e = PyTestSupport.emit(
            """
            pub @_only("cpp") cppOnly: () Int32 {
                3
            }

            pub shared: () Int32 {
                cppOnly()
            }
            """
        )
        assertNull(e.text, "the module was emitted")
        assertTrue(e.errors.any { it.contains("rules.only.reach") }, e.errors.joinToString("\n"))
    }

    @Test
    fun cppEmitsWhatItBuilds() {
        val uri = "test:only"
        val e = DeclTestSupport.emit(
            DeclTestSupport.module(
                uri,
                """
                pub @_only("py") pyOnly: () Int32 {
                    2
                }

                pub @_only("cpp") cppOnly: () Int32 {
                    3
                }

                pub shared: () Int32 {
                    1
                }
                """
            )
        )
        val header = e.header(uri)
        assertTrue(header.contains("cppOnly"), header)
        assertTrue(header.contains("shared"), header)
        assertFalse(header.contains("pyOnly"), header)
    }
}
