package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

abstract class Literal : Expr()

/**
 * **marker interface**
 *
 * Signifies that this literal are of either of these types:
 *
 * 1. float
 * 2. int
 * 3. strings
 * 4. bools
 * 5. none/null
 */
interface SimpleLiteral
