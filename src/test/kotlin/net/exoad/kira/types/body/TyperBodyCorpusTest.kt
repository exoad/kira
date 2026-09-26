package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.EnumMemberExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationTargetExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase C over the C++ golden corpus: every case's `src/` types under STRICT, all three phases,
 * with no diagnostic of any severity, and every value expression the C++ emitter will lower has
 * a type (TypedModel.require would otherwise throw). The stdlib's own bodies type cleanly too.
 */
class TyperBodyCorpusTest {
    private val corpus = File("src/test/resources/cpp-golden")

    @TestFactory
    fun everyGoldenCaseTypesStrictWithoutDiagnostics(): List<DynamicTest> {
        val cases = corpus.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }?.sortedBy { it.name }.orEmpty()
        assertTrue(cases.isNotEmpty(), "no golden cases under $corpus")
        return cases.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val program = TyperTestSupport.project(dir, TyperMode.STRICT)
                assertTrue(program.diagnostics.isEmpty(), "${dir.name}:\n${TyperTestSupport.render(program)}")
                val problems = program.workspaceModules.flatMap { untypedValues(program, it) }
                assertTrue(problems.isEmpty(), "${dir.name}: value expressions without a type, or typed <error>:\n${problems.joinToString("\n")}")
            }
        }
    }

    @Test
    fun theStdlibBodiesTypeWithoutDiagnostics() {
        val program = TyperTestSupport.snippet("fx main: () Void { }")
        if (program.diagnostics.isNotEmpty()) {
            fail("stdlib + an empty main:\n${TyperTestSupport.render(program)}")
        }
        val problems = program.modules.filter { it.isStdlib }.flatMap { untypedValues(program, it) }
        assertTrue(problems.isEmpty(), "stdlib value expressions without a type:\n${problems.joinToString("\n")}")
    }

    /**
     * The expressions of [m] that denote values but have no type (or an Error type). Not values:
     * declarations and their names, Type nodes, argument wrappers, a call's callee name, a
     * member's name after '.', a name that is a type or a module (`Kind` in `Kind.KIND_OK`), a
     * handler's `on e` name, and a for loop's head.
     */
    private fun untypedValues(program: TypedProgram, m: ModuleSymbol): List<String> {
        val model = program.model
        val notValues = IdentityHashMap<ASTNode, Boolean>()
        AstTree.walk(m.source.ast) { n ->
            when (n) {
                is Decl -> notValues[n.name] = true
                is net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg -> {
                    notValues[n.value] = true
                    notValues[n.identifier] = true
                }
                is Type -> notValues[n.identifier] = true
                is EnumMemberExpr -> notValues[n.name] = true
                is net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement -> notValues[n.uri] = true
                is FunctionCallExpr -> {
                    val name = n.name
                    notValues[name] = true
                    if (name is MemberAccessExpr) {
                        notValues[name.member] = true
                    }
                }
                is MemberAccessExpr -> {
                    notValues[n.member] = true
                    val origin = n.origin
                    if (origin is Identifier && (model.refs[origin] is TypeSymbol || model.refs[origin] == null && model.types[origin] == null)) {
                        notValues[origin] = true
                    }
                }
                is TryExpr -> n.exceptionName?.let { notValues[it] = true }
                is FunctionDeclParameterExpr -> notValues[n.name] = true
                is FunctionCallNamedParameterExpr -> notValues[n.name] = true
                is ForIterationExpr -> {
                    notValues[n.initializer] = true
                    (n.target as? ForIterationTargetExpr)?.let { notValues[it] = true }
                }
                else -> {}
            }
        }
        // kira:core's `true: Bool = Bool { }` and the like are placeholders the backends never lower.
        m.declarations.filterIsInstance<net.exoad.kira.compiler.analysis.types.GlobalSymbol>()
            .filter { it.foreign is net.exoad.kira.compiler.analysis.types.Foreign.Magic && m.isStdlib }
            .forEach { g -> g.init?.let { init -> AstTree.walk(init) { notValues[it] = true } } }
        val out = mutableListOf<String>()
        AstTree.walk(m.source.ast) { n ->
            if (n !is Expr || notValues.containsKey(n)) {
                return@walk
            }
            if (n is Decl || n is Type || n is FunctionDefExpr || n is FunctionDeclParameterExpr || n is EnumMemberExpr ||
                n is FunctionCallPositionalParameterExpr || n is FunctionCallNamedParameterExpr || n is ForIterationExpr || n === NoExpr
            ) {
                return@walk
            }
            if (n is Identifier && model.refs[n] is ModuleSymbol) {
                return@walk
            }
            val t = model.types[n]
            if (t == null || t.containsError() || t == KType.Error) {
                out.add("${m.uri} ${program.locate(n)?.second}: ${n.javaClass.simpleName} '${KiraUnparser.text(n)}' is ${t ?: "untyped"}")
            }
        }
        return out
    }
}
