package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/** The stdlib's `@_magic` callables and the entries of its .bind.yaml manifests, which the binding tests hold against each other. */
object StdlibManifests {
    /** Magic names lowered per call site instead of bound: their format is type-directed. */
    val printFamily = setOf("print", "println", "eprint", "trace")

    /**
     * Magic callables the typer makes, which no `.kira` file can declare: `Str.of` (D55) is
     * called on the type, as Result.success is (D39), and binds through the manifest.
     */
    val compilerKnown = setOf("Str.of", "Json.parse", "Json.error", "Json.obj", "Json.arr", "Json.null") +
        listOf("Bool", "Int64", "Float64", "Str").flatMap { listOf("Json.of($it)", "Json.of(Maybe<$it>)") }

    fun manifestFiles(): List<File> {
        val root = File("kira")
        assertTrue(root.isDirectory, "stdlib kira/ dir must be at cwd")
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".bind.yaml") }
            .sortedBy { it.path }
            .toList()
    }

    fun parseManifest(file: File): Map<String, Any?> {
        val loaded = Yaml().load<Any>(file.readText()) ?: return emptyMap()
        assertTrue(loaded is Map<*, *>, "${file.name} must be a mapping at top level")
        return (loaded as Map<*, *>).entries.associate { (k, v) -> k.toString() to v }
    }

    fun manifestEntries(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        manifestFiles().forEach { file ->
            parseManifest(file).forEach { (key, value) ->
                assertTrue(key !in out, "binding '$key' is declared in two manifests")
                out[key] = value
            }
        }
        return out
    }

    /** The `cpp` or `py` sub-map of an entry. */
    fun targetMapOf(value: Any?, target: String): Map<*, *>? = ((value as? Map<*, *>)?.get(target)) as? Map<*, *>

    /**
     * `Type.method` for every method of a `@_magic` class -- declared in its body or taken from a
     * trait it lists as a parent, transitively through the trait's own parents -- and the bare
     * name of every `@_magic` free function, across every stdlib module, the print family left out.
     *
     * A parent that is a class (`Int32: Num`) adds nothing here: the entries are keyed on the
     * declaring class, and the emitter walks the parent chain (`Int32.abs` resolves to `Num.abs`).
     */
    fun magicCallableNames(): Set<String> {
        val traits = linkedMapOf<String, TraitDecl>()
        val magicClasses = mutableListOf<ClassDecl>()
        val magicFunctions = mutableListOf<FunctionDecl>()
        File("kira").walkTopDown()
            .filter { it.isFile && it.extension == "kira" }
            .sortedBy { it.path }
            .forEach { file ->
                val result = TestCompileSupport.compileFile(file.path, runSemantic = false)
                val source = result.compilationUnit.getSource(file.canonicalPath)
                    ?: fail("stdlib source ${file.path} was not registered")
                source.ast.statements.forEach { stmt ->
                    val node = if (stmt is Statement) stmt.expr else stmt
                    if (node is TraitDecl) {
                        val name = baseNameOf(node.name) ?: return@forEach
                        assertTrue(name !in traits, "trait '$name' is declared twice in the stdlib")
                        traits[name] = node
                    }
                }
                val marks = runCatching { source.astIntrinsicMarked }.getOrNull() ?: return@forEach
                marks.forEach { (node, intrinsics) ->
                    if (intrinsics.none { it.name == "_magic" }) return@forEach
                    when (node) {
                        is ClassDecl -> magicClasses.add(node)
                        is FunctionDecl -> magicFunctions.add(node)
                        else -> {}
                    }
                }
            }

        fun traitMethodNames(parent: Type, seen: MutableSet<String>): List<String> {
            val name = baseNameOf(parent) ?: return emptyList()
            val trait = traits[name] ?: return emptyList() // a class parent, e.g. Num
            if (!seen.add(name)) return emptyList()
            val own = trait.members.mapNotNull { (it.name as? Identifier)?.value }
            return own + trait.parents.flatMap { traitMethodNames(it, seen) }
        }

        val out = linkedSetOf<String>()
        magicClasses.forEach { node ->
            val className = baseNameOf(node.name) ?: return@forEach
            val own = node.members.filterIsInstance<FunctionDecl>()
                .mapNotNull { (it.name as? Identifier)?.value }
            own.forEach { out.add("$className.$it") }
            node.parents.flatMap { traitMethodNames(it, mutableSetOf()) }
                .filterNot { it in own }
                .forEach { out.add("$className.$it") }
        }
        magicFunctions.forEach { node ->
            val name = (node.name as? Identifier)?.value ?: return@forEach
            if (name !in printFamily) out.add(name)
        }
        return out
    }

    private fun baseNameOf(type: Type): String? = (type.identifier as? Identifier)?.value
}
