lexer grammar KiraAntlrLexer;

MODULE: 'module';
USE: 'use';
CLASS: 'class';
TRAIT: 'trait';
ENUM: 'enum';
VARIANT: 'variant';
ALIAS: 'alias';
AS: 'as';
FX: 'fx';
IF: 'if';
ELSE: 'else';
WHILE: 'while';
DO: 'do';
FOR: 'for';
IN: 'in';
RETURN: 'return';
BREAK: 'break';
CONTINUE: 'continue';
THROW: 'throw';
TRY: 'try';
ON: 'on';
IS: 'is';
THIS: 'this';
OVERRIDE: 'override';
PUB: 'pub';
MUT: 'mut';
REQUIRE: 'require';
FINALLY: 'finally';
INITIALLY: 'initially';

NULL: 'null';
BOOL_LITERAL: 'true' | 'false';

OP_ASSIGN_SHR: '>>>=';
OP_ASSIGN_SHL: '<<=';
OP_ASSIGN_OR: '|=';
OP_ASSIGN_AND: '&=';
OP_ASSIGN_XOR: '^=';
OP_ASSIGN_ADD: '+=';
OP_ASSIGN_SUB: '-=';
OP_ASSIGN_MUL: '*=';
OP_ASSIGN_DIV: '/=';
OP_ASSIGN_MOD: '%=';

OP_USHR: '>>>';
OP_SHL: '<<';
OP_SHR: '>>';
OP_EQ: '==';
OP_NE: '!=';
OP_LE: '<=';
OP_GE: '>=';
OP_AND: '&&';
OP_OR: '||';
OP_RANGE: '..';
SCOPE: '::';
ARROW: '->';

ASSIGN: '=';
PLUS: '+';
MINUS: '-';
STAR: '*';
SLASH: '/';
PERCENT: '%';
LT: '<';
GT: '>';
BANG: '!';
TILDE: '~';
PIPE: '|';
AMP: '&';
CARET: '^';
HASH: '#';
DOT: '.';
AT: '@';

LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
LBRACK: '[';
RBRACK: ']';
COMMA: ',';
COLON: ':';
SEMI: ';';
QMARK: '?';

INTRINSIC_IDENTIFIER: '@' [a-zA-Z_] [a-zA-Z0-9_]*;
TYPE_IDENTIFIER: [A-Z] [a-zA-Z0-9]*;
IDENTIFIER: [a-zA-Z] [a-zA-Z0-9]*;

FLOAT_LITERAL: [0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)?;
INTEGER_LITERAL: [0-9]+;
STRING_LITERAL: '"' ( '\\' [ntr"\\$] | ~["\\\r\n] )* '"';

LINE_COMMENT: '//' ~[\r\n]* -> skip;
WS: [ \t\f]+ -> skip;
NL: ('\r'? '\n')+;
