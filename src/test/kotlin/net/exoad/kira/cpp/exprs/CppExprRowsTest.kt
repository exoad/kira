package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One golden snippet per row of design 5.3 (R1-R21), 5.4 (statements), 5.6 (closures,
 * the class rows aside: classes are W2.4's), 5.7 (Maybe) and 5.8 / section 10 (Str, StrBuf):
 * each row is a module whose emitted C++ must hold the lines the design gives, and whose
 * behaviour a driver checks at run time.
 *
 * All rows form one project, emitted by the real backend and compiled with one driver under
 * the warning contract (design 8.2: g++ and clang `-Wall -Wextra -Wconversion
 * -Wsign-conversion -Wshadow -Werror`, MSVC `/W4 /WX`) on gcc, clang, msvc and zig-aarch64
 * (compile only). The driver prints bibo's check format; every check must pass and the count
 * must be the number the rows declare, so a row that stops being checked fails.
 */
class CppExprRowsTest {
    /** A row: its module, the lines its C++ must contain, and C++ statements checking it at run time. */
    data class Row(val id: String, val module: Module, val wants: List<String>, val checks: List<String>)

    private fun row(id: String, body: String, wants: List<String>, checks: List<String>): Row =
        Row(id, Module("rows:$id", body), wants, checks)

    private val shapes = Module(
        "rows:shapes",
        """
        pub struct Pt {
            pub x: Int32 = 0
            pub y: Int32 = 0
        }

        pub enum Dir: Int32 { DIR_N = 0, DIR_S = 1 }

        pub SCALE: Int32 = 3

        pub fx origin: () Pt {
            return Pt { }
        }
        """,
    )

    val rows: List<Row> = listOf(
        row(
            "r1",
            """
            pub fx wrapAdd: (a: UInt8, b: UInt8) UInt8 {
                return a + b
            }

            pub fx low3: (next: UInt8) UInt8 {
                bits: UInt8 = next & 7
                return bits
            }

            pub fx bump: (x: UInt8) UInt8 {
                mut y: UInt8 = x
                y += 1
                return y
            }

            pub fx negate: (a: Int8) Int8 {
                return -a
            }

            pub fx flip: (a: UInt16) UInt16 {
                return ~a
            }
            """,
            listOf(
                "return static_cast<std::uint8_t>(a + b);",
                "const std::uint8_t bits{static_cast<std::uint8_t>(next & 7u)};",
                "std::uint8_t y{x};",
                "y += 1;",
                "return static_cast<std::int8_t>(-a);",
                "return static_cast<std::uint16_t>(~a);",
            ),
            listOf(
                """check(r1::wrapAdd(200, 100) == 44, "R1: UInt8 + wraps: 200 + 100 is 44");""",
                """check(r1::low3(0xFF) == 7, "R1: & on UInt8 is narrowed back");""",
                """check(r1::bump(255) == 0, "R1: a compound += on UInt8 is bare and wraps");""",
                """check(r1::negate(-128) == -128, "R1: -Int8 wraps");""",
                """check(r1::flip(0) == 65535, "R1: ~UInt16 is narrowed back");""",
            ),
        ),
        row(
            "r2",
            """
            pub fx crcInit: () UInt32 {
                mut crc: UInt32 = 0xFFFFFFFF
                crc = crc ^ 1
                return crc
            }

            pub fx trillion: () Int64 {
                return 1000000 * 1000000
            }

            pub fx pick: (c: Bool) Int8 {
                return if c { 1 } else { -1 }
            }

            pub fx lowest: () Int32 {
                return -2147483648
            }

            pub fx perMs: (d: UInt32) UInt32 {
                return 1000000 / d
            }

            pub fx wide: (n: Int64) Int64 {
                return n * 3 + 1
            }
            """,
            listOf(
                "std::uint32_t crc{0xFFFFFFFFu};",
                "crc = crc ^ 1u;",
                "return std::int64_t{1000000} * std::int64_t{1000000};",
                "return c ? std::int8_t{1} : std::int8_t{-1};",
                "return std::numeric_limits<std::int32_t>::min();",
                "return kira::div(std::uint32_t{1000000}, d);",
                "return n * 3 + 1;",
            ),
            listOf(
                """check(r2::crcInit() == 0xFFFFFFFEu, "R2: a UInt32 literal takes u, the radix kept");""",
                """check(r2::trillion() == 1000000000000LL, "R2: a literal-only Int64 product is typed, not computed in Int32");""",
                """check(r2::pick(true) == 1 && r2::pick(false) == -1, "R2: a ternary branch of Int8 is T{lit}");""",
                """check(r2::lowest() == std::numeric_limits<std::int32_t>::min(), "R2: Int32's minimum through numeric_limits");""",
                """check(r2::perMs(1000u) == 1000u, "R2: a literal where a template deduces is T{lit}");""",
                """check(r2::wide(2) == 7, "R2: Int64 literals beside a variable are bare");""",
            ),
        ),
        row(
            "r3",
            """
            pub fx scaled: (v: Float32) Float32 {
                return v * 1000.0 + 0.5
            }

            pub fx positive: (x: Float64) Bool {
                return x > 0
            }

            pub fx half: () Float32 {
                return 0.5
            }
            """,
            listOf("return v * 1000.0f + 0.5f;", "return x > 0.0;", "return 0.5f;"),
            listOf(
                """check(r3::scaled(0.25f) == 250.5f, "R3: literals take v's Float32");""",
                """check(r3::positive(1.0) && !r3::positive(-1.0), "R3: an integer literal in a Float64 context is 0.0");""",
                """check(r3::half() == 0.5f, "R3: a Float32 return literal takes f");""",
            ),
        ),
        Row(
            "r4",
            Module(
                "rows:r4",
                """
                use "rows:shapes"

                pub fx sumOf: (p: Pt) Int32 {
                    return p.x + p.y
                }

                pub fx south: () Dir {
                    return Dir.DIR_S
                }

                pub fx scale: (v: Int32) Int32 {
                    return v * SCALE
                }

                pub fx fromOrigin: () Int32 {
                    return origin().x
                }
                """,
            ),
            listOf(
                "[[nodiscard]] std::int32_t sumOf(const ::shapes::Pt& p);",
                "return p.x + p.y;",
                "return ::shapes::Dir::DIR_S;",
                "return v * ::shapes::SCALE;",
                "return ::shapes::origin().x;",
            ),
            listOf(
                """check(r4::sumOf(shapes::Pt{.x = 2, .y = 3}) == 5, "R4: a struct's field is s.f");""",
                """check(r4::south() == shapes::Dir::DIR_S, "R4: an enum entry is E::F, from another module ::ns::E::F");""",
                """check(r4::scale(2) == 6, "R4: another module's constant is ::ns::NAME");""",
                """check(r4::fromOrigin() == 0, "R4: another module's function is ::ns::f");""",
            ),
        ),
        row(
            "r5",
            """
            pub LIDAR_IP: Str = "192.168.1.62"

            pub fx len: (s: Str) Size {
                return s.length()
            }

            pub fx ipLen: () Size {
                return LIDAR_IP.length()
            }

            pub fx grow: (n: Int32) List<Int32> {
                mut xs: List<Int32> = List<Int32> { }
                xs.add(n)
                xs.add(n + 1)
                return xs
            }

            pub fx count: (v: View<UInt8>) Size {
                return v.size()
            }

            pub fx absOf: (x: Int32) Int32 {
                return x.abs()
            }
            """,
            listOf(
                "return kira::str::length(s);",
                "return kira::str::length(LIDAR_IP);",
                "kira::List<std::int32_t> xs = kira::List<std::int32_t>{};",
                "xs.push_back(n);",
                "xs.push_back(n + 1);",
                "return v.size();",
                "return kira::abs(x);",
            ),
            listOf(
                """check(r5::len("abc") == 3u, "R5: Str.length is kira::str::length");""",
                """check(r5::ipLen() == 12u, "R5: a Str constant receiver of a free-function binding converts implicitly");""",
                """check(r5::grow(4).size() == 2u && r5::grow(4)[1] == 5, "R5: List.add is push_back");""",
                """{ const std::array<std::uint8_t, 3> bytes{1, 2, 3}; check(r5::count(bytes) == 3u, "R5: View.size, a std::array converting to a view"); }""",
                """check(r5::absOf(-5) == 5, "R5: Int32.abs reaches Num.abs through its parent");""",
            ),
        ),
        row(
            "r6",
            """
            pub fx sub: (a: Int32, b: Int32) Int32 {
                return a - b
            }

            pub fx three: (a: Int32, b: Int32 = 2, c: Int32 = 3) Int32 {
                return a * 100 + b * 10 + c
            }

            pub fx named: () Int32 {
                return sub(b = 3, a = 10)
            }

            pub fx skipMiddle: () Int32 {
                return three(1, c = 9)
            }

            pub fx trailing: () Int32 {
                return three(1)
            }
            """,
            listOf(
                "[[nodiscard]] std::int32_t three(std::int32_t a, std::int32_t b = 2, std::int32_t c = 3);",
                "return sub(10, 3);",
                "return three(1, 2, 9);",
                "return three(1);",
            ),
            listOf(
                """check(r6::named() == 7, "R6: named arguments in parameter order");""",
                """check(r6::skipMiddle() == 129, "R6: a skipped middle default is filled in");""",
                """check(r6::trailing() == 123, "R6: trailing defaults are C++ default arguments");""",
            ),
        ),
        row(
            "r7",
            """
            pub @_const fx sq: (x: Int32) Int32 {
                return x * x
            }

            @_static_assert(sq(4) == 16, "sq is constexpr")
            """,
            listOf("[[nodiscard]] constexpr std::int32_t sq(std::int32_t x);", "static_assert(sq(4) == 16, \"sq is constexpr\");"),
            listOf(
                """static_assert(r7::sq(5) == 25, "R7");""",
                """check(r7::sq(3) == 9, "R7: @_const is constexpr, usable in a static_assert");""",
            ),
        ),
        row(
            "r8",
            """
            pub mut calls: Int32 = 0

            pub fx tick: () Int32 {
                calls += 1
                return calls
            }

            pub fx twice: () Int32 {
                tick()
                tick()
                return calls
            }
            """,
            listOf("static_cast<void>(tick());"),
            listOf("""check(r8::twice() == 2, "R8: a discarded non-Void result is static_cast<void>");"""),
        ),
        row(
            "r9",
            """
            pub struct Reply {
                pub kind: Int32 = 0
                pub line: Str = ""
                pub rest: Str = ""
            }

            pub fx make: (s: Str) Reply {
                return Reply { rest = "r", line = s }
            }

            pub fx positional: () Reply {
                return Reply { 1, "a", "b" }
            }

            pub fx cell: () Int32 {
                c: Ref<Int32> = Ref<Int32> { value = 7 }
                return c.value
            }
            """,
            listOf(
                "return Reply{.line = s, .rest = \"r\"};",
                "return Reply{.kind = 1, .line = \"a\", .rest = \"b\"};",
                "const kira::Rc<kira::Box<std::int32_t>> c = std::make_shared<kira::Box<std::int32_t>>(7);",
                "return c->value;",
            ),
            listOf(
                """check(r9::make("x").line == "x" && r9::make("x").rest == "r" && r9::make("x").kind == 0, "R9: designated initializers in declaration order");""",
                """check(r9::positional().kind == 1 && r9::positional().rest == "b", "R9: positional fields in order");""",
                """check(r9::cell() == 7, "R9: Ref<T> is make_shared<kira::Box<T>>");""",
            ),
        ),
        row(
            "r10",
            """
            pub fx scaleBy: (k: Int32) Fx<Tuple1<Int32>, Int32> {
                return fx(x: Int32) Int32 {
                    return x * k
                }
            }

            pub fx applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return f(x)
            }

            pub fx addAll: (xs: List<Int32>, k: Int32) Int32 {
                mut total: Int32 = 0
                for x: Int32 in xs {
                    total += applyTo(fx(v: Int32) Int32 {
                        return v + k
                    }, x)
                }
                return total
            }

            pub struct Gain {
                pub k: Int32 = 3

                pub fx scaler: () Fx<Tuple1<Int32>, Int32> {
                    return fx(x: Int32) Int32 {
                        return x * k
                    }
                }

                pub fx twice: (x: Int32) Int32 {
                    return x * k * 2
                }

                pub fx twicer: () Fx<Tuple1<Int32>, Int32> {
                    return fx(x: Int32) Int32 {
                        return twice(x)
                    }
                }
            }

            pub fx tally: () Fx<Tuple0, Int32> {
                counter: Ref<Int32> = Ref<Int32> { value = 0 }
                return fx() Int32 {
                    counter.value = counter.value + 1
                    return counter.value
                }
            }

            pub fx constant: () Fx<Tuple1<Int32>, Int32> {
                return fx(x: Int32) Int32 {
                    return 5
                }
            }
            """,
            listOf(
                "return [k](std::int32_t x) -> std::int32_t",
                "total += applyTo([k](std::int32_t v) -> std::int32_t",
                "}, x);",
                "return [c_k = k](std::int32_t x) -> std::int32_t",
                "return x * c_k;",
                "return [*this](std::int32_t x) -> std::int32_t",
                "return twice(x);",
                "return [counter]() -> std::int32_t",
                "counter->value = counter->value + 1;",
                "return []([[maybe_unused]] std::int32_t x) -> std::int32_t",
            ),
            listOf(
                """check(r10::scaleBy(3)(4) == 12, "5.6: a local captured by copy, [k]");""",
                """check(r10::addAll(kira::List<std::int32_t>{1, 2, 3}, 10) == 36, "5.6: a lambda passed straight to an Fx parameter");""",
                """check(r10::Gain{}.scaler()(2) == 6, "5.6: a struct field copied under its own name, [c_k = k]");""",
                """check(r10::Gain{}.twicer()(2) == 12, "5.6: a struct method call captures the struct by copy, [*this]");""",
                """{ const kira::Fn<std::int32_t()> tallied = r10::tally(); static_cast<void>(tallied()); check(tallied() == 2, "5.6: a Ref<T> shares its cell, [counter]"); }""",
                """check(r10::constant()(9) == 5, "5.6: an unread lambda parameter is [[maybe_unused]]");""",
            ),
        ),
        row(
            "r11",
            """
            pub fx ratio: (a: Int32, b: Int32) Int32 {
                return a / b
            }

            pub fx rem: (a: Int32, b: Int32) Int32 {
                return a % b
            }

            pub fx tenth: (a: Int32) Int32 {
                return a / 10
            }

            pub fx byMinusOne: (a: Int32) Int32 {
                return a / -1
            }

            pub fx halve: (mut a: Int32, b: Int32) Void {
                a /= b
            }
            """,
            listOf(
                "return kira::div(a, b);",
                "return kira::mod(a, b);",
                "return a / 10;",
                "return kira::div(a, std::int32_t{-1});",
                "a = kira::div(a, b);",
            ),
            listOf(
                """check(r11::ratio(7, 2) == 3, "R11: / by a variable is kira::div");""",
                """check(r11::rem(7, 2) == 1, "R11: % by a variable is kira::mod");""",
                """check(r11::tenth(95) == 9, "R11: / by a nonzero constant is bare");""",
                """check(r11::byMinusOne(5) == -5, "R11: / -1 on a signed type is checked");""",
                """{ std::int32_t h = 9; r11::halve(h, 2); check(h == 4, "R11: /= by a variable is x = kira::div(x, y)"); }""",
            ),
        ),
        row(
            "r12",
            """
            pub fx shift: (v: UInt32, n: UInt32) UInt32 {
                return v << n
            }

            pub fx eighth: (v: UInt32) UInt32 {
                return v >> 3
            }

            pub fx doubled: (v: UInt8) UInt8 {
                return v << 1
            }

            pub fx top: (v: Int32) Int32 {
                return v >>> 28
            }
            """,
            listOf(
                "return kira::shl(v, n);",
                "return v >> 3;",
                "return static_cast<std::uint8_t>(v << 1);",
                "return static_cast<std::int32_t>(static_cast<std::uint32_t>(v) >> 28);",
            ),
            listOf(
                """check(r12::shift(1u, 4u) == 16u, "R12: a shift by a variable is kira::shl");""",
                """check(r12::eighth(64u) == 8u, "R12: a shift by a constant below the width is bare");""",
                """check(r12::doubled(200) == 144, "R12: a UInt8 shift is narrowed back (R1)");""",
                """check(r12::top(-1) == 15, "R12: >>> on a signed type shifts its unsigned counterpart");""",
            ),
        ),
        row(
            "r13",
            """
            pub enum Mood: Int32 { MOOD_CALM = 0, MOOD_BUSY = 1 }

            pub fx toInt: (v: Float64) Int32 {
                return v as Int32
            }

            pub fx narrow: (v: Int64) Int32 {
                return v as Int32
            }

            pub fx widen: (v: Int32) Float64 {
                return v as Float64
            }

            pub fx half: (v: Float64) Float32 {
                return v as Float32
            }

            pub fx code: (c: Char) UInt8 {
                return c as UInt8
            }

            pub fx letter: (n: UInt8) Char {
                return n as Char
            }

            pub fx raw: (m: Mood) Int32 {
                return m as Int32
            }

            pub fx shown: (n: Int32) Str {
                return n as Str
            }
            """,
            listOf(
                "return kira::as<std::int32_t>(v);",
                "return static_cast<std::int32_t>(v);",
                "return static_cast<double>(v);",
                "return static_cast<float>(v);",
                "return static_cast<std::uint8_t>(static_cast<unsigned char>(c));",
                "return static_cast<char>(n);",
                "return static_cast<std::int32_t>(m);",
                "return kira::text(n);",
            ),
            listOf(
                """check(r13::toInt(1e12) == std::numeric_limits<std::int32_t>::max(), "R13: float to int saturates (D9)");""",
                """check(r13::toInt(std::numeric_limits<double>::quiet_NaN()) == 0, "R13: NaN to int is 0 (D9)");""",
                """check(r13::narrow(5000000000LL) == 705032704, "R13: int to int wraps");""",
                """check(r13::widen(3) == 3.0, "R13: int to float");""",
                """check(r13::half(0.5) == 0.5f, "R13: float to float");""",
                """check(r13::code(static_cast<char>(-1)) == 255, "R13: Char to int is the unsigned code unit");""",
                """check(r13::letter(65) == 'A', "R13: int to Char");""",
                """check(r13::raw(r13::Mood::MOOD_BUSY) == 1, "R13: an enum to its base");""",
                """check(r13::shown(-42) == "-42", "R13: as Str is kira::text");""",
            ),
        ),
        row(
            "r14",
            """
            pub TAG: Str = "tag"

            pub fx greet: (name: Str, n: Int32) Str {
                return "hello ${'$'}{name}, you are ${'$'}{n}"
            }

            pub fx joined: (a: Str, b: Str) Str {
                return a + " " + b
            }

            pub fx bracketed: (s: Str) Str {
                return "<" + s + ">"
            }

            pub fx tagged: (s: Str) Str {
                return TAG + ":" + s
            }
            """,
            listOf(
                "return kira::cat(\"hello \", name, \", you are \", n);",
                "return a + \" \" + b;",
                "return kira::Str(\"<\") + s + \">\";",
                "return kira::Str(TAG) + \":\" + s;",
            ),
            listOf(
                """check(r14::greet("kira", 3) == "hello kira, you are 3", "R14: interpolation is kira::cat");""",
                """check(r14::joined("a", "b") == "a b", "R14: Str + Str");""",
                """check(r14::bracketed("x") == "<x>", "R14: a literal on the left of + becomes a kira::Str");""",
                """check(r14::tagged("v") == "tag:v", "R14: so does a Str constant");""",
            ),
        ),
        row(
            "r15",
            """
            pub fx initial: (s: Str) Char {
                return s[0]
            }

            pub fx second: (xs: List<Int32>) Int32 {
                return xs[1]
            }

            pub fx third: (a: Arr<UInt8, 4>) UInt8 {
                return a[2]
            }

            pub fx fourth: (v: View<UInt8>) UInt8 {
                return v[3]
            }

            pub fx setFirst: (mut a: Arr<Int32, 2>, v: Int32) Void {
                a[0] = v
            }
            """,
            listOf(
                "return kira::str::at(s, 0);",
                "return kira::at(xs, 1);",
                "return kira::at(a, 2);",
                "return kira::at(v, 3);",
                "kira::at(a, 0) = v;",
            ),
            listOf(
                """check(r15::initial("kira") == 'k', "R15: s[i] on a Str is kira::str::at");""",
                """check(r15::second(kira::List<std::int32_t>{4, 5}) == 5, "R15: a List index is kira::at");""",
                """{ const std::array<std::uint8_t, 4> quad{1, 2, 3, 4}; check(r15::third(quad) == 3 && r15::fourth(quad) == 4, "R15: Arr<T, N> and View indexes are kira::at"); }""",
                """{ std::array<std::int32_t, 2> pair{0, 0}; r15::setFirst(pair, 8); check(pair[0] == 8, "R15: kira::at is an lvalue on a mutable place"); }""",
            ),
        ),
        row(
            "r16",
            """
            pub struct P2 {
                pub x: Int32 = 0
                pub y: Int32 = 0
            }

            pub enum Light: Int32 { LIGHT_RED = 0, LIGHT_GREEN = 1 }

            pub A: Str = "abc"

            pub fx samePoint: (a: P2, b: P2) Bool {
                return a == b
            }

            pub fx sameText: (a: Str, b: Str) Bool {
                return a == b
            }

            pub fx isGreen: (l: Light) Bool {
                return l == Light.LIGHT_GREEN
            }

            pub fx constEq: () Bool {
                return A == "abc"
            }

            @_static_assert(A == "abc", "A is abc")
            """,
            listOf(
                "bool operator==(const P2&) const = default;",
                "return a == b;",
                "return l == Light::LIGHT_GREEN;",
                "return std::string_view(A) == \"abc\";",
                "static_assert(std::string_view(A) == \"abc\", \"A is abc\");",
            ),
            listOf(
                """check(r16::samePoint(r16::P2{.x = 1, .y = 2}, r16::P2{.x = 1, .y = 2}) && !r16::samePoint(r16::P2{.x = 1, .y = 2}, r16::P2{}), "R16: struct == is memberwise");""",
                """check(r16::sameText("q", std::string("q")) && !r16::sameText("q", "r"), "R16: Str == compares the text");""",
                """check(r16::isGreen(r16::Light::LIGHT_GREEN) && !r16::isGreen(r16::Light::LIGHT_RED), "R16: enum ==");""",
                """check(r16::constEq(), "R16: two const char* compare their text, not their addresses");""",
            ),
        ),
        row(
            "r17",
            """
            pub fx before: (a: Char, b: Char) Bool {
                return a < b
            }

            pub fx same: (a: Char, b: Char) Bool {
                return a == b
            }
            """,
            listOf("return kira::ord(a) < kira::ord(b);", "return a == b;"),
            listOf(
                """check(r17::before('a', 'b'), "R17: Char < compares code units");""",
                """check(r17::before(static_cast<char>(0x7F), static_cast<char>(0x80)), "R17: as unsigned on every target (D3)");""",
                """check(r17::same('x', 'x') && !r17::same('x', 'y'), "R17: Char == is native");""",
            ),
        ),
        row(
            "r18",
            """
            pub fx pick: (neg: Bool, v: Float32) Float32 {
                return if neg { -v } else { v }
            }

            pub fx label: (a: Int32) Str {
                return if a == 0 { "zero" } else if a > 0 { "pos" } else { "neg" }
            }

            pub fx positive: (a: Int32) Maybe<Int32> {
                return if a > 0 { a } else { null }
            }

            pub fx longer: (a: Int32, neg: Bool) Int32 {
                return if neg {
                    t: Int32 = a * 2
                    t + 1
                } else {
                    0
                }
            }
            """,
            listOf(
                "return neg ? -v : v;",
                "return a == 0 ? kira::Str(\"zero\") : a > 0 ? kira::Str(\"pos\") : kira::Str(\"neg\");",
                "return a > 0 ? kira::Maybe<std::int32_t>(a) : kira::Maybe<std::int32_t>(kira::none);",
                "return [&]() -> std::int32_t",
                "const std::int32_t t{a * 2};",
                "return t + 1;",
            ),
            listOf(
                """check(r18::pick(true, 2.0f) == -2.0f && r18::pick(false, 2.0f) == 2.0f, "R18: an if-expression is a ternary");""",
                """check(r18::label(0) == "zero" && r18::label(5) == "pos" && r18::label(-1) == "neg", "R18: else-if nests; Str branches are kira::Str");""",
                """check(kira::unwrap(r18::positive(3)) == 3 && !kira::isSome(r18::positive(-1)), "R18: a coerced branch is T(branch)");""",
                """check(r18::longer(4, true) == 9 && r18::longer(4, false) == 0, "R18: a branch with statements is an IIFE");""",
            ),
        ),
        row(
            "r19",
            """
            mut ticks: Int32 = 0

            fx next: () Int32 {
                ticks += 1
                return ticks
            }

            fx sub: (a: Int32, b: Int32) Int32 {
                return a - b
            }

            pub fx ordered: () Int32 {
                return sub(next(), next())
            }

            pub fx pureArgs: (a: Int32, b: Int32) Int32 {
                return sub(a + 1, b * 2)
            }
            """,
            listOf(
                "const std::int32_t t0_ = next();",
                "const std::int32_t t1_ = next();",
                "return sub(t0_, t1_);",
                "return sub(a + 1, b * 2);",
            ),
            listOf(
                """check(r19::ordered() == -1, "R19: impure arguments are spilled, left to right (D33)");""",
                """check(r19::pureArgs(1, 2) == -2, "R19: pure arguments are not");""",
            ),
        ),
        row(
            "r20",
            """
            pub fx bits: (f: Float32) UInt32 {
                return bitCast<UInt32>(f)
            }
            """,
            listOf("return kira::bitCast<std::uint32_t>(f);"),
            listOf("""check(r20::bits(1.0f) == 0x3F800000u, "R20: bitCast is kira::bitCast");"""),
        ),
        row(
            "r21",
            """
            pub fx show: (x: Int32, b: Bool, f: Float64) Void {
                trace(x)
                trace(b)
                trace(f)
            }
            """,
            listOf("kira::trace(x);", "kira::trace(b);", "kira::trace(f);"),
            listOf("""r21::show(7, true, 0.5);"""),
        ),
        row(
            "s1",
            """
            pub struct Holder {
                pub n: Int32 = 0
            }

            pub fx locals: (v: Int32) Int32 {
                x: Int32 = v
                mut y: Int32 = x
                r: Holder = Holder { n = y }
                y += r.n
                return y
            }
            """,
            listOf("const std::int32_t x{v};", "std::int32_t y{x};", "const Holder r = Holder{.n = y};"),
            listOf("""check(s1::locals(3) == 6, "5.4: scalar locals brace-init, others copy-init, const without mut");"""),
        ),
        row(
            "s2",
            """
            pub LIMIT: Int32 = 4

            pub fx sumTo: (n: Size) Size {
                mut t: Size = 0
                for i: Size in 0..n {
                    t += i
                }
                return t
            }

            pub fx constBound: () Int32 {
                mut t: Int32 = 0
                for i: Int32 in 0..LIMIT {
                    t += i
                }
                for j: Int32 in 0..3 {
                    t += j
                }
                return t
            }

            pub fx each: (xs: List<Int32>, names: List<Str>) Int32 {
                mut t: Int32 = 0
                for x: Int32 in xs {
                    t += x
                }
                for s: Str in names {
                    t += s.length() as Int32
                }
                return t
            }

            pub fx legacy: (n: Int32) Int32 {
                mut t: Int32 = 0
                for mut i: 0..n {
                    t += i
                }
                return t
            }
            """,
            listOf(
                "for(kira::Size i = 0, i_end = n; i < i_end; ++i)",
                "for(std::int32_t i = 0; i < LIMIT; ++i)",
                "for(std::int32_t j = 0; j < 3; ++j)",
                "for(const std::int32_t x : xs)",
                "for(const kira::Str& s : names)",
                "for(std::int32_t i = 0, i_end = n; i <= i_end; ++i)",
            ),
            listOf(
                """check(s2::sumTo(4) == 6u, "5.4: a range's bound is evaluated once, i_end");""",
                """check(s2::constBound() == 9, "5.4: a constant or literal bound needs no temporary");""",
                """check(s2::each(kira::List<std::int32_t>{1, 2}, kira::List<kira::Str>{"ab", "c"}) == 6, "5.4: range-for, by value for a scalar, by const& otherwise");""",
                """check(s2::legacy(3) == 6, "5.4: the legacy loop includes its bound (D17)");""",
            ),
        ),
        row(
            "s3",
            """
            pub enum K2: Int32 { K2_OK = 0, K2_ERR = 1 }

            pub struct Out {
                pub kind: K2 = K2.K2_OK
            }

            pub TAIL: Arr<UInt8, 2> = [0x00, 0xFF]

            pub fx places: () Int32 {
                mut o: Out = Out { }
                o.kind = K2.K2_ERR
                mut p: Arr<UInt8, 4> = [1, 2, 3, 4]
                p[3] = TAIL[1]
                return (p[3] as Int32) + (o.kind as Int32)
            }

            pub fx mapped: () Size {
                mut m: Map<Str, Int32> = Map<Str, Int32> { }
                m["k"] = 30
                m["k"] = 31
                return m.size()
            }
            """,
            listOf(
                "o.kind = K2::K2_ERR;",
                "std::array<std::uint8_t, 4> p = {1, 2, 3, 4};",
                "kira::at(p, 3) = kira::at(TAIL, 1);",
                "m[\"k\"] = 30;",
            ),
            listOf(
                """check(s3::places() == 256, "5.4: field and element assignment");""",
                """check(s3::mapped() == 1u, "5.4: m[k] = v writes a Map");""",
            ),
        ),
        row(
            "s4",
            """
            pub fx control: (n: Int32) Int32 {
                mut i: Int32 = 0
                mut t: Int32 = 0
                while i < n {
                    i += 1
                    if i == 2 {
                        continue
                    }
                    if i > 4 {
                        break
                    }
                    t += i
                }
                do {
                    t -= 1
                } while t > 100
                return t
            }

            pub fx early: (x: Int32) Void {
                if x > 0 {
                    return
                }
                trace(x)
            }
            """,
            listOf("while(i < n)", "continue;", "break;", "do", "while(t > 100);", "return;"),
            listOf(
                """check(s4::control(10) == 7, "5.4: while, do-while, break, continue");""",
                """s4::early(1);""",
            ),
        ),
        row(
            "s5",
            """
            pub fx risky: (ok: Bool) Int32 {
                if !ok {
                    throw "bad"
                }
                return 1
            }

            pub fx caught: () Str {
                mut msg: Str = ""
                try {
                    v: Int32 = risky(false)
                    msg = "no"
                } on e: Str {
                    msg = e
                }
                return msg
            }
            """,
            listOf(
                "throw kira::Error{\"bad\"};",
                "catch(const kira::Error& ex_)",
                "const kira::Str& e = ex_.message;",
                "[[maybe_unused]] const std::int32_t v{risky(false)};",
            ),
            listOf("""check(s5::caught() == "bad", "5.4: throw is kira::Error, try catches it (hosted)");"""),
        ),
        row(
            "m1",
            """
            pub fx some: () Maybe<Int32> {
                return 42
            }

            pub fx nothing: () Maybe<Int32> {
                return null
            }

            pub fx read: (m: Maybe<Int32>) Int32 {
                if m.isNone() {
                    return -1
                }
                return m.unwrap() + m.unwrapOr(0)
            }

            pub fx small: () Maybe<UInt8> {
                return 200
            }

            pub fx locals: () Int32 {
                a: Maybe<Int32> = null
                b: Maybe<Int32> = 7
                return a.unwrapOr(1) + b.value
            }
            """,
            listOf(
                "return 42;",
                "return kira::none;",
                "if(!kira::isSome(m))",
                "return kira::unwrap(m) + kira::unwrapOr(m, 0);",
                "return std::uint8_t{200};",
                "const kira::Maybe<std::int32_t> a = kira::none;",
                "const kira::Maybe<std::int32_t> b = 7;",
                "return kira::unwrapOr(a, 1) + kira::unwrap(b);",
            ),
            listOf(
                """check(m1::read(m1::some()) == 84 && m1::read(m1::nothing()) == -1, "5.7: Maybe returns, isNone, unwrap, unwrapOr");""",
                """check(kira::unwrap(m1::small()) == 200, "5.7: a narrow literal into a Maybe is T{lit}");""",
                """check(m1::locals() == 8, "5.7: Maybe locals, and m.value is unwrap (D40)");""",
            ),
        ),
        row(
            "d24",
            """
            pub fx run: (f: Fx<Tuple1<mut Int32>, Void>, mut x: Int32) Void {
                f(mut x)
            }

            pub fx twiceBumped: () Int32 {
                mut n: Int32 = 1
                run(fx(mut v: Int32) Void {
                    v += 1
                }, mut n)
                run(fx(mut v: Int32) Void {
                    v += 1
                }, mut n)
                return n
            }
            """,
            listOf("run([](std::int32_t& v) -> void", "v += 1;", "f(x);", "}, n);"),
            listOf("""check(d24::twiceBumped() == 3, "D24: a mut lambda parameter is T&, and writing it is a use");"""),
        ),
        row(
            "d39",
            """
            pub fx half: (n: Int32) Result<Int32, Str> {
                if n % 2 != 0 {
                    return Result.error("odd")
                }
                return Result.success(n / 2)
            }

            pub fx halved: (n: Int32) Int32 {
                r: Result<Int32, Str> = half(n)
                if r.isOk() {
                    return r.unwrap()
                }
                return -1
            }
            """,
            listOf(
                "return kira::Result<std::int32_t, kira::Str>::error(\"odd\");",
                "return kira::Result<std::int32_t, kira::Str>::success(n / 2);",
                "if(r.isOk())",
                "return r.unwrap();",
            ),
            listOf("""check(d39::halved(4) == 2 && d39::halved(3) == -1, "D39: Result.success and Result.error are kira::Result's factories");"""),
        ),
        row(
            "d55",
            """
            pub fx read: (b: View<UInt8>) Str {
                return Str.of(b)
            }

            pub fx text: () Str {
                b: List<UInt8> = [0x68, 0xC3, 0xA9, 0xFF, 0xE2, 0x82]
                return Str.of(b)
            }
            """,
            listOf("return kira::str::of(b);"),
            listOf(
                """check(d55::text() == "h\xc3\xa9\xef\xbf\xbd\xef\xbf\xbd", "D55: Str.of reads UTF-8, a stray byte and a cut sequence one U+FFFD each");""",
                """check(d55::read(kira::str::bytes("ok \xe2\x9c\x93")) == "ok \xe2\x9c\x93", "D55: and keeps what is well-formed");""",
            ),
        ),
        row(
            "d56",
            """
            pub fx before: (a: Str, b: Str) Bool {
                return a < b
            }

            pub fx atMost: (a: Str) Bool {
                return a <= "m"
            }

            pub fx literals: () Bool {
                return "abc" < "abd" && "b" >= "abc"
            }
            """,
            listOf("return a < b;", "return a <= \"m\";", "return std::string_view(\"abc\") < \"abd\" && std::string_view(\"b\") >= \"abc\";"),
            listOf(
                """check(d56::before("abc", "abd") && !d56::before("abd", "abc") && !d56::before("abc", "abc") && d56::before("ab", "abc") && d56::before("", "a"), "D56: a Str orders by its bytes, a prefix first");""",
                """check(d56::before("z", "\xc3\xa9") && d56::before("\xc3\xa9", "\xe2\x9c\x93") && d56::before("\xef\xbf\xbd", "\xf0\x9f\x9a\x97"), "D56: as unsigned bytes, so UTF-8 orders by code point");""",
                """check(d56::atMost("m") && d56::atMost("Z") && !d56::atMost("n") && d56::literals(), "D56: a literal on either side, and two literals compare their text");""",
            ),
        ),
        row(
            "d57",
            """
            pub fx padded: (s: Str) Str {
                return s.padEnd(4, '.')
            }

            pub fx trimmed: (s: Str) Str {
                return "[" + s.trimStart() + "|" + s.trimEnd() + "]"
            }

            pub fx words: (s: Str) Size {
                return s.splitWhitespace().size()
            }

            pub fx swapped: (s: Str) Str {
                return s.replace("ab", "ba").replace("", "/")
            }

            pub fx hex: (s: Str) Int64 {
                return s.toInt64Radix(16).unwrapOr(-1)
            }
            """,
            listOf(
                "return kira::str::padEnd(s, 4, '.');",
                "return kira::str::replace(kira::Str(kira::str::replace(s, \"ab\", \"ba\")), \"\", \"/\");",
                "return kira::List<kira::Str>(kira::str::splitWhitespace(s)).size();",
            ),
            listOf(
                """check(d57::padded("ab") == "ab.." && d57::padded("abcde") == "abcde", "D57: padEnd fills on the right and never cuts");""",
                """check(d57::trimmed(" \t x \r\n") == "[x \r\n| \t x]", "D57: trimStart and trimEnd take trim's four");""",
                """check(d57::words("\v a\fb  c\r\n") == 3 && d57::words(" \t") == 0, "D57: splitWhitespace splits on runs of C's six");""",
                """check(d57::swapped("abab") == "/b/a/b/a/" && d57::swapped("\xc3\xa9") == "/\xc3\xa9/", "D57: replace takes every occurrence, an empty one between code points");""",
                """check(d57::hex("-7F") == -127 && d57::hex("0x7f") == -1 && d57::hex("8000000000000000") == -1, "D57: toInt64Radix is strict and none past Int64");""",
            ),
        ),
        row(
            "d58",
            """
            pub fx joined: (xs: List<Str>, sep: Str) Str {
                return xs.joinToString(sep)
            }

            pub fx words: (s: Str) Str {
                return s.splitWhitespace().joinToString("|")
            }
            """,
            listOf("return kira::list::joinToString(xs, sep);"),
            listOf(
                """check(d58::joined({"a", "", "b"}, ", ") == "a, , b" && d58::joined({}, ", ").empty() && d58::joined({"x"}, "-") == "x", "D58: joinToString puts the separator between the pieces");""",
                """check(d58::words(" a b\tc ") == "a|b|c", "D58: and joins a List a call made");""",
            ),
        ),
        row(
            "d59",
            """
            pub @_const fx wordChar: (c: Char) Bool {
                return c.isDigit() || c.isLetter()
            }

            pub fx spaces: (s: Str) Int32 {
                mut n: Int32 = 0
                mut i: Size = 0
                while i < s.length() {
                    if s[i].isWhitespace() {
                        n += 1
                    }
                    i += 1
                }
                return n
            }
            """,
            listOf("return kira::isDigit(c) || kira::isLetter(c);", "if(kira::isWhitespace(kira::str::at(s, i)))"),
            listOf(
                """static_assert(d59::wordChar('7') && d59::wordChar('q') && !d59::wordChar('_') && !d59::wordChar('\xC3'), "D59: isDigit and isLetter in a constant expression");""",
                """check(d59::spaces(" \t\n\v\f\r\x1c\xc2\xa0x") == 6, "D59: isWhitespace takes isspace's six, no other byte");""",
            ),
        ),
        row(
            "d60",
            """
            use "kira:math"

            pub fx rem: (a: Float64, b: Float64) Float64 {
                return fmod(a, b)
            }

            pub fx rem32: (a: Float32, b: Float32) Float32 {
                return fmod(a, b)
            }
            """,
            listOf("return std::fmod(a, b);"),
            listOf(
                """check(d60::rem(5.5, 2.0) == 1.5 && d60::rem(-5.5, 2.0) == -1.5 && d60::rem(5.5, -2.0) == 1.5 && d60::rem(1.0, 1e300) == 1.0, "D60: fmod truncates, with the dividend's sign");""",
                """check(d60::rem(1.0, 0.0) != d60::rem(1.0, 0.0) && d60::rem(std::numeric_limits<double>::infinity(), 1.0) != d60::rem(std::numeric_limits<double>::infinity(), 1.0) && d60::rem(2.0, std::numeric_limits<double>::infinity()) == 2.0, "D60: NaN for a zero divisor or an infinite dividend, the dividend for an infinite divisor");""",
                """check(d60::rem32(7.5f, 2.0f) == 1.5f && std::is_same_v<decltype(d60::rem32(1.0f, 1.0f)), float>, "D60: and a Float32 stays a float");""",
            ),
        ),
        row(
            "b1",
            """
            fx poke: (v: MutView<UInt8>, x: UInt8) Void {
                v[0] = x
            }

            pub fx lend: () UInt8 {
                mut p: Arr<UInt8, 4> = [0, 0, 0, 0]
                poke(p.from(2), 9)
                return p[2]
            }

            pub fx fill: (servo: Int32, esc: UInt32, mut out: StrBuf<32>) Void {
                out.set("servo=${'$'}{servo} esc=${'$'}{esc}")
            }

            pub fx append: (mut out: StrBuf<32>) Void {
                out.add("!")
            }
            """,
            listOf(
                "poke(kira::mutView(p).from(2), 9);",
                "kira::at(v, 0) = x;",
                "out.clear();",
                "out.add(kira::lit(\"servo=\"));",
                "out.addInt(servo);",
                "out.add(kira::lit(\" esc=\"));",
                "out.addUInt(esc);",
                "out.add(kira::lit(\"!\"));",
            ),
            listOf(
                """check(b1::lend() == 9, "5.3: a mutable Arr lends a MutView, kira::mutView(p).from(2)");""",
                """{ kira::StrBuf<32> buf; b1::fill(-5, 7u, buf); b1::append(buf); check(std::string(buf.c_str()) == "servo=-5 esc=7!", "10: interpolation into a StrBuf appends piece by piece"); }""",
            ),
        ),
        row(
            "f1",
            """
            pub fx negneg: (y: Int32) Int32 {
                return -(-y)
            }

            pub fx negTwice: (a: Int32) Int32 {
                mut y: Int32 = a
                z: Int32 = -(-y)
                y += 1
                return z * 10 + y
            }

            pub fx big: () Int64 {
                big: Int64 = 1 << 40
                return big
            }

            pub fx ubig: () UInt64 {
                u: UInt64 = 1 << 63
                return u
            }

            pub fx mask: (v: UInt64) UInt64 {
                m: UInt64 = ~0
                return v & ~0xFF & m
            }

            pub fx alignDown: (x: Size) Size {
                return x & ~7
            }

            pub fx lowNibbleOff: (v: UInt8) UInt8 {
                return v & ~0x0F
            }

            pub @_const fx mul16: (a: UInt16, b: UInt16) UInt16 {
                return a * b
            }

            pub fx mulAssign16: (a: UInt16, b: UInt16) UInt16 {
                mut x: UInt16 = a
                x *= b
                return x
            }
            """,
            listOf(
                "return -(-y);",
                "const std::int32_t z{-(-y)};",
                "const std::int64_t big{std::int64_t{1} << 40};",
                "const std::uint64_t u{std::uint64_t{1} << 63};",
                "const std::uint64_t m{~std::uint64_t{0}};",
                "return v & ~std::uint64_t{0xFF} & m;",
                "return x & ~kira::Size{7};",
                "return static_cast<std::uint8_t>(v & static_cast<std::uint8_t>(~0x0Fu));",
                "return static_cast<std::uint16_t>(static_cast<unsigned>(a) * b);",
                "x = static_cast<std::uint16_t>(static_cast<unsigned>(x) * b);",
            ),
            listOf(
                """check(f1::negneg(7) == 7 && f1::negTwice(5) == 56, "a minus before a minus is -(-y), never the pre-decrement --y");""",
                """check(f1::big() == (std::int64_t{1} << 40) && f1::ubig() == 9223372036854775808ull, "R2: a literal on the left of a shift carries the operation's width");""",
                """check(f1::mask(0xFFFFu) == 0xFF00u && f1::alignDown(23) == 16 && f1::lowNibbleOff(0xABu) == 0xA0u, "R2: a literal under ~ carries the operation's width");""",
                """static_assert(f1::mul16(65535, 65535) == 1, "D8: a UInt16 product wraps in a constant expression too");""",
                """check(f1::mul16(65535, 65535) == 1 && f1::mulAssign16(65535, 65535) == 1, "D8: a UInt16 product is multiplied as unsigned, never as an overflowing int");""",
            ),
        ),
        row(
            "f2",
            """
            mut ticks: Int32 = 0
            mut idx: Size = 0

            fx next: () Int32 {
                ticks += 1
                return ticks
            }

            fx nextSize: () Size {
                idx += 1
                return idx
            }

            fx sub: (a: Int32, b: Int32) Int32 {
                return a - b
            }

            fx store: (mut into: Int32, tens: Int32, ones: Int32) Void {
                into = tens * 10 + ones
            }

            pub struct Box {
                pub v: Int32 = 0

                pub fx pair: (a: Int32, b: Int32) Int32 {
                    return v * 100 + a * 10 + b
                }

                pub mut fx grow: (a: Int32, b: Int32) Void {
                    v = v * 100 + a * 10 + b
                }
            }

            fx makeBox: () Box {
                ticks += 1
                return Box { v = ticks }
            }

            pub fx readBesideEffect: () Int32 {
                ticks = 0
                return sub(ticks, next())
            }

            pub fx holeBesideEffect: () Str {
                ticks = 0
                return "${'$'}{ticks}:${'$'}{next()}"
            }

            pub fx compoundReadsFirst: () Int32 {
                ticks = 0
                ticks += next()
                return ticks
            }

            pub fx receiverFirst: () Int32 {
                ticks = 0
                return makeBox().pair(next(), next())
            }

            pub fx mutIndexFirst: () Int32 {
                ticks = 0
                idx = 0
                mut q: Arr<Int32, 4> = [0, 0, 0, 0]
                store(mut q[nextSize()], next(), next())
                return q[1]
            }

            pub fx targetFirst: () Int32 {
                ticks = 0
                idx = 0
                mut s: Arr<Int32, 4> = [0, 0, 0, 0]
                s[nextSize()] = next()
                return s[1] * 10 + s[2]
            }

            pub fx dividedOnce: () Int32 {
                idx = 0
                mut p: Arr<Int32, 4> = [80, 80, 80, 80]
                d: Int32 = 2
                p[nextSize()] /= d
                return p[1] * 100 + p[2] + (idx as Int32)
            }

            pub fx shiftedOnce: () Int32 {
                idx = 0
                mut q: Arr<Int32, 4> = [1, 1, 1, 1]
                n: Int32 = 3
                q[nextSize()] <<= n
                return q[1] * 10 + (idx as Int32)
            }

            pub fx mutReceiverBound: () Int32 {
                ticks = 0
                idx = 0
                mut boxes: Arr<Box, 1> = [Box { }]
                boxes[nextSize() - 1].grow(next(), next())
                return boxes[0].v
            }
            """,
            listOf(
                "const std::int32_t t0_ = ticks;\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);",
                "return kira::cat(t0_, \":\", t1_);",
                "const std::int32_t t0_ = ticks;\n          const std::int32_t t1_ = next();\n          ticks = t0_ + t1_;",
                "const Box t0_ = makeBox();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);",
                "const kira::Size t0_ = nextSize();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          store(kira::at(q, t0_), t1_, t2_);",
                "const kira::Size t0_ = nextSize();\n          const std::int32_t t1_ = next();\n          kira::at(s, t0_) = t1_;",
                "const kira::Size t0_ = nextSize();\n          kira::at(p, t0_) = kira::div(kira::at(p, t0_), d);",
                "const kira::Size t0_ = nextSize();\n          kira::at(q, t0_) = kira::shl(kira::at(q, t0_), n);",
                "const kira::Size t0_ = nextSize() - 1;\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          kira::at(boxes, t0_).grow(t1_, t2_);",
            ),
            listOf(
                """check(f2::readBesideEffect() == -1 && f2::holeBesideEffect() == "0:1", "D33: a read of shared state beside an effect is copied before the effect runs");""",
                """check(f2::compoundReadsFirst() == 1, "D33: a compound assignment reads its target before an impure value runs");""",
                """check(f2::receiverFirst() == 123, "D33: an impure receiver runs before the spilled arguments");""",
                """check(f2::mutIndexFirst() == 12, "D33: a mut argument's index runs before its siblings, and the element itself is written, not a copy");""",
                """check(f2::targetFirst() == 10, "D33: a place assignment locates its target before the value runs");""",
                """check(f2::dividedOnce() == 4081 && f2::shiftedOnce() == 81, "R11, R12: a rewritten compound assignment computes its target's index once");""",
                """check(f2::mutReceiverBound() == 12, "D33: the receiver of a mut fx on an element is written in place, never copied");""",
            ),
        ),
        row(
            "f3",
            """
            mut gl: List<Int32> = List<Int32> { values = [11, 22, 33] }
            mut idx: Size = 0

            fx nextSize: () Size {
                idx += 1
                return idx
            }

            fx tailV: (v: View<Int32>, k: Size) View<Int32> {
                return v.from(k)
            }

            fx tailAtV: (k: Size, v: View<Int32>) View<Int32> {
                return v.from(k)
            }

            fx sumV: (v: View<Int32>) Int32 {
                mut s: Int32 = 0
                for x: Int32 in v {
                    s += x
                }
                return s
            }

            pub fx viewOfAPlace: () Int32 {
                idx = 0
                return sumV(tailAtV(nextSize(), gl))
            }

            pub fx viewOfATemporary: () Int32 {
                idx = 0
                return sumV(tailV(List<Int32> { values = [5, 6, 7] }.view(), nextSize()))
            }

            pub struct Acc {
                pub n: Int32 = 1

                pub mut fx bump: () Int32 {
                    n = 2
                    return 10
                }

                pub mut fx viaThis: () Int32 {
                    return peek(this, bump())
                }
            }

            fx peek: (a: Acc, k: Int32) Int32 {
                return a.n + k
            }

            pub fx thisBeforeBump: () Int32 {
                mut x: Acc = Acc { }
                return x.viaThis()
            }

            pub fx fixedContains: () Bool {
                a: Arr<Int32, 3> = [1, 2, 3]
                return a.contains(2) && !a.contains(4)
            }

            pub fx fixedClone: () Size {
                a: Arr<Int32, 3> = [1, 2, 3]
                c: Arr<Int32> = a.clone()
                return c.size()
            }

            pub fx mapLiteral: () Int32 {
                m: Map<Str, Int32> = Map<Str, Int32> { values = [Tuple2<Str, Int32> { first = "k", second = 1 }, Tuple2<Str, Int32> { first = "j", second = 2 }] }
                return (m.size() as Int32) * 10 + m.get("j").unwrapOr(-1)
            }

            pub fx setLiteral: () Size {
                s: Set<Int32> = Set<Int32> { values = [3, 4, 3] }
                e: Map<Str, Int32> = Map<Str, Int32> { values = [] }
                f: Set<Float32> = Set<Float32> { values = [1.5, 2.5] }
                return s.size() * 100 + e.size() * 10 + f.size()
            }
            """,
            listOf(
                // A place a view is formed of is never copied (E1), and a view call with an effect
                // is a kira::View temporary of its root's lambda (E3), its owner spilled first (E2).
                // The impure sibling runs before the view of the mut global gl is formed: in its
                // span, decision 4b read literally refuses it (`tailV(gl, nextSize())`).
                "const kira::Size t0_ = nextSize();\n          const kira::View<std::int32_t> t1_ = tailAtV(t0_, gl);\n          return sumV(t1_);",
                "const kira::List<std::int32_t> t0_ = kira::List<std::int32_t>{5, 6, 7};\n          const kira::Size t1_ = nextSize();\n          const kira::View<std::int32_t> t2_ = tailV(kira::view(t0_), t1_);",
                "const Acc t0_ = *this;\n          const std::int32_t t1_ = bump();\n          return peek(t0_, t1_);",
                "return kira::list::contains(a, 2) && !kira::list::contains(a, 4);",
                "const kira::List<std::int32_t> c = kira::list::clone(a);",
                "const kira::Map<kira::Str, std::int32_t> m = kira::Map<kira::Str, std::int32_t>{kira::Tuple2<kira::Str, std::int32_t>{.first = \"k\", .second = 1}, kira::Tuple2<kira::Str, std::int32_t>{.first = \"j\", .second = 2}};",
                "const kira::Set<std::int32_t> s = kira::Set<std::int32_t>{3, 4, 3};",
                "const kira::Map<kira::Str, std::int32_t> e = kira::Map<kira::Str, std::int32_t>{};",
            ),
            listOf(
                """check(f3::viewOfAPlace() == 55 && f3::viewOfATemporary() == 13, "D33: a view is formed of the place itself, and of a temporary only inside the lambda that holds it (design 30, E1-E3)");""",
                """check(f3::thisBeforeBump() == 11, "D33: a struct's this beside a sibling mut fx is read before the effect");""",
                """check(f3::fixedContains() && f3::fixedClone() == 3, "Arr<T, N>.contains and Arr<T, N>.clone bind over a std::array");""",
                """check(f3::mapLiteral() == 22 && f3::setLiteral() == 202, "R9: a Map or Set literal is a braced list of entries or values (a Set keeps one of each), and an empty one is T{}");""",
            ),
        ),
    )

    private val tree by lazy { CppExprTestSupport.emit("rows", rows.map { it.module } + shapes) }

    @TestFactory
    fun everyRowEmitsItsLines(): List<DynamicNode> = rows.map { r ->
        DynamicTest.dynamicTest("${r.id} emits the design's C++") {
            val text = tree.text(r.module)
            val missing = r.wants.filter { it !in text }
            assertTrue(missing.isEmpty(), "${r.id}: missing lines:\n${missing.joinToString("\n")}\n--- emitted ---\n$text")
        }
    }

    private fun driver(): String = buildString {
        (rows.map { it.module } + shapes).forEach { m ->
            append("#include \"").append(m.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
        }
        append(CppExprTestSupport.CHECK_PRELUDE)
        append("\nint main()\n{\n")
        rows.forEach { r -> r.checks.forEach { append("    ").append(it).append('\n') } }
        append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
        append("    return failures == 0 ? 0 : 1;\n}\n")
    }

    private val checkCount: Int get() = rows.sumOf { r -> r.checks.count { it.contains("check(") } }

    @TestFactory
    fun theRowsCompileUnderTheWarningContractAndPass(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC, CppToolchain.ZIG_AARCH64).map { tc ->
            DynamicTest.dynamicTest("rows [${tc.id}]") {
                val stdout = CppExprTestSupport.compileAndRun(tree, driver(), tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n$checkCount checks, 0 failed\n"), "${tc.id}: expected $checkCount checks, 0 failed:\n$stdout")
                assertTrue(stdout.contains("\n7\n1\n0.5\n"), "${tc.id}: trace prints the C prelude's formats (D42):\n$stdout")
                assertEquals(0, stdout.lines().count { it.startsWith("  FAIL") }, stdout)
            }
        }
}
