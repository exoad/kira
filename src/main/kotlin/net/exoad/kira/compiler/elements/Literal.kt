package net.exoad.kira.compiler.elements

import net.exoad.kira.compiler.exprs.Expr

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
