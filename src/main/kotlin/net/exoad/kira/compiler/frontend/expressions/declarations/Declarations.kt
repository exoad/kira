package net.exoad.kira.compiler.frontend.expressions.declarations

import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.compiler.frontend.elements.IdentifierNode

abstract class DeclarationsNode(open val name: IdentifierNode) : ExpressionNode()