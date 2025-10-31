#include "kira_runtime.h"

Int32 main(Void)
{
    KiraStringTable* strings = kiraStringTable();
    kiraFormIR("program.k", (KiraInstruction[]) {
#define LOAD_FLOAT(dest, value) (KiraInstruction) { OP_LOAD_FLOAT, dest, 0, 0, value },
#define LOAD_WORD(dest, value) (KiraInstruction) { OP_LOAD_WORD, dest, 0, 0, value },
#define LOAD_BYTE(dest, value) (KiraInstruction) { OP_LOAD_BYTE, dest, 0, 0, value },
#define IADD(dest, src1, src2) (KiraInstruction) { OP_IADD, dest, src1, src2, 0 },
#define CALL(returnRegister, nameOffset) (KiraInstruction) { OP_CALL, returnRegister, 0, 0, nameOffset },
#define RETURN(src) (KiraInstruction) { OP_RETURN, 0, src, 0, 0 },
#define JUMP(address) (KiraInstruction) { OP_JUMP, 0, 0, 0, address },
#define SYSOUT(src) (KiraInstruction) { SYSOUT, 0, src, 0, 0 },
#define SYSOUT_CHAR(src) {KiraInstruction} { SYSOUT_CHAR, 0, src, 0, 0 },
#define HALT() (KiraInstruction) { OP_HALT, 0, 0, 0, 0 },
#define ICMP_LT(src1, src2) (KiraInstruction) { OP_ICMP_LT, 0, src1, src2, 0 },
#define ICMP_GT(src1, src2) (KiraInstruction) { OP_ICMP_GT, 0, src1, src2, 0 },
#define JMP_ZERO(location) (KiraInstruction) { OP_JMP_ZERO, 0, 0, 0, location },
#define JMP_LT(location) (KiraInstruction) { OP_JMP_LT, 0, 0, 0, location },
#include "test.kir"
    }, 4, strings);
    KiraProgram* program = kiraProgram("program.k");
    kiraProgramExecute(program);
    return 0;
}
