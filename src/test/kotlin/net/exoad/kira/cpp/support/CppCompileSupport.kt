package net.exoad.kira.cpp.support

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which runtime profile a translation unit is built for (design 8.2, 10).
 *
 * The profile follows the toolchain, never the case: `KIRA_PROFILE_FREESTANDING=1`
 * is set for the Pico image only, and host suites compile the same headers
 * hosted (design 8.2). So [ARM][CppToolchain.ARM] is always [FREESTANDING]
 * and every other toolchain is always [HOSTED]; a freestanding case is a
 * valid hosted case (design 10), which is what the host toolchains prove.
 */
enum class CppProfile(val id: String) {
    HOSTED("hosted"),
    FREESTANDING("freestanding");

    companion object {
        fun byId(id: String): CppProfile? = entries.firstOrNull { it.id == id }

        /** The one profile a toolchain builds (design 8.1). */
        fun forToolchain(toolchain: CppToolchain): CppProfile =
            if (toolchain == CppToolchain.ARM) FREESTANDING else HOSTED
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
 * `KIRA_PROFILE_FREESTANDING=1` appears in the arm line and nowhere else:
 * the profile is a property of the toolchain (design 8.2), and [compile]
 * refuses a [CppProfile] that does not match its toolchain rather than
 * quietly building something the design never runs.
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

    /**
     * Symbols a freestanding object must not reference or define (design 10,
     * W1.3): heap, exceptions, RTTI.
     *
     * The Itanium ABI mangles `operator new` as `_Znw<size>`, `new[]` as
     * `_Zna<size>`, `delete` as `_Zdl...` and `delete[]` as `_Zda...`, where
     * `<size>` is the target's `size_t`: `j` on 32-bit arm-none-eabi, `m` on
     * x64 and aarch64. The patterns match the operator, not one size letter,
     * so the aligned and nothrow overloads (`_ZnwjSt11align_val_t`,
     * `_ZnajRKSt9nothrow_t`) and sized deletes (`_ZdlPvj`) are covered too.
     * No ordinary name can mangle to `_Zn`/`_Zd` followed by a lowercase
     * pair: an identifier always carries its length first.
     */
    val forbiddenSymbolPatterns: List<Regex> = listOf(
        Regex("^_?malloc$"),
        Regex("^_?calloc$"),
        Regex("^_?realloc$"),
        Regex("^_?free$"),
        Regex("^_Zn[wa]"),   // operator new, operator new[]
        Regex("^_Zd[la]"),   // operator delete, operator delete[]
        Regex("__cxa"),      // C++ ABI runtime (throw, guard, ...)
        Regex("_Unwind"),    // unwinder
        Regex("^_ZTI"),      // typeinfo
        Regex("^_ZTS"),      // typeinfo name
        Regex("typeinfo"),
    )

    private const val COMPILE_TIMEOUT_SECONDS = 300L

    /**
     * Compile [sources] (and link them, unless the toolchain is compile-only)
     * into [outDir], which is wiped first. The exe is named [exeName] plus
     * the host's suffix.
     *
     * [profile] must be [CppProfile.forToolchain] of the toolchain: it is the
     * caller stating which profile it expects, and a mismatch is a bug in the
     * caller (design 8.2), reported as an [IllegalArgumentException].
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
        val expected = CppProfile.forToolchain(toolchain.toolchain)
        require(profile == expected) {
            "compile: toolchain '${toolchain.toolchain.id}' builds the ${expected.id} profile only, not ${profile.id} " +
                "(design 8.2: KIRA_PROFILE_FREESTANDING=1 is set for the Pico image only; host suites compile the same headers hosted)"
        }
        for (d in defines) require(!d.startsWith("KIRA_PROFILE_")) {
            "compile: '$d' is not a case define; the profile follows the toolchain (design 8.2)"
        }
        clearOutputs(outDir)
        outDir.mkdirs()

        return when (toolchain.toolchain) {
            CppToolchain.MSVC -> compileMsvc(sources, includeDirs, defines, toolchain, outDir, exeName)
            CppToolchain.ZIG_AARCH64, CppToolchain.ARM -> compileObjectsOnly(sources, includeDirs, defines, toolchain, outDir)
            CppToolchain.GCC, CppToolchain.CLANG -> compileGnuLink(sources, includeDirs, defines, toolchain, outDir, exeName)
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
        val r = await(builder.start(), timeoutSeconds)
        return r.copy(stdout = r.stdout.replace("\r", ""))
    }

    /**
     * List [obj]'s symbols with [nmTool] (`arm-none-eabi-nm` beside the
     * compiler when the caller passes none) and report the forbidden ones.
     */
    fun nmCheck(obj: File, toolchain: LocatedToolchain.Found, nmTool: File? = null): NmResult {
        require(obj.isFile) { "nmCheck: $obj does not exist" }
        val nm = nmTool ?: nmBeside(toolchain)
            ?: throw IllegalStateException("nmCheck: no nm beside ${toolchain.command.first()}")
        val r = await(ProcessBuilder(listOf(nm.absolutePath, obj.absolutePath)).redirectErrorStream(true).start(), 60)
        if (r.timedOut) {
            throw IllegalStateException("nmCheck: $nm timed out")
        }
        if (r.exitCode != 0) {
            throw IllegalStateException("nmCheck: $nm exited ${r.exitCode}: ${r.stdout.trim()}")
        }
        val symbols = r.stdout.lines()
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

    /** `<index>_<basename>.<ext>`: two sources may share a basename (`a/util.kira.cxx`, `b/util.kira.cxx`). */
    fun objectName(index: Int, source: File, extension: String): String =
        "${index}_${source.nameWithoutExtension}.$extension"

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
            val obj = File(outDir, objectName(index, source, "o"))
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

    /**
     * One `cl /c` per source into an indexed object name, then one link
     * step. A single `cl <all sources>` writes every object as
     * `<basename>.obj` into the working directory, so two sources sharing a
     * basename (`a/util.kira.cxx`, `b/util.kira.cxx`) collide: LNK4042
     * "object specified more than once" and an unresolved symbol.
     */
    private fun compileMsvc(
        sources: List<File>, includeDirs: List<File>, defines: List<String>,
        toolchain: LocatedToolchain.Found, outDir: File, exeName: String,
    ): CompileResult {
        val vcvars = toolchain.command.last()
        val exe = File(outDir, "$exeName.exe")
        val common = msvcWarningContract +
            includeDirs.map { "/I" + quoteBat(it.absolutePath) } +
            defines.map { "/D" + quoteBat(it) }
        val objectFiles = sources.mapIndexed { index, source -> File(outDir, objectName(index, source, "obj")) }
        val compileLines = sources.mapIndexed { index, source ->
            listOf("cl") + common + listOf("/c", "/Fo" + quoteBat(objectFiles[index].absolutePath), quoteBat(source.absolutePath))
        }
        val linkLine = listOf("cl", "/nologo", "/Fe" + quoteBat(exe.absolutePath)) + objectFiles.map { quoteBat(it.absolutePath) }
        val shown = compileLines + listOf(linkLine)

        // A batch file: `cmd /c call "<path with spaces>" && cl ...` loses
        // its quoting on the way through ProcessBuilder, a file does not.
        // %ERRORLEVEL% is read on its own line, after cl has run: cmd expands
        // it when it parses a line, so `cl ... || exit /b %ERRORLEVEL%` would
        // report the level from before cl.
        val bat = File(outDir, "compile.bat")
        bat.writeText(buildString {
            append("@echo off\r\n")
            append("cd /d \"${outDir.absolutePath}\"\r\n")
            append("call \"$vcvars\" >nul 2>&1 || exit /b 97\r\n")
            for (line in shown) {
                append(line.joinToString(" ")).append("\r\n")
                append("set CL_EXIT=%ERRORLEVEL%\r\n")
                append("if not \"%CL_EXIT%\"==\"0\" exit /b %CL_EXIT%\r\n")
            }
            append("exit /b 0\r\n")
        })
        val command = listOf("cmd", "/c", bat.absolutePath)
        val r = execute(command, outDir)
        val objects = objectFiles.filter { it.isFile }
        val ok = r.exitCode == 0 && exe.isFile
        return CompileResult(
            toolchain.toolchain, ok, if (ok) exe else null, objects, shown,
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
        val r = await(ProcessBuilder(command).directory(workingDir).start(), COMPILE_TIMEOUT_SECONDS)
        return if (r.timedOut) r.copy(stderr = r.stderr + "\n(compile timed out after ${COMPILE_TIMEOUT_SECONDS}s)") else r
    }

    /**
     * Drain both streams on their own threads and wait at most
     * [timeoutSeconds]; a process that hangs is killed and reported as
     * [RunResult.timedOut] with exit -1. Reading a stream on the calling
     * thread would block for as long as the process keeps it open, and the
     * timeout would never fire.
     */
    internal fun await(process: Process, timeoutSeconds: Long): RunResult {
        val stderr = StringBuilder()
        val stderrThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
        stderrThread.isDaemon = true
        stderrThread.start()
        val stdout = StringBuilder()
        val stdoutThread = Thread { stdout.append(process.inputStream.bufferedReader().readText()) }
        stdoutThread.isDaemon = true
        stdoutThread.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        return RunResult(
            if (finished) process.exitValue() else -1,
            stdout.toString(),
            stderr.toString(),
            timedOut = !finished,
        )
    }
}
