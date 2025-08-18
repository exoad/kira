#include "kira_ir.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

KiraProgram* kiraProgram(String fileName)
{
    CFile* file = fopen(fileName, "rb");
    if(!file)
    {
        return null;
    }
    KiraProgram* program = malloc(sizeof(KiraProgram));
    fread(&program->header, sizeof(KiraHeader), 1, file);
    if(memcmp(program->header.magic, "KIRA", 4) != 0)
    {
        free(program);
        fclose(file);
        return null;
    }
    program->stringTable = malloc(program->header.stringSize);
    fread(program->stringTable, program->header.stringSize, 1, file);
    Size inStringSize = sizeof(KiraInstruction) * program->header.instructionCount;
    program->instructions = malloc(inStringSize);
    fread(program->instructions, inStringSize, 1, file);
    fclose(file);
    return program;
}