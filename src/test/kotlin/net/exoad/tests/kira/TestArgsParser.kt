package net.exoad.tests.kira

import net.exoad.kira.utils.ArgsParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestArgsParser
{
    @Test
    fun argsDuplicateOptions()
    {
        val argsParser = ArgsParser(arrayOf("--flag=123", "--flag=456"))
        assertNotNull(argsParser.findOption("--flag"))
        assertEquals("456", argsParser.findOption("--flag"))
    }

    @Test
    fun argsContainsFlags()
    {
        val argsParser = ArgsParser(arrayOf("-flag1", "--flag2"))
        assertTrue { argsParser.findFlag("-flag1") }
        assertTrue { argsParser.findFlag("--flag2") }
        assertFalse { argsParser.findFlag("-flag3") }
    }

    @Test
    fun argsContainsOptions()
    {
        val argsParser = ArgsParser(arrayOf("-flag1238", "1283912", "--myOption=2391230as", "--myOption12839a"))
        assertNotNull(argsParser.findOption("--myOption"))
        assertContains(argsParser.viewPositionalArgs, "1283912", "Could not find positional arg")
    }
}