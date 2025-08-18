#include "kira_runtime.h"
#include "kira_diagnostics.h"

String kiraVMRegisterTypeNames[] = {
    "KIRA_REGISTER_TYPE_INT",
    "KIRA_REGISTER_TYPE_FLOAT",
    "KIRA_REGISTER_TYPE_STRING",
    "KIRA_REGISTER_TYPE_UNSET"
};

Void kiraVMRegisterSetInt(KiraVMRegisterFile* regFile, KiraAddress reg, Int32 value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_INT;
    regFile->registers[reg].value.intValue = value;
}

Void kiraVMRegisterSetFloat(KiraVMRegisterFile* regFile, KiraAddress reg, Float32 value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_FLOAT;
    regFile->registers[reg].value.floatValue = value;
}

Void kiraVMRegisterSetString(KiraVMRegisterFile* regFile, KiraAddress reg, String value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_STRING;
    regFile->registers[reg].value.stringValue = value;
}

Void checkRegister(KiraVMRegisterFile* regFile, KiraAddress reg, KiraVMRegisterType expected)
{
    if(!regFile || reg >= KIRA_VM_MAX_REGISTERS)
    {
        _PANICF("Received invalid register %u (non-existent)", reg);
    }
    // if(regFile->registers[reg].type != expected)
    // {
    //     _PANICF(
    //         "The register at %d is of [%s] but got [%s]",
    //         reg,
    //         kiraVMRegisterTypeNames[regFile->registers[reg].type],
    //         kiraVMRegisterTypeNames[expected]
    //     );
    // }
}

Int32 kiraVMRegisterGetInt(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_INT);
    return regFile->registers[reg].value.intValue;
}

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_FLOAT);
    return regFile->registers[reg].value.floatValue;
}

String kiraVMRegisterGetString(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_STRING);
    return regFile->registers[reg].value.stringValue;
}

KiraVMRegisterType kiraVMRegisterTypeAt(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    if(!regFile || reg >= KIRA_VM_MAX_REGISTERS)
    {
        _PANICF("Received invalid register %u (out-of-bounds)", reg);
    }
    return regFile->registers[reg].type;
}
