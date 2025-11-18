package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.lexer.Token

// this file holds some of the global stuffs of the language like keywords and valid ascii symbols (maybe even utf8 later on)
//
// todo: [low-priority] should i modularize this and move some of the stuffs away from this file like the symbols and keywords and stuffs. in most of my other projects, a shared source file usually means just internal configurations and constants, but who knows, ig this makes sense!
/**
 * Global symbols used by the language and are defined here. these are all one byte characters for now and should all be ascii based (maybe utf8 for easter egg or special intrinsics or special feature??)
 * lmao having to copy and paste constantly from an utf8 character code website would be hilarious
 *
 *
 * - primarily used by [net.exoad.kira.compiler.frontend.lexer.KiraLexer] to match them to [Token]s
 */
enum class Symbols(val rep: Char) {
    NULL('\u0000'),
    NEWLINE('\n'),
    DOUBLE_QUOTE('\u0022'),
    BACK_SLASH('\u005c'), // unused ignore; this might not be necessary, but we might need it in the parser stage since the parse will now evaluate escaped strings? (check chores)
    UNDERSCORE('\u005f'),
    OPEN_BRACKET('\u005b'),
    CLOSE_BRACKET('\u005d'),
    CARET('\u005e'),
    AMPERSAND('\u0026'),
    PLUS('\u002b'),
    HYPHEN('\u002d'),
    PIPE('\u007c'),
    ASTERISK('\u002a'),
    SLASH('\u002f'),
    QUESTION_MARK('\u003f'),
    PERCENT('\u0025'),
    STATEMENT_DELIMITER('\u003b'),
    EQUALS('\u003d'),
    OPEN_PARENTHESIS('\u0028'),
    CLOSE_PARENTHESIS('\u0029'),
    AT('\u0040'),
    HASH_MARK('\u0023'),
    TILDE('\u007e'),
    OPEN_BRACE('\u007b'),
    CLOSE_BRACE('\u007d'),
    OPEN_ANGLE('\u003c'),

    // lmao this is just hilarious, but yea i just dont want to ruin the structuring of the language, so these tables are necessary
    LOWERCASE_A('\u0061'),
    LOWERCASE_I('\u0069'),
    LOWERCASE_S('\u0073'),
    CLOSE_ANGLE('\u003e'),
    EXCLAMATION('\u0021'),
    COMMA('\u002c'),
    PERIOD('\u002e'),
    COLON('\u003a');

    override fun toString(): String {
        return rep.toString()
    }
}



