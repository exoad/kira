package net.exoad.kira.compiler.backend.codegen.c

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
        /** C keywords: never renamed by the minifier. */
        private val C_KEYWORDS = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if",
            "inline", "int", "long", "register", "restrict", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
            "unsigned", "void", "volatile", "while", "_Bool", "_Complex",
            "_Imaginary", "_Alignas", "_Alignof", "_Atomic", "_Generic",
            "_Noreturn", "_Static_assert", "_Thread_local",
        )
        private lateinit var bundleFileContents: String
        private lateinit var templateFileContents: String

        fun fetchBundleFileContents(): String {
            if (!::bundleFileContents.isInitialized) {
                // The stdlib owns its runtime: kira/c/ sits next to the modules
                // (see StdlibLayout). Resources remain a fallback for packaged jars.
                val fromStdlib = StdlibLayout.cFile(BUNDLE_FILE)
                val resource = Public::class.java.getResource("/$BUNDLE_FILE")
                    ?: Public::class.java.getResource(BUNDLE_FILE)
                bundleFileContents = fromStdlib?.let { Files.readString(it) }
                    ?: resource?.readText()
                    ?: File("src/main/resources/$BUNDLE_FILE").readText()
            }
            return bundleFileContents
        }

        fun fetchTemplateFileContents(): String {
            if (!::templateFileContents.isInitialized) {
                val fromStdlib = StdlibLayout.cFile(TEMPLATE_FILE)
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
     * User-declared identifiers that the minifier may rename in the emitted
     * user layer. Registered at the exact points where codegen creates names,
     * so the set is always precisely what was emitted (missing a name only
     * leaves it readable -- never breaks the build).
     */
    private val userSymbols = linkedSetOf<String>()
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
    /** Enum type names in the current unit -- int-like, keep direct operators. */
    private val enumTypeNames = mutableSetOf<String>()
    /**
     * Simple name -> Kira type arguments of a container-typed value
     * (`entries: Map<Str, Int32>` records `[Str, Int32]`).
     *
     * Containers erase to `KiraSlot` in C, so this is what lets codegen cast a
     * slot back to the element type the program declared.
     */
    private val containerTypeArgs = mutableMapOf<String, List<String>>()
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
     * Stack of ARC scopes, innermost last. Each holds the class-typed locals
     * declared directly in that block, so a release lands inside the C block
     * that declared the variable rather than at function scope.
     */
    private val arcScopes = ArrayDeque<MutableList<Pair<String, String>>>()

    /** Collect non-magic user classes (concrete + specialized) for ARC lowering. */
    private fun collectUserClasses() {
        eachClassDecl { decl ->
            val base = baseTypeNameOf(decl.name)
            if (isMagicDecl(decl) || isOpaqueTypeName(base)) return@eachClassDecl
            userClassNames.add(base)
            val fields = decl.members.filterIsInstance<VariableDecl>().map { field ->
                field.name.value to typeNameOf(field.type)
            }
            userClassFields[base] = fields
        }
        classSpecializations.keys.forEach { userClassNames.add(it) }
    }

    private fun pushArcScope() {
        arcScopes.addLast(mutableListOf())
    }

    /** Register a class-typed local in the innermost open scope. */
    private fun registerArcLocal(name: String, className: String) {
        arcScopes.lastOrNull()?.add(name to className)
    }

    /** Containers that own heap storage and must be disposed at scope end. */
    private val disposableContainers = setOf("List", "Map", "Set", "Stack", "Queue", "Deque")

    /**
     * Emit the cleanup for one scope entry. Class references are refcounted;
     * containers own a buffer and are disposed. `Arr` is deliberately absent --
     * an Arr literal points at a stack compound literal, so freeing it by type
     * alone would be wrong.
     */
    private fun emitArcRelease(name: String, kind: String) {
        if (kind in disposableContainers) {
            appendIndented(kind)
            buffer.append("_dispose(&")
            buffer.append(name)
            buffer.appendLine(");")
            return
        }
        appendIndented("kira_rc_release(")
        buffer.append(name)
        buffer.appendLine(");")
    }

    /**
     * Close the innermost scope, releasing its locals in reverse declaration
     * order. [exclude] names a local whose ownership is moving out (a returned
     * value), so it must survive.
     *
     * When the block already ended in `return`, the return path emitted its own
     * releases; emitting again here would be unreachable code (and a second
     * release on any path that did reach it).
     */
    private fun popArcScope(exclude: String? = null, terminated: Boolean = false) {
        val scope = arcScopes.removeLastOrNull() ?: return
        if (terminated) return
        scope.asReversed().forEach { (name, kind) ->
            if (name != exclude) emitArcRelease(name, kind)
        }
    }

    /** True when [statements] ends in a `return`, so control cannot fall through. */
    private fun endsWithReturn(statements: List<Statement>?): Boolean {
        val last = statements?.lastOrNull() ?: return false
        return last is ReturnStatement || last.expr is ReturnStatement
    }

    /**
     * Releases for every open scope, innermost first, *without* popping --
     * used just before an early `return`, where the C block braces have not
     * been closed yet.
     */
    private fun emitArcReleasesBeforeReturn(exclude: String?) {
        arcScopes.asReversed().forEach { scope ->
            scope.asReversed().forEach { (name, kind) ->
                if (name != exclude) emitArcRelease(name, kind)
            }
        }
    }

    /** True when [expr] is a bare reference we do not own (needs a retain to store). */
    private fun isBorrowedRef(expr: Expr): Boolean {
        return expr is Identifier || expr is MemberAccessExpr
    }

    /** The scope-tracked local a return statement hands ownership of, if any. */
    private fun returnedArcLocal(expr: Expr): String? {
        val name = (expr as? Identifier)?.value ?: return null
        return if (arcScopes.any { scope -> scope.any { it.first == name } }) name else null
    }

    /** One trait method signature, with concrete (resolved) param and return types. */
    private data class TraitMethodSig(
        val name: String,
        val params: List<String>,
        val returnType: String,
    )

    /** Non-generic user trait names (lower to by-value interface structs). */
    private val traitNames = mutableSetOf<String>()
    /** Trait name -> ordered method signatures (flattened with trait ancestors). */
    private val traitMethodSigs = mutableMapOf<String, List<TraitMethodSig>>()
    /** Class name -> trait names it implements (transitively through trait parents). */
    private val classTraits = mutableMapOf<String, MutableList<String>>()
    /** Mangled method name -> parameter type names, for call-site trait coercion. */
    private val methodParamTypes = mutableMapOf<String, List<String>>()
    /** Free/specialized function name -> parameter type names, for call-site trait coercion. */
    private val functionParamTypes = mutableMapOf<String, List<String>>()
    /** Return type of the function/method body currently being emitted. */
    private var currentReturnType: String? = null

    /**
     * Discover user traits, their flattened method sets, and which classes
     * implement them (via the class parent list). Generic traits are skipped
     * for now: they are used by magic prelude classes, never lowered here.
     */
    private fun collectTraits() {
        val rawTraits = linkedMapOf<String, TraitDecl>()
        compilationUnit.allSources().forEach { source ->
            if (shouldSkipSource(source)) return@forEach
            source.ast.statements.forEach { stmt ->
                val expr: Any? = when (stmt) {
                    is TraitDecl -> stmt
                    is Statement -> stmt.expr
                    else -> null
                }
                if (expr is TraitDecl && !isMagicDecl(expr)) {
                    val base = baseTypeNameOf(expr.name)
                    if (base !in rawTraits) rawTraits[base] = expr
                }
            }
        }

        fun flattenTrait(t: TraitDecl): List<TraitMethodSig> {
            val out = mutableListOf<TraitMethodSig>()
            t.parents.forEach { parent ->
                rawTraits[baseTypeNameOf(parent)]?.let { out.addAll(flattenTrait(it)) }
            }
            // Trait members are signature declarations (no body), so isStub()
            // is true by design -- include every member regardless.
            t.members.forEach { member ->
                out.add(
                    TraitMethodSig(
                        functionLikeName(member.name),
                        member.def.parameters.map { typeNameOf(it.typeSpecifier) },
                        typeNameOf(member.def.returnTypeSpecifier),
                    )
                )
            }
            return out
        }

        rawTraits.forEach { (name, decl) ->
            traitNames.add(name)
            traitMethodSigs[name] = flattenTrait(decl)
        }

        eachClassDecl { decl ->
            val className = typeNameOf(decl.name)
            decl.parents.forEach { parent ->
                val parentBase = baseTypeNameOf(parent)
                if (parentBase in traitNames) {
                    classTraits.getOrPut(className) { mutableListOf() }.add(parentBase)
                }
            }
        }

        // Transitive: implementing trait T also satisfies T's trait parents.
        fun addAncestors(trait: String, seen: MutableSet<String>) {
            rawTraits[trait]?.parents?.forEach { parent ->
                val parentBase = baseTypeNameOf(parent)
                if (parentBase in traitNames && seen.add(parentBase)) {
                    classTraits.forEach { (cls, traits) ->
                        if (trait in traits && parentBase !in traits) traits.add(parentBase)
                    }
                    addAncestors(parentBase, seen)
                }
            }
        }
        traitNames.toList().forEach { addAncestors(it, mutableSetOf()) }
    }

    /** Emit trait interface structs + vtable structs (no trampolines yet). */
    private fun emitTraitStructs() {
        traitNames.sorted().forEach { trait ->
            val sigs = traitMethodSigs[trait] ?: return@forEach
            if (sigs.isEmpty()) return@forEach
            buffer.appendLine("typedef struct $trait $trait;")
            buffer.appendLine("typedef struct ${trait}VTable ${trait}VTable;")
            buffer.appendLine("struct ${trait}VTable")
            buffer.appendLine("{")
            indentLevel++
            sigs.forEach { sig ->
                appendIndented("")
                buffer.append(mapTypeName(sig.returnType))
                buffer.append(" (*")
                buffer.append(sig.name)
                buffer.appendLine(")(void* self);")
            }
            indentLevel--
            buffer.appendLine("};")
            buffer.appendLine()
            buffer.appendLine("struct $trait")
            buffer.appendLine("{")
            indentLevel++
            appendIndentedLine("void* data;")
            appendIndented("")
            buffer.append(trait)
            buffer.appendLine("VTable* vtable;")
            indentLevel--
            buffer.appendLine("};")
            buffer.appendLine()
        }
    }

    /**
     * Emit per (class, trait) trampolines + static vtables. Must run after
     * emitFunctionPrototypes so every class method is registered. Also
     * validates that the class actually provides every trait method.
     */
    private fun emitTraitTables() {
        classTraits.forEach { (className, traits) ->
            traits.distinct().sorted().forEach { trait ->
                val sigs = traitMethodSigs[trait] ?: return@forEach
                if (sigs.isEmpty()) return@forEach
                sigs.forEach { sig ->
                    if (resolveMethodMangled(sig.name, className) == null) {
                        throw IllegalStateException(
                            "Class '$className' implements trait '$trait' but is missing method '${sig.name}'"
                        )
                    }
                }
                sigs.forEach { sig ->
                    val mangled = resolveMethodMangled(sig.name, className)!!
                    appendIndented("static ")
                    buffer.append(mapTypeName(sig.returnType))
                    buffer.append(" ${trait}_${sig.name}_tramp_$className(void* self")
                    sig.params.forEach { param ->
                        buffer.append(", ")
                        buffer.append(mapTypeName(param))
                        buffer.append(" arg${sig.params.indexOf(param)}")
                    }
                    buffer.append(") { return ")
                    buffer.append(mangled)
                    buffer.append("(($className*)self")
                    sig.params.forEachIndexed { i, _ -> buffer.append(", arg$i") }
                    buffer.appendLine("); }")
                }
                appendIndented("static ${trait}VTable ${trait}_vtable_$className = { ")
                buffer.append(sigs.joinToString(", ") { "${trait}_${it.name}_tramp_$className" })
                buffer.appendLine(" };")
            }
        }
    }

    /**
     * Emit an expression, wrapping it in `(Trait){ .data = expr, .vtable = &Trait_vtable_Class }`
     * when the declared target type is a trait and the expression is a class
     * value of a type implementing it.
     */
    private fun emitCoercedTraitValue(expr: Expr, declaredType: String) {
        val argType = receiverTypeOf(expr)
        val implTraits = argType?.let { classTraits[it] }
        if (declaredType in traitNames && implTraits != null && declaredType in implTraits) {
            buffer.append("((")
            buffer.append(declaredType)
            buffer.append("){ .data = ")
            expr.accept(this)
            buffer.append(", .vtable = &")
            buffer.append(declaredType)
            buffer.append("_vtable_")
            buffer.append(argType)
            buffer.append(" })")
        } else {
            expr.accept(this)
        }
    }

    /**
     * One-shot emit of the whole compilation unit into [outputPath].
     * Returns the generated C source (also written to disk).
     *
     * By default the user layer (everything after the runtime prelude) is
     * minified and obfuscated via [OutputMinifier]. The prelude itself stays
     * byte-identical and readable. `GeneratedProvider.minifyOutput = false`
     * (the `--readable` CLI flag, or `build.minify: false`) restores the
     * pretty Jack-style formatting.
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
        val marker = "#endif /* KIRA_RUNTIME_H */"
        val idx = source.lastIndexOf(marker)
        require(idx >= 0) { "C prelude end marker not found in emitted source" }
        val cut = idx + marker.length
        val prelude = source.substring(0, cut)
        val user = source.substring(cut)
        val reserved = OutputMinifier.extractIdentifiers(fetchBundleFileContents()) +
            OutputMinifier.extractIdentifiers(fetchTemplateFileContents()) +
            C_KEYWORDS + externFunctions.values + opaqueTypes + setOf("main", "this", "_empty")
        val rename = OutputMinifier.buildRenameMap(collectUserSymbols(), reserved)
        return prelude + "\n" + OutputMinifier.minify(MinifyLanguage.C, user, rename)
    }

    /**
     * Every identifier codegen created while emitting the user layer: module
     * surface names plus the composed forms (mangled methods, specializations,
     * vtable / trampoline names) the emitter actually wrote.
     */
    private fun collectUserSymbols(): Set<String> {
        userSymbols.addAll(userClassNames)
        userSymbols.addAll(methodReturnTypes.keys)
        userSymbols.addAll(classSpecializations.keys)
        userSymbols.addAll(functionSpecializations.keys)
        traitNames.forEach { trait ->
            userSymbols.add(trait)
            userSymbols.add("${trait}VTable")
        }
        classTraits.forEach { (cls, traits) ->
            traits.distinct().forEach { trait ->
                traitMethodSigs[trait]?.forEach { sig ->
                    userSymbols.add("${trait}_${sig.name}_tramp_$cls")
                }
                userSymbols.add("${trait}_vtable_$cls")
            }
        }
        return userSymbols.toSet()
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
        collectUserClasses()
        collectTraits()

        // Layer 2 -- user program
        // 1) Forward-declare structs (concrete + specialized)
        // 2) Emit full struct + enum bodies (complete types before prototypes)
        // 3) Trait interface + vtable structs
        // 4) Forward-declare free functions + methods (+ specialized generics)
        // 5) Trait trampolines + static vtables (needs registered methods)
        // 6) Emit everything else -- classes/enums/specialized already emitted
        emitStructForwardDecls()
        emitStructBodies()
        emitSpecializedClassBodies()
        emitTraitStructs()
        emitEnumBodies()
        emitFunctionPrototypes()
        emitTraitTables()
        emitSpecializedFunctionBodies()

        emittableSources().forEach { source ->
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
                userSymbols.add(field.name.value)
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
            val mangledMethod = registerMethod(
                mangled,
                methodName,
                returnTypeName,
                method.def.parameters.map { resolveKiraTypeName(it.typeSpecifier) }
            )
            userSymbols.add(methodName)
            method.def.parameters.forEach { userSymbols.add(it.name.value) }

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
            val savedReturnType = currentReturnType
            currentReturnType = returnTypeName
            method.def.body?.forEach { it.accept(this) }
            currentReturnType = savedReturnType
            currentMethodClass = null
            method.def.parameters.forEach { param ->
                knownValueTypes.remove(param.name.value)
            }
            indentLevel--
            appendIndentedLine("}")
            buffer.appendLine()
        }

        // ARC factory for the specialized class: Box_Int32_new(...) with RC=1.
        if (!fields.isEmpty()) {
            userSymbols.add("${mangled}_new")
            userSymbols.add("${mangled}_finalize")
            emitClassFinalizer(
                mangled,
                fields.filter { userClassNames.contains(resolveKiraTypeName(it.type)) }.map { it.name.value }
            )
            appendIndented("simple ")
            buffer.append(mapTypeName(mangled))
            buffer.append(" ")
            buffer.append(mangled)
            buffer.append("_new(")
            fields.forEachIndexed { i, field ->
                if (i > 0) buffer.append(", ")
                buffer.append(mapTypeName(resolveKiraTypeName(field.type)))
                buffer.append(" ")
                buffer.append(field.name.value)
            }
            buffer.appendLine(")")
            appendIndentedLine("{")
            indentLevel++
            val ownedM = fields.filter { userClassNames.contains(resolveKiraTypeName(it.type)) }
                .map { it.name.value }
            appendIndented("")
            buffer.append(mapTypeName(mangled))
            buffer.append(" self = (")
            buffer.append(mangled)
            buffer.append("*)kira_rc_alloc_with(sizeof(")
            buffer.append(mangled)
            buffer.append("), ")
            buffer.append(if (ownedM.isEmpty()) "null" else "${mangled}_finalize")
            buffer.appendLine(");")
            fields.forEach { field ->
                appendIndented("self->")
                buffer.append(field.name.value)
                buffer.append(" = ")
                buffer.append(field.name.value)
                buffer.appendLine(";")
            }
            appendIndentedLine("return self;")
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
            userSymbols.add(param.name.value)
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
        emittableSources().forEach { source ->
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
                        val kiraName = functionLikeName(expr.name)
                        functionParamTypes[kiraName] =
                            expr.def.parameters.map { typeNameOf(it.typeSpecifier) }
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
                        val mangled = registerMethod(
                            className,
                            methodName,
                            returnTypeName,
                            method.def.parameters.map { typeNameOf(it.typeSpecifier) }
                        )
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
            functionParamTypes[mangled] =
                template.def.parameters.map { resolveKiraTypeName(it.typeSpecifier) }
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
                val mangled = registerMethod(
                    classMangled,
                    methodName,
                    returnTypeName,
                    method.def.parameters.map { resolveKiraTypeName(it.typeSpecifier) }
                )
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
            val paramType = typeNameOf(param.typeSpecifier)
            knownValueTypes[param.name.value] = paramType
            recordContainerTypeArgs(param.name.value, paramType, param.typeSpecifier)
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

    /**
     * True when a stdlib source carries real Kira code that must be emitted.
     *
     * `@_magic` declarations are typechecker-only signatures (the runtime
     * prelude defines them), so a stdlib module full of magic stays skipped.
     * A module with non-magic function bodies -- the stdlib written in Kira
     * itself -- must be walked like user code: `visitFunctionDecl` emits the
     * body and skips the magic members.
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
        // Magic names resolve through the loaded binding manifest first; the
        // intrinsic table remains as a fallback for unbound names (print family
        // is handled before this point, so it never arrives here in practice).
        return CMagicBindingTable.resolveFunctionOrNull(canonical)
            ?: CIntrinsicsTable.resolveFunctionOrNull(canonical)
            ?: rawName.removePrefix("@")
    }

    private fun includeForIntrinsic(rawName: String) {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        val includes = CMagicBindingTable.includesOrNull(canonical)
            ?: CIntrinsicsTable.resolveIncludes(canonical)
        requiredIncludes.addAll(includes)
    }

    private fun mapTypeName(typeName: String): String {
        CMagicTypeLowering.resolve(typeName)?.let { return it.cType }
        // Foreign opaque handles are C pointers (never Kira ARC objects).
        if (opaqueTypes.contains(typeName)) {
            return "$typeName*"
        }
        // User classes are ARC heap objects: every reference is a pointer.
        if (userClassNames.contains(typeName)) {
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
        return typeName != null && typeName in CMagicTypeLowering.slotContainers
    }

    /**
     * Remember `Map<Str, Int32>` as `[Str, Int32]` for [name] so slot casts can
     * recover the element type later. No-op for non-container types.
     */
    private fun recordContainerTypeArgs(name: String, typeName: String, type: Type) {
        if (typeName !in CMagicTypeLowering.slotContainers) return
        if (type.children.isEmpty()) return
        containerTypeArgs[name] = type.children.map { resolveKiraTypeName(it) }
    }

    /** Element type of the Arr literal currently being emitted, when known. */
    private var pendingArrayElementType: String? = null

    private fun isStrType(kiraType: String?): Boolean {
        return kiraType == "Str" || kiraType == "String"
    }

    /** True when [expr] already produces a `Maybe` (so it needs no wrapping). */
    private fun isMaybeTyped(expr: Expr): Boolean {
        return receiverTypeOf(expr) == "Maybe"
    }

    /** True when [expr] is the stdlib `null` global. */
    private fun isNullLiteral(expr: Expr): Boolean {
        return expr is NullLiteral || (expr is Identifier && expr.value == "null")
    }

    /** Wrap a plain value into `Maybe<T>`; `null` becomes the absent case. */
    private fun emitMaybeCoercion(expr: Expr, elementType: String?) {
        if (isNullLiteral(expr)) {
            buffer.append("Maybe_none()")
            return
        }
        buffer.append("Maybe_some(")
        emitSlotIn(elementType, expr)
        buffer.append(")")
    }

    /** Kira types whose C representation is a pointer, so they slot via intptr_t. */
    private fun isPointerSlotType(kiraType: String?): Boolean {
        if (kiraType == null) return false
        return kiraType == "Str" || kiraType == "String" || kiraType == "Any" ||
            userClassNames.contains(kiraType) || opaqueTypes.contains(kiraType)
    }

    /** Wrap an element as it goes *into* a slot container. */
    private fun emitSlotIn(elementType: String?, value: Expr) {
        buffer.append(if (isPointerSlotType(elementType)) "KIRA_SLOT_PTR(" else "KIRA_SLOT(")
        value.accept(this)
        buffer.append(")")
    }

    /** Unwrap a slot back to [elementType]; emits [inner] as the slot expression. */
    private fun emitSlotOut(elementType: String?, inner: () -> Unit) {
        if (elementType == null || elementType == "Void") {
            inner()
            return
        }
        val cType = mapTypeName(elementType)
        buffer.append(if (isPointerSlotType(elementType)) "KIRA_UNSLOT_PTR(" else "KIRA_UNSLOT(")
        buffer.append(cType)
        buffer.append(", ")
        inner()
        buffer.append(")")
    }

    /** Kira type arguments recorded for a container-typed value (`Map<Str, Int32>` -> [Str, Int32]). */
    private fun receiverTypeArgs(expr: Expr): List<String> {
        val name = when (expr) {
            is Identifier -> expr.value
            is MemberAccessExpr -> (expr.member as? Identifier)?.value
            is FunctionCallExpr -> functionLikeName(expr.name)
            else -> null
        } ?: return emptyList()
        return containerTypeArgs[name] ?: emptyList()
    }

    /** `Type_method(&recv, ...)` with each argument emitted by its own lambda. */
    private fun emitRuntimeCall(
        fn: String,
        receiver: Expr,
        byPointer: Boolean = true,
        argEmitters: List<() -> Unit> = emptyList()
    ) {
        buffer.append(fn)
        buffer.append("(")
        if (byPointer) buffer.append("&")
        receiver.accept(this)
        argEmitters.forEach {
            buffer.append(", ")
            it()
        }
        buffer.append(")")
    }

    /**
     * Lower a stdlib method call on a magic receiver. Returns true if handled.
     *
     * Containers erase their element type to `KiraSlot`, so anything crossing
     * that boundary is wrapped on the way in and cast back on the way out using
     * the type arguments recorded at declaration time.
     */
    private fun tryEmitCollectionMethod(methodName: String, receiver: Expr, args: List<Expr>): Boolean {
        val recvType = receiverTypeOf(receiver) ?: return false
        val targs = receiverTypeArgs(receiver)
        return when (recvType) {
            "Str", "String" -> emitStrMethod(methodName, receiver, args)
            "Num", "Int", "Int8", "Int16", "Int32", "Int64",
            "Float", "Float32", "Float64" -> emitNumMethod(methodName, recvType, receiver, args)
            "Arr" -> emitArrMethod(methodName, receiver, args, targs.getOrNull(0))
            "List" -> emitListMethod(methodName, receiver, args, targs.getOrNull(0))
            "Map" -> emitMapMethod(methodName, receiver, args, targs.getOrNull(0), targs.getOrNull(1))
            "Set" -> emitSetMethod(methodName, receiver, args, targs.getOrNull(0))
            "Stack", "Queue", "Deque" -> emitLinearAdtMethod(methodName, recvType, receiver, args, targs.getOrNull(0))
            "Maybe" -> emitMaybeMethod(methodName, receiver, args, targs.getOrNull(0))
            "Result" -> emitResultMethod(methodName, receiver, args, targs.getOrNull(0), targs.getOrNull(1))
            else -> false
        }
    }

    // ---- Str -------------------------------------------------------------

    private fun emitStrMethod(methodName: String, receiver: Expr, args: List<Expr>): Boolean {
        val arity = when (methodName) {
            "length", "isEmpty", "trim", "toLower", "toUpper", "hashCode" -> 0
            "charAt", "contains", "startsWith", "endsWith", "equals", "split" -> 1
            "substring" -> 2
            else -> return false
        }
        if (args.size != arity) return false
        // Str is already a pointer, so these take the receiver by value.
        buffer.append("Str_")
        buffer.append(methodName)
        buffer.append("(")
        receiver.accept(this)
        args.forEach {
            buffer.append(", ")
            it.accept(this)
        }
        buffer.append(")")
        return true
    }

    // ---- Num -------------------------------------------------------------

    private fun emitNumMethod(methodName: String, recvType: String, receiver: Expr, args: List<Expr>): Boolean {
        // Numeric conversions are plain C casts; abs picks the right libc call.
        val target = when (methodName) {
            "toInt32" -> "Int32"
            "toInt64" -> "Int64"
            "toFloat32" -> "Float32"
            "toFloat64" -> "Float64"
            "abs" -> null
            else -> return false
        }
        if (args.isNotEmpty()) return false
        if (target != null) {
            buffer.append("((")
            buffer.append(mapTypeName(target))
            buffer.append(")(")
            receiver.accept(this)
            buffer.append("))")
            return true
        }
        val isFloat = recvType == "Float" || recvType == "Float32" || recvType == "Float64"
        if (isFloat) {
            requiredIncludes.add("math.h")
            buffer.append("fabs(")
        } else {
            requiredIncludes.add("stdlib.h")
            buffer.append("llabs(")
        }
        receiver.accept(this)
        buffer.append(")")
        return true
    }

    // ---- Arr -------------------------------------------------------------

    private fun emitArrMethod(methodName: String, receiver: Expr, args: List<Expr>, elem: String?): Boolean {
        when (methodName) {
            "size", "isEmpty" -> {
                emitRuntimeCall("Arr_$methodName", receiver)
                return true
            }
            "get" -> {
                if (args.size != 1) return false
                // Arr_get takes the receiver by value, not by pointer.
                emitSlotOut(elem) {
                    buffer.append("Arr_get(")
                    receiver.accept(this)
                    buffer.append(", ")
                    args[0].accept(this)
                    buffer.append(")")
                }
                return true
            }
            "set" -> {
                if (args.size != 2) return false
                buffer.append("Arr_set(")
                receiver.accept(this)
                buffer.append(", ")
                args[0].accept(this)
                buffer.append(", ")
                emitSlotIn(elem, args[1])
                buffer.append(")")
                return true
            }
            "contains" -> {
                if (args.size != 1) return false
                emitRuntimeCall("Arr_contains", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                return true
            }
            "clone" -> {
                emitRuntimeCall("Arr_clone", receiver)
                return true
            }
            else -> return false
        }
    }

    // ---- List ------------------------------------------------------------

    private fun emitListMethod(methodName: String, receiver: Expr, args: List<Expr>, elem: String?): Boolean {
        when (methodName) {
            "size", "isEmpty", "clear", "toArr" -> {
                emitRuntimeCall("List_$methodName", receiver)
                return true
            }
            "add" -> {
                if (args.size != 1) return false
                emitRuntimeCall("List_add", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                return true
            }
            "addAll" -> {
                if (args.size != 1) return false
                emitRuntimeCall("List_addAll", receiver, argEmitters = listOf { args[0].accept(this) })
                return true
            }
            "contains" -> {
                if (args.size != 1) return false
                emitRuntimeCall("List_contains", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                return true
            }
            "get" -> {
                if (args.size != 1) return false
                emitSlotOut(elem) {
                    emitRuntimeCall("List_get", receiver, argEmitters = listOf { args[0].accept(this) })
                }
                return true
            }
            "set" -> {
                if (args.size != 2) return false
                emitRuntimeCall(
                    "List_set", receiver,
                    argEmitters = listOf({ args[0].accept(this) }, { emitSlotIn(elem, args[1]) })
                )
                return true
            }
            // `remove` on a List is positional, matching removeAt.
            "removeAt", "remove" -> {
                if (args.size != 1) return false
                emitSlotOut(elem) {
                    emitRuntimeCall("List_removeAt", receiver, argEmitters = listOf { args[0].accept(this) })
                }
                return true
            }
            else -> return false
        }
    }

    // ---- Map -------------------------------------------------------------

    private fun emitMapMethod(
        methodName: String,
        receiver: Expr,
        args: List<Expr>,
        keyType: String?,
        valueType: String?
    ): Boolean {
        when (methodName) {
            "size", "isEmpty", "clear" -> {
                emitRuntimeCall("Map_$methodName", receiver)
                return true
            }
            "put" -> {
                if (args.size != 2) return false
                emitRuntimeCall(
                    "Map_put", receiver,
                    argEmitters = listOf({ emitSlotIn(keyType, args[0]) }, { emitSlotIn(valueType, args[1]) })
                )
                return true
            }
            // get / remove return Maybe<V>; the payload is unwrapped at use sites.
            "get", "remove" -> {
                if (args.size != 1) return false
                emitRuntimeCall("Map_$methodName", receiver, argEmitters = listOf { emitSlotIn(keyType, args[0]) })
                return true
            }
            "containsKey" -> {
                if (args.size != 1) return false
                emitRuntimeCall("Map_containsKey", receiver, argEmitters = listOf { emitSlotIn(keyType, args[0]) })
                return true
            }
            "containsValue" -> {
                if (args.size != 1) return false
                emitRuntimeCall("Map_containsValue", receiver, argEmitters = listOf { emitSlotIn(valueType, args[0]) })
                return true
            }
            "keys", "valuesArr", "entries" -> {
                emitRuntimeCall("Map_$methodName", receiver)
                return true
            }
            else -> return false
        }
    }

    // ---- Set -------------------------------------------------------------

    private fun emitSetMethod(methodName: String, receiver: Expr, args: List<Expr>, elem: String?): Boolean {
        when (methodName) {
            "size", "isEmpty", "clear", "toArr" -> {
                emitRuntimeCall("Set_$methodName", receiver)
                return true
            }
            "add", "remove", "contains" -> {
                if (args.size != 1) return false
                emitRuntimeCall("Set_$methodName", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                return true
            }
            else -> return false
        }
    }

    // ---- Stack / Queue / Deque -------------------------------------------

    private fun emitLinearAdtMethod(
        methodName: String,
        recvType: String,
        receiver: Expr,
        args: List<Expr>,
        elem: String?
    ): Boolean {
        val pushLike = setOf("push", "enqueue", "pushFront", "pushBack")
        // pop/peek variants return Maybe<T>; payload unwrapped at use sites.
        val popLike = setOf("pop", "dequeue", "peek", "popFront", "popBack")
        when {
            methodName in setOf("size", "isEmpty", "clear") -> {
                emitRuntimeCall("${recvType}_$methodName", receiver)
                return true
            }
            methodName in pushLike -> {
                if (args.size != 1) return false
                emitRuntimeCall("${recvType}_$methodName", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                return true
            }
            methodName in popLike -> {
                if (args.isNotEmpty()) return false
                emitRuntimeCall("${recvType}_$methodName", receiver)
                return true
            }
            else -> return false
        }
    }

    // ---- Maybe / Result --------------------------------------------------

    private fun emitMaybeMethod(methodName: String, receiver: Expr, args: List<Expr>, elem: String?): Boolean {
        when (methodName) {
            "isSome", "isNone" -> {
                emitRuntimeCall("Maybe_$methodName", receiver)
                return true
            }
            "unwrap" -> {
                emitSlotOut(elem) { emitRuntimeCall("Maybe_unwrap", receiver) }
                return true
            }
            "unwrapOr" -> {
                if (args.size != 1) return false
                emitSlotOut(elem) {
                    emitRuntimeCall("Maybe_unwrapOr", receiver, argEmitters = listOf { emitSlotIn(elem, args[0]) })
                }
                return true
            }
            else -> return false
        }
    }

    private fun emitResultMethod(
        methodName: String,
        receiver: Expr,
        args: List<Expr>,
        okType: String?,
        errType: String?
    ): Boolean {
        when (methodName) {
            "isOk", "isErr" -> {
                emitRuntimeCall("Result_$methodName", receiver)
                return true
            }
            "unwrap" -> {
                emitSlotOut(okType) { emitRuntimeCall("Result_unwrap", receiver) }
                return true
            }
            "unwrapErr" -> {
                emitSlotOut(errType) { emitRuntimeCall("Result_unwrapErr", receiver) }
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
        userSymbols.clear()
        requiredIncludes.clear()
        knownValueTypes.clear()
        enumTypeNames.clear()
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

    private fun registerMethod(
        className: String,
        methodName: String,
        returnType: String,
        paramTypes: List<String> = emptyList(),
    ): String {
        val mangled = mangleMethodName(className, methodName)
        methodReturnTypes[mangled] = returnType
        methodParamTypes[mangled] = paramTypes
        methodsBySimpleName.getOrPut(methodName) { mutableListOf() }.add(className to mangled)
        knownValueTypes[mangled] = returnType
        userSymbols.add(mangled)
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
                val nameExpr = expr.name
                if (nameExpr is MemberAccessExpr) {
                    // Chained call: the receiver's type is the inner method's
                    // return type, so `s.trim().length()` can see a Str receiver.
                    val methodName = (nameExpr.member as? Identifier)?.value
                    val innerType = receiverTypeOf(nameExpr.origin)
                    stdlibMethodReturnType(innerType, methodName, receiverTypeArgs(nameExpr.origin))
                        ?: traitMethodSigs[innerType]?.firstOrNull { it.name == methodName }?.returnType
                        ?: methodName?.let { resolveMethodMangled(it, innerType) }
                            ?.let { methodReturnTypes[it] }
                } else {
                    val name = functionLikeName(nameExpr)
                    knownValueTypes[name] ?: methodReturnTypes[name]
                }
            }
            is ObjectInitExpr -> typeNameOf(expr.typeName)
            else -> null
        }
    }

    /**
     * Types that keep direct C operators: scalars, bools, strings, and the
     * Any/Num roots. Anything else statically known (user classes, enums,
     * opaque handles, containers) is a candidate for an `@op_*` overload.
     */
    private val primitiveTypeNames = setOf(
        "Int8", "Int16", "Int32", "Int64",
        "Float32", "Float64", "Bool", "Char",
        "Str", "String", "Num", "Int", "Float", "Any"
    )

    private fun isKnownNonPrimitive(expr: Expr): Boolean {
        val t = receiverTypeOf(expr) ?: return false
        // Enum types are int-like value types: direct C operators (==, <, ...)
        // work and no @op_* definition is ever emitted for them.
        return t !in primitiveTypeNames && t !in enumTypeNames
    }

    /**
     * Emit a call to an operator intrinsic (`op_add(...)`, ...). User
     * overloads named `@op_add` lower to the same C identifier, so an
     * overloaded expression and a literal `@op_add(a, b)` call agree.
     */
    private fun emitOperatorCall(opName: String, args: List<Expr>) {
        buffer.append(mapIntrinsicName(opName))
        buffer.append("(")
        args.forEachIndexed { index, arg ->
            if (index > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(")")
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
        pushArcScope()
        ifSelectionStatement.thenStatements.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(ifSelectionStatement.thenStatements))
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
        pushArcScope()
        ifElseIfBranchNode.statements.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(ifElseIfBranchNode.statements))
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        buffer.appendLine("else")
        appendIndentedLine("{")
        indentLevel++
        pushArcScope()
        elseBranchNode.statements.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(elseBranchNode.statements))
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        appendIndented("while(")
        whileIterationStatement.condition.accept(this)
        buffer.appendLine(")")
        appendIndentedLine("{")
        indentLevel++
        pushArcScope()
        whileIterationStatement.statements.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(whileIterationStatement.statements))
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        appendIndentedLine("do")
        appendIndentedLine("{")
        indentLevel++
        pushArcScope()
        doWhileIterationStatement.statements.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(doWhileIterationStatement.statements))
        indentLevel--
        appendIndented("} while(")
        doWhileIterationStatement.condition.accept(this)
        buffer.appendLine(");")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement) {
        // Releases must precede the `return`, or they are dead code. The
        // returned local (if any) keeps its +1 -- ownership moves to the caller.
        val moved = if (returnStatement.expr !is NoExpr) {
            returnedArcLocal(returnStatement.expr)
        } else {
            null
        }
        emitArcReleasesBeforeReturn(moved)
        appendIndented("return")
        if (returnStatement.expr !is NoExpr) {
            buffer.append(" ")
            val rt = currentReturnType
            if (rt != null && rt in traitNames) {
                emitCoercedTraitValue(returnStatement.expr, rt)
            } else {
                returnStatement.expr.accept(this)
            }
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
            pushArcScope()
            forIterationStatement.body.forEach { it.accept(this) }
            popArcScope(terminated = endsWithReturn(forIterationStatement.body))
            indentLevel--
            appendIndentedLine("}")
            return
        }

        appendIndentedLine("/* unsupported for-target; stub loop */")
        appendIndentedLine("for(;;)")
        appendIndentedLine("{")
        indentLevel++
        pushArcScope()
        forIterationStatement.body.forEach { it.accept(this) }
        popArcScope(terminated = endsWithReturn(forIterationStatement.body))
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
        val opName = OperatorIntrinsics.binaryName(binaryExpr.operator)
        // Non-primitive operands desugar to the op_* overload. When a side's
        // type is unknown we keep the direct C operator (status quo).
        if (opName != null &&
            (isKnownNonPrimitive(binaryExpr.leftExpr) || isKnownNonPrimitive(binaryExpr.rightExpr))
        ) {
            emitOperatorCall(opName, listOf(binaryExpr.leftExpr, binaryExpr.rightExpr))
            return
        }
        buffer.append("(")
        binaryExpr.leftExpr.accept(this)
        buffer.append(" ${binaryOpSymbol(binaryExpr.operator)} ")
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
        storeInto(assignmentExpr.target, owned = !isBorrowedRef(assignmentExpr.value)) {
            assignmentExpr.value.accept(this)
        }
    }

    /**
     * Emit `target = <value>`, routing class-typed stores through ARC helpers.
     * [owned] says the incoming value is a fresh +1 (constructor or callee
     * return); borrowed values (plain reads like `a = a`) go through
     * kira_rc_store which retains first.
     */
    private fun storeInto(target: Expr, owned: Boolean, emitValue: () -> Unit) {
        val targetType = receiverTypeOf(target)
        if (targetType != null && userClassNames.contains(targetType)) {
            val helper = if (owned) "kira_rc_store_owned" else "kira_rc_store"
            buffer.append(helper)
            buffer.append("((Void**)&")
            target.accept(this)
            buffer.append(", ")
            emitValue()
            buffer.append(")")
            return
        }
        target.accept(this)
        buffer.append(" = ")
        emitValue()
    }

    /**
     * Best-effort printf format for a single trace/print argument.
     * Strings and string-returning calls use "%s"; everything else "%d"
     * (good enough for Int* in the baseline backend).
     */
    private fun printfFormatFor(expr: Expr): String {
        return when (expr) {
            is StringLiteral -> "%s"
            is FloatLiteral -> "%g"
            is IntegerLiteral -> "%d"
            is FunctionCallExpr -> {
                // Method call: name is MemberAccess
                val nameExpr = expr.name
                if (nameExpr is MemberAccessExpr) {
                    val methodName = (nameExpr.member as? Identifier)?.value
                    val recvType = receiverTypeOf(nameExpr.origin)
                    // Trait dispatch: use the trait signature's return type.
                    val traitRet = if (recvType != null && recvType in traitNames && methodName != null) {
                        traitMethodSigs[recvType]?.firstOrNull { it.name == methodName }?.returnType
                    } else {
                        null
                    }
                    val stdlibRet = stdlibMethodReturnType(
                        recvType, methodName, receiverTypeArgs(nameExpr.origin)
                    )
                    if (traitRet != null) {
                        formatForTypeName(traitRet)
                    } else if (stdlibRet != null) {
                        formatForTypeName(stdlibRet)
                    } else {
                        val mangled = methodName?.let { resolveMethodMangled(it, recvType) }
                        formatForTypeName(mangled?.let { methodReturnTypes[it] })
                    }
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
            "Float32", "Float64", "Float" -> "%g"
            "Bool" -> "%d"
            // int64_t is `long` on LP64 and `long long` on LLP64; the print site
            // casts to long long so %lld is right on both.
            "Int64", "UInt64" -> "%lld"
            else -> "%d"
        }
    }

    /**
     * Return type of a stdlib method on a magic receiver, mirroring
     * [tryEmitCollectionMethod]. Used for print-format selection, where the
     * user-class method table has nothing to say.
     */
    private fun stdlibMethodReturnType(
        recvType: String?,
        methodName: String?,
        typeArgs: List<String>
    ): String? {
        if (recvType == null || methodName == null) return null
        return when (recvType) {
            "Str", "String" -> when (methodName) {
                "length" -> "Int32"
                "isEmpty", "contains", "startsWith", "endsWith", "equals" -> "Bool"
                "substring", "charAt", "trim", "toLower", "toUpper" -> "Str"
                "hashCode" -> "Int64"
                "split" -> "List"
                else -> null
            }
            "Num", "Int", "Int8", "Int16", "Int32", "Int64",
            "Float", "Float32", "Float64" -> when (methodName) {
                "toInt32" -> "Int32"
                "toInt64" -> "Int64"
                "toFloat32" -> "Float32"
                "toFloat64" -> "Float64"
                "abs" -> recvType
                else -> null
            }
            "Arr", "List" -> when (methodName) {
                "size" -> "Int32"
                "isEmpty", "contains" -> "Bool"
                "get", "removeAt", "remove" -> typeArgs.getOrNull(0)
                "clone", "toArr" -> "Arr"
                else -> null
            }
            "Map" -> when (methodName) {
                "size" -> "Int32"
                "isEmpty", "containsKey", "containsValue" -> "Bool"
                "get", "remove" -> "Maybe"
                "keys", "valuesArr", "entries" -> "Arr"
                else -> null
            }
            "Set" -> when (methodName) {
                "size" -> "Int32"
                "isEmpty", "add", "remove", "contains" -> "Bool"
                "toArr" -> "Arr"
                else -> null
            }
            "Stack", "Queue", "Deque" -> when (methodName) {
                "size" -> "Int32"
                "isEmpty" -> "Bool"
                "pop", "peek", "dequeue", "popFront", "popBack" -> "Maybe"
                else -> null
            }
            "Maybe" -> when (methodName) {
                "isSome", "isNone" -> "Bool"
                "unwrap", "unwrapOr" -> typeArgs.getOrNull(0)
                else -> null
            }
            "Result" -> when (methodName) {
                "isOk", "isErr" -> "Bool"
                "unwrap" -> typeArgs.getOrNull(0)
                "unwrapErr" -> typeArgs.getOrNull(1)
                else -> null
            }
            else -> null
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
        val spec = printfFormatFor(arg)
        val fmt = spec + if (isPrintln) "\\n" else ""
        val needsWiden = spec == "%lld"
        buffer.append(if (isEprint) "fprintf(stderr, \"" else "print(\"")
        buffer.append(fmt)
        buffer.append("\", ")
        if (needsWiden) buffer.append("(long long)(")
        arg.accept(this)
        if (needsWiden) buffer.append(")")
        buffer.append(")")
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
            // Trait dispatch: recv.vtable->method(recv.data, args)
            if (recvType != null && recvType in traitNames &&
                traitMethodSigs[recvType]?.any { it.name == methodName } == true
            ) {
                nameExpr.origin.accept(this)
                buffer.append(".vtable->")
                buffer.append(methodName)
                buffer.append("(")
                nameExpr.origin.accept(this)
                buffer.append(".data")
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
            val mangled = resolveMethodMangled(methodName, recvType) ?: methodName
            buffer.append(mangled)
            buffer.append("(")
            if (recvType != null && userClassNames.contains(recvType)) {
                // ARC class receiver is already a heap pointer.
                nameExpr.origin.accept(this)
            } else {
                // Value/struct receiver: pass by address for mutable field access.
                buffer.append("&")
                nameExpr.origin.accept(this)
            }
            val paramTypes = methodParamTypes[mangled]
            functionCallExpr.positionalParameters.forEachIndexed { i, param ->
                buffer.append(", ")
                emitCoercedTraitValue(param.value, paramTypes?.getOrNull(i) ?: "Any")
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
            val paramTypes = methodParamTypes[currentMethodMangled]
            args.forEachIndexed { index, arg ->
                buffer.append(", ")
                emitCoercedTraitValue(arg, paramTypes?.getOrNull(index) ?: "Any")
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
        val paramTypes = functionParamTypes[rawName] ?: functionParamTypes[functionName]
        args.forEachIndexed { index, arg ->
            if (index > 0) buffer.append(", ")
            emitCoercedTraitValue(arg, paramTypes?.getOrNull(index) ?: "Any")
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
        val opName = OperatorIntrinsics.binaryName(compoundAssignmentExpr.operator)
        // a += b on a non-primitive becomes a = op_add(a, b). The call result
        // is a fresh +1 (owned); storeInto keeps class-typed stores on ARC.
        if (opName != null && isKnownNonPrimitive(compoundAssignmentExpr.left)) {
            val target = compoundAssignmentExpr.left
            storeInto(target, owned = true) {
                emitOperatorCall(opName, listOf(target, compoundAssignmentExpr.right))
            }
            return
        }
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
        // ARC user classes are heap pointers: member access uses "->".
        // Magic collections / value types stay on ".".
        val originType = receiverTypeOf(origin)
        buffer.append(if (originType != null && userClassNames.contains(originType)) "->" else ".")
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
        // User classes (concrete + specialized) construct via ARC factory: Class_new(...)
        if (userClassNames.contains(typeName) || genericClassTemplates.containsKey(baseName)) {
            val fieldTypes = userClassFields[typeName].orEmpty()
            buffer.append(typeName)
            buffer.append("_new(")
            objectInitExpr.positionalArgs.forEachIndexed { i, arg ->
                if (i > 0) buffer.append(", ")
                // A field takes ownership of what it holds, so each class-typed
                // argument must arrive with a +1. Fresh temporaries already have
                // one; a borrowed local needs a retain first.
                val fieldType = fieldTypes.getOrNull(i)?.second
                if (fieldType != null && userClassNames.contains(fieldType) && isBorrowedRef(arg)) {
                    buffer.append("(")
                    buffer.append(mapTypeName(fieldType))
                    buffer.append(")kira_rc_retained(")
                    arg.accept(this)
                    buffer.append(")")
                } else {
                    arg.accept(this)
                }
            }
            buffer.append(")")
            return
        }
        // Empty container constructors use runtime helpers.
        if (objectInitExpr.positionalArgs.isEmpty()) {
            when (baseName) {
                "Map" -> {
                    // Key kind decides hashing/equality: Str keys compare by content.
                    val keyType = objectInitExpr.typeName.children.firstOrNull()?.let { resolveKiraTypeName(it) }
                    buffer.append(if (isStrType(keyType)) "Map_new_s()" else "Map_new_i()")
                    return
                }
                "Arr" -> {
                    buffer.append("Arr_empty()")
                    return
                }
                "List", "Set", "Stack", "Queue", "Deque" -> {
                    buffer.append("${baseName}_new()")
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
        // Compound-literal backed Arr view over KiraSlot elements. Lifetime is
        // the enclosing block -- fine for locals and immediate call arguments.
        val n = arrayLiteral.value.size
        if (n == 0) {
            // (KiraSlot[]){ } is a GCC extension with zero size; prefer the helper.
            buffer.append("Arr_empty()")
            return
        }
        val elem = pendingArrayElementType
        buffer.append("Arr_lit((KiraSlot[]){ ")
        arrayLiteral.value.forEachIndexed { i, expr ->
            if (i > 0) buffer.append(", ")
            // Integer literals already widen to KiraSlot; only pointers need the cast.
            if (isPointerSlotType(elem)) emitSlotIn(elem, expr) else expr.accept(this)
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
        userSymbols.add(variableDecl.name.value)
        val typeName = typeNameOf(variableDecl.type)
        recordContainerTypeArgs(variableDecl.name.value, typeName, variableDecl.type)
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
        // Track locals needing scope-end cleanup: class references (refcounted)
        // and containers (own a heap buffer).
        if (userClassNames.contains(typeName) || typeName in disposableContainers) {
            registerArcLocal(variableDecl.name.value, typeName)
        }
        appendIndented("")
        variableDecl.type.accept(this)
        buffer.append(" ")
        variableDecl.name.accept(this)
        if (variableDecl.value != null) {
            buffer.append(" = ")
            // Empty Map/Arr typed locals with missing/empty init
            val value = variableDecl.value!!
            // Let an Arr literal see its declared element type so pointer
            // elements (Arr<Str>) get slot-cast instead of truncated.
            val previousElem = pendingArrayElementType
            pendingArrayElementType = containerTypeArgs[variableDecl.name.value]?.firstOrNull()
            if (value is ObjectInitExpr && value.positionalArgs.isEmpty() && isCollectionType(typeName)) {
                value.accept(this)
            } else if (typeName == "Maybe" && !isMaybeTyped(value)) {
                // `p: Maybe<Pet> = null` -> Maybe_none(); any other value is
                // wrapped as present. Maybe is the only nullable shape.
                emitMaybeCoercion(value, containerTypeArgs[variableDecl.name.value]?.firstOrNull())
            } else {
                emitCoercedTraitValue(value, typeName)
            }
            pendingArrayElementType = previousElem
            // `b: Pet = a` copies a reference we do not own; without a retain
            // both names would release the same object.
            if (userClassNames.contains(typeName) && isBorrowedRef(value)) {
                buffer.appendLine(";")
                appendIndented("kira_rc_retain(")
                variableDecl.name.accept(this)
                buffer.append(")")
            }
        } else if (isCollectionType(typeName)) {
            buffer.append(" = ")
            when (typeName) {
                "Map" -> buffer.append("Map_new_i()")
                "Arr" -> buffer.append("Arr_empty()")
                "Maybe" -> buffer.append("Maybe_none()")
                "Result" -> buffer.append("Result_err(0)")
                else -> buffer.append("${typeName}_new()")
            }
        } else if (userClassNames.contains(typeName)) {
            // Uninitialized class-typed local: null-init so scope-end release is safe.
            buffer.append(" = null")
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
        userSymbols.add(functionName)
        functionDecl.def.parameters.forEach { userSymbols.add(it.name.value) }

        // Parameters are visible for print-format heuristics inside the body.
        functionDecl.def.parameters.forEach { param ->
            val paramType = typeNameOf(param.typeSpecifier)
            knownValueTypes[param.name.value] = paramType
            recordContainerTypeArgs(param.name.value, paramType, param.typeSpecifier)
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
        pushArcScope()
        val savedReturnType = currentReturnType
        currentReturnType = returnTypeName
        functionDecl.def.body!!.forEach { it.accept(this) }
        currentReturnType = savedReturnType
        // Fall-through path: an explicit `return` already emitted its own
        // releases, so this only covers reaching the closing brace.
        val bodyTerminated = endsWithReturn(functionDecl.def.body)
        if (functionName == "main" && returnsVoid) {
            popArcScope(terminated = bodyTerminated)
            appendIndentedLine("return 0;")
        } else {
            popArcScope(terminated = bodyTerminated)
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
        userSymbols.add(className)

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
            val mangled = registerMethod(
                className,
                methodName,
                returnTypeName,
                method.def.parameters.map { typeNameOf(it.typeSpecifier) }
            )
            userSymbols.add(methodName)
            method.def.parameters.forEach { userSymbols.add(it.name.value) }

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
            pushArcScope()
            currentMethodClass = className
            val savedReturnType = currentReturnType
            currentReturnType = returnTypeName
            method.def.body?.forEach { it.accept(this) }
            currentReturnType = savedReturnType
            currentMethodClass = null
            popArcScope(terminated = endsWithReturn(method.def.body))
            // Drop param locals so they don't leak
            method.def.parameters.forEach { param ->
                knownValueTypes.remove(param.name.value)
            }
            indentLevel--
            appendIndentedLine("}")
            buffer.appendLine()
        }

        // ARC factory: Class_new(field args...) -> heap-allocated Class* with RC=1
        if (!fields.isEmpty()) {
            userSymbols.add("${className}_new")
            userSymbols.add("${className}_finalize")
            emitClassFinalizer(
                className,
                fields.filter { userClassNames.contains(typeNameOf(it.type)) }.map { it.name.value }
            )
            appendIndented("simple ")
            buffer.append(mapTypeName(className))
            buffer.append(" ")
            buffer.append(className)
            buffer.append("_new(")
            fields.forEachIndexed { i, field ->
                if (i > 0) buffer.append(", ")
                buffer.append(mapTypeName(typeNameOf(field.type)))
                buffer.append(" ")
                buffer.append(field.name.value)
            }
            buffer.appendLine(")")
            appendIndentedLine("{")
            indentLevel++
            val owned = fields.filter { userClassNames.contains(typeNameOf(it.type)) }
                .map { it.name.value }
            appendIndented("")
            buffer.append(mapTypeName(className))
            buffer.append(" self = (")
            buffer.append(className)
            buffer.append("*)kira_rc_alloc_with(sizeof(")
            buffer.append(className)
            buffer.append("), ")
            buffer.append(if (owned.isEmpty()) "null" else "${className}_finalize")
            buffer.appendLine(");")
            fields.forEach { field ->
                appendIndented("self->")
                buffer.append(field.name.value)
                buffer.append(" = ")
                buffer.append(field.name.value)
                buffer.appendLine(";")
            }
            appendIndentedLine("return self;")
            indentLevel--
            appendIndentedLine("}")
            buffer.appendLine()
        }
    }

    /**
     * Emit `Class_finalize` when the class owns class-typed fields, and return
     * the finalizer argument for kira_rc_alloc_with (`null` when it owns none).
     *
     * Constructor arguments arrive borrowed, so the factory retains each owned
     * field; the finalizer releases them when the owner's count hits zero.
     */
    private fun emitClassFinalizer(cName: String, ownedFields: List<String>): String {
        if (ownedFields.isEmpty()) return "null"
        appendIndented("static Void ")
        buffer.append(cName)
        buffer.appendLine("_finalize(Void* p)")
        appendIndentedLine("{")
        indentLevel++
        appendIndented("")
        buffer.append(cName)
        buffer.append("* self = (")
        buffer.append(cName)
        buffer.appendLine("*)p;")
        ownedFields.forEach { field ->
            appendIndented("kira_rc_release(self->")
            buffer.append(field)
            buffer.appendLine(");")
        }
        indentLevel--
        appendIndentedLine("}")
        buffer.appendLine()
        return "${cName}_finalize"
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
        userSymbols.add(typeName)
        enumTypeNames.add(typeName)

        appendIndented("typedef enum ")
        buffer.append(typeName)
        buffer.appendLine()
        appendIndentedLine("{")
        indentLevel++
        enumDecl.members.forEachIndexed { index, member ->
            val memberName = member.name.value
            userSymbols.add("${prefix}_$memberName")
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
        // Traits are lowered structurally in emitTraitStructs / emitTraitTables.
        // Nothing to emit at the statement site.
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
            is Identifier -> {
                userSymbols.add(id.value)
                buffer.append(id.value)
            }
            else -> typeAliasDecl.alias.accept(this)
        }
        buffer.appendLine(";")
    }
}
