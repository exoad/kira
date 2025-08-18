#include "kira_ir.h"

Int32 main(Void)
{
    const KiraStringTable* strings = kiraStringTable();
    KiraInstruction instructions[] = {
        {OP_FUNC_DEF, 0, 2, 0, kiraStringTableAdd(strings, "add") },
        {OP_PARAM   , 0, 1, 0, 0                                  },
        {OP_PARAM   , 1, 2, 0, 0                                  },
        {OP_ADD     , 3, 1, 2, 0                                  },
        {OP_RETURN  , 0, 3, 0, 0                                  },
        {OP_FUNC_END, 0, 0, 0, 0                                  },
        {OP_FUNC_DEF, 0, 0, 0, kiraStringTableAdd(strings, "main")},
        {OP_LOAD_INT, 1, 0, 0, 5                                  },
        {OP_LOAD_INT, 2, 0, 0, 3                                  },
        {OP_ARG     , 0, 1, 0, 0                                  },
        {OP_ARG     , 1, 2, 0, 0                                  },
        {OP_CALL    , 3, 2, 0, kiraStringTableAdd(strings, "add") },
        {OP_RETURN  , 0, 3, 0, 0                                  },
        {OP_FUNC_END, 0, 0, 0, 0                                  }
    };
    kiraFormIR("program.k", instructions, 14, strings);
    KiraProgram* program = kiraProgram("program.k");
    kiraProgramExecute(program);
    return 0;
}