#include "kira_codegen.h"
#include <stdio.h>

Void generateNativeFibonacci()
{
    KiraCodeGenContext* ctx = kiraCodeGenCreate(TARGET_X86_64);

    if(!kiraCodeGenOpenOutput(ctx, "fibonacci.asm"))
    {
        printf("Failed to open output file\n");
        kiraCodeGenFree(ctx);
        return;
    }
    kiraCodeGenBegin(ctx);
    kiraCodeGenFunctionBegin(ctx, "fibonacci", 1, 1);
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenLoadInt(ctx, 1);
    kiraCodeGenCompare(ctx);
    String recursiveCase = kiraCodeGenNewLabel(ctx);
    kiraCodeGenJumpIfNotZero(ctx, recursiveCase);
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenReturn(ctx);
    kiraCodeGenLabel(ctx, recursiveCase);
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenLoadInt(ctx, 1);
    kiraCodeGenSub(ctx);
    kiraCodeGenCall(ctx, "fibonacci");
    kiraCodeGenStoreLocal(ctx, 1);
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenLoadInt(ctx, 2);
    kiraCodeGenSub(ctx);
    kiraCodeGenCall(ctx, "fibonacci");
    kiraCodeGenLoadLocal(ctx, 1);
    kiraCodeGenAdd(ctx);
    kiraCodeGenReturn(ctx);
    kiraCodeGenFunctionEnd(ctx);
    kiraCodeGenFunctionBegin(ctx, "main", 0, 0);
    kiraCodeGenLoadInt(ctx, 10);
    kiraCodeGenCall(ctx, "fibonacci");
    kiraCodeGenPrint(ctx);
    kiraCodeGenLoadInt(ctx, 0);
    kiraCodeGenReturn(ctx);
    kiraCodeGenFunctionEnd(ctx);
    kiraCodeGenEnd(ctx);
    kiraCodeGenCloseOutput(ctx);
    kiraCodeGenFree(ctx);
    printf("Generated fibonacci.asm\n");
}


Void generateNativeWithARC()
{
    KiraCodeGenContext* ctx = kiraCodeGenCreate(TARGET_X86_64);
    if(!kiraCodeGenOpenOutput(ctx, "arc_example.asm"))
    {
        printf("Failed to open output file\n");
        kiraCodeGenFree(ctx);
        return;
    }
    kiraCodeGenBegin(ctx);
    kiraCodeGenFunctionBegin(ctx, "create_object", 0, 1);
    kiraAsmComment(ctx, "Allocate a 64-byte object with type ID 1");
    kiraCodeGenAllocate(ctx, 64, 1);
    kiraCodeGenStoreLocal(ctx, 0);
    kiraAsmComment(ctx, "Use the object...");
    kiraCodeGenLoadLocal(ctx, 0);
    kiraAsmComment(ctx, "Retain the object (if we want to keep it)");
    kiraCodeGenRetain(ctx);
    kiraAsmComment(ctx, "Return the object");
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenReturn(ctx);
    kiraCodeGenFunctionEnd(ctx);
    kiraCodeGenFunctionBegin(ctx, "main", 0, 1);
    kiraCodeGenCall(ctx, "create_object");
    kiraCodeGenStoreLocal(ctx, 0);
    kiraAsmComment(ctx, "Release the object when done");
    kiraCodeGenLoadLocal(ctx, 0);
    kiraCodeGenRelease(ctx);
    kiraCodeGenLoadInt(ctx, 0);
    kiraCodeGenReturn(ctx);
    kiraCodeGenFunctionEnd(ctx);
    kiraCodeGenEnd(ctx);
    kiraCodeGenCloseOutput(ctx);
    kiraCodeGenFree(ctx);
    printf("Generated arc_example.asm\n");
}

Int32 main(Void)
{
    printf("=== Kira Native Code Generator ===\n\n");
    printf("Generating fibonacci example...\n");
    generateNativeFibonacci();
    printf("\nGenerating ARC example...\n");
    generateNativeWithARC();
    printf("\nTo assemble and link (Linux/Mac):\n");
    printf("  nasm -f elf64 fibonacci.asm -o fibonacci.o\n");
    printf("  gcc fibonacci.o -o fibonacci\n");
    printf("\nTo assemble and link (Windows):\n");
    printf("  nasm -f win64 fibonacci.asm -o fibonacci.obj\n");
    printf("  gcc fibonacci.obj -o fibonacci.exe\n");
    return 0;
}
