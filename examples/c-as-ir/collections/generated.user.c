
Int32 main(Void);

/* module app:main */
Int32 main(Void)
{
    Arr numbers = Arr_i32((Int32[]){ 1, 2, 3 }, 3);
    Int32 head = Arr_get_i32(numbers, 0);
    Map bag = Map_new();
    if(Map_isEmpty(&bag))
    {
        print("%d\n", head);
    }
    return 0;
}
