#ifndef KIRA_VM_H
#define KIRA_VM_H

#include "kira_ir.h"

#define KIRA_VM_MAX_REGISTERS 256

// --- struct: KiraVMRegisterType

typedef enum
{
    KIRA_REGISTER_TYPE_INT,
    KIRA_REGISTER_TYPE_FLOAT,
    KIRA_REGISTER_TYPE_STRING,
    KIRA_REGISTER_TYPE_UNSET
} KiraVMRegisterType;

extern String kiraVMRegisterTypeNames[];

// --- struct: KiraVMRegister

typedef struct
{
    KiraVMRegisterType type;
    union
    {
        Int32 intValue;
        Float32 floatValue;
        String stringValue;
    } value;
} KiraVMRegister;

// --- struct: KiraVMRegisterFile

typedef struct
{
    KiraVMRegister registers[KIRA_VM_MAX_REGISTERS];
} KiraVMRegisterFile;

KiraVMRegisterFile* kiraVMRegisterFile();

Void freeKiraVMRegisterFile(KiraVMRegisterFile* registerFile);

Void kiraVMRegisterSetInt(KiraVMRegisterFile* regFile, KiraAddress reg, Int32 value);

Void kiraVMRegisterSetFloat(KiraVMRegisterFile* regFile, KiraAddress reg, Float32 value);

Void kiraVMRegisterSetString(KiraVMRegisterFile* regFile, KiraAddress reg, String value);

Int32 kiraVMRegisterGetInt(KiraVMRegisterFile* regFile, KiraAddress reg);

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, KiraAddress reg);

String kiraVMRegisterGetString(KiraVMRegisterFile* regFile, KiraAddress reg);

KiraVMRegisterType kiraVMRegisterTypeAt(KiraVMRegisterFile* regFile, KiraAddress reg);

// --- struct: KiraVM

#define KIRA_VM_DEFAULT_CALL_STACK_SIZE 64

typedef struct {
    KiraVMRegisterFile* registers;
    KiraProgram* program;
    UInt32 programCount;
    KiraFunctionTable* functionTable;
    UInt32* callStack;
    UInt32 callStackTop;
    UInt32 callStackSize;
} KiraVM;

KiraVM* kiraVM(KiraProgram* program);

Void freeKiraVM(KiraVM* vm);

#endif