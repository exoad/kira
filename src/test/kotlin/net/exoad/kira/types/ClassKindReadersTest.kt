package net.exoad.kira.types

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W2.9 1.2.1 (review 1, #6): a reader that means the C++ representation reads
 * `ClassSymbol.shape` (`isValue`, `isRef`, `isValueClass`), never the declaration's kind.
 * `ClassKind.CLASS` was renamed `USER` so that every reader had to decide; this keeps it
 * decided. In the C++ backend and the rule passes, a test of `ClassKind.USER` or
 * `ClassKind.STRUCT` is allowed only where it means the DECLARATION, each named here with
 * why. `ClassKind.MAGIC` and `ClassKind.OPAQUE` are their shapes one for one, so they stay.
 */
class ClassKindReadersTest {
    private val roots = listOf(
        "src/main/kotlin/net/exoad/kira/compiler/backend/codegen/cpp",
        "src/main/kotlin/net/exoad/kira/compiler/analysis/types/rules",
    )

    /** File name to the code each allowed declaration test is on (a fragment of the line). */
    private val allowed: List<Pair<String, String>> = listOf(
        // The superclass chain of a Kira class: its declarations, structs included (constructors, Link).
        "CppClassEmitter.kt" to "(sym.kind == ClassKind.USER || sym.kind == ClassKind.STRUCT) && sym.foreign == null",
        // A subclass that overrides a method: a declaration of the hierarchy (every subclass is a reference anyway).
        "CppClassEmitter.kt" to "d.kind == ClassKind.USER && chain(d)",
        // rules.escape.this-in-initially applies to a class's initially; a struct keeps its own rules.
        "EscapePass.kt" to "cls.kind != ClassKind.USER",
        // Q4's held receiver: a struct with a `mut` field or `mut fx` is a mutable value (struct semantics).
        "ExclusivityPass.kt" to "ClassKind.STRUCT -> sym.fields.any { it.isMut }",
        // A struct's field is writable through a mutable place (its own rule until W2.9's no-struct).
        "MutabilityPass.kt" to "owner.kind == ClassKind.STRUCT && receiver != null",
        "RuleSupport.kt" to "owner.kind == ClassKind.STRUCT -> p.receiver?.let",
        "RuleSupport.kt" to "val isStructOwner: Boolean get() = (owner as? ClassSymbol)?.kind == ClassKind.STRUCT",
        // An immutable class's fields are fixed (1.1), and its own initially may assign them (Q8): Kira semantics.
        "MutabilityPass.kt" to "owner.kind == ClassKind.USER && owner.isImmutable",
        "MutabilityPass.kt" to "owner.kind != ClassKind.USER || lambdas.isNotEmpty()",
        // types.weak.immutable: a Weak needs a mutable class (Q5), a fact of the declaration.
        "WeakPass.kt" to "it.kind == ClassKind.USER && it.isImmutable",
    )

    private val reader = Regex("""ClassKind\.(USER|STRUCT|CLASS)\b""")

    @Test
    fun representationReadersReadTheShape() {
        val found = mutableListOf<String>()
        val used = HashSet<Pair<String, String>>()
        for (root in roots) {
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//").trim()
                    if (code.startsWith("*") || code.startsWith("/*") || !reader.containsMatchIn(code)) {
                        return@forEachIndexed
                    }
                    val ok = allowed.firstOrNull { (name, fragment) -> f.name == name && fragment in code }
                    if (ok == null) {
                        found += "${f.name}:${i + 1}: $code"
                    } else {
                        used += ok
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "a ClassKind.USER/STRUCT test that is no declaration test (read the shape, or name it here):\n${found.joinToString("\n")}")
        val stale = allowed.filter { it !in used }
        assertTrue(stale.isEmpty(), "allowlist entries that match nothing any more:\n${stale.joinToString("\n")}")
    }

    @Test
    fun noReaderSaysClassKindClass() {
        val hits = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if ("ClassKind.CLASS" in line) "${f.path}:${i + 1}" else null } }
            .toList()
        assertTrue(hits.isEmpty(), "ClassKind.CLASS is ClassKind.USER (W2.9 1.2.1):\n${hits.joinToString("\n")}")
    }
}
