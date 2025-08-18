#ifndef KIRA_VM_H
#define KIRA_VM_H

#include "kira_ir.h"

#define KIRA_VM_MAX_REGISTERS 512

// --- struct: KiraVMRegisterType

typedef enum
{
    KIRA_REGISTER_TYPE_INT,
    KIRA_REGISTER_TYPE_FLOAT,
    KIRA_REGISTER_TYPE_STRING,
    KIRA_REGISTER_TYPE_UNSET
} KiraVMRegisterType;

String kiraVMRegisterTypeNames[] = {
    "KIRA_REGISTER_TYPE_INT",
    "KIRA_REGISTER_TYPE_FLOAT",
    "KIRA_REGISTER_TYPE_STRING",
    "KIRA_REGISTER_TYPE_UNSET"
};

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

Void kiraVMRegisterSetInt(KiraVMRegisterFile* regFile, UInt8 reg, Int32 value);

Void kiraVMRegisterSetFloat(KiraVMRegisterFile* regFile, UInt8 reg, Float32 value);

Void kiraVMRegisterSetString(KiraVMRegisterFile* regFile, UInt8 reg, String value);

Int32 kiraVMRegisterGetInt(KiraVMRegisterFile* regFile, UInt8 reg);

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, UInt8 reg);

String kiraVMRegisterGetString(KiraVMRegisterFile* regFile, UInt8 reg);

// --- struct: KiraVM

typedef struct {
    KiraVMRegisterFile* registers;
    KiraProgram* program;
    UInt32 programCount;
} KiraVM;

KiraVM* kiraVM(KiraProgram* program);

Void freeKiraVM(KiraVM* vm);

#endif