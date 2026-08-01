
// module "app:main"
// use "app:text"
function main() {
    const name = "  kira  ";
    const trimmed = kira_str_trim(name);
    kira_trace(kira_str_length(trimmed));
    kira_trace(shout(trimmed));
    kira_trace(initials(trimmed));
    kira_trace(kira_str_startsWith(trimmed, "ki"));
    kira_trace(kira_str_substring(trimmed, 0, 2));
    const count = 7;
    kira_trace(kira_num_toInt64(count));
    const seen = kira_set_new();
    seen.add(1);
    seen.add(2);
    seen.add(1);
    kira_trace(seen.size());
    kira_trace(seen.contains(2));
    const undo = kira_stack_new();
    undo.push(10);
    undo.push(20);
    const top = undo.pop();
    kira_trace(top.unwrapOr(0));
    const jobs = kira_queue_new();
    jobs.enqueue(1);
    jobs.enqueue(2);
    const next = jobs.dequeue();
    kira_trace(next.unwrapOr(0));
    const ages = kira_map_new();
    ages.put("ada", 36);
    const found = ages.get("ada");
    kira_trace(found.isSome());
    kira_trace(found.unwrapOr(0));
    const missing = ages.get("nobody");
    kira_trace(missing.unwrapOr(-1));
    const nums = kira_list_new();
    nums.add(3);
    nums.add(4);
    kira_trace(nums.get(1));
    kira_trace(nums.contains(3));
    const absent = kira_none();
    kira_trace(absent.isNone());
    kira_trace(absent.unwrapOr("fallback"));
    const present = kira_some("here");
    kira_trace(present.isSome());
    kira_trace(present.unwrapOr("fallback"));
    kira_assert((nums.size() == 2), "list should hold two entries");
    kira_trace("ok");
}

// module "app:text"
function shout(value) {
    return kira_str_toUpper(value);
}

function initials(value) {
    return kira_str_charAt(value, 0);
}

main();
