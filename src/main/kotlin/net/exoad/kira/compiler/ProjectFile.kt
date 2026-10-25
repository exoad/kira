package net.exoad.kira.compiler

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.QualifiedIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Builder
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * project.kira, a project's one file: `PROJECT: Project = Project { } with { ... }`. It is Kira,
 * read by the compiler's own lexer and parser; [parse] evaluates that value (strings, true/false,
 * @listOf, @mapOf, `T { }` and `with`) into a [Project], and [check] types the file against the
 * stdlib's `kira:config`, as any code is typed.
 */
object ProjectFile {
    const val FILE = "project.kira"

    /** The module the file is typed as: no project's, so no source can import it. */
    const val URI = "project:project"

    /** The constant the file declares. */
    const val CONSTANT = "PROJECT"

    /** Every field of the stdlib's Project and Cpp, as [parse] reads them. */
    val PROJECT_FIELDS = listOf(
        "name", "author", "version", "srcDir", "srcExclude", "target", "cSources", "linkFlags", "minify", "cpp",
        "types", "emitIr", "dependencies",
    )
    val CPP_FIELDS = listOf(
        "layout", "outDir", "runtimeDir", "lineDirectives", "namespaces", "headerOnly", "freestanding", "headerExt", "sourceExt",
    )

    /** [dir]'s project file; null for none. */
    fun find(dir: Path): Path? = dir.resolve(FILE).takeIf { Files.isRegularFile(it) }

    fun isProject(dir: Path): Boolean = find(dir) != null

    /** Whether [file] is [root]'s project.kira, which configures the project and is none of its modules. */
    fun isProjectFile(file: Path, root: Path): Boolean =
        file.toAbsolutePath().normalize() == root.toAbsolutePath().normalize().resolve(FILE)

    /** A Kira string literal of [s]: `\`, `"` and `${` escaped. */
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\${", "\\\${") + "\""

    fun load(path: Path): Project {
        require(Files.exists(path)) { "Project file not found: $path" }
        return parse(Files.readString(path), path.toString())
    }

    /** [dir]'s project, its project.kira read; null when it has none. */
    fun loadIn(dir: Path): Project? = find(dir)?.let(::load)

    /** Each project file read, by its path and when it was last written: a rewritten one is read again. */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Project>>()

    /** The project the file [file] configures, read once while it is unchanged; null when it does not load. */
    fun cached(file: Path): Project? {
        val key = "$file|${Files.getLastModifiedTime(file).toMillis()}|${Files.size(file)}"
        return loaded.computeIfAbsent(key) { java.util.Optional.ofNullable(runCatching { load(file) }.getOrNull()) }.orElse(null)
    }

    /** [text]'s project; an [IllegalArgumentException] names what is wrong and where. */
    fun parse(text: String, file: String = FILE): Project {
        val ctx = read(CompilationUnit(bootstrap = false), text, file)
        return project(evaluate(ctx), ctx)
    }

    /** [text] parsed as the source [file] of [unit]. */
    fun read(unit: CompilationUnit, text: String, file: String): SourceContext {
        try {
            val processed = KiraPreprocessor(text).process().processedContent
            val bare = unit.addSource(file, processed, emptyList(), URI)
            val ctx = unit.addSource(file, processed, KiraLexer(bare).tokenize())
            KiraParser(ctx).parse()
            return ctx
        } catch (e: DiagnosticsException) {
            throw IllegalArgumentException((e.message.lines().firstOrNull { it.isNotBlank() } ?: e.toString()) + " at ${e.location}")
        }
    }

    /** A value of project.kira: Str, Bool, a list, a map, or a constructed type's fields. */
    private class Obj(val type: String, val at: ASTNode) {
        val fields = LinkedHashMap<String, Any>()

        /** Where each field was given, for a refusal. */
        val where = HashMap<String, ASTNode>()
    }

    /** The PROJECT constant's value; anything else at the top level is refused. */
    private fun evaluate(ctx: SourceContext): Obj {
        // The parser's own module header aside (the file is the module).
        val decls = ctx.ast.statements.map { (it as? Statement)?.expr }.filter { it !is ModuleDecl }
        fun isProject(e: Expr?) = e is VariableDecl && e.name.value == CONSTANT && (e.type.identifier as? Identifier)?.value == "Project" && e.value != null
        val project = decls.singleOrNull()?.takeIf(::isProject) as? VariableDecl
        val init = project?.value
        if (project == null || init == null) {
            refuse(ctx, decls.firstOrNull { !isProject(it) }, "$FILE holds one declaration, 'PROJECT: Project = Project { } with { ... }', and nothing else.")
        }
        return value(ctx, init) as? Obj ?: refuse(ctx, init, "PROJECT is a Project: 'Project { } with { ... }'.")
    }

    private fun value(ctx: SourceContext, e: Expr): Any = when {
        e is StringLiteral -> e.value
        // A number is a value too, so a field that wants a Str says so by name.
        e is IntegerLiteral -> e.value
        e is FloatLiteral -> e.value
        e is Identifier && e !is IntrinsicExpr && e !is QualifiedIdentifier && (e.value == "true" || e.value == "false") -> e.value == "true"
        e is BuilderExpr && e.kind == Builder.LIST -> e.elements.map { value(ctx, it) }
        e is BuilderExpr && e.kind == Builder.MAP -> LinkedHashMap<Any, Any>().also { m ->
            e.elements.chunked(2).forEach { (k, v) -> m[value(ctx, k)] = value(ctx, v) }
        }
        e is ObjectInitExpr -> construct(ctx, e)
        e is WithExpr -> (value(ctx, e.receiver) as? Obj ?: refuse(ctx, e, "A 'with' here builds on a type: 'Cpp { } with { ... }'.")).also { o ->
            e.body.forEach { st ->
                val a = st.expr as? AssignmentExpr ?: refuse(ctx, st.expr, "A 'with' block here holds assignments only: 'field = value', one per line.")
                o.fields[a.target.value] = value(ctx, a.value)
                o.where[a.target.value] = a
            }
        }
        else -> refuse(ctx, e, "$FILE holds plain values: a string, true or false, @listOf(...), @mapOf(...), or 'Type { } with { ... }'.")
    }

    /** `Project { }`, `Cpp { name = v }`, or an empty `List<Str> { }` / `Map<Str, Str> { }`. */
    private fun construct(ctx: SourceContext, e: ObjectInitExpr): Any {
        val name = (e.typeName.identifier as? Identifier)?.value ?: refuse(ctx, e, "Unknown type here.")
        if (e.positionalArgs.isNotEmpty()) refuse(ctx, e, "Name each field here: '$name { field = value }' or '$name { } with { field = value }'.")
        return when (name) {
            "List" -> if (e.namedArgs.isEmpty()) emptyList<Any>() else refuse(ctx, e, "A list here is '@listOf(a, b)'.")
            "Map" -> if (e.namedArgs.isEmpty()) LinkedHashMap<Any, Any>() else refuse(ctx, e, "A map here is '@mapOf(k1, v1, k2, v2)'.")
            "Project", "Cpp" -> Obj(name, e).also { o ->
                e.namedArgs.forEach {
                    o.fields[it.name.value] = value(ctx, it.value)
                    o.where[it.name.value] = it
                }
            }
            else -> refuse(ctx, e, "$FILE builds a Project, its Cpp, lists and maps: '$name' is none of them.")
        }
    }

    private fun project(p: Obj, ctx: SourceContext): Project {
        known(ctx, p, PROJECT_FIELDS)
        val defaults = Project("")
        val f = Fields(ctx, p)
        val types = f.str("types") ?: "off"
        val cpp = (p.fields["cpp"] as? Obj)?.let { cppOf(ctx, it, p) } ?: if ("cpp" in p.fields) refuse(ctx, p.where["cpp"], "Field 'cpp' is a Cpp: 'Cpp { } with { ... }'.") else CppOptions()
        return Project(
            name = f.str("name") ?: "",
            author = f.str("author") ?: "",
            version = f.str("version") ?: "",
            srcDir = f.str("srcDir") ?: defaults.srcDir,
            srcExclude = f.strs("srcExclude") ?: defaults.srcExclude,
            target = f.str("target") ?: defaults.target,
            cSources = f.strs("cSources") ?: defaults.cSources,
            linkFlags = f.strs("linkFlags") ?: defaults.linkFlags,
            minify = f.bool("minify") ?: defaults.minify,
            cpp = cpp,
            types = runCatching { TypeCheckMode.parse(types) }.getOrElse { refuse(ctx, p.where["types"], "Field 'types' must be one of off, lenient, strict (got '$types').") },
            emitIr = f.str("emitIr")?.takeIf { it.isNotEmpty() },
            dependencies = f.map("dependencies") ?: emptyMap(),
        )
    }

    private fun cppOf(ctx: SourceContext, c: Obj, p: Obj): CppOptions {
        if (c.type != "Cpp") refuse(ctx, p.where["cpp"], "Field 'cpp' is a Cpp: 'Cpp { } with { ... }'.")
        known(ctx, c, CPP_FIELDS)
        val d = CppOptions()
        val f = Fields(ctx, c)
        val layout = f.str("layout")
        return CppOptions(
            layout = layout?.let { runCatching { CppLayout.parse(it) }.getOrElse { _ -> refuse(ctx, c.where["layout"], "Field 'layout' must be one of beside, tree (got '$layout').") } } ?: d.layout,
            outDir = f.str("outDir") ?: d.outDir,
            runtimeDir = f.str("runtimeDir")?.takeIf { it.isNotEmpty() },
            lineDirectives = f.bool("lineDirectives") ?: d.lineDirectives,
            namespaces = f.map("namespaces") ?: d.namespaces,
            headerOnly = f.strs("headerOnly") ?: d.headerOnly,
            freestanding = f.strs("freestanding") ?: d.freestanding,
            headerExt = f.str("headerExt") ?: d.headerExt,
            sourceExt = f.str("sourceExt") ?: d.sourceExt,
        )
    }

    private fun known(ctx: SourceContext, o: Obj, fields: List<String>) {
        o.fields.keys.firstOrNull { it !in fields }?.let { refuse(ctx, o.where[it] ?: o.at, "${o.type} has no field '$it' (its fields: ${fields.joinToString(", ")}).") }
    }

    /** [o]'s fields by kind, each value trimmed, empty list entries dropped. */
    private class Fields(val ctx: SourceContext, val o: Obj) {
        fun str(k: String): String? = o.fields[k]?.let { it as? String ?: wrong(k, "a Str") }?.trim()

        fun bool(k: String): Boolean? = o.fields[k]?.let { it as? Boolean ?: wrong(k, "a Bool") }

        fun strs(k: String): List<String>? = o.fields[k]?.let { v ->
            (v as? List<*>)?.map { it as? String ?: wrong(k, "a List<Str>") }?.map { it.trim() }?.filter { it.isNotEmpty() } ?: wrong(k, "a List<Str>")
        }

        fun map(k: String): Map<String, String>? = o.fields[k]?.let { v ->
            val m = v as? Map<*, *> ?: wrong(k, "a Map<Str, Str>")
            LinkedHashMap<String, String>().also { out ->
                m.forEach { (a, b) -> out[(a as? String ?: wrong(k, "a Map<Str, Str>")).trim()] = (b as? String ?: wrong(k, "a Map<Str, Str>")).trim() }
            }
        }

        private fun wrong(k: String, what: String): Nothing = refuse(ctx, o.where[k] ?: o.at, "Field '$k' of ${o.type} is $what.")
    }

    private fun refuse(ctx: SourceContext, at: ASTNode?, message: String): Nothing {
        val pos = at?.let { ctx.astOrigins[it] }
        throw IllegalArgumentException(if (pos != null) "$message (${ctx.file}:${pos.lineNumber}:${pos.column})" else "$message (${ctx.file})")
    }

    /** The stdlib modules a project file is typed with: Project's own, and what its fields' types need. */
    val CHECK_MODULES = setOf("config", "core", "collections", "result", "tuples")

    /**
     * The typer's findings in the project file at [path], typed with the [CHECK_MODULES] of
     * [stdlib] (the dependency sources its configuration names), each as `file:line:col: error: code: message`.
     */
    fun check(path: Path, stdlib: List<String>): List<String> {
        val unit = CompilationUnit(bootstrap = false)
        val wanted = stdlib.filter { java.io.File(it).name.removeSuffix(".kira") in CHECK_MODULES }
        if (wanted.none { java.io.File(it).name == "config.kira" }) {
            return listOf("$path: error: project.stdlib: the stdlib this project names has no config.kira, whose Project $FILE builds")
        }
        for (s in wanted) {
            val canonical = runCatching { java.io.File(s).canonicalPath }.getOrDefault(s)
            val processed = KiraPreprocessor(java.io.File(s).readText()).process().processedContent
            val bare = unit.addSource(canonical, processed, emptyList(), ModuleNames.stdlib(s))
            KiraParser(unit.addSource(canonical, processed, KiraLexer(bare).tokenize())).parse()
        }
        val ctx = try {
            read(unit, Files.readString(path), path.toAbsolutePath().normalize().toString())
        } catch (e: IllegalArgumentException) {
            return listOf("$path: error: project.parse: ${e.message}")
        }
        val program = KiraTyper.run(unit, TyperMode.STRICT)
        // `file:line:col: error: code: message`, as the backends print theirs.
        return program.diagnostics.filter { it.isError && it.source === ctx }.map { d ->
            val at = d.position?.takeIf { it.lineNumber >= 0 }?.let { ":${it.lineNumber}:${it.column}" } ?: ""
            "$path$at: error: ${d.code}: ${d.message}"
        }
    }
}
