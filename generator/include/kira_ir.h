#ifndef KIRA_H
#define KIRA_H

#include "kira_shared.h"

typedef enum
{
    OP_NOP = 0x00,
    OP_CONST_NULL = 0x01,
    OP_CONST_I32_M1 = 0x02,
    OP_CONST_I32_0 = 0x03,
    OP_CONST_I32_1 = 0x04,
    OP_CONST_I32_2 = 0x05,
    OP_CONST_I32_3 = 0x06,
    OP_CONST_I32_4 = 0x07,
    OP_CONST_I32_5 = 0x08,
    OP_BIPUSH = 0x09,
    OP_SIPUSH = 0x0A,
    OP_LDC = 0x0B,
    OP_LDC_W = 0x0C,
    OP_ILOAD = 0x10,
    OP_LLOAD = 0x11,
    OP_FLOAD = 0x12,
    OP_ALOAD = 0x13,
    OP_ILOAD_0 = 0x14,
    OP_ILOAD_1 = 0x15,
    OP_ILOAD_2 = 0x16,
    OP_ILOAD_3 = 0x17,
    OP_FLOAD_0 = 0x18,
    OP_FLOAD_1 = 0x19,
    OP_FLOAD_2 = 0x1A,
    OP_FLOAD_3 = 0x1B,
    OP_ALOAD_0 = 0x1C,
    OP_ALOAD_1 = 0x1D,
    OP_ALOAD_2 = 0x1E,
    OP_ALOAD_3 = 0x1F,
    OP_ISTORE = 0x20,
    OP_LSTORE = 0x21,
    OP_FSTORE = 0x22,
    OP_ASTORE = 0x23,
    OP_ISTORE_0 = 0x24,
    OP_ISTORE_1 = 0x25,
    OP_ISTORE_2 = 0x26,
    OP_ISTORE_3 = 0x27,
    OP_FSTORE_0 = 0x28,
    OP_FSTORE_1 = 0x29,
    OP_FSTORE_2 = 0x2A,
    OP_FSTORE_3 = 0x2B,
    OP_ASTORE_0 = 0x2C,
    OP_ASTORE_1 = 0x2D,
    OP_ASTORE_2 = 0x2E,
    OP_ASTORE_3 = 0x2F,
    OP_POP = 0x30,
    OP_POP2 = 0x31,
    OP_DUP = 0x32,
    OP_DUP_X1 = 0x33,
    OP_DUP_X2 = 0x34,
    OP_SWAP = 0x35,
    OP_IADD = 0x40,
    OP_ISUB = 0x41,
    OP_IMUL = 0x42,
    OP_IDIV = 0x43,
    OP_IREM = 0x44,
    OP_INEG = 0x45,
    OP_FADD = 0x46,
    OP_FSUB = 0x47,
    OP_FMUL = 0x48,
    OP_FDIV = 0x49,
    OP_FREM = 0x4A,
    OP_FNEG = 0x4B,
    OP_ISHL = 0x50,
    OP_ISHR = 0x51,
    OP_IUSHR = 0x52,
    OP_IAND = 0x53,
    OP_IOR = 0x54,
    OP_IXOR = 0x55,
    OP_IINC = 0x56,
    OP_I2F = 0x60,
    OP_F2I = 0x61,
    OP_I2B = 0x62,
    OP_ICMP = 0x70,
    OP_FCMP = 0x71,
    OP_IFEQ = 0x80,
    OP_IFNE = 0x81,
    OP_IFLT = 0x82,
    OP_IFGE = 0x83,
    OP_IFGT = 0x84,
    OP_IFLE = 0x85,
    OP_IF_ICMPEQ = 0x86,
    OP_IF_ICMPNE = 0x87,
    OP_IF_ICMPLT = 0x88,
    OP_IF_ICMPGE = 0x89,
    OP_IF_ICMPGT = 0x8A,
    OP_IF_ICMPLE = 0x8B,
    OP_GOTO = 0x8C,
    OP_INVOKE_STATIC = 0xA0,
    OP_INVOKE_VIRTUAL = 0xA1,
    OP_INVOKE_SPECIAL = 0xA2,
    OP_RETURN = 0xA3,
    OP_IRETURN = 0xA4,
    OP_FRETURN = 0xA5,
    OP_ARETURN = 0xA6,
    OP_NEW = 0xB0,
    OP_NEWARRAY = 0xB1,
    OP_ARRAYLENGTH = 0xB2,
    OP_GETFIELD = 0xB3,
    OP_PUTFIELD = 0xB4,
    OP_GETSTATIC = 0xB5,
    OP_PUTSTATIC = 0xB6,
    OP_IALOAD = 0xB7,
    OP_FALOAD = 0xB8,
    OP_AALOAD = 0xB9,
    OP_IASTORE = 0xBA,
    OP_FASTORE = 0xBB,
    OP_AASTORE = 0xBC,
    OP_PRINT = 0xF0,
    OP_HALT = 0xFF,
} KiraOpCode;

typedef enum
{
    CONST_UTF8 = 1,
    CONST_INTEGER = 2,
    CONST_FLOAT = 3,
    CONST_STRING = 4,
    CONST_METHODREF = 5,
    CONST_FIELDREF = 6,
    CONST_CLASSREF = 7,
} KiraConstantType;

typedef struct
{
    KiraConstantType type;
    union
    {
        struct { String value; UInt32 length; } utf8;
        Int32 intValue;
        Float32 floatValue;
        UInt16 stringIndex;
        struct { UInt16 classIndex; UInt16 nameIndex; } methodRef;
        struct { UInt16 classIndex; UInt16 nameIndex; } fieldRef;
        UInt16 classIndex;
    } data;
} KiraConstant;

typedef struct
{
    KiraConstant* constants;
    UInt32 count;
    UInt32 capacity;
} KiraConstantPool;

typedef struct
{
    UInt16 nameIndex;
    UInt16 descriptorIndex;
    UInt16 codeOffset;
    UInt16 codeLength;
    UInt8 maxStack;
    UInt8 maxLocals;
    UInt8 paramCount;
    UInt8 flags;
} KiraMethodInfo;

typedef struct
{
    KiraMethodInfo* methods;
    UInt32 count;
    UInt32 capacity;
} KiraMethodTable;

typedef struct
{
    UInt16 nameIndex;
    UInt16 superClassIndex;
    UInt16 fieldCount;
    UInt16 methodCount;
    UInt16 flags;
} KiraClassInfo;

typedef struct
{
    KiraClassInfo* classes;
    UInt32 count;
    UInt32 capacity;
} KiraClassTable;

typedef struct
{
    Byte magic[4];
    UInt16 majorVersion;
    UInt16 minorVersion;
    UInt32 constantPoolSize;
    UInt32 methodTableSize;
    UInt32 classTableSize;
    UInt32 bytecodeSize;
    UInt32 entryPoint;
    UInt32 flags;
} KiraBytecodeHeader;

typedef struct
{
    KiraBytecodeHeader header;
    KiraConstantPool* constantPool;
    KiraMethodTable* methodTable;
    KiraClassTable* classTable;
    UInt8* bytecode;
    UInt32 bytecodeLength;
} KiraProgram;

KiraProgram* kiraLoadProgram(String fileName);
Void kiraFreeProgram(KiraProgram* program);
Void kiraProgramExecute(KiraProgram* program);


KiraConstantPool* kiraConstantPoolCreate();
Void kiraConstantPoolFree(KiraConstantPool* pool);
UInt16 kiraConstantPoolAddInt(KiraConstantPool* pool, Int32 value);
UInt16 kiraConstantPoolAddFloat(KiraConstantPool* pool, Float32 value);
UInt16 kiraConstantPoolAddUTF8(KiraConstantPool* pool, String str);
UInt16 kiraConstantPoolAddString(KiraConstantPool* pool, UInt16 utf8Index);
KiraConstant* kiraConstantPoolGet(KiraConstantPool* pool, UInt16 index);

KiraMethodTable* kiraMethodTableCreate();
Void kiraMethodTableFree(KiraMethodTable* table);
UInt16 kiraMethodTableAdd(KiraMethodTable* table, KiraMethodInfo method);
KiraMethodInfo* kiraMethodTableGet(KiraMethodTable* table, UInt16 index);
KiraMethodInfo* kiraMethodTableFindByName(KiraMethodTable* table, KiraConstantPool* pool, String name);

KiraClassTable* kiraClassTableCreate();
Void kiraClassTableFree(KiraClassTable* table);
UInt16 kiraClassTableAdd(KiraClassTable* table, KiraClassInfo classInfo);
KiraClassInfo* kiraClassTableGet(KiraClassTable* table, UInt16 index);

Void kiraSaveBytecode(KiraProgram* program, String fileName);
KiraProgram* kiraLoadBytecode(String fileName);

#endif

