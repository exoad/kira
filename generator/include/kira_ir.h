#ifndef KIRA_H
#define KIRA_H

#include "kira_shared.h"

// --- struct: KiraOpCode

typedef enum KiraOpCode
{
    OP_ADD,
    OP_SUB,
    OP_MUL,
    OP_DIV,
    OP_MOD,
    OP_NEG,
    OP_LOAD_INT,
    OP_LOAD_FLOAT,
    OP_LOAD_STRING,
    OP_MOVE,
    OP_FUNC_DEF,
    OP_FUNC_END,
    OP_CALL,
    OP_RETURN,
    OP_PARAM,
    OP_ARG,
    OP_CMP_EQ,
    OP_JUMP
} KiraOpCode;

// --- struct: KiraInstruction

typedef struct
{
    UInt8 opcode;
    UInt8 dest;
    UInt8 operand1;
    UInt8 operand2;
    Int32 immediate;
} KiraInstruction;

// --- struct: KiraHeader

typedef struct
{
    Int8 magic[4]; // "kira"
    UInt16 version;
    UInt16 flags;
    UInt32 stringCount;
    UInt32 stringSize;
    UInt32 instructionCount;
} KiraHeader;

// --- struct: KiraProgram

typedef struct
{
    KiraHeader header;
    Int8* stringTable;
    KiraInstruction* instructions;
} KiraProgram;

KiraProgram* kiraProgram(String fileName);

Void kiraProgramExecute(KiraProgram* program);

// --- struct: KiraStringTable

typedef struct {
    Int8** strings;
    UInt32 count;
    UInt32 capacity;
    UInt32 totalSize;
} KiraStringTable;

KiraStringTable* kiraStringTable();

UInt32 kiraStringTableAdd(KiraStringTable* table, String str);

String kiraStringTableGet(KiraStringTable* program, UInt32 offset);

// --- external functions

Void kiraFormIR(String fileName, KiraInstruction* instructions, UInt32 instructionsCount, KiraStringTable* strings);

#endif