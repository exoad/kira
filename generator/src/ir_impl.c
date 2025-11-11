#include "kira_ir.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

KiraConstantPool* kiraConstantPoolCreate()
{
    KiraConstantPool* pool = (KiraConstantPool*) malloc(sizeof(KiraConstantPool));
    pool->capacity = 16;
    pool->count = 0;
    pool->constants = (KiraConstant*) calloc(pool->capacity, sizeof(KiraConstant));
    return pool;
}

Void kiraConstantPoolFree(KiraConstantPool* pool)
{
    if(pool == null)
    {
        return;
    }
    for(UInt32 i = 0; i < pool->count; i++)
    {
        if(pool->constants[i].type == CONST_UTF8 && pool->constants[i].data.utf8.value != null)
        {
            free((Void*) pool->constants[i].data.utf8.value);
        }
    }
    free(pool->constants);
    free(pool);
}

static Void kiraConstantPoolGrow(KiraConstantPool* pool)
{
    UInt32 newCapacity = pool->capacity * 2;
    pool->constants = (KiraConstant*) realloc(pool->constants, newCapacity * sizeof(KiraConstant));
    pool->capacity = newCapacity;
}

UInt16 kiraConstantPoolAddInt(KiraConstantPool* pool, Int32 value)
{
    if(pool->count >= pool->capacity)
    {
        kiraConstantPoolGrow(pool);
    }
    UInt16 index = pool->count++;
    pool->constants[index].type = CONST_INTEGER;
    pool->constants[index].data.intValue = value;
    return index;
}

UInt16 kiraConstantPoolAddFloat(KiraConstantPool* pool, Float32 value)
{
    if(pool->count >= pool->capacity)
    {
        kiraConstantPoolGrow(pool);
    }
    UInt16 index = pool->count++;
    pool->constants[index].type = CONST_FLOAT;
    pool->constants[index].data.floatValue = value;
    return index;
}

UInt16 kiraConstantPoolAddUTF8(KiraConstantPool* pool, String str)
{
    if(pool->count >= pool->capacity)
    {
        kiraConstantPoolGrow(pool);
    }
    UInt32 length = strlen(str);
    Int8* copy = (Int8*) malloc(length + 1);
    strcpy(copy, str);
    UInt16 index = pool->count++;
    pool->constants[index].type = CONST_UTF8;
    pool->constants[index].data.utf8.value = copy;
    pool->constants[index].data.utf8.length = length;
    return index;
}

UInt16 kiraConstantPoolAddString(KiraConstantPool* pool, UInt16 utf8Index)
{
    if(pool->count >= pool->capacity)
    {
        kiraConstantPoolGrow(pool);
    }
    UInt16 index = pool->count++;
    pool->constants[index].type = CONST_STRING;
    pool->constants[index].data.stringIndex = utf8Index;
    return index;
}

KiraConstant* kiraConstantPoolGet(KiraConstantPool* pool, UInt16 index)
{
    if(index >= pool->count)
    {
        return null;
    }
    return &pool->constants[index];
}

KiraMethodTable* kiraMethodTableCreate()
{
    KiraMethodTable* table = (KiraMethodTable*) malloc(sizeof(KiraMethodTable));
    table->capacity = 16;
    table->count = 0;
    table->methods = (KiraMethodInfo*) calloc(table->capacity, sizeof(KiraMethodInfo));
    return table;
}

Void kiraMethodTableFree(KiraMethodTable* table)
{
    if(table == null)
    {
        return;
    }
    free(table->methods);
    free(table);
}

static Void kiraMethodTableGrow(KiraMethodTable* table)
{
    UInt32 newCapacity = table->capacity * 2;
    table->methods = (KiraMethodInfo*) realloc(table->methods, newCapacity * sizeof(KiraMethodInfo));
    table->capacity = newCapacity;
}

UInt16 kiraMethodTableAdd(KiraMethodTable* table, KiraMethodInfo method)
{
    if(table->count >= table->capacity)
    {
        kiraMethodTableGrow(table);
    }
    UInt16 index = table->count++;
    table->methods[index] = method;
    return index;
}

KiraMethodInfo* kiraMethodTableGet(KiraMethodTable* table, UInt16 index)
{
    if(index >= table->count)
    {
        return null;
    }
    return &table->methods[index];
}

KiraMethodInfo* kiraMethodTableFindByName(KiraMethodTable* table, KiraConstantPool* pool, String name)
{
    for(UInt32 i = 0; i < table->count; i++)
    {
        KiraConstant* nameConst = kiraConstantPoolGet(pool, table->methods[i].nameIndex);
        if(nameConst && nameConst->type == CONST_UTF8)
        {
            if(strcmp(nameConst->data.utf8.value, name) == 0)
            {
                return &table->methods[i];
            }
        }
    }
    return null;
}

KiraClassTable* kiraClassTableCreate()
{
    KiraClassTable* table = (KiraClassTable*) malloc(sizeof(KiraClassTable));
    table->capacity = 16;
    table->count = 0;
    table->classes = (KiraClassInfo*) calloc(table->capacity, sizeof(KiraClassInfo));
    return table;
}

Void kiraClassTableFree(KiraClassTable* table)
{
    if(table == null)
    {
        return;
    }
    free(table->classes);
    free(table);
}

static Void kiraClassTableGrow(KiraClassTable* table)
{
    UInt32 newCapacity = table->capacity * 2;
    table->classes = (KiraClassInfo*) realloc(table->classes, newCapacity * sizeof(KiraClassInfo));
    table->capacity = newCapacity;
}

UInt16 kiraClassTableAdd(KiraClassTable* table, KiraClassInfo classInfo)
{
    if(table->count >= table->capacity)
    {
        kiraClassTableGrow(table);
    }
    UInt16 index = table->count++;
    table->classes[index] = classInfo;
    return index;
}

KiraClassInfo* kiraClassTableGet(KiraClassTable* table, UInt16 index)
{
    if(index >= table->count)
    {
        return null;
    }
    return &table->classes[index];
}

KiraProgram* kiraLoadProgram(String fileName)
{
    FILE* file = fopen(fileName, "rb");
    if(file == null)
    {
        fprintf(stderr, "ERROR: Cannot open file '%s'\n", fileName);
        return null;
    }
    KiraProgram* program = (KiraProgram*) calloc(1, sizeof(KiraProgram));
    fread(&program->header, sizeof(KiraBytecodeHeader), 1, file);
    if(memcmp(program->header.magic, "KIRA", 4) != 0)
    {
        fprintf(stderr, "ERROR: Invalid bytecode file format\n");
        fclose(file);
        free(program);
        return null;
    }
    program->constantPool = kiraConstantPoolCreate();
    fread(&program->constantPool->count, sizeof(UInt32), 1, file);
    if(program->constantPool->count > 0)
    {
        if(program->constantPool->count > program->constantPool->capacity)
        {
            program->constantPool->capacity = program->constantPool->count;
            program->constantPool->constants = (KiraConstant*) realloc(
                program->constantPool->constants,
                program->constantPool->capacity * sizeof(KiraConstant)
            );
        }
        for(UInt32 i = 0; i < program->constantPool->count; i++)
        {
            KiraConstant* constant = &program->constantPool->constants[i];
            fread(&constant->type, sizeof(KiraConstantType), 1, file);
            switch(constant->type)
            {
                case CONST_UTF8:
                    fread(&constant->data.utf8.length, sizeof(UInt32), 1, file);
                    constant->data.utf8.value = (String) malloc(constant->data.utf8.length + 1);
                    fread((Void*) constant->data.utf8.value, 1, constant->data.utf8.length, file);
                    ((Int8*) constant->data.utf8.value)[constant->data.utf8.length] = '\0';
                    break;
                case CONST_INTEGER:
                    fread(&constant->data.intValue, sizeof(Int32), 1, file);
                    break;
                case CONST_FLOAT:
                    fread(&constant->data.floatValue, sizeof(Float32), 1, file);
                    break;
                case CONST_STRING:
                    fread(&constant->data.stringIndex, sizeof(UInt16), 1, file);
                    break;
                default:
                    break;
            }
        }
    }
    program->methodTable = kiraMethodTableCreate();
    fread(&program->methodTable->count, sizeof(UInt32), 1, file);
    if(program->methodTable->count > 0)
    {
        if(program->methodTable->count > program->methodTable->capacity)
        {
            program->methodTable->capacity = program->methodTable->count;
            program->methodTable->methods = (KiraMethodInfo*) realloc(
                program->methodTable->methods,
                program->methodTable->capacity * sizeof(KiraMethodInfo)
            );
        }
        fread(program->methodTable->methods, sizeof(KiraMethodInfo), program->methodTable->count, file);
    }
    program->classTable = kiraClassTableCreate();
    fread(&program->classTable->count, sizeof(UInt32), 1, file);
    if(program->classTable->count > 0)
    {
        if(program->classTable->count > program->classTable->capacity)
        {
            program->classTable->capacity = program->classTable->count;
            program->classTable->classes = (KiraClassInfo*) realloc(
                program->classTable->classes,
                program->classTable->capacity * sizeof(KiraClassInfo)
            );
        }
        fread(program->classTable->classes, sizeof(KiraClassInfo), program->classTable->count, file);
    }
    fread(&program->bytecodeLength, sizeof(UInt32), 1, file);
    program->bytecode = (UInt8*) malloc(program->bytecodeLength);
    fread(program->bytecode, 1, program->bytecodeLength, file);
    fclose(file);
    return program;
}

Void kiraFreeProgram(KiraProgram* program)
{
    if(program == null)
    {
        return;
    }
    kiraConstantPoolFree(program->constantPool);
    kiraMethodTableFree(program->methodTable);
    kiraClassTableFree(program->classTable);
    free(program->bytecode);
    free(program);
}

Void kiraSaveBytecode(KiraProgram* program, String fileName)
{
    FILE* file = fopen(fileName, "wb");
    if(file == null)
    {
        fprintf(stderr, "ERROR: Cannot create file '%s'\n", fileName);
        return;
    }
    fwrite(&program->header, sizeof(KiraBytecodeHeader), 1, file);
    fwrite(&program->constantPool->count, sizeof(UInt32), 1, file);
    for(UInt32 i = 0; i < program->constantPool->count; i++)
    {
        KiraConstant* constant = &program->constantPool->constants[i];
        fwrite(&constant->type, sizeof(KiraConstantType), 1, file);
        switch(constant->type)
        {
            case CONST_UTF8:
                fwrite(&constant->data.utf8.length, sizeof(UInt32), 1, file);
                fwrite(constant->data.utf8.value, 1, constant->data.utf8.length, file);
                break;
            case CONST_INTEGER:
                fwrite(&constant->data.intValue, sizeof(Int32), 1, file);
                break;
            case CONST_FLOAT:
                fwrite(&constant->data.floatValue, sizeof(Float32), 1, file);
                break;
            case CONST_STRING:
                fwrite(&constant->data.stringIndex, sizeof(UInt16), 1, file);
                break;
            default:
                break;
        }
    }
    fwrite(&program->methodTable->count, sizeof(UInt32), 1, file);
    fwrite(program->methodTable->methods, sizeof(KiraMethodInfo), program->methodTable->count, file);
    fwrite(&program->classTable->count, sizeof(UInt32), 1, file);
    fwrite(program->classTable->classes, sizeof(KiraClassInfo), program->classTable->count, file);
    fwrite(&program->bytecodeLength, sizeof(UInt32), 1, file);
    fwrite(program->bytecode, 1, program->bytecodeLength, file);
    fclose(file);
}

KiraProgram* kiraLoadBytecode(String fileName)
{
    return kiraLoadProgram(fileName);
}
