package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.types.TyperTestSupport
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Snippet helpers for the rule-pass tests: type a module, then ask for its error and warning codes. */
object RulesTestSupport {
    fun snippet(body: String, uri: String = "test:main", options: TyperOptions = TyperOptions()): TypedProgram =
        TyperTestSupport.snippet(body, uri = uri, options = options)

    fun modules(vararg sources: Pair<String, String>, options: TyperOptions = TyperOptions()): TypedProgram =
        TyperTestSupport.type(*sources.map { (uri, body) -> TyperTestSupport.module(uri, body) }.toTypedArray(), mode = TyperMode.STRICT, options = options)

    fun errors(p: TypedProgram): List<String> = p.diagnostics.filter { it.isError }.map { it.code }

    fun warnings(p: TypedProgram): List<String> = p.diagnostics.filter { !it.isError }.map { it.code }

    /** The rule-pass diagnostics only (`rules.*`), errors and warnings. */
    fun rules(p: TypedProgram): List<String> = p.diagnostics.map { it.code }.filter { it.startsWith("rules.") }

    /** Fails unless the program's diagnostics are exactly [codes] (in order). */
    fun expectExactly(p: TypedProgram, vararg codes: String) {
        assertEquals(codes.toList(), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }

    /** Fails unless the program has no error and no warning at all. */
    fun expectClean(p: TypedProgram) {
        assertTrue(p.diagnostics.isEmpty(), "expected no diagnostics, got:\n${TyperTestSupport.render(p)}")
    }

    /** Fails unless no `rules.*` diagnostic was reported (phase C's own may be). */
    fun expectNoRules(p: TypedProgram) {
        assertTrue(rules(p).isEmpty(), "expected no rule diagnostics, got:\n${TyperTestSupport.render(p)}")
    }

    fun fn(p: TypedProgram, name: String, uri: String = "test:main"): FnSymbol =
        p.module(uri)?.members?.get(name) as? FnSymbol ?: fail("no function $name in $uri")

    fun cls(p: TypedProgram, name: String, uri: String = "test:main"): ClassSymbol =
        p.module(uri)?.members?.get(name) as? ClassSymbol ?: fail("no class $name in $uri")

    fun method(p: TypedProgram, cls: String, name: String, uri: String = "test:main"): FnSymbol =
        cls(p, cls, uri).method(name) ?: fail("no method $cls.$name in $uri")

    /** The message of the first diagnostic with [code]. */
    fun message(p: TypedProgram, code: String): String = TyperTestSupport.expectDiagnostic(p, code).message
}
