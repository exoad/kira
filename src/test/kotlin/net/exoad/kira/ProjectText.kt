package net.exoad.kira

import net.exoad.kira.compiler.ProjectFile
import java.io.File

/** project.kira text for a test's project: its name, srcDir and target, the repo's stdlib as its dependency, and [more] fields. */
object ProjectText {
    /** The repo's stdlib, as a dependency path. */
    val stdlib: String = File("kira").absolutePath.replace('\\', '/')

    fun of(name: String, srcDir: String = "src", target: String? = null, stdlib: String? = ProjectText.stdlib, vararg more: String): String {
        val fields = buildList {
            add("name = ${ProjectFile.quote(name)}")
            add("srcDir = ${ProjectFile.quote(srcDir)}")
            target?.let { add("target = ${ProjectFile.quote(it)}") }
            stdlib?.let { add("dependencies = @mapOf(\"kira_stdlib\", ${ProjectFile.quote(it)})") }
            addAll(more)
        }
        return "PROJECT: Project = Project { } with {\n" + fields.joinToString("") { "    $it\n" } + "}\n"
    }
}
