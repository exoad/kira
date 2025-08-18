#include "kira_ir.h"
#include <stdlib.h>
#include <string.h>

KiraFunctionTable* kiraFunctionTable()
{
    KiraFunctionTable* table = (KiraFunctionTable*) malloc(sizeof(KiraFunctionTable));
    if(!table)
    {
        return null;
    }
    table->capacity = KIRA_FUNCTION_TABLE_DEFAULT_CAPACITY;
    table->functions = malloc(sizeof(KiraFunction) * table->capacity);
    table->count = 0;
    if(!table->functions)
    {
        free(table);
        return null;
    }
    return table;
}

Void freeKiraFunctionTable(KiraFunctionTable* table)
{
    if(table)
    {
        free(table->functions);
        free(table);
    }
}

Void kiraFunctionTableAdd(KiraFunctionTable* table, String name, UInt32 address, UInt8 paramCount)
{
    if(!table || !name)
    {
        return;
    }
    if(table->count >= table->capacity)
    {
        table->capacity *= 2;
        table->functions = realloc(table->functions, sizeof(KiraFunction) * table->capacity);
        if(!table->functions)
        {
            return;
        }
    }
    KiraFunction* func = &table->functions[table->count];
    func->name = name;
    func->startAddress = address;
    func->parametersCount = paramCount;
    func->localRegistersStart = 0;
    func->localRegistersCount = 0;
    table->count++;
}

KiraFunction* kiraFunctionTableFind(KiraFunctionTable* table, String name)
{
    if(!table || !name)
    {
        return null;
    }
    for(UInt32 i = 0; i < table->count; i++)
    {
        if(table->functions[i].name && strcmp(table->functions[i].name, name) == 0)
        {
            return &table->functions[i];
        }
    }
    return null;
}

Void kiraFunctionTableBuild(KiraFunctionTable* table, KiraProgram* program)
{
    if(!table || !program)
    {
        return;
    }
    for(UInt32 i = 0; i < program->header.instructionCount; i++)
    {
        KiraInstruction* inst = &program->instructions[i];
        if(inst->opcode == OP_FUNC_DEF)
        {
            String funcName = kiraStringTableGet(program->stringTable, inst->immediate);
            UInt8 paramCount = inst->operand1;
            kiraFunctionTableAdd(table, funcName, i, paramCount);
        }
    }
}