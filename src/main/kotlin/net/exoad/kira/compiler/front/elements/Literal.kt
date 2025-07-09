package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.exprs.Expr

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
 */
interface SimpleLiteral
