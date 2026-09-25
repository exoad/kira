#!/usr/bin/env python3
"""Measures which global names a generated `namespace X {` cannot take, and writes the list
CppNames.isCGlobal reads: src/main/resources/net/exoad/kira/cpp/c-global-names.txt.

A module's namespace is the last segment of its URI (design 4.3). `namespace time {` beside
<ctime>'s ::time, or `namespace clone {` beside what glibc's <sched.h> adds under _GNU_SOURCE,
is "redeclared as a different kind of symbol" on gcc, clang and MSVC alike, so such a segment
gets a trailing underscore (`time_`). The list is measured, not remembered: for each toolchain
this script preprocesses a TU that includes kira/rt.hxx and the C++ wrappers of every C17
header, harvests every identifier those headers mention, and compiles
`namespace <id> { inline int probe_ = 0; }` and `inline int <id> = 0;` for all of them; the
lines the compiler rejects are the names (two probes because clang, unlike gcc, lets a
namespace stand beside an overloaded function; see `colliding`). Names beginning with `_` are
dropped (a Kira identifier starts with a letter), and C17's own functions and types are added
by name so the list covers the standard whether or not a toolchain here declared each one.

Toolchains, whichever are found: zig c++ for x86_64-linux-gnu, aarch64-linux-gnu (glibc 2.35,
the Orange Pi's) and x86_64-linux-musl; g++ (MinGW on Windows); MSVC through
kira/cpp/tests/msvc.bat. Run it from the repository root after a change to the runtime's
includes, and commit the list with the change:

    python kira/cpp/tools/c_global_names.py            # rewrites the list
    python kira/cpp/tools/c_global_names.py --check    # exit 1 when the list would change
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
INC = ROOT / "kira" / "cpp"
OUT = ROOT / "src" / "main" / "resources" / "net" / "exoad" / "kira" / "cpp" / "c-global-names.txt"
MSVC_BAT = ROOT / "kira" / "cpp" / "tests" / "msvc.bat"

C_HEADERS = [
    "cassert", "cctype", "cerrno", "cfenv", "cfloat", "cinttypes", "climits", "clocale", "cmath", "csetjmp",
    "csignal", "cstdarg", "cstddef", "cstdint", "cstdio", "cstdlib", "cstring", "ctime", "cuchar", "cwchar", "cwctype",
]
PRELUDE = '#include "kira/rt.hxx"\n' + "".join(f"#include <{h}>\n" for h in C_HEADERS)

KEYWORDS = set("""alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t
char32_t class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield
decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if
inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public
register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch
template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile
wchar_t while xor xor_eq""".split())

# C17's functions, types and the program's entry point, by header. <math.h>'s functions come in
# double, float and long double forms; <threads.h> is C11's optional threads.
C17_MATH = """acos acosh asin asinh atan atan2 atanh cbrt ceil copysign cos cosh erf erfc exp exp2 expm1 fabs fdim
floor fma fmax fmin fmod frexp hypot ilogb ldexp lgamma llrint llround log log10 log1p log2 logb lrint lround modf
nan nearbyint nextafter nexttoward pow remainder remquo rint round scalbln scalbn sin sinh sqrt tan tanh tgamma
trunc""".split()
C17_NAMES = set("""
main
abort abs aligned_alloc at_quick_exit atexit atof atoi atol atoll bsearch calloc div exit free getenv labs ldiv
llabs lldiv malloc mblen mbstowcs mbtowc qsort quick_exit rand realloc srand strtod strtof strtol strtold strtoll
strtoul strtoull system wcstombs wctomb div_t ldiv_t lldiv_t
clearerr fclose feof ferror fflush fgetc fgetpos fgets fopen fprintf fputc fputs fread freopen fscanf fseek
fsetpos ftell fwrite getc getchar perror printf putc putchar puts remove rename rewind scanf setbuf setvbuf
snprintf sprintf sscanf tmpfile tmpnam ungetc vfprintf vfscanf vprintf vscanf vsnprintf vsprintf vsscanf FILE
fpos_t
memchr memcmp memcpy memmove memset strcat strchr strcmp strcoll strcpy strcspn strerror strlen strncat strncmp
strncpy strpbrk strrchr strspn strstr strtok strxfrm
fpclassify isfinite isgreater isgreaterequal isinf isless islessequal islessgreater isnan isnormal isunordered
signbit float_t double_t
asctime clock ctime difftime gmtime localtime mktime strftime time timespec_get clock_t time_t tm timespec
int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t int_least8_t int_least16_t int_least32_t
int_least64_t uint_least8_t uint_least16_t uint_least32_t uint_least64_t int_fast8_t int_fast16_t int_fast32_t
int_fast64_t uint_fast8_t uint_fast16_t uint_fast32_t uint_fast64_t intptr_t uintptr_t intmax_t uintmax_t size_t
ptrdiff_t max_align_t imaxabs imaxdiv imaxdiv_t strtoimax strtoumax wcstoimax wcstoumax
signal raise sig_atomic_t longjmp setjmp jmp_buf va_list setlocale localeconv lconv feclearexcept fegetenv
fegetexceptflag fegetround feholdexcept feraiseexcept fesetenv fesetexceptflag fesetround fetestexcept feupdateenv
fenv_t fexcept_t
isalnum isalpha isblank iscntrl isdigit isgraph islower isprint ispunct isspace isupper isxdigit tolower toupper
wint_t mbstate_t btowc wctob mbrlen mbrtowc wcrtomb mbsrtowcs wcsrtombs mbsinit wcslen wcscpy wcsncpy wcscat
wcsncat wcscmp wcsncmp wcscoll wcsxfrm wcschr wcsrchr wcsstr wcstok wcspbrk wcsspn wcscspn wmemchr wmemcmp wmemcpy
wmemmove wmemset wcstod wcstof wcstold wcstol wcstoll wcstoul wcstoull fgetwc fgetws fputwc fputws fwide fwprintf
fwscanf getwc getwchar putwc putwchar swprintf swscanf ungetwc vfwprintf vfwscanf vswprintf vswscanf vwprintf
vwscanf wprintf wscanf wcsftime wctype_t wctrans_t iswalnum iswalpha iswblank iswcntrl iswdigit iswgraph iswlower
iswprint iswpunct iswspace iswupper iswxdigit iswctype towlower towupper towctrans wctrans wctype mbrtoc16 c16rtomb
mbrtoc32 c32rtomb
thrd_t thrd_start_t thrd_create thrd_equal thrd_current thrd_sleep thrd_yield thrd_exit thrd_detach thrd_join
mtx_t mtx_init mtx_lock mtx_timedlock mtx_trylock mtx_unlock mtx_destroy cnd_t cnd_init cnd_signal cnd_broadcast
cnd_wait cnd_timedwait cnd_destroy tss_t tss_dtor_t tss_create tss_delete tss_get tss_set once_flag call_once
""".split()) | {f + s for f in C17_MATH for s in ("", "f", "l")}

IDENT = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
GNU_LINE = re.compile(r"probe\.cxx:(\d+):\d+: (?:error|fatal error)")
MSVC_LINE = re.compile(r"probe\.cxx\((\d+)\): (?:error|fatal error)")


def run(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, errors="replace")


class Gnu:
    """A gcc- or clang-style command line: `<cmd> -E` preprocesses, `-c` compiles."""

    def __init__(self, name, cmd):
        self.name = name
        self.cmd = cmd

    def preprocess(self, tu):
        r = run(self.cmd + ["-E", str(tu)])
        if r.returncode != 0:
            raise RuntimeError(f"{self.name}: preprocessing failed:\n{r.stderr[:2000]}")
        return r.stdout

    def compile(self, tu, work):
        r = run(self.cmd + ["-c", str(tu), "-o", str(work / "probe.o")])
        return r.returncode == 0, {int(m.group(1)) for m in GNU_LINE.finditer(r.stderr)}


class Msvc:
    name = "msvc"

    def _cl(self, work, extra, tu):
        rsp = work / "cl.rsp"
        rsp.write_text("".join(f"{x}\n" for x in extra) + f'"{tu}"\n')
        r = run(["cmd", "/c", str(MSVC_BAT), "cl", f"@{rsp}"], cwd=str(work))
        return r.returncode, r.stdout + r.stderr

    def preprocess(self, tu):
        code, out = self._cl(tu.parent, ["/E"], tu)
        if code != 0:
            raise RuntimeError(f"msvc: preprocessing failed:\n{out[-2000:]}")
        return out

    def compile(self, tu, work):
        # /wd4459: a probe variable named `n` or `i` at global scope makes the runtime's own
        # template locals "hide a global declaration" under /W4 /WX, which is not a collision.
        code, out = self._cl(work, ["/c", "/wd4459", f'/Fo"{work}\\\\"'], tu)
        return code == 0, {int(m.group(1)) for m in MSVC_LINE.finditer(out)}


def toolchains(work):
    found = []
    zig = shutil.which("zig")
    if zig:
        os.environ.setdefault("ZIG_LOCAL_CACHE_DIR", str(work / "zig-cache"))
        os.environ.setdefault("ZIG_GLOBAL_CACHE_DIR", str(work / "zig-cache"))
        for target in ("x86_64-linux-gnu.2.35", "aarch64-linux-gnu.2.35", "x86_64-linux-musl"):
            found.append(Gnu(f"zig {target}", [zig, "c++", "-target", target, "-std=c++20", "-ferror-limit=0", "-I", str(INC)]))
    gxx = shutil.which("g++")
    if gxx:
        found.append(Gnu("g++", [gxx, "-std=gnu++20", "-fmax-errors=0", "-I", str(INC)]))
    if os.name == "nt" and MSVC_BAT.exists() and pathlib.Path("C:/Users/error/Code/bibo-kira/tools/find_vs.bat").exists():
        found.append(Msvc())
    return found


def identifiers(preprocessed):
    ids = set()
    for line in preprocessed.splitlines():
        if line.startswith("#"):
            continue
        for m in IDENT.finditer(line):
            s = m.group(0)
            if not s.startswith("_") and s not in KEYWORDS:
                ids.add(s)
    return ids


def rejected(tool, names, work, line):
    """The names among [names] for which the toolchain rejects [line] (with `{n}` the name),
    one per line after the prelude. MSVC stops after 100 errors, so rounds repeat with the
    found names removed until the TU compiles."""
    names = sorted(names)
    head = PRELUDE.count("\n")
    failing = set()
    for _ in range(1000):
        remaining = [n for n in names if n not in failing]
        tu = work / "probe.cxx"
        tu.write_text(PRELUDE + "".join(line.format(n=n) + "\n" for n in remaining))
        ok, lines = tool.compile(tu, work)
        if ok:
            return failing
        new = {remaining[ln - head - 1] for ln in lines if 0 <= ln - head - 1 < len(remaining)}
        if not new:
            raise RuntimeError(f"{tool.name}: the probe fails for a reason other than a name; see {tu}")
        failing |= new
    raise RuntimeError(f"{tool.name}: too many rounds")


def colliding(tool, names, work):
    """The names among [names] a global `namespace` of that name cannot take on the toolchain,
    as gcc reads the rule. clang lets `namespace log {` stand beside an overloaded function
    `log` (libc++ overloads every <math.h> function, glibc declares `basename` twice under
    _GNU_SOURCE); gcc rejects it. A variable of the name conflicts with an overload set on
    both, so the union of the two probes is taken, less the names a namespace alias accepts
    (a real namespace such as `std`, which a variable conflicts with but a module may not
    reopen anyway; that is not this list's concern)."""
    namespaces = rejected(tool, names, work, "namespace {n} {{ inline int probe_ = 0; }}")
    variables = rejected(tool, names, work, "inline int {n} = 0;")
    unresolved = variables - namespaces
    real_namespaces = unresolved - rejected(tool, unresolved, work, "namespace probe_{n}_ = {n};") if unresolved else set()
    return (namespaces | variables) - real_namespaces


def measure():
    names = set(C17_NAMES)
    used = []
    with tempfile.TemporaryDirectory(prefix="kira-c-global-") as tmp:
        work = pathlib.Path(tmp)
        tools = toolchains(work)
        if not tools:
            raise RuntimeError("no toolchain found: needs zig, g++ or MSVC")
        for tool in tools:
            sub = work / re.sub(r"[^A-Za-z0-9]+", "-", tool.name)
            sub.mkdir()
            tu = sub / "probe.cxx"
            tu.write_text(PRELUDE)
            ids = identifiers(tool.preprocess(tu))
            found = colliding(tool, ids, sub)
            print(f"{tool.name}: {len(ids)} identifiers, {len(found)} collide", file=sys.stderr)
            names |= found
            used.append(tool.name)
    return names, used


def render(names, used):
    lines = [
        "# Global names a generated namespace cannot take: CppNames.isCGlobal reads this file.",
        "# Measured by kira/cpp/tools/c_global_names.py (see its comment) over kira/rt.hxx and the",
        "# C++ wrappers of every C17 header, plus C17's own functions and types by name.",
        "# Toolchains: " + "; ".join(used) + ".",
        "# Regenerate after a change to the runtime's includes; do not edit by hand.",
    ]
    return "\n".join(lines + sorted(names)) + "\n"


def main():
    names, used = measure()
    text = render(names, used)
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        strip = lambda t: [l for l in t.splitlines() if not l.startswith("#")]
        if strip(current) != strip(text):
            print(f"{OUT} is out of date: rerun without --check", file=sys.stderr)
            sys.exit(1)
        print(f"{OUT} is current ({len(names)} names)")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, newline="\n")
    print(f"wrote {OUT}: {len(names)} names")


if __name__ == "__main__":
    main()
