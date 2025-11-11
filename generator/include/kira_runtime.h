#ifndef KIRA_VM_H
#define KIRA_VM_H

#include "kira_ir.h"

typedef enum
{
    KIRA_TYPE_REFERENCE = 0,
    KIRA_TYPE_RETURNADDR = 1,
    KIRA_TYPE_UNINITIALIZED = 2
} KiraValueType;

typedef struct
{
    KiraValueType type;
    union
    {
        Int32 intValue;
        Float32 floatValue;
        Any refValue;
        UInt32 returnAddr;
    } as;
} KiraValue;

typedef enum
{
    KIRA_OBJ_STRING = 0,
    KIRA_OBJ_ARRAY = 1,
    KIRA_OBJ_INSTANCE = 2,
    KIRA_OBJ_INT = 3,
    KIRA_OBJ_FLOAT = 4,
    KIRA_OBJ_BOOL = 5,
    KIRA_OBJ_TYPE = 6,
    KIRA_OBJ_GENERIC = 7,
    KIRA_OBJ_TUPLE = 8
} KiraObjectType;

typedef struct sKiraObject
{
    KiraObjectType type;
    UInt32 refCount;
    struct sKiraObject* next;
} KiraObject;

typedef struct sKiraTypeInfo KiraTypeInfo;
typedef struct sKiraVTable KiraVTable;
typedef struct sKiraFieldDescriptor KiraFieldDescriptor;
typedef struct sKiraMethodDescriptor KiraMethodDescriptor;
typedef struct sKiraInlineCache KiraInlineCache;

struct sKiraFieldDescriptor
{
    String name;
    UInt32 nameHash;
    UInt16 offset;
    UInt16 typeIndex;
    Bool isPublic;
    Bool isMutable;
};

struct sKiraMethodDescriptor
{
    String name;
    UInt32 nameHash;
    UInt16 methodIndex;
    UInt16 paramCount;
    Bool isPublic;
    Bool isMutable;
    Bool isVirtual;
};

struct sKiraVTable
{
    UInt16 methodCount;
    UInt16* methodIndices;
    KiraMethodDescriptor* methods;
};

struct sKiraTypeInfo
{
    KiraObject obj;
    String name;
    UInt32 nameHash;
    UInt16 typeId;
    UInt16 parentTypeId;
    UInt16 instanceSize;
    UInt16 fieldCount;
    UInt16 methodCount;
    UInt16 traitCount;
    UInt16 typeParamCount;
    KiraFieldDescriptor* fields;
    KiraVTable* vtable;
    UInt16* traitIds;
    String* typeParamNames;
    Bool isAbstract;
    Bool isFinal;
    Bool isGeneric;
};

struct sKiraInlineCache
{
    UInt16 typeId;
    UInt16 methodIndex;
    UInt32 hitCount;
};

#define KIRA_INLINE_CACHE_SIZE 4

typedef struct
{
    KiraObject obj;
    KiraTypeInfo* baseType;
    UInt16 typeParamCount;
    KiraTypeInfo** typeParams;
} KiraGenericInstance;

typedef struct
{
    KiraObject obj;
    UInt16 arity;
    KiraValue* elements;
} KiraTuple;

typedef struct
{
    KiraObject obj;
    UInt32 length;
    KiraValue* elements;
} KiraArray;

typedef struct
{
    KiraObject obj;
    Int32 value;
} KiraInt;

typedef struct
{
    KiraObject obj;
    Float32 value;
} KiraFloat;

typedef struct
{
    KiraObject obj;
    UInt8 value;
} KiraBool;

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
    KiraTypeInfo* typeInfo;
    KiraGenericInstance* genericInstance;
    KiraValue* fields;
    UInt16 fieldCount;
} KiraInstance;

#define KIRA_MAX_LOCALS 256
#define KIRA_MAX_STACK 256
#define KIRA_INT_CACHE_MIN -128
#define KIRA_INT_CACHE_MAX 127
#define KIRA_INT_CACHE_SIZE ((KIRA_INT_CACHE_MAX - KIRA_INT_CACHE_MIN) + 1)
#define KIRA_SMALL_OBJ_POOL_SIZE 256
#define KIRA_BOOL_POOL_SIZE 2

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
    KiraInt* intCache[KIRA_INT_CACHE_SIZE];
    KiraBool* boolPool[KIRA_BOOL_POOL_SIZE];
    KiraObject* smallObjPool[KIRA_SMALL_OBJ_POOL_SIZE];
    UInt32 poolCount;
    KiraTypeInfo** typeRegistry;
    UInt16 typeCount;
    UInt16 typeCapacity;
    KiraInlineCache* inlineCaches;
    UInt32 inlineCacheCount;
    UInt32 inlineCacheCapacity;
} KiraVM;

KiraVM* kiraVMCreate(KiraProgram* program);
Void kiraVMFree(KiraVM* vm);
Void kiraVMRun(KiraVM* vm);

KiraValue kiraValueInt(Int32 value);
KiraValue kiraValueFloat(Float32 value);
KiraValue kiraValueRef(Any ref);
Bool kiraValueEquals(KiraValue a, KiraValue b);
Void kiraValuePrint(KiraValue value);

Void kiraPush(KiraCallFrame* frame, KiraValue value);
KiraValue kiraPop(KiraCallFrame* frame);
KiraValue kiraPeek(KiraCallFrame* frame, Int32 distance);

KiraValue kiraBoxInt(KiraVM* vm, Int32 value);
Int32 kiraUnboxInt(KiraValue value);
KiraValue kiraBoxFloat(KiraVM* vm, Float32 value);
Float32 kiraUnboxFloat(KiraValue value);
KiraValue kiraBoxBool(KiraVM* vm, Bool value);
Bool kiraUnboxBool(KiraValue value);

KiraObject* kiraAllocateObject(KiraVM* vm, Size size, KiraObjectType type);
KiraArray* kiraAllocateArray(KiraVM* vm, UInt32 length);
KiraString* kiraAllocateString(KiraVM* vm, String chars, UInt32 length);
KiraInstance* kiraAllocateInstance(KiraVM* vm, UInt16 classIndex, UInt16 fieldCount);
Void kiraFreeObject(KiraObject* object);

Void kiraRetain(KiraObject* object);
Void kiraRelease(KiraVM* vm, KiraObject* object);
Void kiraReleaseValue(KiraVM* vm, KiraValue value);

KiraTypeInfo* kiraCreateTypeInfo(KiraVM* vm, String name, UInt16 typeId, UInt16 parentTypeId, UInt16 instanceSize, UInt16 fieldCount, UInt16 methodCount, UInt16 traitCount, UInt16 typeParamCount, Bool isAbstract, Bool isFinal, Bool isGeneric);
KiraVTable* kiraCreateVTable(KiraVM* vm, UInt16 methodCount);
Void kiraRegisterType(KiraVM* vm, KiraTypeInfo* typeInfo);
KiraTypeInfo* kiraGetTypeInfo(KiraVM* vm, UInt16 typeId);
Void kiraAddField(KiraTypeInfo* typeInfo, UInt16 fieldIndex, String name, UInt16 offset, UInt16 typeIndex, Bool isPublic, Bool isMutable);
Void kiraAddMethod(KiraVTable* vtable, UInt16 methodSlot, String name, UInt16 methodIndex, UInt16 paramCount, Bool isPublic, Bool isVirtual);
Int32 kiraLookupMethod(KiraVM* vm, KiraTypeInfo* typeInfo, UInt32 nameHash);
Int32 kiraLookupMethodCached(KiraVM* vm, KiraTypeInfo* typeInfo, UInt32 nameHash, UInt16 callSiteId);
Int32 kiraLookupField(KiraTypeInfo* typeInfo, UInt32 nameHash);
Bool kiraIsSubtype(KiraVM* vm, KiraTypeInfo* subtype, UInt16 supertypeId);
Bool kiraImplementsTrait(KiraTypeInfo* typeInfo, UInt16 traitId);

KiraGenericInstance* kiraCreateGenericInstance(KiraVM* vm, KiraTypeInfo* baseType, UInt16 typeParamCount, KiraTypeInfo** typeParams);
Void kiraAddTypeParameter(KiraTypeInfo* typeInfo, UInt16 index, String name);
KiraTypeInfo* kiraGetTypeParameter(KiraGenericInstance* instance, UInt16 index);

KiraTuple* kiraCreateTuple(KiraVM* vm, UInt16 arity);
Void kiraSetTupleElement(KiraTuple* tuple, UInt16 index, KiraValue value);
KiraValue kiraGetTupleElement(KiraTuple* tuple, UInt16 index);

#endif