package net.exoad.kira.cpp

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.cpp.support.CppGoldenCase
import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ManifestLoader
import net.exoad.kira.kim.ManifestValidator
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Design 5.10: for every golden case marked `emit: required`, run the compiler's C++ emitter
 * on the case's `src/` under its `kira.yaml` and diff every generated file against
 * `expected/` byte for byte, with a unified diff on a mismatch. An expected file the
 * emitter does not produce, or a produced file `expected/` lacks, fails the case.
 *
 * What runs is the CLI's path: the manifest, the semantic pass, the typer (STRICT) and the
 * module emitter over the real layout, through [KiraCppBackend.emitModules], so exactly the
 * Kira-written stdlib modules the case reaches (`kira/std/<x>.kira.hxx` under the runtime
 * directory) are emitted and diffed, and a case that reaches one must claim its header in
 * `expected/`. The CLI-level truth (`kira --target cpp` on a whole project) is CppCliTest's.
 *
 * `KIRA_UPDATE_GOLDENS=1` writes `expected/` from the emitter instead of diffing, for the
 * package that flips a case to `required`.
 */
class CppGoldenEmitTest {
    private val update: Boolean = System.getenv("KIRA_UPDATE_GOLDENS") == "1"

    @TestFactory
    fun requiredCases(): List<DynamicNode> {
        val (roots, _) = CppGoldenCompileTest.configuredRoots()
        val nodes = ArrayList<DynamicNode>()
        var required = 0
        for (root in roots) {
            if (!root.isDirectory) {
                continue
            }
            for (case in CppGoldenCase.discover(root)) {
                if (case.emit != "required") {
                    continue
                }
                required += 1
                nodes += DynamicTest.dynamicTest("${case.name} emits expected/ byte for byte") { check(case) }
            }
        }
        nodes += DynamicTest.dynamicTest("at least one case is emit: required") {
            assertTrue(required > 0, "no golden case is marked emit: required under $roots")
        }
        return nodes
    }

    private fun check(case: CppGoldenCase) {
        val caseRoot = case.dir.toPath().toAbsolutePath().normalize()
        val manifest = ManifestLoader.loadFromPath(caseRoot.resolve("kira.yaml"))
        val issues = ManifestValidator.validate(manifest, caseRoot)
        assertTrue(issues.isEmpty(), "${case.name}: kira.yaml issues: ${issues.joinToString { "${it.field}: ${it.message}" }}")

        val workspace = DependencyResolver.resolveProjectSources(manifest, caseRoot)
        assertTrue(workspace.isNotEmpty(), "${case.name}: no Kira sources under src/")
        val unit = CompilationUnit()
        workspace.sorted().forEach { file ->
            val text = File(file).readText()
            val processed = KiraPreprocessor(text).process().processedContent
            val canonical = File(file).canonicalPath
            var ctx = unit.addSource(canonical, processed, emptyList())
            val tokens = KiraLexer(ctx).tokenize()
            ctx = unit.addSource(canonical, ctx.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        val semantic = KiraSemanticAnalyzer(unit).validateAST()
        assertTrue(semantic.diagnostics.isEmpty(), "${case.name}: the semantic pass refused the case:\n${semantic.diagnostics.joinToString("\n")}")

        val options = KiraCppBackend.buildOptions(manifest, null)
        val refs = unit.allSources().mapNotNull { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (uri.startsWith("(unknown)")) null else CppModuleRef(uri, Path.of(src.file))
        }
        val layout = CppModuleLayout(options, caseRoot, refs)
        val layoutErrors = layout.checkCollisions().filter { it.isError }
        assertTrue(layoutErrors.isEmpty(), "${case.name}: layout errors:\n${layoutErrors.joinToString("\n") { it.render() }}")

        val emitter = CppModuleEmitterFactory.create(unit, options)
        emitter.prepare(layout, "dev")
        val runErrors = emitter.diagnostics.filter { it.isError }
        assertTrue(runErrors.isEmpty(), "${case.name}: the typer refused the case:\n${runErrors.joinToString("\n") { it.render() }}")

        val expectedRoot = case.expectedDir.toPath().toAbsolutePath().normalize()
        val seen = HashSet<Path>()
        val problems = mutableListOf<String>()
        val sources = unit.allSources()
            .map { runCatching { it.getModuleUri() }.getOrNull().orEmpty() to it }
            .filter { it.first.isNotEmpty() && !it.first.startsWith("(unknown)") }
            .sortedBy { it.first }
            .map { (uri, source) -> CppModuleRef(uri, Path.of(source.file)) to source }
        // The CLI's path: the case's modules, then the stdlib modules they reach, and no other.
        for ((ref, source, emitted) in KiraCppBackend.emitModules(emitter, sources)) {
            val uri = ref.uri
            val files = layout.filesFor(uri, Path.of(source.file))
            val expectedHeader = expectedRoot.resolve(caseRoot.relativize(files.header))
            val expectedSource = files.source?.let { expectedRoot.resolve(caseRoot.relativize(it)) }
            val errors = emitted.diagnostics.filter { it.isError }
            if (errors.isNotEmpty()) {
                problems += "module $uri: ${errors.size} error(s):\n" + errors.joinToString("\n") { "  " + it.render() }
                continue
            }
            compare(expectedHeader, CppWriter.normalize(emitted.header), caseRoot, seen, problems)
            if (expectedSource != null) {
                val text = emitted.source?.let { CppWriter.normalize(it) }
                when {
                    text != null -> compare(expectedSource, text, caseRoot, seen, problems)
                    Files.exists(expectedSource) -> problems += "${caseRoot.relativize(expectedSource)}: expected, but the emitter produced no source for $uri"
                }
            }
        }
        if (!update && Files.isDirectory(expectedRoot)) {
            Files.walk(expectedRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    val normalized = file.toAbsolutePath().normalize()
                    if (normalized !in seen) {
                        problems += "${caseRoot.relativize(normalized)}: in expected/, but the emitter produced no such file"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("${case.name}: ${problems.size} problem(s)\n\n" + problems.joinToString("\n\n"))
        }
    }

    private fun compare(expected: Path, actual: String, caseRoot: Path, seen: MutableSet<Path>, problems: MutableList<String>) {
        val normalized = expected.toAbsolutePath().normalize()
        seen.add(normalized)
        val shown = caseRoot.relativize(normalized).toString().replace('\\', '/')
        if (update) {
            Files.createDirectories(normalized.parent)
            Files.write(normalized, actual.toByteArray(Charsets.UTF_8))
            return
        }
        if (!Files.exists(normalized)) {
            problems += "$shown: the emitter produced it, but expected/ has no such file (KIRA_UPDATE_GOLDENS=1 writes it)\n" +
                actual.lines().take(12).joinToString("\n") { "  | $it" }
            return
        }
        val want = String(CppWriter.lfBytes(Files.readAllBytes(normalized)), Charsets.UTF_8)
        if (want != actual) {
            problems += "$shown differs\n" + unifiedDiff(want.lines(), actual.lines(), "expected/$shown", "emitted")
        }
    }

    companion object {
        /** A unified diff (3 lines of context) of [a] against [b], by a longest common subsequence. */
        fun unifiedDiff(a: List<String>, b: List<String>, aName: String, bName: String, context: Int = 3): String {
            val n = a.size
            val m = b.size
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
            // ops: ' ' keep (i, j), '-' delete a[i], '+' insert b[j]
            data class Op(val kind: Char, val ai: Int, val bj: Int)
            val ops = mutableListOf<Op>()
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && a[i] == b[j] -> { ops += Op(' ', i, j); i += 1; j += 1 }
                    j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j]) -> { ops += Op('+', i, j); j += 1 }
                    else -> { ops += Op('-', i, j); i += 1 }
                }
            }
            val out = StringBuilder()
            out.append("--- ").append(aName).append('\n').append("+++ ").append(bName).append('\n')
            var k = 0
            while (k < ops.size) {
                if (ops[k].kind == ' ') {
                    k += 1
                    continue
                }
                val start = maxOf(0, k - context)
                var end = k
                var lastChange = k
                while (end < ops.size && end - lastChange <= context * 2) {
                    if (ops[end].kind != ' ') {
                        lastChange = end
                    }
                    end += 1
                }
                end = minOf(ops.size, lastChange + context + 1)
                val slice = ops.subList(start, end)
                val aStart = slice.first().ai + 1
                val bStart = slice.first().bj + 1
                val aCount = slice.count { it.kind != '+' }
                val bCount = slice.count { it.kind != '-' }
                out.append("@@ -$aStart,$aCount +$bStart,$bCount @@\n")
                slice.forEach { op ->
                    val text = if (op.kind == '+') b[op.bj] else a[op.ai]
                    out.append(op.kind).append(text).append('\n')
                }
                k = end
            }
            return out.toString()
        }
    }
}
