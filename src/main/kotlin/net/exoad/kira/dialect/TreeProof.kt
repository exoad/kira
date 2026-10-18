package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.SourceContext

/**
 * The converter's proof: the old text's tree (legacy parser) equals the new text's (the clean
 * dialect's parser), normalised only where the dialect itself changed: the module header and
 * imports by their names, `require`, a root parent, the trait/class split, a zero the converter
 * wrote for a field the old dialect value-initialized.
 */
object TreeProof {
    /** A new-dialect parse of [text] as module [uri]: its context, or the error. */
    fun parseNew(text: String, file: String, uri: String): Pair<SourceContext?, String?> {
        return try {
            val cu = CompilationUnit()
            val processed = KiraPreprocessor(text).process().processedContent
            val bare = cu.addSource(file, processed, emptyList(), uri)
            val ctx = cu.addSource(file, processed, KiraLexer(bare).tokenize())
            KiraParser(ctx).parse()
            ctx to null
        } catch (e: DiagnosticsException) {
            null to ((e.message.lines().firstOrNull { it.isNotBlank() } ?: e.toString()) + " at ${e.location}")
        } catch (e: Exception) {
            null to (e.message ?: e.toString())
        }
    }

    /** The first difference between [old] and [new], or null when they are the same tree. */
    fun compare(
        old: SourceContext,
        new: SourceContext,
        renames: DialectConverter.Renames,
        madeRequired: Collection<VariableDecl> = emptyList(),
    ): String? {
        return Run(old, new, renames, java.util.Collections.newSetFromMap(java.util.IdentityHashMap<VariableDecl, Boolean>()).also { it.addAll(madeRequired) }).root(old.ast, new.ast)
    }

    private class Run(val oc: SourceContext, val nc: SourceContext, val renames: DialectConverter.Renames, val madeRequired: Set<VariableDecl>) {
        /** The old tree's fields: a field the old dialect value-initialized must keep a value. */
        val fields: MutableSet<ASTNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        fun root(o: RootASTNode, n: RootASTNode): String? = list("root", o.statements, n.statements)

        fun list(path: String, o: List<*>, n: List<*>): String? {
            if (o.size != n.size) return "$path: ${o.size} entries became ${n.size}"
            for (i in o.indices) {
                value("$path[$i]", o[i], n[i])?.let { return it }
            }
            return null
        }

        fun value(path: String, o: Any?, n: Any?): String? {
            if (o is ASTNode && n is ASTNode) return node(path, o, n)
            if (o is List<*> && n is List<*>) return list(path, o, n)
            if (o is Array<*> && n is Array<*>) return list(path, o.toList(), n.toList())
            if (o is Map<*, *> && n is Map<*, *>) {
                if (o.keys != n.keys) return "$path: keys ${o.keys} became ${n.keys}"
                for (k in o.keys) value("$path[$k]", o[k], n[k])?.let { return it }
                return null
            }
            if (o != null && n != null && o.javaClass == n.javaClass && AstWalk.isAstPart(o)) {
                for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
                return null
            }
            // Positions move with the text: they are the one thing a conversion may change.
            if (o is net.exoad.kira.source.SourceLocation && n is net.exoad.kira.source.SourceLocation) return null
            if (o == n) return null
            return "$path: '$o' became '$n'"
        }

        fun node(path: String, o: ASTNode, n: ASTNode): String? {
            special(path, o, n)?.let { return it.ifEmpty { null } }
            if (o.javaClass != n.javaClass) return "$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}"
            marks(path, o, n)?.let { return it }
            for (f in AstWalk.fieldsOf(o.javaClass)) {
                value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
            }
            return null
        }

        /** The intrinsics on both nodes, by name and arguments. */
        fun marks(path: String, o: ASTNode, n: ASTNode): String? {
            val om = oc.intrinsicInvocationsOf(o)
            val nm = nc.intrinsicInvocationsOf(n)
            if (om.size != nm.size) return "$path: marks ${om.map { it.intrinsicKey.name }} became ${nm.map { it.intrinsicKey.name }}"
            for (i in om.indices) {
                if (om[i].intrinsicKey.name != nm[i].intrinsicKey.name) return "$path: mark ${om[i].intrinsicKey.name} became ${nm[i].intrinsicKey.name}"
                value("$path.@${om[i].intrinsicKey.name}", om[i].parameters, nm[i].parameters)?.let { return it }
                value("$path.@${om[i].intrinsicKey.name}", om[i].namedParameters, nm[i].namedParameters)?.let { return it }
            }
            val op = o in oc.astParenthesized
            val np = n in nc.astParenthesized
            if (op != np) return "$path: parenthesized $op became $np"
            return null
        }

        /** The dialect's own changes: "" when the pair is the same, a reason when it is not, null for the generic walk. */
        fun special(path: String, o: ASTNode, n: ASTNode): String? = when {
            o is ModuleDecl && n is ModuleDecl ->
                if (renames.of(o.uri.value) == ModuleNames.dotted(n.uri.value)) "" else "$path: module ${o.uri.value} became ${n.uri.value}"
            o is UseStatement && n is UseStatement ->
                if (renames.of(o.uri.value) == n.uri.value) "" else "$path: use ${o.uri.value} became ${n.uri.value}"
            (o is ClassDecl || o is TraitDecl) && (n is ClassDecl || n is TraitDecl) -> typeDecl(path, o, n) ?: ""
            o is VariableDecl && n is VariableDecl -> variable(path, o, n) ?: ""
            o is StructDecl && n is StructDecl -> {
                fields.addAll(o.members.filterIsInstance<VariableDecl>())
                null
            }
            else -> null
        }

        fun typeDecl(path: String, o: ASTNode, n: ASTNode): String? {
            marks(path, o, n)?.let { return it }
            when (o) {
                is ClassDecl -> fields.addAll(o.members.filterIsInstance<VariableDecl>())
                is TraitDecl -> {}
            }
            fun parts(d: ASTNode): List<Any?> = when (d) {
                is ClassDecl -> listOf(d.name, d.modifiers, d.members, d.parents, d.initially, d.finally)
                is TraitDecl -> listOf(d.name, d.modifiers.toList(), d.members, d.parents, null, null)
                else -> error("not a type")
            }
            val op = parts(o)
            val np = parts(n)
            val names = listOf("name", "modifiers", "members", "parents", "initially", "finally")
            for (i in op.indices) value("$path.${names[i]}", op[i], np[i])?.let { return it }
            return null
        }

        fun variable(path: String, o: VariableDecl, n: VariableDecl): String? {
            marks(path, o, n)?.let { return it }
            value("$path.name", o.name, n.name)?.let { return it }
            value("$path.type", o.type, n.type)?.let { return it }
            value("$path.modifiers", o.modifiers - Modifier.REQUIRE, n.modifiers)?.let { return it }
            val ov = o.value
            val nv = n.value
            if (ov == null && nv != null && Modifier.REQUIRE !in o.modifiers) {
                return if (isZero(nv, o.type)) null else "$path.value: none became a value that is not the type's zero"
            }
            if (ov == null && nv == null && Modifier.REQUIRE !in o.modifiers && o in fields && o !in madeRequired) {
                return "$path: a field the old dialect value-initialized is now one the construction must give"
            }
            return value("$path.value", ov, nv)
        }

        fun isZero(v: ASTNode, t: Type): Boolean = when (v) {
            is IntegerLiteral -> v.value == 0L
            is FloatLiteral -> v.value == 0.0
            is StringLiteral -> v.value.isEmpty()
            is CharLiteral -> v.value == 0
            is Identifier -> v.value == "false" || v.value == "null"
            is NullLiteral -> true
            is ObjectInitExpr -> v.positionalArgs.isEmpty() && v.namedArgs.isEmpty() && value("t", t, v.typeName) == null
            else -> false
        }
    }
}
