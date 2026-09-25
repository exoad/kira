package net.exoad.kira.compiler.backend.codegen.cpp

/**
 * The one formatter every generated C++ line goes through, so the output is
 * the same on every host and every run:
 *
 * - Allman braces: `{` and `}` on their own lines;
 * - a namespace body is indented 2 spaces, every other block 4;
 * - `if(`, `while(`, `for(`, `switch(` without a space;
 * - LF line endings, no trailing whitespace, no two blank lines in a row,
 *   no blank line right after `{`, and exactly one newline at the end.
 */
class CppWriter {
    private val out = StringBuilder()
    private var indent = 0
    private var lastWasBlank = true
    private var lastWasOpen = false

    /** One line at the current indent; an empty text is a blank line. */
    fun line(text: String = ""): CppWriter {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) {
            return blank()
        }
        repeat(indent) { out.append(' ') }
        out.append(trimmed).append('\n')
        lastWasBlank = false
        lastWasOpen = false
        return this
    }

    /** Several lines of preformatted text, each re-indented to the current level. */
    fun lines(text: String): CppWriter {
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line(it) }
        return this
    }

    /** A blank separator line; consecutive blanks and a blank right after `{` collapse. */
    fun blank(): CppWriter {
        if (!lastWasBlank && !lastWasOpen) {
            out.append('\n')
            lastWasBlank = true
        }
        return this
    }

    /** `head` then an Allman block whose body is indented [BODY_INDENT]; [trailer] follows `}` (`;` for a struct). */
    fun block(head: String, trailer: String = "", body: CppWriter.() -> Unit): CppWriter {
        return braced(head, BODY_INDENT, trailer, body)
    }

    /** `namespace name` (or an anonymous one for a null or empty name), body indented [NAMESPACE_INDENT]. */
    fun namespace(name: String?, body: CppWriter.() -> Unit): CppWriter {
        val head = if (name.isNullOrEmpty()) "namespace" else "namespace $name"
        return braced(head, NAMESPACE_INDENT, "", body)
    }

    fun ifBlock(condition: String, body: CppWriter.() -> Unit): CppWriter = block(ifHead(condition), body = body)
    fun elseIfBlock(condition: String, body: CppWriter.() -> Unit): CppWriter = block("else ${ifHead(condition)}", body = body)
    fun elseBlock(body: CppWriter.() -> Unit): CppWriter = block("else", body = body)
    fun whileBlock(condition: String, body: CppWriter.() -> Unit): CppWriter = block(whileHead(condition), body = body)
    fun forBlock(clauses: String, body: CppWriter.() -> Unit): CppWriter = block(forHead(clauses), body = body)

    private fun braced(head: String, bodyIndent: Int, trailer: String, body: CppWriter.() -> Unit): CppWriter {
        line(head)
        line("{")
        lastWasOpen = true
        indent += bodyIndent
        body()
        indent -= bodyIndent
        dropTrailingBlank()
        line("}$trailer")
        return this
    }

    private fun dropTrailingBlank() {
        if (lastWasBlank && out.isNotEmpty() && out.endsWith("\n\n")) {
            out.setLength(out.length - 1)
            lastWasBlank = false
        }
    }

    val isEmpty: Boolean
        get() = out.isEmpty()

    /** The text so far: LF only, ending with exactly one newline when not empty. */
    override fun toString(): String {
        var end = out.length
        while (end > 0 && out[end - 1] == '\n') {
            end -= 1
        }
        return if (end == 0) "" else out.substring(0, end) + "\n"
    }

    companion object {
        const val NAMESPACE_INDENT = 2
        const val BODY_INDENT = 4

        fun ifHead(condition: String): String = "if($condition)"
        fun whileHead(condition: String): String = "while($condition)"
        fun forHead(clauses: String): String = "for($clauses)"
        fun switchHead(subject: String): String = "switch($subject)"

        /** Any text to the writer's conventions: LF endings, no trailing whitespace, one final newline. */
        fun normalize(text: String): String {
            val lf = text.replace("\r\n", "\n").replace('\r', '\n')
            val trimmed = lf.split('\n').joinToString("\n") { it.trimEnd() }.trimEnd('\n')
            return if (trimmed.isEmpty()) "" else trimmed + "\n"
        }
    }
}
