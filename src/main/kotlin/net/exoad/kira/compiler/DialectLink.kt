package net.exoad.kira.compiler

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.source.SourceContext

/**
 * What needs every file read: each import's module, and each type's role. Run once a unit is
 * parsed, before anything reads it; it runs again only when the unit has changed.
 */
object DialectLink {
    private val linked = java.util.WeakHashMap<CompilationUnit, Int>()

    fun link(unit: CompilationUnit) {
        val sources = unit.allSources().filter { runCatching { it.ast }.isSuccess }
        val stamp = sources.sumOf { System.identityHashCode(it.ast) }
        if (linked[unit] == stamp) return
        imports(sources)
        roles(sources)
        linked[unit] = sources.sumOf { System.identityHashCode(it.ast) }
    }

    private fun moduleUri(source: SourceContext): String? =
        source.ast.statements.firstNotNullOfOrNull { (it as? Statement)?.expr as? ModuleDecl }?.uri?.value

    /** `a.b.c as name` names the module whose dotted name is `a.b.c`, or `a.b`'s member `c`. */
    private fun imports(sources: List<SourceContext>) {
        val byDotted = HashMap<String, String>()
        sources.forEach { s -> moduleUri(s)?.let { byDotted.putIfAbsent(ModuleNames.dotted(it), it) } }
        for (s in sources) {
            for (node in s.ast.statements) {
                val use = node as? UseStatement ?: continue
                val written = use.uri.value
                if (use.alias == null) continue
                val whole = byDotted[written]
                val owner = byDotted[written.substringBeforeLast('.', "")]
                when {
                    whole != null -> use.resolvedUri = whole
                    owner != null -> {
                        use.resolvedUri = owner
                        use.member = written.substringAfterLast('.')
                    }
                }
            }
        }
    }

    /**
     * Trait and class are one kind of type. A type is a class when it holds state (a field,
     * `initially`, an intrinsic), is constructed, or has a class parent; any other type is a
     * trait when a type lists it as a parent or it has a body-less function.
     */
    private fun roles(sources: List<SourceContext>) {
        val classes = mutableListOf<Pair<SourceContext, ClassDecl>>()
        val parentNames = HashSet<String>()
        for (s in sources) {
            for (node in s.ast.statements) {
                when (val e = (node as? Statement)?.expr) {
                    is ClassDecl -> {
                        classes += s to e
                        e.parents.forEach { p -> (p.identifier as? Identifier)?.let { parentNames += it.value } }
                    }
                    is StructDecl -> e.traits.forEach { p -> (p.identifier as? Identifier)?.let { parentNames += it.value } }
                    is TraitDecl -> e.parents.forEach { p -> (p.identifier as? Identifier)?.let { parentNames += it.value } }
                    else -> {}
                }
            }
        }
        val constructed = HashSet<String>()
        sources.forEach { src ->
            net.exoad.kira.compiler.analysis.types.AstTree.walk(src.ast) { n ->
                if (n is net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr) {
                    (n.typeName.identifier as? Identifier)?.let { constructed += it.value }
                }
            }
        }
        val classLike = HashSet<String>()
        fun nameOf(c: ClassDecl) = (c.name.identifier as? Identifier)?.value ?: ""
        classes.forEach { (s, c) ->
            val marked = s.astIntrinsicMarked[c]?.isNotEmpty() == true
            if (marked || c.initially != null || c.finally != null || c.members.any { it is VariableDecl } || nameOf(c) in constructed) {
                classLike += nameOf(c)
            }
        }
        var grew = true
        while (grew) {
            grew = false
            classes.forEach { (_, c) ->
                if (nameOf(c) !in classLike && c.parents.any { (it.identifier as? Identifier)?.value in classLike }) {
                    classLike += nameOf(c)
                    grew = true
                }
            }
        }
        for ((s, c) in classes) {
            val name = nameOf(c)
            val slotted = c.members.any { it is FunctionDecl && it.def.body == null }
            if (name in classLike || (name !in parentNames && !slotted)) continue
            replace(s, c, TraitDecl(c.name, c.modifiers.toTypedArray(), c.members.map { it as FunctionDecl }, c.parents))
        }
    }

    /** [old]'s statement in [s] now holds [new], with [old]'s origin and intrinsics. */
    private fun replace(s: SourceContext, old: ASTNode, new: ASTNode) {
        val statements = s.ast.statements
        val i = statements.indexOfFirst { (it as? Statement)?.expr === old }
        if (i < 0) return
        s.astOrigins[old]?.let { s.astOrigins[new] = it }
        s.astIntrinsicMarked[old]?.let { s.astIntrinsicMarked[new] = it }
        s.intrinsicInvocationsOf(old).takeIf { it.isNotEmpty() }?.let { s.astIntrinsicInvocations[new] = it }
        val statement = Statement(new as net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr)
        s.astOrigins[statements[i]]?.let { s.astOrigins[statement] = it }
        val list = statements.toMutableList()
        list[i] = statement
        s.ast = RootASTNode(list)
    }
}
