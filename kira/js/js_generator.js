/*
 * Kira JS backend -- runtime prelude (mirrors the C facade in c_generator.c).
 *
 * Maps the Kira stdlib surface (kira/core, kira/tuples, kira/collections,
 * kira/result, kira/io, kira/math) onto plain JavaScript. The generated user
 * code sits after this prelude in the same file, so the whole artifact is one
 * self-contained Node script.
 *
 * Type mapping (runtime is untyped -- these are the shapes codegen emits):
 *   Int8..Int64 / Float32 / Float64 / Bool -> JS number / boolean
 *   Str                                    -> JS primitive string
 *   Arr<T>                                 -> JS Array
 *   List / Map / Set / Stack / Queue / Deque -> Kira* classes below
 *   Maybe / Result / Exception             -> Kira* classes below
 *   Tuple0..Tuple9 / Pair                  -> KiraTuple* classes below
 *   User classes                           -> JS classes emitted by codegen
 *   Traits                                 -> erased (duck typing)
 *
 * Memory: GC owns everything. ARC is a C-backend concept and is a no-op here.
 *
 * Print parity: `trace` prints booleans as 1/0 (C %d) and numbers as their
 * decimal representation, so both backends agree on the committed
 * expected.txt for the example ladder.
 */
"use strict";

/* ---- print / trace (C-parity formatting) -------------------------------- */
function kira_format(v) {
  if (typeof v === "boolean") return v ? "1" : "0";
  if (typeof v === "undefined") return "";
  return String(v);
}

function kira_print() {
  let s = "";
  for (let i = 0; i < arguments.length; i++) s += kira_format(arguments[i]);
  process.stdout.write(s);
}

function kira_println() {
  kira_print.apply(null, arguments);
  kira_print("\n");
}

function kira_trace() {
  kira_println.apply(null, arguments);
}

function kira_eprint() {
  let s = "";
  for (let i = 0; i < arguments.length; i++) s += kira_format(arguments[i]);
  process.stderr.write(s);
}

function kira_assert(condition, message) {
  if (!condition) {
    process.stderr.write("kira: assertion failed: " + (message == null ? "" : message) + "\n");
    process.exit(1);
  }
}

/* ---- Str (JS primitive strings) ----------------------------------------- */
function kira_str_length(s) { return s == null ? 0 : s.length; }

function kira_str_isEmpty(s) { return s == null || s.length === 0; }

function kira_str_substring(s, start, end) {
  const n = s == null ? 0 : s.length;
  if (start < 0 || end > n || start > end) throw new Error("kira: Str.substring out of range");
  return s.substring(start, end);
}

function kira_str_charAt(s, index) {
  if (s == null || index < 0 || index >= s.length) throw new Error("kira: Str.charAt out of range");
  return s.charAt(index);
}

function kira_str_contains(s, needle) {
  return s != null && needle != null && s.indexOf(needle) >= 0;
}

function kira_str_startsWith(s, prefix) {
  return s != null && prefix != null && s.startsWith(prefix);
}

function kira_str_endsWith(s, suffix) {
  return s != null && suffix != null && s.endsWith(suffix);
}

/* Split on a delimiter into a Kira List<Str>; mirrors C Str_split. */
function kira_str_split(s, delimiter) {
  const out = kira_list_new();
  if (s == null || delimiter == null || delimiter.length === 0) {
    out.add(s == null ? "" : s);
    return out;
  }
  let cursor = 0;
  for (;;) {
    const hit = s.indexOf(delimiter, cursor);
    if (hit < 0) {
      out.add(s.substring(cursor));
      break;
    }
    out.add(s.substring(cursor, hit));
    cursor = hit + delimiter.length;
  }
  return out;
}

/* Trim only space/tab/newline/CR, exactly like the C backend. */
function kira_str_trim(s) {
  if (s == null) return s;
  const isWs = (c) => c === " " || c === "\t" || c === "\n" || c === "\r";
  let a = 0;
  let b = s.length;
  while (a < b && isWs(s.charAt(a))) a++;
  while (b > a && isWs(s.charAt(b - 1))) b--;
  return s.substring(a, b);
}

/* ASCII-only case mapping, exactly like the C backend. */
function kira_str_toLower(s) {
  if (s == null) return s;
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    out += c >= 65 && c <= 90 ? String.fromCharCode(c + 32) : s.charAt(i);
  }
  return out;
}

function kira_str_toUpper(s) {
  if (s == null) return s;
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    out += c >= 97 && c <= 122 ? String.fromCharCode(c - 32) : s.charAt(i);
  }
  return out;
}

function kira_str_equals(a, b) { return a === b; }

/* djb2, byte-for-byte the same hash as the C backend. */
function kira_str_hashCode(s) {
  if (s == null) return 0;
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = (h * 33 + s.charCodeAt(i)) | 0;
  return h;
}

/* ---- Num (all scalars are JS numbers) ----------------------------------- */
function kira_num_toInt32(v) { return Math.trunc(v); }
function kira_num_toInt64(v) { return Math.trunc(v); }
function kira_num_toFloat32(v) { return Math.fround(v); }
function kira_num_toFloat64(v) { return v; }

/* ---- Maybe / Result / Exception ----------------------------------------- */
class KiraMaybe {
  constructor(present, value) {
    this.present = present;
    this.value = value;
  }
  isSome() { return this.present; }
  isNone() { return !this.present; }
  unwrap() {
    if (!this.present) throw new Error("kira: unwrap() on a None Maybe");
    return this.value;
  }
  unwrapOr(fallback) { return this.present ? this.value : fallback; }
}

function kira_some(v) { return new KiraMaybe(true, v); }
function kira_none() { return new KiraMaybe(false, undefined); }

class KiraResult {
  constructor(ok, value, error) {
    this.ok = ok;
    this.value = value;
    this.error = error;
  }
  isOk() { return this.ok; }
  isErr() { return !this.ok; }
  unwrap() {
    if (!this.ok) throw new Error("kira: unwrap() on an Err Result");
    return this.value;
  }
  unwrapErr() {
    if (this.ok) throw new Error("kira: unwrapErr() on an Ok Result");
    return this.error;
  }
}

function kira_ok(v) { return new KiraResult(true, v, undefined); }
function kira_err(e) { return new KiraResult(false, undefined, e); }

class KiraException {
  constructor(message) {
    this.message = message;
  }
}

/* ---- List --------------------------------------------------------------- */
class KiraList {
  constructor(values) {
    this.values = values == null ? [] : values;
  }
  size() { return this.values.length; }
  isEmpty() { return this.values.length === 0; }
  add(value) { this.values.push(value); }
  addAll(values) {
    for (let i = 0; i < values.length; i++) this.values.push(values[i]);
  }
  get(index) {
    if (index < 0 || index >= this.values.length) throw new Error("kira: List index out of range");
    return this.values[index];
  }
  set(index, value) {
    if (index < 0 || index >= this.values.length) throw new Error("kira: List index out of range");
    this.values[index] = value;
  }
  removeAt(index) {
    if (index < 0 || index >= this.values.length) throw new Error("kira: List index out of range");
    return this.values.splice(index, 1)[0];
  }
  clear() { this.values.length = 0; }
  contains(value) { return this.values.indexOf(value) >= 0; }
  toArr() { return this.values.slice(); }
}

function kira_list_new() { return new KiraList(); }

/* ---- Set (linear membership, like the C backend) ------------------------ */
class KiraSet {
  constructor() { this.values = []; }
  size() { return this.values.length; }
  isEmpty() { return this.values.length === 0; }
  add(value) {
    if (this.contains(value)) return false;
    this.values.push(value);
    return true;
  }
  remove(value) {
    const i = this.values.indexOf(value);
    if (i < 0) return false;
    this.values.splice(i, 1);
    return true;
  }
  contains(value) { return this.values.indexOf(value) >= 0; }
  toArr() { return this.values.slice(); }
  clear() { this.values.length = 0; }
}

function kira_set_new() { return new KiraSet(); }

/* ---- Map (native Map; key equality is SameValueZero) -------------------- */
class KiraMap {
  constructor() { this._m = new globalThis.Map(); }
  size() { return this._m.size; }
  isEmpty() { return this._m.size === 0; }
  put(key, value) { this._m.set(key, value); }
  get(key) { return this._m.has(key) ? kira_some(this._m.get(key)) : kira_none(); }
  remove(key) {
    if (!this._m.has(key)) return kira_none();
    const v = this._m.get(key);
    this._m.delete(key);
    return kira_some(v);
  }
  containsKey(key) { return this._m.has(key); }
  containsValue(value) {
    for (const v of this._m.values()) if (v === value) return true;
    return false;
  }
  keys() { return Array.from(this._m.keys()); }
  valuesArr() { return Array.from(this._m.values()); }
  entries() {
    const out = [];
    for (const [k, v] of this._m) out.push(new KiraTuple2(k, v));
    return out;
  }
  clear() { this._m.clear(); }
}

function kira_map_new() { return new KiraMap(); }

/* ---- Stack / Queue / Deque ---------------------------------------------- */
class KiraStack {
  constructor() { this.values = new KiraList(); }
  size() { return this.values.size(); }
  isEmpty() { return this.values.isEmpty(); }
  push(value) { this.values.add(value); }
  pop() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.removeAt(this.size() - 1));
  }
  peek() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.get(this.size() - 1));
  }
  clear() { this.values.clear(); }
}

function kira_stack_new() { return new KiraStack(); }

class KiraQueue {
  constructor() { this.values = new KiraList(); }
  size() { return this.values.size(); }
  isEmpty() { return this.values.isEmpty(); }
  enqueue(value) { this.values.add(value); }
  dequeue() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.removeAt(0));
  }
  peek() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.get(0));
  }
  clear() { this.values.clear(); }
}

function kira_queue_new() { return new KiraQueue(); }

class KiraDeque {
  constructor() { this.values = new KiraList(); }
  size() { return this.values.size(); }
  isEmpty() { return this.values.isEmpty(); }
  pushFront(value) { this.values.values.unshift(value); }
  pushBack(value) { this.values.add(value); }
  popFront() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.removeAt(0));
  }
  popBack() {
    return this.size() === 0 ? kira_none() : kira_some(this.values.removeAt(this.size() - 1));
  }
  clear() { this.values.clear(); }
}

function kira_deque_new() { return new KiraDeque(); }

/* ---- Tuples ------------------------------------------------------------- */
class KiraTuple0 {
  size() { return 0; }
}

class KiraTuple1 {
  constructor(first) { this.first = first; }
  size() { return 1; }
}

class KiraTuple2 {
  constructor(first, second) {
    this.first = first;
    this.second = second;
  }
  size() { return 2; }
}

class KiraTuple3 {
  constructor(first, second, third) {
    this.first = first;
    this.second = second;
    this.third = third;
  }
  size() { return 3; }
}

class KiraTuple4 {
  constructor(first, second, third, fourth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
  }
  size() { return 4; }
}

class KiraTuple5 {
  constructor(first, second, third, fourth, fifth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
  }
  size() { return 5; }
}

class KiraTuple6 {
  constructor(first, second, third, fourth, fifth, sixth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
    this.sixth = sixth;
  }
  size() { return 6; }
}

class KiraTuple7 {
  constructor(first, second, third, fourth, fifth, sixth, seventh) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
    this.sixth = sixth;
    this.seventh = seventh;
  }
  size() { return 7; }
}

class KiraTuple8 {
  constructor(first, second, third, fourth, fifth, sixth, seventh, eighth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
    this.sixth = sixth;
    this.seventh = seventh;
    this.eighth = eighth;
  }
  size() { return 8; }
}

class KiraTuple9 {
  constructor(first, second, third, fourth, fifth, sixth, seventh, eighth, ninth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
    this.sixth = sixth;
    this.seventh = seventh;
    this.eighth = eighth;
    this.ninth = ninth;
  }
  size() { return 9; }
}

// __KIRA_JS_PRELUDE_END__
