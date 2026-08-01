package net.exoad.kira.compiler.backend.codegen.js

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.KiraCodeGenerator
import net.exoad.kira.compiler.backend.codegen.MinifyLanguage
import net.exoad.kira.compiler.backend.codegen.OutputMinifier
import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.OperatorIntrinsics
import net.exoad.kira.core.intrinsics.MagicIntrinsic
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files

/**
 * JavaScript backend.
 *
 * Emits one self-contained Node script:
 *  1. Runtime prelude (`js_generator.js`) -- the stdlib surface as plain JS
 *  2. User / non-magic declarations only -- `@_magic` and `kira:*` stdlib
 *     bodies are skipped; their runtime already lives in the prelude
 *
 * Design notes (the deltas from the C backend are the point):
 *  - Generics are erased: one class / function per template, type arguments
 *    dropped at the call site. No monomorphization.
 *  - Traits are erased: JS dispatch is duck-typed, so a trait-typed value is
 *    just the object. No interface structs, no vtables, no coercion.
 *  - ARC is a no-op: the GC owns memory. No kira_rc_* calls are emitted.
 *  - Magic receivers rewrite to prelude helpers where the JS shape differs
 *    (Str is a primitive string, Arr is a native Array); everything else is a
 *    natural method call on a runtime or user class.
 */
class KiraJSCodeGenerator(override val compilationUnit: CompilationUnit) : KiraCodeGenerator(compilationUnit) {
    companion object {
        /** Layer 1 -- Kira stdlib surface as plain JS (see js_generator.js). */
        const val TEMPLATE_FILE = "js_generator.js"
        const val DEFAULT_OUTPUT = "out.kira.js"
        /**
         * JS keywords + globals/builtins the codegen or runtime may reference
         * literally (Math.*, Object.freeze, process, ...): never renamed.
         */
        private val JS_RESERVED = setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "enum", "export", "extends",
            "false", "finally", "for", "function", "if", "import", "in",
            "instanceof", "new", "null", "return", "super", "switch", "this",
            "throw", "true", "try", "typeof", "var", "void", "while", "with",
            "yield", "let", "static", "await", "async",
            "Object", "Array", "Function", "String", "Number", "Boolean",
            "Symbol", "BigInt", "Math", "JSON", "Date", "RegExp", "Error",
            "Promise", "Map", "Set", "WeakMap", "WeakSet", "Proxy", "Reflect",
            "Intl", "ArrayBuffer", "DataView", "undefined", "NaN", "Infinity",
            "globalThis", "process", "require", "module", "exports", "console",
            "Buffer", "arguments", "freeze",
            "max", "min", "abs", "floor", "ceil", "round", "sqrt", "pow",
            "random", "trunc", "sign", "hypot", "cbrt", "clz32", "exp", "log",
            "main",
        )
        private lateinit var templateFileContents: String

        fun fetchTemplateFileContents(): String {
            if (!::templateFileContents.isInitialized) {
                // The stdlib owns its runtime: kira/js/ sits next to the modules
                // (see StdlibLayout). Resources remain a fallback for packaged jars.
                val fromStdlib = StdlibLayout.jsFile(TEMPLATE_FILE)
                val resource = Public::class.java.getResource("/$TEMPLATE_FILE")
                    ?: Public::class.java.getResource(TEMPLATE_FILE)
                templateFileContents = fromStdlib?.let { Files.readString(it) }
                    ?: resource?.readText()
                    ?: File("src/main/resources/$TEMPLATE_FILE").readText()
            }
            return templateFileContents
        }
    }

    private val buffer = StringBuilder()
    /**
     * User-declared identifiers the minifier may rename in the emitted user
     * layer. Registered where codegen creates names; missing a name only
     * leaves it readable, never breaks the build.
     */
    private val userSymbols = linkedSetOf<String>()
    private val discoveredMagicTypes by lazy {
        compilationUnit.collectIntrinsicMarkedTypeNames(MagicIntrinsic.name) +
            compilationUnit.allMagicTypes()
    }
    private val opaqueTypes by lazy {
        compilationUnit.collectIntrinsicMarkedTypeNames("_opaque") +
            compilationUnit.allOpaqueTypes()
    }
    private val externFunctions by lazy {
        compilationUnit.allExternFunctions()
    }
    /** Simple name -> Kira type name for method-rewrite decisions. */
    private val knownValueTypes = mutableMapOf<String, String>()
    /** Field name -> Kira type name (best-effort; last writer wins on collisions). */
    private val fieldTypes = mutableMapOf<String, String>()
    /** User class method return types: "Class.method" -> Kira type name. */
    private val methodReturnTypes = mutableMapOf<String, String>()
    /** User class name -> method names (for bare `method(args)` calls in bodies). */
    private val methodsByClass = mutableMapOf<String, MutableSet<String>>()
    /** Non-magic user class names (generic templates included -- erased in JS). */
    private val userClassNames = mutableSetOf<String>()
    /** Enum type names in the current unit -- int-like, keep direct operators. */
    private val enumTypeNames = mutableSetOf<String>()
    private var indentLevel = 0
    private var emittingClassMembers = false
    /** True while emitting a method body -- bare field names become this.field. */
    private var currentMethodClass: String? = null
    /** True on the `.member` side of MemberAccess -- no this-> rewrite. */
    private var suppressThisRewrite = false
    private var hasMain = false

    private val strMethods = setOf(
        "length", "isEmpty", "substring", "charAt", "contains",
        "startsWith", "endsWith", "split", "trim", "toLower", "toUpper",
        "equals", "hashCode",
    )
    private val numMethods = setOf("toInt32", "toInt64", "toFloat32", "toFloat64", "abs")
    private val numScalarTypes = setOf(
        "Int8", "Int16", "Int32", "Int64", "Int", "UInt8", "UInt16", "UInt32", "UInt64",
        "Float32", "Float64", "Float", "Num",
    )
    private val collectionTypes = setOf(
        "Arr", "List", "Map", "Set", "Stack", "Queue", "Deque", "Maybe", "Result",
    )

    /**
     * Pre-register every function / class signature so method-rewrite
     * decisions work regardless of source order (JS has no prototypes, but
     * the walk must still know what `shout` returns before `main` uses it).
     */
    private fun collectSignatures() {
        emittableSources().forEach { source ->
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is FunctionDecl -> stmt
                    is ClassDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                when (expr) {
                    is FunctionDecl -> {
                        if (!isMagicDecl(expr)) {
                            val name = functionLikeName(expr.name)
                            knownValueTypes[name] = typeNameOf(expr.def.returnTypeSpecifier)
                            if (name == "main") hasMain = true
                        }
                    }
                    is ClassDecl -> {
                        if (isMagicDecl(expr)) return@forEach
                        val base = baseTypeNameOf(expr.name)
                        if (isOpaqueTypeName(base)) return@forEach
                        userClassNames.add(base)
                        expr.members.filterIsInstance<VariableDecl>().forEach { field ->
                            fieldTypes[field.name.value] = typeNameOf(field.type)
                        }
                        expr.members.filterIsInstance<FunctionDecl>().forEach { method ->
                            if (method.isStub()) return@forEach
                            val mname = functionLikeName(method.name)
                            methodsByClass.getOrPut(base) { mutableSetOf() }.add(mname)
                            methodReturnTypes["$base.$mname"] = typeNameOf(method.def.returnTypeSpecifier)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * One-shot emit of the whole compilation unit into [outputPath].
     *
     * By default the user layer (everything after the runtime prelude) is
     * minified and obfuscated via [OutputMinifier]; the prelude stays
     * byte-identical and readable. `GeneratedProvider.minifyOutput = false`
     * (the `--readable` CLI flag, or `build.minify: false`) restores the
     * pretty formatting.
     */
    fun generate(outputPath: String = DEFAULT_OUTPUT): String {
        clean()
        val source = buildTranslationUnit()
        val written = if (GeneratedProvider.minifyOutput) minifyWritten(source) else source
        File(outputPath).writeText(written)
        return written
    }

    /** Minify + obfuscate the user layer, keeping the prelude untouched. */
    private fun minifyWritten(source: String): String {
        val marker = "// __KIRA_JS_PRELUDE_END__"
        val idx = source.lastIndexOf(marker)
        require(idx >= 0) { "JS prelude end marker not found in emitted source" }
        val cut = idx + marker.length
        val prelude = source.substring(0, cut)
        val user = source.substring(cut)
        val reserved = OutputMinifier.extractIdentifiers(fetchTemplateFileContents()) +
            JS_RESERVED
        val rename = OutputMinifier.buildRenameMap(collectUserSymbols(), reserved)
        return prelude + "\n" + OutputMinifier.minify(MinifyLanguage.JS, user, rename)
    }

    /**
     * Every identifier codegen created while emitting the user layer: user
     * class names, method names, and field names (collected from the
     * signature registries) plus everything registered during the walk.
     */
    private fun collectUserSymbols(): Set<String> {
        userSymbols.addAll(userClassNames)
        userSymbols.addAll(fieldTypes.keys)
        methodsByClass.values.forEach { userSymbols.addAll(it) }
        return userSymbols.toSet()
    }

    /** Build JS text without writing a file -- used by tests. */
    fun emitToString(): String {
        clean()
        return buildTranslationUnit()
    }

    /**
     * Pre-register enum type names before the statement walk. Source order can
     * put `main` before the enum declaration, and operator lowering must know
     * a type is an int-like enum even before its decl is emitted.
     */
    private fun collectEnumTypes() {
        emittableSources().forEach { source ->
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is EnumDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (expr is EnumDecl && !isMagicDecl(expr)) {
                    enumTypeNames.add(expr.name.value)
                }
            }
        }
    }

    private fun buildTranslationUnit(): String {
        // Layer 1 -- stdlib runtime, then Layer 2 -- user program.
        buffer.append(fetchTemplateFileContents().trimEnd())
        buffer.appendLine()
        buffer.appendLine()

        // Ensure @_opaque / @_extern marks are registered even if semantics skipped apply().
        harvestForeignMarks()
        collectSignatures()
        collectEnumTypes()

        emittableSources().forEach { source ->
            visitRootASTNode(source.ast)
        }

        // The C backend gets `main` from the host C runtime; Node has no entry
        // convention, so a Kira `main` is invoked explicitly at the end.
        if (hasMain) {
            buffer.appendLine()
            appendIndentedLine("main();")
        }

        return buffer.toString()
    }

    /** Pull @_opaque / @_extern from parser marks into CompilationUnit registries. */
    private fun harvestForeignMarks() {
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            val marks = runCatching { source.astIntrinsicMarked }.getOrNull() ?: return@forEach
            marks.forEach { (node, intrinsics) ->
                val names = intrinsics.map { it.name }.toSet()
                if ("_opaque" in names) {
                    when (node) {
                        is ClassDecl -> compilationUnit.registerOpaqueType(baseTypeNameOf(node.name))
                        is TypeAliasDecl -> {
                            val n = (node.alias.identifier as? Identifier)?.value
                            if (n != null) compilationUnit.registerOpaqueType(n)
                        }
                        else -> {}
                    }
                }
                if ("_extern" in names && node is FunctionDecl) {
                    val kiraName = functionLikeName(node.name)
                    if (compilationUnit.externCNameOrNull(kiraName) == null) {
                        compilationUnit.registerExternFunction(kiraName, kiraName)
                    }
                }
            }
            // Also walk AST for class/function decls that carry marks only on nested nodes
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is ClassDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                val node = expr ?: return@forEach
                val nodeMarks = runCatching {
                    compilationUnit.allSources().flatMap { s ->
                        runCatching { s.astIntrinsicMarked }.getOrNull().orEmpty().entries
                            .filter { it.key === node }
                            .flatMap { it.value.map { m -> m.name } }
                    }
                }.getOrNull().orEmpty()
                if ("_opaque" in nodeMarks && node is ClassDecl) {
                    compilationUnit.registerOpaqueType(baseTypeNameOf(node.name))
                }
                if ("_extern" in nodeMarks && node is FunctionDecl) {
                    val kiraName = functionLikeName(node.name)
                    if (compilationUnit.externCNameOrNull(kiraName) == null) {
                        compilationUnit.registerExternFunction(kiraName, kiraName)
                    }
                }
            }
        }
    }

    private fun shouldSkipSource(source: SourceContext): Boolean {
        val uri = runCatching { source.getModuleUri() }.getOrNull() ?: return true
        return uri == "kira:stl" || uri.startsWith("kira:")
    }

    /**
     * True when a stdlib source carries real Kira code that must be emitted.
     * `@_magic` declarations are typechecker-only signatures (the runtime
     * prelude defines them); a stdlib module with non-magic function bodies
     * is walked like user code so the real implementations ship in the output.
     */
    private fun hasEmittableStdlibFunctions(source: SourceContext): Boolean {
        if (!shouldSkipSource(source)) return false
        return source.ast.statements.any { stmt ->
            val expr: Any? = when (stmt) {
                is FunctionDecl -> stmt
                is Statement -> stmt.expr
                else -> null
            }
            expr is FunctionDecl && !isMagicDecl(expr)
        }
    }

    /** Sources whose declarations may contribute to the emitted program. */
    private fun emittableSources(): List<SourceContext> {
        return compilationUnit.allSources().filter { source ->
            !shouldSkipSource(source) || hasEmittableStdlibFunctions(source)
        }
    }

    private fun isMagicDecl(decl: Decl): Boolean {
        if (declHasIntrinsic(decl, "_magic")) {
            return true
        }
        val name = when (decl) {
            is ClassDecl -> baseTypeNameOf(decl.name)
            is EnumDecl -> decl.name.value
            is TraitDecl -> baseTypeNameOf(decl.name)
            is VariantDecl -> baseTypeNameOf(decl.name)
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

    private fun declHasIntrinsic(decl: Decl, intrinsicName: String): Boolean {
        compilationUnit.allSources().forEach { source ->
            val marks = runCatching { source.astIntrinsicMarked }.getOrNull() ?: return@forEach
            val arr = marks[decl] ?: return@forEach
            if (arr.any { it.name == intrinsicName }) {
                return true
            }
        }
        return false
    }

    private fun isOpaqueTypeName(typeName: String): Boolean = opaqueTypes.contains(typeName)

    private fun isExternFunction(name: String): Boolean = externFunctions.containsKey(name)

    private fun baseTypeNameOf(type: Type): String {
        return when (val id = type.identifier) {
            is Identifier -> id.value
            else -> "_anon"
        }
    }

    /** JS erases generics, so a type always lowers to its base name. */
    private fun typeNameOf(type: Type): String = baseTypeNameOf(type)

    private fun functionLikeName(expr: Expr): String {
        return when (expr) {
            is Identifier -> expr.value
            is IntrinsicExpr -> expr.intrinsicKey.name
            else -> "_anon"
        }
    }

    private fun isStrType(typeName: String): Boolean = typeName == "Str" || typeName == "String"

    private fun isNumScalar(typeName: String): Boolean = typeName in numScalarTypes

    private fun isCollectionType(typeName: String): Boolean = typeName in collectionTypes

    /**
     * Best-effort receiver type of an expression, used to pick method-call
     * rewrites. Mirrors the C backend's inference: locals, fields, function
     * returns, and object-init types.
     */
    private fun receiverTypeOf(expr: Expr): String? {
        return when (expr) {
            is Identifier -> knownValueTypes[expr.value] ?: fieldTypes[expr.value]
            is MemberAccessExpr -> {
                val memberName = (expr.member as? Identifier)?.value
                memberName?.let { fieldTypes[it] } ?: knownValueTypes[memberName]
            }
            is FunctionCallExpr -> {
                val name = functionLikeName(expr.name)
                knownValueTypes[name]?.let { return it }
                methodReturnTypes[name]?.let { return it }
                // receiver.method(args): resolve the method's return type from
                // the receiver's class so chained calls rewrite correctly.
                val n = expr.name
                if (n is MemberAccessExpr) {
                    val m = (n.member as? Identifier)?.value ?: return null
                    val recv = receiverTypeOf(n.origin) ?: return null
                    methodReturnTypes["$recv.$m"]?.let { return it }
                }
                null
            }
            is ObjectInitExpr -> typeNameOf(expr.typeName)
            else -> null
        }
    }

    private fun binaryOpSymbol(op: BinaryOp): String {
        return op.symbol.joinToString(separator = "") { it.rep.toString() }
    }

    /**
     * Types that keep direct JS operators. Anything else statically known
     * (user classes, enums, opaque handles, containers) is a candidate for
     * an `@op_*` overload.
     */
    private val primitiveTypeNames = setOf(
        "Int8", "Int16", "Int32", "Int64",
        "Float32", "Float64", "Bool", "Char",
        "Str", "String", "Num", "Int", "Float", "Any"
    )

    private fun isKnownNonPrimitive(expr: Expr): Boolean {
        val t = receiverTypeOf(expr) ?: return false
        // Enum types are int-like value types: direct JS operators work and no
        // @op_* definition is ever emitted for them.
        return t !in primitiveTypeNames && t !in enumTypeNames
    }

    /** Emit a call to an operator intrinsic (`op_add(...)`, ...). */
    private fun emitOperatorCall(opName: String, args: List<Expr>) {
        buffer.append(opName)
        buffer.append("(")
        args.forEachIndexed { index, arg ->
            if (index > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
    }

    /** True when an expression is statically a float-typed scalar. */
    private fun isFloatTyped(expr: Expr): Boolean {
        val floatTypes = setOf("Float32", "Float64", "Float")
        return when (expr) {
            is FloatLiteral -> true
            is IntegerLiteral -> false
            is Identifier -> {
                val t = knownValueTypes[expr.value] ?: fieldTypes[expr.value] ?: return false
                t in floatTypes
            }
            is MemberAccessExpr -> {
                val m = (expr.member as? Identifier)?.value ?: return false
                fieldTypes[m] in floatTypes
            }
            is FunctionCallExpr -> receiverTypeOf(expr) in floatTypes
            is ObjectInitExpr -> typeNameOf(expr.typeName) in floatTypes
            else -> false
        }
    }

    private fun appendIndented(value: String) {
        repeat(indentLevel) { buffer.append("    ") }
        buffer.append(value)
    }

    private fun appendIndentedLine(value: String = "") {
        appendIndented(value)
        buffer.appendLine()
    }

    /** Emit the empty-container factory for a magic collection type. */
    private fun emitEmptyContainerFactory(baseName: String) {
        when (baseName) {
            "Map" -> buffer.append("kira_map_new()")
            "Arr" -> buffer.append("[]")
            "List" -> buffer.append("kira_list_new()")
            "Set" -> buffer.append("kira_set_new()")
            "Stack" -> buffer.append("kira_stack_new()")
            "Queue" -> buffer.append("kira_queue_new()")
            "Deque" -> buffer.append("kira_deque_new()")
            "Maybe" -> buffer.append("kira_none()")
            "Result" -> buffer.append("kira_err(undefined)")
            else -> buffer.append("null")
        }
    }

    private fun isPrintLike(rawName: String): Boolean {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        return canonical in setOf("trace", "print", "println", "_trace_", "eprint")
    }

    private fun emitPrintCall(rawName: String, args: List<Expr>) {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        val fn = when {
            canonical == "eprint" -> "kira_eprint"
            canonical == "print" -> "kira_print"
            else -> "kira_trace" // trace / println / _trace_
        }
        buffer.append(fn)
        buffer.append("(")
        args.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
    }

    /** Math intrinsics lower straight to Math.* (no prelude helper needed). */
    private fun jsIntrinsic(rawName: String): String? {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        return when (canonical) {
            "sqrt" -> "Math.sqrt"
            "pow" -> "Math.pow"
            "floor" -> "Math.floor"
            "ceil" -> "Math.ceil"
            "round" -> "Math.round"
            "sin" -> "Math.sin"
            "cos" -> "Math.cos"
            "tan" -> "Math.tan"
            "abs" -> "Math.abs"
            "min" -> "Math.min"
            "max" -> "Math.max"
            else -> null
        }
    }

    private fun escapeJsString(value: String): String {
        val sb = StringBuilder()
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun clean() {
        buffer.clear()
        userSymbols.clear()
        knownValueTypes.clear()
        fieldTypes.clear()
        methodReturnTypes.clear()
        methodsByClass.clear()
        userClassNames.clear()
        enumTypeNames.clear()
        indentLevel = 0
        emittingClassMembers = false
        currentMethodClass = null
        suppressThisRewrite = false
        hasMain = false
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
        appendIndented("if (")
        ifSelectionStatement.expr.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        ifSelectionStatement.thenStatements.forEach { it.accept(this) }
        indentLevel--
        appendIndented("}")
        if (ifSelectionStatement.elseBranches.isEmpty()) {
            buffer.appendLine()
            return
        }
        ifSelectionStatement.elseBranches.forEach { branch ->
            buffer.append(" ")
            branch.accept(this)
        }
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {
        buffer.append("else if (")
        ifElseIfBranchNode.condition.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        ifElseIfBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        buffer.appendLine("else {")
        indentLevel++
        elseBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        appendIndented("while (")
        whileIterationStatement.condition.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        whileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        appendIndentedLine("do {")
        indentLevel++
        doWhileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndented("} while (")
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
            appendIndented("for (let ")
            buffer.append(name)
            buffer.append(" = ")
            iterExpr.target.begin.accept(this)
            buffer.append("; ")
            buffer.append(name)
            buffer.append(" <= ")
            iterExpr.target.end.accept(this)
            buffer.append("; ++")
            buffer.append(name)
            buffer.appendLine(") {")
            indentLevel++
            forIterationStatement.body.forEach { it.accept(this) }
            indentLevel--
            appendIndentedLine("}")
            return
        }

        appendIndentedLine("/* unsupported for-target; stub loop */")
        appendIndentedLine("for (;;) {")
        indentLevel++
        forIterationStatement.body.forEach { it.accept(this) }
        appendIndentedLine("break;")
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitUseStatement(useStatement: UseStatement) {
        appendIndentedLine("// use \"${useStatement.uri.value}\"")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement) {
        appendIndentedLine("break;")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement) {
        appendIndentedLine("continue;")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {
        val op = binaryExpr.operator
        // Non-primitive operands desugar to the op_* overload.
        if (OperatorIntrinsics.binaryName(op) != null &&
            (isKnownNonPrimitive(binaryExpr.leftExpr) || isKnownNonPrimitive(binaryExpr.rightExpr))
        ) {
            emitOperatorCall(
                OperatorIntrinsics.binaryName(op)!!,
                listOf(binaryExpr.leftExpr, binaryExpr.rightExpr)
            )
            return
        }
        // Kira int / int is integer division (C truncates toward zero); JS /
        // is float division, so non-float operands must be truncated.
        if (op == BinaryOp.DIV &&
            !isFloatTyped(binaryExpr.leftExpr) &&
            !isFloatTyped(binaryExpr.rightExpr)
        ) {
            buffer.append("Math.trunc((")
            binaryExpr.leftExpr.accept(this)
            buffer.append(" / ")
            binaryExpr.rightExpr.accept(this)
            buffer.append("))")
            return
        }
        buffer.append("(")
        binaryExpr.leftExpr.accept(this)
        buffer.append(" ${binaryOpSymbol(op)} ")
        binaryExpr.rightExpr.accept(this)
        buffer.append(")")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {
        val opName = OperatorIntrinsics.unaryName(unaryExpr.operator)
        if (opName != null && isKnownNonPrimitive(unaryExpr.operand)) {
            emitOperatorCall(opName, listOf(unaryExpr.operand))
            return
        }
        buffer.append(unaryExpr.operator.symbol.rep)
        unaryExpr.operand.accept(this)
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {
        assignmentExpr.target.accept(this)
        buffer.append(" = ")
        assignmentExpr.value.accept(this)
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        val nameExpr = functionCallExpr.name
        val args = buildList {
            functionCallExpr.positionalParameters.forEach { add(it.value) }
            functionCallExpr.namedParameters.forEach { add(it.value) }
        }

        // Method call: receiver.method(args)
        if (nameExpr is MemberAccessExpr) {
            val methodName = (nameExpr.member as? Identifier)?.value ?: "_anon"
            val recvType = receiverTypeOf(nameExpr.origin)

            // Str is a JS primitive: rewrite to kira_str_* helpers.
            if (recvType != null && isStrType(recvType) && methodName in strMethods) {
                buffer.append("kira_str_")
                buffer.append(methodName)
                buffer.append("(")
                nameExpr.origin.accept(this)
                args.forEach { arg ->
                    buffer.append(", ")
                    arg.accept(this)
                }
                buffer.append(")")
                return
            }

            // Num scalars are JS numbers: conversions are identity, abs is Math.abs.
            if (recvType != null && isNumScalar(recvType) && methodName in numMethods) {
                val helper = when (methodName) {
                    "toInt32" -> "kira_num_toInt32"
                    "toInt64" -> "kira_num_toInt64"
                    "toFloat32" -> "kira_num_toFloat32"
                    "toFloat64" -> "kira_num_toFloat64"
                    "abs" -> "Math.abs"
                    else -> null
                }
                if (helper != null) {
                    buffer.append(helper)
                    buffer.append("(")
                    nameExpr.origin.accept(this)
                    buffer.append(")")
                    return
                }
            }

            // Arr is a JS Array: size/get/set/contains/clone rewrite to array ops.
            if (recvType == "Arr") {
                when (methodName) {
                    "get" -> {
                        nameExpr.origin.accept(this)
                        buffer.append("[")
                        args.getOrNull(0)?.accept(this)
                        buffer.append("]")
                        return
                    }
                    "size" -> {
                        nameExpr.origin.accept(this)
                        buffer.append(".length")
                        return
                    }
                    "isEmpty" -> {
                        nameExpr.origin.accept(this)
                        buffer.append(".length === 0")
                        return
                    }
                    "contains" -> {
                        nameExpr.origin.accept(this)
                        buffer.append(".includes(")
                        args.getOrNull(0)?.accept(this)
                        buffer.append(")")
                        return
                    }
                    "clone" -> {
                        nameExpr.origin.accept(this)
                        buffer.append(".slice()")
                        return
                    }
                    "set" -> {
                        buffer.append("(")
                        nameExpr.origin.accept(this)
                        buffer.append("[")
                        args.getOrNull(0)?.accept(this)
                        buffer.append("] = ")
                        args.getOrNull(1)?.accept(this)
                        buffer.append(")")
                        return
                    }
                }
            }

            // Natural dispatch: user classes, runtime container classes, tuples,
            // and erased trait receivers all lower to a plain method call.
            nameExpr.origin.accept(this)
            buffer.append(".")
            buffer.append(methodName)
            buffer.append("(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }

        // Free-function / intrinsic / bare-method call.
        val rawName = functionLikeName(nameExpr)
        if (isPrintLike(rawName)) {
            emitPrintCall(rawName, args)
            return
        }
        if (rawName == "assert") {
            buffer.append("kira_assert(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        // Bare method call inside a class body: `method(args)` -> `this.method(args)`.
        val cls = currentMethodClass
        if (cls != null && methodsByClass[cls]?.contains(rawName) == true) {
            buffer.append("this.")
            buffer.append(rawName)
            buffer.append("(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        val math = jsIntrinsic(rawName)
        if (math != null) {
            buffer.append(math)
            buffer.append("(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        // Plain function. Generic type arguments are erased: id<Int32>(x) -> id(x).
        buffer.append(rawName)
        buffer.append("(")
        args.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
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
        if (rawName == "assert") {
            buffer.append("kira_assert(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        val math = jsIntrinsic(rawName)
        if (math != null) {
            buffer.append(math)
            buffer.append("(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        if (intrinsicExpr.parameters == null) {
            buffer.append(rawName)
            return
        }
        buffer.append(rawName)
        buffer.append("(")
        args.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {
        val opName = OperatorIntrinsics.binaryName(compoundAssignmentExpr.operator)
        // a += b on a non-primitive becomes a = op_add(a, b).
        if (opName != null && isKnownNonPrimitive(compoundAssignmentExpr.left)) {
            val target = compoundAssignmentExpr.left
            target.accept(this)
            buffer.append(" = ")
            emitOperatorCall(opName, listOf(target, compoundAssignmentExpr.right))
            return
        }
        // Same integer-division rule as visitBinaryExpr: /= on int operands
        // must truncate. Emitted as an explicit assignment (left twice; fine
        // for identifier targets, the shape the language actually uses).
        if (compoundAssignmentExpr.operator == BinaryOp.DIV &&
            !isFloatTyped(compoundAssignmentExpr.left) &&
            !isFloatTyped(compoundAssignmentExpr.right)
        ) {
            compoundAssignmentExpr.left.accept(this)
            buffer.append(" = Math.trunc((")
            compoundAssignmentExpr.left.accept(this)
            buffer.append(" / ")
            compoundAssignmentExpr.right.accept(this)
            buffer.append("))")
            return
        }
        compoundAssignmentExpr.left.accept(this)
        buffer.append(" ${binaryOpSymbol(compoundAssignmentExpr.operator)}= ")
        compoundAssignmentExpr.right.accept(this)
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {
        // Types are erased in JS; only the name survives.
        functionDeclParameterExpr.name.accept(this)
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {
        val origin = memberAccessExpr.origin
        val member = memberAccessExpr.member
        // Enum member access Color.RED -> Color.RED (left is type-ish identifier).
        if (origin is Identifier && member is Identifier) {
            if (origin.value.firstOrNull()?.isUpperCase() == true &&
                member.value.all { it.isUpperCase() || it == '_' || it.isDigit() }
            ) {
                buffer.append(origin.value)
                buffer.append(".")
                buffer.append(member.value)
                return
            }
        }
        origin.accept(this)
        buffer.append(".")
        val prev = suppressThisRewrite
        suppressThisRewrite = true
        member.accept(this)
        suppressThisRewrite = prev
    }

    override fun visitIdentifier(identifier: Identifier) {
        val name = identifier.value
        // Inside a method body, bare field names become this.field.
        val cls = currentMethodClass
        if (cls != null &&
            !suppressThisRewrite &&
            fieldTypes.containsKey(name) &&
            !knownValueTypes.containsKey(name)
        ) {
            buffer.append("this.")
            buffer.append(name)
            return
        }
        buffer.append(name)
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {
        buffer.append("/* for ")
        buffer.append(forIterationExpr.initializer.value)
        buffer.append(" in ")
        forIterationExpr.target.accept(this)
        buffer.append(" */")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr) {
        buffer.append("/* (")
        rangeExpr.begin.accept(this)
        buffer.append(" .. ")
        rangeExpr.end.accept(this)
        buffer.append(") */")
    }

    override fun visitArrayIndexExpr(arrayIndexExpr: ArrayIndexExpr) {
        // Arr is a native array: index directly.
        arrayIndexExpr.originExpr.accept(this)
        buffer.append("[")
        arrayIndexExpr.indexExpr.accept(this)
        buffer.append("]")
    }

    override fun visitThrowExpr(throwExpr: ThrowExpr) {
        buffer.append("throw ")
        throwExpr.value.accept(this)
    }

    override fun visitTryExpr(tryExpr: TryExpr) {
        appendIndentedLine("try {")
        indentLevel++
        tryExpr.tryBlock.forEach { it.accept(this) }
        indentLevel--
        appendIndented("} catch (")
        buffer.append(tryExpr.exceptionName?.value ?: "e")
        buffer.appendLine(") {")
        indentLevel++
        tryExpr.handlerBlock.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr) {
        enumMemberExpr.name.accept(this)
    }

    override fun visitObjectInitExpr(objectInitExpr: ObjectInitExpr) {
        val baseName = baseTypeNameOf(objectInitExpr.typeName)
        if (objectInitExpr.positionalArgs.isEmpty()) {
            when (baseName) {
                "Map", "List", "Set", "Stack", "Queue", "Deque" -> {
                    emitEmptyContainerFactory(baseName)
                    return
                }
                "Arr" -> {
                    buffer.append("[]")
                    return
                }
                "Maybe" -> {
                    buffer.append("kira_none()")
                    return
                }
                "Result" -> {
                    buffer.append("kira_err(undefined)")
                    return
                }
            }
        }
        val ctorName = when (baseName) {
            "Tuple0" -> "KiraTuple0"
            "Tuple1" -> "KiraTuple1"
            "Tuple2", "Pair" -> "KiraTuple2"
            "Tuple3" -> "KiraTuple3"
            "Tuple4" -> "KiraTuple4"
            "Tuple5" -> "KiraTuple5"
            "Tuple6" -> "KiraTuple6"
            "Tuple7" -> "KiraTuple7"
            "Tuple8" -> "KiraTuple8"
            "Tuple9" -> "KiraTuple9"
            "Exception" -> "KiraException"
            else -> baseName
        }
        buffer.append("new ")
        buffer.append(ctorName)
        buffer.append("(")
        objectInitExpr.positionalArgs.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {
        // Runtime type checks are meaningless in untyped JS; keep the value.
        buffer.append("(")
        typeCheckExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {
        // Casts are erased; JS is dynamic. Keep the value.
        buffer.append("(")
        typeCastExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitNoExpr(noExpr: NoExpr) {
        // no-op
    }

    override fun visitWithExpr(withExpr: WithExpr) {
        buffer.append("({ ")
        withExpr.members.forEachIndexed { idx, member ->
            if (idx > 0) buffer.append(", ")
            member.accept(this)
        }
        buffer.append(" })")
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
        withExprMember.name.accept(this)
        buffer.append(": ")
        withExprMember.value.accept(this)
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {
        buffer.append(integerLiteral.value)
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral) {
        buffer.append("\"")
        buffer.append(escapeJsString(stringLiteral.value))
        buffer.append("\"")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {
        buffer.append(floatLiteral.value)
    }

    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {
        buffer.append("(")
        functionDefExpr.parameters.forEachIndexed { i, param ->
            if (i > 0) buffer.append(", ")
            buffer.append(param.name.value)
        }
        buffer.append(") => ")
        if (functionDefExpr.body == null) {
            buffer.append("{ /* noimpl */ }")
            return
        }
        buffer.appendLine("{")
        indentLevel++
        functionDefExpr.parameters.forEach { param ->
            knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
        }
        functionDefExpr.body!!.forEach { it.accept(this) }
        functionDefExpr.parameters.forEach { param ->
            knownValueTypes.remove(param.name.value)
        }
        indentLevel--
        appendIndented("}")
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral) {
        // Arr is a native array; an empty literal is just [].
        if (arrayLiteral.value.isEmpty()) {
            buffer.append("[]")
            return
        }
        buffer.append("[")
        arrayLiteral.value.forEachIndexed { i, expr ->
            if (i > 0) buffer.append(", ")
            expr.accept(this)
        }
        buffer.append("]")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        buffer.append("null")
    }

    override fun visitType(type: Type) {
        // Types are erased in JS output.
    }

    /** True when [expr] is the stdlib `null` global (or the legacy null literal). */
    private fun isNullValue(expr: Expr): Boolean {
        return expr is NullLiteral || (expr is Identifier && expr.value == "null")
    }

    /** Stdlib methods that already hand back a `Maybe`, keyed by receiver type. */
    private val maybeReturningMethods = mapOf(
        "Map" to setOf("get", "remove"),
        "Stack" to setOf("pop", "peek"),
        "Queue" to setOf("dequeue", "peek"),
        "Deque" to setOf("popFront", "popBack")
    )

    /** True when [expr] already evaluates to a `Maybe`, so wrapping would nest one. */
    private fun producesMaybe(expr: Expr): Boolean {
        if (receiverTypeOf(expr) == "Maybe") return true
        val call = expr as? FunctionCallExpr ?: return false
        val member = call.name as? MemberAccessExpr ?: return false
        val method = (member.member as? Identifier)?.value ?: return false
        val recv = receiverTypeOf(member.origin) ?: return false
        return maybeReturningMethods[recv]?.contains(method) == true
    }

    override fun visitVariableDecl(variableDecl: VariableDecl) {
        if (isMagicDecl(variableDecl)) {
            return
        }
        userSymbols.add(variableDecl.name.value)
        val typeName = typeNameOf(variableDecl.type)
        val name = variableDecl.name.value
        if (emittingClassMembers) {
            fieldTypes[name] = typeName
            return
        }
        knownValueTypes[name] = typeName
        val isMutable = variableDecl.modifiers.any { it == Modifier.MUTABLE }
        val kind = if (isMutable) "let" else "const"
        appendIndented("$kind $name")
        when {
            variableDecl.value != null -> {
                buffer.append(" = ")
                val value = variableDecl.value!!
                if (value is ObjectInitExpr && value.positionalArgs.isEmpty() && isCollectionType(typeName)) {
                    emitEmptyContainerFactory(baseTypeNameOf(value.typeName))
                } else if (typeName == "Maybe" && !producesMaybe(value)) {
                    // `p: Maybe<T> = null` -> kira_none(); any other value is the
                    // present case. Mirrors the C backend so null safety behaves
                    // the same on both targets.
                    if (isNullValue(value)) {
                        buffer.append("kira_none()")
                    } else {
                        buffer.append("kira_some(")
                        value.accept(this)
                        buffer.append(")")
                    }
                } else {
                    value.accept(this)
                }
            }
            isCollectionType(typeName) -> {
                buffer.append(" = ")
                emitEmptyContainerFactory(typeName)
            }
            userClassNames.contains(typeName) -> {
                buffer.append(" = null")
            }
            else -> {
                // Scalars stay undefined until assigned.
            }
        }
        buffer.appendLine(";")
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl) {
        if (isMagicDecl(functionDecl)) {
            return
        }
        // Methods inside classes: emitted in visitClassDecl.
        if (emittingClassMembers) {
            return
        }
        val kiraName = functionLikeName(functionDecl.name)
        if (isExternFunction(kiraName)) {
            appendIndentedLine("// @_extern $kiraName: foreign edge not supported on the JS backend yet")
            return
        }
        userSymbols.add(kiraName)
        functionDecl.def.parameters.forEach { userSymbols.add(it.name.value) }
        val returnTypeName = typeNameOf(functionDecl.def.returnTypeSpecifier)
        knownValueTypes[kiraName] = returnTypeName

        appendIndented("function ")
        buffer.append(kiraName)
        buffer.append("(")
        functionDecl.def.parameters.forEachIndexed { idx, param ->
            if (idx > 0) buffer.append(", ")
            buffer.append(param.name.value)
        }
        buffer.appendLine(") {")
        indentLevel++
        functionDecl.def.parameters.forEach { param ->
            knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
        }
        if (functionDecl.def.body != null) {
            functionDecl.def.body!!.forEach { it.accept(this) }
        } else {
            appendIndentedLine("// noimpl")
        }
        functionDecl.def.parameters.forEach { param ->
            knownValueTypes.remove(param.name.value)
        }
        indentLevel--
        appendIndentedLine("}")
        buffer.appendLine()
    }

    override fun visitClassDecl(classDecl: ClassDecl) {
        if (isMagicDecl(classDecl)) {
            return
        }
        val base = baseTypeNameOf(classDecl.name)
        if (isOpaqueTypeName(base)) {
            appendIndentedLine("// @_opaque $base: foreign edge not supported on the JS backend yet")
            return
        }
        val className = typeNameOf(classDecl.name)
        val fields = classDecl.members.filterIsInstance<VariableDecl>()
        val methods = classDecl.members.filterIsInstance<FunctionDecl>()
        val requireFields = fields.filter { field ->
            field.modifiers.any { it == Modifier.REQUIRE }
        }
        val defaultFields = fields.filter { field ->
            field.modifiers.none { it == Modifier.REQUIRE } && field.value != null
        }
        userSymbols.add(className)
        methods.forEach { userSymbols.add(functionLikeName(it.name)) }

        appendIndented("class ")
        buffer.append(className)
        buffer.appendLine(" {")
        indentLevel++

        // Constructor: require fields become positional params; defaulted
        // fields are set from their initializer inside the body.
        appendIndented("constructor(")
        requireFields.forEachIndexed { i, field ->
            if (i > 0) buffer.append(", ")
            buffer.append(field.name.value)
        }
        if (requireFields.isEmpty() && defaultFields.isEmpty()) {
            buffer.appendLine(") {}")
        } else {
            buffer.appendLine(") {")
            indentLevel++
            requireFields.forEach { field ->
                appendIndented("this.")
                buffer.append(field.name.value)
                buffer.append(" = ")
                buffer.append(field.name.value)
                buffer.appendLine(";")
            }
            defaultFields.forEach { field ->
                appendIndented("this.")
                buffer.append(field.name.value)
                buffer.append(" = ")
                field.value!!.accept(this)
                buffer.appendLine(";")
            }
            indentLevel--
            appendIndentedLine("}")
        }

        // Methods: plain prototype methods -- JS dispatch is natural.
        methods.forEach { method ->
            if (method.isStub()) return@forEach
            val methodName = functionLikeName(method.name)
            method.def.parameters.forEach { userSymbols.add(it.name.value) }
            appendIndented(methodName)
            buffer.append("(")
            method.def.parameters.forEachIndexed { idx, param ->
                if (idx > 0) buffer.append(", ")
                buffer.append(param.name.value)
            }
            buffer.appendLine(") {")
            indentLevel++
            method.def.parameters.forEach { param ->
                knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
            }
            val savedClass = currentMethodClass
            currentMethodClass = className
            method.def.body?.forEach { it.accept(this) }
            currentMethodClass = savedClass
            method.def.parameters.forEach { param ->
                knownValueTypes.remove(param.name.value)
            }
            indentLevel--
            appendIndentedLine("}")
        }

        indentLevel--
        appendIndentedLine("}")
        buffer.appendLine()
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl) {
        appendIndentedLine("// module \"${moduleDecl.uri.value}\"")
    }

    override fun visitEnumDecl(enumDecl: EnumDecl) {
        if (isMagicDecl(enumDecl)) {
            return
        }
        userSymbols.add(enumDecl.name.value)
        enumTypeNames.add(enumDecl.name.value)
        enumDecl.members.forEach { userSymbols.add(it.name.value) }
        appendIndented("const ")
        buffer.append(enumDecl.name.value)
        buffer.append(" = Object.freeze({ ")
        enumDecl.members.forEachIndexed { index, member ->
            if (index > 0) buffer.append(", ")
            buffer.append(member.name.value)
            buffer.append(": ")
            buffer.append(index)
        }
        buffer.appendLine(" });")
    }

    override fun visitTraitDecl(traitDecl: TraitDecl) {
        // Traits are erased at runtime -- duck typing does the dispatch.
        appendIndentedLine("// trait ${baseTypeNameOf(traitDecl.name)}")
    }

    override fun visitVariantDecl(variantDecl: VariantDecl) {
        appendIndentedLine("// variant ${typeNameOf(variantDecl.name)}: no JS lowering in baseline backend")
    }

    override fun visitTypeAliasDecl(typeAliasDecl: TypeAliasDecl) {
        if (isMagicDecl(typeAliasDecl)) {
            return
        }
        // Type aliases are erased at runtime.
        appendIndentedLine("// alias ${(typeAliasDecl.alias.identifier as? Identifier)?.value ?: "?"}")
    }
}
