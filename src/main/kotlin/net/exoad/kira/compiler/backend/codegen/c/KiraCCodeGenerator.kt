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
        /** Layer 0 -- cupup-style compiler bundle substrate (mangle hooks). */
        const val BUNDLE_FILE = "c_bundle.h"
        /** Layer 1 -- Kira facade types + thin Arr/Map runtime. */
        const val TEMPLATE_FILE = "c_generator.c"
        const val DEFAULT_OUTPUT = "out.kira.c"
        private lateinit var bundleFileContents: String
        private lateinit var templateFileContents: String

        fun fetchBundleFileContents(): String {
            if (!::bundleFileContents.isInitialized) {
                val resource = Public::class.java.getResource("/$BUNDLE_FILE")
                    ?: Public::class.java.getResource(BUNDLE_FILE)
                bundleFileContents = resource?.readText()
                    ?: File("src/main/resources/$BUNDLE_FILE").readText()
            }
            return bundleFileContents
        }

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
    private val opaqueTypes by lazy {
        compilationUnit.collectIntrinsicMarkedTypeNames("_opaque") +
            compilationUnit.allOpaqueTypes()
    }
    private val externFunctions by lazy {
        compilationUnit.allExternFunctions()
    }
    /** Simple name -> Kira type name for print-format heuristics in the current unit. */
    private val knownValueTypes = mutableMapOf<String, String>()
    /**
     * Class methods lowered as free functions: mangledName -> return type.
     * Call site: `recv.method(args)` becomes `Class_method(&recv, args)`.
     */
    private val methodReturnTypes = mutableMapOf<String, String>()
    /** Simple method name -> list of (className, mangledName) for call-site resolution. */
    private val methodsBySimpleName = mutableMapOf<String, MutableList<Pair<String, String>>>()
    /** Class field name -> field type (best-effort; last writer wins on collisions). */
    private val fieldTypes = mutableMapOf<String, String>()
    private var indentLevel = 0
    private var currentModuleUri: String? = null
    private var emittingClassMembers = false
    /** When emitting a method body, the receiver type name (for bare field refs). */
    private var currentMethodClass: String? = null
    /** True while emitting the `.member` side of MemberAccess -- no this-> rewrite. */
    private var suppressThisRewrite = false

    /**
     * Generic class templates keyed by base name (`Box` for `class Box<T>`).
     * Specialized forms are emitted on demand from call/init sites.
     */
    private val genericClassTemplates = mutableMapOf<String, ClassDecl>()
    /** Generic free-function templates keyed by function name (`id` for `fx id<T>`). */
    private val genericFunctionTemplates = mutableMapOf<String, FunctionDecl>()
    /** Requested class specializations: mangled name -> (template, type-arg names). */
    private val classSpecializations = linkedMapOf<String, Pair<ClassDecl, List<String>>>()
    /** Requested function specializations: mangled name -> (template, type-arg names). */
    private val functionSpecializations = linkedMapOf<String, Pair<FunctionDecl, List<String>>>()
    /**
     * Active type-parameter substitution while emitting a monomorphized body
     * (`T` -> `Int32`). Empty outside specialized emission.
     */
    private var typeSubst: Map<String, String> = emptyMap()

    /** User-defined (non-magic) class names. These get ARC heap allocation. */
    private val userClassNames = mutableSetOf<String>()
    /** User class name -> ordered list of (fieldName, fieldType) for init lowering. */
    private val userClassFields = mutableMapOf<String, List<Pair<String, String>>>()

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
        // Cupup-style layering: substrate first, then facade/runtime, then user.
        // Layer 0 -- compiler bundle (fixed-width types + named hooks)
        buffer.appendLine(fetchBundleFileContents().trimEnd())
        buffer.appendLine()
        // Layer 1 -- Kira-facing typedefs + thin collections
        buffer.appendLine(fetchTemplateFileContents().trimEnd())
        buffer.appendLine()

        // Extra includes requested by intrinsics (math.h, etc.)
        // Collected while walking; prepended after the walk.
        val bodyStart = buffer.length

        // Ensure @_opaque / @_extern marks are registered even if semantics skipped apply().
        harvestForeignMarks()

        // Discover generic templates + monomorphization sites before any emit.
        collectGenericTemplates()
        collectSpecializationSites()

        // Layer 2 -- user program
        // 1) Forward-declare structs (concrete + specialized)
        // 2) Emit full struct + enum bodies (complete types before prototypes)
        // 3) Forward-declare free functions + methods (+ specialized generics)
        // 4) Emit everything else -- classes/enums/specialized already emitted
        emitStructForwardDecls()
        emitStructBodies()
        emitSpecializedClassBodies()
        emitEnumBodies()
        emitFunctionPrototypes()
        emitSpecializedFunctionBodies()

        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) {
                return@forEach
            }
            visitRootASTNodeSkippingTypes(source.ast)
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

    private fun eachClassDecl(action: (ClassDecl) -> Unit) {
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is ClassDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (expr is ClassDecl && !isMagicDecl(expr)) {
                    action(expr)
                }
            }
        }
    }

    /**
     * Pull @_opaque / @_extern from parser marks into CompilationUnit registries.
     * Semantic apply() may not run on all stub shapes; emit must still see them.
     */
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
                    // Optional C symbol not recovered from mark alone; default to Kira name.
                    // Full apply() path can override via registerExternFunction.
                    if (compilationUnit.externCNameOrNull(kiraName) == null) {
                        compilationUnit.registerExternFunction(kiraName, kiraName)
                    }
                }
            }
            // Also walk AST for class/function decls that carry marks only on nested nodes
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is ClassDecl, is FunctionDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                when (expr) {
                    is ClassDecl -> {
                        val marked = marks[expr]?.any { it.name == "_opaque" } == true
                        if (marked) compilationUnit.registerOpaqueType(baseTypeNameOf(expr.name))
                    }
                    is FunctionDecl -> {
                        val marked = marks[expr]?.any { it.name == "_extern" } == true
                        if (marked) {
                            val kiraName = functionLikeName(expr.name)
                            if (compilationUnit.externCNameOrNull(kiraName) == null) {
                                compilationUnit.registerExternFunction(kiraName, kiraName)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun eachFunctionDecl(action: (FunctionDecl) -> Unit) {
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is FunctionDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (expr is FunctionDecl && !isMagicDecl(expr)) {
                    action(expr)
                }
            }
        }
    }

    /** True when the class declaration introduces type parameters (`class Box<T>`). */
    private fun isGenericClass(classDecl: ClassDecl): Boolean {
        return classDecl.name.children.isNotEmpty()
    }

    private fun isGenericFunction(functionDecl: FunctionDecl): Boolean {
        return functionDecl.generics.isNotEmpty()
    }

    private fun specializedName(base: String, typeArgs: List<String>): String {
        if (typeArgs.isEmpty()) return base
        return base + typeArgs.joinToString(separator = "") { "_$it" }
    }

    private fun baseTypeNameOf(type: Type): String {
        return when (val identifier = type.identifier) {
            is Identifier -> identifier.value
            is IntrinsicExpr -> identifier.intrinsicKey.name
            else -> "Any"
        }
    }

    /**
     * Resolve a Kira type name with active type-param substitution and
     * specialization mangling (`Box<Int32>` -> `Box_Int32`, bare `T` -> `Int32`).
     * Magic collections (`Arr`/`Map`/...) erase type args at the C boundary.
     */
    private fun resolveKiraTypeName(type: Type): String {
        val base = baseTypeNameOf(type)
        val resolvedBase = typeSubst[base] ?: base
        if (type.children.isEmpty()) {
            return resolvedBase
        }
        // Only monomorphize user generic classes we hold a template for.
        // Prelude/magic generics (Arr, Map, ...) keep the erased base name.
        if (!genericClassTemplates.containsKey(resolvedBase)) {
            return resolvedBase
        }
        val args = type.children.map { resolveKiraTypeName(it) }
        return specializedName(resolvedBase, args)
    }

    private fun collectGenericTemplates() {
        eachClassDecl { decl ->
            if (isGenericClass(decl)) {
                genericClassTemplates[baseTypeNameOf(decl.name)] = decl
            }
        }
        eachFunctionDecl { decl ->
            if (isGenericFunction(decl)) {
                genericFunctionTemplates[functionLikeName(decl.name)] = decl
            }
        }
    }

    private fun requestClassSpecialization(type: Type) {
        val base = baseTypeNameOf(type)
        if (type.children.isEmpty()) return
        val template = genericClassTemplates[base] ?: return
        val args = type.children.map { resolveKiraTypeName(it) }
        val mangled = specializedName(base, args)
        classSpecializations.putIfAbsent(mangled, template to args)
        // Nested type args may themselves need specialization.
        type.children.forEach { requestClassSpecialization(it) }
    }

    private fun requestFunctionSpecialization(name: String, typeArgs: List<Type>) {
        if (typeArgs.isEmpty()) return
        val template = genericFunctionTemplates[name] ?: return
        val args = typeArgs.map { resolveKiraTypeName(it) }
        val mangled = specializedName(name, args)
        functionSpecializations.putIfAbsent(mangled, template to args)
        typeArgs.forEach { requestClassSpecialization(it) }
    }

    private fun collectSpecializationSites() {
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            walkExprs(source.ast) { expr ->
                when (expr) {
                    is ObjectInitExpr -> requestClassSpecialization(expr.typeName)
                    is FunctionCallExpr -> {
                        val fname = functionLikeName(expr.name)
                        if (expr.typeArguments.isNotEmpty()) {
                            requestFunctionSpecialization(fname, expr.typeArguments)
                        }
                    }
                    is VariableDecl -> requestClassSpecialization(expr.type)
                    else -> {}
                }
            }
            // VariableDecl is a Decl, not always reached via expr walk of statements
            source.ast.statements.forEach { stmt ->
                val node: Any? = when (stmt) {
                    is VariableDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (node is VariableDecl) {
                    requestClassSpecialization(node.type)
                }
            }
        }
    }

    /**
     * Lightweight AST walk for specialization discovery. Covers the node shapes
     * the baseline backend actually emits; unknown nodes are skipped.
     */
    private fun walkExprs(node: Any?, visit: (Any) -> Unit) {
        if (node == null) return
        visit(node)
        when (node) {
            is RootASTNode -> node.statements.forEach { walkExprs(it, visit) }
            is Statement -> walkExprs(node.expr, visit)
            is FunctionDecl -> {
                walkExprs(node.def, visit)
            }
            is FunctionDefExpr -> {
                node.parameters.forEach { walkExprs(it, visit) }
                node.body?.forEach { walkExprs(it, visit) }
            }
            is ClassDecl -> node.members.forEach { walkExprs(it, visit) }
            is VariableDecl -> {
                walkExprs(node.type, visit)
                walkExprs(node.value, visit)
            }
            is FunctionCallExpr -> {
                walkExprs(node.name, visit)
                node.typeArguments.forEach { walkExprs(it, visit) }
                node.positionalParameters.forEach { walkExprs(it.value, visit) }
                node.namedParameters.forEach { walkExprs(it.value, visit) }
            }
            is ObjectInitExpr -> {
                walkExprs(node.typeName, visit)
                node.positionalArgs.forEach { walkExprs(it, visit) }
            }
            is BinaryExpr -> {
                walkExprs(node.leftExpr, visit)
                walkExprs(node.rightExpr, visit)
            }
            is UnaryExpr -> walkExprs(node.operand, visit)
            is AssignmentExpr -> {
                walkExprs(node.target, visit)
                walkExprs(node.value, visit)
            }
            is MemberAccessExpr -> {
                walkExprs(node.origin, visit)
                walkExprs(node.member, visit)
            }
            is ArrayIndexExpr -> {
                walkExprs(node.originExpr, visit)
                walkExprs(node.indexExpr, visit)
            }
            is IfSelectionStatement -> {
                walkExprs(node.expr, visit)
                node.thenStatements.forEach { walkExprs(it, visit) }
                node.elseBranches.forEach { walkExprs(it, visit) }
            }
            is ElseIfBranchStatement -> {
                walkExprs(node.condition, visit)
                node.statements.forEach { walkExprs(it, visit) }
            }
            is ElseBranchStatement -> node.statements.forEach { walkExprs(it, visit) }
            is WhileIterationStatement -> {
                walkExprs(node.condition, visit)
                node.statements.forEach { walkExprs(it, visit) }
            }
            is DoWhileIterationStatement -> {
                node.statements.forEach { walkExprs(it, visit) }
                walkExprs(node.condition, visit)
            }
            is ForIterationStatement -> {
                walkExprs(node.forIterationExpr, visit)
                node.body.forEach { walkExprs(it, visit) }
            }
            is ReturnStatement -> walkExprs(node.expr, visit)
            is TypeCheckExpr -> {
                walkExprs(node.value, visit)
                walkExprs(node.type, visit)
            }
            is TypeCastExpr -> {
                walkExprs(node.value, visit)
                walkExprs(node.type, visit)
            }
            is ArrayLiteral -> node.value.forEach { walkExprs(it, visit) }
            is Type -> node.children.forEach { walkExprs(it, visit) }
            else -> {}
        }
    }

    private fun emitStructForwardDecls() {
        val names = linkedSetOf<String>()
        eachClassDecl {
            if (!isGenericClass(it) && !isOpaqueTypeName(baseTypeNameOf(it.name))) {
                names.add(baseTypeNameOf(it.name))
            }
        }
        classSpecializations.keys.forEach { names.add(it) }
        // Opaque foreign types: incomplete struct + pointer typedef handled in emitOpaqueTypedefs
        if (names.isEmpty() && opaqueTypes.isEmpty()) return
        names.forEach { name ->
            buffer.appendLine("typedef struct $name $name;")
        }
        emitOpaqueTypedefs()
        buffer.appendLine()
    }

    private fun emitOpaqueTypedefs() {
        opaqueTypes.sorted().forEach { name ->
            // Incomplete struct tag; values are name* in mapTypeName.
            buffer.appendLine("typedef struct $name $name;")
        }
    }

    private fun emitStructBodies() {
        eachClassDecl {
            if (!isGenericClass(it) && !isOpaqueTypeName(baseTypeNameOf(it.name))) {
                visitClassDecl(it)
            }
        }
    }

    private fun emitSpecializedClassBodies() {
        // Snapshot keys -- specialization set is fixed after collection.
        classSpecializations.entries.toList().forEach { (mangled, pair) ->
            val (template, args) = pair
            emitSpecializedClass(mangled, template, args)
        }
    }

    private fun emitSpecializedClass(mangled: String, template: ClassDecl, args: List<String>) {
        val paramNames = template.name.children.map { baseTypeNameOf(it) }
        val subst = paramNames.zip(args).toMap()
        val prev = typeSubst
        typeSubst = subst

        val fields = template.members.filterIsInstance<VariableDecl>()
        val methods = template.members.filterIsInstance<FunctionDecl>()

        appendIndented("struct ")
        buffer.append(mangled)
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        if (fields.isEmpty()) {
            appendIndentedLine("Utf8 _empty;")
        } else {
            emittingClassMembers = true
            fields.forEach { field ->
                fieldTypes[field.name.value] = resolveKiraTypeName(field.type)
                appendIndented("")
                buffer.append(mapTypeName(resolveKiraTypeName(field.type)))
                buffer.append(" ")
                buffer.append(field.name.value)
                buffer.appendLine(";")
            }
            emittingClassMembers = false
        }
        indentLevel--
        appendIndentedLine("};")
        buffer.appendLine()

        methods.forEach { method ->
            if (method.isStub()) return@forEach
            val methodName = functionLikeName(method.name)
            val returnTypeName = resolveKiraTypeName(method.def.returnTypeSpecifier)
            val mangledMethod = registerMethod(mangled, methodName, returnTypeName)

            appendIndented("")
            buffer.append(mapTypeName(returnTypeName))
            buffer.append(" ")
            buffer.append(mangledMethod)
            buffer.append("(")
            buffer.append(mangled)
            buffer.append("* this")
            method.def.parameters.forEach { param ->
                buffer.append(", ")
                buffer.append(mapTypeName(resolveKiraTypeName(param.typeSpecifier)))
                buffer.append(" ")
                buffer.append(param.name.value)
            }
            buffer.appendLine(")")
            appendIndentedLine("{")
            indentLevel++
            method.def.parameters.forEach { param ->
                knownValueTypes[param.name.value] = resolveKiraTypeName(param.typeSpecifier)
            }
            currentMethodClass = mangled
            method.def.body?.forEach { it.accept(this) }
            currentMethodClass = null
            method.def.parameters.forEach { param ->
                knownValueTypes.remove(param.name.value)
            }
            indentLevel--
            appendIndentedLine("}")
            buffer.appendLine()
        }

        typeSubst = prev
    }

    private fun emitSpecializedFunctionBodies() {
        functionSpecializations.entries.toList().forEach { (mangled, pair) ->
            val (template, args) = pair
            emitSpecializedFunction(mangled, template, args)
        }
    }

    private fun emitSpecializedFunction(mangled: String, template: FunctionDecl, args: List<String>) {
        val paramNames = template.generics.map { baseTypeNameOf(it) }
        val subst = paramNames.zip(args).toMap()
        val prev = typeSubst
        typeSubst = subst

        val returnTypeName = resolveKiraTypeName(template.def.returnTypeSpecifier)
        knownValueTypes[mangled] = returnTypeName
        template.def.parameters.forEach { param ->
            knownValueTypes[param.name.value] = resolveKiraTypeName(param.typeSpecifier)
        }

        appendIndented("")
        buffer.append(mapTypeName(returnTypeName))
        buffer.append(" ")
        buffer.append(mangled)
        buffer.append("(")
        if (template.def.parameters.isEmpty()) {
            buffer.append("Void")
        } else {
            template.def.parameters.forEachIndexed { idx, parameter ->
                if (idx > 0) buffer.append(", ")
                buffer.append(mapTypeName(resolveKiraTypeName(parameter.typeSpecifier)))
                buffer.append(" ")
                buffer.append(parameter.name.value)
            }
        }
        buffer.append(")")
        if (template.def.body == null) {
            buffer.appendLine(";")
            typeSubst = prev
            return
        }
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        template.def.body!!.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
        buffer.appendLine()

        template.def.parameters.forEach { param ->
            knownValueTypes.remove(param.name.value)
        }
        typeSubst = prev
    }

    private fun eachEnumDecl(action: (EnumDecl) -> Unit) {
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is EnumDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (expr is EnumDecl && !isMagicDecl(expr)) {
                    action(expr)
                }
            }
        }
    }

    private fun emitEnumBodies() {
        eachEnumDecl { visitEnumDecl(it) }
    }

    private fun visitRootASTNodeSkippingTypes(node: RootASTNode) {
        node.statements.forEach { stmt ->
            val expr: Any? = when (stmt) {
                is ClassDecl, is EnumDecl -> stmt
                is Statement -> stmt.expr
                else -> stmt
            }
            if (expr is ClassDecl || expr is EnumDecl) {
                // Already emitted in emitStructBodies / emitEnumBodies
                return@forEach
            }
            stmt.accept(this)
        }
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
            val expr: Any? = when (stmt) {
                is FunctionDecl -> stmt
                is ClassDecl -> stmt
                is Statement -> stmt.expr
                else -> null
            }
            when (expr) {
                is FunctionDecl -> {
                    if (!isMagicDecl(expr) && !isGenericFunction(expr)) {
                        out.add(functionPrototypeLine(expr))
                    }
                }
                is ClassDecl -> {
                    if (isMagicDecl(expr) || isGenericClass(expr) || isOpaqueTypeName(baseTypeNameOf(expr.name))) return@forEach
                    val className = typeNameOf(expr.name)
                    // Register fields early for print-format / method body rewriting
                    expr.members.filterIsInstance<VariableDecl>().forEach { field ->
                        fieldTypes[field.name.value] = typeNameOf(field.type)
                    }
                    expr.members.filterIsInstance<FunctionDecl>().forEach { method ->
                        if (method.isStub()) return@forEach
                        val methodName = functionLikeName(method.name)
                        val returnTypeName = typeNameOf(method.def.returnTypeSpecifier)
                        val mangled = registerMethod(className, methodName, returnTypeName)
                        val params = buildString {
                            append(className)
                            append("* this")
                            method.def.parameters.forEach { param ->
                                append(", ")
                                append(mapTypeName(typeNameOf(param.typeSpecifier)))
                                append(" ")
                                append(param.name.value)
                            }
                        }
                        out.add("${mapTypeName(returnTypeName)} $mangled($params);")
                    }
                }
            }
        }
        // Specialized generic free functions
        functionSpecializations.forEach { (mangled, pair) ->
            val (template, args) = pair
            val paramNames = template.generics.map { baseTypeNameOf(it) }
            val subst = paramNames.zip(args).toMap()
            val prev = typeSubst
            typeSubst = subst
            val returnTypeName = resolveKiraTypeName(template.def.returnTypeSpecifier)
            knownValueTypes[mangled] = returnTypeName
            val params = if (template.def.parameters.isEmpty()) {
                "Void"
            } else {
                template.def.parameters.joinToString(", ") { param ->
                    "${mapTypeName(resolveKiraTypeName(param.typeSpecifier))} ${param.name.value}"
                }
            }
            out.add("${mapTypeName(returnTypeName)} $mangled($params);")
            typeSubst = prev
        }
        // Specialized generic class methods
        classSpecializations.forEach { (classMangled, pair) ->
            val (template, args) = pair
            val paramNames = template.name.children.map { baseTypeNameOf(it) }
            val subst = paramNames.zip(args).toMap()
            val prev = typeSubst
            typeSubst = subst
            template.members.filterIsInstance<VariableDecl>().forEach { field ->
                fieldTypes[field.name.value] = resolveKiraTypeName(field.type)
            }
            template.members.filterIsInstance<FunctionDecl>().forEach { method ->
                if (method.isStub()) return@forEach
                val methodName = functionLikeName(method.name)
                val returnTypeName = resolveKiraTypeName(method.def.returnTypeSpecifier)
                val mangled = registerMethod(classMangled, methodName, returnTypeName)
                val params = buildString {
                    append(classMangled)
                    append("* this")
                    method.def.parameters.forEach { param ->
                        append(", ")
                        append(mapTypeName(resolveKiraTypeName(param.typeSpecifier)))
                        append(" ")
                        append(param.name.value)
                    }
                }
                out.add("${mapTypeName(returnTypeName)} $mangled($params);")
            }
            typeSubst = prev
        }
    }

    private fun functionPrototypeLine(functionDecl: FunctionDecl): String {
        val kiraName = functionLikeName(functionDecl.name)
        val functionName = if (isExternFunction(kiraName)) externCName(kiraName) else kiraName
        val returnTypeName = typeNameOf(functionDecl.def.returnTypeSpecifier)
        val returnsVoid = returnTypeName == "Void"
        val retC = if (kiraName == "main" && returnsVoid) {
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
        knownValueTypes[kiraName] = returnTypeName
        if (functionName != kiraName) {
            knownValueTypes[functionName] = returnTypeName
        }
        functionDecl.def.parameters.forEach { param ->
            knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
        }
        val linkage = if (isExternFunction(kiraName)) "extern " else ""
        return "$linkage$retC $functionName($params);"
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
        // Prefer specialized / substituted names so Box<Int32> and bare T lower correctly.
        return resolveKiraTypeName(type)
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
        // Foreign opaque handles are C pointers (never Kira ARC objects).
        if (opaqueTypes.contains(typeName)) {
            return "$typeName*"
        }
        // Always map well-known builtins even without magic registration
        return when (typeName) {
            "Int8", "Int16", "Int32", "Int64",
            "Float32", "Float64", "Bool", "Void", "Never",
            "Str", "String", "Any", "Int", "Float",
            "Arr", "List", "Map", "Set" ->
                CMagicTypeLowering.resolve(typeName)?.cType ?: typeName
            else -> typeName
        }
    }

    private fun isOpaqueTypeName(typeName: String): Boolean {
        return opaqueTypes.contains(typeName)
    }

    private fun isExternFunction(name: String): Boolean {
        return externFunctions.containsKey(name)
    }

    private fun externCName(name: String): String {
        return externFunctions[name] ?: name
    }

    private fun isCollectionType(typeName: String?): Boolean {
        return typeName == "Arr" || typeName == "List" || typeName == "Map" || typeName == "Set"
    }

    /**
     * Lower a known collection method on a receiver. Returns true if handled.
     * Call shape in C: Arr_isEmpty(&recv) / Map_isEmpty(&recv) / Arr_size(&recv).
     */
    private fun tryEmitCollectionMethod(methodName: String, receiver: Expr, args: List<Expr>): Boolean {
        val recvType = receiverTypeOf(receiver) ?: return false
        val prefix = when (recvType) {
            "Arr", "List" -> recvType
            "Map", "Set" -> "Map"
            else -> return false
        }

        when (methodName) {
            "isEmpty" -> {
                buffer.append(prefix)
                buffer.append("_isEmpty(&")
                receiver.accept(this)
                buffer.append(")")
                return true
            }
            "size" -> {
                buffer.append(prefix)
                buffer.append("_size(&")
                receiver.accept(this)
                buffer.append(")")
                return true
            }
            "clear" -> {
                buffer.append(prefix)
                buffer.append("_clear(&")
                receiver.accept(this)
                buffer.append(")")
                return true
            }
            "get" -> {
                if (prefix == "Map" || prefix == "Set") {
                    // Map_get(map, key) -- pointer receiver
                    buffer.append("Map_get(&")
                    receiver.accept(this)
                    buffer.append(", ")
                    args[0].accept(this)
                    buffer.append(")")
                    return true
                }
                // Arr_get_i32(arr, index) -- value receiver, not pointer
                buffer.append("Arr_get_i32(")
                receiver.accept(this)
                if (args.isNotEmpty()) {
                    buffer.append(", ")
                    args[0].accept(this)
                }
                buffer.append(")")
                return true
            }
            "set" -> {
                if (prefix == "Map") return false // Map uses put
                if (prefix == "List") {
                    buffer.append("List_set(&")
                    receiver.accept(this)
                    buffer.append(", ")
                    args[0].accept(this)
                    buffer.append(", ")
                    args[1].accept(this)
                    buffer.append(")")
                    return true
                }
                // Arr_set_i32
                buffer.append("Arr_set_i32(")
                receiver.accept(this)
                buffer.append(", ")
                args[0].accept(this)
                buffer.append(", ")
                args[1].accept(this)
                buffer.append(")")
                return true
            }
            "put" -> {
                if (prefix != "Map") return false
                buffer.append("Map_put(&")
                receiver.accept(this)
                buffer.append(", ")
                args[0].accept(this)
                buffer.append(", ")
                args[1].accept(this)
                buffer.append(")")
                return true
            }
            "containsKey" -> {
                if (prefix != "Map") return false
                buffer.append("Map_containsKey(&")
                receiver.accept(this)
                buffer.append(", ")
                args[0].accept(this)
                buffer.append(")")
                return true
            }
            "remove" -> {
                if (prefix == "Map") {
                    buffer.append("Map_remove(&")
                    receiver.accept(this)
                    buffer.append(", ")
                    args[0].accept(this)
                    buffer.append(")")
                    return true
                }
                if (prefix == "List") {
                    // List_removeAt for List.remove(index)
                    buffer.append("List_removeAt(&")
                    receiver.accept(this)
                    buffer.append(", ")
                    args[0].accept(this)
                    buffer.append(")")
                    return true
                }
                return false
            }
            "add" -> {
                if (prefix != "List") return false
                buffer.append("List_add(&")
                receiver.accept(this)
                buffer.append(", ")
                args[0].accept(this)
                buffer.append(")")
                return true
            }
            "toArr" -> {
                if (prefix != "List") return false
                buffer.append("List_toArr(&")
                receiver.accept(this)
                buffer.append(")")
                return true
            }
            else -> return false
        }
    }

    private fun isMagicDecl(decl: Decl): Boolean {
        // Marks live on SourceContext.astIntrinsicMarked, not decl.attachedIntrinsics
        // (that list is rarely populated). Treat @_magic only -- not @_opaque/@_extern.
        if (declHasIntrinsic(decl, "_magic")) {
            return true
        }
        // Also treat known magic type names as skippable even if marker missed
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
        methodReturnTypes.clear()
        methodsBySimpleName.clear()
        fieldTypes.clear()
        genericClassTemplates.clear()
        genericFunctionTemplates.clear()
        classSpecializations.clear()
        functionSpecializations.clear()
        typeSubst = emptyMap()
        indentLevel = 0
        currentModuleUri = null
        emittingClassMembers = false
        currentMethodClass = null
        suppressThisRewrite = false
    }

    private fun mangleMethodName(className: String, methodName: String): String {
        return "${className}_$methodName"
    }

    private fun registerMethod(className: String, methodName: String, returnType: String): String {
        val mangled = mangleMethodName(className, methodName)
        methodReturnTypes[mangled] = returnType
        methodsBySimpleName.getOrPut(methodName) { mutableListOf() }.add(className to mangled)
        knownValueTypes[mangled] = returnType
        return mangled
    }

    private fun resolveMethodMangled(methodName: String, receiverType: String?): String? {
        val candidates = methodsBySimpleName[methodName] ?: return null
        if (receiverType != null) {
            candidates.firstOrNull { it.first == receiverType }?.let { return it.second }
        }
        // Single candidate: unambiguous even without receiver type.
        if (candidates.size == 1) return candidates[0].second
        return null
    }

    private fun receiverTypeOf(expr: Expr): String? {
        return when (expr) {
            is Identifier -> {
                // Check locals first, then fields (for bare field refs inside methods like `cells`)
                knownValueTypes[expr.value] ?: fieldTypes[expr.value]
            }
            is MemberAccessExpr -> {
                // nested.field -- look up field type if known
                val memberName = (expr.member as? Identifier)?.value
                memberName?.let { fieldTypes[it] } ?: knownValueTypes[memberName]
            }
            is FunctionCallExpr -> {
                val name = functionLikeName(expr.name)
                knownValueTypes[name] ?: methodReturnTypes[name]
            }
            else -> null
        }
    }

    /**
     * Check if a bare function call name is actually a method on the current class.
     * Returns the mangled method name if found, null otherwise.
     */
    private fun tryResolveAsCurrentMethod(funcName: String): String? {
        if (currentMethodClass == null) return null
        return resolveMethodMangled(funcName, currentMethodClass!!)
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
                // Method call: name is MemberAccess
                val nameExpr = expr.name
                if (nameExpr is MemberAccessExpr) {
                    val methodName = (nameExpr.member as? Identifier)?.value
                    val recvType = receiverTypeOf(nameExpr.origin)
                    val mangled = methodName?.let { resolveMethodMangled(it, recvType) }
                    formatForTypeName(mangled?.let { methodReturnTypes[it] })
                } else {
                    val ret = knownValueTypes[functionLikeName(expr.name)]
                    formatForTypeName(ret)
                }
            }
            is MemberAccessExpr -> {
                val memberName = (expr.member as? Identifier)?.value
                formatForTypeName(memberName?.let { fieldTypes[it] } ?: knownValueTypes[memberName])
            }
            is Identifier -> {
                when (expr.value) {
                    "true", "false" -> "%d"
                    else -> formatForTypeName(knownValueTypes[expr.value] ?: fieldTypes[expr.value])
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
        val nameExpr = functionCallExpr.name
        // Method call: receiver.method(args) -> Class_method(&receiver, args)
        // or Arr/Map runtime helpers for magic collection types.
        if (nameExpr is MemberAccessExpr) {
            val methodName = (nameExpr.member as? Identifier)?.value ?: "_anon"
            val args = buildList {
                functionCallExpr.positionalParameters.forEach { add(it.value) }
                functionCallExpr.namedParameters.forEach { add(it.value) }
            }
            if (tryEmitCollectionMethod(methodName, nameExpr.origin, args)) {
                return
            }
            val recvType = receiverTypeOf(nameExpr.origin)
            val mangled = resolveMethodMangled(methodName, recvType) ?: methodName
            buffer.append(mangled)
            buffer.append("(")
            // Receiver by pointer for mutable field access in method bodies.
            buffer.append("&")
            nameExpr.origin.accept(this)
            functionCallExpr.positionalParameters.forEach { param ->
                buffer.append(", ")
                param.value.accept(this)
            }
            functionCallExpr.namedParameters.forEach { param ->
                buffer.append(", ")
                param.value.accept(this)
            }
            buffer.append(")")
            return
        }

        val rawName = functionLikeName(nameExpr)
        val args = buildList {
            functionCallExpr.positionalParameters.forEach { add(it.value) }
            functionCallExpr.namedParameters.forEach { add(it.value) }
        }
        if (isPrintLike(rawName)) {
            emitPrintCall(rawName, args)
            return
        }
        // Bare method call inside a class method: `methodName(args)` → `Class_method(this, args)`
        val currentMethodMangled = tryResolveAsCurrentMethod(rawName)
        if (currentMethodMangled != null) {
            buffer.append(currentMethodMangled)
            buffer.append("(this")
            args.forEach { arg ->
                buffer.append(", ")
                arg.accept(this)
            }
            buffer.append(")")
            return
        }
        includeForIntrinsic(rawName)
        val functionName = when {
            isExternFunction(rawName) -> externCName(rawName)
            functionCallExpr.typeArguments.isNotEmpty() -> {
                val typeArgNames = functionCallExpr.typeArguments.map { resolveKiraTypeName(it) }
                specializedName(mapIntrinsicName(rawName), typeArgNames)
            }
            else -> mapIntrinsicName(rawName)
        }
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
        // Baseline: receivers are values/locals, so "." is correct.
        // Member side must not get this-> rewriting (nested.field stays .field).
        buffer.append(".")
        val prev = suppressThisRewrite
        suppressThisRewrite = true
        member.accept(this)
        suppressThisRewrite = prev
    }

    override fun visitIdentifier(identifier: Identifier) {
        val name = identifier.value
        // Inside a method body, bare field names become this->field.
        // Skip when we are already emitting the member side of a MemberAccess
        // (nested.field must stay ".field", not ".this->field").
        val cls = currentMethodClass
        if (cls != null &&
            !suppressThisRewrite &&
            fieldTypes.containsKey(name) &&
            !knownValueTypes.containsKey(name)
        ) {
            buffer.append("this->")
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
        buffer.append("(")
        rangeExpr.begin.accept(this)
        buffer.append(" .. ")
        rangeExpr.end.accept(this)
        buffer.append(")")
    }

    override fun visitArrayIndexExpr(arrayIndexExpr: ArrayIndexExpr) {
        // Arr is a struct { data, length }; index through Arr_get_i32.
        buffer.append("Arr_get_i32(")
        arrayIndexExpr.originExpr.accept(this)
        buffer.append(", ")
        arrayIndexExpr.indexExpr.accept(this)
        buffer.append(")")
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
        val baseName = baseTypeNameOf(objectInitExpr.typeName)
        val typeName = typeNameOf(objectInitExpr.typeName)
        // Empty Map/Arr/List constructors use runtime helpers.
        if (objectInitExpr.positionalArgs.isEmpty()) {
            when (baseName) {
                "Map", "Set" -> {
                    buffer.append("Map_new()")
                    return
                }
                "Arr" -> {
                    buffer.append("Arr_empty()")
                    return
                }
                "List" -> {
                    buffer.append("List_new()")
                    return
                }
            }
        }
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
        // Compound-literal backed Arr view. Elements are Int32 in the baseline
        // backend (matches Arr_i32). Lifetime is the enclosing block -- fine
        // for locals and immediate call arguments in the examples.
        val n = arrayLiteral.value.size
        buffer.append("Arr_i32((Int32[]){ ")
        arrayLiteral.value.forEachIndexed { i, expr ->
            if (i > 0) buffer.append(", ")
            expr.accept(this)
        }
        if (n == 0) {
            // Empty compound literal still needs a valid pointer; use null via Arr_empty.
            // (Int32[]){ } is a GCC extension with zero size; prefer helper.
            buffer.setLength(buffer.length - "Arr_i32((Int32[]){ ".length)
            buffer.append("Arr_empty()")
            return
        }
        buffer.append(" }, ")
        buffer.append(n)
        buffer.append(")")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        buffer.append("null")
    }

    override fun visitType(type: Type) {
        buffer.append(mapTypeName(typeNameOf(type)))
    }

    override fun visitVariableDecl(variableDecl: VariableDecl) {
        if (isMagicDecl(variableDecl)) {
            return
        }
        val typeName = typeNameOf(variableDecl.type)
        if (emittingClassMembers) {
            fieldTypes[variableDecl.name.value] = typeName
            // Field only -- no initializer inside struct
            appendIndented("")
            variableDecl.type.accept(this)
            buffer.append(" ")
            // Field names must not go through this-> rewriting
            buffer.append(variableDecl.name.value)
            buffer.appendLine(";")
            return
        }
        knownValueTypes[variableDecl.name.value] = typeName
        appendIndented("")
        variableDecl.type.accept(this)
        buffer.append(" ")
        variableDecl.name.accept(this)
        if (variableDecl.value != null) {
            buffer.append(" = ")
            // Empty Map/Arr typed locals with missing/empty init
            val value = variableDecl.value!!
            if (value is ObjectInitExpr && value.positionalArgs.isEmpty() && isCollectionType(typeName)) {
                value.accept(this)
            } else {
                value.accept(this)
            }
        } else if (isCollectionType(typeName)) {
            buffer.append(" = ")
            when (typeName) {
                "Map", "Set" -> buffer.append("Map_new()")
                "List" -> buffer.append("List_empty()")
                else -> buffer.append("Arr_empty()")
            }
        }
        buffer.appendLine(";")
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl) {
        if (isMagicDecl(functionDecl)) {
            return
        }
        // Generic templates are monomorphized separately; skip the template body.
        if (isGenericFunction(functionDecl)) {
            return
        }
        // Methods inside classes: skip body emission in baseline (no vtable yet)
        if (emittingClassMembers) {
            return
        }

        val kiraName = functionLikeName(functionDecl.name)
        // @_extern stubs: prototype only (already emitted); never emit a body.
        if (isExternFunction(kiraName)) {
            knownValueTypes[kiraName] = typeNameOf(functionDecl.def.returnTypeSpecifier)
            return
        }
        val functionName = kiraName
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
        // Generic class templates are monomorphized into specialized structs.
        if (isGenericClass(classDecl)) {
            return
        }
        // @_opaque foreign types: incomplete struct only (no Kira fields/ARC).
        if (isOpaqueTypeName(baseTypeNameOf(classDecl.name))) {
            return
        }
        val className = typeNameOf(classDecl.name)
        val fields = classDecl.members.filterIsInstance<VariableDecl>()
        val methods = classDecl.members.filterIsInstance<FunctionDecl>()

        // Forward decl already emitted `typedef struct Class Class;`.
        // Define the body with a tagged struct so the typedef alias completes.
        appendIndented("struct ")
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
        appendIndentedLine("};")
        buffer.appendLine()

        // Lower methods as free functions: Ret Class_method(Class* this, ...)
        methods.forEach { method ->
            if (method.isStub()) return@forEach
            val methodName = functionLikeName(method.name)
            val returnTypeName = typeNameOf(method.def.returnTypeSpecifier)
            val mangled = registerMethod(className, methodName, returnTypeName)

            appendIndented("")
            buffer.append(mapTypeName(returnTypeName))
            buffer.append(" ")
            buffer.append(mangled)
            buffer.append("(")
            buffer.append(className)
            buffer.append("* this")
            method.def.parameters.forEach { param ->
                buffer.append(", ")
                param.accept(this)
            }
            buffer.appendLine(")")
            appendIndentedLine("{")
            indentLevel++
            // Parameters are locals for this body
            method.def.parameters.forEach { param ->
                knownValueTypes[param.name.value] = typeNameOf(param.typeSpecifier)
            }
            currentMethodClass = className
            method.def.body?.forEach { it.accept(this) }
            currentMethodClass = null
            // Drop param locals so they don't leak
            method.def.parameters.forEach { param ->
                knownValueTypes.remove(param.name.value)
            }
            indentLevel--
            appendIndentedLine("}")
            buffer.appendLine()
        }
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
