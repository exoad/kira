/*
 * Kira JS backend: runtime prelude (mirrors the C facade in c_generator.c).
 *
 * Maps the Kira stdlib surface (kira/core, kira/tuples, kira/collections,
 * kira/result, kira/io, kira/math) onto plain JavaScript. The generated user
 * code sits after this prelude in the same file, so the whole artifact is one
 * self-contained Node script.
 *
 * Type mapping (runtime is untyped; these are the shapes codegen emits):
 *   Int8..Int64 / Float32 / Float64 / Bool -> JS number / boolean
 *   Str                                    -> JS primitive string
 *   Arr<T>                                 -> JS Array
 *   List / Map / Set / Stack / Queue / Deque -> Kira* classes below
 *   Maybe / Result / Exception             -> Kira* classes below
 *   T0..T10 / Pair                         -> KiraT* classes below
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

/* Interpolation: `"a${x}b"` lowers to kira_cat("a", x, "b"). Every piece
 * goes through kira_format, so a Bool prints 1/0 exactly as trace does. */
function kira_cat() {
  let s = "";
  for (let i = 0; i < arguments.length; i++) s += kira_format(arguments[i]);
  return s;
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

/* The rest of kira:core's Str surface. C and JS trim the four whitespace
 * characters (space, tab, LF, CR); D62's six are on C++ and py today. */
const KIRA_STR_WS = " \t\n\r";

function kira_str_size(s) { return kira_str_length(s); }

function kira_str_at(s, index) {
  if (s == null || index < 0 || index >= s.length) throw new Error("kira: Str.at out of range");
  return s.charAt(index);
}

function kira_str_find(s, needle) {
  const hit = s == null || needle == null ? -1 : s.indexOf(needle);
  return hit < 0 ? kira_none() : kira_some(hit);
}

function kira_str_trimStart(s) {
  if (s == null) return s;
  let a = 0;
  while (a < s.length && KIRA_STR_WS.indexOf(s.charAt(a)) >= 0) a++;
  return s.substring(a);
}

function kira_str_trimEnd(s) {
  if (s == null) return s;
  let b = s.length;
  while (b > 0 && KIRA_STR_WS.indexOf(s.charAt(b - 1)) >= 0) b--;
  return s.substring(0, b);
}

function kira_str_splitWhitespace(s) {
  const out = kira_list_new();
  if (s == null) return out;
  let i = 0;
  while (i < s.length) {
    while (i < s.length && KIRA_STR_WS.indexOf(s.charAt(i)) >= 0) i++;
    const start = i;
    while (i < s.length && KIRA_STR_WS.indexOf(s.charAt(i)) < 0) i++;
    if (i > start) out.add(s.substring(start, i));
  }
  return out;
}

function kira_str_padStart(s, width, fill) {
  s = s == null ? "" : s;
  if (s.length >= width) return s;
  return fill.repeat(width - s.length) + s;
}

function kira_str_padEnd(s, width, fill) {
  s = s == null ? "" : s;
  if (s.length >= width) return s;
  return s + fill.repeat(width - s.length);
}

function kira_str_replace(s, oldValue, newValue) {
  if (s == null) return s;
  if (oldValue === "") {
    const chars = Array.from(s);
    if (chars.length === 0) return newValue;
    return newValue + chars.join(newValue) + newValue;
  }
  return s.split(oldValue).join(newValue);
}

function kira_str_toInt64(s) {
  if (s == null) return kira_none();
  const negative = s.charAt(0) === "-";
  const body = negative || s.charAt(0) === "+" ? s.substring(1) : s;
  if (body.length === 0) return kira_none();
  for (let i = 0; i < body.length; i++) {
    const c = body.charCodeAt(i);
    if (c < 48 || c > 57) return kira_none();
  }
  const digits = body.replace(/^0+/, "");
  if (digits.length > 19) return kira_none();
  let value = BigInt(digits || "0");
  if (negative) value = -value;
  if (value < -9223372036854775808n || value > 9223372036854775807n) return kira_none();
  return kira_some(Number(value));
}

const KIRA_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";

function kira_str_toInt64Radix(s, radix) {
  if (radix < 2 || radix > 36) throw new Error("kira: radix out of range");
  if (s == null) return kira_none();
  const negative = s.charAt(0) === "-";
  const body = negative || s.charAt(0) === "+" ? s.substring(1) : s;
  if (body.length === 0) return kira_none();
  const base = BigInt(radix);
  let value = 0n;
  for (let i = 0; i < body.length; i++) {
    const digit = KIRA_DIGITS.indexOf(body.charAt(i).toLowerCase());
    if (digit < 0 || digit >= radix) return kira_none();
    value = value * base + BigInt(digit);
  }
  if (negative) value = -value;
  if (value < -9223372036854775808n || value > 9223372036854775807n) return kira_none();
  return kira_some(Number(value));
}

const KIRA_DECIMAL = /^[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/;
const KIRA_SPECIAL = /^[+-]?(?:inf|infinity|nan(?:\([0-9A-Za-z_]*\))?)$/i;

function kira_str_toFloat64(s) {
  if (s == null) return kira_none();
  if (KIRA_DECIMAL.test(s)) {
    const v = Number(s);
    if (!Number.isFinite(v)) return kira_none();
    if (v === 0) {
      const mantissa = s.toLowerCase().split("e")[0].replace(/[+\-.0]/g, "");
      if (mantissa.length > 0) return kira_none();
    }
    return kira_some(v);
  }
  if (KIRA_SPECIAL.test(s)) {
    const negative = s.charAt(0) === "-";
    const body = s.replace(/^[+-]/, "").toLowerCase();
    if (body.startsWith("nan")) return kira_some(NaN);
    return kira_some(negative ? -Infinity : Infinity);
  }
  return kira_none();
}

/* Str.bytes (D54): the text's UTF-8, as a new List<UInt8>. */
function kira_str_bytes(s) {
  const encoded = new TextEncoder().encode(s == null ? "" : s);
  return new KiraList(Array.from(encoded));
}

/* ---- Num (all scalars are JS numbers) ----------------------------------- */
function kira_num_toInt32(v) { return Math.trunc(v); }
function kira_num_toInt64(v) { return Math.trunc(v); }
function kira_num_toFloat32(v) { return Math.fround(v); }
function kira_num_toFloat64(v) { return v; }

/* IntNum.toHex (D51): lowercase, no prefix; negatives are "-" plus the
 * magnitude's digits ("-ff"), as Python's %x writes them. */
function kira_num_toHex(v) {
  const n = Math.trunc(v);
  return n < 0 ? "-" + Math.abs(n).toString(16) : n.toString(16);
}

/* A finite double as sign * mantissa * 2^exponent, exactly. */
function kira_double_parts(v) {
  const view = new DataView(new ArrayBuffer(8));
  view.setFloat64(0, v);
  const hi = view.getUint32(0);
  const lo = view.getUint32(4);
  const sign = hi >>> 31 ? -1 : 1;
  const exponentBits = (hi >>> 20) & 0x7ff;
  const mantissa = BigInt(hi & 0xfffff) * 4294967296n + BigInt(lo);
  if (exponentBits === 0) return { sign, mantissa, exponent: -1074 };
  return { sign, mantissa: mantissa + 4503599627370496n, exponent: exponentBits - 1075 };
}

/* FloatNum.fixed (D50): `places` decimals clamped to 0..9, rounded as
 * printf's %.*f -- on the exact binary value, ties to even. Intl would
 * round the shortest decimal spelling instead (2.675 -> 2.68), so the
 * scaled value is computed in BigInt and rounded here. NaN is "nan". */
function kira_num_fixed(v, places) {
  if (Number.isNaN(v)) return "nan";
  if (!Number.isFinite(v)) return v < 0 ? "-inf" : "inf";
  const p = places < 0 ? 0 : places > 9 ? 9 : places;
  const parts = kira_double_parts(v);
  let num = parts.mantissa;
  let den = 1n;
  if (parts.exponent >= 0) num <<= BigInt(parts.exponent);
  else den <<= BigInt(-parts.exponent);
  num *= 10n ** BigInt(p);
  let q = num / den;
  const rem = num % den;
  if (rem * 2n > den || (rem * 2n === den && q % 2n === 1n)) q += 1n;
  let digits = q.toString();
  if (p > 0) {
    while (digits.length <= p) digits = "0" + digits;
    digits = digits.slice(0, digits.length - p) + "." + digits.slice(digits.length - p);
  }
  return (parts.sign < 0 ? "-" : "") + digits;
}

/* ---- Char (a one-character JS string) ----------------------------------- */
function kira_char_isDigit(c) { return c != null && c >= "0" && c <= "9"; }
function kira_char_isWhitespace(c) { return c != null && (c === " " || (c >= "\t" && c <= "\r")); }
function kira_char_isLetter(c) {
  return c != null && ((c >= "A" && c <= "Z") || (c >= "a" && c <= "z"));
}

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

/* List.sort / minOrNull / maxOrNull (D61): Kotlin's order, so -0.0 before
 * 0.0 and NaN last. */
function kira_compare_values(a, b) {
  if (typeof a === "number" && typeof b === "number") {
    if (Number.isNaN(a)) return Number.isNaN(b) ? 0 : 1;
    if (Number.isNaN(b)) return -1;
    if (a === 0 && b === 0) {
      const an = Object.is(a, -0);
      const bn = Object.is(b, -0);
      if (an !== bn) return an ? -1 : 1;
      return 0;
    }
    return a < b ? -1 : a > b ? 1 : 0;
  }
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
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

KiraList.prototype.joinToString = function (separator) {
  return this.values.join(separator);
};
KiraList.prototype.sort = function () {
  this.values.sort(kira_compare_values);
};
KiraList.prototype.sum = function () {
  let total = 0;
  for (const v of this.values) total += v;
  return total;
};
KiraList.prototype.minOrNull = function () {
  if (this.values.length === 0) return kira_none();
  let best = this.values[0];
  for (const v of this.values) {
    if (typeof v === "number" && Number.isNaN(v)) return kira_some(v);
    if (kira_compare_values(v, best) < 0) best = v;
  }
  return kira_some(best);
};
KiraList.prototype.maxOrNull = function () {
  if (this.values.length === 0) return kira_none();
  let best = this.values[0];
  for (const v of this.values) {
    if (typeof v === "number" && Number.isNaN(v)) return kira_some(v);
    if (kira_compare_values(best, v) < 0) best = v;
  }
  return kira_some(best);
};

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
    for (const [k, v] of this._m) out.push(new KiraT2(k, v));
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
class KiraT0 {
  size() { return 0; }
}

class KiraT1 {
  constructor(first) { this.first = first; }
  size() { return 1; }
}

class KiraT2 {
  constructor(first, second) {
    this.first = first;
    this.second = second;
  }
  size() { return 2; }
}

class KiraT3 {
  constructor(first, second, third) {
    this.first = first;
    this.second = second;
    this.third = third;
  }
  size() { return 3; }
}

class KiraT4 {
  constructor(first, second, third, fourth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
  }
  size() { return 4; }
}

class KiraT5 {
  constructor(first, second, third, fourth, fifth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
  }
  size() { return 5; }
}

class KiraT6 {
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

class KiraT7 {
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

class KiraT8 {
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

class KiraT9 {
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

class KiraT10 {
  constructor(first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth) {
    this.first = first;
    this.second = second;
    this.third = third;
    this.fourth = fourth;
    this.fifth = fifth;
    this.sixth = sixth;
    this.seventh = seventh;
    this.eighth = eighth;
    this.ninth = ninth;
    this.tenth = tenth;
  }
  size() { return 10; }
}

// __KIRA_JS_PRELUDE_END__
