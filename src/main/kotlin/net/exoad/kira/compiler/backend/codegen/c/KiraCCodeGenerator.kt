package net.exoad.kira.compiler.backend.codegen.c

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.KiraCodeGenerator
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.intrinsics.MagicIntrinsic
import net.exoad.kira.source.SourceContext
import java.io.File

/**
 * Baseline C backend.
 *
 * Emits one translation unit:
 *  1. Jack-style runtime prelude (shared types + print helpers)
 *  2. User / non-magic declarations only -- `@_magic` and `kira:*` stdlib
 *     bodies are skipped; their types already live in the prelude
 *
 * Formatting follows Jack's C style guide: Allman braces, PascalCase types,
 * camelCase functions, SCREAMING_SNAKE enum members prefixed by type name.
 */
class KiraCCodeGenerator(override val compilationUnit: CompilationUnit) : KiraCodeGenerator(compilationUnit) {
    companion object {
        const val TEMPLATE_FILE = "c_generator.c"
        const val DEFAULT_OUTPUT = "out.kira.c"
        private lateinit var templateFileContents: String

        fun fetchTemplateFileContents(): String {
            if (!::templateFileContents.isInitialized) {
                val resource = Public::class.java.getResource("/$TEMPLATE_FILE")
                    ?: Public::class.java.getResource(TEMPLATE_FILE)
                templateFileContents = resource?.readText()
                    ?: File("src/main/resources/$TEMPLATE_FILE").readText()
            }
            return templateFileContents
        }
    }

    private val buffer = StringBuilder()
    private val requiredIncludes = linkedSetOf<String>()
    private val discoveredMagicTypes by lazy {
        compilationUnit.collectIntrinsicMarkedTypeNames(MagicIntrinsic.name) +
            compilationUnit.allMagicTypes()
    }
    /** Simple name -> Kira type name for print-format heuristics in the current unit. */
    private val knownValueTypes = mutableMapOf<String, String>()
    private var indentLevel = 0
    private var currentModuleUri: String? = null
    private var emittingClassMembers = false

    /**
     * One-shot emit of the whole compilation unit into [outputPath].
     * Returns the generated C source (also written to disk).
     */
    fun generate(outputPath: String = DEFAULT_OUTPUT): String {
        clean()
        val source = buildTranslationUnit()
        File(outputPath).writeText(source)
        return source
    }

    /**
     * Build C text without writing a file -- used by tests.
     */
    fun emitToString(): String {
        clean()
        return buildTranslationUnit()
    }

    private fun buildTranslationUnit(): String {
        // Prelude once
        buffer.appendLine(fetchTemplateFileContents().trimEnd())
        buffer.appendLine()

        // Extra includes requested by intrinsics (math.h, etc.)
        // Collected while walking; prepended after the walk.
        val bodyStart = buffer.length

        // Forward-declare every non-magic free function so multi-module emit
        // order does not matter for C (definitions may appear after calls).
        emitFunctionPrototypes()

        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) {
                return@forEach
            }
            visitRootASTNode(source.ast)
        }

        if (requiredIncludes.isNotEmpty()) {
            val body = buffer.substring(bodyStart)
            val header = buildString {
                requiredIncludes.forEach { appendLine("#include <$it>") }
                appendLine()
            }
            buffer.delete(bodyStart, buffer.length)
            buffer.insert(bodyStart, header)
            // body already appended after delete+insert? no -- re-append
            buffer.append(body)
        }

        return buffer.toString()
    }

    private fun emitFunctionPrototypes() {
        val prototypes = linkedSetOf<String>()
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            collectFunctionPrototypes(source.ast, prototypes)
        }
        if (prototypes.isEmpty()) return
        prototypes.forEach { buffer.appendLine(it) }
        buffer.appendLine()
    }

    private fun collectFunctionPrototypes(node: RootASTNode, out: MutableSet<String>) {
        node.statements.forEach { stmt ->
            val decl: FunctionDecl? = when (stmt) {
                is FunctionDecl -> stmt
                is Statement -> stmt.expr as? FunctionDecl
                else -> null
            }
            if (decl != null && !isMagicDecl(decl)) {
                out.add(functionPrototypeLine(decl))
            }
        }
    }

    private fun functionPrototypeLine(functionDecl: FunctionDecl): String {
        val functionName = functionLikeName(functionDecl.name)
        val returnTypeName = typeNameOf(functionDecl.def.returnTypeSpecifier)
        val returnsVoid = returnTypeName == "Void"
        val retC = if (functionName == "main" && returnsVoid) {
            "Int32"
        } else {
            mapTypeName(returnTypeName)
        }
        val params = if (functionDecl.def.parameters.isEmpty()) {
            "Void"
        } else {
            functionDecl.def.parameters.joinToString(", ") { param ->
                "${mapTypeName(typeNameOf(param.typeSpecifier))} ${param.name.value}"
            }
        }
        // Register return type early so print-format works even if a call
        // appears before the definition in the walk order.
        knownValueTypes[functionName] = returnTypeName
        functionDecl.def.parameters.forEach { param ->
            knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
        }
        return "$retC $functionName($params);"
    }

    private fun shouldSkipSource(source: SourceContext): Boolean {
        // Skip kira:* stdlib modules entirely -- magic types live in the prelude.
        // getModuleUri panics if AST is missing; treat that as skippable noise.
        val uri = runCatching { source.getModuleUri() }.getOrNull() ?: return true
        return uri == "kira:stl" || uri.startsWith("kira:")
    }

    private fun appendIndented(value: String) {
        repeat(indentLevel) { buffer.append("    ") }
        buffer.append(value)
    }

    private fun appendIndentedLine(value: String = "") {
        appendIndented(value)
        buffer.appendLine()
    }

    private fun binaryOpSymbol(op: BinaryOp): String {
        return op.symbol.joinToString(separator = "") { it.rep.toString() }
    }

    private fun functionLikeName(expr: Expr): String {
        return when (expr) {
            is Identifier -> expr.value
            is IntrinsicExpr -> expr.intrinsicKey.name
            else -> "_anon"
        }
    }

    private fun typeNameOf(type: Type): String {
        return when (val identifier = type.identifier) {
            is Identifier -> identifier.value
            is IntrinsicExpr -> identifier.intrinsicKey.name
            else -> "Any"
        }
    }

    private fun mapIntrinsicName(rawName: String): String {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        // Only rewrite known intrinsics; user functions keep their original casing.
        return CIntrinsicsTable.resolveFunctionOrNull(canonical) ?: rawName.removePrefix("@")
    }

    private fun includeForIntrinsic(rawName: String) {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        requiredIncludes.addAll(CIntrinsicsTable.resolveIncludes(canonical))
    }

    private fun mapTypeName(typeName: String): String {
        CMagicTypeLowering.resolve(typeName)?.let { return it.cType }
        // Always map well-known builtins even without magic registration
        return when (typeName) {
            "Int8", "Int16", "Int32", "Int64",
            "Float32", "Float64", "Bool", "Void", "Never",
            "Str", "String", "Any", "Int", "Float" ->
                CMagicTypeLowering.resolve(typeName)?.cType ?: typeName
            else -> typeName
        }
    }

    private fun isMagicDecl(decl: Decl): Boolean {
        if (decl is FirstClassDecl && decl.isMagic()) {
            return true
        }
        // Also treat known magic type names as skippable even if marker missed
        val name = when (decl) {
            is ClassDecl -> typeNameOf(decl.name)
            is EnumDecl -> decl.name.value
            is TraitDecl -> typeNameOf(decl.name)
            is VariantDecl -> typeNameOf(decl.name)
            is TypeAliasDecl -> when (val id = decl.alias.identifier) {
                is Identifier -> id.value
                else -> null
            }
            is FunctionDecl -> functionLikeName(decl.name)
            is VariableDecl -> decl.name.value
            else -> null
        }
        return name != null && discoveredMagicTypes.contains(name)
    }

    private fun toScreamingSnake(name: String): String {
        if (name.isEmpty()) return name
        val sb = StringBuilder()
        name.forEachIndexed { index, c ->
            if (c.isUpperCase() && index > 0) {
                sb.append('_')
            }
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }

    fun clean() {
        buffer.clear()
        requiredIncludes.clear()
        knownValueTypes.clear()
        indentLevel = 0
        currentModuleUri = null
        emittingClassMembers = false
    }

    override fun toString(): String {
        // Prefer emitToString for full unit; this returns the current buffer
        // so existing tests that call visitRootASTNode + toString still work.
        if (buffer.isEmpty()) {
            return emitToString()
        }
        // If someone only walked a root without prelude, prepend it
        val text = buffer.toString()
        if (!text.contains("KIRA_RUNTIME_H") && !text.contains("typedef int32_t Int32")) {
            return fetchTemplateFileContents().trimEnd() + "\n\n" + text
        }
        return text
    }

    override fun visitRootASTNode(node: RootASTNode) {
        node.statements.forEach { it.accept(this) }
    }

    override fun visitStatement(statement: Statement) {
        // Control-flow subclasses Statement and override accept(); they never
        // arrive here. Only Expr-shaped statement payloads do.
        when (val expr = statement.expr) {
            is NoExpr -> return
            // Decls and try manage their own terminators / newlines.
            is ClassDecl, is FunctionDecl, is ModuleDecl, is EnumDecl,
            is TraitDecl, is VariantDecl, is TypeAliasDecl, is VariableDecl,
            is TryExpr -> {
                expr.accept(this)
                if (buffer.isNotEmpty() && buffer.last() != '\n') {
                    buffer.appendLine()
                }
            }
            else -> {
                appendIndented("")
                expr.accept(this)
                buffer.appendLine(";")
            }
        }
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {
        appendIndented("if(")
        ifSelectionStatement.expr.accept(this)
        buffer.appendLine(")")
        appendIndentedLine("{")
        indentLevel++
        ifSelectionStatement.thenStatements.forEach { it.accept(this) }
        indentLevel--
        appendIndented("}")
        if (ifSelectionStatement.elseBranches.isEmpty()) {
            buffer.appendLine()
            return
        }
        // Keep "} else" / "} else if" on the same structural block
        ifSelectionStatement.elseBranches.forEach { branch ->
            buffer.append(" ")
            branch.accept(this)
        }
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {
        buffer.append("else if(")
        ifElseIfBranchNode.condition.accept(this)
        buffer.appendLine(")")
        appendIndentedLine("{")
        indentLevel++
        ifElseIfBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        buffer.appendLine("else")
        appendIndentedLine("{")
        indentLevel++
        elseBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        appendIndented("while(")
        whileIterationStatement.condition.accept(this)
        buffer.appendLine(")")
        appendIndentedLine("{")
        indentLevel++
        whileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        appendIndentedLine("do")
        appendIndentedLine("{")
        indentLevel++
        doWhileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndented("} while(")
        doWhileIterationStatement.condition.accept(this)
        buffer.appendLine(");")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement) {
        appendIndented("return")
        if (returnStatement.expr !is NoExpr) {
            buffer.append(" ")
            returnStatement.expr.accept(this)
        }
        buffer.appendLine(";")
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement) {
        val iterExpr = forIterationStatement.forIterationExpr
        if (iterExpr.target is RangeExpr) {
            val name = iterExpr.initializer.value
            appendIndented("for(Int32 ")
            buffer.append(name)
            buffer.append(" = ")
            iterExpr.target.begin.accept(this)
            buffer.append("; ")
            buffer.append(name)
            buffer.append(" <= ")
            iterExpr.target.end.accept(this)
            buffer.append("; ++")
            buffer.append(name)
            buffer.appendLine(")")
            appendIndentedLine("{")
            indentLevel++
            forIterationStatement.body.forEach { it.accept(this) }
            indentLevel--
            appendIndentedLine("}")
            return
        }

        appendIndentedLine("/* unsupported for-target; stub loop */")
        appendIndentedLine("for(;;)")
        appendIndentedLine("{")
        indentLevel++
        forIterationStatement.body.forEach { it.accept(this) }
        appendIndentedLine("break;")
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitUseStatement(useStatement: UseStatement) {
        appendIndentedLine("/* use ${useStatement.uri.value} */")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement) {
        appendIndentedLine("break;")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement) {
        appendIndentedLine("continue;")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {
        buffer.append("(")
        binaryExpr.leftExpr.accept(this)
        buffer.append(" ${binaryOpSymbol(binaryExpr.operator)} ")
        binaryExpr.rightExpr.accept(this)
        buffer.append(")")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {
        buffer.append(unaryExpr.operator.symbol.rep)
        unaryExpr.operand.accept(this)
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {
        assignmentExpr.target.accept(this)
        buffer.append(" = ")
        assignmentExpr.value.accept(this)
    }

    /**
     * Best-effort printf format for a single trace/print argument.
     * Strings and string-returning calls use "%s"; everything else "%d"
     * (good enough for Int* in the baseline backend).
     */
    private fun printfFormatFor(expr: Expr): String {
        return when (expr) {
            is StringLiteral -> "%s"
            is FloatLiteral -> "%f"
            is IntegerLiteral -> "%d"
            is FunctionCallExpr -> {
                val ret = knownValueTypes[functionLikeName(expr.name)]
                formatForTypeName(ret)
            }
            is Identifier -> {
                when (expr.value) {
                    "true", "false" -> "%d"
                    else -> formatForTypeName(knownValueTypes[expr.value])
                }
            }
            else -> "%d"
        }
    }

    private fun formatForTypeName(typeName: String?): String {
        return when (typeName) {
            null -> "%d"
            "Str", "String" -> "%s"
            "Float32", "Float64", "Float" -> "%f"
            "Bool" -> "%d"
            else -> if (typeName.startsWith("Int") || typeName.startsWith("UInt")) "%d" else "%d"
        }
    }

    private fun isPrintLike(rawName: String): Boolean {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        return canonical in setOf("trace", "print", "println", "_trace_", "eprint")
    }

    private fun emitPrintCall(rawName: String, args: List<Expr>) {
        includeForIntrinsic(rawName)
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        // Kira's @trace is line-oriented in practice; treat it like println.
        val isPrintln = canonical == "println" || canonical == "trace" || canonical == "_trace_"
        val isEprint = canonical == "eprint"

        if (args.isEmpty()) {
            if (isPrintln) {
                buffer.append("println(\"\")")
            } else {
                buffer.append("print(\"\")")
            }
            return
        }

        // Single-arg path covers the common case.
        val arg = args.first()
        val fmt = printfFormatFor(arg) + if (isPrintln) "\\n" else ""
        if (isEprint) {
            buffer.append("fprintf(stderr, \"")
            buffer.append(fmt)
            buffer.append("\", ")
            arg.accept(this)
            buffer.append(")")
        } else {
            buffer.append("print(\"")
            buffer.append(fmt)
            buffer.append("\", ")
            arg.accept(this)
            buffer.append(")")
        }
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        val rawName = functionLikeName(functionCallExpr.name)
        val args = buildList {
            functionCallExpr.positionalParameters.forEach { add(it.value) }
            functionCallExpr.namedParameters.forEach { add(it.value) }
        }
        if (isPrintLike(rawName)) {
            emitPrintCall(rawName, args)
            return
        }
        includeForIntrinsic(rawName)
        val functionName = mapIntrinsicName(rawName)
        buffer.append(functionName)
        buffer.append("(")
        args.forEachIndexed { index, arg ->
            if (index > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
    }

    override fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {
        val rawName = intrinsicExpr.intrinsicKey.name
        val args = intrinsicExpr.parameters ?: emptyList()
        if (isPrintLike(rawName)) {
            emitPrintCall(rawName, args)
            return
        }
        includeForIntrinsic(rawName)
        val mappedName = mapIntrinsicName(rawName)
        if (intrinsicExpr.parameters == null) {
            buffer.append(mappedName)
            return
        }
        buffer.append(mappedName)
        buffer.append("(")
        args.forEachIndexed { idx, expr ->
            if (idx > 0) buffer.append(", ")
            expr.accept(this)
        }
        buffer.append(")")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {
        compoundAssignmentExpr.left.accept(this)
        buffer.append(" ${binaryOpSymbol(compoundAssignmentExpr.operator)}= ")
        compoundAssignmentExpr.right.accept(this)
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {
        functionDeclParameterExpr.typeSpecifier.accept(this)
        buffer.append(" ")
        functionDeclParameterExpr.name.accept(this)
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {
        // Enum member access Color.RED -> COLOR_RED (if left is type-ish identifier)
        val origin = memberAccessExpr.origin
        val member = memberAccessExpr.member
        if (origin is Identifier && member is Identifier) {
            // Heuristic: PascalCase origin + SCREAMING member => enum constant
            if (origin.value.firstOrNull()?.isUpperCase() == true &&
                member.value.all { it.isUpperCase() || it == '_' || it.isDigit() }
            ) {
                buffer.append(toScreamingSnake(origin.value))
                buffer.append("_")
                buffer.append(member.value)
                return
            }
        }
        origin.accept(this)
        buffer.append(".")
        member.accept(this)
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {
        buffer.append("/* for ")
        buffer.append(forIterationExpr.initializer.value)
        buffer.append(" in ")
        forIterationExpr.target.accept(this)
        buffer.append(" */")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr) {
        buffer.append("(")
        rangeExpr.begin.accept(this)
        buffer.append(" .. ")
        rangeExpr.end.accept(this)
        buffer.append(")")
    }

    override fun visitArrayIndexExpr(arrayIndexExpr: ArrayIndexExpr) {
        arrayIndexExpr.originExpr.accept(this)
        buffer.append("[")
        arrayIndexExpr.indexExpr.accept(this)
        buffer.append("]")
    }

    override fun visitThrowExpr(throwExpr: ThrowExpr) {
        buffer.append("abort()")
    }

    override fun visitTryExpr(tryExpr: TryExpr) {
        appendIndentedLine("/* try/on: baseline backend lowers try-block only */")
        tryExpr.tryBlock.forEach { it.accept(this) }
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr) {
        // Member name alone; prefix applied by visitEnumDecl
        enumMemberExpr.name.accept(this)
    }

    override fun visitObjectInitExpr(objectInitExpr: ObjectInitExpr) {
        val typeName = typeNameOf(objectInitExpr.typeName)
        buffer.append("(")
        buffer.append(mapTypeName(typeName))
        buffer.append(") { ")
        objectInitExpr.positionalArgs.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(" }")
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {
        buffer.append("/* type-check */(")
        typeCheckExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {
        buffer.append("((")
        typeCastExpr.type.accept(this)
        buffer.append(") ")
        typeCastExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitNoExpr(noExpr: NoExpr) {
        // no-op
    }

    override fun visitWithExpr(withExpr: WithExpr) {
        buffer.append("{ ")
        withExpr.members.forEachIndexed { idx, member ->
            if (idx > 0) buffer.append(", ")
            member.accept(this)
        }
        buffer.append(" }")
    }

    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {
        functionCallNamedParameterExpr.value.accept(this)
    }

    override fun visitFunctionCallPositionalParameterExpr(
        functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr
    ) {
        functionCallPositionalParameterExpr.value.accept(this)
    }

    override fun visitWithExprMember(withExprMember: WithExprMember) {
        buffer.append(".")
        withExprMember.name.accept(this)
        buffer.append(" = ")
        withExprMember.value.accept(this)
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {
        buffer.append(integerLiteral.value)
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral) {
        buffer.append("\"")
        buffer.append(stringLiteral.value)
        buffer.append("\"")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {
        buffer.append(floatLiteral.value)
    }

    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {
        buffer.append("/* function literal */")
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral) {
        buffer.append("{ ")
        arrayLiteral.value.forEachIndexed { i, expr ->
            if (i > 0) buffer.append(", ")
            expr.accept(this)
        }
        buffer.append(" }")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        buffer.append("null")
    }

    override fun visitType(type: Type) {
        buffer.append(mapTypeName(typeNameOf(type)))
    }

    override fun visitIdentifier(identifier: Identifier) {
        buffer.append(identifier.value)
    }

    override fun visitVariableDecl(variableDecl: VariableDecl) {
        if (isMagicDecl(variableDecl)) {
            return
        }
        val typeName = typeNameOf(variableDecl.type)
        knownValueTypes[variableDecl.name.value] = typeName
        if (emittingClassMembers) {
            // Field only -- no initializer inside struct
            appendIndented("")
            variableDecl.type.accept(this)
            buffer.append(" ")
            variableDecl.name.accept(this)
            buffer.appendLine(";")
            return
        }
        appendIndented("")
        variableDecl.type.accept(this)
        buffer.append(" ")
        variableDecl.name.accept(this)
        if (variableDecl.value != null) {
            buffer.append(" = ")
            variableDecl.value!!.accept(this)
        }
        buffer.appendLine(";")
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl) {
        if (isMagicDecl(functionDecl)) {
            return
        }
        // Methods inside classes: skip body emission in baseline (no vtable yet)
        if (emittingClassMembers) {
            return
        }

        val functionName = functionLikeName(functionDecl.name)
        val returnTypeName = typeNameOf(functionDecl.def.returnTypeSpecifier)
        val returnsVoid = returnTypeName == "Void"
        knownValueTypes[functionName] = returnTypeName

        // Parameters are visible for print-format heuristics inside the body.
        functionDecl.def.parameters.forEach { param ->
            knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
        }

        appendIndented("")
        if (functionName == "main" && returnsVoid) {
            // Host entry: Int32 main(Void) per Jack guide
            buffer.append("Int32")
        } else {
            functionDecl.def.returnTypeSpecifier.accept(this)
        }
        buffer.append(" ")
        buffer.append(functionName)
        buffer.append("(")
        if (functionDecl.def.parameters.isEmpty()) {
            buffer.append("Void")
        } else {
            functionDecl.def.parameters.forEachIndexed { idx, parameter ->
                if (idx > 0) buffer.append(", ")
                parameter.accept(this)
            }
        }
        buffer.append(")")
        if (functionDecl.def.body == null) {
            buffer.appendLine(";")
            return
        }
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        functionDecl.def.body!!.forEach { it.accept(this) }
        if (functionName == "main" && returnsVoid) {
            appendIndentedLine("return 0;")
        }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitClassDecl(classDecl: ClassDecl) {
        if (isMagicDecl(classDecl)) {
            return
        }
        val className = typeNameOf(classDecl.name)
        // Fields only -- methods are free functions later (baseline)
        val fields = classDecl.members.filterIsInstance<VariableDecl>()

        appendIndented("typedef struct ")
        buffer.append(className)
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        if (fields.isEmpty()) {
            // Empty struct: one unused char to stay standard-compliant
            appendIndentedLine("Utf8 _empty;")
        } else {
            emittingClassMembers = true
            fields.forEach { it.accept(this) }
            emittingClassMembers = false
        }
        indentLevel--
        appendIndented("} ")
        buffer.append(className)
        buffer.appendLine(";")
        buffer.appendLine()
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl) {
        currentModuleUri = moduleDecl.uri.value
        appendIndentedLine("/* module ${moduleDecl.uri.value} */")
    }

    override fun visitEnumDecl(enumDecl: EnumDecl) {
        if (isMagicDecl(enumDecl)) {
            return
        }
        val typeName = enumDecl.name.value
        val prefix = toScreamingSnake(typeName)

        appendIndented("typedef enum ")
        buffer.append(typeName)
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        enumDecl.members.forEachIndexed { index, member ->
            val memberName = member.name.value
            appendIndented("")
            buffer.append(prefix)
            buffer.append("_")
            buffer.append(memberName)
            if (index < enumDecl.members.size - 1) {
                buffer.append(",")
            }
            buffer.appendLine()
        }
        indentLevel--
        appendIndented("} ")
        buffer.append(typeName)
        buffer.appendLine(";")
        buffer.appendLine()
    }

    override fun visitTraitDecl(traitDecl: TraitDecl) {
        if (isMagicDecl(traitDecl)) {
            return
        }
        appendIndentedLine("/* trait ${typeNameOf(traitDecl.name)}: no C lowering in baseline backend */")
    }

    override fun visitVariantDecl(variantDecl: VariantDecl) {
        if (isMagicDecl(variantDecl)) {
            return
        }
        appendIndentedLine("/* variant ${typeNameOf(variantDecl.name)}: no C lowering in baseline backend */")
    }

    override fun visitTypeAliasDecl(typeAliasDecl: TypeAliasDecl) {
        if (isMagicDecl(typeAliasDecl)) {
            return
        }
        appendIndented("typedef ")
        typeAliasDecl.target.accept(this)
        buffer.append(" ")
        when (val id = typeAliasDecl.alias.identifier) {
            is Identifier -> buffer.append(id.value)
            else -> typeAliasDecl.alias.accept(this)
        }
        buffer.appendLine(";")
    }
}
