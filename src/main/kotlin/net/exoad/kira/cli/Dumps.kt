package net.exoad.kira.cli

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.frontend.parser.ast.XMLASTVisitorKira
import java.io.File
import java.nio.file.Path

/** What a dump reads: the parsed unit, the project's own source files (canonical paths) and the project. */
internal class DumpInput(
    val unit: CompilationUnit,
    val workspace: List<String>,
    val project: Project?,
    val projectRoot: Path,
)

/** A dump's text, and whether the stage it dumps found no error (the CLI exits 1 when not). */
internal class DumpOutput(val text: String, val ok: Boolean = true)

/** One dump flag: its name, its help lines and what it prints for a parsed unit. */
internal class DumpFlag(val flag: String, val help: List<String>, val render: (DumpInput) -> DumpOutput)

/**
 * The CLI's dump flags, one family: each prints a stage's text for the project's own files to
 * stdout and stops before any backend. To add a dump, add an entry to [all]; the flag parser,
 * `--help` and the runner read that list.
 */
internal object Dumps {
    val all: List<DumpFlag> = listOf(
        DumpFlag(
            "--dump-ast-xml",
            listOf("print each source file's AST as XML to stdout and stop (no analysis, no backend)"),
        ) { input ->
            DumpOutput(
                input.workspace.mapNotNull { input.unit.getSource(it) }.joinToString("") { source ->
                    "<!-- ${shown(input, source.file)} -->\n" + XMLASTVisitorKira.build(source.ast)
                }
            )
        },
        DumpFlag(
            "--dump-typed",
            listOf("print the typed model to stdout and stop: module symbols and typer diagnostics, then each", "expression's type and resolved facts per source file; exit 1 on a typer error"),
        ) { input ->
            val cpp = input.project?.cpp
            val program = KiraTyper.run(
                input.unit,
                TyperMode.STRICT,
                TyperOptions(cpp?.freestanding.orEmpty(), cpp?.headerOnly.orEmpty(), cpp?.namespaces.orEmpty()),
            )
            val text = StringBuilder(TypedModelDumper.dumpSymbols(program))
            for (module in program.workspaceModules) {
                text.append("bodies ").append(TypedModelDumper.logicalPath(module)).append('\n')
                    .append(TypedModelDumper.dump(program, module.source)).append('\n')
            }
            DumpOutput(text.toString(), !program.hasErrors)
        },
    )

    /** The dump a command-line argument names, else null. */
    fun named(arg: String): DumpFlag? = all.firstOrNull { it.flag == arg }

    /** The usage's lines for every dump flag. */
    fun helpLines(): List<String> = all.flatMap { d ->
        d.help.mapIndexed { i, line -> if (i == 0) "  ${d.flag}".padEnd(18) + line else " ".repeat(18) + line }
    }

    /** Prints each requested dump (headed by its flag when more than one) and returns the exit code. */
    fun run(requested: List<DumpFlag>, input: DumpInput): Int {
        var code = 0
        for (dump in requested) {
            val output = dump.render(input)
            if (requested.size > 1) {
                print("# ${dump.flag}\n")
            }
            print(output.text)
            if (!output.ok) {
                code = 1
            }
        }
        return code
    }

    /** [file] relative to the project root, with forward slashes. */
    private fun shown(input: DumpInput, file: String): String {
        val path = File(file).toPath().toAbsolutePath().normalize()
        val root = input.projectRoot.toAbsolutePath().normalize()
        return (if (path.startsWith(root)) root.relativize(path) else path).toString().replace('\\', '/')
    }
}
