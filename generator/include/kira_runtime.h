#ifndef KIRA_VM_H
#define KIRA_VM_H

#include "kira_ir.h"

typedef enum
{
    KIRA_TYPE_INT = 0,
    KIRA_TYPE_FLOAT = 1,
    KIRA_TYPE_REFERENCE = 2,
    KIRA_TYPE_RETURNADDR = 3,
    KIRA_TYPE_UNINITIALIZED = 4
} KiraValueType;

typedef struct
{
    KiraValueType type;
    union
    {
        Int32 intValue;
        Float32 floatValue;
        Void* refValue;
        UInt32 returnAddr;
    } as;
} KiraValue;

typedef enum
{
    KIRA_OBJ_STRING = 0,
    KIRA_OBJ_ARRAY = 1,
    KIRA_OBJ_INSTANCE = 2,
} KiraObjectType;

typedef struct sKiraObject
{
    KiraObjectType type;
    UInt32 refCount;
    struct sKiraObject* next;
} KiraObject;

typedef struct
{
    KiraObject obj;
    UInt32 length;
    KiraValue* elements;
} KiraArray;

typedef struct
{
    KiraObject obj;
    String chars;
    UInt32 length;
    UInt32 hash;
} KiraString;

typedef struct
{
    KiraObject obj;
    UInt16 classIndex;
    KiraValue* fields;
    UInt16 fieldCount;
} KiraInstance;

#define KIRA_MAX_LOCALS 256
#define KIRA_MAX_STACK 256

typedef struct
{
    UInt8* ip;
    KiraValue* slots;
    KiraValue* stackTop;
    KiraValue stack[KIRA_MAX_STACK];
    KiraMethodInfo* method;
    UInt32 returnAddress;
} KiraCallFrame;

#define KIRA_MAX_FRAMES 64

typedef struct
{
    KiraProgram* program;
    KiraCallFrame frames[KIRA_MAX_FRAMES];
    Int32 frameCount;
    KiraObject* objects;
    UInt32 bytesAllocated;
    KiraValue globals[256];
    Bool halted;
    Int32 exitCode;
} KiraVM;

KiraVM* kiraVMCreate(KiraProgram* program);
Void kiraVMFree(KiraVM* vm);
Void kiraVMRun(KiraVM* vm);

KiraValue kiraValueInt(Int32 value);
KiraValue kiraValueFloat(Float32 value);
KiraValue kiraValueRef(Void* ref);
Bool kiraValueEquals(KiraValue a, KiraValue b);
Void kiraValuePrint(KiraValue value);

Void kiraPush(KiraCallFrame* frame, KiraValue value);
KiraValue kiraPop(KiraCallFrame* frame);
KiraValue kiraPeek(KiraCallFrame* frame, Int32 distance);

KiraObject* kiraAllocateObject(KiraVM* vm, Size size, KiraObjectType type);
KiraArray* kiraAllocateArray(KiraVM* vm, UInt32 length);
KiraString* kiraAllocateString(KiraVM* vm, String chars, UInt32 length);
KiraInstance* kiraAllocateInstance(KiraVM* vm, UInt16 classIndex, UInt16 fieldCount);
Void kiraFreeObject(KiraObject* object);

Void kiraRetain(KiraObject* object);
Void kiraRelease(KiraVM* vm, KiraObject* object);
Void kiraReleaseValue(KiraVM* vm, KiraValue value);

#endif