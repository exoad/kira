#include "kira_vm.h"
#include <stdio.h>
#include <stdlib.h>

Void kiraProgramExecute(KiraProgram* program)
{
    for(UInt32 i = 0; i < program->header.instructionCount; i++)
    {
        KiraInstruction* instruction = &program->instructions[i];
        switch(instruction->opcode)
        {
            case OP_ADD:
            {
                _PRINT("ADD r%d = r%d + r%d\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_SUB:
            {
                _PRINT("SUB r%d = r%d - r%d\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_MUL:
            {
                _PRINT("MUL r%d = r%d * r%d\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_DIV:
            {
                _PRINT("DIV r%d = r%d / r%d\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_MOD:
            {
                _PRINT("MOD r%d = r%d %% r%d\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_NEG:
            {
                _PRINT("NEG r%d = -r%d\n", instruction->dest, instruction->operand1);
                break;
            }
            case OP_LOAD_INT:
            {
                _PRINT("LOAD_INT r%d = %d\n", instruction->dest, instruction->immediate);
                break;
            }
            case OP_LOAD_FLOAT:
            {
                _PRINT("LOAD_FLOAT r%d = %f\n", instruction->dest, *(float*)&instruction->immediate);
                break;
            }
            case OP_LOAD_STRING:
            {
                String str = kiraStringTableGet(program->stringTable, instruction->immediate);
                _PRINT("LOAD_STRING r%d = \"%s\"\n", instruction->dest, str ? str : "<null>");
                break;
            }
            case OP_MOVE:
            {
                _PRINT("MOVE r%d = r%d\n", instruction->dest, instruction->operand1);
                break;
            }
            case OP_FUNC_DEF:
            {
                String funcName = kiraStringTableGet(program->stringTable, instruction->immediate);
                _PRINT("FUNC_DEF %s (params: %d)\n", funcName ? funcName : "<null>", instruction->operand1);
                break;
            }
            case OP_FUNC_END:
            {
                _PRINT("FUNC_END\n");
                break;
            }
            case OP_CALL:
            {
                String funcName = kiraStringTableGet(program->stringTable, instruction->immediate);
                _PRINT("CALL r%d = %s(%d args)\n", instruction->dest, funcName ? funcName : "<null>", instruction->operand1);
                break;
            }
            case OP_RETURN:
            {
                if (instruction->operand1 != 0)
                {
                    _PRINT("RETURN r%d\n", instruction->operand1);
                }
                else
                {
                    _PRINT("RETURN\n");
                }
                break;
            }
            case OP_PARAM:
            {
                _PRINT("PARAM %d -> r%d\n", instruction->operand1, instruction->dest);
                break;
            }
            case OP_ARG:
            {
                _PRINT("ARG %d = r%d\n", instruction->operand1, instruction->dest);
                break;
            }
            case OP_CMP_EQ:
            {
                _PRINT("CMP_EQ r%d = (r%d == r%d)\n", instruction->dest, instruction->operand1, instruction->operand2);
                break;
            }
            case OP_JUMP:
            {
                String label = kiraStringTableGet(program->stringTable, instruction->immediate);
                _PRINT("JUMP %s\n", label ? label : "<null>");
                break;
            }
            default:
            {
                _PRINT("UNKNOWN_OPCODE %d\n", instruction->opcode);
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
    return vm;
}

Void freeKiraVM(KiraVM* vm)
{
    if(vm != null)
    {
        freeKiraVMRegisterFile(vm->registers);
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