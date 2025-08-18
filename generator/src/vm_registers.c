#include "kira_vm.h"
#include "kira_diagnostics.h"

Void kiraVMRegisterSetInt(KiraVMRegisterFile* regFile, UInt8 reg, Int32 value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_INT;
    regFile->registers[reg].value.intValue = value;
}

Void kiraVMRegisterSetFloat(KiraVMRegisterFile* regFile, UInt8 reg, Float32 value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_FLOAT;
    regFile->registers[reg].value.floatValue = value;
}

Void kiraVMRegisterSetString(KiraVMRegisterFile* regFile, UInt8 reg, String value)
{
    _CHECK(regFile != null, "Received a null pointer to a register file.");
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        return;
    }
    regFile->registers[reg].type = KIRA_REGISTER_TYPE_STRING;
    regFile->registers[reg].value.stringValue = value;
}

Void checkRegister(KiraVMRegisterFile* regFile, UInt8 reg, KiraVMRegisterType expected)
{
    if(reg >= KIRA_VM_MAX_REGISTERS)
    {
        _PANICF("Received invalid register %d (out-of-bounds)", reg);
    }
    if(regFile->registers[reg].type != expected)
    {
        _PANICF(
            "The register at %d is of [%s] but got [%s]",
            reg,
            kiraVMRegisterTypeNames[regFile->registers[reg].type],
            kiraVMRegisterTypeNames[expected]
        );
    }
}

Int32 kiraVMRegisterGetInt(KiraVMRegisterFile* regFile, UInt8 reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_INT);
    return regFile->registers[reg].value.intValue;
}

Float32 kiraVMRegisterGetFloat(KiraVMRegisterFile* regFile, UInt8 reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_FLOAT);
    return regFile->registers[reg].value.floatValue;
}

String kiraVMRegisterGetString(KiraVMRegisterFile* regFile, UInt8 reg)
{
    checkRegister(regFile, reg, KIRA_REGISTER_TYPE_STRING);
    return regFile->registers[reg].value.stringValue;
}
