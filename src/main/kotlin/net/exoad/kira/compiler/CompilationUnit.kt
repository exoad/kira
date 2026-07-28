package net.exoad.kira.compiler

import net.exoad.kira.compiler.analysis.semantic.KiraSymbolTable
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbol
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariantDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.parser.LegacyKiraSourceParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext
import java.io.File

class CompilationUnit {
    private val sources = mutableMapOf<String, SourceContext>()
    private val magicTypeNames = mutableSetOf<String>()
    /** Foreign opaque class names → C pointer handles (no Kira ARC). */
    private val opaqueTypeNames = mutableSetOf<String>()
    /** Kira function name → C symbol for @_extern stubs. */
    private val externFunctions = linkedMapOf<String, String>()
    val symbolTable = KiraSymbolTable()

    init {
        try {
            val kiraRoot = File("kira")
            if (kiraRoot.exists() && kiraRoot.isDirectory) {
                kiraRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kira" }
                    .forEach { sourceFile ->
                        val pre = KiraPreprocessor(sourceFile.readText())
                        val processed = pre.process()
                        val ctx = addSource(sourceFile.canonicalPath, processed.processedContent, emptyList())
                        val lexer = KiraLexer(ctx)
                        val tokens = lexer.tokenize()
                        addSource(sourceFile.canonicalPath, ctx.content, tokens)
                        LegacyKiraSourceParser(getSource(sourceFile.canonicalPath)!!).parse()
                    }
            }
        } catch (_: Exception) {
            // silently ignore any bootstrap errors here
        }
    }

    fun addSource(file: String, content: String, tokens: List<Token>): SourceContext {
        val ctx = SourceContext(content, file, tokens)
        sources[file] = ctx
        return ctx
    }

    fun getSource(file: String): SourceContext? {
        return sources[file]
    }

    fun getSourcesLength(): Int {
        return sources.size
    }

    fun allSources(): Collection<SourceContext> {
        return sources.values
    }

    fun resolveSymbol(name: String): SemanticSymbol? {
        return symbolTable.resolve(name)
    }

    fun registerMagicType(name: String) {
        if (name.isNotBlank()) {
            magicTypeNames.add(name)
        }
    }

    fun isMagicType(name: String): Boolean {
        return magicTypeNames.contains(name)
    }

    fun allMagicTypes(): Set<String> {
        return magicTypeNames.toSet()
    }

    fun registerOpaqueType(name: String) {
        if (name.isNotBlank()) {
            opaqueTypeNames.add(name)
        }
    }

    fun isOpaqueType(name: String): Boolean {
        return opaqueTypeNames.contains(name)
    }

    fun allOpaqueTypes(): Set<String> {
        return opaqueTypeNames.toSet()
    }

    fun registerExternFunction(kiraName: String, cName: String) {
        if (kiraName.isNotBlank() && cName.isNotBlank()) {
            externFunctions[kiraName] = cName
        }
    }

    fun externCNameOrNull(kiraName: String): String? {
        return externFunctions[kiraName]
    }

    fun allExternFunctions(): Map<String, String> {
        return externFunctions.toMap()
    }

    fun collectIntrinsicMarkedTypeNames(intrinsicName: String): Set<String> {
        val collected = mutableSetOf<String>()
        allSources().forEach { source ->
            val marks = runCatching { source.astIntrinsicMarked }.getOrNull() ?: return@forEach
            marks.forEach { (node, intrinsics) ->
                if (intrinsics.none { it.name == intrinsicName }) {
                    return@forEach
                }
                node.declaredTypeNameOrNull()?.let { collected.add(it) }
            }
        }
        return collected
    }

    private fun ASTNode.declaredTypeNameOrNull(): String? {
        return when (this) {
            is ClassDecl -> (name.identifier as? Identifier)?.value
            is TraitDecl -> (name.identifier as? Identifier)?.value
            is VariantDecl -> (name.identifier as? Identifier)?.value
            is TypeAliasDecl -> (alias.identifier as? Identifier)?.value
            is EnumDecl -> name.value
            else -> null
        }
    }
}
