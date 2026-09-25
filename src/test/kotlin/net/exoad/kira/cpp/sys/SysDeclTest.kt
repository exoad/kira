package net.exoad.kira.cpp.sys

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hosted system stdlib (W2.7): kira:time, kira:sync, kira:os and kira:test.
 *
 * Three things must hold:
 *  1. each module parses and types under STRICT with no diagnostic, as part of
 *     the stdlib every program loads;
 *  2. every `@_magic` callable a module declares has a `cpp` binding in that
 *     module's own `.bind.yaml`, and that manifest names nothing else;
 *  3. a user program that names the modules' types and calls their functions
 *     types under STRICT without an error.
 */
class SysDeclTest {
    private val modules = listOf("time", "sync", "os", "test")
    private val uris = modules.map { "kira:$it" }.toSet()

    @TestFactory
    fun everyModuleParsesAndTypesStrict(): List<DynamicTest> {
        val program = TyperTestSupport.snippet("fx main: () Void { }")
        return modules.map { name ->
            DynamicTest.dynamicTest("kira:$name") {
                val uri = "kira:$name"
                val module = program.modules.firstOrNull { it.uri == uri }
                    ?: fail("$uri is not among the stdlib modules: ${program.modules.filter { it.isStdlib }.map { it.uri }}")
                assertTrue(module.isStdlib, "$uri must be a stdlib module")
                val diagnostics = program.diagnostics.filter { d ->
                    d.source?.file?.let { File(it).name == "$name.kira" } == true
                }
                assertTrue(diagnostics.isEmpty(), "$uri diagnostics:\n${TyperTestSupport.render(program)}")
                val dump = TypedModelDumper.dumpSymbols(program) { it.uri == uri }
                assertTrue(dump.isNotBlank(), "$uri declares nothing?")
                assertTrue("<error>" !in dump, "an unresolved type in $uri:\n$dump")
            }
        }
    }

    @TestFactory
    fun everyMagicCallableHasACppBindingInItsOwnManifest(): List<DynamicTest> {
        return modules.map { name ->
            DynamicTest.dynamicTest("kira/$name.bind.yaml") {
                val declared = magicCallablesOf(File("kira/$name.kira"))
                assertTrue(declared.isNotEmpty(), "kira/$name.kira declares no @_magic callable")
                val manifest = File("kira/$name.bind.yaml")
                assertTrue(manifest.isFile, "$manifest is missing")
                val bound = entriesOf(manifest)
                bound.forEach { (key, value) ->
                    val cpp = (value as? Map<*, *>)?.get("cpp") as? Map<*, *>
                    assertTrue(cpp != null, "$manifest: $key has no cpp map")
                    val expr = cpp["expr"]
                    assertTrue(expr is String && expr.isNotBlank(), "$manifest: $key has no cpp.expr")
                    val includes = cpp["includes"]
                    assertTrue(includes is List<*> && includes.isNotEmpty(), "$manifest: $key names no include")
                }
                assertEquals(emptyList(), declared.filterNot { it in bound.keys }.sorted(), "$manifest: declared but unbound")
                assertEquals(emptyList(), bound.keys.filterNot { it in declared }.sorted(), "$manifest: bound but not declared")
            }
        }
    }

    @Test
    fun aProgramUsingTheModulesTypesStrict() {
        val program = TyperTestSupport.snippet(
            """
            use "kira:time"
            use "kira:sync"
            use "kira:os"
            use "kira:test"

            pub fx clock: () Int64 {
                return monoNowNs() + monoNowMs() + wallNowNs()
            }

            pub fx shared: (t: Thread, m: Mutex<Int32>, a: Atomic<Bool>, q: BlockingQueue<Str>) Bool {
                return t.stopRequested()
            }

            pub fx net: (u: UdpSocket, l: TcpListener, s: TcpStream, p: Poller, d: Datagram, r: Ready) Int32 {
                return u.localPort() + d.port + r.events + POLL_READ
            }

            pub fx devices: (port: Serial, proc: Process) Int64 {
                return port.handle() + proc.stdoutHandle()
            }

            pub fx files: (path: Str) Bool {
                return exists(path) && makeDirs(path)
            }

            pub fx report: (suite: Suite) Int32 {
                return suite.finish()
            }
            """
        )
        assertTrue(!program.hasErrors, TyperTestSupport.render(program))
        val dump = TypedModelDumper.dumpSymbols(program) { !it.isStdlib }
        assertTrue("<error>" !in dump, "an unresolved type in the program:\n$dump")
        listOf("Thread", "Mutex", "Atomic", "BlockingQueue", "UdpSocket", "Serial", "Process", "Suite", "Datagram").forEach {
            assertTrue(it in dump, "$it does not appear in the program's types:\n$dump")
        }
    }

    // ---- helpers ------------------------------------------------------

    private fun entriesOf(manifest: File): Map<String, Any?> {
        val loaded = Yaml().load<Any>(manifest.readText()) ?: return emptyMap()
        assertTrue(loaded is Map<*, *>, "${manifest.name} must be a mapping at top level")
        return (loaded as Map<*, *>).entries.associate { (k, v) -> k.toString() to v }
    }

    /**
     * `Type.method` for every method of a `@_magic` class in [file] and the
     * bare name of every `@_magic` free function, as StdlibCppBindingsTest
     * keys them. None of these modules' classes takes a trait method, so the
     * class body is the whole surface.
     */
    private fun magicCallablesOf(file: File): Set<String> {
        assertTrue(file.isFile, "$file is missing")
        val result = TestCompileSupport.compileFile(file.path, runSemantic = false)
        val source = result.compilationUnit.getSource(file.canonicalPath)
            ?: fail("stdlib source ${file.path} was not registered")
        val marks = source.astIntrinsicMarked
        val out = linkedSetOf<String>()
        marks.forEach { (node, intrinsics) ->
            if (intrinsics.none { it.name == "_magic" }) return@forEach
            when (node) {
                is ClassDecl -> {
                    val className = nameOf(node.name) ?: return@forEach
                    node.members.filterIsInstance<FunctionDecl>()
                        .mapNotNull { (it.name as? Identifier)?.value }
                        .forEach { out.add("$className.$it") }
                }

                is FunctionDecl -> (node.name as? Identifier)?.value?.let { out.add(it) }
                else -> {}
            }
        }
        return out
    }

    private fun nameOf(type: Type): String? = (type.identifier as? Identifier)?.value
}
