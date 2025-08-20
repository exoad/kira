#include "kira_runtime.h"
#include <stdio.h>
#include <stdlib.h>

Void kiraProgramExecute(KiraProgram* program)
{
    KiraVM* vm = kiraVM(program);
    while(vm->programCount < program->header.instructionCount)
    {
        KiraInstruction* instruction = &program->instructions[vm->programCount];
        switch(instruction->opcode)
        {
            case OP_IADD:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, op1 + op2);
                vm->programCount++;
                break;
            }
            case OP_ISUB:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, op1 - op2);
                vm->programCount++;
                break;
            }
            case OP_IMUL:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, op1 * op2);
                vm->programCount++;
                break;
            }
            case OP_IDIV:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, op1 / op2);
                vm->programCount++;
                break;
            }
            case OP_IMOD:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, op1 % op2);
                vm->programCount++;
                break;
            }
            case OP_INEG:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                kiraVMRegisterSetInt(vm->registers, instruction->dest, -op1);
                vm->programCount++;
                break;
            }
            case OP_FADD:
            {
                Float32 op1 = kiraVMRegisterGetFloat(vm->registers, instruction->operand1);
                Float32 op2 = kiraVMRegisterGetFloat(vm->registers, instruction->operand2);
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, op1 + op2);
                vm->programCount++;
                break;
            }
            case OP_FSUB:
            {
                Float32 op1 = kiraVMRegisterGetFloat(vm->registers, instruction->operand1);
                Float32 op2 = kiraVMRegisterGetFloat(vm->registers, instruction->operand2);
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, op1 - op2);
                vm->programCount++;
                break;
            }
            case OP_FMUL:
            {
                Float32 op1 = kiraVMRegisterGetFloat(vm->registers, instruction->operand1);
                Float32 op2 = kiraVMRegisterGetFloat(vm->registers, instruction->operand2);
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, op1 * op2);
                vm->programCount++;
                break;
            }
            case OP_FDIV:
            {
                Float32 op1 = kiraVMRegisterGetFloat(vm->registers, instruction->operand1);
                Float32 op2 = kiraVMRegisterGetFloat(vm->registers, instruction->operand2);
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, op1 / op2);
                vm->programCount++;
                break;
            }
            case OP_FNEG:
            {
                Float32 op1 = kiraVMRegisterGetFloat(vm->registers, instruction->operand1);
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, -op1);
                vm->programCount++;
                break;
            }
            case OP_ICMP_LT:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                Int32 result = op1 - op2;
                vm->flagBits.zero = (result == 0) ? 1 : 0;
                vm->flagBits.negative = (result < 0) ? 1 : 0;
                vm->flagBits.carry = ((UInt32) op1 < (UInt32) op2) ? 1 : 0;
                vm->programCount++;
                break;
            }
            case OP_ICMP_GT:
            {
                Int32 op1 = kiraVMRegisterGetInt(vm->registers, instruction->operand1);
                Int32 op2 = kiraVMRegisterGetInt(vm->registers, instruction->operand2);
                Int32 result = op1 - op2;
                vm->flagBits.zero = (result == 0) ? 1 : 0;
                vm->flagBits.negative = (result < 0) ? 1 : 0;
                vm->flagBits.carry = ((UInt32) op1 > (UInt32) op2) ? 1 : 0;
                vm->flagBits.overflow = ((op1 ^ result) & (op2 ^ result)) < 0 ? 1 : 0;
                vm->programCount++;
                break;
            }
            case OP_JMP_ZERO:
            {
                if(vm->flagBits.zero)
                {
                    vm->programCount = instruction->immediate;
                }
                else
                {
                    vm->programCount++;
                }
                break;
            }
            case OP_JMP_LT:
            {
                if(vm->flagBits.negative)
                {
                    vm->programCount = instruction->immediate;
                }
                else
                {
                    vm->programCount++;
                }
                break;
            }
            case OP_LOAD_INT:
            {
                kiraVMRegisterSetInt(vm->registers, instruction->dest, instruction->immediate);
                vm->programCount++;
                break;
            }
            case OP_LOAD_FLOAT:
            {
                kiraVMRegisterSetFloat(vm->registers, instruction->dest, instruction->immediate);
                vm->programCount++;
                break;
            }
            case OP_MOVE:
            {
                KiraVMRegister* src = &vm->registers->registers[instruction->operand1];
                KiraVMRegister* dest = &vm->registers->registers[instruction->dest];
                dest->type = src->type;
                dest->value = src->value;
                vm->programCount++;
                break;
            }
            case OP_FUNC_DEF:
            {
                vm->programCount++;
                break;
            }
            case OP_FUNC_END:
            {
                vm->programCount++;
                break;
            }
            case OP_CALL:
            {
                String funcName = kiraStringTableGet(program->stringTable, instruction->immediate);
                KiraFunction* func = kiraFunctionTableFind(vm->functionTable, funcName);
                if(func && vm->callStackTop < vm->callStackSize - 1)
                {
                    vm->callStack[vm->callStackTop++] = vm->programCount + 1;
                    vm->callStack[vm->callStackTop++] = instruction->dest;
                    vm->programCount = func->startAddress + 1;
                }
                else
                {
                    _PRINT("ERROR: Function '%s' not found or call stack overflowed\n", funcName ? funcName : "<null>");
                    vm->programCount++;
                }
                break;
            }
            case OP_RETURN:
            {
                if(vm->callStackTop >= 2)
                {
                    KiraAddress returnReg = vm->callStack[--vm->callStackTop];
                    UInt32 returnAddr = vm->callStack[--vm->callStackTop];
                    if(instruction->operand1 != 0)
                    {
                        KiraVMRegister* retVal = &vm->registers->registers[instruction->operand1];
                        vm->registers->registers[returnReg] = *retVal;
                    }
                    vm->programCount = returnAddr;
                }
                else
                {
                    vm->programCount = program->header.instructionCount;
                }
                break;
            }
            // parameters and arguments are stored with a +10 offset, meaning in registers r10 and onwards.
            case OP_PARAM:
            {
                KiraAddress argReg = 10 + instruction->operand1;
                if(argReg < KIRA_VM_MAX_REGISTERS)
                {
                    KiraVMRegister* src = &vm->registers->registers[argReg];
                    KiraVMRegister* dest = &vm->registers->registers[instruction->dest];
                    *dest = *src;
                }
                vm->programCount++;
                break;
            }
            case OP_ARG:
            {
                KiraAddress argReg = 10 + instruction->operand1;
                if(argReg < KIRA_VM_MAX_REGISTERS)
                {
                    KiraVMRegister* src = &vm->registers->registers[instruction->operand2];
                    KiraVMRegister* dest = &vm->registers->registers[argReg];
                    *dest = *src;
                }
                vm->programCount++;
                break;
            }
            case OP_JUMP:
            {
                if(instruction->immediate < program->header.instructionCount)
                {
                    vm->programCount = instruction->immediate;
                }
                else
                {
                    vm->programCount++;
                }
                break;
            }
            case OP_HALT:
            {
                vm->programCount = vm->program->header.instructionCount;
                break;
            }
            case SYSOUT:
            {
                KiraVMRegister* reg = &vm->registers->registers[instruction->operand1];
                switch (reg->type)
                {
                    case KIRA_REGISTER_TYPE_INT:
                        _PRINT("%d", reg->value.intValue);
                        break;
                    case KIRA_REGISTER_TYPE_FLOAT:
                        _PRINT("%.6f", reg->value.floatValue);
                        break;
                    case KIRA_REGISTER_TYPE_UNSET:
                        _PRINT("<unset>");
                        break;
                    default:
                        _PRINT("<unknown-type>");
                        break;
                }
                vm->programCount++;
                break;
            }
            default:
            {
                _PRINT("UNKNOWN_OPCODE %d\n", instruction->opcode);
                vm->programCount++;
                break;
            }
        }
    }
}

KiraVM* kiraVM(KiraProgram* program)
{
    KiraVM* vm = (KiraVM*) malloc(sizeof(KiraVM));
    vm->registers = kiraVMRegisterFile();
    vm->program = program;
    vm->programCount = 0;
    vm->functionTable = kiraFunctionTable();
    if(vm->functionTable)
    {
        kiraFunctionTableBuild(vm->functionTable, program);
    }
    vm->callStackSize = KIRA_VM_DEFAULT_CALL_STACK_SIZE;
    vm->callStack = malloc(sizeof(UInt32) * vm->callStackSize);
    vm->callStackTop = 0;
    return vm;
}

Void freeKiraVM(KiraVM* vm)
{
    if(vm != null)
    {
        freeKiraVMRegisterFile(vm->registers);
        freeKiraFunctionTable(vm->functionTable);
        free(vm->callStack);
        free(vm);
    }
}

KiraVMRegisterFile* kiraVMRegisterFile()
{
    KiraVMRegisterFile* registerFile = (KiraVMRegisterFile*) malloc(sizeof(KiraVMRegisterFile));
    for(Int32 i = 0; i < KIRA_VM_MAX_REGISTERS; i++)
    {
        registerFile->registers[i].type = KIRA_REGISTER_TYPE_UNSET;
        registerFile->registers[i].value.intValue = 0;
    }
    return registerFile;
}

Void freeKiraVMRegisterFile(KiraVMRegisterFile* registerFile)
{
    if(registerFile != null)
    {
        free(registerFile);
    }
}