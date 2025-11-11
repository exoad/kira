#ifndef KIRA_CODEGEN_H
#define KIRA_CODEGEN_H

#include "kira_ir.h"
#include "kira_runtime.h"

typedef enum
{
    TARGET_BYTECODE,
    TARGET_X86_64,
    TARGET_ARM64,
} KiraCodeGenTarget;

typedef struct
{
    UInt32 refCount;
    UInt32 typeId;
    UInt32 size;
    UInt32 flags;
} KiraNativeObjectHeader;

#define KIRA_NATIVE_OBJECT_HEADER_SIZE sizeof(KiraNativeObjectHeader)

typedef struct
{
    KiraCodeGenTarget target;
    KiraProgram* program;
    FILE* outputFile;
    UInt32 labelCounter;
    UInt32 tempCounter;
    struct
    {
        String name;
        Int32 stackOffset;
        Bool isParameter;
    } locals[256];
    UInt32 localCount;
    Int32 stackSize;
    Bool inFunction;

} KiraCodeGenContext;

KiraCodeGenContext* kiraCodeGenCreate(KiraCodeGenTarget target);
Void kiraCodeGenFree(KiraCodeGenContext* ctx);
Bool kiraCodeGenOpenOutput(KiraCodeGenContext* ctx, String filename);
Void kiraCodeGenCloseOutput(KiraCodeGenContext* ctx);
Void kiraCodeGenBegin(KiraCodeGenContext* ctx);
Void kiraCodeGenEnd(KiraCodeGenContext* ctx);
Void kiraCodeGenFunctionBegin(KiraCodeGenContext* ctx, String name, UInt32 paramCount, UInt32 localCount);
Void kiraCodeGenFunctionEnd(KiraCodeGenContext* ctx);
Void kiraCodeGenLoadInt(KiraCodeGenContext* ctx, Int32 value);
Void kiraCodeGenLoadLocal(KiraCodeGenContext* ctx, UInt32 index);
Void kiraCodeGenStoreLocal(KiraCodeGenContext* ctx, UInt32 index);
Void kiraCodeGenAdd(KiraCodeGenContext* ctx);
Void kiraCodeGenSub(KiraCodeGenContext* ctx);
Void kiraCodeGenMul(KiraCodeGenContext* ctx);
Void kiraCodeGenDiv(KiraCodeGenContext* ctx);
Void kiraCodeGenCall(KiraCodeGenContext* ctx, String functionName);
Void kiraCodeGenReturn(KiraCodeGenContext* ctx);
Void kiraCodeGenPrint(KiraCodeGenContext* ctx);
String kiraCodeGenNewLabel(KiraCodeGenContext* ctx);
Void kiraCodeGenLabel(KiraCodeGenContext* ctx, String label);
Void kiraCodeGenJump(KiraCodeGenContext* ctx, String label);
Void kiraCodeGenJumpIfZero(KiraCodeGenContext* ctx, String label);
Void kiraCodeGenJumpIfNotZero(KiraCodeGenContext* ctx, String label);
Void kiraCodeGenCompare(KiraCodeGenContext* ctx);
Void kiraCodeGenRetain(KiraCodeGenContext* ctx);
Void kiraCodeGenRelease(KiraCodeGenContext* ctx);
Void kiraCodeGenAllocate(KiraCodeGenContext* ctx, UInt32 size, UInt32 typeId);
Void kiraAsmEmit(KiraCodeGenContext* ctx, String format, ...);
Void kiraAsmComment(KiraCodeGenContext* ctx, String comment);
Void kiraCodeGenEmitRuntimeSupport(KiraCodeGenContext* ctx);

#endif
