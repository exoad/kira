package net.exoad.kira.ui

import com.formdev.flatlaf.FlatDarkLaf
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.source.SourceContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.lang.reflect.Modifier

class KiraVisualViewer(
    private val compilationUnit: CompilationUnit,
    private val semanticAnalyzerResults: SemanticAnalyzerResults? = null
) : JFrame("Kira Compiler Debugger") {

    private val sourceCombo = JComboBox<String>()
    private val sourceCodePane = JTextPane()
    private val astTree = JTree()
    private val tokensTable = JTable()
    private val symbolsTable = JTable()
    private val diagnosticsArea = JTextArea()

    init {
        try {
            UIManager.setLookAndFeel(FlatDarkLaf())
            SwingUtilities.updateComponentTreeUI(this)
        } catch (ex: Exception) {
            System.err.println("Failed to initialize FlatDarkLaf")
        }

        size = Dimension(1200, 800)
        preferredSize = size
        defaultCloseOperation = EXIT_ON_CLOSE

        sourceCodePane.isEditable = false
        sourceCodePane.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        diagnosticsArea.isEditable = false
        diagnosticsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)

        val mainSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = JScrollPane(sourceCodePane)
            rightComponent = JTabbedPane().apply {
                addTab("AST Viewer", JScrollPane(astTree))
                addTab("Tokens", JScrollPane(tokensTable))
                addTab("Symbol Table", JScrollPane(symbolsTable))
            }
            resizeWeight = 0.4
        }

        val bottomSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = mainSplit
            bottomComponent = JTabbedPane().apply {
                addTab("Diagnostics & Intrinsics", JScrollPane(diagnosticsArea))
            }
            resizeWeight = 0.8
        }

        val topPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            add(JLabel(" Target Source: "), BorderLayout.WEST)
            add(sourceCombo, BorderLayout.CENTER)
        }

        contentPane = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(bottomSplit, BorderLayout.CENTER)
        }

        setupInteractions()
        loadData()
    }

    private fun loadData() {
        val sources = compilationUnit.allSources().toList()
        sources.forEach { sourceCombo.addItem(it.file) }
        sourceCombo.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                val file = it.item as String
                val ctx = sources.find { s -> s.file == file }
                if (ctx != null) loadSource(ctx)
            }
        }

        loadSymbols()
        loadDiagnostics()

        if (sources.isNotEmpty()) {
            loadSource(sources.first())
        }
    }

    private fun loadSource(ctx: SourceContext) {
        sourceCodePane.text = ctx.content
        sourceCodePane.caretPosition = 0

        if (ctx.hasAst()) {
            val rootNode = buildASTNode(ctx.ast, ctx, "Root")
            astTree.model = DefaultTreeModel(rootNode)
            expandLevel1(astTree)
        } else {
            astTree.model = DefaultTreeModel(DefaultMutableTreeNode("No AST found"))
        }

        populateTokensTable(ctx)
    }

    private fun populateTokensTable(ctx: SourceContext) {
        val model = DefaultTableModel(arrayOf("Index", "Type", "Text", "Line", "Col"), 0)
        ctx.tokens.forEachIndexed { idx, tk ->
            model.addRow(
                arrayOf<Any>(
                    idx,
                    tk.type.name,
                    tk.content.replace("\n", "\\n").replace("\r", "\\r"),
                    tk.canonicalLocation.lineNumber,
                    tk.canonicalLocation.column
                )
            )
        }
        tokensTable.model = model
    }

    private fun loadSymbols() {
        val model = DefaultTableModel(arrayOf("Scope Name", "Scope Kind", "Name", "Properties"), 0)

        compilationUnit.symbolTable.forEach { frame ->
            val scopeKindStr = frame.kind::class.simpleName ?: "Unknown"
            val scopeName = when (val k = frame.kind) {
                is SemanticScope.Module -> k.name
                is SemanticScope.Class -> k.name
                is SemanticScope.Function -> k.name
                is SemanticScope.Enum -> k.name
                is SemanticScope.Trait -> k.name
                is SemanticScope.Variant -> k.name
                is SemanticScope.VariantMember -> k.name
                is SemanticScope.Global -> "(global)"
                else -> "(unknown)"
            }

            frame.symbols.forEach { (symName, sym) ->
                model.addRow(arrayOf<Any>(scopeName, scopeKindStr, symName, sym.toString()))
            }
        }
        symbolsTable.model = model
    }

    private fun loadDiagnostics() {
        val sb = StringBuilder()
        if (semanticAnalyzerResults != null) {
            sb.appendLine("Compile Target Health: ${if (semanticAnalyzerResults.isHealthy) "PASSED" else "FAILED"}")
            if (semanticAnalyzerResults.diagnostics.isNotEmpty()) {
                sb.appendLine("\n--- DIAGNOSTICS ---")
                semanticAnalyzerResults.diagnostics.forEach { d ->
                    sb.appendLine(Diagnostics.recordDiagnostics(d))
                }
            }
        }

        val magicTypes = compilationUnit.allMagicTypes()
        if (magicTypes.isNotEmpty()) {
            sb.appendLine("\n--- REGISTERED MAGIC TYPES ---")
            magicTypes.forEach { sb.appendLine("- $it") }
        }

        diagnosticsArea.text = sb.toString()
        diagnosticsArea.caretPosition = 0
    }

    private fun setupInteractions() {
        astTree.addTreeSelectionListener { e ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode
            val userObj = node?.userObject
            if (userObj is ASTNodeWrapper && userObj.node is ASTNode) {
                val origin = userObj.sourceContext.astOrigins[userObj.node]
                if (origin != null) {
                    highlightToken(origin.lineNumber, origin.column, userObj.sourceContext)
                }
            }
        }

        tokensTable.selectionModel.addListSelectionListener {
            val r = tokensTable.selectedRow
            if (r >= 0) {
                val file = sourceCombo.selectedItem as String
                val ctx = compilationUnit.getSource(file)
                if (ctx != null) {
                    val line = tokensTable.getValueAt(r, 3) as Int
                    val col = tokensTable.getValueAt(r, 4) as Int
                    highlightToken(line, col, ctx)
                }
            }
        }
    }

    private fun highlightToken(line: Int, col: Int, ctx: SourceContext) {
        try {
            val lines = ctx.content.split("\n")
            if (line > lines.size || line < 1) return
            var offset = 0
            for (i in 0 until (line - 1)) {
                offset += lines[i].length + 1
            }
            val start = offset + (col - 1)

            val token = ctx.tokens.find { it.canonicalLocation.lineNumber == line && it.canonicalLocation.column == col }
            val len = token?.content?.length ?: 1

            sourceCodePane.select(start, start + len)
            sourceCodePane.requestFocusInWindow()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun buildASTNode(node: Any?, ctx: SourceContext, name: String? = null): DefaultMutableTreeNode {
        val displayStr = if (name != null) "$name: " else ""
        if (node == null) return DefaultMutableTreeNode(displayStr + "null")

        if (node is Collection<*>) {
            val root = DefaultMutableTreeNode(displayStr + "List (${node.size})")
            node.forEach { root.add(buildASTNode(it, ctx, null)) }
            return root
        }
        if (node is Array<*>) {
            val root = DefaultMutableTreeNode(displayStr + "Array (${node.size})")
            node.forEach { root.add(buildASTNode(it, ctx, null)) }
            return root
        }

        val clazz = node.javaClass
        if (clazz.packageName.startsWith("net.exoad.kira")) {
            val typeStr = clazz.simpleName ?: "Unknown"
            val root = DefaultMutableTreeNode(ASTNodeWrapper(node, displayStr + typeStr, ctx))

            try {
                val fields = generateSequence(clazz) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter { !Modifier.isStatic(it.modifiers) }
                    .toList()

                fields.forEach { field ->
                    try {
                        field.isAccessible = true
                        val value = field.get(node)
                        root.add(buildASTNode(value, ctx, field.name))
                    } catch (e: Exception) {
                        root.add(DefaultMutableTreeNode("${field.name}: <error>"))
                    }
                }
            } catch (e: Exception) {
                // Ignore reflective access issues safely
            }
            return root
        }
        return DefaultMutableTreeNode(displayStr + node.toString())
    }

    private fun expandLevel1(tree: JTree) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val path = javax.swing.tree.TreePath(root)
        tree.expandPath(path)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            tree.expandPath(path.pathByAddingChild(child))
        }
    }

    data class ASTNodeWrapper(val node: Any, val displayStr: String, val sourceContext: SourceContext) {
        override fun toString(): String {
            val intrinsics = sourceContext.astIntrinsicMarked[node]?.joinToString { it.name }
            return if (!intrinsics.isNullOrEmpty()) "$displayStr [@$intrinsics]" else displayStr
        }
    }

    fun run() {
        SwingUtilities.invokeLater {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}