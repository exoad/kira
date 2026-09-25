package net.exoad.kira.compiler.backend.codegen.cpp

/**
 * How Kira names become C++ names, and where synthesized names come from.
 *
 * - A Kira name that is a C++ keyword gets a trailing underscore: `new` is
 *   `new_`.
 * - A synthesized name is lowercase and contains an underscore (`t0_`,
 *   `i_end`, `c_k`, `impl_`), which no Kira name can be, so it never
 *   shadows one. One instance serves one module; [fresh] never repeats.
 * - [isObjectLikeMacro] answers D35: a `pub` name that `<windows.h>` or a
 *   common POSIX header defines as an object-like macro cannot be spelled by
 *   a C++ caller that includes those headers, so the emitter warns
 *   (`cpp.macro-name`). Function-like macros (`min`, `max`, `Yield()`,
 *   `UNREFERENCED_PARAMETER(p)`) are not in the list: a function-like macro
 *   only fires on `name(`, and the header guard (`kira/macro_push.hxx`)
 *   handles `min`/`max` and private names, as D35 says. Names that only C
 *   headers define (`complex`, `noreturn`, `I`) are not in it either.
 */
class CppNames {
    private val used = HashSet<String>()
    private val counters = HashMap<String, Int>()

    /** Marks [name] as taken so [fresh] never returns it. */
    fun reserve(name: String) {
        used.add(name)
    }

    /** `new` becomes `new_`; anything else is returned as it is. */
    fun escape(name: String): String = escapeKeyword(name)

    /**
     * A new synthesized name for [stem]: `fresh("t")` gives `t0_`, `t1_`,
     * ...; `fresh("i_end")` gives `i_end` first and then `i_end0_`, `i_end1_`, ...
     * The stem is lowercased; the result always has an underscore.
     */
    fun fresh(stem: String): String {
        val base = stem.lowercase().ifEmpty { "t" }
        var candidate = if (base.contains('_')) base else null
        if (candidate == null || candidate in used) {
            var n = counters[base] ?: 0
            do {
                candidate = "$base${n}_"
                n += 1
            } while (candidate in used)
            counters[base] = n
        }
        check(isSynthesized(candidate)) { "synthesized name '$candidate' must be lowercase with an underscore" }
        used.add(candidate)
        return candidate
    }

    companion object {
        /** True for names only the emitter makes: lowercase, with an underscore, no uppercase letter. */
        fun isSynthesized(name: String): Boolean {
            return name.isNotEmpty() && name.contains('_') && name.none { it.isUpperCase() } &&
                name.all { it == '_' || it.isLetterOrDigit() }
        }

        fun isKeyword(name: String): Boolean = name in KEYWORDS

        fun escapeKeyword(name: String): String = if (isKeyword(name)) "${name}_" else name

        /**
         * A namespace segment derived from a module URI: a keyword, an
         * object-like macro or a name the C library declares at global scope
         * gets the trailing underscore (`new_`, `linux_`, `errno_`, `main_`,
         * `exit_`). `namespace errno {` is legal to the compiler that wrote
         * it and unnameable to every caller that includes `<cerrno>`,
         * `linux` is `1` under `-std=gnu++20`, and `namespace main {` or
         * `namespace exit {` is "redeclared as different kind of entity" the
         * moment `<cstdlib>` (which `kira/core.hxx` includes) or the program's
         * own `int main` sits beside it, so a derived namespace never spells
         * one; a manifest override that does is an error instead.
         */
        fun escapeNamespaceSegment(name: String): String {
            return if (isKeyword(name) || isObjectLikeMacro(name) || isCGlobal(name)) "${name}_" else name
        }

        /**
         * [name] is declared at global scope by the C library headers the
         * runtime includes (`<cstdlib>`, `<cstdio>`, `<cstring>`, `<cmath>`,
         * `<ctime>`, `<cstdint>`, `<csignal>`, `<cctype>`, ... and the POSIX
         * names glibc adds to them under `_GNU_SOURCE`, which g++ defines), or
         * is `main`. A namespace of that name at global scope is an error on
         * gcc, clang and MSVC alike: a name is one kind of entity per scope.
         */
        fun isCGlobal(name: String): Boolean = name in C_GLOBAL_NAMES

        /**
         * D35: [name] is an object-like macro in `<windows.h>` (and what it
         * pulls in), in a common C or POSIX header, or a macro the compiler or
         * every Windows build predefines (`_WIN32`, `unix`, `STRICT`), by
         * exact name or by a family such as `ERROR_*` and `STATUS_*`. Never
         * true for a function-like macro: `min`, `max`, `Yield`,
         * `UNREFERENCED_PARAMETER` and `IMAGE_FIRST_SECTION` are not flagged.
         */
        fun isObjectLikeMacro(name: String): Boolean {
            if (name in FUNCTION_LIKE_IN_FAMILIES) {
                return false
            }
            if (name in OBJECT_LIKE_MACROS || name in POSIX_MACROS) {
                return true
            }
            return MACRO_FAMILIES.any { prefix -> name.length > prefix.length && name.startsWith(prefix) }
        }

        /** C++20 keywords and alternative tokens. */
        val KEYWORDS: Set<String> = setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break",
            "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept", "const",
            "consteval", "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return", "co_yield",
            "decltype", "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit", "export",
            "extern", "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
            "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private",
            "protected", "public", "register", "reinterpret_cast", "requires", "return", "short", "signed",
            "sizeof", "static", "static_assert", "static_cast", "struct", "switch", "template", "this",
            "thread_local", "throw", "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq",
        )

        /**
         * The program's entry point and the C library's global-scope functions and types:
         * C17's, and the POSIX and glibc names its headers add on Linux under `_GNU_SOURCE`
         * (`<stdlib.h>` pulls in `<sys/types.h>`, `<alloca.h>` and `<sys/select.h>`).
         */
        val C_GLOBAL_NAMES: Set<String> = setOf(
            "main",
            // <stdlib.h>
            "abort", "abs", "aligned_alloc", "at_quick_exit", "atexit", "atof", "atoi", "atol", "atoll", "bsearch",
            "calloc", "div", "exit", "free", "getenv", "labs", "ldiv", "llabs", "lldiv", "malloc", "mblen", "mbstowcs",
            "mbtowc", "qsort", "quick_exit", "rand", "realloc", "srand", "strtod", "strtof", "strtol", "strtold",
            "strtoll", "strtoul", "strtoull", "system", "wcstombs", "wctomb", "_Exit", "div_t", "ldiv_t", "lldiv_t",
            "random", "srandom", "initstate", "setstate", "rand_r", "drand48", "erand48", "lrand48", "nrand48",
            "mrand48", "jrand48", "srand48", "seed48", "lcong48", "posix_memalign", "valloc", "setenv", "unsetenv",
            "putenv", "clearenv", "mkstemp", "mkstemps", "mkdtemp", "mktemp", "realpath", "getsubopt", "getloadavg",
            "ecvt", "fcvt", "gcvt", "a64l", "l64a", "strtoq", "strtouq", "ptsname", "grantpt", "unlockpt",
            "posix_openpt", "rpmatch", "on_exit", "secure_getenv", "canonicalize_file_name", "alloca",
            // <stdio.h>
            "clearerr", "fclose", "feof", "ferror", "fflush", "fgetc", "fgetpos", "fgets", "fopen", "fprintf",
            "fputc", "fputs", "fread", "freopen", "fscanf", "fseek", "fsetpos", "ftell", "fwrite", "getc", "getchar",
            "perror", "printf", "putc", "putchar", "puts", "remove", "rename", "rewind", "scanf", "setbuf", "setvbuf",
            "snprintf", "sprintf", "sscanf", "tmpfile", "tmpnam", "ungetc", "vfprintf", "vfscanf", "vprintf",
            "vscanf", "vsnprintf", "vsprintf", "vsscanf", "FILE", "fpos_t", "fileno", "fdopen", "popen", "pclose",
            "getline", "getdelim", "dprintf", "vdprintf", "fmemopen", "open_memstream", "flockfile", "ftrylockfile",
            "funlockfile", "getc_unlocked", "putc_unlocked", "getchar_unlocked", "putchar_unlocked", "tempnam",
            "ctermid", "cuserid", "renameat", "fseeko", "ftello", "asprintf", "vasprintf", "getw", "putw",
            "setbuffer", "setlinebuf", "fcloseall", "fopencookie", "cookie_io_functions_t",
            // <string.h>
            "memchr", "memcmp", "memcpy", "memmove", "memset", "strcat", "strchr", "strcmp", "strcoll", "strcpy",
            "strcspn", "strerror", "strlen", "strncat", "strncmp", "strncpy", "strpbrk", "strrchr", "strspn",
            "strstr", "strtok", "strxfrm", "strdup", "strndup", "strtok_r", "strerror_r", "strsep", "strcasecmp",
            "strncasecmp", "memccpy", "mempcpy", "memmem", "memrchr", "strchrnul", "stpcpy", "stpncpy", "strnlen",
            "strsignal", "strverscmp", "strfry", "memfrob", "basename", "explicit_bzero", "bcopy", "bzero", "bcmp",
            "index", "rindex", "ffs", "ffsl", "ffsll",
            // <math.h>
            "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "cbrt", "ceil", "copysign", "cos", "cosh",
            "erf", "erfc", "exp", "exp2", "expm1", "fabs", "fdim", "floor", "fma", "fmax", "fmin", "fmod", "fpclassify",
            "frexp", "hypot", "ilogb", "isfinite", "isgreater", "isgreaterequal", "isinf", "isless", "islessequal",
            "islessgreater", "isnan", "isnormal", "isunordered", "ldexp", "lgamma", "llrint", "llround", "log",
            "log10", "log1p", "log2", "logb", "lrint", "lround", "modf", "nan", "nanf", "nanl", "nearbyint",
            "nextafter", "nexttoward", "pow", "remainder", "remquo", "rint", "round", "scalbln", "scalbn",
            "signbit", "sin", "sinh", "sqrt", "tan", "tanh", "tgamma", "trunc", "float_t", "double_t",
            "j0", "j1", "jn", "y0", "y1", "yn", "gamma", "lgamma_r", "drem", "significand", "scalb", "finite",
            "isinff", "isnanf", "sincos", "exp10", "pow10",
            // <time.h>
            "asctime", "clock", "ctime", "difftime", "gmtime", "localtime", "mktime", "strftime", "time",
            "timespec_get", "clock_t", "time_t", "tm", "timespec", "itimerspec", "nanosleep", "clock_gettime",
            "clock_settime", "clock_getres", "clock_nanosleep", "clock_getcpuclockid", "timer_create",
            "timer_delete", "timer_settime", "timer_gettime", "timer_getoverrun", "timegm", "timelocal",
            "strptime", "tzset", "tzname", "timezone", "daylight", "getdate", "dysize", "asctime_r", "ctime_r",
            "gmtime_r", "localtime_r", "clockid_t", "timer_t", "sigevent",
            // <stdint.h>, <stddef.h>, <inttypes.h>
            "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "int_least8_t", "int_least16_t", "int_least32_t", "int_least64_t", "uint_least8_t", "uint_least16_t",
            "uint_least32_t", "uint_least64_t", "int_fast8_t", "int_fast16_t", "int_fast32_t", "int_fast64_t",
            "uint_fast8_t", "uint_fast16_t", "uint_fast32_t", "uint_fast64_t", "intptr_t", "uintptr_t",
            "intmax_t", "uintmax_t", "size_t", "ptrdiff_t", "max_align_t", "imaxabs", "imaxdiv", "imaxdiv_t",
            "strtoimax", "strtoumax", "wcstoimax", "wcstoumax",
            // <signal.h>, <setjmp.h>, <stdarg.h>, <errno.h>, <locale.h>, <assert.h>, <fenv.h>
            "signal", "raise", "sig_atomic_t", "sigset_t", "sigaction", "siginfo_t", "kill", "sigemptyset",
            "sigfillset", "sigaddset", "sigdelset", "sigismember", "sigprocmask", "sigpending", "sigsuspend",
            "sigwait", "sigqueue", "psignal", "psiginfo", "longjmp", "setjmp", "jmp_buf", "sigjmp_buf",
            "siglongjmp", "sigsetjmp", "va_list", "setlocale", "localeconv", "lconv", "locale_t", "newlocale",
            "uselocale", "freelocale", "duplocale", "feclearexcept", "fegetenv", "fegetexceptflag", "fegetround",
            "feholdexcept", "feraiseexcept", "fesetenv", "fesetexceptflag", "fesetround", "fetestexcept",
            "feupdateenv", "fenv_t", "fexcept_t",
            // <ctype.h>, <wchar.h>, <wctype.h>, <uchar.h>
            "isalnum", "isalpha", "isblank", "iscntrl", "isdigit", "isgraph", "islower", "isprint", "ispunct",
            "isspace", "isupper", "isxdigit", "tolower", "toupper", "isascii", "toascii", "wint_t", "mbstate_t",
            "btowc", "wctob", "mbrlen", "mbrtowc", "wcrtomb", "mbsrtowcs", "wcsrtombs", "mbsinit", "wcslen",
            "wcscpy", "wcsncpy", "wcscat", "wcsncat", "wcscmp", "wcsncmp", "wcscoll", "wcsxfrm", "wcschr",
            "wcsrchr", "wcsstr", "wcstok", "wcspbrk", "wcsspn", "wcscspn", "wmemchr", "wmemcmp", "wmemcpy",
            "wmemmove", "wmemset", "wcstod", "wcstof", "wcstold", "wcstol", "wcstoll", "wcstoul", "wcstoull",
            "fgetwc", "fgetws", "fputwc", "fputws", "fwide", "fwprintf", "fwscanf", "getwc", "getwchar", "putwc",
            "putwchar", "swprintf", "swscanf", "ungetwc", "vfwprintf", "vfwscanf", "vswprintf", "vswscanf",
            "vwprintf", "vwscanf", "wprintf", "wscanf", "wcsftime", "wctype_t", "wctrans_t", "iswalnum",
            "iswalpha", "iswblank", "iswcntrl", "iswdigit", "iswgraph", "iswlower", "iswprint", "iswpunct",
            "iswspace", "iswupper", "iswxdigit", "iswctype", "towlower", "towupper", "towctrans", "wctrans",
            "wctype", "mbrtoc16", "c16rtomb", "mbrtoc32", "c32rtomb", "mbrtoc8", "c8rtomb",
            // <sys/types.h> and <sys/select.h>, which glibc's <stdlib.h> includes
            "ssize_t", "off_t", "pid_t", "uid_t", "gid_t", "mode_t", "dev_t", "ino_t", "nlink_t", "blkcnt_t",
            "blksize_t", "fsblkcnt_t", "fsfilcnt_t", "id_t", "key_t", "suseconds_t", "useconds_t", "loff_t",
            "caddr_t", "daddr_t", "register_t", "u_char", "u_short", "u_int", "u_long", "quad_t", "u_quad_t",
            "fsid_t", "ulong", "ushort", "uint", "fd_set", "fd_mask", "select", "pselect", "timeval",
            "pthread_t", "pthread_attr_t", "pthread_mutex_t", "pthread_cond_t", "pthread_key_t", "pthread_once_t",
            "pthread_rwlock_t", "pthread_spinlock_t", "pthread_barrier_t",
        )

        /** Families `<windows.h>` and friends define wholesale; a name `PREFIX...` is taken. */
        val MACRO_FAMILIES: List<String> = listOf(
            "ERROR_", "STATUS_", "WM_", "VK_", "WSAE", "FILE_ATTRIBUTE_", "FILE_SHARE_", "FILE_MAP_", "GENERIC_",
            "PAGE_", "MEM_", "HKEY_", "SUBLANG_", "IMAGE_", "EXCEPTION_", "IOCTL_", "LOGON32_", "IPPROTO_",
            "INADDR_", "EAI_", "CLOCK_", "DLL_PROCESS_", "DLL_THREAD_",
        )

        /** The few function-like macros inside a family above (`winnt.h`); a family never claims them. */
        private val FUNCTION_LIKE_IN_FAMILIES: Set<String> = setOf(
            "IMAGE_FIRST_SECTION", "IMAGE_ORDINAL", "IMAGE_ORDINAL32", "IMAGE_ORDINAL64",
            "IMAGE_SNAP_BY_ORDINAL", "IMAGE_SNAP_BY_ORDINAL32", "IMAGE_SNAP_BY_ORDINAL64",
        )

        /** Common POSIX and Winsock object-like macros, by exact name. */
        private val POSIX_MACROS: Set<String> = setOf(
            "SIGINT", "SIGTERM", "SIGKILL", "SIGSEGV", "SIGABRT", "SIGFPE", "SIGILL", "SIGHUP", "SIGPIPE", "SIGALRM",
            "SIGUSR1", "SIGUSR2", "SIGCHLD", "SIGSTOP", "SIGCONT", "SIGQUIT", "SIGBUS", "SIGTRAP", "SIGWINCH",
            "O_RDONLY", "O_WRONLY", "O_RDWR", "O_CREAT", "O_TRUNC", "O_APPEND", "O_NONBLOCK", "O_EXCL", "O_CLOEXEC",
            "O_SYNC", "O_DIRECTORY", "O_NOFOLLOW", "O_BINARY", "O_TEXT",
            "S_IRUSR", "S_IWUSR", "S_IXUSR", "S_IRWXU", "S_IRGRP", "S_IWGRP", "S_IXGRP", "S_IRWXG", "S_IROTH",
            "S_IWOTH", "S_IXOTH", "S_IRWXO", "S_IFMT", "S_IFDIR", "S_IFREG", "S_IFLNK", "S_IFCHR", "S_IFBLK",
            "S_IFIFO", "S_IFSOCK", "S_ISUID", "S_ISGID", "S_ISVTX",
            "POLLIN", "POLLOUT", "POLLERR", "POLLHUP", "POLLNVAL", "POLLPRI", "POLLRDNORM", "POLLWRNORM",
            "AF_INET", "AF_INET6", "AF_UNIX", "AF_UNSPEC", "AF_LOCAL", "PF_INET", "PF_INET6", "PF_UNIX", "PF_UNSPEC",
            "SOCK_STREAM", "SOCK_DGRAM", "SOCK_RAW", "SOCK_SEQPACKET", "SOCK_NONBLOCK", "SOCK_CLOEXEC",
            "SOL_SOCKET", "SO_REUSEADDR", "SO_REUSEPORT", "SO_KEEPALIVE", "SO_RCVBUF", "SO_SNDBUF", "SO_BROADCAST",
            "SO_RCVTIMEO", "SO_SNDTIMEO", "SO_ERROR", "SO_LINGER", "SO_TYPE", "TCP_NODELAY", "TCP_KEEPIDLE",
            "MSG_PEEK", "MSG_DONTWAIT", "MSG_NOSIGNAL", "MSG_WAITALL", "MSG_OOB", "MSG_TRUNC",
            "AI_PASSIVE", "AI_CANONNAME", "AI_NUMERICHOST", "AI_NUMERICSERV", "NI_NUMERICHOST", "NI_NUMERICSERV",
            "PRId64", "PRIu64", "PRIx64", "PRId32", "PRIu32", "PRIx32", "PRIdPTR", "PRIuPTR", "PRIxPTR",
            "SCNd64", "SCNu64", "SCNd32", "SCNu32",
            "CREATE_NEW", "CREATE_ALWAYS", "OPEN_EXISTING", "OPEN_ALWAYS", "TRUNCATE_EXISTING",
            "PROCESS_ALL_ACCESS", "THREAD_ALL_ACCESS", "EVENT_ALL_ACCESS", "MUTEX_ALL_ACCESS",
            "STD_INPUT_HANDLE", "STD_OUTPUT_HANDLE", "STD_ERROR_HANDLE", "KEY_READ", "KEY_WRITE", "KEY_ALL_ACCESS",
            "REG_SZ", "REG_DWORD", "REG_BINARY", "REG_QWORD", "CP_UTF8", "CP_ACP", "MB_OK", "MB_ICONERROR",
            "MB_ICONWARNING", "MB_YESNO", "SW_SHOW", "SW_HIDE", "SW_SHOWNORMAL", "PIPE_ACCESS_DUPLEX",
            "PIPE_TYPE_BYTE", "PIPE_READMODE_BYTE", "PIPE_WAIT", "SERVICE_WIN32", "LANG_NEUTRAL", "TOKEN_QUERY",
            "TOKEN_ADJUST_PRIVILEGES", "SEC_COMMIT", "SEC_RESERVE", "WSA_INVALID_EVENT", "WSA_WAIT_TIMEOUT",
            "WSA_WAIT_FAILED", "WSA_INFINITE", "WSADESCRIPTION_LEN", "WSASYS_STATUS_LEN",
        )

        /**
         * Object-like macros by exact name. `min` and `max` are function-like
         * (`#define min(a,b) ...`) and the header guard handles them (D35);
         * `UNREFERENCED_PARAMETER(P)`, `Yield()`, `GetCurrentTime()`,
         * `GetFreeSpace(w)` and windowsx.h's `IsMaximized(hwnd)` family are
         * function-like too, so none of them is here.
         */
        val OBJECT_LIKE_MACROS: Set<String> = setOf(
            // windows.h, windef.h, winnt.h, minwindef.h
            "ERROR", "IN", "OUT", "OPTIONAL", "DELETE", "TRUE", "FALSE", "INFINITE", "IGNORE", "NEAR", "FAR",
            "CONST", "VOID", "CALLBACK", "ABSOLUTE", "RELATIVE", "TRANSPARENT", "OPAQUE", "small",
            "NO_ERROR", "WINAPI", "APIENTRY", "PASCAL", "CDECL", "STDCALL", "FASTCALL", "WINAPIV", "APIPRIVATE",
            "THIS", "PURE", "interface", "far", "near", "pascal", "cdecl", "hyper", "MAX_PATH", "INVALID_HANDLE_VALUE",
            "INVALID_FILE_SIZE", "INVALID_SET_FILE_POINTER", "INVALID_FILE_ATTRIBUTES", "NULL", "ANYSIZE_ARRAY",
            "DUMMYUNIONNAME", "DUMMYSTRUCTNAME", "DECLSPEC_NORETURN", "FORCEINLINE",
            "MAXBYTE", "MAXWORD", "MAXDWORD", "MAXCHAR", "MAXSHORT", "MAXLONG", "MAXLONGLONG", "MINCHAR", "MINSHORT",
            "MINLONG", "MINLONGLONG", "MAXUINT_PTR", "MAXINT_PTR", "MAXULONG_PTR", "MAXLONG_PTR", "MAXHALF_PTR",
            "MINHALF_PTR", "MAXUHALF_PTR", "WAIT_OBJECT_0", "WAIT_TIMEOUT", "WAIT_FAILED", "WAIT_ABANDONED",
            "SYNCHRONIZE", "STANDARD_RIGHTS_ALL", "STANDARD_RIGHTS_REQUIRED", "SPECIFIC_RIGHTS_ALL",
            "ACCESS_SYSTEM_SECURITY", "MAXIMUM_ALLOWED", "READ_CONTROL", "WRITE_DAC", "WRITE_OWNER", "S_OK",
            "S_FALSE", "E_FAIL", "E_ABORT", "E_ACCESSDENIED", "E_HANDLE", "E_INVALIDARG", "E_NOINTERFACE",
            "E_NOTIMPL", "E_OUTOFMEMORY", "E_PENDING", "E_POINTER", "E_UNEXPECTED", "NOERROR", "SEVERITY_SUCCESS",
            "SEVERITY_ERROR", "FACILITY_WIN32", "FACILITY_NULL",
            "SOCKET_ERROR", "INVALID_SOCKET", "SOMAXCONN", "FIONREAD", "FIONBIO", "FIOASYNC", "SD_RECEIVE",
            "SD_SEND", "SD_BOTH", "SHUT_RD", "SHUT_WR", "SHUT_RDWR", "INADDR_ANY", "INADDR_NONE",
            "INADDR_LOOPBACK", "INADDR_BROADCAST", "INET_ADDRSTRLEN", "INET6_ADDRSTRLEN", "STRICT",
            "UNICODE", "_UNICODE",
            // the A/W function aliases: `#define CreateFile CreateFileW` is object-like
            "GetObject", "CreateFile", "DeleteFile", "MoveFile", "CopyFile", "GetMessage",
            "SendMessage", "PostMessage", "CreateWindow", "GetUserName", "GetComputerName",
            "GetTempPath", "GetEnvironmentVariable", "SetEnvironmentVariable", "OutputDebugString",
            "LoadLibrary", "GetModuleHandle", "GetCommandLine", "FindFirstFile", "FindNextFile",
            "CreateMutex", "CreateEvent", "OpenEvent", "CreateSemaphore", "ReportEvent",
            "GetClassName", "GetProp", "SetProp", "RemoveProp", "DrawText", "PlaySound",
            "CreateService", "OpenService", "StartService", "DefWindowProc", "DispatchMessage", "PeekMessage",
            // C and POSIX headers (C++ includes them through <cstdio>, <cerrno>, <climits>, <cmath>, ...)
            "EOF", "BUFSIZ", "FILENAME_MAX", "FOPEN_MAX", "TMP_MAX", "L_tmpnam", "stdin", "stdout", "stderr",
            "errno", "EXIT_SUCCESS", "EXIT_FAILURE", "RAND_MAX", "MB_CUR_MAX", "CHAR_BIT", "CHAR_MAX", "CHAR_MIN",
            "SCHAR_MAX", "SCHAR_MIN", "UCHAR_MAX", "SHRT_MAX", "SHRT_MIN", "USHRT_MAX", "INT_MAX", "INT_MIN",
            "UINT_MAX", "LONG_MAX", "LONG_MIN", "ULONG_MAX", "LLONG_MAX", "LLONG_MIN", "ULLONG_MAX",
            "INT8_MAX", "INT8_MIN", "UINT8_MAX", "INT16_MAX", "INT16_MIN", "UINT16_MAX", "INT32_MAX", "INT32_MIN",
            "UINT32_MAX", "INT64_MAX", "INT64_MIN", "UINT64_MAX", "INTMAX_MAX", "INTMAX_MIN", "UINTMAX_MAX",
            "INTPTR_MAX", "INTPTR_MIN", "UINTPTR_MAX", "SIZE_MAX", "PTRDIFF_MAX", "PTRDIFF_MIN", "WCHAR_MAX",
            "WCHAR_MIN", "WINT_MAX", "WINT_MIN", "SIG_ATOMIC_MAX", "SIG_ATOMIC_MIN", "FLT_MAX", "FLT_MIN",
            "FLT_EPSILON", "FLT_DIG", "FLT_RADIX", "DBL_MAX", "DBL_MIN", "DBL_EPSILON", "DBL_DIG", "LDBL_MAX",
            "LDBL_MIN", "LDBL_EPSILON", "HUGE_VAL", "HUGE_VALF", "HUGE_VALL", "INFINITY", "NAN", "M_PI", "M_E",
            "M_SQRT2", "M_LN2", "M_LN10", "M_LOG2E", "M_LOG10E", "M_PI_2", "M_PI_4", "M_1_PI", "M_2_PI",
            "M_2_SQRTPI", "M_SQRT1_2", "FP_NAN", "FP_INFINITE", "FP_ZERO", "FP_SUBNORMAL", "FP_NORMAL",
            "MATH_ERRNO", "MATH_ERREXCEPT", "SEEK_SET", "SEEK_CUR", "SEEK_END", "STDIN_FILENO", "STDOUT_FILENO",
            "STDERR_FILENO", "PATH_MAX", "NAME_MAX", "PIPE_BUF", "LINE_MAX", "ARG_MAX", "CHILD_MAX", "OPEN_MAX",
            "PAGESIZE", "PAGE_SIZE", "HOST_NAME_MAX", "LOGIN_NAME_MAX", "TTY_NAME_MAX", "CLOCKS_PER_SEC",
            "TIME_UTC", "LC_ALL", "LC_COLLATE", "LC_CTYPE", "LC_MONETARY", "LC_NUMERIC", "LC_TIME", "LC_MESSAGES",
            "R_OK", "W_OK", "X_OK", "F_OK", "F_GETFL", "F_SETFL", "F_GETFD", "F_SETFD", "FD_CLOEXEC", "FD_SETSIZE",
            "WNOHANG", "WUNTRACED", "SA_RESTART", "SA_SIGINFO", "SIG_DFL", "SIG_IGN", "SIG_ERR", "NSIG",
            "E2BIG", "EACCES", "EADDRINUSE", "EADDRNOTAVAIL", "EAFNOSUPPORT", "EAGAIN", "EALREADY", "EBADF",
            "EBADMSG", "EBUSY", "ECANCELED", "ECHILD", "ECONNABORTED", "ECONNREFUSED", "ECONNRESET", "EDEADLK",
            "EDESTADDRREQ", "EDOM", "EDQUOT", "EEXIST", "EFAULT", "EFBIG", "EHOSTUNREACH", "EIDRM", "EILSEQ",
            "EINPROGRESS", "EINTR", "EINVAL", "EIO", "EISCONN", "EISDIR", "ELOOP", "EMFILE", "EMLINK", "EMSGSIZE",
            "EMULTIHOP", "ENAMETOOLONG", "ENETDOWN", "ENETRESET", "ENETUNREACH", "ENFILE", "ENOBUFS", "ENODATA",
            "ENODEV", "ENOENT", "ENOEXEC", "ENOLCK", "ENOLINK", "ENOMEM", "ENOMSG", "ENOPROTOOPT", "ENOSPC",
            "ENOSR", "ENOSTR", "ENOSYS", "ENOTCONN", "ENOTDIR", "ENOTEMPTY", "ENOTRECOVERABLE", "ENOTSOCK",
            "ENOTSUP", "ENOTTY", "ENXIO", "EOPNOTSUPP", "EOVERFLOW", "EOWNERDEAD", "EPERM", "EPIPE", "EPROTO",
            "EPROTONOSUPPORT", "EPROTOTYPE", "ERANGE", "EROFS", "ESPIPE", "ESRCH", "ESTALE", "ETIME", "ETIMEDOUT",
            "ETXTBSY", "EWOULDBLOCK", "EXDEV",
            // predefined by the compiler, by windows.h (WIN32, WINVER, STRICT) or by every Windows build
            "unix", "linux", "i386", "__STDC__", "__cplusplus", "NDEBUG", "DEBUG", "_DEBUG",
            "WIN32", "_WIN32", "_WIN64", "WINVER", "_MSC_VER", "__GNUC__", "__clang__", "__linux__", "__APPLE__",
        )
    }
}
