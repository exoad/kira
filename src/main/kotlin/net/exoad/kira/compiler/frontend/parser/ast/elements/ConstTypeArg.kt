package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral

/**
 * An integer literal in a type-argument list, `Arr<UInt8, 32>` (design D6).
 * It sits in [Type.children] like any other argument, named `#const` so no
 * user type can collide with it. A constant *name* in that position
 * (`Arr<UInt8, USER_CMD_BYTES>`) stays an ordinary [Type]; the typer resolves it.
 */
class ConstTypeArg(val value: IntegerLiteral) : Type(Identifier(NAME)) {
    override fun toString(): String {
        return "ConstTypeArg(${value.value})"
    }

    companion object {
        const val NAME = "#const"
    }
}
