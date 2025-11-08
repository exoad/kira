#ifndef KIRA_VM_H
#define KIRA_VM_H

#include "kira_ir.h"

#define KIRA_VM_MAX_REGISTERS ((UInt8) 255)

// --- struct: KiraVMRegisterType

typedef enum
{
    KIRA_REGISTER_TYPE_BYTE,
    KIRA_REGISTER_TYPE_WORD,
    KIRA_REGISTER_TYPE_FLOAT,
    KIRA_REGISTER_TYPE_UNSET
} KiraVMRegisterType;

extern String kiraVMRegisterTypeNames[];

// --- struct: KiraVMRegister

typedef struct
{
    KiraVMRegisterType type;
    union
    {
        Int32 wordValue;
        Int8 byteValue;
        Float32 floatValue;
    } value;
} KiraVMRegister;

#define r0 0 // return result register
#define r1 1
#define r2 2
#define r3 3
#define r4 4
#define r5 5
#define r6 6
#define r7 7
#define r8 8
#define r9 9
#define rA 10 // first argument
#define rB 11 // second argument
#define rC 12 // third argument
#define rD 13 // fourth argument
#define rE 14 // fifth argument
#define rF 15 // sixth argument

// --- struct: KiraVMRegisterFile

typedef struct
{
    KiraVMRegister registers[KIRA_VM_MAX_REGISTERS];
} KiraVMRegisterFile;

KiraVMRegisterFile* kiraVMRegisterFile();

Void freeKiraVMRegisterFile(KiraVMRegisterFile* registerFile);

Void kiraVMRegisterSetWord(KiraVMRegisterFile* regFile, KiraAddress reg, Word value);

Void kiraVMRegisterSetFloat(KiraVMRegisterFile* regFile, KiraAddress reg, Float32 value);

Void kiraVMRegisterSetByte(KiraVMRegisterFile* regFile, KiraAddress reg, Byte value);

Byte kiraVMRegisterGetByte(KiraVMRegisterFile* regFile, KiraAddress reg);

Word kiraVMRegisterGetWord(KiraVMRegisterFile* regFile, KiraAddress reg);

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, KiraAddress reg);

KiraVMRegisterType kiraVMRegisterTypeAt(KiraVMRegisterFile* regFile, KiraAddress reg);

// --- struct: KiraVM

#define KIRA_VM_DEFAULT_CALL_STACK_SIZE 64

typedef struct
{
    KiraVMRegisterFile* registers;
    KiraProgram* program;
    UInt32 pc;
    KiraFunctionTable* functionTable;
    UInt32* callStack;
    UInt32 callStackTop;
    UInt32 callStackSize;
    union
    {
        UInt32 flags;
        struct
        {
            UInt32 zero: 1;
            UInt32 negative: 1;
            UInt32 carry: 1;
            UInt32 overflow: 1;
            UInt32 reserved: 28;
        } flagBits;
    };
} KiraVM;

KiraVM* kiraVM(KiraProgram* program);

Void freeKiraVM(KiraVM* vm);

#endif