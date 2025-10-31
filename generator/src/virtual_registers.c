#include "kira_runtime.h"
#include "kira_diagnostics.h"

String kiraVMRegisterTypeNames[] = {
    "KIRA_REGISTER_TYPE_BYTE"
    "KIRA_REGISTER_TYPE_WORD",
    "KIRA_REGISTER_TYPE_DWORD"
    "KIRA_REGISTER_TYPE_FLOAT",
    "KIRA_REGISTER_TYPE_UNSET"
};

Void kiraVMRegisterSetWord(KiraVMRegisterFile* regFile, KiraAddress reg, Word value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_WORD;
    regFile->registers[reg].value.wordValue = value;
}

Void kiraVMRegisterSetByte(KiraVMRegisterFile* regFile, KiraAddress reg, Byte value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_BYTE;
    regFile->registers[reg].value.byteValue = value;
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

Byte kiraVMRegisterGetByte(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_BYTE);
    return regFile->registers[reg].value.byteValue;
}

Word kiraVMRegisterGetWord(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_WORD);
    return regFile->registers[reg].value.wordValue;
}

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_FLOAT);
    return regFile->registers[reg].value.floatValue;
}

KiraVMRegisterType kiraVMRegisterTypeAt(KiraVMRegisterFile* regFile, KiraAddress reg)
{
    if(!regFile || reg >= KIRA_VM_MAX_REGISTERS)
    {
        _PANICF("Received invalid register %u (out-of-bounds)", reg);
    }
    return regFile->registers[reg].type;
}
