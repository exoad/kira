package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CppWriterTest {
    /** The shape of design 5.9's proto.kira.hxx and .cxx, character for character. */
    @Test
    fun goldenHeaderShape() {
        val w = CppWriter()
        w.line("// proto.kira.hxx")
        w.line("#pragma once")
        w.line("#include \"kira/rt.hxx\"")
        w.line("#include \"kira/macro_push.hxx\"")
        w.namespace("proto") {
            block("enum class Kind : std::int32_t", trailer = ";") {
                line("KIND_OK = 0,")
                line("KIND_ERR = 1,")
            }
            blank()
            block("struct Reply", trailer = ";") {
                line("Kind kind = Kind::KIND_EMPTY;")
                line("kira::Str topic = \"\";")
            }
            blank()
            line("[[nodiscard]] Reply read(const kira::Str& line);")
        }
        w.line("#include \"kira/macro_pop.hxx\"")

        val expected = """
            |// proto.kira.hxx
            |#pragma once
            |#include "kira/rt.hxx"
            |#include "kira/macro_push.hxx"
            |namespace proto
            |{
            |  enum class Kind : std::int32_t
            |  {
            |      KIND_OK = 0,
            |      KIND_ERR = 1,
            |  };
            |
            |  struct Reply
            |  {
            |      Kind kind = Kind::KIND_EMPTY;
            |      kira::Str topic = "";
            |  };
            |
            |  [[nodiscard]] Reply read(const kira::Str& line);
            |}
            |#include "kira/macro_pop.hxx"
            |""".trimMargin()
        assertEquals(expected, w.toString())
    }

    @Test
    fun goldenSourceShapeWithAnonymousNamespaceAndControlFlow() {
        val w = CppWriter()
        w.line("#include \"proto.kira.hxx\"")
        w.namespace("proto") {
            namespace(null) {
                block("[[nodiscard]] bool isSpace(char c)") {
                    line("return c == ' ' || c == '\\t';")
                }
            }
            blank()
            block("bool fieldInt(const kira::Str& text, const kira::Str& key, std::int32_t& out)") {
                line("kira::Str raw = \"\";")
                ifBlock("!field(text, key, raw)") {
                    line("return false;")
                }
                whileBlock("at < buf.size()") {
                    line("at += 1;")
                }
                forBlock("std::size_t i = 0; i < n; ++i") {
                    line("sum += i;")
                }
                blank()
                blank()
                line("return true;")
            }
        }

        val expected = """
            |#include "proto.kira.hxx"
            |namespace proto
            |{
            |  namespace
            |  {
            |    [[nodiscard]] bool isSpace(char c)
            |    {
            |        return c == ' ' || c == '\t';
            |    }
            |  }
            |
            |  bool fieldInt(const kira::Str& text, const kira::Str& key, std::int32_t& out)
            |  {
            |      kira::Str raw = "";
            |      if(!field(text, key, raw))
            |      {
            |          return false;
            |      }
            |      while(at < buf.size())
            |      {
            |          at += 1;
            |      }
            |      for(std::size_t i = 0; i < n; ++i)
            |      {
            |          sum += i;
            |      }
            |
            |      return true;
            |  }
            |}
            |""".trimMargin()
        assertEquals(expected, w.toString())
    }

    @Test
    fun lineEndingsAreLfAndTrailingWhitespaceIsDropped() {
        val w = CppWriter()
        w.lines("int a;   \r\nint b;\r\n\r\n\r\nint c;")
        val text = w.toString()
        assertFalse(text.contains('\r'))
        assertEquals("int a;\nint b;\n\nint c;\n", text)
    }

    @Test
    fun blankLinesNeverFollowAnOpeningBraceOrPrecedeAClosingOne() {
        val w = CppWriter()
        w.block("void f()") {
            blank()
            line("x();")
            blank()
        }
        assertEquals("void f()\n{\n    x();\n}\n", w.toString())
    }

    @Test
    fun headsHaveNoSpaceBeforeTheParenthesis() {
        assertEquals("if(a)", CppWriter.ifHead("a"))
        assertEquals("while(a)", CppWriter.whileHead("a"))
        assertEquals("for(;;)", CppWriter.forHead(";;"))
        assertEquals("switch(k)", CppWriter.switchHead("k"))
    }

    @Test
    fun emptyWriterIsEmptyAndNormalizeIsIdempotent() {
        assertEquals("", CppWriter().toString())
        val once = CppWriter.normalize("a \r\nb\r\n\r\n")
        assertEquals("a\nb\n", once)
        assertEquals(once, CppWriter.normalize(once))
    }

    @Test
    fun lfBytesReadsCrlfAsLfAndLeavesBinaryAlone() {
        assertEquals("a\nb\n", String(CppWriter.lfBytes("a\r\nb\r\n".toByteArray())))
        assertEquals("a\nb\n", String(CppWriter.lfBytes("a\nb\n".toByteArray())))
        // a lone CR is content, not a line ending
        assertEquals("a\rb\n", String(CppWriter.lfBytes("a\rb\r\n".toByteArray())))
        val binary = byteArrayOf(0, '\r'.code.toByte(), '\n'.code.toByte(), 7)
        assertEquals(binary.toList(), CppWriter.lfBytes(binary).toList())
    }

    @Test
    fun outputIsDeterministic() {
        fun build(): String {
            val w = CppWriter()
            w.namespace("n") { block("struct S", ";") { line("int x;") } }
            return w.toString()
        }
        assertEquals(build(), build())
    }
}
