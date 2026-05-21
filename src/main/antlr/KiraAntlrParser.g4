parser grammar KiraAntlrParser;

options {
    tokenVocab = KiraAntlrLexer;
}

program
    : separators* moduleDecl separators* importDecl* separators* topLevelDecl* separators* EOF
    ;

moduleDecl
    : MODULE stringLiteral
    ;

importDecl
    : USE stringLiteral
    ;

topLevelDecl
    : classDecl
    | traitDecl
    | enumDecl
    | variantDecl
    | functionDecl
    | variableDecl
    | typeAliasDecl
    ;

classDecl
    : modifiers? CLASS typeRef typeParams? inheritance? classBody
    ;

classBody
    : LBRACE separators* classMember (separators+ classMember)* separators* RBRACE
    | LBRACE separators* RBRACE
    ;

classMember
    : variableDecl
    | methodDecl
    | initiallyBlock
    | finallyBlock
    ;

methodDecl
    : OVERRIDE? modifiers? MUT? FX callableName typeParams? LPAREN parameterList? RPAREN COLON typeRef (block | SEMI)
    ;

initiallyBlock
    : INITIALLY block
    ;

finallyBlock
    : FINALLY block
    ;

traitDecl
    : modifiers? TRAIT typeRef typeParams? inheritance? traitBody
    ;

traitBody
    : LBRACE separators* traitMember (separators+ traitMember)* separators* RBRACE
    | LBRACE separators* RBRACE
    ;

traitMember
    : methodSignature
    | methodDecl
    ;

methodSignature
    : modifiers? FX callableName LPAREN parameterList? RPAREN COLON typeRef
    ;

enumDecl
    : modifiers? ENUM typeIdentifier COLON primitiveType LBRACE enumVariant (COMMA enumVariant)* RBRACE
    ;

enumVariant
    : typeIdentifier ASSIGN literal
    ;

variantDecl
    : modifiers? VARIANT typeRef typeParams? inheritance? variantBody
    ;

variantBody
    : LBRACE separators* variantMember (separators+ variantMember)* separators* RBRACE
    | LBRACE separators* RBRACE
    ;

variantMember
    : classDecl
    | variableDecl
    | functionDecl
    ;

typeAliasDecl
    : modifiers? ALIAS typeRef typeParams? AS typeRef
    ;

functionDecl
    : modifiers? FX callableName typeParams? LPAREN parameterList? RPAREN COLON typeRef (block | SEMI)
    ;

variableDecl
    : modifiers? MUT? IDENTIFIER COLON typeRef (ASSIGN expression)?
    ;

assignment
    : IDENTIFIER ASSIGN expression
    ;

statement
    : variableDecl
    | assignment
    | expression
    | ifStatement
    | whileStatement
    | doWhileStatement
    | forStatement
    | returnStatement
    | breakStatement
    | continueStatement
    | throwStatement
    | tryStatement
    | useStatement
    | classDecl
    | traitDecl
    | enumDecl
    | variantDecl
    | typeAliasDecl
    ;

block
    : LBRACE separators* statement (statementSep+ statement)* separators* RBRACE
    | LBRACE separators* RBRACE
    ;

ifStatement
    : IF parenthesizedOrSimpleExpr block (ELSE IF parenthesizedOrSimpleExpr block)* (ELSE block)?
    ;

whileStatement
    : WHILE parenthesizedOrSimpleExpr block
    ;

doWhileStatement
    : DO block WHILE parenthesizedOrSimpleExpr
    ;

forStatement
    : FOR LPAREN? MUT IDENTIFIER COLON expression RPAREN? block
    ;

returnStatement
    : RETURN expression
    ;

breakStatement
    : BREAK
    ;

continueStatement
    : CONTINUE
    ;

throwStatement
    : THROW expression
    ;

tryStatement
    : TRY block ON IDENTIFIER COLON typeRef block
    ;

useStatement
    : USE stringLiteral
    ;

expression
    : assignmentExpression
    ;

assignmentExpression
    : logicalOrExpression (assignmentOperator assignmentExpression)?
    ;

assignmentOperator
    : ASSIGN
    | OP_ASSIGN_ADD
    | OP_ASSIGN_SUB
    | OP_ASSIGN_MUL
    | OP_ASSIGN_DIV
    | OP_ASSIGN_MOD
    | OP_ASSIGN_OR
    | OP_ASSIGN_AND
    | OP_ASSIGN_SHL
    | OP_ASSIGN_SHR
    | OP_ASSIGN_XOR
    ;

logicalOrExpression
    : logicalAndExpression (OP_OR logicalAndExpression)*
    ;

logicalAndExpression
    : equalityExpression (OP_AND equalityExpression)*
    ;

equalityExpression
    : relationalExpression ((OP_EQ | OP_NE) relationalExpression)*
    ;

relationalExpression
    : rangeExpression ((LT | GT | OP_LE | OP_GE) rangeExpression)*
    ;

rangeExpression
    : additiveExpression (OP_RANGE additiveExpression)*
    ;

additiveExpression
    : multiplicativeExpression ((PLUS | MINUS) multiplicativeExpression)*
    ;

multiplicativeExpression
    : unaryExpression ((STAR | SLASH | PERCENT) unaryExpression)*
    ;

unaryExpression
    : (MINUS | BANG | PLUS | TILDE) unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression postfixSuffix*
    ;

postfixSuffix
    : DOT identifierExpr
    | LBRACK expression RBRACK
    | LPAREN argumentList? RPAREN
    | AS typeRef
    | IS typeRef
    ;

primaryExpression
    : identifierExpr
    | literal
    | arrayLiteral
    | lambdaExpression
    | parenthesizedExpression
    | objectCreation
    | intrinsicCall
    | THIS
    ;

lambdaExpression
    : FX LPAREN parameterList? RPAREN COLON typeRef block
    ;

objectCreation
    : typeRef LBRACE constructorArguments? RBRACE
    ;

constructorArguments
    : constructorArgument (COMMA constructorArgument)*
    ;

constructorArgument
    : (IDENTIFIER ASSIGN)? expression
    ;

argumentList
    : expression (COMMA expression)*
    ;

typeRef
    : (typeIdentifier | intrinsicIdentifier) typeArguments? (QMARK)?
    ;

typeArguments
    : LT typeRef (COMMA typeRef)* GT
    ;

typeParams
    : LT typeParam (COMMA typeParam)* GT
    ;

typeParam
    : typeIdentifier (COLON typeRef)?
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : modifiers? IDENTIFIER COLON typeRef (ASSIGN expression)?
    ;

modifiers
    : modifier+
    ;

modifier
    : PUB
    | MUT
    | REQUIRE
    ;

inheritance
    : COLON typeRef (COMMA typeRef)*
    ;

callableName
    : identifierExpr
    | intrinsicIdentifier
    ;

identifierExpr
    : IDENTIFIER
    | typeIdentifier
    ;

typeIdentifier
    : TYPE_IDENTIFIER
    ;

intrinsicIdentifier
    : INTRINSIC_IDENTIFIER
    ;

intrinsicCall
    : intrinsicIdentifier LPAREN argumentList? RPAREN
    ;

literal
    : integerLiteral
    | floatLiteral
    | stringLiteral
    | BOOL_LITERAL
    | NULL
    ;

integerLiteral
    : INTEGER_LITERAL
    ;

floatLiteral
    : FLOAT_LITERAL
    ;

stringLiteral
    : STRING_LITERAL
    ;

arrayLiteral
    : LBRACK (expression (COMMA expression)*)? RBRACK
    ;

primitiveType
    : typeIdentifier
    ;

parenthesizedExpression
    : LPAREN expression RPAREN
    ;

parenthesizedOrSimpleExpr
    : LPAREN expression RPAREN
    | expression
    ;

statementSep
    : SEMI
    | NL
    ;

separators
    : statementSep+
    ;
