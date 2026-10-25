package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Stage 5: types inside type bodies, named bare inside their type and `Outer.Inner` from outside. */
class NestedTypeTest {
    private val shapes = """
        pub Shape: Any {
            pub Corner: Any {
                pub x: Int32 = 0
                pub mark: Mark = Mark { }

                pub Mark: Any {
                    pub label: Str = "m"
                }

                pub to: (other: Corner) Side {
                    Side { a = this, b = other }
                }
            }

            pub Side: Any {
                pub a: Corner
                pub b: Corner
            }

            pub first: Corner = Corner { }

            pub marked: () Corner.Mark {
                Corner.Mark { label = "first" }
            }
        }
    """.trimIndent()

    private fun topLevelTypes(text: String): List<String> {
        val unit = TyperTestSupport.unitOf(module("test:main", text))
        return unit.allSources().first { it.file == "test/main.kira" }.ast.statements
            .mapNotNull { ((it as? Statement)?.expr as? ClassDecl)?.let { c -> (c.name.identifier as Identifier).value } }
    }

    @Test
    fun aNestedTypeIsATopLevelTypeOfItsOwnNameBeforeItsOuter() {
        assertEquals(listOf("Shape_Corner_Mark", "Shape_Corner", "Shape_Side", "Shape"), topLevelTypes(shapes))
    }

    @Test
    fun itsNamesResolveInsideAndOutside() {
        val p = TyperTestSupport.snippet(
            shapes + "\n\nf: () Str {\n    s: Shape = Shape { }\n    c: Shape.Corner = Shape.Corner { x = 2 }\n" +
                "    side: Shape.Side = s.first.to(c)\n    m: Shape.Corner.Mark = s.marked()\n    \"${'$'}{side.b.x} ${'$'}{m.label}\"\n}",
        )
        expectNoErrors(p)
        val main = p.module("test:main")!!
        assertIs<ClassSymbol>(main.members["Shape_Corner_Mark"])
        // Messages and dumps spell a nested type as the program does.
        assertTrue(p.model.types.values.any { it.toString() == "Shape.Corner" } && p.model.types.values.none { "Shape_" in it.toString() })
        // No naming warning: each part of Shape_Corner is PascalCase.
        assertTrue(p.diagnostics.none { it.code.startsWith("rules.naming") }, TyperTestSupport.render(p))
    }

    @Test
    fun fromAnotherModuleThroughAMemberAliasOrAModuleAlias() {
        val lib = module("test:shapes", shapes)
        val p = TyperTestSupport.type(
            lib,
            module(
                "test:main",
                "test.shapes as shapes\ntest.shapes.Shape as Shape\n\nf: () Int32 {\n    a: Shape.Corner = Shape.Corner { x = 1 }\n" +
                    "    b: shapes.Shape.Corner = shapes.Shape.Corner { x = 2 }\n    m: Shape.Corner.Mark = Shape { }.marked()\n    a.x + b.x\n}",
            ),
        )
        expectNoErrors(p)
        // Without either, the nested name is no more in scope than its outer.
        val bare = TyperTestSupport.type(lib, module("test:main", "test.shapes as shapes\n\nf: () Int32 {\n    a: Shape.Corner = Shape.Corner { }\n    a.x\n}"))
        assertTrue(bare.diagnostics.any { it.isError }, TyperTestSupport.render(bare))
    }

    @Test
    fun aNestedTraitIsAParentAndAConformanceTarget() {
        val p = TyperTestSupport.snippet(
            """
            pub Zoo: Any {
                pub Animal: Any {
                    pub noise: () Str;
                }

                pub Cat: Animal {
                    pub lives: Int32 = 9

                    override pub noise: () Str {
                        "mew"
                    }
                }
            }

            f: (a: Zoo.Animal) Str {
                a == Zoo.Cat ? "cat ${'$'}{a.lives}" : a.noise()
            }
            """,
        )
        expectNoErrors(p)
        assertIs<TraitSymbol>(p.module("test:main")!!.members["Zoo_Animal"])
    }

    @Test
    fun aTypeInsideAFunctionIsStillRefused() {
        val e = assertThrows<DiagnosticsException> { TyperTestSupport.unitOf(module("test:main", "f: () Void {\n    Local: Any {\n        pub x: Int32 = 0\n    }\n}")) }
        assertTrue(e.message.contains("parse.type.nested") || e.message.contains("declared at the top level of a file or in a type's body"), e.message)
    }

    @Test
    fun theNestedNameCannotBeWrittenInSource() {
        // The lexer refuses `Shape_Corner`, so a nested type's name never meets a written one.
        val e = assertThrows<DiagnosticsException> { TyperTestSupport.unitOf(module("test:main", "$shapes\n\ng: Shape_Corner = Shape.Corner { }")) }
        assertTrue(e.message.contains("Underscores are not allowed"), e.message)
    }

    @Test
    fun aGlobalOfANestedTypeKeepsItsType() {
        val p = TyperTestSupport.snippet("$shapes\n\nCORNER: Shape.Corner = Shape.Corner { x = 4 }")
        expectNoErrors(p)
        assertEquals("Shape.Corner", (p.module("test:main")!!.members["CORNER"] as GlobalSymbol).type.toString())
    }

    /** py lowers a nested type as the top-level class Outer_Inner (py-golden/nestedtypes runs it); C++ the same. */
    @Test
    fun bothTargetsLowerItAsATopLevelClass() {
        val (py, errors) = PyTestSupport.emitProgram("test:main" to "$shapes\n\npub main: () Void {\n    println(Shape { }.marked().label)\n}")
        assertEquals(emptyList(), errors)
        val text = py.values.single()
        assertTrue(text.contains("class Shape_Corner_Mark:") && text.indexOf("class Shape_Corner_Mark:") < text.indexOf("class Shape:"), text)
        val dir = Files.createTempDirectory("nested_cpp").toFile()
        File(dir, "project.kira").writeText(
            "PROJECT: Project = Project { } with {\n    name = \"nest\"\n    srcDir = \"src\"\n    target = \"cpp\"\n" +
                "    dependencies = @mapOf(\"kira_stdlib\", \"${File("kira").absoluteFile.path.replace('\\', '/')}\")\n}\n",
        )
        File(dir, "src/app").mkdirs()
        File(dir, "src/app/shapes.kira").writeText("$shapes\n")
        val run = PyTestSupport.cli(dir, "--out", File(dir, "out").path)
        assertEquals(0, run.exitCode, run.all)
        val header = File(dir, "out").walkTopDown().first { it.name == "shapes.kira.hxx" }.readText()
        assertTrue(header.contains("class Shape_Corner") && header.contains("class Shape_Side"), header)
    }
}
