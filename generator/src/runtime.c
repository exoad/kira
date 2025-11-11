#include "kira_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

KiraValue kiraValueInt(Int32 value)
{
    KiraValue v;
    v.type = KIRA_TYPE_INT;
    v.as.intValue = value;
    return v;
}

KiraValue kiraValueFloat(Float32 value)
{
    KiraValue v;
    v.type = KIRA_TYPE_FLOAT;
    v.as.floatValue = value;
    return v;
}

KiraValue kiraValueRef(Void* ref)
{
    KiraValue v;
    v.type = KIRA_TYPE_REFERENCE;
    v.as.refValue = ref;
    return v;
}

Bool kiraValueEquals(KiraValue a, KiraValue b)
{
    if(a.type != b.type) return false;

    switch(a.type)
    {
        case KIRA_TYPE_INT:
            return a.as.intValue == b.as.intValue;
        case KIRA_TYPE_FLOAT:
            return fabs(a.as.floatValue - b.as.floatValue) < 0.0001f;
        case KIRA_TYPE_REFERENCE:
            return a.as.refValue == b.as.refValue;
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
        case KIRA_TYPE_INT:
            printf("%d", value.as.intValue);
            break;
        case KIRA_TYPE_FLOAT:
            printf("%.6f", value.as.floatValue);
            break;
        case KIRA_TYPE_REFERENCE:
            if(value.as.refValue == null)
            {
                printf("null");
            }
            else
            {
                KiraObject* obj = (KiraObject*) value.as.refValue;
                if(obj->type == KIRA_OBJ_STRING)
                {
                    KiraString* str = (KiraString*) obj;
                    printf("%s", str->chars);
                }
                else
                {
                    printf("<object@%p>", value.as.refValue);
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
    *frame->stackTop = value;
    frame->stackTop++;
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

KiraObject* kiraAllocateObject(KiraVM* vm, Size size, KiraObjectType type)
{
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
    memcpy((Void*) string->chars, chars, length);
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
    instance->fieldCount = fieldCount;
    instance->fields = (KiraValue*) calloc(fieldCount, sizeof(KiraValue));

    return instance;
}

Void kiraFreeObject(KiraObject* object)
{
    switch(object->type)
    {
        case KIRA_OBJ_STRING:
        {
            KiraString* string = (KiraString*) object;
            free((Void*) string->chars);
            free(string);
            break;
        }
        case KIRA_OBJ_ARRAY:
        {
            KiraArray* array = (KiraArray*) object;

            for(UInt32 i = 0; i < array->length; i++)
            {
                if(array->elements[i].type == KIRA_TYPE_REFERENCE &&
                   array->elements[i].as.refValue != null)
                {


                }
            }
            free(array->elements);
            free(array);
            break;
        }
        case KIRA_OBJ_INSTANCE:
        {
            KiraInstance* instance = (KiraInstance*) object;
            free(instance->fields);
            free(instance);
            break;
        }
    }
}





Void kiraRetain(KiraObject* object)
{
    if(object == null) return;
    object->refCount++;
}

Void kiraRelease(KiraVM* vm, KiraObject* object)
{
    if(object == null) return;

    object->refCount--;


    if(object->refCount == 0)
    {

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
    for(Int32 i = 0; i < 256; i++)
    {
        vm->globals[i].type = KIRA_TYPE_UNINITIALIZED;
    }

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

    free(vm);
}

static inline UInt8 readByte(KiraCallFrame* frame)
{
    return *frame->ip++;
}

static inline UInt16 readShort(KiraCallFrame* frame)
{
    frame->ip += 2;
    return (UInt16)((frame->ip[-2] << 8) | frame->ip[-1]);
}

static inline KiraConstant* readConstant(KiraVM* vm, KiraCallFrame* frame)
{
    UInt8 index = readByte(frame);
    return kiraConstantPoolGet(vm->program->constantPool, index);
}

static inline KiraConstant* readConstantLong(KiraVM* vm, KiraCallFrame* frame)
{
    UInt16 index = readShort(frame);
    return kiraConstantPoolGet(vm->program->constantPool, index);
}

#define BINARY_OP(valueType, op) \
    do { \
        KiraValue b = kiraPop(frame); \
        KiraValue a = kiraPop(frame); \
        kiraPush(frame, valueType(a.as.intValue op b.as.intValue)); \
    } while(false)

#define BINARY_OP_FLOAT(op) \
    do { \
        KiraValue b = kiraPop(frame); \
        KiraValue a = kiraPop(frame); \
        kiraPush(frame, kiraValueFloat(a.as.floatValue op b.as.floatValue)); \
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
                kiraPush(frame, kiraValueInt(-1));
                break;
            case OP_CONST_I32_0:
                kiraPush(frame, kiraValueInt(0));
                break;
            case OP_CONST_I32_1:
                kiraPush(frame, kiraValueInt(1));
                break;
            case OP_CONST_I32_2:
                kiraPush(frame, kiraValueInt(2));
                break;
            case OP_CONST_I32_3:
                kiraPush(frame, kiraValueInt(3));
                break;
            case OP_CONST_I32_4:
                kiraPush(frame, kiraValueInt(4));
                break;
            case OP_CONST_I32_5:
                kiraPush(frame, kiraValueInt(5));
                break;
            case OP_BIPUSH:
            {
                Int8 value = (Int8) readByte(frame);
                kiraPush(frame, kiraValueInt(value));
                break;
            }
            case OP_SIPUSH:
            {
                Int16 value = (Int16) readShort(frame);
                kiraPush(frame, kiraValueInt(value));
                break;
            }
            case OP_LDC:
            {
                KiraConstant* constant = readConstant(vm, frame);
                if(constant->type == CONST_INTEGER)
                {
                    kiraPush(frame, kiraValueInt(constant->data.intValue));
                }
                else if(constant->type == CONST_FLOAT)
                {
                    kiraPush(frame, kiraValueFloat(constant->data.floatValue));
                }
                break;
            }
            case OP_LDC_W:
            {
                KiraConstant* constant = readConstantLong(vm, frame);
                if(constant->type == CONST_INTEGER)
                {
                    kiraPush(frame, kiraValueInt(constant->data.intValue));
                }
                else if(constant->type == CONST_FLOAT)
                {
                    kiraPush(frame, kiraValueFloat(constant->data.floatValue));
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
                frame->slots[index] = kiraPop(frame);
                break;
            }
            case OP_ISTORE_0: frame->slots[0] = kiraPop(frame); break;
            case OP_ISTORE_1: frame->slots[1] = kiraPop(frame); break;
            case OP_ISTORE_2: frame->slots[2] = kiraPop(frame); break;
            case OP_ISTORE_3: frame->slots[3] = kiraPop(frame); break;
            case OP_FSTORE_0: frame->slots[0] = kiraPop(frame); break;
            case OP_FSTORE_1: frame->slots[1] = kiraPop(frame); break;
            case OP_FSTORE_2: frame->slots[2] = kiraPop(frame); break;
            case OP_FSTORE_3: frame->slots[3] = kiraPop(frame); break;
            case OP_ASTORE_0: frame->slots[0] = kiraPop(frame); break;
            case OP_ASTORE_1: frame->slots[1] = kiraPop(frame); break;
            case OP_ASTORE_2: frame->slots[2] = kiraPop(frame); break;
            case OP_ASTORE_3: frame->slots[3] = kiraPop(frame); break;
            case OP_POP:
                kiraPop(frame);
                break;
            case OP_POP2:
                kiraPop(frame);
                kiraPop(frame);
                break;
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
            case OP_IADD: BINARY_OP(kiraValueInt, +); break;
            case OP_ISUB: BINARY_OP(kiraValueInt, -); break;
            case OP_IMUL: BINARY_OP(kiraValueInt, *); break;
            case OP_IDIV: BINARY_OP(kiraValueInt, /); break;
            case OP_IREM: BINARY_OP(kiraValueInt, %); break;
            case OP_INEG:
            {
                KiraValue value = kiraPop(frame);
                kiraPush(frame, kiraValueInt(-value.as.intValue));
                break;
            }
            case OP_FADD: BINARY_OP_FLOAT(+); break;
            case OP_FSUB: BINARY_OP_FLOAT(-); break;
            case OP_FMUL: BINARY_OP_FLOAT(*); break;
            case OP_FDIV: BINARY_OP_FLOAT(/); break;
            case OP_FNEG:
            {
                KiraValue value = kiraPop(frame);
                kiraPush(frame, kiraValueFloat(-value.as.floatValue));
                break;
            }
            case OP_ISHL: BINARY_OP(kiraValueInt, <<); break;
            case OP_ISHR: BINARY_OP(kiraValueInt, >>); break;
            case OP_IAND: BINARY_OP(kiraValueInt, &); break;
            case OP_IOR: BINARY_OP(kiraValueInt, |); break;
            case OP_IXOR: BINARY_OP(kiraValueInt, ^); break;
            case OP_IINC:
            {
                UInt8 index = readByte(frame);
                Int8 increment = (Int8) readByte(frame);
                frame->slots[index].as.intValue += increment;
                break;
            }
            case OP_I2F:
            {
                KiraValue value = kiraPop(frame);
                kiraPush(frame, kiraValueFloat((Float32) value.as.intValue));
                break;
            }
            case OP_F2I:
            {
                KiraValue value = kiraPop(frame);
                kiraPush(frame, kiraValueInt((Int32) value.as.floatValue));
                break;
            }
            case OP_I2B:
            {
                KiraValue value = kiraPop(frame);
                kiraPush(frame, kiraValueInt((Int8) value.as.intValue));
                break;
            }
            case OP_ICMP:
            {
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                Int32 result = (a.as.intValue > b.as.intValue) ? 1 :
                              (a.as.intValue < b.as.intValue) ? -1 : 0;
                kiraPush(frame, kiraValueInt(result));
                break;
            }
            case OP_IFEQ:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue == 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFNE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue != 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFLT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue < 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFGE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue >= 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFGT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue > 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IFLE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue value = kiraPop(frame);
                if(value.as.intValue <= 0)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPEQ:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue == b.as.intValue)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPNE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue != b.as.intValue)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPLT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue < b.as.intValue)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPGE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue >= b.as.intValue)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPGT:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue > b.as.intValue)
                {
                    frame->ip += offset - 3;
                }
                break;
            }
            case OP_IF_ICMPLE:
            {
                Int16 offset = (Int16) readShort(frame);
                KiraValue b = kiraPop(frame);
                KiraValue a = kiraPop(frame);
                if(a.as.intValue <= b.as.intValue)
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


                free(frame->slots);
                vm->frameCount--;

                if(vm->frameCount == 0)
                {

                    vm->halted = true;
                    if(hasReturn && returnValue.type == KIRA_TYPE_INT)
                    {
                        vm->exitCode = returnValue.as.intValue;
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
                KiraArray* array = kiraAllocateArray(vm, length.as.intValue);
                kiraPush(frame, kiraValueRef(array));
                break;
            }
            case OP_ARRAYLENGTH:
            {
                KiraValue arrayRef = kiraPop(frame);
                KiraArray* array = (KiraArray*) arrayRef.as.refValue;
                kiraPush(frame, kiraValueInt(array->length));
                break;
            }
            case OP_IALOAD:
            case OP_FALOAD:
            case OP_AALOAD:
            {
                KiraValue index = kiraPop(frame);
                KiraValue arrayRef = kiraPop(frame);
                KiraArray* array = (KiraArray*) arrayRef.as.refValue;

                if(index.as.intValue < 0 || (UInt32) index.as.intValue >= array->length)
                {
                    fprintf(stderr, "ERROR: Array index out of bounds\n");
                    vm->halted = true;
                    break;
                }

                kiraPush(frame, array->elements[index.as.intValue]);
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

                if(index.as.intValue < 0 || (UInt32) index.as.intValue >= array->length)
                {
                    fprintf(stderr, "ERROR: Array index out of bounds\n");
                    vm->halted = true;
                    break;
                }

                array->elements[index.as.intValue] = value;
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
