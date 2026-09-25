package net.exoad.kira.cpp.support

import java.io.File
import java.util.concurrent.TimeUnit

/** Which runtime profile a translation unit is built for (design 8.2, 10). */
enum class CppProfile(val id: String) {
    HOSTED("hosted"),
    FREESTANDING("freestanding");

    companion object {
        fun byId(id: String): CppProfile? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Compiles, runs and inspects C++ with one of the [CppToolchain]s, applying
 * the warning contract of design 8.2:
 *
 * - MSVC `/std:c++20 /W4 /WX` (plus `/EHsc /permissive-`, as kira/cpp/tests/msvc.bat);
 * - g++, clang and zig `-std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror`;
 * - arm-none-eabi `-std=c++20 -mcpu=cortex-m33 -mthumb -Os -fno-exceptions -fno-rtti -Wall -Wextra -Wconversion -Werror -DKIRA_PROFILE_FREESTANDING=1`;
 * - every gcc/clang family build adds `-ffp-contract=off` (D28), so one expected.txt serves x64 and aarch64.
 *
 * Every candidate output (the exe, `.exe`, `.o`, `.obj`, and the batch file
 * MSVC runs through) is deleted before a compile starts: a stale binary once
 * reported a pass for code that no longer compiled.
 */
object CppCompileSupport {
    data class CompileResult(
        val toolchain: CppToolchain,
        val success: Boolean,
        /** The linked program, when the toolchain links; null for compile-only toolchains and on failure. */
        val exe: File?,
        /** Every object file the compile produced (all toolchains). */
        val objects: List<File>,
        val commands: List<List<String>>,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        /** Compiler diagnostics, wherever the toolchain put them (cl writes them to stdout). */
        val diagnostics: String get() = (stdout + "\n" + stderr).trim()

        fun describe(): String = buildString {
            appendLine("toolchain: ${toolchain.id}, exit ${exitCode}")
            for (c in commands) appendLine("  $ " + c.joinToString(" "))
            appendLine(diagnostics)
        }
    }

    data class RunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    data class NmResult(
        val obj: File,
        val symbols: List<String>,
        val forbidden: List<String>,
    ) {
        val clean: Boolean get() = forbidden.isEmpty()
    }

    /** Symbols a freestanding object must not reference or define (design 10, W1.3): heap, exceptions, RTTI. */
    val forbiddenSymbolPatterns: List<Regex> = listOf(
        Regex("^_?malloc$"),
        Regex("^_?calloc$"),
        Regex("^_?realloc$"),
        Regex("^_?free$"),
        Regex("_Znw"),    // operator new
        Regex("_Znam"),   // operator new[]
        Regex("_Zdl"),    // operator delete
        Regex("__cxa"),   // C++ ABI runtime (throw, guard, ...)
        Regex("_Unwind"), // unwinder
        Regex("_ZTI"),    // typeinfo
        Regex("_ZTS"),    // typeinfo name
        Regex("typeinfo"),
    )

    private const val COMPILE_TIMEOUT_SECONDS = 300L

    /**
     * Compile [sources] (and link them, unless the toolchain is compile-only)
     * into [outDir], which is wiped first. The exe is named [exeName] plus
     * the host's suffix.
     */
    fun compile(
        sources: List<File>,
        includeDirs: List<File>,
        defines: List<String>,
        toolchain: LocatedToolchain.Found,
        profile: CppProfile,
        outDir: File,
        exeName: String = "app",
    ): CompileResult {
        require(sources.isNotEmpty()) { "compile: no sources" }
        for (s in sources) require(s.isFile) { "compile: missing source $s" }
        clearOutputs(outDir)
        outDir.mkdirs()

        val allDefines = buildList {
            if (profile == CppProfile.FREESTANDING) add("KIRA_PROFILE_FREESTANDING=1")
            addAll(defines)
        }

        return when (toolchain.toolchain) {
            CppToolchain.MSVC -> compileMsvc(sources, includeDirs, allDefines, toolchain, outDir, exeName)
            CppToolchain.ZIG_AARCH64, CppToolchain.ARM -> compileObjectsOnly(sources, includeDirs, allDefines, toolchain, outDir)
            CppToolchain.GCC, CppToolchain.CLANG -> compileGnuLink(sources, includeDirs, allDefines, toolchain, outDir, exeName)
        }
    }

    /** Run a produced program with a timeout; stdout has the Windows `\r` stripped, as TestCompileSupport does. */
    fun run(
        exe: File,
        workingDir: File = exe.parentFile,
        timeoutSeconds: Long = 60,
        extraPathDirs: List<File> = emptyList(),
        args: List<String> = emptyList(),
    ): RunResult {
        require(exe.isFile) { "run: $exe does not exist" }
        val builder = ProcessBuilder(listOf(exe.absolutePath) + args).directory(workingDir)
        if (extraPathDirs.isNotEmpty()) {
            val env = builder.environment()
            val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            env[key] = extraPathDirs.joinToString(File.pathSeparator) { it.absolutePath } +
                File.pathSeparator + (env[key] ?: "")
        }
        val process = builder.start()
        val stderr = StringBuilder()
        val stderrThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
        stderrThread.start()
        val stdoutHolder = StringBuilder()
        val stdoutThread = Thread { stdoutHolder.append(process.inputStream.bufferedReader().readText()) }
        stdoutThread.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        val code = if (finished) process.exitValue() else -1
        return RunResult(code, stdoutHolder.toString().replace("\r", ""), stderr.toString(), timedOut = !finished)
    }

    /**
     * List [obj]'s symbols with [nmTool] (`arm-none-eabi-nm` beside the
     * compiler when the caller passes none) and report the forbidden ones.
     */
    fun nmCheck(obj: File, toolchain: LocatedToolchain.Found, nmTool: File? = null): NmResult {
        require(obj.isFile) { "nmCheck: $obj does not exist" }
        val nm = nmTool ?: nmBeside(toolchain)
            ?: throw IllegalStateException("nmCheck: no nm beside ${toolchain.command.first()}")
        val process = ProcessBuilder(listOf(nm.absolutePath, obj.absolutePath)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("nmCheck: $nm timed out")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("nmCheck: $nm exited ${process.exitValue()}: ${output.trim()}")
        }
        val symbols = output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("\\s+")).last() }
        val forbidden = symbols.filter { sym -> forbiddenSymbolPatterns.any { it.containsMatchIn(sym) } }
        return NmResult(obj, symbols, forbidden)
    }

    /** `arm-none-eabi-nm` (or `nm`) in the compiler's directory. */
    fun nmBeside(toolchain: LocatedToolchain.Found): File? {
        val dir = toolchain.binDir ?: return null
        val gxx = File(toolchain.command.first()).name
        val prefix = gxx.substringBefore("g++")
        return CppToolchains.findInDir(dir, prefix + "nm") ?: CppToolchains.findInDir(dir, "nm")
    }

    // ---- flags ------------------------------------------------------------

    val gnuWarningContract: List<String> = listOf(
        "-std=c++20", "-Wall", "-Wextra", "-Wconversion", "-Wsign-conversion", "-Wshadow", "-Wnon-virtual-dtor", "-Werror",
        "-ffp-contract=off",
    )

    val armFlags: List<String> = listOf(
        "-std=c++20", "-mcpu=cortex-m33", "-mthumb", "-Os", "-fno-exceptions", "-fno-rtti",
        "-Wall", "-Wextra", "-Wconversion", "-Werror", "-ffp-contract=off",
        "-DKIRA_PROFILE_FREESTANDING=1",
    )

    val msvcWarningContract: List<String> = listOf("/nologo", "/std:c++20", "/W4", "/WX", "/EHsc", "/permissive-")

    /** The flags [compile] passes for a toolchain, before includes, defines and files. */
    fun contractFlags(toolchain: CppToolchain): List<String> = when (toolchain) {
        CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.ZIG_AARCH64 -> gnuWarningContract
        CppToolchain.ARM -> armFlags
        CppToolchain.MSVC -> msvcWarningContract
    }

    // ---- per-family compiles ---------------------------------------------

    private fun compileGnuLink(
        sources: List<File>, includeDirs: List<File>, defines: List<String>,
        toolchain: LocatedToolchain.Found, outDir: File, exeName: String,
    ): CompileResult {
        val exe = File(outDir, if (CppToolchains.isWindows) "$exeName.exe" else exeName)
        val command = toolchain.command + gnuWarningContract +
            includeDirs.flatMap { listOf("-I", it.absolutePath) } +
            defines.map { "-D$it" } +
            sources.map { it.absolutePath } +
            listOf("-o", exe.absolutePath)
        val r = execute(command, outDir)
        val ok = r.exitCode == 0 && exe.isFile
        val objects = outDir.listFiles { f -> f.name.endsWith(".o") }?.toList() ?: emptyList()
        return CompileResult(
            toolchain.toolchain, ok, if (ok) exe else null, objects, listOf(command),
            r.exitCode, r.stdout, if (r.exitCode == 0 && !exe.isFile) r.stderr + "\ncompiler exited 0 but produced no $exe" else r.stderr,
        )
    }

    private fun compileObjectsOnly(
        sources: List<File>, includeDirs: List<File>, defines: List<String>,
        toolchain: LocatedToolchain.Found, outDir: File,
    ): CompileResult {
        val flags = contractFlags(toolchain.toolchain)
        val commands = ArrayList<List<String>>()
        val objects = ArrayList<File>()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exit = 0
        for ((index, source) in sources.withIndex()) {
            val obj = File(outDir, "${index}_${source.nameWithoutExtension}.o")
            val command = toolchain.command + flags +
                includeDirs.flatMap { listOf("-I", it.absolutePath) } +
                defines.map { "-D$it" } +
                listOf("-c", source.absolutePath, "-o", obj.absolutePath)
            commands += command
            val r = execute(command, outDir)
            stdout.append(r.stdout)
            stderr.append(r.stderr)
            if (r.exitCode != 0) {
                exit = r.exitCode
                break
            }
            if (!obj.isFile) {
                stderr.append("\ncompiler exited 0 but produced no $obj")
                exit = -2
                break
            }
            objects += obj
        }
        return CompileResult(toolchain.toolchain, exit == 0, null, objects, commands, exit, stdout.toString(), stderr.toString())
    }

    private fun compileMsvc(
        sources: List<File>, includeDirs: List<File>, defines: List<String>,
        toolchain: LocatedToolchain.Found, outDir: File, exeName: String,
    ): CompileResult {
        val vcvars = toolchain.command.last()
        val exe = File(outDir, "$exeName.exe")
        val clArgs = msvcWarningContract +
            includeDirs.map { "/I" + quoteBat(it.absolutePath) } +
            defines.map { "/D" + quoteBat(it) } +
            listOf("/Fe" + quoteBat(exe.absolutePath)) +
            sources.map { quoteBat(it.absolutePath) }
        // A batch file: `cmd /c call "<path with spaces>" && cl ...` loses
        // its quoting on the way through ProcessBuilder, a file does not.
        val bat = File(outDir, "compile.bat")
        bat.writeText(
            "@echo off\r\n" +
                "cd /d \"${outDir.absolutePath}\"\r\n" +
                "call \"$vcvars\" >nul 2>&1 || exit /b 97\r\n" +
                "cl " + clArgs.joinToString(" ") + "\r\n" +
                "exit /b %ERRORLEVEL%\r\n"
        )
        val command = listOf("cmd", "/c", bat.absolutePath)
        val r = execute(command, outDir)
        val objects = outDir.listFiles { f -> f.name.endsWith(".obj") }?.toList() ?: emptyList()
        val ok = r.exitCode == 0 && exe.isFile
        val shown = listOf("cl") + clArgs
        return CompileResult(
            toolchain.toolchain, ok, if (ok) exe else null, objects, listOf(shown),
            r.exitCode, r.stdout,
            when {
                r.exitCode == 97 -> r.stderr + "\ncall $vcvars failed"
                r.exitCode == 0 && !exe.isFile -> r.stderr + "\ncl exited 0 but produced no $exe"
                else -> r.stderr
            },
        )
    }

    private fun quoteBat(s: String): String = if (s.any { it == ' ' || it == '(' || it == ')' }) "\"$s\"" else s

    /** Remove every output a previous compile could have left, so nothing stale can pass. */
    fun clearOutputs(outDir: File) {
        if (!outDir.exists()) return
        val stale = outDir.walkTopDown().filter { it.isFile }.filter { f ->
            val n = f.name.lowercase()
            n.endsWith(".exe") || n.endsWith(".o") || n.endsWith(".obj") || n.endsWith(".pdb") ||
                n.endsWith(".ilk") || n.endsWith(".bat") || f.extension.isEmpty()
        }.toList()
        for (f in stale) {
            if (!f.delete()) throw IllegalStateException("could not delete stale output $f")
        }
    }

    private fun execute(command: List<String>, workingDir: File): RunResult {
        val process = ProcessBuilder(command).directory(workingDir).start()
        val stderr = StringBuilder()
        val stderrThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
        stderrThread.start()
        val stdout = StringBuilder()
        val stdoutThread = Thread { stdout.append(process.inputStream.bufferedReader().readText()) }
        stdoutThread.start()
        val finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        return RunResult(
            if (finished) process.exitValue() else -1,
            stdout.toString(),
            stderr.toString() + if (finished) "" else "\n(compile timed out after ${COMPILE_TIMEOUT_SECONDS}s)",
            timedOut = !finished,
        )
    }
}
