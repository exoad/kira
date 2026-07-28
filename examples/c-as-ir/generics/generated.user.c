
typedef struct Box_Int32 Box_Int32;

struct Box_Int32
{
    Int32 value;
};

typedef enum Phase
{
    PHASE_READY,
    PHASE_DONE
} Phase;

Int32 id_Int32(Int32 value);
Int32 main(Void);

Int32 id_Int32(Int32 value)
{
    return value;
}

/* module app:box */
/* module app:main */
/* use app:box */
/* use app:status */
Int32 main(Void)
{
    Phase phase = PHASE_READY;
    Box_Int32 wrapped = (Box_Int32) { 42 };
    Int32 value = id_Int32(wrapped.value);
    if((phase == PHASE_READY))
    {
        print("%d\n", value);
    }
    return 0;
}
/* module app:status */
