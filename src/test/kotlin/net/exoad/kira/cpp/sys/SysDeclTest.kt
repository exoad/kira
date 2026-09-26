package net.exoad.kira.cpp.sys

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
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
 * Five things must hold:
 *  1. each module parses and types under STRICT with no diagnostic, as part of
 *     the stdlib every program loads;
 *  2. every `@_magic` callable a module declares has a `cpp` binding in that
 *     module's own `.bind.yaml`, and that manifest names nothing else;
 *  3. a user program that names the modules' types in its signatures and
 *     calls them in its bodies types under STRICT without an error, phase C
 *     included;
 *  4. the structs and constants kira/cpp/kira/os.hxx writes by hand are the
 *     ones kira/os.kira declares, field for field and value for value;
 *  5. Thread's construction is spawn's two arguments, so `Thread { }` has no
 *     lowering to fail in C++: phase C refuses it as a missing require field.
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

    /**
     * What this proves is that every type, constant and function the program
     * names resolves under STRICT, and that its bodies type there too: phase C
     * (W2.1) checks that `u.localPort() + d.port` is an Int32 and that
     * `t.stopRequested()` is the Bool returned, so a wrong call in a body
     * fails here. The cpp-golden/sys case, which spawns, locks and reports,
     * is typed by TyperBodyCorpusTest.
     */
    @Test
    fun aProgramNamingTheModulesTypesResolvesStrict() {
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

    /**
     * os.bind.yaml binds no struct and no constant: kira/cpp/kira/os.hxx
     * writes Datagram, Ready, POLL_* and SIGNAL_* by hand. This is what keeps
     * the two files from drifting: every pub constant of kira/os.kira is an
     * `inline constexpr` of the mapped C++ type and the same value in os.hxx,
     * every struct has the same fields in the same order with the same
     * defaults, and os.hxx defines no other constant or namespace-level struct.
     */
    @Test
    fun osHxxMirrorsTheStructsAndConstantsOfOsKira() {
        val decls = topLevelDeclsOf(File("kira/os.kira"))
        val constants = decls.filterIsInstance<VariableDecl>().filter { Modifier.PUBLIC in it.modifiers }
        val structs = decls.filterIsInstance<StructDecl>()
        assertTrue(constants.size >= 5, "kira/os.kira declares ${constants.size} pub constants; POLL_* and SIGNAL_* alone are 5")
        assertTrue(structs.size >= 2, "kira/os.kira declares ${structs.size} structs; Datagram and Ready alone are 2")
        val hxx = File("kira/cpp/kira/os.hxx").readText()

        val hxxConstants = Regex("""inline constexpr (\S+) (\w+) = ([^;]+);""").findAll(hxx)
            .associate { it.groupValues[2] to (it.groupValues[1] to it.groupValues[3].trim()) }
        constants.forEach { c ->
            val name = c.name.value
            val want = cppTypeOf(c.type) to literalText(c.value)
            assertEquals(want, hxxConstants[name], "os.hxx's $name (type to value) against os.kira's")
        }
        assertEquals(constants.map { it.name.value }.sorted(), hxxConstants.keys.sorted(), "os.hxx's constants are exactly os.kira's")

        // Namespace-level structs sit at the file's namespace indentation
        // (two spaces); Poller's private Watch sits deeper and is not a Kira
        // struct. Each field is one `type name = default;` line.
        val hxxStructs = Regex("""^  struct (\w+)\s*\{([^}]*)\}""", RegexOption.MULTILINE).findAll(hxx).associate { m ->
            m.groupValues[1] to Regex("""^\s*(\S+) (\w+) = ([^;]+);\s*$""", RegexOption.MULTILINE)
                .findAll(m.groupValues[2])
                .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].trim()) }
                .toList()
        }
        structs.forEach { s ->
            val name = nameOf(s.name) ?: fail("a struct in os.kira without a plain name: ${s.name}")
            val want = s.members.filterIsInstance<VariableDecl>().map { f ->
                Triple(cppTypeOf(f.type), f.name.value, literalText(f.value))
            }
            assertTrue(want.isNotEmpty(), "os.kira's $name has no fields?")
            assertEquals(want, hxxStructs[name], "os.hxx's struct $name (type, name, default per field) against os.kira's")
        }
        assertEquals(structs.mapNotNull { nameOf(it.name) }.sorted(), hxxStructs.keys.sorted(), "os.hxx's namespace-level structs are exactly os.kira's")
    }

    /**
     * kira::sync::Thread has one constructor, (name, body), and it starts the
     * thread. A Kira class construction lowers to make_shared<C>(fields in
     * order), so Thread's require fields must be exactly spawn's parameters:
     * then `Thread { name, body }` is spawn(name, body), and `Thread { }` is
     * a missing require field for the typer, not a C++ error in the output.
     */
    @Test
    fun threadIsConstructedWithSpawnsArguments() {
        val decls = topLevelDeclsOf(File("kira/sync.kira"))
        val thread = decls.filterIsInstance<ClassDecl>().firstOrNull { nameOf(it.name) == "Thread" }
            ?: fail("kira/sync.kira declares no class Thread")
        val spawn = decls.filterIsInstance<FunctionDecl>().firstOrNull { (it.name as? Identifier)?.value == "spawn" }
            ?: fail("kira/sync.kira declares no fx spawn")
        val fields = thread.members.filterIsInstance<VariableDecl>()
        assertTrue(fields.isNotEmpty(), "Thread declares no field, so `Thread { }` would construct it")
        fields.forEach { f ->
            assertTrue(Modifier.REQUIRE in f.modifiers, "Thread.${f.name.value} must be a require field: a Thread has no default")
            assertTrue(f.value == null, "Thread.${f.name.value} must have no default: a Thread has no default")
        }
        val fieldSignature = fields.map { it.name.value to typeText(it.type) }
        val spawnSignature = spawn.def.parameters.map { it.name.value to typeText(it.typeSpecifier) }
        assertEquals(spawnSignature, fieldSignature, "Thread's require fields against spawn's parameters")
        assertEquals(listOf("name" to "Str", "body" to "Fx<Tuple0, Void>"), fieldSignature, "what kira::sync::Thread's constructor takes")
    }

    /**
     * The other half of [threadIsConstructedWithSpawnsArguments], through
     * phase C's construction check (W2.1): `Thread { }` is refused with
     * types.init.missing-required naming both fields, one field alone names
     * the other, and both given type clean.
     */
    @Test
    fun theTyperRefusesAThreadWithoutItsRequireFields() {
        fun missingOf(body: String): List<String> {
            val program = TyperTestSupport.snippet("use \"kira:sync\"\n\n$body")
            val all = program.diagnostics
            assertTrue(all.all { it.code == "types.init.missing-required" }, "only a missing require field:\n${TyperTestSupport.render(program)}")
            return all.map { it.message }
        }
        val none = missingOf("pub fx main: () Void {\n    t: Thread = Thread { }\n}")
        assertEquals(1, none.size, "Thread { }: $none")
        assertTrue("'name'" in none[0] && "'body'" in none[0], "Thread { } must name both require fields: ${none[0]}")
        val nameOnly = missingOf("pub fx main: () Void {\n    t: Thread = Thread { name = \"w\" }\n}")
        assertEquals(1, nameOnly.size, "Thread { name }: $nameOnly")
        assertTrue("'body'" in nameOnly[0] && "'name'" !in nameOnly[0], "Thread { name } must name body alone: ${nameOnly[0]}")
        val both = missingOf("pub fx main: () Void {\n    t: Thread = Thread { name = \"w\", body = fx() Void { } }\n}")
        assertEquals(emptyList(), both, "Thread { name, body } types clean")
    }

    // ---- helpers ------------------------------------------------------

    /** The declarations at the top level of [file], parsed and nothing more. */
    private fun topLevelDeclsOf(file: File): List<Decl> {
        assertTrue(file.isFile, "$file is missing")
        val result = TestCompileSupport.compileFile(file.path, runSemantic = false)
        val source = result.compilationUnit.getSource(file.canonicalPath)
            ?: fail("stdlib source ${file.path} was not registered")
        return source.ast.statements.mapNotNull { (it as? Statement)?.expr as? Decl }
    }

    /** The C++ spelling os.hxx uses for a Kira scalar or Str: design 5.2. */
    private fun cppTypeOf(type: Type): String = when (val name = nameOf(type)) {
        "Int8" -> "std::int8_t"
        "Int16" -> "std::int16_t"
        "Int32" -> "std::int32_t"
        "Int64" -> "std::int64_t"
        "UInt8" -> "std::uint8_t"
        "UInt16" -> "std::uint16_t"
        "UInt32" -> "std::uint32_t"
        "UInt64" -> "std::uint64_t"
        "Size" -> "Size"
        "Str" -> "Str"
        "Bool" -> "bool"
        "Float32" -> "float"
        "Float64" -> "double"
        else -> fail("no C++ spelling here for the Kira type $name: extend cppTypeOf")
    }

    /**
     * A constant's or default's value as os.hxx spells it. The parser folds
     * `-1` into the literal in one place and keeps the unary minus in
     * another, so both shapes read as "-1".
     */
    private fun literalText(value: Expr?): String = when (value) {
        is IntegerLiteral -> value.value.toString()
        is StringLiteral -> "\"" + value.value + "\""
        is UnaryExpr -> when (val inner = value.operand) {
            is IntegerLiteral -> when (value.operator) {
                UnaryOp.NEG -> "-" + inner.value.toString()
                UnaryOp.POS -> inner.value.toString()
                else -> fail("a default that is not a signed literal: $value")
            }
            else -> fail("a default that is not a literal: $value")
        }
        else -> fail("a default that is not a literal: $value")
    }

    /** `Name<Arg, ...>` as written in Kira. */
    private fun typeText(type: Type): String {
        val name = nameOf(type) ?: fail("a type without a plain name: $type")
        return if (type.children.isEmpty()) name else name + "<" + type.children.joinToString(", ") { typeText(it) } + ">"
    }

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
