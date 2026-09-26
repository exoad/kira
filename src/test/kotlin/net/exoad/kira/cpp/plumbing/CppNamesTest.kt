package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppNames
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CppNamesTest {
    @Test
    fun keywordsGetATrailingUnderscore() {
        val names = CppNames()
        assertEquals("new_", names.escape("new"))
        assertEquals("delete_", names.escape("delete"))
        assertEquals("class_", names.escape("class"))
        assertEquals("and_", names.escape("and"))
        assertEquals("co_await_", names.escape("co_await"))
        assertEquals("requires_", names.escape("requires"))
    }

    @Test
    fun ordinaryNamesPassThrough() {
        val names = CppNames()
        assertEquals("read", names.escape("read"))
        assertEquals("Reply", names.escape("Reply"))
        assertEquals("KIND_OK", names.escape("KIND_OK"))
        // contextual keywords are legal identifiers and part of the API C++ callers use
        assertEquals("override", names.escape("override"))
        assertEquals("final", names.escape("final"))
    }

    @Test
    fun freshNamesAreLowercaseWithAnUnderscoreAndNeverRepeat() {
        val names = CppNames()
        val produced = listOf(
            names.fresh("t"), names.fresh("t"), names.fresh("t"),
            names.fresh("i_end"), names.fresh("i_end"),
            names.fresh("c_k"), names.fresh("Impl"), names.fresh("impl_"),
        )
        assertEquals(listOf("t0_", "t1_", "t2_", "i_end", "i_end0_", "c_k", "impl0_", "impl_"), produced)
        assertEquals(produced.size, produced.toSet().size)
        produced.forEach {
            assertTrue(CppNames.isSynthesized(it), "$it must be lowercase with an underscore")
            assertTrue(it.contains('_'))
            assertEquals(it.lowercase(), it)
        }
    }

    @Test
    fun reservedNamesAreSkipped() {
        val names = CppNames()
        names.reserve("t0_")
        assertEquals("t1_", names.fresh("t"))
    }

    @Test
    fun synthesizedShapeExcludesEveryPossibleKiraName() {
        // Kira identifiers are camelCase, PascalCase or UPPER_SNAKE: never lowercase with an underscore.
        listOf("read", "fieldInt", "Reply", "KIND_OK", "MAX_SPEED", "x").forEach {
            assertFalse(CppNames.isSynthesized(it), "$it is a possible Kira name")
        }
    }

    @Test
    fun win32AndPosixObjectLikeMacrosAreRecognised() {
        listOf(
            "ERROR", "IN", "OUT", "DELETE", "TRUE", "FALSE", "INFINITE", "IGNORE", "NEAR", "FAR", "CONST", "VOID",
            "CALLBACK", "ABSOLUTE", "RELATIVE", "TRANSPARENT", "OPAQUE", "small", "NO_ERROR",
            "ERROR_SUCCESS", "ERROR_FILE_NOT_FOUND", "STATUS_PENDING", "WM_PAINT", "VK_ESCAPE", "EOF", "NULL",
            "EINVAL", "SIGINT", "O_RDONLY", "AF_INET", "SOCK_STREAM", "INT_MAX", "SEEK_SET", "stdin", "errno",
            "MAX_PATH", "INVALID_HANDLE_VALUE", "CreateFile", "interface", "IMAGE_DOS_SIGNATURE",
        ).forEach { assertTrue(CppNames.isObjectLikeMacro(it), "$it is an object-like macro") }
    }

    @Test
    fun ordinaryPublicNamesAreNotMacros() {
        listOf("read", "Reply", "Kind", "KIND_OK", "MAX_SPEED", "PRIORITY", "SIGNAL", "KEY_UP", "ERROR_", "Level")
            .forEach { assertFalse(CppNames.isObjectLikeMacro(it), "$it must not be flagged") }
    }

    @Test
    fun functionLikeMacrosAndCOnlyNamesAreNotFlagged() {
        // D35: the header guard handles min/max; a function-like macro only fires on `name(`;
        // kira/math.kira declares `pub fx min` and `max`, so flagging them would warn on every build.
        listOf(
            "min", "max", "Yield", "GetCurrentTime", "GetFreeSpace", "UNREFERENCED_PARAMETER",
            "IsMaximized", "IsMinimized", "IsRestored", "IMAGE_FIRST_SECTION", "IMAGE_SNAP_BY_ORDINAL",
            // C-only headers: <complex.h> and <stdnoreturn.h> define nothing of these in C++
            "I", "complex", "imaginary", "noreturn",
        ).forEach { assertFalse(CppNames.isObjectLikeMacro(it), "$it is not an object-like macro in C++") }
        assertFalse("min" in CppNames.OBJECT_LIKE_MACROS)
        assertFalse("max" in CppNames.OBJECT_LIKE_MACROS)
    }

    @Test
    fun cLibraryGlobalsCoverC17AndWhatTheRuntimesHeadersAddOnEveryToolchain() {
        // C17 <math.h>: every function in its double, float and long double forms.
        val math = listOf(
            "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "cbrt", "ceil", "copysign", "cos", "cosh",
            "erf", "erfc", "exp", "exp2", "expm1", "fabs", "fdim", "floor", "fma", "fmax", "fmin", "fmod", "frexp",
            "hypot", "ilogb", "ldexp", "lgamma", "llrint", "llround", "log", "log10", "log1p", "log2", "logb", "lrint",
            "lround", "modf", "nan", "nearbyint", "nextafter", "nexttoward", "pow", "remainder", "remquo", "rint",
            "round", "scalbln", "scalbn", "sin", "sinh", "sqrt", "tan", "tanh", "tgamma", "trunc",
        )
        math.flatMap { listOf(it, it + "f", it + "l") }.forEach { assertTrue(CppNames.isCGlobal(it), "$it is a C17 <math.h> function") }
        // The rest of C17, a sample per header, and C11 <threads.h>.
        listOf(
            "main", "abort", "qsort", "strtoull", "fopen", "vsnprintf", "memcpy", "strtok", "clock_gettime", "timespec",
            "size_t", "int64_t", "imaxdiv", "signal", "setjmp", "va_list", "setlocale", "fesetround", "isalnum", "wcslen",
            "mbrtoc32", "thrd_create", "mtx_lock", "cnd_wait", "tss_get", "call_once",
        ).forEach { assertTrue(CppNames.isCGlobal(it), "$it is a C17 global") }
        // glibc's extras under _GNU_SOURCE, and what libstdc++ and libc++ pull in through <thread>
        // (<pthread.h>, <sched.h>), <cstdlib> and <cstring>: measured by kira/cpp/tools/c_global_names.py.
        listOf(
            "clone", "unshare", "sched_yield", "pthread_create", "pthread_mutex_lock", "qsort_r", "reallocarray",
            "mkostemp", "getpt", "strfromd", "strcasestr", "memalign", "sincosf", "exp10l",
        ).forEach { assertTrue(CppNames.isCGlobal(it), "$it is declared at global scope by a header the runtime includes") }
        // and a namespace that meets none of them stays as it is
        listOf("proto", "bibowire", "odom", "course", "text", "scan", "chain", "plant", "band", "viewer", "Reply")
            .forEach { assertFalse(CppNames.isCGlobal(it), "$it is no C library global") }
        assertEquals("time_", CppNames.escapeNamespaceSegment("time"))
        assertEquals("time", CppNames.escapeNestedSegment("time"))
        assertEquals("new_", CppNames.escapeNestedSegment("new"))
        assertEquals("linux_", CppNames.escapeNestedSegment("linux"))
        // A Kira identifier starts with a letter, so the list never needs a reserved `_name`.
        assertTrue(CppNames.C_GLOBAL_NAMES.none { it.startsWith("_") }, "the list holds only names a Kira module can have")
        assertTrue(CppNames.C_GLOBAL_NAMES.size > 2000, "the measured list has ${CppNames.C_GLOBAL_NAMES.size} names")
    }
}
