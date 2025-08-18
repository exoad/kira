#include "kira_ir.h"

Int32 main()
{
    const KiraStringTable* strings = kiraStringTable();
    kiraFormIR("program.k", (KiraInstruction[]) {
        LOAD_INT(1, 0, 0, 2),
        LOAD_INT(2, 0, 0, 3),
        IADD(3, 1, 2, 0),
        SYSOUT(3),
        HALT()
    }, 4, strings);
    KiraProgram* program = kiraProgram("program.k");
    kiraProgramExecute(program);
    return 0;
}