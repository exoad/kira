package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppTypeSpeller
import net.exoad.kira.cpp.decls.DeclEmitRun.emit
import net.exoad.kira.cpp.decls.DeclEmitRun.render
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A private prototype nothing can define and a magic class the runtime does not back are refused at their line. */
class DeclRefusalTest {
    @Test
    fun aPrivateFunctionWithoutABodyIsAnError() {
        val (emitted, _) = emit(
            """
            helper: (x: Int32, y: Int32) Int32
            pub defined: (x: Int32) Int32
            Inner: Struct {
                pub v: Int32 { 1 }
                pub twice: () Int32
            }
            pub Outer: Struct {
                pub v: Int32 { 1 }
                pub twice: () Int32
            }
            """,
        )
        val refused = emitted.diagnostics.filter { it.code == CppDeclEmitter.NO_BODY_CODE }
        assertEquals(2, refused.size, render(emitted.diagnostics))
        assertTrue(refused[0].message.contains("function 'helper'") && refused[0].position?.lineNumber == 1, refused[0].render())
        assertTrue(refused[1].message.contains("method 'twice'") && refused[1].position != null, refused[1].render())
        assertTrue(emitted.diagnostics.none { it.isError && it.code != CppDeclEmitter.NO_BODY_CODE }, render(emitted.diagnostics))

        val headerOnly = emit("helper: (x: Int32) Int32\npub X: Int32 { 1 }", uri = "lib:h", options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:h"))).first
        assertEquals(1, headerOnly.diagnostics.count { it.code == CppDeclEmitter.NO_BODY_CODE }, render(headerOnly.diagnostics))
    }

    @Test
    fun theSystemModulesMapToTheirRuntimeHeaders() {
        assertEquals("kira/sync.hxx", CppTypeSpeller.systemHeaderFor("kira:sync"))
        assertEquals("kira/test.hxx", CppTypeSpeller.systemHeaderFor("kira:test"))
        assertEquals("kira/os.hxx", CppTypeSpeller.systemHeaderFor("kira:os"))
        assertEquals("kira/time.hxx", CppTypeSpeller.systemHeaderFor("kira:time"))
        assertNull(CppTypeSpeller.systemHeaderFor("kira:result"))
        assertNull(CppTypeSpeller.systemHeaderFor("kira:core"))
    }

    @Test
    fun aMagicClassNoRuntimeTypeBacksIsRefusedAtItsLine() {
        val (emitted, _) = emit(
            """
            kira.result as result
            pub Holder: Struct {
                pub failure: Maybe<Exception> { null }
            }
            pub describe: (e: Exception) Str
            """,
        )
        val refused = emitted.diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }
        assertEquals(2, refused.size, render(emitted.diagnostics))
        assertEquals(listOf(3, 5), refused.map { it.position?.lineNumber }, render(refused))
        assertTrue(refused.all { it.message.contains("Exception") }, render(refused))
    }
}
