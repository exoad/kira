package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AliasSymbol
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr

/**
 * The spec's naming conventions (Identifiers and Naming Conventions), as **warnings**:
 * `rules.naming.function` and `.variable` (camelCase: functions, methods, parameters,
 * locals, fields, `mut` globals, which may also be UPPER_SNAKE_CASE), `.type` (PascalCase:
 * classes, structs, traits, enums, aliases, type parameters), `.constant` (UPPER_SNAKE_CASE:
 * module constants and enum entries). Only the modules the user wrote are checked; extern
 * and magic declarations keep their foreign names; operator overloads have no name to check.
 */
internal class NamingPass : RulePass {
    override val name: String = "naming"

    private val camel = Regex("^[a-z][a-zA-Z0-9]*$")
    private val pascal = Regex("^[A-Z][a-zA-Z0-9]*$")
    private val upper = Regex("^[A-Z][A-Z0-9_]*$")

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (m in program.workspaceModules) {
            for (s in m.declarations) {
                declaration(r, s)
            }
            AstTree.walk(m.source.ast) { n ->
                when (n) {
                    is VariableDecl -> (r.model.declSyms[n] as? LocalSymbol)?.let { variable(r, it.name, "Local", n.name) }
                    is ForIterationExpr -> (r.model.declSyms[n] as? LocalSymbol)?.let { variable(r, it.name, "Loop variable", n.initializer) }
                    is FunctionDeclParameterExpr -> (r.model.declSyms[n] as? ParamSymbol)?.let { p -> if (p.fn == null) variable(r, p.name, "Parameter", n.name) }
                    else -> {}
                }
            }
        }
    }

    private fun declaration(r: Rules, s: Symbol) {
        when (s) {
            is FnSymbol -> function(r, s)
            is GlobalSymbol -> if (s.foreign == null) {
                if (s.isConstant) {
                    if (!upper.matches(s.name)) {
                        warn(r, "constant", "Constant '${s.name}' does not conform to UPPER_SNAKE_CASE (a module-level binding without `mut` is a constant); did you mean '${toUpperSnake(s.name)}'?", s.decl)
                    }
                } else if (!camel.matches(s.name) && !upper.matches(s.name)) {
                    warn(r, "variable", "Variable '${s.name}' does not conform to camelCase; did you mean '${toCamel(s.name)}'?", s.decl)
                }
            }
            is ClassSymbol -> if (s.foreign == null) {
                type(r, if (s.isStruct) "Struct" else "Class", s.name, s.decl)
                s.typeParams.forEach { typeParam(r, it) }
                s.fields.forEach { f -> if (f.markers.none { it.name == "_extern" }) variable(r, f.name, "Field", f.decl) }
                s.methods.forEach { function(r, it) }
            }
            is TraitSymbol -> if (s.foreign == null) {
                type(r, "Trait", s.name, s.decl)
                s.typeParams.forEach { typeParam(r, it) }
                s.methods.forEach { function(r, it) }
            }
            is EnumSymbol -> if (s.foreign == null) {
                type(r, "Enum", s.name, s.decl)
                s.entries.forEach { e ->
                    if (!upper.matches(e.name)) {
                        warn(r, "constant", "Enum entry '${e.name}' does not conform to UPPER_SNAKE_CASE; did you mean '${toUpperSnake(e.name)}'?", e.decl)
                    }
                }
            }
            is AliasSymbol -> {
                type(r, "Alias", s.name, s.decl)
                s.typeParams.forEach { typeParam(r, it) }
            }
            else -> {}
        }
    }

    private fun function(r: Rules, fn: FnSymbol) {
        if (fn.isOperator || fn.foreign != null || fn.decl?.isAnonymous() == true) {
            return
        }
        if (!camel.matches(fn.name)) {
            warn(r, "function", "Function '${fn.name}' does not conform to camelCase; did you mean '${toCamel(fn.name)}'?", fn.decl?.name ?: fn.decl)
        }
        fn.typeParams.forEach { typeParam(r, it) }
        fn.params.forEach { p -> variable(r, p.name, "Parameter", p.decl) }
    }

    private fun typeParam(r: Rules, p: TypeParamSymbol) {
        if (!pascal.matches(p.name)) {
            warn(r, "type", "Type parameter '${p.name}' does not conform to PascalCase.", p.decl)
        }
    }

    private fun type(r: Rules, kind: String, name: String, at: ASTNode?) {
        if (!pascal.matches(name)) {
            warn(r, "type", "$kind '$name' does not conform to PascalCase; did you mean '${toPascal(name)}'?", at)
        }
    }

    private fun variable(r: Rules, name: String, kind: String, at: ASTNode?) {
        if (!camel.matches(name)) {
            warn(r, "variable", "$kind '$name' does not conform to camelCase; did you mean '${toCamel(name)}'?", at)
        }
    }

    private fun warn(r: Rules, case: String, message: String, at: ASTNode?) {
        r.report("rules.naming.$case", message, at, Severity.WARNING)
    }

    private fun words(name: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in name) {
            when {
                ch == '_' -> if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                ch.isUpperCase() && current.isNotEmpty() && (current.last().isLowerCase() || current.last().isDigit()) -> {
                    out.add(current.toString())
                    current.clear()
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            out.add(current.toString())
        }
        return out.map { it.lowercase() }
    }

    private fun toCamel(name: String): String = words(name).mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun toPascal(name: String): String = words(name).joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun toUpperSnake(name: String): String = words(name).joinToString("_") { it.uppercase() }
}
