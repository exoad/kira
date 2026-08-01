
typedef struct Box_Int32 Box_Int32;

struct Box_Int32
{
    Int32 value;
};

simple Box_Int32* Box_Int32_new(Int32 value)
{
    Box_Int32* self = (Box_Int32*)kira_rc_alloc_with(sizeof(Box_Int32), null);
    self->value = value;
    return self;
}

typedef enum BuildStatus
{
    BUILD_STATUS_READY,
    BUILD_STATUS_RUNNING,
    BUILD_STATUS_DONE
} BuildStatus;

Int32 id_Int32(Int32 value);
Int32 main(Void);

Int32 id_Int32(Int32 value)
{
    return value;
}

/* module app:box */
/* module app:main */
/* use app:status */
/* use app:box */
Int32 main(Void)
{
    BuildStatus state = BUILD_STATUS_READY;
    Box_Int32* wrapped = Box_Int32_new(7);
    Int32 value = id_Int32(wrapped->value);
    if((state == BUILD_STATUS_READY))
    {
        print("%d\n", value);
    }
    kira_rc_release(wrapped);
    return 0;
}
/* module app:status */
