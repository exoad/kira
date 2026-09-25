package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.EmittedModule
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A test-only emitter: a recognisable header per module and, unless the
 * module is header-only, a source. It never reports a diagnostic unless
 * told to, so the tests exercise the plumbing around it, not a lowering.
 */
class FakeCppModuleEmitter(
    private val options: CppOptions,
    private val extraDiagnostics: List<CppDiagnostic> = emptyList(),
    private val failUris: Set<String> = emptySet(),
) : CppModuleEmitter {
    val emittedUris = mutableListOf<String>()

    override fun emit(source: SourceContext): EmittedModule {
        val uri = source.getModuleUri()
        emittedUris += uri
        if (uri in failUris) {
            return EmittedModule("", null, listOf(CppDiagnostic("cpp.fake-failure", "told to fail $uri", file = source.file)))
        }
        val stem = CppModuleLayout.stemOf(File(source.file).name)
        val header = "// fake header for $uri\n#pragma once\n"
        val body = if (options.isHeaderOnly(uri)) null else "// fake source for $uri\n#include \"$stem${options.headerExt}\"\n"
        return EmittedModule(header, body, extraDiagnostics)
    }
}

object PlumbingTestSupport {
    /** A fresh directory under build/tmp, so nothing leaks into the repository. */
    fun tempProject(name: String): Path {
        val dir = Path.of("build/tmp/cpp-plumbing").resolve(name).toAbsolutePath().normalize()
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    fun write(root: Path, relative: String, text: String): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    /** A stand-in stdlib with a C++ runtime: `<root>/cpp/kira/{core,rt}.hxx`. */
    fun fakeStdlib(root: Path): Path {
        val stdlib = root.resolve("stdlib")
        write(stdlib, "cpp/kira/core.hxx", "#pragma once\n// fake core\n")
        write(stdlib, "cpp/kira/rt.hxx", "#pragma once\n#include \"kira/core.hxx\"\n// fake rt\n")
        write(stdlib, "cpp/tests/rt_test.cxx", "// not installed\n")
        return stdlib.resolve("cpp")
    }

    /** Parses [files] (absolute path to Kira text) the way the CLI does. */
    fun compilationUnit(files: Map<Path, String>): CompilationUnit {
        val unit = CompilationUnit()
        files.forEach { (path, text) ->
            val processed = KiraPreprocessor(text).process()
            val canonical = path.toFile().canonicalPath
            var ctx = unit.addSource(canonical, processed.processedContent, emptyList())
            val tokens = KiraLexer(ctx).tokenize()
            ctx = unit.addSource(canonical, ctx.content, tokens)
            KiraSourceParsers.from(ctx).parse()
        }
        return unit
    }

    fun module(uri: String, body: String = ""): String {
        return "module \"$uri\"\n\n" + body.trimIndent() + "\n"
    }

    /** Every regular file under [root], relative with forward slashes, sorted. */
    fun listFiles(root: Path): List<String> {
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        Files.walk(root).use { stream ->
            return stream.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }
    }
}
