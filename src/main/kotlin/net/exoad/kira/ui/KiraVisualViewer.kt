package net.exoad.kira.ui

import net.exoad.kira.compiler.SourceContext
import net.exoad.kira.compiler.front.Token
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import kotlin.math.floor
import kotlin.math.log10

/**
 * idrk what this is for, i just wanted to experiment with syntax highlighting LOL
 *
 * doesnt really work although the coloring does work. the indentation and a lot of the raw formatting are not preserved
 *
 * ok after some finicking (idt this is a word), but anyway, going to make this just a glorified tokens viewer :)
 */
class KiraVisualViewer(private val context: SourceContext) : JFrame("Kira Lexer")
{
    private val editorPane = JEditorPane()
    private var showTokenType = true

    init
    {
        size = Dimension(600, 800)
        preferredSize = size
        defaultCloseOperation = EXIT_ON_CLOSE //often is the last flag that is run and checked in the lifecycle
        editorPane.apply {
            isEditable = false
            contentType = "text/html"
            font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            border = BorderFactory.createLineBorder(Color.BLACK, 1, true)
        }
        jMenuBar = JMenuBar().apply {
            add(JMenu("View").apply {
                add(JMenuItem(if(showTokenType) "Toggle Raw View" else "Toggle Token View").apply {
                    addActionListener {
                        showTokenType = !showTokenType
                        render()
                    }
                })
            })
        }
        contentPane = JScrollPane(editorPane).apply {
            isOpaque = true
            preferredSize = size
        }
        render()
    }

    private fun render()
    {
        editorPane.text = buildString {
            fun node(name: String, attrs: String? = null, body: () -> Unit)
            {
                append("<$name")
                if(attrs != null) append(" $attrs")
                append(">")
                body()
                append("</$name>")
            }
            append("<!DOCTYPE html>")
            node("html") {
                node(
                    "body",
                    """
                        style="background-color:#282c34;
                               color: #abb2bf;
                               font-family: monospace;
                               font-size: 14px;
                               padding-left: 12px;
                               padding-right: 12px;
                               line-height: 1.4;
                               white-space: pre-wrap;"
                    """.trimIndent()
                ) {
                    var lastLine = -1
                    context.tokens.forEach { token ->
                        val color = when
                        {
                            token.type.name.startsWith("K_MODIFIER") -> "#4db6ac"
                            token.type.name.startsWith("K_")         -> "#58a6ff"
                            token.type.name.startsWith("OP_")        -> "#f78c6c"
                            token.type.name.startsWith("S_")         -> "#c792ea"
                            token.type.name.startsWith("L_")         -> "#ffd580"
                            else                                     -> "#fdf6e3"
                        }
                        val lineNumber = token.canonicalLocation.lineNumber
                        if(lineNumber != lastLine)
                        {
                            append("<br/>")
                            append(
                                """<span style="color:#5c6370;background-color:#21252b;">
                                ${
                                    lineNumber.toString().padStart(
                                        floor(log10(context.tokens.size.toDouble())).toInt() + 2,
                                        ' '
                                    )
                                }</span> """
                            )
                            lastLine = lineNumber
                        }
                        node(
                            "span", """style="${
                                if(showTokenType)
                                {
                                    "background-color:$color;color:#000000;"
                                }
                                else
                                {
                                    "color:$color"
                                }
                            }""""
                        )
                        {
                            append(
                                (if(showTokenType) token.type.name else token.content)
                                    .replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;")
                                    .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
                                    .replace(Regex("^\\s+")) { match ->
                                        match.value.replace(" ", "&nbsp;")
                                    })
                        }
                        append("&nbsp;")
                        if(token.type == Token.Type.S_SEMICOLON)
                        {
                            append("<br/>")
                        }
                    }
                }
            }
        }
    }

    fun run()
    {
        SwingUtilities.invokeLater {
            pack()
            isVisible = true
        }
    }
}
