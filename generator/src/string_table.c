#include "kira_ir.h"

KiraStringTable* kiraStringTable()
{
    KiraStringTable* table = malloc(sizeof(KiraStringTable));
    table->strings = malloc(sizeof(Int8*) * 16);
    table->count = 0;
    table->capacity = 16;
    table->totalSize = 0;
    return table;
}

UInt32 kiraStringTableAdd(KiraStringTable* table, String str)
{
    if(table->count >= table->capacity)
    {
        table->capacity *= 2;
        table->strings = (Int8**) realloc(table->strings, sizeof(Int8*) * table->capacity);
    }
    UInt32 offset = table->totalSize;
    table->strings[table->count] = strdup(str);
    table->count++;
    table->totalSize += strlen(str) + 1;
    return offset;
}

String kiraStringTableGet(KiraStringTable* table, UInt32 offset)
{
    return offset >= table->totalSize ? null : (String) table + offset;
}

Void kiraFormIR(String fileName, KiraInstruction* instructions, UInt32 instructionsCount, KiraStringTable* strings)
{
    CFile* file = fopen(fileName, "wb");
    KiraHeader header = {
        .magic = {'K', 'I', 'R', 'A'},
        .version = 1,
        .flags = 0,
        .stringCount = strings->count,
        .stringSize = strings->totalSize,
        .instructionCount = instructionsCount
    };
    fwrite(&header, sizeof(header), 1, file);
    for(UInt32 i = 0; i < strings->count; i++)
    {
        fwrite(strings->strings[i], strlen(strings->strings[i]) + 1, 1, file);
    }
    fwrite(instructions, sizeof(KiraInstruction), instructionsCount, file);
    fclose(file);
}
