#ifndef KIRA_H
#define KIRA_H

#include "kira_shared.h"

typedef UInt8 KiraAddress;

// --- enum: KiraOpCode

typedef enum
{
    OP_IADD,
    OP_ISUB,
    OP_IMUL,
    OP_IDIV,
    OP_IMOD,
    OP_INEG,
    OP_FADD,
    OP_FSUB,
    OP_FMUL,
    OP_FDIV,
    OP_FNEG,
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
    OP_JUMP,
    OP_HALT,
    SYSOUT,
} KiraOpCode;

#define LOAD_INT(dest, op1, op2, immediate) (KiraInstruction) { OP_LOAD_INT, dest, op1, op2, immediate }
#define IADD(dest, op1, op2, immediate) (KiraInstruction) { OP_IADD, dest, op1, op2, immediate }
#define SYSOUT(op1) (KiraInstruction) { SYSOUT, 0, op1, 0, 0 }
#define HALT() (KiraInstruction) { OP_HALT, 0, 0, 0, 0 }

// --- struct: KiraInstruction

typedef struct
{
    UInt8 opcode;
    KiraAddress dest;
    KiraAddress operand1;
    KiraAddress operand2;
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

// --- struct: KiraFunction

typedef struct
{
    String name;
    UInt32 startAddress;
    KiraAddress parametersCount;
    KiraAddress localRegistersStart;
    KiraAddress localRegistersCount;
} KiraFunction;

// --- struct: KiraFunctionTable

#define KIRA_FUNCTION_TABLE_DEFAULT_CAPACITY 16

typedef struct
{
    KiraFunction* functions;
    UInt32 count;
    UInt32 capacity;
} KiraFunctionTable;

KiraFunctionTable* kiraFunctionTable();

Void freeKiraFunctionTable(KiraFunctionTable* table);

Void kiraFunctionTableAdd(KiraFunctionTable* table, String name, UInt32 address, UInt8 paramCount);

KiraFunction* kiraFunctionTableFind(KiraFunctionTable* table, String name);

Void kiraFunctionTableBuild(KiraFunctionTable* table, KiraProgram* program);

// --- external functions

Void kiraFormIR(String fileName, KiraInstruction* instructions, UInt32 instructionsCount, KiraStringTable* strings);

#endif