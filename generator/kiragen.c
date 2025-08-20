#include "kira_runtime.h"

Int32 main()
{
    KiraStringTable* strings = kiraStringTable();
    kiraFormIR("program.k", (KiraInstruction[]) {
        LOAD_INT(r1, 2),
        LOAD_INT(r2, 1),
        ICMP_GT(r1, r2),
        JMP_LT(6),
        SYSOUT(r1),
        HALT(),
        LOAD_INT(rA, 69),
        SYSOUT(rA),
        HALT(),
    }, 4, strings);
    KiraProgram* program = kiraProgram("program.k");
    kiraProgramExecute(program);
    return 0;
}