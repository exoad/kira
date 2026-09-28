package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.cpp.CppBackendResult
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import net.exoad.kira.kim.BuildOptions
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.kim.ProjectSpec
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A Kira project written to disk and emitted the way `kira --target cpp` does it (the real
 * factory, the real layout, the runtime copied from `kira/cpp`), then compiled with a driver
 * under the warning contract (design 8.2) on every toolchain that links, and run.
 */
object CppExprTestSupport {
    /** A module: its URI (`rows:r1`, whose namespace is `r1`) and its Kira text after the module line. */
    data class Module(val uri: String, val body: String) {
        val relativePath: String
            get() {
                val (pkg, dotted) = uri.split(":", limit = 2)
                return "src/$pkg/${dotted.replace('.', '/')}.kira"
            }

        val text: String get() = "module \"$uri\"\n\n${body.trimIndent()}\n"
    }

    /** An emitted project: its root, what the backend reported, and each module's generated text. */
    class Tree(val root: Path, val result: CppBackendResult, val runtimeDir: Path) {
        fun header(m: Module): String = read(m.relativePath.removeSuffix(".kira") + ".kira.hxx")
        fun source(m: Module): String = read(m.relativePath.removeSuffix(".kira") + ".kira.cxx")
        fun text(m: Module): String = header(m) + source(m)

        private fun read(relative: String): String {
            val p = root.resolve(relative)
            return if (Files.isRegularFile(p)) Files.readString(p).replace("\r\n", "\n") else ""
        }

        /** Every generated `.kira.cxx` under `src/`, sorted. */
        fun sources(): List<File> = root.resolve("src").toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kira.cxx") }.sortedBy { it.path }.toList()
    }

    /** Writes [modules] under `build/tmp/cpp-exprs/<name>` and runs the backend over them. */
    fun emit(
        name: String,
        modules: List<Module>,
        options: CppOptions = CppOptions(lineDirectives = false),
        emitterFactory: (CompilationUnit, CppOptions) -> CppModuleEmitter = CppModuleEmitterFactory::create,
    ): Tree {
        val (root, result, report) = run(name, modules, options, emitterFactory)
        assertEquals(0, result.exitCode, "the backend refused the project:\n" + report.joinToString("\n") +
            "\n" + result.diagnostics.joinToString("\n") { it.render() })
        return Tree(root, result, root.resolve(options.effectiveRuntimeDir))
    }

    /** As [emit], for a project the typer, the rules or the emitter must refuse: its result, which writes nothing. */
    fun emitRefused(
        name: String,
        modules: List<Module>,
        options: CppOptions = CppOptions(lineDirectives = false),
    ): CppBackendResult {
        val (_, result, _) = run(name, modules, options, CppModuleEmitterFactory::create)
        assertTrue(result.exitCode != 0 && result.diagnostics.any { it.isError }, "the backend accepted the project")
        return result
    }

    private fun run(
        name: String,
        modules: List<Module>,
        options: CppOptions,
        emitterFactory: (CompilationUnit, CppOptions) -> CppModuleEmitter,
    ): Triple<Path, CppBackendResult, List<String>> {
        val root = Path.of("build/tmp/cpp-exprs").resolve(name).toAbsolutePath().normalize()
        root.toFile().deleteRecursively()
        Files.createDirectories(root)
        val unit = CompilationUnit()
        for (m in modules) {
            val file = root.resolve(m.relativePath)
            Files.createDirectories(file.parent)
            Files.writeString(file, m.text)
            val processed = KiraPreprocessor(m.text).process().processedContent
            val canonical = file.toFile().canonicalPath
            var ctx = unit.addSource(canonical, processed, emptyList())
            val tokens = KiraLexer(ctx).tokenize()
            ctx = unit.addSource(canonical, ctx.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        val manifest = ProjectManifest(ProjectSpec(name), srcDir = "src", build = BuildOptions(target = "cpp", cpp = options))
        val report = mutableListOf<String>()
        val result = KiraCppBackend.run(
            unit, manifest, check = false, projectRoot = root,
            emitterFactory = emitterFactory,
            version = "dev",
            stdlibCppDir = Path.of("kira/cpp").toAbsolutePath().normalize(),
            log = { }, report = { report += it }, out = { },
        )
        return Triple(root, result, report)
    }

    /**
     * Compiles [tree]'s sources and [driver] (a `main.cxx` body) under [toolchain]'s warning
     * contract; on a toolchain that links, runs the program and returns its stdout (null for a
     * compile-only toolchain).
     */
    fun compileAndRun(tree: Tree, driver: String, toolchain: CppToolchain): String? {
        Assumptions.assumeTrue(CppToolchains.isEnabled(toolchain), "toolchain '${toolchain.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(toolchain)
        val driverFile = tree.root.resolve("driver/main.cxx")
        Files.createDirectories(driverFile.parent)
        Files.writeString(driverFile, driver)
        val outDir = tree.root.resolve("out/${toolchain.id}").toFile()
        val result = CppCompileSupport.compile(
            sources = tree.sources() + driverFile.toFile(),
            includeDirs = listOf(tree.root.toFile(), tree.runtimeDir.toFile()),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.forToolchain(toolchain),
            outDir = outDir,
        )
        assertTrue(result.success, "compile failed under ${toolchain.id}\n${result.describe()}")
        if (toolchain.compileOnly) {
            assertTrue(result.objects.isNotEmpty(), "${toolchain.id} compiled nothing")
            return null
        }
        val exe = result.exe ?: fail("${toolchain.id} reported success without an executable")
        val run = CppCompileSupport.run(exe, workingDir = outDir, extraPathDirs = listOfNotNull(located.binDir))
        assertTrue(!run.timedOut, "${toolchain.id}: the program timed out")
        assertEquals(0, run.exitCode, "${toolchain.id}: the program exited ${run.exitCode}\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}")
        return run.stdout
    }

    /** The check helpers a driver's checks call: bibo's check format. */
    const val CHECK_PRELUDE: String = """
#include <cstdio>
#include <cstring>
#include <string>

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++failures;
      }
  }
}
"""
}
