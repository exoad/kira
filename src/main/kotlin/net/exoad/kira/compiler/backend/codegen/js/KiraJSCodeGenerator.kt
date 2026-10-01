package net.exoad.kira.compiler.backend.codegen.js

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.backend.codegen.KiraCodeGenerator
import net.exoad.kira.compiler.backend.codegen.MinifyLanguage
import net.exoad.kira.compiler.backend.codegen.ModuleFunctionScopes
import net.exoad.kira.compiler.backend.codegen.OutputMinifier
import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import net.exoad.kira.compiler.backend.codegen.UserClassTable
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.NamedArguments
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
    /** Free function name -> Kira parameter names, for binding named arguments. */
    private val functionParamNames = mutableMapOf<String, List<String>>()
    /**
     * Which function names each module's calls reach before the ambient
     * magic names (its own members, then its `use`d modules' `pub` ones).
     */
    private val functionScopes: ModuleFunctionScopes by lazy {
        ModuleFunctionScopes.collect(compilationUnit) { isMagicDecl(it) }
    }
    /**
     * The module whose code is being emitted, so a call in it resolves in
     * that module's scope; null outside any module's code, where a call
     * falls back to the intrinsic table.
     */
    private var callerModuleUri: String? = null
    /** "Class.method" -> parameter names. */
    private val methodParamNames = mutableMapOf<String, List<String>>()
    /** Method simple name -> the distinct parameter-name lists declared under it (classes and traits). */
    private val methodParamNamesBySimpleName = mutableMapOf<String, MutableSet<List<String>>>()
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
        val tableDecls = mutableListOf<ClassDecl>()
        try {
            collectSignaturesInto(tableDecls)
        } finally {
            classTable = UserClassTable(tableDecls)
        }
    }

    private fun collectSignaturesInto(tableDecls: MutableList<ClassDecl>) {
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
                            functionParamNames[name] = expr.def.parameters.map { it.name.value }
                            if (name == "main") hasMain = true
                            if (expr.isIntrinsicOverload() && OperatorIntrinsics.isFreeOperatorName(name)) {
                                freeOperatorFns.add(name)
                            }
                        }
                    }
                    is ClassDecl -> {
                        if (isMagicDecl(expr)) return@forEach
                        val base = baseTypeNameOf(expr.name)
                        if (isOpaqueTypeName(base)) return@forEach
                        userClassNames.add(base)
                        tableDecls.add(expr)
                        expr.members.filterIsInstance<VariableDecl>().forEach { field ->
                            fieldTypes[field.name.value] = typeNameOf(field.type)
                            recordContainerTypeArgs(field.name.value, field.type)
                        }
                        expr.members.filterIsInstance<FunctionDecl>().forEach { method ->
                            if (method.isStub()) return@forEach
                            val mname = functionLikeName(method.name)
                            methodsByClass.getOrPut(base) { mutableSetOf() }.add(mname)
                            methodReturnTypes["$base.$mname"] = typeNameOf(method.def.returnTypeSpecifier)
                            val paramNames = method.def.parameters.map { it.name.value }
                            methodParamNames["$base.$mname"] = paramNames
                            methodParamNamesBySimpleName.getOrPut(mname) { linkedSetOf() }.add(paramNames)
                        }
                    }
                    is TraitDecl -> {
                        if (isMagicDecl(expr)) return@forEach
                        expr.members.forEach { method ->
                            val mname = functionLikeName(method.name)
                            methodParamNamesBySimpleName.getOrPut(mname) { linkedSetOf() }
                                .add(method.def.parameters.map { it.name.value })
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
        val source = try {
            buildTranslationUnitOrFail()
        } catch (e: UnsupportedConstruct) {
            reportUnsupported(e)
        }
        val written = if (GeneratedProvider.minifyOutput) minifyWritten(source) else source
        File(outputPath).writeText(written)
        return written
    }

    // --- the AST contract (design 2.4): this backend fails loudly ---------------

    private val TARGET_NAME = "JS"

    /** [buildTranslationUnit], with every [UnsupportedConstruct] naming this target. */
    private fun buildTranslationUnitOrFail(): String {
        try {
            return buildTranslationUnit()
        } catch (e: UnsupportedConstruct) {
            throw e.withTarget(TARGET_NAME)
        }
    }

    /**
     * A construct this backend cannot lower surfaces as a compiler diagnostic
     * that names the construct and the target, and the compiler exits 1. The
     * CLI does not catch backend exceptions, and a stack trace is not a
     * diagnostic; tests use [emitToString], which lets the exception through.
     */
    private fun reportUnsupported(e: UnsupportedConstruct): Nothing {
        val where = compilationUnit.allSources().firstNotNullOfOrNull { source ->
            runCatching { source.astOrigins[e.node] }.getOrNull()
                ?.let { "${source.file}:${it.lineNumber}:${it.column}" }
        }
        Diagnostics.Logging.warn(
            "Kira",
            "\n-- Diagnostic Report: ${e.message}${where?.let { " (at $it)" } ?: ""}\n" +
                "   Compile this program with --target cpp, or rewrite the construct for the $TARGET_NAME backend."
        )
        kotlin.system.exitProcess(1)
    }

    /**
     * Every function or method that declares a default parameter value, by
     * simple name (the resolution named arguments use), with its parameter
     * list. This backend has no default lowering, so a call that leaves such
     * a parameter out cannot be emitted.
     */
    private val defaultedSignatures: Map<String, List<List<FunctionDeclParameterExpr>>> by lazy {
        val out = mutableMapOf<String, MutableList<List<FunctionDeclParameterExpr>>>()
        fun record(decl: FunctionDecl) {
            if (decl.def.parameters.none { it.defaultValue != null }) return
            out.getOrPut(functionLikeName(decl.name)) { mutableListOf() }
                .add(decl.def.parameters)
        }
        compilationUnit.allSources().forEach { source ->
            source.ast.statements.forEach { stmt ->
                when (val expr = (stmt as? Statement)?.expr ?: stmt) {
                    is FunctionDecl -> record(expr)
                    is ClassDecl -> expr.members.filterIsInstance<FunctionDecl>().forEach(::record)
                    is StructDecl -> expr.members.filterIsInstance<FunctionDecl>().forEach(::record)
                    is TraitDecl -> expr.members.forEach(::record)
                    else -> {}
                }
            }
        }
        out
    }

    /** The call-site forms of design 2.4 this backend refuses: `mut` arguments and omitted defaults. */
    private fun guardNewCallForms(call: FunctionCallExpr) {
        if (call.positionalParameters.any { it.isMut } || call.namedParameters.any { it.isMut }) {
            throw UnsupportedConstruct(call, "a call-site 'mut' argument", TARGET_NAME)
        }
        val callee = when (val name = call.name) {
            is MemberAccessExpr -> (name.member as? Identifier)?.value ?: return
            else -> functionLikeName(name)
        }
        val signatures = defaultedSignatures[callee] ?: return
        val omitted = signatures.firstNotNullOfOrNull { params -> omittedDefault(call, params) } ?: return
        throw UnsupportedConstruct(
            call,
            "a call to '$callee' that omits a default-valued parameter ('$omitted')",
            TARGET_NAME
        )
    }

    /**
     * The first default-valued parameter of [params] that [call] leaves
     * without an argument, or null. Slots fill the way [NamedArguments.bind]
     * fills them: positional arguments take the leading parameters and a
     * named argument takes the one it names, so a skipped middle default
     * (`g(1, c = 3)` against `(a, b = 2, c = 3)`) is found as well as a
     * trailing one. Measured before: only the trailing positions were
     * inspected. A slot the call cannot bind at all (an unknown name, a
     * missing required parameter) is the analyzer's diagnostic, not this one.
     */
    private fun omittedDefault(call: FunctionCallExpr, params: List<FunctionDeclParameterExpr>): String? {
        val filled = BooleanArray(params.size)
        for (index in call.positionalParameters.indices) {
            if (index < params.size) filled[index] = true
        }
        for (named in call.namedParameters) {
            val index = params.indexOfFirst { it.name.value == named.name.value }
            if (index >= 0) filled[index] = true
        }
        return params.withIndex()
            .firstOrNull { (index, param) -> !filled[index] && param.defaultValue != null }
            ?.value?.name?.value
    }

    /**
     * The type forms of design 2.4 this backend cannot lower: a const type
     * argument (`Arr<UInt8, 32>`, D6) and `mut T` inside a Tuple's arguments
     * (`Fx<Tuple1<mut T>, Void>`, D24). Both used to lower silently to the
     * erased base type, dropping the size and the mutability.
     */
    private fun guardNewTypeForms(type: Type) {
        if (type is ConstTypeArg) {
            throw UnsupportedConstruct(type, "a const type argument 'T<..., ${type.value.value}>'", TARGET_NAME)
        }
        if (type.isMutParam) {
            throw UnsupportedConstruct(type, "'mut' in an Fx parameter type 'Tuple<mut T>'", TARGET_NAME)
        }
        type.children.forEach(::guardNewTypeForms)
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
        return buildTranslationUnitOrFail()
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
            callerModuleUri = runCatching { source.getModuleUri() }.getOrNull()
            visitRootASTNode(source.ast)
        }
        callerModuleUri = null

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
        // The name fallback below covers a stdlib declaration whose mark was
        // missed. It must never apply to user code: a module's own `Ref` or
        // `parseInt64` shadows the ambient `kira:*` name and must be emitted.
        if (!isStdlibDecl(decl)) {
            return false
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

    /** True when [decl] is a top-level declaration of a `kira:*` stdlib source. */
    private fun isStdlibDecl(decl: Decl): Boolean {
        return compilationUnit.allSources().any { source ->
            shouldSkipSource(source) && runCatching { source.ast.statements }.getOrNull()?.any { stmt ->
                stmt === decl || (stmt is Statement && stmt.expr === decl)
            } == true
        }
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
        // Every lowering of a type passes through here, so this is where a
        // form this backend cannot lower is refused.
        guardNewTypeForms(type)
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
                    if (m == "copy" && classTable.hasCopy(recv)) return recv
                    // A method may be inherited: the nearest class that declares it answers.
                    val lineage = if (classTable.has(recv)) classTable.lineage(recv) else listOf(recv)
                    for (cls in lineage) {
                        methodReturnTypes["$cls.$m"]?.let { return it }
                    }
                }
                null
            }
            is ObjectInitExpr -> typeNameOf(expr.typeName)
            // `xs[i]` has the container's declared element type.
            is ArrayIndexExpr -> indexElementType(expr)
            // A member operator's result is its method's declared result.
            is BinaryExpr -> userClassOf(expr.leftExpr)?.let { cls ->
                when (expr.operator) {
                    BinaryOp.EQUALS, BinaryOp.NOT_EQUAL, BinaryOp.LESS_THAN, BinaryOp.GREATER_THAN,
                    BinaryOp.LESS_THAN_OR_EQUAL, BinaryOp.GREATER_THAN_OR_EQUAL -> "Bool"
                    else -> OperatorIntrinsics.memberName(expr.operator)?.let { memberResultType(cls, it) }
                }
            } ?: freeOperatorResultType(OperatorIntrinsics.binaryName(expr.operator), expr.leftExpr, expr.rightExpr)
            is UnaryExpr -> userClassOf(expr.operand)?.let { cls ->
                OperatorIntrinsics.memberName(expr.operator)?.let { memberResultType(cls, it) }
            } ?: freeOperatorResultType(OperatorIntrinsics.unaryName(expr.operator), expr.operand)
            else -> null
        }
    }

    /** Container-typed name -> its type arguments (`List<Str>` -> [Str]). */
    private val containerTypeArgs = mutableMapOf<String, List<String>>()

    private fun recordContainerTypeArgs(name: String, type: Type) {
        if (type.children.isEmpty()) return
        if (!isCollectionType(baseTypeNameOf(type))) return
        containerTypeArgs[name] = type.children.map { baseTypeNameOf(it) }
    }

    /** Declared element type of a container-typed expression, when it is a plain name. */
    private fun elementTypeOf(expr: Expr): String? {
        val name = when (expr) {
            is Identifier -> expr.value
            is MemberAccessExpr -> (expr.member as? Identifier)?.value
            else -> null
        } ?: return null
        return containerTypeArgs[name]?.firstOrNull()
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

    // ---- member operators, the synthesized `==`, `copy` and the index forms --------------------
    //
    // This backend reads the AST, not the typer's model: a receiver's class is the name-based
    // guess [receiverTypeOf] makes, and what a class declares or inherits comes from [classTable].
    // JS dispatches natively, so a member operator is a plain method call.

    private var classTable = UserClassTable(emptyList())

    /** The free operator functions the program declares (`fx @op_add: (a, b)`), by name. */
    private val freeOperatorFns = mutableSetOf<String>()
    private var tempSerial = 0

    /** True once the unit carries the `kira_eq_` helper the synthesized `_op_eq_` bodies call. */
    private var emittedEqHelper = false

    private fun userClassOf(expr: Expr): String? = receiverTypeOf(expr)?.takeIf { classTable.has(it) }

    private fun hasMemberOp(cls: String, member: String): Boolean =
        classTable.findMethod(cls, member)?.second?.isStub() == false

    private fun emitMemberCall(cls: String, method: String, receiver: Expr, args: List<Expr>): Boolean {
        if (!hasMemberOp(cls, method)) return false
        receiver.accept(this)
        buffer.append(".")
        buffer.append(method)
        buffer.append("(")
        args.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
        return true
    }

    /** The result of a free-form operator (`fx @op_add: (a, b)`) the program declares, for a non-primitive operand. */
    private fun freeOperatorResultType(freeName: String?, vararg operands: Expr): String? {
        if (freeName == null || freeName !in freeOperatorFns) return null
        if (operands.none { isKnownNonPrimitive(it) }) return null
        return knownValueTypes[freeName]
    }

    private fun memberResultType(cls: String, member: String): String? {
        val (decl, fn) = classTable.findMethod(cls, member) ?: return null
        if (fn.isStub()) return null
        return methodReturnTypes["$decl.$member"]
    }

    /** `a op b` where `a`'s class declares or inherits the member operator, or `==` by 1.2.5. */
    private fun tryEmitMemberBinary(op: BinaryOp, left: Expr, right: Expr): Boolean {
        val cls = userClassOf(left) ?: return false
        if (isNullValue(left) || isNullValue(right)) return false
        if (op == BinaryOp.EQUALS || op == BinaryOp.NOT_EQUAL) {
            val negated = op == BinaryOp.NOT_EQUAL
            if (negated && hasMemberOp(cls, UserClassTable.NEQ)) {
                return emitMemberCall(cls, UserClassTable.NEQ, left, listOf(right))
            }
            if (!hasMemberOp(cls, UserClassTable.EQ)) {
                // The free form keeps its lowering when the program still declares it.
                val free = OperatorIntrinsics.binaryName(op)
                if (free != null && free in freeOperatorFns) return false
                if (negated && "op_eq" in freeOperatorFns) {
                    buffer.append("(!")
                    emitOperatorCall("op_eq", listOf(left, right))
                    buffer.append(")")
                    return true
                }
            }
            if (negated) buffer.append("(!")
            if (hasMemberOp(cls, UserClassTable.EQ) || classTable.synthesizesEq(cls)) {
                // Every class that has a `==` carries it as `_op_eq_`: declared, or synthesized in its body.
                left.accept(this)
                buffer.append("._op_eq_(")
                right.accept(this)
                buffer.append(")")
            } else {
                // A mutable class compares identity.
                buffer.append("(")
                left.accept(this)
                buffer.append(" === ")
                right.accept(this)
                buffer.append(")")
            }
            if (negated) buffer.append(")")
            return true
        }
        val member = OperatorIntrinsics.memberName(op) ?: return false
        if (!hasMemberOp(cls, member)) return false
        return emitMemberCall(cls, member, left, listOf(right))
    }

    private fun tryEmitMemberUnary(op: UnaryOp, operand: Expr): Boolean {
        val cls = userClassOf(operand) ?: return false
        val member = OperatorIntrinsics.memberName(op) ?: return false
        if (!hasMemberOp(cls, member)) return false
        return emitMemberCall(cls, member, operand, emptyList())
    }

    /** `recv.copy(field = value, ...)` (1.2.3) on an immutable class nothing extends. */
    private fun tryEmitCopy(call: FunctionCallExpr, receiver: Expr): Boolean {
        val cls = userClassOf(receiver) ?: return false
        if (!classTable.hasCopy(cls)) return false
        val fields = classTable.allFields(cls)
        val values = arrayOfNulls<Expr>(fields.size)
        call.positionalParameters.forEachIndexed { i, p ->
            if (i >= fields.size) throw IllegalStateException("copy: '$cls' has only ${fields.size} fields")
            values[i] = p.value
        }
        call.namedParameters.forEach { n ->
            val at = fields.indexOfFirst { it.name.value == n.name.value }
            if (at < 0) throw IllegalStateException("copy: '$cls' has no field '${n.name.value}'")
            if (values[at] != null) throw IllegalStateException("copy: field '${n.name.value}' given twice")
            values[at] = n.value
        }
        // The receiver is evaluated first and once, as the arrow's argument; the new values after it.
        buffer.append("((kira_r) => Object.assign(Object.create(Object.getPrototypeOf(kira_r)), kira_r, { ")
        var first = true
        fields.forEachIndexed { i, f ->
            val v = values[i] ?: return@forEachIndexed
            if (!first) buffer.append(", ")
            first = false
            buffer.append(f.name.value)
            buffer.append(": ")
            v.accept(this)
        }
        buffer.append(" }))(")
        receiver.accept(this)
        buffer.append(")")
        return true
    }

    /** The type arguments recorded for a container-typed name (`Map<Str, Int32>` -> [Str, Int32]). */
    private fun containerArgsOf(expr: Expr): List<String> {
        val name = when (expr) {
            is Identifier -> expr.value
            is MemberAccessExpr -> (expr.member as? Identifier)?.value
            else -> null
        } ?: return emptyList()
        return containerTypeArgs[name] ?: emptyList()
    }

    /** The element type `xs[i]` reads: a class's `@_op_get_` result, a Map's value, a List's or Arr's element. */
    private fun indexElementType(index: ArrayIndexExpr): String? {
        val ot = receiverTypeOf(index.originExpr)
        if (ot != null && classTable.has(ot)) {
            val (decl, _) = classTable.findMethod(ot, UserClassTable.GET) ?: return null
            return methodReturnTypes["$decl.${UserClassTable.GET}"]
        }
        val targs = containerArgsOf(index.originExpr)
        return if (ot == "Map") targs.getOrNull(1) else targs.firstOrNull()
    }

    private fun isPureLocation(e: Expr): Boolean = when (e) {
        is Identifier -> true
        is MemberAccessExpr -> e.member is Identifier && isPureLocation(e.origin)
        else -> false
    }

    private fun isPureIndex(e: Expr): Boolean =
        e is IntegerLiteral || e is StringLiteral || isPureLocation(e)

    override fun visitPlaceAssignmentExpr(placeAssignmentExpr: PlaceAssignmentExpr) {
        val target = placeAssignmentExpr.target
        val op = placeAssignmentExpr.operator
        val value = placeAssignmentExpr.value
        when (target) {
            is ArrayIndexExpr -> if (op == null) emitIndexStore(target, value) else emitIndexCompound(target, op, value)
            is MemberAccessExpr -> {
                if (op == null) {
                    target.accept(this)
                    buffer.append(" = ")
                    value.accept(this)
                } else {
                    visitCompoundAssignmentExpr(CompoundAssignmentExpr(target, op, value))
                }
            }
            else -> super.visitPlaceAssignmentExpr(placeAssignmentExpr)
        }
    }

    /** `a[i] = v`: the class's `@_op_set_`, or the built-in container's store. */
    private fun emitIndexStore(target: ArrayIndexExpr, value: Expr) {
        val origin = target.originExpr
        val ot = receiverTypeOf(origin)
        if (ot != null && classTable.has(ot)) {
            if (!emitMemberCall(ot, UserClassTable.SET, origin, listOf(target.indexExpr, value))) {
                throw UnsupportedConstruct(target, "a[i] = v on '$ot', which has no @_op_set_", TARGET_NAME)
            }
            return
        }
        origin.accept(this)
        when (ot) {
            "Map" -> buffer.append(".put(")
            "List" -> buffer.append(".set(")
            else -> buffer.append("[")
        }
        target.indexExpr.accept(this)
        if (ot == "Map" || ot == "List") {
            buffer.append(", ")
            value.accept(this)
            buffer.append(")")
        } else {
            buffer.append("] = ")
            value.accept(this)
        }
    }

    /** `a[i] op= v` is `a[i] = a[i] op v`, with `a` and `i` evaluated once. */
    private fun emitIndexCompound(target: ArrayIndexExpr, op: BinaryOp, value: Expr) {
        val origin = target.originExpr
        val index = target.indexExpr
        val ot = receiverTypeOf(origin)
        val pureOrigin = isPureLocation(origin)
        val pureIndex = isPureIndex(index)
        if (pureOrigin && pureIndex) {
            emitSynthesizedIndexCompound(origin, index, op, value)
            return
        }
        val serial = tempSerial++
        val originTemp = "kira_ix_a$serial"
        val indexTemp = "kira_ix_i$serial"
        buffer.append("{ ")
        var o: Expr = origin
        var i: Expr = index
        if (!pureOrigin) {
            buffer.append("const $originTemp = ")
            origin.accept(this)
            buffer.append("; ")
            if (ot != null) knownValueTypes[originTemp] = ot
            containerArgsOf(origin).takeIf { it.isNotEmpty() }?.let { containerTypeArgs[originTemp] = it }
            o = Identifier(originTemp)
        }
        if (!pureIndex) {
            buffer.append("const $indexTemp = ")
            index.accept(this)
            buffer.append("; ")
            i = Identifier(indexTemp)
        }
        emitSynthesizedIndexCompound(o, i, op, value)
        buffer.append("; }")
        knownValueTypes.remove(originTemp)
        containerTypeArgs.remove(originTemp)
    }

    private fun emitSynthesizedIndexCompound(origin: Expr, index: Expr, op: BinaryOp, value: Expr) {
        val read = ArrayIndexExpr(origin, index)
        visitPlaceAssignmentExpr(
            PlaceAssignmentExpr(ArrayIndexExpr(origin, index), null, BinaryExpr(read, value, op))
        )
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
            is UnaryExpr -> isFloatTyped(expr.operand)
            // Arithmetic on a float propagates float-ness (mixed int/float
            // follows the same promotion the C backend gets for free). Without
            // this, `(value - a) / (b - a)` looked like integer division and
            // Math.trunc turned 0.4 into 0.
            is BinaryExpr ->
                isFloatTyped(expr.leftExpr) || isFloatTyped(expr.rightExpr)
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
        callerModuleUri = null
    }

    /**
     * Makes [decl]'s module the scope calls resolve in while its body is
     * emitted. A declaration no source owns at top level keeps the
     * enclosing scope.
     */
    private fun enterModuleOf(decl: Decl) {
        functionScopes.moduleOf(decl)?.let { callerModuleUri = it }
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
            // Legacy `for mut i: a..b` is inclusive (design 2.2); the spec
            // form `for i: T in a..b` is exclusive (D37).
            appendIndented("for (let ")
            buffer.append(name)
            buffer.append(" = ")
            iterExpr.target.begin.accept(this)
            buffer.append("; ")
            buffer.append(name)
            buffer.append(if (iterExpr.isLegacy) " <= " else " < ")
            iterExpr.target.end.accept(this)
            buffer.append("; ++")
            buffer.append(name)
            buffer.appendLine(") {")
            iterExpr.declaredType?.takeIf { !iterExpr.isLegacy }?.let { knownValueTypes[name] = typeNameOf(it) }
            indentLevel++
            forIterationStatement.body.forEach { it.accept(this) }
            indentLevel--
            appendIndentedLine("}")
            return
        }
        if (!iterExpr.isLegacy) {
            throw UnsupportedConstruct(iterExpr, "'for x: T in xs' over a container (only a range is lowered here)", TARGET_NAME)
        }

        // Iteration over a container: an Arr is a native array; KiraList and
        // KiraSet keep their elements in `.values`. (This used to be a stub
        // that ran the body exactly once.)
        val target = iterExpr.target
        val targetName = (target as? Identifier)?.value ?: target.toString()
        val name = iterExpr.initializer.value
        appendIndented("for (let $name of ")
        when (val recvType = receiverTypeOf(target)) {
            "Arr" -> target.accept(this)
            "List", "Set" -> {
                target.accept(this)
                buffer.append(".values")
            }
            else -> throw IllegalStateException(
                "for-in over '$targetName': the JS backend needs it to be an Arr, List or Set" +
                    (recvType?.let { " (it is '$it')" } ?: " (its type is not known here)")
            )
        }
        buffer.appendLine(") {")
        elementTypeOf(target)?.let { knownValueTypes[name] = it }
        indentLevel++
        forIterationStatement.body.forEach { it.accept(this) }
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
        if (tryEmitMemberBinary(op, binaryExpr.leftExpr, binaryExpr.rightExpr)) return
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
        if (tryEmitMemberUnary(unaryExpr.operator, unaryExpr.operand)) return
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

    /**
     * The call's arguments in parameter order (see [NamedArguments]). A
     * method callee resolves through the receiver's class when known, else
     * by simple name when every declaration of it agrees; a call with names
     * that cannot be bound is refused, never silently reordered.
     */
    private fun boundArguments(functionCallExpr: FunctionCallExpr): List<Expr> {
        val nameExpr = functionCallExpr.name
        val calleeName: String
        val parameterNames: List<String>?
        if (nameExpr is MemberAccessExpr) {
            calleeName = (nameExpr.member as? Identifier)?.value ?: "_anon"
            val recvType = receiverTypeOf(nameExpr.origin)
            parameterNames = methodParamNames["$recvType.$calleeName"]
                ?: methodParamNamesBySimpleName[calleeName]?.singleOrNull()
        } else {
            calleeName = functionLikeName(nameExpr)
            val cls = currentMethodClass
            parameterNames = if (cls != null && methodsByClass[cls]?.contains(calleeName) == true) {
                methodParamNames["$cls.$calleeName"]
            } else {
                functionParamNames[calleeName]
            }
        }
        return when (val binding = NamedArguments.bind(functionCallExpr, calleeName, parameterNames)) {
            is NamedArguments.Binding.Ordered -> binding.arguments
            is NamedArguments.Binding.Unbound -> throw IllegalStateException(binding.message)
        }
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        guardNewCallForms(functionCallExpr)
        val nameExpr = functionCallExpr.name
        if (nameExpr is MemberAccessExpr) {
            val called = (nameExpr.member as? Identifier)?.value
            if (called == "copy" && tryEmitCopy(functionCallExpr, nameExpr.origin)) return
        }
        val args = boundArguments(functionCallExpr)
        // `a.@_op_eq_(b)` on a class that declares none is the synthesized `==` (1.2.5).
        if (nameExpr is MemberAccessExpr && (nameExpr.member as? Identifier)?.value == UserClassTable.EQ &&
            args.size == 1
        ) {
            val cls = userClassOf(nameExpr.origin)
            if (cls != null && !hasMemberOp(cls, UserClassTable.EQ)) {
                if (classTable.synthesizesEq(cls)) {
                    nameExpr.origin.accept(this)
                    buffer.append("._op_eq_(")
                } else {
                    buffer.append("(")
                    nameExpr.origin.accept(this)
                    buffer.append(" === ")
                }
                args[0].accept(this)
                buffer.append(")")
                return
            }
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
        // A function in the caller's scope (its module's own, or a `pub` one
        // of a module it `use`s) shadows the ambient magic name of the same
        // spelling: a user `fx ceil` is called, not Math.ceil, and a user
        // `fx assert` is called, not the runtime's kira_assert (the C backend
        // asks the same table, so both pick the same callee). A function some
        // other module declares is out of scope, and Math.ceil stands.
        val declaredHere = functionScopes.resolves(callerModuleUri, rawName)
        if (!declaredHere && rawName == "assert") {
            buffer.append("kira_assert(")
            args.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        val math = if (declaredHere) null else jsIntrinsic(rawName)
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
        if (rawName == "_static_assert") {
            throw UnsupportedConstruct(intrinsicExpr, "@_static_assert", TARGET_NAME)
        }
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
        // `a op= b` is `a = a.op(b)` when a's class declares or inherits the member operator.
        val memberCls = userClassOf(compoundAssignmentExpr.left)
        val member = OperatorIntrinsics.memberName(compoundAssignmentExpr.operator)
        if (memberCls != null && member != null && hasMemberOp(memberCls, member)) {
            val target = compoundAssignmentExpr.left
            target.accept(this)
            buffer.append(" = ")
            emitMemberCall(memberCls, member, target, listOf(compoundAssignmentExpr.right))
            return
        }
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
        val originType = receiverTypeOf(arrayIndexExpr.originExpr)
        // A class's `a[i]` is its `@_op_get_`.
        if (originType != null && classTable.has(originType)) {
            if (!emitMemberCall(originType, UserClassTable.GET, arrayIndexExpr.originExpr, listOf(arrayIndexExpr.indexExpr))) {
                throw UnsupportedConstruct(arrayIndexExpr, "a[i] on '$originType', which has no @_op_get_", TARGET_NAME)
            }
            return
        }
        // `m[k]` reads the key or panics (Q12); the same text as C and C++.
        if (originType == "Map") {
            arrayIndexExpr.originExpr.accept(this)
            buffer.append(".at(")
            arrayIndexExpr.indexExpr.accept(this)
            buffer.append(")")
            return
        }
        // Arr is a native array: index directly. A List is a KiraList
        // wrapper, so it reads through get() (range-checked, like C).
        if (receiverTypeOf(arrayIndexExpr.originExpr) == "List") {
            arrayIndexExpr.originExpr.accept(this)
            buffer.append(".get(")
            arrayIndexExpr.indexExpr.accept(this)
            buffer.append(")")
            return
        }
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
        if (objectInitExpr.namedArgs.isNotEmpty()) {
            throw UnsupportedConstruct(objectInitExpr, "named construction 'T { field = value }'", TARGET_NAME)
        }
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
            recordContainerTypeArgs(param.name.value, param.typeSpecifier)
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
        recordContainerTypeArgs(name, variableDecl.type)
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
        enterModuleOf(functionDecl)
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
            recordContainerTypeArgs(param.name.value, param.typeSpecifier)
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
        if (classDecl.initially != null || classDecl.finally != null) {
            throw UnsupportedConstruct(classDecl, "a class with an 'initially' or 'finally' block", TARGET_NAME)
        }
        if (isMagicDecl(classDecl)) {
            return
        }
        enterModuleOf(classDecl)
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

        val parent = classTable.parentOf(className)
        // A synthesized `==` (1.2.5) reads each field through `kira_eq_`; the helper lives in the
        // user layer (the prelude is never renamed, `_op_eq_` is), once, before the first class
        // that needs it.
        val synthesizesEq = classTable.has(className) && classTable.synthesizesEq(className)
        if (synthesizesEq && !emittedEqHelper) {
            emittedEqHelper = true
            appendIndentedLine("function kira_eq_(a, b) {")
            appendIndentedLine("    if (a !== null && typeof a === \"object\" && typeof a._op_eq_ === \"function\") return a._op_eq_(b);")
            appendIndentedLine("    return a === b;")
            appendIndentedLine("}")
        }
        appendIndented("class ")
        buffer.append(className)
        if (parent != null) {
            buffer.append(" extends ")
            buffer.append(parent)
        }
        buffer.appendLine(" {")
        indentLevel++

        // Constructor: require fields become positional params, inherited ones first; defaulted
        // fields are set from their initializer inside the body.
        val parentRequire = if (parent != null) {
            classTable.allFields(parent).filter { f -> f.modifiers.any { it == Modifier.REQUIRE } }
        } else {
            emptyList()
        }
        appendIndented("constructor(")
        (parentRequire + requireFields).forEachIndexed { i, field ->
            if (i > 0) buffer.append(", ")
            buffer.append(field.name.value)
        }
        if (parent == null && requireFields.isEmpty() && defaultFields.isEmpty()) {
            buffer.appendLine(") {}")
        } else {
            buffer.appendLine(") {")
            indentLevel++
            if (parent != null) {
                appendIndented("super(")
                parentRequire.forEachIndexed { i, field ->
                    if (i > 0) buffer.append(", ")
                    buffer.append(field.name.value)
                }
                buffer.appendLine(");")
            }
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
            recordContainerTypeArgs(param.name.value, param.typeSpecifier)
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

        // 1.2.5: an immutable class that declares or inherits no `@_op_eq_` compares its dynamic
        // class and every field; a mutable subclass of one compares identity.
        if (synthesizesEq) {
            userSymbols.add(UserClassTable.EQ)
            appendIndentedLine("${UserClassTable.EQ}(other) {")
            val fieldTests = classTable.allFields(className).joinToString("") { f ->
                " && kira_eq_(this.${f.name.value}, other.${f.name.value})"
            }
            appendIndentedLine("    return other !== null && other !== undefined && other.constructor === this.constructor$fieldTests;")
            appendIndentedLine("}")
        } else if (classTable.has(className) && parent != null && !hasOwnEq(className) &&
            classTable.synthesizesEq(parent)
        ) {
            userSymbols.add(UserClassTable.EQ)
            appendIndentedLine("${UserClassTable.EQ}(other) {")
            appendIndentedLine("    return this === other;")
            appendIndentedLine("}")
        }

        indentLevel--
        appendIndentedLine("}")
        buffer.appendLine()
    }

    private fun hasOwnEq(cls: String): Boolean = classTable.info(cls)?.methods?.containsKey(UserClassTable.EQ) == true

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
        // Explicit values are honoured; the rest count on from the previous
        // member (same rule as the C enum), and Str / Float bases carry
        // their literals.
        val values = enumDecl.memberValues()
        enumDecl.members.forEachIndexed { index, member ->
            if (index > 0) buffer.append(", ")
            buffer.append(member.name.value)
            buffer.append(": ")
            when (val value = values[index]) {
                is String -> {
                    buffer.append("\"")
                    buffer.append(escapeJsString(value))
                    buffer.append("\"")
                }
                else -> buffer.append(value.toString())
            }
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
