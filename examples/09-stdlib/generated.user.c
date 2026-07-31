
Int32 main(Void);
Str shout(Str value);
Str initials(Str value);

/* module app:main */
/* use app:text */
Int32 main(Void)
{
    Str name = "  kira  ";
    Str trimmed = Str_trim(name);
    print("%d\n", Str_length(trimmed));
    print("%s\n", shout(trimmed));
    print("%s\n", initials(trimmed));
    print("%d\n", Str_startsWith(trimmed, "ki"));
    print("%s\n", Str_substring(trimmed, 0, 2));
    Int32 count = 7;
    print("%lld\n", (long long)(((Int64)(count))));
    Set seen = Set_new();
    Set_add(&seen, KIRA_SLOT(1));
    Set_add(&seen, KIRA_SLOT(2));
    Set_add(&seen, KIRA_SLOT(1));
    print("%d\n", Set_size(&seen));
    print("%d\n", Set_contains(&seen, KIRA_SLOT(2)));
    Stack undo = Stack_new();
    Stack_push(&undo, KIRA_SLOT(10));
    Stack_push(&undo, KIRA_SLOT(20));
    Maybe top = Stack_pop(&undo);
    print("%d\n", KIRA_UNSLOT(Int32, Maybe_unwrapOr(&top, KIRA_SLOT(0))));
    Queue jobs = Queue_new();
    Queue_enqueue(&jobs, KIRA_SLOT(1));
    Queue_enqueue(&jobs, KIRA_SLOT(2));
    Maybe next = Queue_dequeue(&jobs);
    print("%d\n", KIRA_UNSLOT(Int32, Maybe_unwrapOr(&next, KIRA_SLOT(0))));
    Map ages = Map_new_s();
    Map_put(&ages, KIRA_SLOT_PTR("ada"), KIRA_SLOT(36));
    Maybe found = Map_get(&ages, KIRA_SLOT_PTR("ada"));
    print("%d\n", Maybe_isSome(&found));
    print("%d\n", KIRA_UNSLOT(Int32, Maybe_unwrapOr(&found, KIRA_SLOT(0))));
    Maybe missing = Map_get(&ages, KIRA_SLOT_PTR("nobody"));
    print("%d\n", KIRA_UNSLOT(Int32, Maybe_unwrapOr(&missing, KIRA_SLOT(-1))));
    List nums = List_new();
    List_add(&nums, KIRA_SLOT(3));
    List_add(&nums, KIRA_SLOT(4));
    print("%d\n", KIRA_UNSLOT(Int32, List_get(&nums, 1)));
    print("%d\n", List_contains(&nums, KIRA_SLOT(3)));
    kira_assert((List_size(&nums) == 2), "list should hold two entries");
    print("%s\n", "ok");
    return 0;
}
/* module app:text */
Str shout(Str value)
{
    return Str_toUpper(value);
}
Str initials(Str value)
{
    return Str_charAt(value, 0);
}
