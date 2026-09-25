package net.exoad.kira.cpp.support

import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.fail

/**
 * The C++ toolchains the harness can drive, and where each one is found.
 *
 * Discovery never shells out to `which`: on Windows that prints an MSYS
 * `/c/...` path that [ProcessBuilder] cannot launch. It walks PATH itself
 * (the `TestCompileSupport.findOnPath` approach; that file belongs to the
 * ground branch and is not edited here), then a short list of well-known
 * directories, and finally runs the tool once to prove it answers.
 *
 * Environment knobs:
 * - `KIRA_CXX_GCC`, `KIRA_CXX_CLANG`, `KIRA_ZIG`, `KIRA_MSVC_VCVARS`,
 *   `KIRA_ARM_GXX`: override one tool (a file path, or a command with
 *   arguments such as `zig c++`). An override that resolves to nothing is a
 *   MISSING toolchain, never a fallback to PATH.
 * - `KIRA_TOOLCHAINS=gcc,clang,...` restricts the set that is exercised.
 * - `KIRA_REQUIRE_TOOLCHAINS=1` turns a missing toolchain into a test
 *   failure. Without it a missing toolchain skips its tests (a JUnit
 *   assumption), which is right for a laptop and wrong for CI.
 */
enum class CppToolchain(val id: String, val envOverride: String, val compileOnly: Boolean) {
    /** g++ (13 on the laptop, 11.4 on the board and in the gcc11 job). */
    GCC("gcc", "KIRA_CXX_GCC", compileOnly = false),

    /** clang++ on Linux/macOS; `zig c++` on Windows, where LLVM clang 18 rejects the MSVC 14.44 STL. */
    CLANG("clang", "KIRA_CXX_CLANG", compileOnly = false),

    /** `zig c++ -target aarch64-linux-gnu.2.35`, the Pi/Jetson cross build: compile only. */
    ZIG_AARCH64("zig-aarch64", "KIRA_ZIG", compileOnly = true),

    /** cl 19.x through vcvars64.bat. */
    MSVC("msvc", "KIRA_MSVC_VCVARS", compileOnly = false),

    /** arm-none-eabi-g++ for the Pico: freestanding, compile only, nm-checked. */
    ARM("arm", "KIRA_ARM_GXX", compileOnly = true);

    companion object {
        fun byId(id: String): CppToolchain? = entries.firstOrNull { it.id == id }
        val ids: List<String> get() = entries.map { it.id }
    }
}

/** Where a toolchain was found, or why it was not. */
sealed class LocatedToolchain {
    abstract val toolchain: CppToolchain

    /**
     * [command] is the launch prefix (`[g++]`, `[zig, c++]`, or
     * `[cmd, /c, <vcvars64.bat>]` for MSVC, which [CppCompileSupport] turns
     * into a batch file). [binDir] is the directory that holds the tool, or
     * null when it is not a plain executable; it is prepended to PATH when a
     * produced program runs, so a MinGW exe finds its libstdc++ DLL.
     */
    data class Found(
        override val toolchain: CppToolchain,
        val command: List<String>,
        val binDir: File?,
        val version: String,
        val source: String,
    ) : LocatedToolchain()

    data class Missing(
        override val toolchain: CppToolchain,
        val reason: String,
    ) : LocatedToolchain()
}

object CppToolchains {
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** `KIRA_REQUIRE_TOOLCHAINS=1`: a missing toolchain fails instead of skipping. */
    val required: Boolean
        get() = System.getenv("KIRA_REQUIRE_TOOLCHAINS")?.trim().let { it == "1" || it.equals("true", ignoreCase = true) }

    /** `KIRA_TOOLCHAINS`: the enabled subset (every toolchain when unset). */
    val enabled: Set<CppToolchain> by lazy {
        val raw = System.getenv("KIRA_TOOLCHAINS")?.trim()
        if (raw.isNullOrEmpty()) {
            CppToolchain.entries.toSet()
        } else {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { id ->
                CppToolchain.byId(id) ?: fail("KIRA_TOOLCHAINS names an unknown toolchain '$id' (known: ${CppToolchain.ids})")
            }.toSet()
        }
    }

    fun isEnabled(toolchain: CppToolchain): Boolean = toolchain in enabled

    private val cache = HashMap<CppToolchain, LocatedToolchain>()

    /** Resolve once per JVM; the probe run is the expensive part (vcvars takes about a second). */
    @Synchronized
    fun locate(toolchain: CppToolchain): LocatedToolchain {
        return cache.getOrPut(toolchain) { discover(toolchain) }
    }

    /**
     * The test-side gate. Returns the toolchain when it is usable; otherwise
     * FAILS under `KIRA_REQUIRE_TOOLCHAINS=1` and aborts (skips) the test
     * without it. The message always names the toolchain and the reason.
     */
    fun requireOrSkip(toolchain: CppToolchain): LocatedToolchain.Found {
        return when (val located = locate(toolchain)) {
            is LocatedToolchain.Found -> located
            is LocatedToolchain.Missing -> {
                val message = "toolchain '${toolchain.id}' is missing: ${located.reason} " +
                    "(override with ${toolchain.envOverride}; KIRA_TOOLCHAINS restricts the set)"
                if (required) {
                    fail("KIRA_REQUIRE_TOOLCHAINS=1 and $message")
                }
                Assumptions.assumeTrue(false, message)
                throw IllegalStateException("unreachable")
            }
        }
    }

    /** One line per toolchain, for logs and failure messages. */
    fun inventory(): String = CppToolchain.entries.joinToString("\n") { tc ->
        val state = if (!isEnabled(tc)) {
            "disabled by KIRA_TOOLCHAINS"
        } else {
            when (val l = locate(tc)) {
                is LocatedToolchain.Found -> "${l.command.joinToString(" ")}  [${l.source}] ${l.version}"
                is LocatedToolchain.Missing -> "MISSING: ${l.reason}"
            }
        }
        "  ${tc.id.padEnd(12)} $state"
    }

    // ---- discovery -------------------------------------------------------

    private val msysBinDirs: List<File>
        get() = if (isWindows) listOf(File("C:/msys64/ucrt64/bin"), File("C:/msys64/mingw64/bin")) else emptyList()

    private fun discover(toolchain: CppToolchain): LocatedToolchain {
        val override = System.getenv(toolchain.envOverride)?.trim()
        return try {
            when (toolchain) {
                CppToolchain.GCC -> discoverPlain(toolchain, override, listOf("g++"), msysBinDirs, versionArgs = listOf("--version"))
                CppToolchain.CLANG -> discoverClang(override)
                CppToolchain.ZIG_AARCH64 -> discoverZig(toolchain, override, listOf("-target", "aarch64-linux-gnu.2.35"))
                CppToolchain.MSVC -> discoverMsvc(override)
                CppToolchain.ARM -> discoverPlain(toolchain, override, listOf("arm-none-eabi-g++"), msysBinDirs, versionArgs = listOf("--version"))
            }
        } catch (e: Exception) {
            LocatedToolchain.Missing(toolchain, "discovery threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A tool that is a single executable, with an env override, PATH candidates and fallback directories. */
    private fun discoverPlain(
        toolchain: CppToolchain,
        override: String?,
        candidates: List<String>,
        fallbackDirs: List<File>,
        versionArgs: List<String>,
    ): LocatedToolchain {
        val resolved: Pair<List<String>, String> = if (!override.isNullOrEmpty()) {
            val cmd = resolveOverride(override)
                ?: return LocatedToolchain.Missing(toolchain, "${toolchain.envOverride}=$override is neither a file nor on PATH")
            cmd to "${toolchain.envOverride}"
        } else {
            var found: Pair<List<String>, String>? = null
            for (name in candidates) {
                val onPath = findOnPath(name)
                if (onPath != null) {
                    found = listOf(onPath.absolutePath) to "PATH"
                    break
                }
                for (dir in fallbackDirs) {
                    val inDir = findInDir(dir, name)
                    if (inDir != null) {
                        found = listOf(inDir.absolutePath) to dir.path
                        break
                    }
                }
                if (found != null) break
            }
            found ?: return LocatedToolchain.Missing(
                toolchain,
                "none of ${candidates} on PATH" + (if (fallbackDirs.isEmpty()) "" else " or in $fallbackDirs")
            )
        }
        val (command, source) = resolved
        return probe(toolchain, command, versionArgs, source)
    }

    private fun discoverClang(override: String?): LocatedToolchain {
        val toolchain = CppToolchain.CLANG
        if (!override.isNullOrEmpty()) {
            val cmd = resolveOverride(override)
                ?: return LocatedToolchain.Missing(toolchain, "${toolchain.envOverride}=$override is neither a file nor on PATH")
            // `clang++ --version` and `zig c++ --version` both answer.
            return probe(toolchain, cmd, listOf("--version"), toolchain.envOverride)
        }
        if (isWindows) {
            // LLVM clang 18 cannot parse the MSVC 14.44 STL; zig ships clang 20
            // with its own libc++, so clang on Windows means `zig c++`.
            return discoverZig(toolchain, System.getenv("KIRA_ZIG")?.trim(), emptyList())
        }
        return discoverPlain(toolchain, null, listOf("clang++"), emptyList(), versionArgs = listOf("--version"))
    }

    private fun discoverZig(toolchain: CppToolchain, override: String?, targetArgs: List<String>): LocatedToolchain {
        val zig: List<String> = if (!override.isNullOrEmpty()) {
            resolveOverride(override)
                ?: return LocatedToolchain.Missing(toolchain, "KIRA_ZIG=$override is neither a file nor on PATH")
        } else {
            val onPath = findOnPath("zig")
                ?: return LocatedToolchain.Missing(toolchain, "zig is not on PATH (set KIRA_ZIG)")
            listOf(onPath.absolutePath)
        }
        // An override may already say `zig c++`; do not double it.
        val command = if (zig.size >= 2 && zig[1] == "c++") zig + targetArgs else zig + listOf("c++") + targetArgs
        val probeResult = runQuick(listOf(zig[0], "version"))
            ?: return LocatedToolchain.Missing(toolchain, "${zig[0]} did not answer `zig version`")
        if (probeResult.exitCode != 0) {
            return LocatedToolchain.Missing(toolchain, "${zig[0]} version exited ${probeResult.exitCode}: ${probeResult.stderr.trim()}")
        }
        val source = if (!override.isNullOrEmpty()) "KIRA_ZIG" else "PATH"
        return LocatedToolchain.Found(toolchain, command, File(zig[0]).parentFile, "zig ${probeResult.stdout.trim()}", source)
    }

    private fun discoverMsvc(override: String?): LocatedToolchain {
        val toolchain = CppToolchain.MSVC
        if (!isWindows) {
            return LocatedToolchain.Missing(toolchain, "MSVC only exists on Windows")
        }
        val vcvars: File
        val source: String
        if (!override.isNullOrEmpty()) {
            vcvars = File(override)
            source = toolchain.envOverride
            if (!vcvars.isFile) {
                return LocatedToolchain.Missing(toolchain, "${toolchain.envOverride}=$override is not a file")
            }
        } else {
            val vswhere = File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe")
            if (!vswhere.isFile) {
                return LocatedToolchain.Missing(toolchain, "$vswhere is not installed (set ${toolchain.envOverride})")
            }
            val result = runQuick(
                listOf(
                    vswhere.absolutePath, "-latest", "-products", "*",
                    "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-property", "installationPath"
                )
            ) ?: return LocatedToolchain.Missing(toolchain, "vswhere did not answer")
            val installation = result.stdout.trim()
            if (result.exitCode != 0 || installation.isEmpty()) {
                return LocatedToolchain.Missing(toolchain, "vswhere found no Visual Studio with the C++ x64 tools")
            }
            vcvars = File(installation, "VC/Auxiliary/Build/vcvars64.bat")
            source = "vswhere"
            if (!vcvars.isFile) {
                return LocatedToolchain.Missing(toolchain, "$vcvars does not exist")
            }
        }
        // Prove that cl answers after vcvars: a batch file, because a command
        // line through `cmd /c` mangles the quoting of a path with spaces.
        val probeDir = File("build/tmp/cpp-harness/probe").apply { mkdirs() }
        val bat = File(probeDir, "msvc-probe.bat")
        bat.writeText(
            "@echo off\r\n" +
                "call \"${vcvars.absolutePath}\" >nul 2>&1 || exit /b 97\r\n" +
                "cl 2>&1\r\n" +
                "exit /b %ERRORLEVEL%\r\n"
        )
        val probeResult = runQuick(listOf("cmd", "/c", bat.absolutePath))
            ?: return LocatedToolchain.Missing(toolchain, "cmd /c ${bat.name} did not answer")
        if (probeResult.exitCode == 97) {
            return LocatedToolchain.Missing(toolchain, "call $vcvars failed")
        }
        val banner = probeResult.stdout.lines().firstOrNull { it.contains("Microsoft") }?.trim() ?: ""
        if (probeResult.exitCode != 0 || banner.isEmpty()) {
            return LocatedToolchain.Missing(toolchain, "cl did not answer after $vcvars (exit ${probeResult.exitCode}): ${probeResult.stdout.trim().take(200)}")
        }
        return LocatedToolchain.Found(toolchain, listOf("cmd", "/c", vcvars.absolutePath), null, banner, source)
    }

    private fun probe(toolchain: CppToolchain, command: List<String>, versionArgs: List<String>, source: String): LocatedToolchain {
        val result = runQuick(command + versionArgs)
            ?: return LocatedToolchain.Missing(toolchain, "${command.joinToString(" ")} could not be launched")
        if (result.exitCode != 0) {
            return LocatedToolchain.Missing(toolchain, "${command.joinToString(" ")} ${versionArgs.joinToString(" ")} exited ${result.exitCode}: ${result.stderr.trim().take(200)}")
        }
        val version = result.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "(no version line)"
        return LocatedToolchain.Found(toolchain, command, File(command[0]).parentFile, version, source)
    }

    /**
     * An override is a file path, or a command with arguments (`zig c++`).
     * Its first word must be a file or on PATH; otherwise null.
     */
    private fun resolveOverride(override: String): List<String>? {
        val asFile = File(override)
        if (asFile.isFile) {
            return listOf(asFile.absolutePath)
        }
        val words = override.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val head = File(words[0])
        val resolvedHead = if (head.isFile) head.absolutePath else findOnPath(words[0])?.absolutePath ?: return null
        return listOf(resolvedHead) + words.drop(1)
    }

    /** Search PATH for [name]; on Windows also try the PATHEXT spellings. */
    fun findOnPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val found = findInDir(File(dir), name)
            if (found != null) return found
        }
        return null
    }

    fun findInDir(dir: File, name: String): File? {
        val extensions = if (isWindows) {
            listOf("") + (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD")
                .split(';')
                .filter { it.isNotBlank() }
                .map { it.lowercase() }
        } else {
            listOf("")
        }
        for (ext in extensions) {
            val candidate = File(dir, name + ext)
            if (candidate.isFile && (isWindows || candidate.canExecute())) {
                return candidate
            }
        }
        return null
    }

    data class QuickResult(val exitCode: Int, val stdout: String, val stderr: String)

    private const val PROBE_TIMEOUT_SECONDS = 30L

    /**
     * Run a short probe; null when the process cannot be launched. Both
     * streams are drained on their own threads ([CppCompileSupport.await]),
     * so a tool that hangs is killed after [PROBE_TIMEOUT_SECONDS] and
     * reported with exit -1 instead of blocking the test JVM.
     */
    private fun runQuick(command: List<String>): QuickResult? {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            return null
        }
        val r = CppCompileSupport.await(process, PROBE_TIMEOUT_SECONDS)
        return if (r.timedOut) {
            QuickResult(-1, r.stdout, r.stderr + "\n(timed out after ${PROBE_TIMEOUT_SECONDS}s)")
        } else {
            QuickResult(r.exitCode, r.stdout, r.stderr)
        }
    }
}
