#include "kira_runtime.h"
#include <stdio.h>

KiraProgram* createFibonacciProgram()
{
    KiraProgram* program = (KiraProgram*) calloc(1, sizeof(KiraProgram));
    program->header.magic[0] = 'K';
    program->header.magic[1] = 'I';
    program->header.magic[2] = 'R';
    program->header.magic[3] = 'A';
    program->header.majorVersion = 1;
    program->header.minorVersion = 0;
    program->header.entryPoint = 0;
    program->constantPool = kiraConstantPoolCreate();
    UInt16 mainNameIdx = kiraConstantPoolAddUTF8(program->constantPool, "main");
    UInt16 mainDescIdx = kiraConstantPoolAddUTF8(program->constantPool, "()I");
    UInt16 fibNameIdx = kiraConstantPoolAddUTF8(program->constantPool, "fibonacci");
    UInt16 fibDescIdx = kiraConstantPoolAddUTF8(program->constantPool, "(I)I");
    program->methodTable = kiraMethodTableCreate();
    KiraMethodInfo mainMethod = {
        .nameIndex = mainNameIdx,
        .descriptorIndex = mainDescIdx,
        .codeOffset = 0,
        .codeLength = 0,
        .maxStack = 2,
        .maxLocals = 1,
        .paramCount = 0,
        .flags = 0
    };
    kiraMethodTableAdd(program->methodTable, mainMethod);
    KiraMethodInfo fibMethod = {
        .nameIndex = fibNameIdx,
        .descriptorIndex = fibDescIdx,
        .codeOffset = 0,
        .codeLength = 0,
        .maxStack = 4,
        .maxLocals = 1,
        .paramCount = 1,
        .flags = 0
    };
    kiraMethodTableAdd(program->methodTable, fibMethod);
    program->classTable = kiraClassTableCreate();
    UInt8* bytecode = (UInt8*) malloc(1024);
    UInt32 offset = 0;
    program->methodTable->methods[0].codeOffset = offset;
    bytecode[offset++] = OP_BIPUSH;
    bytecode[offset++] = 10;
    bytecode[offset++] = OP_INVOKE_STATIC;
    bytecode[offset++] = 0;
    bytecode[offset++] = 1;
    bytecode[offset++] = OP_DUP;
    bytecode[offset++] = OP_PRINT;
    bytecode[offset++] = OP_IRETURN;
    program->methodTable->methods[0].codeLength = offset - program->methodTable->methods[0].codeOffset;
    program->methodTable->methods[1].codeOffset = offset;
    UInt32 fibStart = offset;
    bytecode[offset++] = OP_ILOAD_0;
    bytecode[offset++] = OP_CONST_I32_1;
    bytecode[offset++] = OP_IF_ICMPGT;
    bytecode[offset++] = 0;
    bytecode[offset++] = 4;
    bytecode[offset++] = OP_ILOAD_0;
    bytecode[offset++] = OP_IRETURN;
    bytecode[offset++] = OP_ILOAD_0;
    bytecode[offset++] = OP_CONST_I32_1;
    bytecode[offset++] = OP_ISUB;
    bytecode[offset++] = OP_INVOKE_STATIC;
    bytecode[offset++] = 0;
    bytecode[offset++] = 1;
    bytecode[offset++] = OP_ILOAD_0;
    bytecode[offset++] = OP_CONST_I32_2;
    bytecode[offset++] = OP_ISUB;
    bytecode[offset++] = OP_INVOKE_STATIC;
    bytecode[offset++] = 0;
    bytecode[offset++] = 1;
    bytecode[offset++] = OP_IADD;
    bytecode[offset++] = OP_IRETURN;
    program->methodTable->methods[1].codeLength = offset - program->methodTable->methods[1].codeOffset;
    program->bytecodeLength = offset;
    program->bytecode = (UInt8*) realloc(bytecode, offset);
    return program;
}

Int32 main(Void)
{
    KiraProgram* program = createFibonacciProgram();
    kiraSaveBytecode(program, "fibonacci.kir");
    printf("\nExecuting program:\n");
    kiraProgramExecute(program);
    kiraFreeProgram(program);
    return 0;
}
