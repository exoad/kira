package net.exoad.kira.compiler.frontend.lexer

import net.exoad.kira.core.Symbols
import net.exoad.kira.source.SourcePosition

/**
 * Semantical tokens representing each part of text that was parsed
 */
sealed class Token(
    val type: Type,
    val content: String,
    val pointerPosition: Int,
    val canonicalLocation: SourcePosition
) {
    enum class Type(val rawDiagnosticsRepresentation: String) {
        X_ANY("X_ANY"), // reserved for [BuiltinTypes]
        L_INTEGER("Integer Literal"),
        L_STRING("String Literal"),
        L_FLOAT("Float Literal"),
        L_TRUE_BOOL("'true' (Boolean)"),
        L_FALSE_BOOL("'false' (Boolean)"),
        L_NULL("'null'"),
        IDENTIFIER("Identifier"),
        INTRINSIC_IDENTIFIER("Intrinsic Identifier"),
        OP_RANGE("'..' (Range To)"),
        OP_ADD("'+' (Plus)"),
        OP_SUB("'-' (Minus)"),
        OP_MUL("'*' (Multiply)"),
        OP_SCOPE("'::' (Static Member Access)"),
        OP_DIV("'/' (Divide)"),
        OP_MOD("'%' (Modulo)"),
        S_EQUAL("'=' (Assignment)"),
        OP_ASSIGN_ADD("'+=' (Compound Addition Assignment)"),
        OP_ASSIGN_SUB("'-=' (Compound Subtraction Assignment)"),
        OP_ASSIGN_MUL("'*=' (Compound Multiplication Assignment)"),
        OP_ASSIGN_DIV("'/=' (Compound Division Assignment)"),
        OP_ASSIGN_MOD("'%=' (Compound Modulus Assignment)"),
        OP_ASSIGN_BIT_OR("'|=' (Compound Bitwise OR Assignment)"),
        OP_ASSIGN_BIT_AND("'&=' (Compound Bitwise AND Assignment)"),
        OP_ASSIGN_BIT_SHL("'<<=' (Compound Bitwise Left Shift Assignment)"),

        // commenting out all the tokens that involve ">" because it is very problematic when parsing them together with generics using the same symbols
//        OP_ASSIGN_BIT_SHR("'>>=' (Compound Bitwise Right Shift Assignment)"),
//        OP_ASSIGN_BIT_USHR("'>>>=' (Compound Bitwise Unsigned Right Shift Assignment)"),
        OP_ASSIGN_BIT_XOR("'^=' (Compound Bitwise XOR Assignment)"),
        OP_CMP_LEQ("'<=' (Less Than Or Equal To)"),

        //        OP_CMP_GEQ("'>=' (Greater Than Or Equal To)"),
        OP_CMP_EQL("'==' (Equals To)"),
        OP_CMP_NEQ("'!=' (Not Equals To)"),
        OP_CMP_AND("'&&' (Logical AND)"),
        OP_CMP_OR("'||' (Logical OR)"),
        OP_BIT_SHL("'<<' (Bitwise Shift Left)"),

        //        OP_BIT_SHR("'>>' (Bitwise Shift Right)"),
//        OP_BIT_USHR("'>>>' (Bitwise Unsigned Shift Right)"),
        OP_BIT_XOR("'^' (Bitwise XOR"),
        K_IF("'if'"),
        K_ELSE("'else'"),
        K_IS("'is'"),
        K_CONTINUE("'continue'"),
        K_BREAK("'break'"),
        K_AS("'as'"),
        K_WHILE("'while'"),
        K_RETURN("'return'"),
        K_DO("'do'"),
        K_FOR("'for'"),
        K_CLASS("'class'"),
        K_USE("'use'"),
        K_WITH("'with'"),
        K_ENUM("'enum'"),
        K_NAMESPACE("'object'"),
        K_MODULE("'module'"),

        //        K_GLOBAL("'global'"),
        K_TRAIT("'trait'"),
        K_THIS("'this'"),
        K_MODIFIER_REQUIRE("'require'"),
        K_MODIFIER_MUTABLE("'mut' (Mutable)"),
        K_MODIFIER_PUBLIC("'pub' (Public Visibility)"),
        K_FX("'fx' (Function)"),

        // RAW SYMBOLS
        S_OPEN_PARENTHESIS("'(' (Opening Parenthesis)"),
        S_CLOSE_PARENTHESIS("')' (Closing Parenthesis)"),
        S_OPEN_BRACKET("'[' (Opening Bracket)"),
        S_CLOSE_BRACKET("']' (Closing Bracket)"),
        S_OPEN_BRACE("'{' (Opening Brace)"),
        S_CLOSE_BRACE("'}' (Closing Brace)"),
        S_OPEN_ANGLE("'<' (Opening Angle Bracket)"),
        S_CLOSE_ANGLE("'>' (Closing Angle Bracket)"),
        S_COLON("':' (Colon)"),
        S_SEMICOLON("';' (Semicolon)"),
        S_AND("'&' (And)"),
        S_UNDERSCORE("'_' (Underscore)"),
        S_PIPE("'|' (Pipe)"),
        S_EOF("'EOF' (End Of File)"),
        S_AT("'@' (At)"),
        S_BANG("'!' (Bang)"),
        S_DOT("'.' (Dot)"),
        S_TILDE("'~' (Tilde)"),
        S_COMMA("',' (Comma)"),
        S_QUESTION_MARK("'?', (Question Mark)")
        ;

        fun diagnosticsName(): String {
            return rawDiagnosticsRepresentation
        }

        companion object {
            fun isBinaryOperator(vararg token: Type): Boolean {
                return when {
                    token.size == 1 -> when (token[0]) {
                        OP_RANGE, S_DOT,
                        OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD,
                        OP_CMP_NEQ, OP_CMP_LEQ, OP_CMP_EQL,
                        S_OPEN_ANGLE, S_CLOSE_ANGLE,
                        OP_CMP_AND, OP_CMP_OR,
                        OP_BIT_XOR, OP_BIT_SHL,
                        S_AND, S_PIPE,
                            -> true

                        else -> false
                    }

                    token.size == 2 &&
                            token[0] == S_CLOSE_ANGLE &&
                            (token[1] == S_EQUAL || token[1] == S_CLOSE_ANGLE) -> true

                    token.size == 3 &&
                            token[0] == S_CLOSE_ANGLE &&
                            token[1] == S_CLOSE_ANGLE &&
                            token[2] == S_CLOSE_ANGLE -> true

                    token.size == 4 &&
                            token[0] == S_CLOSE_ANGLE &&
                            token[1] == S_CLOSE_ANGLE &&
                            token[2] == S_CLOSE_ANGLE &&
                            token[3] == S_EQUAL -> true

                    else -> false
                }
            }

            val modifiers = entries.filter { it.name.startsWith("K_MODIFIER_") }.toTypedArray()
            val literals = entries.filter { it.name.startsWith("L_") }.toTypedArray()
        }
    }

    class Raw(type: Type, rawString: String, pointerPosition: Int, canonicalLocation: SourcePosition) :
        Token(type, rawString, pointerPosition, canonicalLocation)

    class Symbol(type: Type, symbol: Symbols, pointerPosition: Int, canonicalLocation: SourcePosition) :
        Token(type, symbol.rep.toString(), pointerPosition, canonicalLocation)

    class LinkedSymbols(type: Type, symbols: Array<Symbols>, pointerPosition: Int, canonicalLocation: SourcePosition) :
        Token(type, symbols.map { it.rep }.joinToString(""), pointerPosition, canonicalLocation)

    override fun toString(): String {
        return String.format(
            "%-${26}s %-${32}s %${5}d",
            type.name,
            "'$content'",
            pointerPosition
        )
    }
}