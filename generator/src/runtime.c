#include "kira_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifdef __AVX2__
#include <immintrin.h>
#define KIRA_SIMD_ENABLED 1
#endif

KiraValue kiraValueRef(Any ref)
{
    KiraValue v;
    v.type = KIRA_TYPE_REFERENCE;
    v.as.refValue = ref;
    return v;
}

KiraValue kiraBoxInt(KiraVM* vm, Int32 value)
{
    if(value >= KIRA_INT_CACHE_MIN && value <= KIRA_INT_CACHE_MAX)
    {
        Int32 cacheIdx = value - KIRA_INT_CACHE_MIN;
        if(vm->intCache[cacheIdx] != null)
        {
            return kiraValueRef(vm->intCache[cacheIdx]);
        }
    }

    KiraInt* iobj = (KiraInt*) kiraAllocateObject(vm, sizeof(KiraInt), KIRA_OBJ_INT);
    iobj->value = value;

    if(value >= KIRA_INT_CACHE_MIN && value <= KIRA_INT_CACHE_MAX)
    {
        Int32 cacheIdx = value - KIRA_INT_CACHE_MIN;
        vm->intCache[cacheIdx] = iobj;
    }

    return kiraValueRef(iobj);
}

Int32 kiraUnboxInt(KiraValue value)
{
    if(value.type != KIRA_TYPE_REFERENCE || value.as.refValue == null) return 0;
    KiraObject* o = (KiraObject*) value.as.refValue;
    if(o->type != KIRA_OBJ_INT) return 0;
    return ((KiraInt*) o)->value;
}KiraValue kiraBoxFloat(KiraVM* vm, Float32 value)
{
    KiraFloat* fobj = (KiraFloat*) kiraAllocateObject(vm, sizeof(KiraFloat), KIRA_OBJ_FLOAT);
    fobj->value = value;
    return kiraValueRef(fobj);
}

Float32 kiraUnboxFloat(KiraValue value)
{
    if(value.type != KIRA_TYPE_REFERENCE || value.as.refValue == null) return 0.0f;
    KiraObject* o = (KiraObject*) value.as.refValue;
    if(o->type != KIRA_OBJ_FLOAT) return 0.0f;
    return ((KiraFloat*) o)->value;
}

KiraValue kiraBoxBool(KiraVM* vm, Bool value)
{
    UInt8 idx = value ? 1 : 0;
    if(vm->boolPool[idx] != null)
    {
        return kiraValueRef(vm->boolPool[idx]);
    }

    KiraBool* bobj = (KiraBool*) kiraAllocateObject(vm, sizeof(KiraBool), KIRA_OBJ_BOOL);
    bobj->value = value ? 1 : 0;
    vm->boolPool[idx] = bobj;

    return kiraValueRef(bobj);
}

Bool kiraUnboxBool(KiraValue value)
{
    if(value.type != KIRA_TYPE_REFERENCE || value.as.refValue == null) return false;
    KiraObject* o = (KiraObject*) value.as.refValue;
    if(o->type != KIRA_OBJ_BOOL) return false;
    return ((KiraBool*) o)->value != 0;
}

simple Bool kiraValueEqualsInt(KiraValue a, KiraValue b)
{
    if(a.as.refValue == b.as.refValue) return true;
    if(a.as.refValue == null || b.as.refValue == null) return false;
    KiraObject* oa = (KiraObject*) a.as.refValue;
    KiraObject* ob = (KiraObject*) b.as.refValue;
    if(oa->type != KIRA_OBJ_INT || ob->type != KIRA_OBJ_INT) return false;
    return ((KiraInt*)oa)->value == ((KiraInt*)ob)->value;
}

Bool kiraValueEquals(KiraValue a, KiraValue b)
{
    if(a.type != b.type) return false;
    switch(a.type)
    {
        case KIRA_TYPE_REFERENCE:
            if(a.as.refValue == b.as.refValue) return true;
            if(a.as.refValue == null || b.as.refValue == null) return false;
            KiraObject* oa = (KiraObject*) a.as.refValue;
            KiraObject* ob = (KiraObject*) b.as.refValue;
            if(oa->type != ob->type) return false;
            if(oa->type == KIRA_OBJ_INT)
                return ((KiraInt*)oa)->value == ((KiraInt*)ob)->value;
            if(oa->type == KIRA_OBJ_FLOAT)
                return fabs(((KiraFloat*)oa)->value - ((KiraFloat*)ob)->value) < 0.0001f;
            if(oa->type == KIRA_OBJ_BOOL)
                return ((KiraBool*)oa)->value == ((KiraBool*)ob)->value;
            return false;
        case KIRA_TYPE_RETURNADDR:
            return a.as.returnAddr == b.as.returnAddr;
        default:
            return false;
    }
}

Void kiraValuePrint(KiraValue value)
{
    switch(value.type)
    {
        case KIRA_TYPE_REFERENCE:
            if(value.as.refValue == null)
            {
                printf("null");
            }
            else
            {
                KiraObject* obj = (KiraObject*) value.as.refValue;
                switch(obj->type)
                {
                    case KIRA_OBJ_INT:
                        printf("%d", ((KiraInt*)obj)->value);
                        break;
                    case KIRA_OBJ_FLOAT:
                        printf("%.6f", ((KiraFloat*)obj)->value);
                        break;
                    case KIRA_OBJ_BOOL:
                        printf("%s", ((KiraBool*)obj)->value ? "true" : "false");
                        break;
                    case KIRA_OBJ_STRING:
                        printf("%s", ((KiraString*)obj)->chars);
                        break;
                    case KIRA_OBJ_TUPLE:
                    {
                        KiraTuple* tuple = (KiraTuple*) obj;
                        printf("(");
                        for(UInt16 i = 0; i < tuple->arity; i++)
                        {
                            kiraValuePrint(tuple->elements[i]);
                            if(i < tuple->arity - 1)
                            {
                                printf(", ");
                            }
                        }
                        printf(")");
                        break;
                    }
                    case KIRA_OBJ_GENERIC:
                    {
                        KiraGenericInstance* gi = (KiraGenericInstance*) obj;
                        printf("%s<", gi->baseType->name);
                        for(UInt16 i = 0; i < gi->typeParamCount; i++)
                        {
                            printf("%s", gi->typeParams[i]->name);
                            if(i < gi->typeParamCount - 1)
                            {
                                printf(", ");
                            }
                        }
                        printf(">");
                        break;
                    }
                    default:
                        printf("<object@%p>", value.as.refValue);
                        break;
                }
            }
            break;
        case KIRA_TYPE_UNINITIALIZED:
            printf("<uninitialized>");
            break;
        default:
            printf("<unknown>");
            break;
    }
}

Void kiraPush(KiraCallFrame* frame, KiraValue value)
{
    if(value.type == KIRA_TYPE_REFERENCE && value.as.refValue != null)
    {
        KiraObject* obj = (KiraObject*) value.as.refValue;
        obj->refCount++;
    }
    *frame->stackTop = value;
    frame->stackTop++;
}

simple KiraValue kiraPopNoRelease(KiraCallFrame* frame)
{
    frame->stackTop--;
    return *frame->stackTop;
}

KiraValue kiraPop(KiraCallFrame* frame)
{
    frame->stackTop--;
    return *frame->stackTop;
}

KiraValue kiraPeek(KiraCallFrame* frame, Int32 distance)
{
    return frame->stackTop[-1 - distance];
}

simple Void kiraRetainFast(KiraObject* object)
{
    if(object != null) object->refCount++;
}

simple Void kiraReleaseFast(KiraVM* vm, KiraObject* object)
{
    if(object == null)
    {
        return;
    }
    if(--object->refCount == 0)
    {
        if(object->type == KIRA_OBJ_BOOL)
        {
            return;
        }
        if(object->type == KIRA_OBJ_INT || object->type == KIRA_OBJ_FLOAT)
        {
            if(vm->poolCount < KIRA_SMALL_OBJ_POOL_SIZE)
            {
                vm->smallObjPool[vm->poolCount++] = object;
                object->refCount = 1;
                return;
            }
        }
        if(object->type == KIRA_OBJ_ARRAY)
        {
            KiraArray* array = (KiraArray*) object;
            for(UInt32 i = 0; i < array->length; i++)
            {
                kiraReleaseValue(vm, array->elements[i]);
            }
        }
        else if(object->type == KIRA_OBJ_INSTANCE)
        {
            KiraInstance* instance = (KiraInstance*) object;
            for(UInt16 i = 0; i < instance->fieldCount; i++)
            {
                kiraReleaseValue(vm, instance->fields[i]);
            }
        }
        KiraObject** obj = &vm->objects;
        while(*obj != null)
        {
            if(*obj == object)
            {
                *obj = object->next;
                break;
            }
            obj = &(*obj)->next;
        }
        kiraFreeObject(object);
    }
}

KiraObject* kiraAllocateObject(KiraVM* vm, Size size, KiraObjectType type)
{
    if((type == KIRA_OBJ_INT || type == KIRA_OBJ_FLOAT) && vm->poolCount > 0)
    {
        KiraObject* object = vm->smallObjPool[--vm->poolCount];
        object->type = type;
        return object;
    }

    KiraObject* object = (KiraObject*) malloc(size);
    object->type = type;
    object->refCount = 1;
    object->next = vm->objects;
    vm->objects = object;
    vm->bytesAllocated += size;
    return object;
}

KiraArray* kiraAllocateArray(KiraVM* vm, UInt32 length)
{
    KiraArray* array = (KiraArray*) kiraAllocateObject(vm, sizeof(KiraArray), KIRA_OBJ_ARRAY);
    array->length = length;
    array->elements = (KiraValue*) calloc(length, sizeof(KiraValue));
    for(UInt32 i = 0; i < length; i++)
    {
        array->elements[i].type = KIRA_TYPE_UNINITIALIZED;
    }
    return array;
}

KiraString* kiraAllocateString(KiraVM* vm, String chars, UInt32 length)
{
    KiraString* string = (KiraString*) kiraAllocateObject(vm, sizeof(KiraString), KIRA_OBJ_STRING);
    string->length = length;
    string->chars = (String) malloc(length + 1);
    memcpy((Any) string->chars, chars, length);
    ((Int8*) string->chars)[length] = '\0';
    string->hash = 0;
    for(UInt32 i = 0; i < length; i++)
    {
        string->hash = string->hash * 31 + chars[i];
    }
    return string;
}

KiraInstance* kiraAllocateInstance(KiraVM* vm, UInt16 classIndex, UInt16 fieldCount)
{
    KiraInstance* instance = (KiraInstance*) kiraAllocateObject(vm, sizeof(KiraInstance), KIRA_OBJ_INSTANCE);
    instance->classIndex = classIndex;
    instance->typeInfo = kiraGetTypeInfo(vm, classIndex);
    instance->genericInstance = null;
    instance->fieldCount = fieldCount;
    instance->fields = (KiraValue*) calloc(fieldCount, sizeof(KiraValue));

    return instance;
}

Void kiraFreeObject(KiraObject* object)
{
    switch(object->type)
    {
        case KIRA_OBJ_INT:
        case KIRA_OBJ_FLOAT:
        case KIRA_OBJ_BOOL:
            free(object);
            break;
        case KIRA_OBJ_STRING:
        {
            KiraString* string = (KiraString*) object;
            free((Any) string->chars);
            free(string);
            break;
        }
        case KIRA_OBJ_ARRAY:
        {
            KiraArray* array = (KiraArray*) object;
            free(array->elements);
            free(array);
            break;
        }
        case KIRA_OBJ_INSTANCE:
        {
            KiraInstance* instance = (KiraInstance*) object;
            for(UInt16 i = 0; i < instance->fieldCount; i++)
            {
                kiraReleaseValue(null, instance->fields[i]);
            }
            free(instance->fields);
            free(instance);
            break;
        }
        case KIRA_OBJ_TYPE:
        {
            KiraTypeInfo* typeInfo = (KiraTypeInfo*) object;
            if(typeInfo->fields != null)
                free(typeInfo->fields);
            if(typeInfo->traitIds != null)
                free(typeInfo->traitIds);
            if(typeInfo->typeParamNames != null)
                free(typeInfo->typeParamNames);
            if(typeInfo->vtable != null)
            {
                if(typeInfo->vtable->methodIndices != null)
                    free(typeInfo->vtable->methodIndices);
                if(typeInfo->vtable->methods != null)
                    free(typeInfo->vtable->methods);
                free(typeInfo->vtable);
            }
            free(typeInfo);
            break;
        }
        case KIRA_OBJ_GENERIC:
        {
            KiraGenericInstance* instance = (KiraGenericInstance*) object;
            if(instance->baseType != null)
                kiraRelease(null, (KiraObject*) instance->baseType);
            if(instance->typeParams != null)
            {
                for(UInt16 i = 0; i < instance->typeParamCount; i++)
                {
                    if(instance->typeParams[i] != null)
                        kiraRelease(null, (KiraObject*) instance->typeParams[i]);
                }
                free(instance->typeParams);
            }
            free(instance);
            break;
        }
        case KIRA_OBJ_TUPLE:
        {
            KiraTuple* tuple = (KiraTuple*) object;
            if(tuple->elements != null)
            {
                for(UInt16 i = 0; i < tuple->arity; i++)
                {
                    kiraReleaseValue(null, tuple->elements[i]);
                }
                free(tuple->elements);
            }
            free(tuple);
            break;
        }
    }
}

Void kiraRetain(KiraObject* object)
{
    if(object == null)
    {
        return;
    }
    object->refCount++;
}

Void kiraRelease(KiraVM* vm, KiraObject* object)
{
    kiraReleaseFast(vm, object);
}

Void kiraReleaseValue(KiraVM* vm, KiraValue value)
{
    if(value.type == KIRA_TYPE_REFERENCE && value.as.refValue != null)
    {
        kiraRelease(vm, (KiraObject*) value.as.refValue);
    }
}

KiraVM* kiraVMCreate(KiraProgram* program)
{
    KiraVM* vm = (KiraVM*) calloc(1, sizeof(KiraVM));
    vm->program = program;
    vm->frameCount = 0;
    vm->objects = null;
    vm->bytesAllocated = 0;
    vm->halted = false;
    vm->exitCode = 0;
    vm->poolCount = 0;
    for(Int32 i = 0; i < 256; i++)
    {
        vm->globals[i].type = KIRA_TYPE_UNINITIALIZED;
    }
    for(Int32 i = 0; i < KIRA_INT_CACHE_SIZE; i++)
    {
        vm->intCache[i] = null;
    }
    for(Int32 i = 0; i < KIRA_BOOL_POOL_SIZE; i++)
    {
        vm->boolPool[i] = null;
    }
    for(Int32 i = 0; i < KIRA_SMALL_OBJ_POOL_SIZE; i++)
    {
        vm->smallObjPool[i] = null;
    }
    vm->typeRegistry = null;
    vm->typeCount = 0;
    vm->typeCapacity = 0;
    vm->inlineCaches = null;
    vm->inlineCacheCount = 0;
    vm->inlineCacheCapacity = 0;
    return vm;
}

Void kiraVMFree(KiraVM* vm)
{
    KiraObject* object = vm->objects;
    while(object != null)
    {
        KiraObject* next = object->next;
        object->refCount = 0;
        kiraFreeObject(object);
        object = next;
    }
    if(vm->typeRegistry != null)
    {
        free(vm->typeRegistry);
    }
    if(vm->inlineCaches != null)
    {
        free(vm->inlineCaches);
    }
    free(vm);
}

simple UInt8 readByte(KiraCallFrame* frame)
{
    return *frame->ip++;
}

simple UInt16 readShort(KiraCallFrame* frame)
{
    frame->ip += 2;
    return (UInt16)((frame->ip[-2] << 8) | frame->ip[-1]);
}

simple KiraConstant* readConstant(KiraVM* vm, KiraCallFrame* frame)
{
    UInt8 index = readByte(frame);
    return kiraConstantPoolGet(vm->program->constantPool, index);
}

simple KiraConstant* readConstantLong(KiraVM* vm, KiraCallFrame* frame)
{
    UInt16 index = readShort(frame);
    return kiraConstantPoolGet(vm->program->constantPool, index);
}

#define BINARY_OP_INT(op) \
    do { \
        KiraValue b = kiraPopNoRelease(frame); \
        KiraValue a = kiraPopNoRelease(frame); \
        Int32 av = kiraUnboxInt(a); \
        Int32 bv = kiraUnboxInt(b); \
        kiraReleaseFast(vm, (KiraObject*)a.as.refValue); \
        kiraReleaseFast(vm, (KiraObject*)b.as.refValue); \
        kiraPush(frame, kiraBoxInt(vm, av op bv)); \
    } while(false)

#define BINARY_OP_FLOAT(op) \
    do { \
        KiraValue b = kiraPopNoRelease(frame); \
        KiraValue a = kiraPopNoRelease(frame); \
        Float32 av = kiraUnboxFloat(a); \
        Float32 bv = kiraUnboxFloat(b); \
        kiraReleaseFast(vm, (KiraObject*)a.as.refValue); \
        kiraReleaseFast(vm, (KiraObject*)b.as.refValue); \
        kiraPush(frame, kiraBoxFloat(vm, av op bv)); \
    } while(false)

Void kiraVMRun(KiraVM* vm)
{
    if(vm->program->header.entryPoint >= vm->program->methodTable->count)
    {
        fprintf(stderr, "ERROR: Invalid entry point\n");
        return;
    }
    KiraMethodInfo* entryMethod = kiraMethodTableGet(vm->program->methodTable, vm->program->header.entryPoint);
    if(entryMethod == null)
    {
        fprintf(stderr, "ERROR: Entry method not found\n");
        return;
    }
    KiraCallFrame* frame = &vm->frames[vm->frameCount++];
    frame->method = entryMethod;
    frame->ip = vm->program->bytecode + entryMethod->codeOffset;
    frame->slots = (KiraValue*) calloc(entryMethod->maxLocals, sizeof(KiraValue));
    frame->stackTop = frame->stack;
    frame->returnAddress = 0;
    for(Int32 i = 0; i < entryMethod->maxLocals; i++)
    {
        frame->slots[i].type = KIRA_TYPE_UNINITIALIZED;
    }
    while(!vm->halted && frame->ip < vm->program->bytecode + vm->program->bytecodeLength)
    {
        UInt8 instruction = readByte(frame);
        switch(instruction)
        {
            case OP_NOP:
                break;
            case OP_CONST_NULL:
                kiraPush(frame, kiraValueRef(null));
                break;
            case OP_CONST_I32_M1:
                kiraPush(frame, kiraBoxInt(vm, -1));
                break;
            case OP_CONST_I32_0:
                kiraPush(frame, kiraBoxInt(vm, 0));
                break;
            case OP_CONST_I32_1:
                kiraPush(frame, kiraBoxInt(vm, 1));
                break;
            case OP_CONST_I32_2:
                kiraPush(frame, kiraBoxInt(vm, 2));
                break;
            case OP_CONST_I32_3:
                kiraPush(frame, kiraBoxInt(vm, 3));
                break;
            case OP_CONST_I32_4:
                kiraPush(frame, kiraBoxInt(vm, 4));
                break;
            case OP_CONST_I32_5:
                kiraPush(frame, kiraBoxInt(vm, 5));
                break;
            case OP_BIPUSH:
            {
                Int8 value = (Int8) readByte(frame);
                kiraPush(frame, kiraBoxInt(vm, value));
                break;
            }
            case OP_SIPUSH:
            {
                Int16 value = (Int16) readShort(frame);
                kiraPush(frame, kiraBoxInt(vm, value));
                break;
            }
            case OP_LDC:
            {
                KiraConstant* constant = readConstant(vm, frame);
                if(constant->type == CONST_INTEGER)
                {
                    kiraPush(frame, kiraBoxInt(vm, constant->data.intValue));
                }
                else if(constant->type == CONST_FLOAT)
                {
                    kiraPush(frame, kiraBoxFloat(vm, constant->data.floatValue));
                }
                break;
            }
            case OP_LDC_W:
            {
                KiraConstant* constant = readConstantLong(vm, frame);
                if(constant->type == CONST_INTEGER)
                {
                    kiraPush(frame, kiraBoxInt(vm, constant->data.intValue));
                }
                else if(constant->type == CONST_FLOAT)
                {
                    kiraPush(frame, kiraBoxFloat(vm, constant->data.floatValue));
                }
                break;
            }
            case OP_ILOAD:
            case OP_FLOAD:
            case OP_ALOAD:
            {
                UInt8 index = readByte(frame);
                kiraPush(frame, frame->slots[index]);
                break;
            }
            case OP_ILOAD_0: kiraPush(frame, frame->slots[0]); break;
            case OP_ILOAD_1: kiraPush(frame, frame->slots[1]); break;
            case OP_ILOAD_2: kiraPush(frame, frame->slots[2]); break;
            case OP_ILOAD_3: kiraPush(frame, frame->slots[3]); break;
            case OP_FLOAD_0: kiraPush(frame, frame->slots[0]); break;
            case OP_FLOAD_1: kiraPush(frame, frame->slots[1]); break;
            case OP_FLOAD_2: kiraPush(frame, frame->slots[2]); break;
            case OP_FLOAD_3: kiraPush(frame, frame->slots[3]); break;
            case OP_ALOAD_0: kiraPush(frame, frame->slots[0]); break;
            case OP_ALOAD_1: kiraPush(frame, frame->slots[1]); break;
            case OP_ALOAD_2: kiraPush(frame, frame->slots[2]); break;
            case OP_ALOAD_3: kiraPush(frame, frame->slots[3]); break;
            case OP_ISTORE:
            case OP_FSTORE:
            case OP_ASTORE:
            {
                UInt8 index = readByte(frame);
                KiraValue old = frame->slots[index];
                frame->slots[index] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ISTORE_0:
            {
                KiraValue old = frame->slots[0];
                frame->slots[0] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ISTORE_1:
            {
                KiraValue old = frame->slots[1];
                frame->slots[1] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ISTORE_2:
            {
                KiraValue old = frame->slots[2];
                frame->slots[2] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ISTORE_3:
            {
                KiraValue old = frame->slots[3];
                frame->slots[3] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_FSTORE_0:
            {
                KiraValue old = frame->slots[0];
                frame->slots[0] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_FSTORE_1:
            {
                KiraValue old = frame->slots[1];
                frame->slots[1] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_FSTORE_2:
            {
                KiraValue old = frame->slots[2];
                frame->slots[2] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_FSTORE_3:
            {
                KiraValue old = frame->slots[3];
                frame->slots[3] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ASTORE_0:
            {
                KiraValue old = frame->slots[0];
                frame->slots[0] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ASTORE_1:
            {
                KiraValue old = frame->slots[1];
                frame->slots[1] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ASTORE_2:
            {
                KiraValue old = frame->slots[2];
                frame->slots[2] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_ASTORE_3:
            {
                KiraValue old = frame->slots[3];
                frame->slots[3] = kiraPop(frame);
                kiraReleaseValue(vm, old);
                break;
            }
            case OP_POP:
            {
                KiraValue v = kiraPop(frame);
                kiraReleaseValue(vm, v);
                break;
            }
            case OP_POP2:
            {
                KiraValue v1 = kiraPop(frame);
                KiraValue v2 = kiraPop(frame);
                kiraReleaseValue(vm, v1);
                kiraReleaseValue(vm, v2);
                break;
            }
            case OP_DUP:
            {
                KiraValue value = kiraPeek(frame, 0);
                kiraPush(frame, value);
                break;
            }
            case OP_SWAP:
            {
                KiraValue a = kiraPop(frame);
                KiraValue b = kiraPop(frame);
                kiraPush(frame, a);
                kiraPush(frame, b);
                break;
            }
            case OP_IADD: BINARY_OP_INT(+); break;
            case OP_ISUB: BINARY_OP_INT(-); break;
            case OP_IMUL: BINARY_OP_INT(*); break;
            case OP_IDIV: BINARY_OP_INT(/); break;
            case OP_IREM: BINARY_OP_INT(%); break;
            case OP_INEG:
            {
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                kiraPush(frame, kiraBoxInt(vm, -v));
                break;
            }
            case OP_FADD: BINARY_OP_FLOAT(+); break;
            case OP_FSUB: BINARY_OP_FLOAT(-); break;
            case OP_FMUL: BINARY_OP_FLOAT(*); break;
            case OP_FDIV: BINARY_OP_FLOAT(/); break;
            case OP_FNEG:
            {
                KiraValue value = kiraPopNoRelease(frame);
                Float32 v = kiraUnboxFloat(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                kiraPush(frame, kiraBoxFloat(vm, -v));
                break;
            }
            case OP_ISHL: BINARY_OP_INT(<<); break;
            case OP_ISHR: BINARY_OP_INT(>>); break;
            case OP_IAND: BINARY_OP_INT(&); break;
            case OP_IOR: BINARY_OP_INT(|); break;
            case OP_IXOR: BINARY_OP_INT(^); break;
            case OP_IINC:
            {
                UInt8 index = readByte(frame);
                Int8 increment = (Int8) readByte(frame);
                Int32 v = kiraUnboxInt(frame->slots[index]);
                KiraValue old = frame->slots[index];
                frame->slots[index] = kiraBoxInt(vm, v + increment);
                kiraReleaseFast(vm, (KiraObject*)old.as.refValue);
                break;
            }
            case OP_I2F:
            {
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                kiraPush(frame, kiraBoxFloat(vm, (Float32)v));
                break;
            }
            case OP_F2I:
            {
                KiraValue value = kiraPopNoRelease(frame);
                Float32 v = kiraUnboxFloat(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                kiraPush(frame, kiraBoxInt(vm, (Int32)v));
                break;
            }
            case OP_I2B:
            {
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                kiraPush(frame, kiraBoxInt(vm, (Int8)v));
                break;
            }
            case OP_ICMP:
            {
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                Int32 result = (av > bv) ? 1 : (av < bv) ? -1 : 0;
                kiraPush(frame, kiraBoxInt(vm, result));
                break;
            }
            case OP_IFEQ:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v == 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFNE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v != 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFLT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v < 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFGE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v >= 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFGT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v > 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFLE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPopNoRelease(frame);
                Int32 v = kiraUnboxInt(value);
                kiraReleaseFast(vm, (KiraObject*)value.as.refValue);
                if(v <= 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPEQ:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av == bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPNE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av != bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPLT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av < bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPGE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av >= bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPGT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av > bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPLE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPopNoRelease(frame);
                KiraValue a = kiraPopNoRelease(frame);
                Int32 av = kiraUnboxInt(a);
                Int32 bv = kiraUnboxInt(b);
                kiraReleaseFast(vm, (KiraObject*)a.as.refValue);
                kiraReleaseFast(vm, (KiraObject*)b.as.refValue);
                if(av <= bv)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_GOTO:
            {
                Int16 offset = (Int16) readShort(frame);
                frame->ip += offset - 3;
                break;
            }
            case OP_INVOKE_STATIC:
            {
                UInt16 methodIndex = readShort(frame);
                KiraMethodInfo* method = kiraMethodTableGet(vm->program->methodTable, methodIndex);

                if(method == null || vm->frameCount >= KIRA_MAX_FRAMES)
                {
                    fprintf(stderr, "ERROR: Method invocation failed\n");
                    vm->halted = true;
                    break;
                }
                KiraCallFrame* newFrame = &vm->frames[vm->frameCount++];
                newFrame->method = method;
                newFrame->ip = vm->program->bytecode + method->codeOffset;
                newFrame->slots = (KiraValue*) calloc(method->maxLocals, sizeof(KiraValue));
                newFrame->stackTop = newFrame->stack;
                newFrame->returnAddress = frame->ip - vm->program->bytecode;
                for(Int32 i = method->paramCount - 1; i >= 0; i--)
                {
                    newFrame->slots[i] = kiraPop(frame);
                }
                frame = newFrame;
                break;
            }
            case OP_RETURN:
            case OP_IRETURN:
            case OP_FRETURN:
            case OP_ARETURN:
            {
                KiraValue returnValue;
                Bool hasReturn = (instruction != OP_RETURN);
                if(hasReturn)
                {
                    returnValue = kiraPop(frame);
                }
                for(Int32 i = 0; i < frame->method->maxLocals; i++)
                {
                    kiraReleaseValue(vm, frame->slots[i]);
                }
                free(frame->slots);
                vm->frameCount--;

                if(vm->frameCount == 0)
                {
                    vm->halted = true;
                    if(hasReturn && returnValue.type == KIRA_TYPE_REFERENCE && returnValue.as.refValue != null)
                    {
                        KiraObject* obj = (KiraObject*)returnValue.as.refValue;
                        if(obj->type == KIRA_OBJ_INT)
                        {
                            vm->exitCode = ((KiraInt*)obj)->value;
                        }
                    }
                    if(hasReturn)
                    {
                        kiraReleaseValue(vm, returnValue);
                    }
                    break;
                }
                frame = &vm->frames[vm->frameCount - 1];
                frame->ip = vm->program->bytecode + frame->returnAddress;

                if(hasReturn)
                {
                    kiraPush(frame, returnValue);
                }
                break;
            }
            case OP_NEWARRAY:
            {
                KiraValue length = kiraPop(frame);
                Int32 len = kiraUnboxInt(length);
                kiraReleaseValue(vm, length);
                KiraArray* array = kiraAllocateArray(vm, len);
                kiraPush(frame, kiraValueRef(array));
                break;
            }
            case OP_ARRAYLENGTH:
            {
                KiraValue arrayRef = kiraPop(frame);
                KiraArray* array = (KiraArray*) arrayRef.as.refValue;
                Int32 len = array->length;
                kiraReleaseValue(vm, arrayRef);
                kiraPush(frame, kiraBoxInt(vm, len));
                break;
            }
            case OP_IALOAD:
            case OP_FALOAD:
            case OP_AALOAD:
            {
                KiraValue index = kiraPop(frame);
                KiraValue arrayRef = kiraPop(frame);
                KiraArray* array = (KiraArray*) arrayRef.as.refValue;
                Int32 idx = kiraUnboxInt(index);
                kiraReleaseValue(vm, index);

                if(idx < 0 || (UInt32) idx >= array->length)
                {
                    fprintf(stderr, "ERROR: Array index out of bounds\n");
                    kiraReleaseValue(vm, arrayRef);
                    vm->halted = true;
                    break;
                }

                KiraValue element = array->elements[idx];
                kiraReleaseValue(vm, arrayRef);
                kiraPush(frame, element);
                break;
            }
            case OP_IASTORE:
            case OP_FASTORE:
            case OP_AASTORE:
            {
                KiraValue value = kiraPop(frame);
                KiraValue index = kiraPop(frame);
                KiraValue arrayRef = kiraPop(frame);
                KiraArray* array = (KiraArray*) arrayRef.as.refValue;
                Int32 idx = kiraUnboxInt(index);
                kiraReleaseValue(vm, index);

                if(idx < 0 || (UInt32) idx >= array->length)
                {
                    fprintf(stderr, "ERROR: Array index out of bounds\n");
                    kiraReleaseValue(vm, arrayRef);
                    kiraReleaseValue(vm, value);
                    vm->halted = true;
                    break;
                }

                KiraValue old = array->elements[idx];
                array->elements[idx] = value;
                kiraReleaseValue(vm, old);
                kiraReleaseValue(vm, arrayRef);
                break;
            }
            case OP_PRINT:
            {
                KiraValue value = kiraPop(frame);
                kiraValuePrint(value);
                printf("\n");
                break;
            }
            case OP_HALT:
                vm->halted = true;
                break;
            default:
                fprintf(stderr, "ERROR: Unknown opcode 0x%02X at offset %lld\n",
                       instruction, (long long)(frame->ip - vm->program->bytecode - 1));
                vm->halted = true;
                break;
        }
    }
    for(Int32 i = 0; i < vm->frameCount; i++)
    {
        free(vm->frames[i].slots);
    }
}

Void kiraProgramExecute(KiraProgram* program)
{
    if(program == null)
    {
        fprintf(stderr, "ERROR: Cannot execute null program\n");
        return;
    }
    KiraVM* vm = kiraVMCreate(program);
    kiraVMRun(vm);
    printf("\n=== Program exited with code %d ===\n", vm->exitCode);
    kiraVMFree(vm);
}

static UInt32 hashString(String str)
{
    UInt32 hash = 5381;
    while(*str)
    {
        hash = ((hash << 5) + hash) + *str;
        str++;
    }
    return hash;
}

KiraTypeInfo* kiraCreateTypeInfo(
    KiraVM* vm,
    String name,
    UInt16 typeId,
    UInt16 parentTypeId,
    UInt16 instanceSize,
    UInt16 fieldCount,
    UInt16 methodCount,
    UInt16 traitCount,
    UInt16 typeParamCount,
    Bool isAbstract,
    Bool isFinal,
    Bool isGeneric
)
{
    KiraTypeInfo* typeInfo = (KiraTypeInfo*) kiraAllocateObject(vm, sizeof(KiraTypeInfo), KIRA_OBJ_TYPE);
    typeInfo->name = name;
    typeInfo->nameHash = hashString(name);
    typeInfo->typeId = typeId;
    typeInfo->parentTypeId = parentTypeId;
    typeInfo->instanceSize = instanceSize;
    typeInfo->fieldCount = fieldCount;
    typeInfo->methodCount = methodCount;
    typeInfo->traitCount = traitCount;
    typeInfo->typeParamCount = typeParamCount;
    typeInfo->isAbstract = isAbstract;
    typeInfo->isFinal = isFinal;
    typeInfo->isGeneric = isGeneric;
    if(fieldCount > 0)
    {
        typeInfo->fields = (KiraFieldDescriptor*) calloc(fieldCount, sizeof(KiraFieldDescriptor));
    }
    else
    {
        typeInfo->fields = null;
    }
    if(traitCount > 0)
    {
        typeInfo->traitIds = (UInt16*) calloc(traitCount, sizeof(UInt16));
    }
    else
    {
        typeInfo->traitIds = null;
    }
    if(typeParamCount > 0)
    {
        typeInfo->typeParamNames = (String*) calloc(typeParamCount, sizeof(String));
    }
    else
    {
        typeInfo->typeParamNames = null;
    }
    typeInfo->vtable = null;
    return typeInfo;
}

KiraVTable* kiraCreateVTable(KiraVM* vm, UInt16 methodCount)
{
    KiraVTable* vtable = (KiraVTable*) malloc(sizeof(KiraVTable));
    vtable->methodCount = methodCount;
    vtable->methodIndices = (UInt16*) calloc(methodCount, sizeof(UInt16));
    vtable->methods = (KiraMethodDescriptor*) calloc(methodCount, sizeof(KiraMethodDescriptor));
    return vtable;
}

Void kiraRegisterType(KiraVM* vm, KiraTypeInfo* typeInfo)
{
    if(vm->typeCount >= vm->typeCapacity)
    {
        vm->typeCapacity = vm->typeCapacity == 0 ? 16 : vm->typeCapacity * 2;
        vm->typeRegistry = (KiraTypeInfo**) realloc(vm->typeRegistry, vm->typeCapacity * sizeof(KiraTypeInfo*));
    }
    vm->typeRegistry[vm->typeCount++] = typeInfo;
}

KiraTypeInfo* kiraGetTypeInfo(KiraVM* vm, UInt16 typeId)
{
    for(UInt16 i = 0; i < vm->typeCount; i++)
    {
        if(vm->typeRegistry[i]->typeId == typeId)
        {
            return vm->typeRegistry[i];
        }
    }
    return null;
}

Void kiraAddField(KiraTypeInfo* typeInfo, UInt16 fieldIndex, String name, UInt16 offset, UInt16 typeIndex, Bool isPublic, Bool isMutable)
{
    if(fieldIndex >= typeInfo->fieldCount)
    {
        return;
    }
    KiraFieldDescriptor* field = &typeInfo->fields[fieldIndex];
    field->name = name;
    field->nameHash = hashString(name);
    field->offset = offset;
    field->typeIndex = typeIndex;
    field->isPublic = isPublic;
    field->isMutable = isMutable;
}

Void kiraAddMethod(KiraVTable* vtable, UInt16 methodSlot, String name, UInt16 methodIndex, UInt16 paramCount, Bool isPublic, Bool isVirtual)
{
    if(methodSlot >= vtable->methodCount)
    {
        return;
    }
    vtable->methodIndices[methodSlot] = methodIndex;
    KiraMethodDescriptor* method = &vtable->methods[methodSlot];
    method->name = name;
    method->nameHash = hashString(name);
    method->methodIndex = methodIndex;
    method->paramCount = paramCount;
    method->isPublic = isPublic;
    method->isVirtual = isVirtual;
}

Int32 kiraLookupMethod(KiraVM* vm, KiraTypeInfo* typeInfo, UInt32 nameHash)
{
    KiraTypeInfo* current = typeInfo;
    while(current != null)
    {
        if(current->vtable != null)
        {
            for(UInt16 i = 0; i < current->vtable->methodCount; i++)
            {
                if(current->vtable->methods[i].nameHash == nameHash)
                    return current->vtable->methods[i].methodIndex;
            }
        }
        if(current->parentTypeId == 0xFFFF)
        {
            break;
        }
        current = kiraGetTypeInfo(vm, current->parentTypeId);
    }
    return -1;
}

Int32 kiraLookupMethodCached(KiraVM* vm, KiraTypeInfo* typeInfo, UInt32 nameHash, UInt16 callSiteId)
{
    if(callSiteId >= vm->inlineCacheCount)
    {
        return kiraLookupMethod(vm, typeInfo, nameHash);
    }
    KiraInlineCache* cache = &vm->inlineCaches[callSiteId * KIRA_INLINE_CACHE_SIZE];
    for(UInt16 i = 0; i < KIRA_INLINE_CACHE_SIZE; i++)
    {
        if(cache[i].typeId == typeInfo->typeId && cache[i].methodIndex != 0xFFFF)
        {
            cache[i].hitCount++;
            return cache[i].methodIndex;
        }
    }
    Int32 methodIndex = kiraLookupMethod(vm, typeInfo, nameHash);
    if(methodIndex >= 0)
    {
        UInt16 minHitIdx = 0;
        UInt32 minHits = cache[0].hitCount;
        for(UInt16 i = 1; i < KIRA_INLINE_CACHE_SIZE; i++)
        {
            if(cache[i].hitCount < minHits)
            {
                minHits = cache[i].hitCount;
                minHitIdx = i;
            }
        }
        cache[minHitIdx].typeId = typeInfo->typeId;
        cache[minHitIdx].methodIndex = (UInt16) methodIndex;
        cache[minHitIdx].hitCount = 1;
    }
    return methodIndex;
}

Int32 kiraLookupField(KiraTypeInfo* typeInfo, UInt32 nameHash)
{
    for(UInt16 i = 0; i < typeInfo->fieldCount; i++)
    {
        if(typeInfo->fields[i].nameHash == nameHash)
        {
            return typeInfo->fields[i].offset;
        }
    }
    return -1;
}

Bool kiraIsSubtype(KiraVM* vm, KiraTypeInfo* subtype, UInt16 supertypeId)
{
    KiraTypeInfo* current = subtype;
    while(current != null)
    {
        if(current->typeId == supertypeId)
        {
            return true;
        }
        if(current->parentTypeId == 0xFFFF)
        {
            break;
        }
        current = kiraGetTypeInfo(vm, current->parentTypeId);
    }
    return false;
}

Bool kiraImplementsTrait(KiraTypeInfo* typeInfo, UInt16 traitId)
{
    for(UInt16 i = 0; i < typeInfo->traitCount; i++)
    {
        if(typeInfo->traitIds[i] == traitId)
        {
            return true;
        }
    }
    return false;
}

KiraGenericInstance* kiraCreateGenericInstance(KiraVM* vm, KiraTypeInfo* baseType, UInt16 typeParamCount, KiraTypeInfo** typeParams)
{
    KiraGenericInstance* instance = (KiraGenericInstance*) kiraAllocateObject(vm, sizeof(KiraGenericInstance), KIRA_OBJ_GENERIC);
    instance->baseType = baseType;
    instance->typeParamCount = typeParamCount;
    if(typeParamCount > 0)
    {
        instance->typeParams = (KiraTypeInfo**) malloc(typeParamCount * sizeof(KiraTypeInfo*));
        for(UInt16 i = 0; i < typeParamCount; i++)
        {
            instance->typeParams[i] = typeParams[i];
            kiraRetain((KiraObject*) typeParams[i]);
        }
    }
    else
    {
        instance->typeParams = null;
    }
    kiraRetain((KiraObject*) baseType);
    return instance;
}

Void kiraAddTypeParameter(KiraTypeInfo* typeInfo, UInt16 index, String name)
{
    if(index >= typeInfo->typeParamCount)
    {
        return;
    }
    typeInfo->typeParamNames[index] = name;
}

KiraTypeInfo* kiraGetTypeParameter(KiraGenericInstance* instance, UInt16 index)
{
    if(index >= instance->typeParamCount)
    {
        return null;
    }
    return instance->typeParams[index];
}

KiraTuple* kiraCreateTuple(KiraVM* vm, UInt16 arity)
{
    KiraTuple* tuple = (KiraTuple*) kiraAllocateObject(vm, sizeof(KiraTuple), KIRA_OBJ_TUPLE);
    tuple->arity = arity;
    if(arity > 0)
    {
        tuple->elements = (KiraValue*) calloc(arity, sizeof(KiraValue));
    }
    else
    {
        tuple->elements = null;
    }
    return tuple;
}

Void kiraSetTupleElement(KiraTuple* tuple, UInt16 index, KiraValue value)
{
    if(index >= tuple->arity)
    {
        return;
    }
    kiraReleaseValue(null, tuple->elements[index]);
    tuple->elements[index] = value;
    if(value.type == KIRA_TYPE_REFERENCE && value.as.refValue != null)
    {
        kiraRetain((KiraObject*) value.as.refValue);
    }
}

KiraValue kiraGetTupleElement(KiraTuple* tuple, UInt16 index)
{
    if(index >= tuple->arity)
    {
        KiraValue nil;
        nil.type = KIRA_TYPE_UNINITIALIZED;
        return nil;
    }
    return tuple->elements[index];
}

